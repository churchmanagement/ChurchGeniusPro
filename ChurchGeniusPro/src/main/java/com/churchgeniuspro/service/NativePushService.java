package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.PushSubscription;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mobile Phase 5 — delivers push notifications to the ChurchGeniusPro app.
 * <ul>
 *   <li><b>Android</b> → Firebase Cloud Messaging HTTP v1 (what the Capacitor push plugin
 *       registers with on Android). Credential: a Firebase service-account JSON (Project
 *       settings → Service accounts → Generate new private key), by file path or inline:
 *       <pre>
 *   push.fcm.service-account-file=/etc/cgp/firebase-sa.json    (PUSH_FCM_SERVICE_ACCOUNT_FILE)
 *   push.fcm.service-account-json={"type":"service_account",…}  (PUSH_FCM_SERVICE_ACCOUNT_JSON)</pre>
 *       The key only signs the OAuth assertion Google exchanges for a short-lived access
 *       token, cached until a minute before it expires.</li>
 *   <li><b>iOS</b> → APNs directly (HTTP/2, token-based auth) — no Firebase SDK in the iOS
 *       app. Credential: an APNs auth key (.p8) from the Apple Developer portal:
 *       <pre>
 *   push.apns.key-file=/etc/cgp/AuthKey_ABC123DEFG.p8   (PUSH_APNS_KEY_FILE)   or push.apns.key-pem inline
 *   push.apns.key-id=ABC123DEFG                          (PUSH_APNS_KEY_ID)
 *   push.apns.team-id=TEAMID1234                         (PUSH_APNS_TEAM_ID)
 *   push.apns.bundle-id=com.churchgeniuspro.app
 *   push.apns.production=true                            (false → sandbox gateway, for TestFlight/dev builds)</pre>
 *       The ES256 provider token is cached for 50 minutes (Apple accepts up to 60).</li>
 * </ul>
 * Either transport can be configured alone; a platform without credentials is skipped and
 * logged once at startup, exactly as Web Push is skipped without VAPID keys.
 *
 * <p>Delivery result semantics mirror the Web Push path: {@link Result#UNREGISTERED}
 * (token no longer valid — app uninstalled, data cleared, token rotated) is reported so
 * the caller deactivates the row; any other failure is logged and the row is kept.
 *
 * <p>No Google/Apple SDK is pulled in: both flows are a signed JWT plus one HTTPS call
 * ({@code java.security} + {@code java.net.http}), which keeps the dependency surface and
 * the build unchanged.
 */
@Service
public class NativePushService {

    private static final Logger log = LoggerFactory.getLogger(NativePushService.class);

    public enum Result { SENT, UNREGISTERED, FAILED, DISABLED }

    public static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    @Value("${push.fcm.service-account-file:}")
    private String serviceAccountFile;

    @Value("${push.fcm.service-account-json:}")
    private String serviceAccountJson;

    /** Base URL of FCM (overridable so tests can point at a local stub). */
    @Value("${push.fcm.base-url:https://fcm.googleapis.com}")
    private String fcmBaseUrl = "https://fcm.googleapis.com";

    /** Android notification channel the app creates (see the mobile project's MainActivity). */
    @Value("${push.fcm.android-channel:cgp_default}")
    private String androidChannel = "cgp_default";

    // ── APNs (iOS) ──
    @Value("${push.apns.key-file:}")   private String apnsKeyFile;
    @Value("${push.apns.key-pem:}")    private String apnsKeyPem;
    @Value("${push.apns.key-id:}")     private String apnsKeyId;
    @Value("${push.apns.team-id:}")    private String apnsTeamId;
    @Value("${push.apns.bundle-id:com.churchgeniuspro.app}") private String apnsBundleId = "com.churchgeniuspro.app";
    @Value("${push.apns.production:true}") private boolean apnsProduction = true;
    /** Overridable so tests can point at a local stub. */
    @Value("${push.apns.base-url:}")   private String apnsBaseUrl;

    private PrivateKey apnsKey;
    private boolean apnsEnabled;
    private volatile String cachedApnsJwt;
    private volatile Instant cachedApnsJwtIssued = Instant.EPOCH;

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private String projectId;
    private String clientEmail;
    private String tokenUri;
    private PrivateKey privateKey;
    /** FCM configured. */
    private boolean enabled;

    private volatile String cachedAccessToken;
    private volatile Instant cachedAccessTokenExpiry = Instant.EPOCH;

    @PostConstruct
    public void init() {
        String json = null;
        try {
            if (serviceAccountJson != null && !serviceAccountJson.isBlank()) {
                json = serviceAccountJson;
            } else if (serviceAccountFile != null && !serviceAccountFile.isBlank()) {
                json = Files.readString(Path.of(serviceAccountFile.trim()), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.error("[NativePush] Cannot read service account file '{}': {}", serviceAccountFile, e.getMessage());
            return;
        }
        if (json == null) {
            log.warn("[NativePush] No Firebase service account configured — Android app push disabled. " +
                     "Set push.fcm.service-account-file or push.fcm.service-account-json.");
        } else {
            try {
                configure(json);
                enabled = true;
                log.info("[NativePush] FCM project '{}' — Android app push enabled.", projectId);
            } catch (Exception e) {
                log.error("[NativePush] Invalid service account JSON: {}", e.getMessage());
            }
        }
        initApns();
    }

    private void initApns() {
        String pem = null;
        try {
            if (apnsKeyPem != null && !apnsKeyPem.isBlank()) pem = apnsKeyPem;
            else if (apnsKeyFile != null && !apnsKeyFile.isBlank()) pem = Files.readString(Path.of(apnsKeyFile.trim()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("[NativePush] Cannot read APNs key file '{}': {}", apnsKeyFile, e.getMessage());
            return;
        }
        if (pem == null) {
            log.warn("[NativePush] No APNs auth key configured — iOS app push disabled. " +
                     "Set push.apns.key-file (or key-pem), push.apns.key-id and push.apns.team-id.");
            return;
        }
        try {
            configureApns(pem, apnsKeyId, apnsTeamId, apnsBundleId, apnsProduction);
            log.info("[NativePush] APNs key {} / team {} / topic {} ({}) — iOS app push enabled.",
                     apnsKeyId, apnsTeamId, apnsBundleId, apnsProduction ? "production" : "sandbox");
        } catch (Exception e) {
            log.error("[NativePush] Invalid APNs configuration: {}", e.getMessage());
        }
    }

    /** Loads the .p8 key and validates the ids (public for tests). */
    public void configureApns(String pem, String keyId, String teamId, String bundleId, boolean production) throws Exception {
        if (keyId == null || keyId.isBlank() || teamId == null || teamId.isBlank()) {
            throw new IllegalArgumentException("push.apns.key-id and push.apns.team-id are required");
        }
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        apnsKey = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        apnsKeyId = keyId.trim(); apnsTeamId = teamId.trim();
        apnsBundleId = bundleId == null || bundleId.isBlank() ? "com.churchgeniuspro.app" : bundleId.trim();
        apnsProduction = production;
        cachedApnsJwt = null; cachedApnsJwtIssued = Instant.EPOCH;
        apnsEnabled = true;
    }

    /** Parses the service-account JSON and loads the RSA key (public for tests). */
    public void configure(String json) throws Exception {
        JsonNode sa = mapper.readTree(json);
        projectId   = text(sa, "project_id");
        clientEmail = text(sa, "client_email");
        tokenUri    = sa.hasNonNull("token_uri") ? sa.get("token_uri").asText() : "https://oauth2.googleapis.com/token";
        String pem  = text(sa, "private_key")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der  = Base64.getDecoder().decode(pem);
        privateKey  = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        cachedAccessToken = null;
        cachedAccessTokenExpiry = Instant.EPOCH;
    }

    private static String text(JsonNode n, String field) {
        if (!n.hasNonNull(field) || n.get(field).asText().isBlank()) {
            throw new IllegalArgumentException("service account JSON is missing '" + field + "'");
        }
        return n.get(field).asText();
    }

    /** True when at least one app platform can be reached. */
    public boolean isEnabled() { return enabled || apnsEnabled; }
    public boolean isFcmEnabled() { return enabled; }
    public boolean isApnsEnabled() { return apnsEnabled; }

    public String getProjectId() { return projectId; }

    // ── sending ──────────────────────────────────────────────────────────────

    /**
     * Sends one notification to one app install, by the row's platform. Never throws.
     */
    public Result send(PushSubscription sub, String title, String body, String url, String tag) {
        if (PushSubscription.PLATFORM_IOS.equals(sub.getPlatform())) return sendApns(sub, title, body, url, tag);
        return sendFcm(sub, title, body, url, tag);
    }

    Result sendFcm(PushSubscription sub, String title, String body, String url, String tag) {
        if (!enabled) return Result.DISABLED;
        String token = sub.getEndpoint();
        try {
            String payload = mapper.writeValueAsString(buildMessage(token, title, body, url, tag));
            HttpResponse<String> res = post(payload, false);
            if (res.statusCode() == 401) {
                // Access token revoked or clock skew: refresh once and retry.
                res = post(payload, true);
            }
            int sc = res.statusCode();
            if (sc >= 200 && sc < 300) {
                log.debug("[NativePush] Sent to {} token …{}", sub.getPlatform(), tail(token));
                return Result.SENT;
            }
            String resBody = res.body() == null ? "" : res.body();
            if (sc == 404 || resBody.contains("UNREGISTERED") || resBody.contains("not a valid FCM registration token")) {
                log.info("[NativePush] Token no longer registered (HTTP {}) — deactivating sub id={}", sc, sub.getId());
                return Result.UNREGISTERED;
            }
            log.warn("[NativePush] FCM rejected sub id={} (HTTP {}): {}", sub.getId(), sc, abbreviate(resBody));
            return Result.FAILED;
        } catch (Exception e) {
            log.warn("[NativePush] Failed to deliver to sub id={}: {}", sub.getId(), e.getMessage() != null ? e.getMessage() : e.toString());
            return Result.FAILED;
        }
    }

    /**
     * The FCM v1 message. The {@code notification} block makes the OS show it even when
     * the app is closed; {@code data} is what the app's tap handler reads (the bridge
     * navigates to {@code url}); {@code tag} / {@code thread-id} collapse repeats of the
     * same reminder on both platforms.
     */
    Map<String, Object> buildMessage(String token, String title, String body, String url, String tag) {
        String u = url != null && !url.isBlank() ? url : "/";
        String t = tag != null && !tag.isBlank() ? tag : "cgp-push";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url",   u);
        data.put("tag",   t);
        data.put("title", title == null ? "" : title);
        data.put("body",  body  == null ? "" : body);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("token", token);
        message.put("notification", Map.of("title", title == null ? "" : title, "body", body == null ? "" : body));
        message.put("data", data);
        message.put("android", Map.of(
                "priority", "high",
                "notification", Map.of("channel_id", androidChannel == null ? "cgp_default" : androidChannel, "tag", t)));
        message.put("apns", Map.of(
                "headers", Map.of("apns-priority", "10"),
                "payload", Map.of("aps", Map.of("sound", "default", "thread-id", t))));
        return Map.of("message", message);
    }

    private HttpResponse<String> post(String payload, boolean forceRefresh) throws Exception {
        String access = accessToken(forceRefresh);
        HttpRequest req = HttpRequest.newBuilder(URI.create(fcmBaseUrl + "/v1/projects/" + projectId + "/messages:send"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + access)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    // ── APNs ─────────────────────────────────────────────────────────────────

    Result sendApns(PushSubscription sub, String title, String body, String url, String tag) {
        if (!apnsEnabled) return Result.DISABLED;
        String token = sub.getEndpoint();
        String t = tag != null && !tag.isBlank() ? tag : "cgp-push";
        try {
            String payload = mapper.writeValueAsString(buildApnsPayload(title, body, url, t));
            HttpResponse<String> res = postApns(token, payload, t, false);
            if (res.statusCode() == 403 && res.body() != null && res.body().contains("ExpiredProviderToken")) {
                res = postApns(token, payload, t, true);
            }
            int sc = res.statusCode();
            if (sc == 200) {
                log.debug("[NativePush] APNs sent to token …{}", tail(token));
                return Result.SENT;
            }
            String resBody = res.body() == null ? "" : res.body();
            if (sc == 410 || resBody.contains("BadDeviceToken") || resBody.contains("Unregistered") || resBody.contains("DeviceTokenNotForTopic")) {
                log.info("[NativePush] APNs token no longer valid (HTTP {} {}) — deactivating sub id={}", sc, abbreviate(resBody), sub.getId());
                return Result.UNREGISTERED;
            }
            log.warn("[NativePush] APNs rejected sub id={} (HTTP {}): {}", sub.getId(), sc, abbreviate(resBody));
            return Result.FAILED;
        } catch (Exception e) {
            log.warn("[NativePush] APNs delivery failed for sub id={}: {}", sub.getId(), e.getMessage() != null ? e.getMessage() : e.toString());
            return Result.FAILED;
        }
    }

    /** The APNs payload: alert + sound for the OS, {@code url}/{@code tag} for the app's tap handler. */
    Map<String, Object> buildApnsPayload(String title, String body, String url, String tag) {
        Map<String, Object> aps = new LinkedHashMap<>();
        aps.put("alert", Map.of("title", title == null ? "" : title, "body", body == null ? "" : body));
        aps.put("sound", "default");
        aps.put("thread-id", tag);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("aps", aps);
        payload.put("url", url != null && !url.isBlank() ? url : "/");
        payload.put("tag", tag);
        payload.put("title", title == null ? "" : title);
        payload.put("body",  body  == null ? "" : body);
        return payload;
    }

    private HttpResponse<String> postApns(String deviceToken, String payload, String collapseId, boolean forceRefresh) throws Exception {
        String base = apnsBaseUrl != null && !apnsBaseUrl.isBlank() ? apnsBaseUrl
                    : (apnsProduction ? "https://api.push.apple.com" : "https://api.sandbox.push.apple.com");
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/3/device/" + deviceToken))
                .version(HttpClient.Version.HTTP_2)
                .timeout(Duration.ofSeconds(15))
                .header("authorization", "bearer " + apnsProviderToken(forceRefresh))
                .header("apns-topic", apnsBundleId)
                .header("apns-push-type", "alert")
                .header("apns-priority", "10")
                .header("apns-collapse-id", collapseId.length() > 64 ? collapseId.substring(0, 64) : collapseId)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /** ES256 provider token ({@code kid} = key id, {@code iss} = team id), reused for 50 minutes. */
    public String apnsProviderToken(boolean forceRefresh) throws Exception {
        Instant now = Instant.now();
        if (!forceRefresh && cachedApnsJwt != null && now.isBefore(cachedApnsJwtIssued.plusSeconds(50 * 60))) {
            return cachedApnsJwt;
        }
        synchronized (this) {
            if (!forceRefresh && cachedApnsJwt != null && now.isBefore(cachedApnsJwtIssued.plusSeconds(50 * 60))) {
                return cachedApnsJwt;
            }
            cachedApnsJwt = signedApnsJwt(now);
            cachedApnsJwtIssued = now;
            return cachedApnsJwt;
        }
    }

    public String signedApnsJwt(Instant now) throws Exception {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header  = b64.encodeToString(("{\"alg\":\"ES256\",\"kid\":\"" + apnsKeyId + "\"}").getBytes(StandardCharsets.UTF_8));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", apnsTeamId);
        claims.put("iat", now.getEpochSecond());
        String payload = b64.encodeToString(mapper.writeValueAsBytes(claims));
        String signingInput = header + "." + payload;
        // JWS wants the raw R||S form, not DER — the P1363 variant produces exactly that.
        Signature sig = Signature.getInstance("SHA256withECDSAinP1363Format");
        sig.initSign(apnsKey);
        sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + b64.encodeToString(sig.sign());
    }

    // ── OAuth 2.0 (JWT bearer) ────────────────────────────────────────────────

    /** Returns a cached Google access token, exchanging a freshly signed JWT when needed. */
    String accessToken(boolean forceRefresh) throws Exception {
        if (!forceRefresh && cachedAccessToken != null && Instant.now().isBefore(cachedAccessTokenExpiry)) {
            return cachedAccessToken;
        }
        synchronized (this) {
            if (!forceRefresh && cachedAccessToken != null && Instant.now().isBefore(cachedAccessTokenExpiry)) {
                return cachedAccessToken;
            }
            String assertion = signedJwt(Instant.now());
            String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                        + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(tokenUri))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                throw new IllegalStateException("token endpoint HTTP " + res.statusCode() + ": " + abbreviate(res.body()));
            }
            JsonNode tok = mapper.readTree(res.body());
            String access = tok.path("access_token").asText(null);
            if (access == null || access.isBlank()) throw new IllegalStateException("token endpoint returned no access_token");
            long expiresIn = tok.path("expires_in").asLong(3600);
            cachedAccessToken = access;
            cachedAccessTokenExpiry = Instant.now().plusSeconds(Math.max(60, expiresIn - 60));
            return access;
        }
    }

    /** RS256 JWT for the Google token endpoint (iss = service account, scope = FCM). */
    public String signedJwt(Instant now) throws Exception {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss",   clientEmail);
        claims.put("scope", SCOPE);
        claims.put("aud",   tokenUri);
        claims.put("iat",   now.getEpochSecond());
        claims.put("exp",   now.plusSeconds(3600).getEpochSecond());
        String payload = b64.encodeToString(mapper.writeValueAsBytes(claims));
        String signingInput = header + "." + payload;
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + b64.encodeToString(sig.sign());
    }

    // ── test seams ───────────────────────────────────────────────────────────

    public void setHttpClient(HttpClient client) { this.http = client; }
    public void setFcmBaseUrl(String url) { this.fcmBaseUrl = url; }
    public void setEnabledForTest(boolean on) { this.enabled = on; }
    public void setApnsBaseUrl(String url) { this.apnsBaseUrl = url; }

    private static String tail(String s) { return s == null ? "" : s.substring(Math.max(0, s.length() - 8)); }
    private static String abbreviate(String s) { return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) + "…" : s); }
}
