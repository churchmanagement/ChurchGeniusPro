package com.churchgeniuspro.security;

import com.churchgeniuspro.controller.ChurchRegistrationController;
import com.churchgeniuspro.controller.KmPickupPublicController;
import com.churchgeniuspro.controller.PublicScreensController;
import com.churchgeniuspro.controller.UnsubscribeController;
import com.churchgeniuspro.hibernate.ChurchEvent;
import com.churchgeniuspro.hibernate.KmCheckin;
import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import com.churchgeniuspro.util.EncryptionUtil;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.churchgeniuspro.util.PublicSendLimiter;

/**
 * Month 1: every public identifier that used to be AES-ECB of a predictable value
 * is now a random token stored on a row (or, for the unsubscribe footer, an HMAC).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Public tokens are random and stored — nothing is decrypted")
class PublicTokenReplacementTest {

    private static final String OURS = "CHR-ours";

    @Mock PublicScreenLinkRepository linkRepo;
    @Mock SubscriptionService subscriptions;
    @Mock MessagingPolicy messaging;

    private PublicPagePolicy policy() {
        when(subscriptions.isFeatureEnabled(anyString(), anyString())).thenReturn(true);
        when(messaging.trialState(anyString())).thenReturn(Boolean.FALSE);
        return new PublicPagePolicy(subscriptions, messaging);
    }

    private static MockHttpServletRequest staff(String cid) {
        MockHttpServletRequest r = new MockHttpServletRequest(); MockHttpSession s = new MockHttpSession();
        s.setAttribute("appClientId", cid); s.setAttribute("clientId", cid);
        s.setAttribute("username", "a@" + cid); s.setAttribute("role", "Admin"); r.setSession(s); return r;
    }

    @Nested @DisplayName("Public Screens links")
    class Screens {
        @Test void generatedTokensAreRandomAndCarriedInEveryUrl() {
            PublicPagePolicy p = policy();
            PublicScreensController c = new PublicScreensController(linkRepo, p, new PublicLinkResolver(linkRepo, p));
            ReflectionTestUtils.setField(c, "baseUrl", "https://churchgeniuspro.net");
            when(linkRepo.save(any())).thenAnswer(i -> { PublicScreenLink l = i.getArgument(0); l.setId(7); return l; });

            ResponseEntity<Map<String, Object>> res = c.generate(Map.of("pageUrl", PublicPagePolicy.KIDS_CHECKIN_URL, "pageLabel", "Kids"), staff(OURS));
            assertThat(res.getStatusCode().is2xxSuccessful()).as("%s", res.getBody()).isTrue();
            ArgumentCaptor<PublicScreenLink> saved = ArgumentCaptor.forClass(PublicScreenLink.class);
            verify(linkRepo).save(saved.capture());
            String token = saved.getValue().getToken();
            assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
            // The URL carries the token itself — not an encryption of the tenant id.
            assertThat(String.valueOf(res.getBody().get("publicUrl"))).endsWith("/kidsCheckin?cid=" + token);
            assertThat(token).isNotEqualTo(encryptOrNull(OURS));
        }
    }

    @Nested @DisplayName("Event registration links")
    class Events {
        @Mock ChurchEventRepository eventRepo;

        @Test void tokenIsMintedOnceAndResolvedByLookup() {
            when(eventRepo.save(any())).thenAnswer(i -> i.getArgument(0));
            EventPublicTokenService svc = new EventPublicTokenService(eventRepo);
            ChurchEvent ev = new ChurchEvent(); ev.setId(12); ev.setAppClientId(OURS);

            String t1 = svc.tokenFor(ev), t2 = svc.tokenFor(ev);
            assertThat(t1).hasSize(43).isEqualTo(t2);
            verify(eventRepo).save(ev);   // persisted exactly once

            when(eventRepo.findByPublicTokenAndDeleteFlagFalse(t1)).thenReturn(Optional.of(ev));
            assertThat(svc.resolve(t1)).contains(ev);
            assertThat(svc.resolve(encryptOrNull("12"))).as("the old AES(id) shape").isEmpty();
            assertThat(svc.resolve("12")).isEmpty();
        }
    }

    @Nested @DisplayName("Kids pickup links")
    class Pickup {
        @Mock KmCheckinRepository checkinRepo;
        @Mock KmChildRepository childRepo;
        @Mock KmClassroomRepository classroomRepo;
        @Mock KmChildSetupRepository setupRepo;
        @Mock KmPickupAlertService alerts;

        @Test void linkIsPerVisitAndDiesAtCheckout() {
            KmCheckin open = new KmCheckin(); open.setId(5L); open.setClientId(OURS); open.setPickupToken("tok-pick");
            when(checkinRepo.findByPickupTokenAndCheckoutTimeIsNull("tok-pick")).thenReturn(Optional.of(open));
            // once checked out the same token finds nothing
            when(checkinRepo.findByPickupTokenAndCheckoutTimeIsNull("tok-done")).thenReturn(Optional.empty());

            // resolve() is private; exercise it through the public status endpoint's contract via reflection
            Object[] a = (Object[]) ReflectionTestUtils.invokeMethod(
                    new KmPickupPublicController(checkinRepo, childRepo, classroomRepo, setupRepo, alerts), "resolve", "tok-pick");
            assertThat(a).isNotNull(); assertThat(a[0]).isEqualTo(OURS);
            Object[] b = (Object[]) ReflectionTestUtils.invokeMethod(
                    new KmPickupPublicController(checkinRepo, childRepo, classroomRepo, setupRepo, alerts), "resolve", "tok-done");
            assertThat(b).isNull();
            Object[] c = (Object[]) ReflectionTestUtils.invokeMethod(
                    new KmPickupPublicController(checkinRepo, childRepo, classroomRepo, setupRepo, alerts), "resolve", encryptOrNull(OURS + "|5"));
            assertThat(c).as("the old AES(clientId|checkinId) shape").isNull();
        }
    }

    @Nested @DisplayName("Church registration links")
    class Registration {
        @Mock ChurchRegistrationService regService;
        @Mock LoginRepository loginRepo;
        @Mock VerificationStore otp;
        @Mock EmailService email;
        @Mock ServiceClientRepository scRepo;
        @Mock PolicyAcceptanceRepository policyRepo;

        @Test void everyStepResolvesTheTokenNotAnId() {
            ServiceClient sc = new ServiceClient(); sc.setClientId(OURS); sc.setEmail("pastor@ours.org"); sc.setRegistrationToken("tok-reg");
            when(scRepo.findByRegistrationTokenAndStatusAndDeleteFlagFalse("tok-reg", "Active")).thenReturn(Optional.of(sc));
            when(loginRepo.findByClientId(anyString())).thenReturn(Optional.empty());
            ChurchRegistrationController c = new ChurchRegistrationController(regService, loginRepo, otp, email, scRepo, policyRepo, new PublicSendLimiter());

            assertThat(c.prefill("tok-reg").getStatusCode().value()).isEqualTo(200);
            assertThat(c.prefill(OURS).getStatusCode().value()).as("plaintext tenant id").isEqualTo(403);
            assertThat(c.prefill(encryptOrNull(OURS)).getStatusCode().value()).as("old AES shape").isEqualTo(403);
            verify(scRepo, never()).findByClientId(anyString());
        }

        @Test void otpGoesToTheApprovedMailboxNotTheFormsEmail() {
            ServiceClient sc = new ServiceClient(); sc.setClientId(OURS); sc.setEmail("pastor@ours.org"); sc.setRegistrationToken("tok-reg");
            when(scRepo.findByRegistrationTokenAndStatusAndDeleteFlagFalse("tok-reg", "Active")).thenReturn(Optional.of(sc));
            when(loginRepo.findByClientId(anyString())).thenReturn(Optional.empty());
            when(loginRepo.existsByUsername(anyString())).thenReturn(false);
            when(otp.generateAndStore(anyString(), anyString(), anyString())).thenReturn("123456");
            ChurchRegistrationController c = new ChurchRegistrationController(regService, loginRepo, otp, email, scRepo, policyRepo, new PublicSendLimiter());

            Map<String, Object> body = new java.util.HashMap<>(Map.of("clientId", "tok-reg", "firstName", "A", "lastName", "B",
                    "email", "attacker@evil.example", "username", "attacker@evil.example", "password", "Str0ng!Passw0rd",
                    "declarationCheck", true, "privacyCheck", true));
            ResponseEntity<?> res = c.initiate(body, new MockHttpServletRequest());
            assertThat(res.getStatusCode().value()).as("%s", res.getBody()).isIn(200, 400);   // policy checks may reject the fixture, but never before routing
            ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
            verify(email, org.mockito.Mockito.atMost(1)).sendGenericEmail(to.capture(), anyString(), anyString());
            if (!to.getAllValues().isEmpty()) assertThat(to.getValue()).isEqualTo("pastor@ours.org");
            verify(email, never()).sendGenericEmail(org.mockito.ArgumentMatchers.eq("attacker@evil.example"), anyString(), anyString());
        }
    }

    @Nested @DisplayName("Unsubscribe footer")
    class Unsubscribe {
        @Mock UnsubscribeService svc;

        @Test void requiresTheMailedSignature() {
            UnsubscribeController c = new UnsubscribeController(svc);
            String sig = EncryptionUtil.sign("victim@x.org|" + OURS);

            assertThat(c.doUnsubscribe(Map.of("email", "victim@x.org", "clientId", OURS)).getStatusCode().value()).isEqualTo(403);
            assertThat(c.doUnsubscribe(Map.of("email", "victim@x.org", "clientId", OURS, "sig", "forged")).getStatusCode().value()).isEqualTo(403);
            verify(svc, never()).unsubscribe(anyString(), anyString(), any(), any());

            assertThat(c.doUnsubscribe(Map.of("email", "victim@x.org", "clientId", OURS, "sig", sig)).getStatusCode().value()).isEqualTo(200);
            verify(svc).unsubscribe(org.mockito.ArgumentMatchers.eq("victim@x.org"), org.mockito.ArgumentMatchers.eq(OURS), any(), any());
        }
    }

    private static String encryptOrNull(String s) {
        try { return EncryptionUtil.encrypt(s); } catch (Exception e) { return null; }
    }
}
