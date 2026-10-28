package com.churchgeniuspro.errors;

import com.churchgeniuspro.hibernate.PushSubscription;
import com.churchgeniuspro.repository.PushNotificationLogRepository;
import com.churchgeniuspro.repository.PushSubscriptionRepository;
import com.churchgeniuspro.service.ChurchStripeGateway;
import com.churchgeniuspro.service.NativePushService;
import com.churchgeniuspro.service.WebPushService;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolVersion;
import org.apache.http.message.BasicHttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Error-handling audit 2026-10-28, H5/H6: what the caller gets when Stripe or the push service fails. */
@DisplayName("External failures — church Stripe error mapping and web-push expiry")
class IntegrationFailureTest {

    static ChurchStripeGateway gatewayThrowing(RuntimeException ex) {
        ChurchStripeGateway g = new ChurchStripeGateway();
        g.setTransport(new ChurchStripeGateway.Transport() {
            @Override public Map<String, Object> post(String u, HttpHeaders h, MultiValueMap<String, String> p) { throw ex; }
            @Override public Map<String, Object> get(String u, HttpHeaders h) { throw ex; }
        });
        return g;
    }

    static HttpClientErrorException stripe4xx(HttpStatus status, String json) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY,
                json.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("ChurchStripeGateway")
    class Stripe {

        @Test @DisplayName("a card_error shows Stripe's own sentence to the donor (400, not transient)")
        void cardError() {
            Map<String, Object> r = gatewayThrowing(stripe4xx(HttpStatus.PAYMENT_REQUIRED,
                    "{\"error\":{\"type\":\"card_error\",\"code\":\"card_declined\",\"message\":\"Your card was declined.\"}}"))
                    .createIntent("sk_test_x", 5000, "usd");
            assertThat(ChurchStripeGateway.errorMessage(r)).isEqualTo("Your card was declined.");
            assertThat(ChurchStripeGateway.isTransient(r)).isFalse();
        }

        @Test @DisplayName("a bad or revoked key is the church's problem: fixed sentence, never 'Unauthorized' or Stripe's key text")
        void authenticationError() {
            Map<String, Object> r = gatewayThrowing(stripe4xx(HttpStatus.UNAUTHORIZED,
                    "{\"error\":{\"type\":\"authentication_error\",\"message\":\"Invalid API Key provided: sk_test_***\"}}"))
                    .retrieveIntent("sk_test_x", "pi_3AbCdEfGhIjKlMnOpQrStUvW");
            assertThat(ChurchStripeGateway.errorMessage(r)).isEqualTo(ChurchStripeGateway.SETUP_MSG);
            assertThat(r.toString()).doesNotContain("Unauthorized").doesNotContain("Invalid API Key");
            assertThat(ChurchStripeGateway.isPermissionError(r)).isTrue();     // status kept for DonationFeeService
            assertThat(ChurchStripeGateway.isTransient(r)).isFalse();
        }

        @Test @DisplayName("a Stripe 5xx and a timeout are transient (502 for the page's retry)")
        void transientErrors() {
            Map<String, Object> five = gatewayThrowing(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway",
                    HttpHeaders.EMPTY, "<html>".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).createIntent("sk", 100, "usd");
            assertThat(ChurchStripeGateway.isTransient(five)).isTrue();
            Map<String, Object> timeout = gatewayThrowing(new ResourceAccessException("Read timed out")).createIntent("sk", 100, "usd");
            assertThat(ChurchStripeGateway.isTransient(timeout)).isTrue();
            assertThat(ChurchStripeGateway.errorMessage(timeout)).contains("Please try again");
        }

        @Test @DisplayName("testConnection reports ok + livemode, or the same error shape")
        void testConnection() {
            ChurchStripeGateway ok = new ChurchStripeGateway();
            ok.setTransport(new ChurchStripeGateway.Transport() {
                @Override public Map<String, Object> post(String u, HttpHeaders h, MultiValueMap<String, String> p) { return Map.of(); }
                @Override public Map<String, Object> get(String u, HttpHeaders h) {
                    assertThat(u).endsWith("/v1/balance");
                    assertThat(h.getFirst("Authorization")).isEqualTo("Bearer sk_live_abc");
                    return Map.of("object", "balance", "livemode", true);
                }
            });
            Map<String, Object> r = ok.testConnection("sk_live_abc");
            assertThat(r).containsEntry("ok", true).containsEntry("livemode", true);
            Map<String, Object> bad = gatewayThrowing(stripe4xx(HttpStatus.UNAUTHORIZED, "{\"error\":{\"type\":\"authentication_error\",\"message\":\"x\"}}"))
                    .testConnection("sk_bad");
            assertThat(bad).containsKey("error");
            assertThat(ChurchStripeGateway.errorMessage(bad)).isEqualTo(ChurchStripeGateway.SETUP_MSG);
        }

