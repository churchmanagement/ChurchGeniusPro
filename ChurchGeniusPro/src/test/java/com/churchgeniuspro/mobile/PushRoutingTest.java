package com.churchgeniuspro.mobile;

import com.churchgeniuspro.controller.PushController;
import com.churchgeniuspro.hibernate.PushSubscription;
import com.churchgeniuspro.repository.PushNotificationLogRepository;
import com.churchgeniuspro.repository.PushSubscriptionRepository;
import com.churchgeniuspro.service.NativePushService;
import com.churchgeniuspro.service.WebPushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mobile Phase 5: one push_subscription table, two transports. The schedulers keep
 * calling WebPushService exactly as before; app rows are routed to FCM, browser rows
 * to VAPID, and the app registers its token through PushController.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushRoutingTest {

    @Mock PushSubscriptionRepository    subs;
    @Mock PushNotificationLogRepository logs;
    @Mock NativePushService             fcm;

    static PushSubscription row(long id, String platform, String endpoint, String userKey) {
        PushSubscription s = new PushSubscription();
        s.setId(id); s.setPlatform(platform); s.setEndpoint(endpoint);
        s.setP256dh("-"); s.setAuth("-"); s.setUserKey(userKey); s.setUserType("staff"); s.setAppClientId("CH-1");
        return s;
    }

    @Nested
    @DisplayName("WebPushService routing")
    class Routing {

        WebPushService svc;

        @BeforeEach
        void setUp() {
            svc = new WebPushService(subs, logs, fcm);   // no VAPID keys: init() leaves web delivery off
            svc.init();
            when(fcm.isEnabled()).thenReturn(true);
            when(logs.countRecentByTag(anyString(), anyString(), any())).thenReturn(0L);
        }

        @Test
        @DisplayName("enabled when only FCM is configured; the VAPID key stays unavailable to browsers")
        void enabledByNativeAlone() {
            assertThat(svc.isEnabled()).isTrue();
            assertThat(svc.isWebPushEnabled()).isFalse();
            assertThat(svc.isNativePushEnabled()).isTrue();
        }

        @Test
        @DisplayName("sendToUser: app rows go to FCM with the same title/body/url/tag; browser rows are skipped without VAPID")
        void mixedRows() {
            when(subs.findByUserKeyAndActiveTrue("USR-1")).thenReturn(List.of(
                    row(1, "web", "https://fcm.googleapis.com/wp/abc", "USR-1"),
                    row(2, "android", "fcm-token-A", "USR-1"),
                    row(3, "ios", "fcm-token-I", "USR-1")));
            when(fcm.send(any(), any(), any(), any(), any())).thenReturn(NativePushService.Result.SENT);

            svc.sendToUser("USR-1", "Hello", "World", "/home", "tag-1");

            ArgumentCaptor<PushSubscription> cap = ArgumentCaptor.forClass(PushSubscription.class);
            verify(fcm, org.mockito.Mockito.times(2)).send(cap.capture(), eq("Hello"), eq("World"), eq("/home"), eq("tag-1"));
            assertThat(cap.getAllValues()).extracting(PushSubscription::getEndpoint).containsExactly("fcm-token-A", "fcm-token-I");
            verify(subs, never()).deactivateByEndpoint(anyString());
        }

        @Test
        @DisplayName("UNREGISTERED from FCM deactivates that row only")
        void unregisteredDeactivates() {
            when(subs.findByUserKeyAndActiveTrue("USR-1")).thenReturn(List.of(
                    row(2, "android", "dead-token", "USR-1"),
                    row(3, "ios", "live-token", "USR-1")));
            when(fcm.send(argThatEndpoint("dead-token"), any(), any(), any(), any())).thenReturn(NativePushService.Result.UNREGISTERED);
            when(fcm.send(argThatEndpoint("live-token"), any(), any(), any(), any())).thenReturn(NativePushService.Result.SENT);

            svc.sendToUser("USR-1", "t", "b", null, null);

            verify(subs).deactivateByEndpoint("dead-token");
            verify(subs, never()).deactivateByEndpoint("live-token");
        }

        @Test
        @DisplayName("logAndSendToOrg: one log row per user, and app devices still receive")
        void orgFanOut() {
            when(subs.findByAppClientIdAndActiveTrue("CH-1")).thenReturn(List.of(
                    row(1, "android", "tok-1", "USR-1"),
                    row(2, "ios", "tok-2", "USR-1"),
                    row(3, "android", "tok-3", "USR-2")));
            when(fcm.send(any(), any(), any(), any(), any())).thenReturn(NativePushService.Result.SENT);

            svc.logAndSendToOrg("CH-1", "Service today", "10am", "/events", "svc-1");

            verify(logs, org.mockito.Mockito.times(2)).save(any());          // USR-1 once, USR-2 once
            verify(fcm, org.mockito.Mockito.times(3)).send(any(), any(), any(), any(), any());
        }

        private PushSubscription argThatEndpoint(String ep) {
            return org.mockito.ArgumentMatchers.argThat(s -> s != null && ep.equals(s.getEndpoint()));
        }
    }

    @Nested
    @DisplayName("POST /api/push/register-native")
    class Register {

        @Mock WebPushService push;
        PushController controller;

        @BeforeEach
        void setUp() {
            push = org.mockito.Mockito.mock(WebPushService.class);
            when(push.isNativePushEnabled()).thenReturn(true);
            controller = new PushController(push, subs, logs);
            when(logs.countUnread(anyString())).thenReturn(3L);
            when(subs.findByEndpoint(anyString())).thenReturn(Optional.empty());
            when(subs.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        MockHttpServletRequest signedIn(String clientId, String role, String appClientId) {
            MockHttpSession s = new MockHttpSession();
            s.setAttribute("clientId", clientId);
            s.setAttribute("role", role);
            if (appClientId != null) s.setAttribute("appClientId", appClientId);
            MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s);
            return r;
        }

        @Test
        @DisplayName("no session → 401, nothing saved")
        void unauthenticated() {
            ResponseEntity<?> r = controller.registerNative(Map.of("token", "t", "platform", "android"), new MockHttpServletRequest());
            assertThat(r.getStatusCode().value()).isEqualTo(401);
            verify(subs, never()).save(any());
        }

        @Test
        @DisplayName("missing token or unknown platform → 400")
        void badInput() {
            MockHttpServletRequest req = signedIn("USR-1", "Admin", "CH-1");
            assertThat(controller.registerNative(Map.of("platform", "android"), req).getStatusCode().value()).isEqualTo(400);
            assertThat(controller.registerNative(Map.of("token", "t", "platform", "web"), req).getStatusCode().value()).isEqualTo(400);
            assertThat(controller.registerNative(Map.of("token", "t", "platform", "windows"), req).getStatusCode().value()).isEqualTo(400);
            verify(subs, never()).save(any());
        }

        @Test
        @DisplayName("staff: row saved with platform, placeholder keys, user key, org and device name")
        void staffRegisters() {
            MockHttpServletRequest req = signedIn("USR-1", "Admin", "CH-1");
            ResponseEntity<?> r = controller.registerNative(Map.of("token", "fcm-abc", "platform", "ANDROID", "deviceName", "Pixel 8"), req);
            assertThat(r.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) r.getBody();
            assertThat(body).containsEntry("status", "registered").containsEntry("enabled", true).containsEntry("unreadCount", 3L);

            ArgumentCaptor<PushSubscription> cap = ArgumentCaptor.forClass(PushSubscription.class);
            verify(subs).save(cap.capture());
            PushSubscription s = cap.getValue();
            assertThat(s.getEndpoint()).isEqualTo("fcm-abc");
            assertThat(s.getPlatform()).isEqualTo("android");
            assertThat(s.isNative()).isTrue();
            assertThat(s.getP256dh()).isEqualTo("-");
            assertThat(s.getAuth()).isEqualTo("-");
            assertThat(s.getUserKey()).isEqualTo("USR-1");
            assertThat(s.getAppClientId()).isEqualTo("CH-1");
            assertThat(s.getUserType()).isEqualTo("staff");
            assertThat(s.getDeviceName()).isEqualTo("Pixel 8");
            assertThat(s.isActive()).isTrue();
        }

        @Test
        @DisplayName("member signing in on a phone another member used: the same token is re-keyed and re-activated")
        void rekeyExisting() {
            PushSubscription old = row(9, "ios", "fcm-shared", "MBR-old");
            old.setUserType("member"); old.setActive(false); old.setDeviceName("iPhone");
            when(subs.findByEndpoint("fcm-shared")).thenReturn(Optional.of(old));

            controller.registerNative(Map.of("token", "fcm-shared", "platform", "ios"), signedIn("MBR-new", "Member", "CH-1"));

            ArgumentCaptor<PushSubscription> cap = ArgumentCaptor.forClass(PushSubscription.class);
            verify(subs).save(cap.capture());
            assertThat(cap.getValue()).isSameAs(old);
            assertThat(old.getUserKey()).isEqualTo("MBR-new");
            assertThat(old.getUserType()).isEqualTo("member");
            assertThat(old.isActive()).isTrue();
            assertThat(old.getDeviceName()).as("name kept when the app sends none").isEqualTo("iPhone");
        }

        @Test
        @DisplayName("the VAPID key endpoint answers 204 when only FCM is configured (browsers must not subscribe)")
        void vapidKeyNeedsWeb() {
            when(push.isWebPushEnabled()).thenReturn(false);
            assertThat(controller.vapidPublicKey().getStatusCode().value()).isEqualTo(204);
            when(push.isWebPushEnabled()).thenReturn(true);
            when(push.getVapidPublicKeyRaw()).thenReturn("BKEY");
            assertThat(controller.vapidPublicKey().getBody()).isEqualTo("BKEY");
        }
    }
}
