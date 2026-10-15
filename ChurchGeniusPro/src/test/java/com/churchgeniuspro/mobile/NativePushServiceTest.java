package com.churchgeniuspro.mobile;

import com.churchgeniuspro.hibernate.PushSubscription;
import com.churchgeniuspro.service.NativePushService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mobile Phase 5: the FCM dispatcher — OAuth JWT-bearer exchange, message shape,
 * token caching, and the "token no longer registered" signal that deactivates a row.
 * Google is replaced by a local HTTP stub; nothing leaves the machine.
 */
@DisplayName("NativePushService (FCM HTTP v1 for Android, APNs for iOS)")
class NativePushServiceTest {

    private static final ObjectMapper M = new ObjectMapper();

    HttpServer server;
    String base;
    KeyPair keyPair;
    NativePushService svc;

    final List<String> apnsBodies = new ArrayList<>();
    final List<java.util.Map<String, String>> apnsHeaders = new ArrayList<>();
    final List<String> apnsPaths = new ArrayList<>();
    final AtomicInteger apnsStatus = new AtomicInteger(200);
    volatile String apnsResponse = "";
    final AtomicInteger apnsExpiredFirst = new AtomicInteger(0);
    KeyPair apnsKeyPair;

    final List<String> fcmBodies = new ArrayList<>();
    final List<String> fcmAuth   = new ArrayList<>();
    final List<String> tokenForms = new ArrayList<>();
    final AtomicInteger fcmStatus = new AtomicInteger(200);
    volatile String fcmResponse = "{\"name\":\"projects/p/messages/1\"}";
    /** When > 0 the first N FCM calls answer 401 (expired access token). */
    final AtomicInteger unauthorizedFirst = new AtomicInteger(0);

