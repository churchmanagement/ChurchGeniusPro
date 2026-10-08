package com.churchgeniuspro.security;

import com.churchgeniuspro.controller.PublicKidsCheckinController;
import com.churchgeniuspro.controller.PublicScreensController;
import com.churchgeniuspro.controller.SmsOptInController;
import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.MessagingPolicy;
import com.churchgeniuspro.service.SmsService;
import com.churchgeniuspro.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.churchgeniuspro.util.PublicSendLimiter;

/**
 * Day-1 hardening of the anonymous surface: public-screen link management,
 * the /pub/{token} session, the kids check-in phone search, and the Twilio webhook.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Public surface hardening")
class PublicSurfaceHardeningTest {

    private static final String OURS   = "CHR-ours";
    private static final String THEIRS = "CHR-theirs";

    @Mock PublicScreenLinkRepository linkRepo;
    @Mock SubscriptionService subscriptions;
    @Mock MessagingPolicy messagingPolicy;

    private static MockHttpServletRequest staff(String clientId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("appClientId", clientId);
        s.setAttribute("clientId", clientId);
        s.setAttribute("username", "admin@" + clientId);
        s.setAttribute("role", "Admin");
        req.setSession(s);
        return req;
    }

    /** The session a visitor gets from scanning a church's QR code. */
    private static MockHttpServletRequest publicViewer(String clientId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("publicView", true);
        s.setAttribute("appClientId", clientId);
        req.setSession(s);
        return req;
    }

    private static PublicScreenLink link(int id, String tenant) {
        PublicScreenLink l = new PublicScreenLink();
        l.setId(id); l.setAppClientId(tenant); l.setPageUrl("/donate"); l.setRevoked(false);
        return l;
    }

    @Nested
    @DisplayName("public-screens admin API")
    class Screens {
        PublicScreensController controller;

        @BeforeEach void setUp() {
            controller = new PublicScreensController(linkRepo, policy(), new com.churchgeniuspro.service.PublicLinkResolver(linkRepo, policy()));
            ReflectionTestUtils.setField(controller, "baseUrl", "https://churchgeniuspro.net");
            when(subscriptions.isFeatureEnabled(anyString(), anyString())).thenReturn(true);
            when(messagingPolicy.isTrial(anyString())).thenReturn(false);
        }

        @Test void anonymousCannotRevoke() {
            when(linkRepo.findById(17)).thenReturn(Optional.of(link(17, THEIRS)));
            ResponseEntity<?> res = controller.revoke(17, new MockHttpServletRequest());
            assertThat(res.getStatusCode().value()).isEqualTo(403);
            verify(linkRepo, never()).save(any());
        }
        @Test void staffCannotRevokeAnotherChurchsLink() {
            when(linkRepo.findById(17)).thenReturn(Optional.of(link(17, THEIRS)));
            ResponseEntity<?> res = controller.revoke(17, staff(OURS));
            assertThat(res.getStatusCode().value()).isEqualTo(404);
            verify(linkRepo, never()).save(any());
        }
        @Test void staffCanRevokeTheirOwn() {
            when(linkRepo.findById(17)).thenReturn(Optional.of(link(17, OURS)));
            when(linkRepo.save(any())).thenAnswer(i -> i.getArgument(0));
            assertThat(controller.revoke(17, staff(OURS)).getStatusCode().value()).isEqualTo(200);
        }
        @Test void aQrCodeVisitorCannotListOrMintLinks() {
            assertThat(controller.list(publicViewer(THEIRS)).getStatusCode().value()).isEqualTo(403);
            assertThat(controller.getAvailablePages(publicViewer(THEIRS)).getStatusCode().value()).isEqualTo(403);
            ResponseEntity<?> res = controller.generate(
                    java.util.Map.of("pageUrl", "/donate", "pageLabel", "Give"), publicViewer(THEIRS));
            assertThat(res.getStatusCode().value()).isEqualTo(403);
            verify(linkRepo, never()).save(any());
            verify(linkRepo, never()).findByAppClientIdAndRevokedFalseOrderByCreatedDateDesc(anyString());
        }
    }

    @Nested
    @DisplayName("/pub/{token} never retargets a signed-in session")
    class PubToken {
        PublicScreensController controller;
        String token;

        @BeforeEach void setUp() throws Exception {
            controller = new PublicScreensController(linkRepo, policy(), new com.churchgeniuspro.service.PublicLinkResolver(linkRepo, policy()));
            ReflectionTestUtils.setField(controller, "baseUrl", "https://churchgeniuspro.net");
            when(subscriptions.isFeatureEnabled(anyString(), anyString())).thenReturn(true);
            when(messagingPolicy.isTrial(anyString())).thenReturn(false);
            token = com.churchgeniuspro.service.PublicLinkResolver.newToken();   // opaque; the row is the link
            PublicScreenLink l = link(1, THEIRS); l.setPageUrl("/upcomingEvents"); l.setToken(token);
            when(linkRepo.findByToken(token)).thenReturn(Optional.of(l));
        }

        @Test void aStaffSessionOfAnotherChurchIsNeitherMovedNorEnded() {
            MockHttpServletRequest req = staff(OURS);
            MockHttpSession before = (MockHttpSession) req.getSession(false);

            String view = controller.publicAccess(token, req, new MockHttpServletResponse());

            // The page resolves its church from the token in the URL; the visitor's own
            // session is untouched — a GET from another site must not sign anyone out (P11).
            assertThat(view).isEqualTo("redirect:/upcomingEvents?c=" + token);
            assertThat(before.isInvalid()).as("the church-A session must survive").isFalse();
            assertThat(req.getSession(false)).isSameAs(before);
            assertThat(before.getAttribute("username")).isEqualTo("admin@" + OURS);
            assertThat(before.getAttribute("appClientId")).isEqualTo(OURS);
            assertThat(before.getAttribute("publicView")).isNull();
        }
        @Test void aStaffSessionOfTheSameChurchIsLeftAlone() {
            MockHttpServletRequest req = staff(THEIRS);
            MockHttpSession before = (MockHttpSession) req.getSession(false);
            String view = controller.publicAccess(token, req, new MockHttpServletResponse());
            assertThat(view).isEqualTo("redirect:/upcomingEvents?c=" + token);
            assertThat(before.isInvalid()).isFalse();
            assertThat(before.getAttribute("publicView")).isNull();
            assertThat(before.getAttribute("username")).isEqualTo("admin@" + THEIRS);
        }
        @Test void anAnonymousVisitorGetsAPublicSession() {
            MockHttpServletRequest req = new MockHttpServletRequest();
            String view = controller.publicAccess(token, req, new MockHttpServletResponse());
            assertThat(view).isEqualTo("redirect:/upcomingEvents?c=" + token);
            assertThat(req.getSession(false).getAttribute("publicView")).isEqualTo(true);
            assertThat(req.getSession(false).getAttribute("clientId")).isNull();   // still fails AuthFilter
        }
    }

    @Nested
    @DisplayName("kids check-in phone search")
    class KidsSearch {
        @Mock KmChildRepository childRepo;
        @Mock KmCheckinRepository checkinRepo;
        @Mock FamilyMemberRepository memberRepo;
        PublicKidsCheckinController controller;
        String cid;

        @BeforeEach void setUp() throws Exception {
            controller = new PublicKidsCheckinController(childRepo, checkinRepo, memberRepo, new com.churchgeniuspro.service.PublicLinkResolver(linkRepo, policy()), new PublicSendLimiter());
            cid = "tok-kids-live";
            PublicScreenLink kids = link(1, OURS); kids.setPageUrl("/kidsCheckin"); kids.setToken(cid);
            when(linkRepo.findByToken(cid)).thenReturn(Optional.of(kids));
            when(subscriptions.isFeatureEnabled(anyString(), anyString())).thenReturn(true);
            when(messagingPolicy.trialState(anyString())).thenReturn(Boolean.FALSE);
            when(childRepo.findByClientIdAndParentPhone(anyString(), anyString())).thenReturn(List.of());
        }
        private MockHttpServletRequest from(String ip) {
            MockHttpServletRequest r = new MockHttpServletRequest(); r.setRemoteAddr(ip); return r;
        }

        @Test void fourDigitsNoLongerSearch() {
            ResponseEntity<?> res = controller.search(cid, "1234", from("10.0.0.1"));
            assertThat(res.getStatusCode().value()).isEqualTo(400);
            verify(childRepo, never()).findByClientIdAndParentPhone(anyString(), anyString());
        }
        @Test void aFullNumberSearches() {
            ResponseEntity<?> res = controller.search(cid, "(555) 010-1234", from("10.0.0.1"));
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            verify(childRepo).findByClientIdAndParentPhone(OURS, "5550101234");
        }
        @Test void enumerationIsRateLimitedPerIp() {
            int max = com.churchgeniuspro.util.PublicSendLimiter.KIDS_CHECKIN_SEARCH.rules().get(0).max();   // the 10-minute per-origin rule
            for (int i = 0; i < max; i++) {
                assertThat(controller.search(cid, "555010" + String.format("%04d", i), from("10.0.0.9")).getStatusCode().value()).isEqualTo(200);
            }
            assertThat(controller.search(cid, "5550109999", from("10.0.0.9")).getStatusCode().value()).isEqualTo(429);
            // a different kiosk is unaffected
            assertThat(controller.search(cid, "5550109999", from("10.0.0.10")).getStatusCode().value()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("Twilio inbound webhook")
    class Twilio {
        @Mock SmsOptInRepository optInRepo;
        @Mock ChurchRegistrationRepository churchRepo;
        @Mock FamilyMemberRepository memberRepo;
        @Mock StripeSettingsRepository stripeRepo;
        @Mock SmsService smsService;
        SmsOptInController controller;

        @BeforeEach void setUp() {
            controller = new SmsOptInController(optInRepo, churchRepo, memberRepo, linkRepo, stripeRepo, smsService, new com.churchgeniuspro.service.PublicLinkResolver(linkRepo, policy()), new PublicSendLimiter());
            ReflectionTestUtils.setField(controller, "appBaseUrl", "https://churchgeniuspro.net");
            ReflectionTestUtils.setField(controller, "twilioAuthToken", "12345");
            ReflectionTestUtils.setField(controller, "twilioAccountSid", "ACtest");
        }
        private MockHttpServletRequest post(String from, String body, String sid, String signature) {
            MockHttpServletRequest r = new MockHttpServletRequest("POST", "/webhook/sms");
            r.setRequestURI("/webhook/sms");
            r.setParameter("From", from); r.setParameter("Body", body); r.setParameter("AccountSid", sid);
            if (signature != null) r.addHeader("X-Twilio-Signature", signature);
            return r;
        }

        @Test void unsignedRequestTouchesNothing() {
            String out = controller.handleInboundSms("+15550101234", "STOP", "ACtest", post("+15550101234", "STOP", "ACtest", null));
            assertThat(out).isEqualTo("<Response/>");
            verify(optInRepo, never()).findByPhoneNumber(anyString());
            verify(optInRepo, never()).save(any());
        }
        @Test void forgedSignatureTouchesNothing() {
            String out = controller.handleInboundSms("+15550101234", "YES", "ACtest",
                    post("+15550101234", "YES", "ACtest", "bm90IGEgcmVhbCBzaWduYXR1cmU="));
            assertThat(out).isEqualTo("<Response/>");
            verify(optInRepo, never()).findByPhoneNumber(anyString());
        }
        @Test void wrongAccountIsRefusedEvenWithASignature() throws Exception {
            var params = new java.util.HashMap<String, String>();
            params.put("From", "+15550101234"); params.put("Body", "YES"); params.put("AccountSid", "ACother");
            String sig = twilioSign("12345", "https://churchgeniuspro.net/webhook/sms", params);
            controller.handleInboundSms("+15550101234", "YES", "ACother", post("+15550101234", "YES", "ACother", sig));
            verify(optInRepo, never()).findByPhoneNumber(anyString());
        }
        @Test void aGenuineRequestIsProcessed() throws Exception {
            var params = new java.util.HashMap<String, String>();
            params.put("From", "+15550101234"); params.put("Body", "STOP"); params.put("AccountSid", "ACtest");
            String sig = twilioSign("12345", "https://churchgeniuspro.net/webhook/sms", params);
            when(optInRepo.findByPhoneNumber("+15550101234")).thenReturn(List.of());
            controller.handleInboundSms("+15550101234", "STOP", "ACtest", post("+15550101234", "STOP", "ACtest", sig));
            verify(optInRepo).findByPhoneNumber("+15550101234");
        }
        /** Twilio's documented scheme: HMAC-SHA1 over URL + sorted (key+value) pairs, base64. */
        private static String twilioSign(String token, String url, java.util.Map<String, String> params) throws Exception {
            StringBuilder data = new StringBuilder(url);
            new java.util.TreeMap<>(params).forEach((k, v) -> data.append(k).append(v));
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
            mac.init(new javax.crypto.spec.SecretKeySpec(token.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA1"));
            return java.util.Base64.getEncoder().encodeToString(mac.doFinal(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        @Test void unconfiguredTokenFailsClosed() {
            ReflectionTestUtils.setField(controller, "twilioAuthToken", "");
            controller.handleInboundSms("+15550101234", "STOP", "ACtest", post("+15550101234", "STOP", "ACtest", "x"));
            verify(optInRepo, never()).findByPhoneNumber(anyString());
        }
    }

    private com.churchgeniuspro.service.PublicPagePolicy policy() {
        return new com.churchgeniuspro.service.PublicPagePolicy(subscriptions, messagingPolicy);
    }
}