        @Test @DisplayName("the gateway's RestTemplate has connect and read timeouts")
        void timeouts() {
            org.springframework.web.client.RestTemplate t = ReflectionTestUtils.invokeMethod(ChurchStripeGateway.class, "timedRestTemplate");
            assertThat(t.getRequestFactory()).isInstanceOf(org.springframework.http.client.SimpleClientHttpRequestFactory.class);
            org.springframework.http.client.SimpleClientHttpRequestFactory f = (org.springframework.http.client.SimpleClientHttpRequestFactory) t.getRequestFactory();
            assertThat((Integer) ReflectionTestUtils.getField(f, "connectTimeout")).isEqualTo(10_000);
            assertThat((Integer) ReflectionTestUtils.getField(f, "readTimeout")).isEqualTo(30_000);
        }
    }

    @Nested
    @DisplayName("WebPushService — expired browser subscriptions")
    class WebPush {

        PushSubscriptionRepository subs = mock(PushSubscriptionRepository.class);
        PushNotificationLogRepository logs = mock(PushNotificationLogRepository.class);
        NativePushService fcm = mock(NativePushService.class);
        PushService push = mock(PushService.class);

        WebPushService build(int statusCode) throws Exception {
            if (java.security.Security.getProvider("BC") == null)
                java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
            WebPushService svc = new WebPushService(subs, logs, fcm);
            ReflectionTestUtils.setField(svc, "pushService", push);
            ReflectionTestUtils.setField(svc, "webEnabled", true);
            HttpResponse resp = new BasicHttpResponse(new ProtocolVersion("HTTP", 1, 1), statusCode, "x");
            when(push.send(any(Notification.class))).thenReturn(resp);
            // the Notification constructor parses the subscriber's P-256 key, so a real one is needed
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("EC");
            kpg.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
            java.security.interfaces.ECPublicKey pub = (java.security.interfaces.ECPublicKey) kpg.generateKeyPair().getPublic();
            byte[] x = to32(pub.getW().getAffineX().toByteArray()), y = to32(pub.getW().getAffineY().toByteArray());
            byte[] point = new byte[65]; point[0] = 4; System.arraycopy(x, 0, point, 1, 32); System.arraycopy(y, 0, point, 33, 32);
            String p256dh = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(point);
            String auth = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
            PushSubscription s = new PushSubscription();
            s.setId(1L); s.setPlatform("web"); s.setEndpoint("https://fcm.googleapis.com/wp/abc"); s.setP256dh(p256dh); s.setAuth(auth);
            s.setUserKey("USR-1"); s.setUserType("staff"); s.setAppClientId("CH-1");
            when(subs.findByUserKeyAndActiveTrue("USR-1")).thenReturn(List.of(s));
            return svc;
        }

        static byte[] to32(byte[] b) {
            byte[] out = new byte[32];
            int n = Math.min(32, b.length);
            System.arraycopy(b, b.length - n, out, 32 - n, n);
            return out;
        }

        @Test @DisplayName("HTTP 410 from the push service deactivates the row (the old exception-message check never fired)")
        void goneDeactivates() throws Exception {
            build(410).sendToUser("USR-1", "t", "b", "/home", "tag");
            verify(subs).deactivateByEndpoint("https://fcm.googleapis.com/wp/abc");
        }

        @Test @DisplayName("HTTP 404 deactivates too; 201 and 400 do not")
        void otherStatuses() throws Exception {
            build(404).sendToUser("USR-1", "t", "b", "/home", "tag");
            verify(subs).deactivateByEndpoint(anyString());
            PushSubscriptionRepository fresh = mock(PushSubscriptionRepository.class);
            subs = fresh;
            build(201).sendToUser("USR-1", "t", "b", "/home", "tag");
            build(400).sendToUser("USR-1", "t", "b", "/home", "tag");
            verify(fresh, never()).deactivateByEndpoint(anyString());
        }
    }
}