    @BeforeEach
    void start() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keyPair = g.generateKeyPair();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", ex -> {
            tokenForms.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = ("{\"access_token\":\"AT-" + tokenForms.size() + "\",\"expires_in\":3600,\"token_type\":\"Bearer\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.createContext("/v1/projects/", ex -> {
            fcmBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            fcmAuth.add(ex.getRequestHeaders().getFirst("Authorization"));
            int status = fcmStatus.get();
            if (unauthorizedFirst.get() > 0) { unauthorizedFirst.decrementAndGet(); status = 401; }
            byte[] out = (status == 401 ? "{\"error\":{\"status\":\"UNAUTHENTICATED\"}}" : fcmResponse).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.createContext("/3/device/", ex -> {
            apnsPaths.add(ex.getRequestURI().getPath());
            apnsBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            java.util.Map<String, String> h = new java.util.HashMap<>();
            for (String k : new String[] {"Authorization", "Apns-Topic", "Apns-Push-Type", "Apns-Priority", "Apns-Collapse-Id"}) {
                h.put(k.toLowerCase(), ex.getRequestHeaders().getFirst(k));
            }
            apnsHeaders.add(h);
            int status = apnsStatus.get();
            String body = apnsResponse;
            if (apnsExpiredFirst.get() > 0) { apnsExpiredFirst.decrementAndGet(); status = 403; body = "{\"reason\":\"ExpiredProviderToken\"}"; }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            try (OutputStream os = ex.getResponseBody()) { if (out.length > 0) os.write(out); }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();

        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        apnsKeyPair = ec.generateKeyPair();

        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String sa = M.writeValueAsString(Map.of(
                "type", "service_account",
                "project_id", "cgp-test",
                "client_email", "fcm@cgp-test.iam.gserviceaccount.com",
                "private_key", pem,
                "token_uri", base + "/token"));

        svc = new NativePushService();
        svc.setFcmBaseUrl(base);
        svc.configure(sa);
        svc.setEnabledForTest(true);
        String p8 = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(apnsKeyPair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        svc.configureApns(p8, "KEYID12345", "TEAMID1234", "com.churchgeniuspro.app", true);
        svc.setApnsBaseUrl(base);
    }

    @AfterEach
    void stop() { server.stop(0); }

    private static PushSubscription sub(String token, String platform) {
        PushSubscription s = new PushSubscription();
        s.setId(42L); s.setEndpoint(token); s.setPlatform(platform);
        s.setP256dh("-"); s.setAuth("-"); s.setUserKey("USR-1"); s.setUserType("staff");
        return s;
    }

    @Test
    @DisplayName("the OAuth assertion is an RS256 JWT for the service account with the FCM scope")
    void jwt() throws Exception {
        String jwt = svc.signedJwt(Instant.ofEpochSecond(1_800_000_000L));
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        JsonNode header = M.readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode claims = M.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(header.get("alg").asText()).isEqualTo("RS256");
        assertThat(claims.get("iss").asText()).isEqualTo("fcm@cgp-test.iam.gserviceaccount.com");
        assertThat(claims.get("scope").asText()).isEqualTo(NativePushService.SCOPE);
        assertThat(claims.get("aud").asText()).isEqualTo(base + "/token");
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(3600);
        Signature v = Signature.getInstance("SHA256withRSA");
        v.initVerify(keyPair.getPublic());
        v.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(v.verify(Base64.getUrlDecoder().decode(parts[2]))).as("signature verifies with the public key").isTrue();
    }

    @Test
    @DisplayName("send: bearer token from the token endpoint, v1 message with notification + data + android/apns blocks")
    void sendShape() throws Exception {
        NativePushService.Result r = svc.send(sub("tok-android-1", "android"), "Meeting reminder", "Board meeting at 7pm", "/events?id=9", "evt-9");
        assertThat(r).isEqualTo(NativePushService.Result.SENT);
        assertThat(tokenForms).hasSize(1);
        assertThat(tokenForms.get(0)).contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer").contains("assertion=");
        assertThat(fcmAuth).containsExactly("Bearer AT-1");
        JsonNode msg = M.readTree(fcmBodies.get(0)).get("message");
        assertThat(msg.get("token").asText()).isEqualTo("tok-android-1");
        assertThat(msg.get("notification").get("title").asText()).isEqualTo("Meeting reminder");
        assertThat(msg.get("notification").get("body").asText()).isEqualTo("Board meeting at 7pm");
        assertThat(msg.get("data").get("url").asText()).isEqualTo("/events?id=9");
        assertThat(msg.get("data").get("tag").asText()).isEqualTo("evt-9");
        assertThat(msg.get("android").get("priority").asText()).isEqualTo("high");
        assertThat(msg.get("android").get("notification").get("channel_id").asText()).isEqualTo("cgp_default");
        assertThat(msg.get("android").get("notification").get("tag").asText()).isEqualTo("evt-9");
        assertThat(msg.get("apns").get("payload").get("aps").get("sound").asText()).isEqualTo("default");
        assertThat(msg.get("apns").get("payload").get("aps").get("thread-id").asText()).isEqualTo("evt-9");
        assertThat(msg.get("apns").get("headers").get("apns-priority").asText()).isEqualTo("10");
    }

    @Test
    @DisplayName("the access token is cached: two sends, one token exchange")
    void tokenCached() throws Exception {
        svc.send(sub("t1", "android"), "a", "b", null, null);
        svc.send(sub("t2", "android"), "a", "b", null, null);
        assertThat(tokenForms).hasSize(1);
        assertThat(fcmAuth).containsExactly("Bearer AT-1", "Bearer AT-1");
        JsonNode msg = M.readTree(fcmBodies.get(0));
        assertThat(msg.get("message").get("data").get("url").asText()).isEqualTo("/");
        assertThat(msg.get("message").get("data").get("tag").asText()).isEqualTo("cgp-push");
    }

    @Test
    @DisplayName("401 from FCM refreshes the token once and retries")
    void refreshOn401() {
        unauthorizedFirst.set(1);
        NativePushService.Result r = svc.send(sub("t1", "android"), "a", "b", null, null);
        assertThat(r).isEqualTo(NativePushService.Result.SENT);
        assertThat(tokenForms).hasSize(2);
        assertThat(fcmAuth).containsExactly("Bearer AT-1", "Bearer AT-2");
    }

    @Test
    @DisplayName("404 / UNREGISTERED → UNREGISTERED (caller deactivates the row); other errors → FAILED")
    void unregistered() {
        fcmStatus.set(404);
        fcmResponse = "{\"error\":{\"status\":\"NOT_FOUND\",\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}";
        assertThat(svc.send(sub("dead", "android"), "a", "b", null, null)).isEqualTo(NativePushService.Result.UNREGISTERED);

        fcmStatus.set(400);
        fcmResponse = "{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"message\":\"The registration token is not a valid FCM registration token\"}}";
        assertThat(svc.send(sub("junk", "android"), "a", "b", null, null)).isEqualTo(NativePushService.Result.UNREGISTERED);

        fcmStatus.set(500);
        fcmResponse = "{\"error\":{\"status\":\"INTERNAL\"}}";
        assertThat(svc.send(sub("ok", "android"), "a", "b", null, null)).isEqualTo(NativePushService.Result.FAILED);
    }

    @Test
    @DisplayName("not configured → disabled, sends are no-ops and nothing is contacted")
    void disabled() {
        NativePushService off = new NativePushService();
        off.init();
        assertThat(off.isEnabled()).isFalse();
        assertThat(off.send(sub("t", "android"), "a", "b", null, null)).isEqualTo(NativePushService.Result.DISABLED);
        assertThat(off.send(sub("t", "ios"), "a", "b", null, null)).isEqualTo(NativePushService.Result.DISABLED);
        assertThat(fcmBodies).isEmpty();
        assertThat(apnsBodies).isEmpty();
    }

    // ── iOS / APNs ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("APNs provider token: ES256 JWT with kid = key id and iss = team id, verifiable with the public key")
    void apnsJwt() throws Exception {
        String jwt = svc.signedApnsJwt(Instant.ofEpochSecond(1_800_000_000L));
        String[] parts = jwt.split("\\.");
        JsonNode header = M.readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode claims = M.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(header.get("alg").asText()).isEqualTo("ES256");
        assertThat(header.get("kid").asText()).isEqualTo("KEYID12345");
        assertThat(claims.get("iss").asText()).isEqualTo("TEAMID1234");
        assertThat(claims.get("iat").asLong()).isEqualTo(1_800_000_000L);
        byte[] rawSig = Base64.getUrlDecoder().decode(parts[2]);
        assertThat(rawSig).as("raw R||S, not DER").hasSize(64);
        Signature v = Signature.getInstance("SHA256withECDSAinP1363Format");
        v.initVerify(apnsKeyPair.getPublic());
        v.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(v.verify(rawSig)).isTrue();
    }

    @Test
    @DisplayName("iOS rows go to APNs: /3/device/<token>, topic = bundle id, alert push, collapse id = tag, aps + url payload")
    void apnsSend() throws Exception {
        NativePushService.Result r = svc.send(sub("abcdef0123456789", "ios"), "Prayer request", "New request from Sam", "/followups?tab=prayer", "prayer-12");
        assertThat(r).isEqualTo(NativePushService.Result.SENT);
        assertThat(fcmBodies).as("nothing to FCM").isEmpty();
        assertThat(apnsPaths).containsExactly("/3/device/abcdef0123456789");
        java.util.Map<String, String> h = apnsHeaders.get(0);
        assertThat(h.get("authorization")).startsWith("bearer ");
        assertThat(h.get("apns-topic")).isEqualTo("com.churchgeniuspro.app");
        assertThat(h.get("apns-push-type")).isEqualTo("alert");
        assertThat(h.get("apns-priority")).isEqualTo("10");
        assertThat(h.get("apns-collapse-id")).isEqualTo("prayer-12");
        JsonNode p = M.readTree(apnsBodies.get(0));
        assertThat(p.get("aps").get("alert").get("title").asText()).isEqualTo("Prayer request");
        assertThat(p.get("aps").get("alert").get("body").asText()).isEqualTo("New request from Sam");
        assertThat(p.get("aps").get("sound").asText()).isEqualTo("default");
        assertThat(p.get("aps").get("thread-id").asText()).isEqualTo("prayer-12");
        assertThat(p.get("url").asText()).isEqualTo("/followups?tab=prayer");
        assertThat(p.get("tag").asText()).isEqualTo("prayer-12");
    }

    @Test
    @DisplayName("APNs: the provider token is reused across sends; ExpiredProviderToken → re-sign once and retry")
    void apnsTokenReuseAndRefresh() {
        svc.send(sub("t1", "ios"), "a", "b", null, null);
        svc.send(sub("t2", "ios"), "a", "b", null, null);
        assertThat(apnsHeaders.get(0).get("authorization")).isEqualTo(apnsHeaders.get(1).get("authorization"));

        apnsExpiredFirst.set(1);
        assertThat(svc.send(sub("t3", "ios"), "a", "b", null, null)).isEqualTo(NativePushService.Result.SENT);
        assertThat(apnsPaths).hasSize(4);
        assertThat(apnsHeaders.get(3).get("authorization")).isNotEqualTo(apnsHeaders.get(2).get("authorization"));
    }

    @Test
    @DisplayName("APNs: 410 / BadDeviceToken → UNREGISTERED; other failures → FAILED")
    void apnsUnregistered() {
        apnsStatus.set(410); apnsResponse = "{\"reason\":\"Unregistered\",\"timestamp\":1}";
        assertThat(svc.send(sub("gone", "ios"), "a", "b", null, null)).isEqualTo(NativePushService.Result.UNREGISTERED);
        apnsStatus.set(400); apnsResponse = "{\"reason\":\"BadDeviceToken\"}";
        assertThat(svc.send(sub("bad", "ios"), "a", "b", null, null)).isEqualTo(NativePushService.Result.UNREGISTERED);
        apnsStatus.set(503); apnsResponse = "{\"reason\":\"ServiceUnavailable\"}";
        assertThat(svc.send(sub("ok", "ios"), "a", "b", null, null)).isEqualTo(NativePushService.Result.FAILED);
    }

    @Test
    @DisplayName("only one platform configured: the other returns DISABLED and the row is kept")
    void onePlatformOnly() throws Exception {
        NativePushService fcmOnly = new NativePushService();
        fcmOnly.setFcmBaseUrl(base);
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----";
        fcmOnly.configure(M.writeValueAsString(Map.of("project_id", "p", "client_email", "e@p", "private_key", pem, "token_uri", base + "/token")));
        fcmOnly.setEnabledForTest(true);
        assertThat(fcmOnly.isEnabled()).isTrue();
        assertThat(fcmOnly.isApnsEnabled()).isFalse();
        assertThat(fcmOnly.send(sub("i", "ios"), "a", "b", null, null)).isEqualTo(NativePushService.Result.DISABLED);
        assertThat(fcmOnly.send(sub("a", "android"), "a", "b", null, null)).isEqualTo(NativePushService.Result.SENT);
    }
}
