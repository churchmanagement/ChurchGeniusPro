package com.churchgeniuspro.security;

import com.churchgeniuspro.controller.*;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.model.ChurchEventBO;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import com.churchgeniuspro.util.PublicFormGuard;
import com.churchgeniuspro.util.PublicSendLimiter;
import com.churchgeniuspro.webfilter.AuthFilter;
import com.churchgeniuspro.webfilter.CspFilter;
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
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Security regression for public-surface audit findings P3–P16 (P11 lives in
 * {@code PublicSurfaceHardeningTest}, P1/P2 in their own classes).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Public surface follow-ups P3–P16")
class PublicSurfaceFollowupTest {

    private static final String OURS = "CHURCH-A";

    final AtomicLong now = new AtomicLong(1_700_000_000_000L);
    PublicSendLimiter limiter;

    @BeforeEach
    void limiter() {
        limiter = new PublicSendLimiter();
        limiter.setClock(now::get);
    }

    static MockHttpServletRequest from(String ip) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setRemoteAddr(ip);
        return r;
    }

    /** The tightest per-origin allowance of a policy (its shortest IP window). */
    static int ipMax(PublicSendLimiter.Policy p) {
        return p.rules().stream().filter(r -> r.scope() == PublicSendLimiter.Scope.IP)
                .min(Comparator.comparingLong(PublicSendLimiter.Rule::windowMs)).orElseThrow().max();
    }

    static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ══════════════════════════════════════════════════════════════════════
    // P3 — kids check-in kiosk
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("P3 — kids check-in kiosk")
    class Kids {
        @Mock KmChildRepository childRepo;
        @Mock KmCheckinRepository checkinRepo;
        @Mock FamilyMemberRepository familyMemberRepo;
        @Mock PublicLinkResolver links;
        PublicKidsCheckinController controller;

        private FamilyMember member(int id, int familyId, String first) {
            Family f = new Family(); f.setId(familyId); f.setAppClientId(OURS);
            FamilyMember m = new FamilyMember(); m.setId(id); m.setFamily(f); m.setFirstName(first); m.setLastName("Rivera");
            m.setRole("Head"); m.setPhone("9135550100"); m.setEmail(first.toLowerCase() + "@example.org");
            return m;
        }

        private KmChild child(long id, int familyMemberId) {
            KmChild c = new KmChild();
            c.setId(id); c.setClientId(OURS); c.setFamilyMemberId(familyMemberId);
            c.setFirstName("Kid"); c.setLastName("Rivera"); c.setDob(LocalDate.now().minusYears(7)); c.setGrade("2nd");
            c.setAllergies("peanuts"); c.setParentName("Ada"); c.setParentPhone("9135550100"); c.setParentEmail("ada@example.org");
            c.setClassroomId(3L);
            return c;
        }

        @BeforeEach
        void setUp() {
            when(links.resolveClientId("tok-kids", PublicPagePolicy.KIDS_CHECKIN_URL)).thenReturn(OURS);
            when(childRepo.findByClientIdAndParentPhone(OURS, "9135550100")).thenReturn(List.of(child(1L, 10)));
            when(familyMemberRepo.findByIdAndTenant(10, OURS)).thenReturn(Optional.of(member(10, 500, "Ada")));
            when(familyMemberRepo.findActiveMembersByFamilyId(500)).thenReturn(List.of(member(10, 500, "Ada"), member(11, 500, "Sam")));
            when(checkinRepo.save(any(KmCheckin.class))).thenAnswer(i -> i.getArgument(0));
            controller = new PublicKidsCheckinController(childRepo, checkinRepo, familyMemberRepo, links, limiter);
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> children(ResponseEntity<?> res) {
            return (List<Map<String, Object>>) ((Map<String, Object>) res.getBody()).get("children");
        }

        @Test
        @DisplayName("the search returns only what the kiosk shows — no DOB, allergy text, classroom or contact details")
        void searchIsMinimal() {
            ResponseEntity<?> res = controller.search("tok-kids", "(913) 555-0100", from("10.0.0.1"));
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            List<Map<String, Object>> kids = children(res);
            assertThat(kids).hasSize(1);
            Map<String, Object> kid = kids.get(0);
            assertThat(kid.keySet()).containsExactlyInAnyOrder("id", "firstName", "lastName", "age", "grade", "hasAllergy", "guardians");
            assertThat(kid).containsEntry("hasAllergy", true).containsEntry("age", 7);
            @SuppressWarnings("unchecked") List<Map<String, Object>> guardians = (List<Map<String, Object>>) kid.get("guardians");
            assertThat(guardians).hasSize(1);
            assertThat(guardians.get(0).keySet()).containsExactlyInAnyOrder("id", "firstName", "lastName", "role");
            assertThat(String.valueOf(res.getBody())).doesNotContain("peanuts", "example.org", "9135550100", "classroomId", "familyId", "dob");
        }

        @Test
        @DisplayName("searches are bounded per origin and per kiosk link")
        void searchIsLimited() {
            int max = ipMax(PublicSendLimiter.KIDS_CHECKIN_SEARCH);
            for (int i = 0; i < max; i++) {
                assertThat(controller.search("tok-kids", "913555" + String.format("%04d", i), from("10.0.0.1")).getStatusCode().value()).isEqualTo(200);
            }
            assertThat(controller.search("tok-kids", "9135559999", from("10.0.0.1")).getStatusCode().value()).isEqualTo(429);
            assertThat(controller.search("tok-kids", "9135559999", from("10.0.0.2")).getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("a caller-chosen family code is never used; the server mints an unpredictable one")
        void callerCodeIsIgnored() {
            when(checkinRepo.findByClientIdAndFamilyCheckinCode(anyString(), anyString())).thenReturn(List.of());
            ResponseEntity<?> res = controller.submit(body("cid", "tok-kids", "phone", "9135550100", "childId", 1, "familyCheckinCode", "Z-0001"));
            assertThat(res.getStatusCode().is2xxSuccessful()).as("%s", res.getBody()).isTrue();
            String code = String.valueOf(((Map<?, ?>) res.getBody()).get("familyCheckinCode"));
            assertThat(code).isNotEqualTo("Z-0001").matches("[A-Z]-\\d{4}");
        }

        @Test
        @DisplayName("a code the server issued minutes ago for the same household is reused so the labels match")
        void recentOwnCodeIsReused() {
            KmCheckin earlier = new KmCheckin();
            earlier.setClientId(OURS); earlier.setChildId(1L); earlier.setFamilyCheckinCode("K-4242");
            earlier.setCheckinTime(LocalDateTime.now().minusMinutes(2));
            when(checkinRepo.findByClientIdAndFamilyCheckinCode(OURS, "K-4242")).thenReturn(List.of(earlier));
            when(checkinRepo.findActiveForChild(OURS, 1L)).thenReturn(List.of(earlier));   // the child row already exists

            ResponseEntity<?> res = controller.submit(body("cid", "tok-kids", "phone", "9135550100", "guardianMemberId", 10, "familyCheckinCode", "K-4242"));
            assertThat(res.getStatusCode().is2xxSuccessful()).as("%s", res.getBody()).isTrue();
            assertThat(((Map<?, ?>) res.getBody()).get("familyCheckinCode")).isEqualTo("K-4242");
        }

        @Test
        @DisplayName("a code from another household, or an old one, is not reused")
        void foreignOrStaleCodeIsNotReused() {
            KmCheckin other = new KmCheckin();
            other.setClientId(OURS); other.setChildId(99L); other.setFamilyCheckinCode("K-4242");
            other.setCheckinTime(LocalDateTime.now().minusMinutes(2));
            KmCheckin stale = new KmCheckin();
            stale.setClientId(OURS); stale.setChildId(1L); stale.setFamilyCheckinCode("K-4242");
            stale.setCheckinTime(LocalDateTime.now().minusHours(3));
            when(checkinRepo.findByClientIdAndFamilyCheckinCode(OURS, "K-4242")).thenReturn(List.of(other, stale));

            ResponseEntity<?> res = controller.submit(body("cid", "tok-kids", "phone", "9135550100", "childId", 1, "familyCheckinCode", "K-4242"));
            assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
            assertThat(((Map<?, ?>) res.getBody()).get("familyCheckinCode")).isNotEqualTo("K-4242");
        }

        @Test
        @DisplayName("a check-in must carry the phone number the household was found with")
        void submitNeedsThePhone() {
            ResponseEntity<?> res = controller.submit(body("cid", "tok-kids", "childId", 1));
            assertThat(res.getStatusCode().value()).isEqualTo(400);
            verify(checkinRepo, never()).save(any());
        }

        @Test
        @DisplayName("a guardian-only check-in must be the designated guardian of a child the phone resolves to")
        void guardianOnlyIsHouseholdBound() {
            when(checkinRepo.findByClientIdAndFamilyCheckinCode(anyString(), anyString())).thenReturn(List.of());
            assertThat(controller.submit(body("cid", "tok-kids", "phone", "9135550100", "guardianMemberId", 11)).getStatusCode().value())
                    .as("Sam is in the household but is not the registered parent").isEqualTo(404);
            assertThat(controller.submit(body("cid", "tok-kids", "phone", "9135550100", "guardianMemberId", 10)).getStatusCode().value())
                    .as("Ada is the registered parent").isEqualTo(200);
            verify(checkinRepo, times(1)).save(any(KmCheckin.class));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // P4 — donations
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("P4 — donation endpoints")
    class Donations {
        @Mock DonationRepository donationRepo;
        @Mock StripeSettingsRepository stripeRepo;
        @Mock PublicScreenLinkRepository linkRepo;
        @Mock ServiceClientRepository clientRepo;
        @Mock ChurchLogoRepository logoRepo;
        @Mock EmailService emailService;
        @Mock SubscriptionService subscriptionService;
        @Mock DonationIncomePostingService poster;
        DonationController controller;

        @BeforeEach
        void setUp() {
            PublicScreenLink l = new PublicScreenLink();
            l.setToken("tok-donate"); l.setAppClientId(OURS); l.setPageUrl("/donate"); l.setRevoked(false);
            when(linkRepo.findByToken("tok-donate")).thenReturn(Optional.of(l));
            controller = new DonationController(donationRepo, stripeRepo, linkRepo, clientRepo, logoRepo, emailService,
                    subscriptionService, poster, limiter);
        }

        @Test
        @DisplayName("one PaymentIntent attempt too many from one origin in ten minutes is refused")
        void intentIsLimited() {
            when(stripeRepo.findByClientId(OURS)).thenReturn(Optional.empty());   // unconfigured → refused before Stripe, still counted
            int max = ipMax(PublicSendLimiter.DONATION_INTENT);
            for (int i = 0; i < max; i++) {
                assertThat(controller.createIntent("tok-donate", body("amount", 25.0, "currency", "usd"), from("10.0.0.1")).getStatusCode().value()).isEqualTo(400);
            }
            ResponseEntity<?> res = controller.createIntent("tok-donate", body("amount", 25.0, "currency", "usd"), from("10.0.0.1"));
            assertThat(res.getStatusCode().value()).isEqualTo(429);
            assertThat(controller.createIntent("tok-donate", body("amount", 25.0, "currency", "usd"), from("10.0.0.2")).getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("a paymentIntentId that is not a Stripe id is refused before any call to Stripe")
        void paymentIntentIdIsValidated() {
            StripeSettings settings = new StripeSettings();
            settings.setPublishableKey("pk_test_x"); settings.setSecretKey("sk_test_x");
            when(stripeRepo.findByClientId(OURS)).thenReturn(Optional.of(settings));
            when(subscriptionService.isFeatureEnabled(OURS, "onlineGiving")).thenReturn(true);
            when(donationRepo.findByStripePaymentIntentId(anyString())).thenReturn(Optional.empty());

            for (String bad : List.of("pi_abc/../charges", "pi_abc?expand[]=customer", "../balance", "pi_", "pi_abc%2F..%2Fcharges")) {
                ResponseEntity<?> res = controller.saveDonation("tok-donate", body("paymentIntentId", bad, "firstName", "Ada"));
                assertThat(res.getStatusCode().value()).as(bad).isEqualTo(400);
                assertThat(String.valueOf(res.getBody())).contains("Invalid paymentIntentId");
            }
            verify(donationRepo, never()).save(any());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // P5 — oracles
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("P5 — lookups and oracles")
    class Oracles {
        @Mock ChurchEventService svc;
        @Mock ChurchLogoRepository logoRepo;
        @Mock ChurchRegistrationRepository churchRegRepo;
        @Mock LoginRepository loginRepo;
        @Mock AppUserRepository appUserRepo;
        @Mock FamilyMemberRepository familyMemberRepo;
        @Mock UsernameRecoveryLogRepository recoveryLogRepo;
        @Mock EmailService emailService;

        @Test
        @DisplayName("the event RSVP lookup is bounded per origin")
        void eventLookupIsLimited() {
            when(svc.lookupRegistrant(anyString(), anyString(), any())).thenReturn(Map.of("found", false));
            ChurchEventController c = new ChurchEventController(svc, logoRepo, churchRegRepo, limiter);
            int max = ipMax(PublicSendLimiter.EVENT_LOOKUP);
            for (int i = 0; i < max; i++) {
                assertThat(c.lookupRegistrant("tok-ev", "p" + i + "@example.org", null, from("10.0.0.1")).getStatusCode().value()).isEqualTo(200);
            }
            assertThat(c.lookupRegistrant("tok-ev", "p99@example.org", null, from("10.0.0.1")).getStatusCode().value()).isEqualTo(429);
            verify(svc, times(max)).lookupRegistrant(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("forgot-username status answers from the caller's own history, never from the identifier's")
        void forgotUsernameStatusIsNotAnOracle() {
            when(recoveryLogRepo.countByIpSince(anyString(), any())).thenReturn(1L);
            when(recoveryLogRepo.countByIdentifierSince(anyString(), any())).thenReturn(4L);
            ForgotUsernameController c = new ForgotUsernameController(loginRepo, appUserRepo, familyMemberRepo, churchRegRepo,
                    recoveryLogRepo, emailService, new PublicFormGuard());

            ResponseEntity<Map<String, Object>> res = c.status("victim@example.org", from("10.0.0.1"));

            assertThat(res.getBody()).containsEntry("attempts", 1L).containsEntry("requireCaptcha", false).containsEntry("blocked", false);
            verify(recoveryLogRepo, never()).countByIdentifierSince(anyString(), any());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // P6 — event codes
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("P6 — event short codes")
    class EventCodes {
        @Mock ChurchEventRepository eventRepo;
        @Mock EventPublicTokenService publicTokens;

        @Test
        @DisplayName("the public page no longer resolves EVT- codes into registration links")
        void noCrossTenantRedirect() {
            EventRegisterPageController c = new EventRegisterPageController(eventRepo, publicTokens);
            ResponseEntity<String> res = c.handleEventRegister("EVT-A3B7C2", from("10.0.0.1"));
            assertThat(res.getStatusCode().value()).isNotEqualTo(302);
            verify(eventRepo, never()).findByEventCodeAndDeleteFlagFalse(anyString());
            verify(publicTokens, never()).tokenFor(any());
        }

        @Test
        @DisplayName("a duplicate code held by another church's event is refused without naming that event")
        void duplicateMessageDoesNotLeakOtherTenants() {
            ChurchEvent other = new ChurchEvent();
            other.setId(3); other.setEventName("Their Secret Gala"); other.setEventCode("EVT-A3B7C2"); other.setAppClientId("CHURCH-B");
            when(eventRepo.findFirstByEventCodeIgnoreCaseAndDeleteFlagFalse("EVT-A3B7C2")).thenReturn(Optional.of(other));
            ChurchEventService svc = new ChurchEventService(eventRepo, mock(ChurchEventDayRepository.class),
                    mock(EventRegistrationRepository.class), mock(EmailService.class), mock(EmailSettingsRepository.class),
                    mock(SmsService.class), mock(FamilyMemberRepository.class), mock(EventEmailTemplateService.class),
                    mock(WhatsAppSenderService.class), mock(SmsOptInRepository.class), mock(ChurchEventImageRepository.class),
                    publicTokens);
            ChurchEventBO bo = new ChurchEventBO();
            bo.setEventName("Ours"); bo.setEventCode("EVT-A3B7C2"); bo.setEventType("One Day");

            assertThatThrownBy(() -> svc.create(bo, OURS, "pastor"))
                    .isInstanceOf(ChurchEventService.DuplicateEventCodeException.class)
                    .hasMessageContaining("EVT-A3B7C2")
                    .hasMessageContaining("another event")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain("Their Secret Gala"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // P7 — anonymous writes
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("P7 — anonymous writes")
    class AnonymousWrites {
        @Mock PolicyAcceptanceRepository acceptanceRepo;
        @Mock NtagLandingService ntagSvc;
        @Mock PublicPageVisitRepository visitRepo;
        @Mock EmailService emailService;

        @Test
        @DisplayName("policy acceptance: unknown policies are refused, sources are clipped, volume is bounded")
        void policyAcceptance() {
            PolicyController c = new PolicyController(acceptanceRepo, limiter);
            assertThat(c.accept(body("policyType", "not-a-policy"), from("10.0.0.1")).getStatusCode().value()).isEqualTo(400);
            int max = ipMax(PublicSendLimiter.POLICY_ACCEPTANCE);
            for (int i = 0; i < max; i++) {
                assertThat(c.accept(body("policyType", "cookie", "source", "x".repeat(500)), from("10.0.0.1")).getStatusCode().value()).isEqualTo(200);
            }
            assertThat(c.accept(body("policyType", "cookie"), from("10.0.0.1")).getStatusCode().value()).isEqualTo(429);
            verify(acceptanceRepo, times(max)).save(argThat(pa -> pa.getSource().length() == 32 && "cookie".equals(pa.getPolicyType())));
        }

        @Test
        @DisplayName("NTAG landing analytics: labels are clipped and excess events are dropped, never errors")
        void ntagTrack() {
            NtagLandingController c = new NtagLandingController(ntagSvc, limiter);
            int max = ipMax(PublicSendLimiter.NTAG_TRACK);
            for (int i = 0; i < max; i++) {
                assertThat(c.track(body("cid", "tok-ntag", "type", "click", "buttonKey", "k", "label", "L".repeat(400)), from("10.0.0.1")).getStatusCode().value()).isEqualTo(200);
            }
            assertThat(c.track(body("cid", "tok-ntag", "type", "click"), from("10.0.0.1")).getStatusCode().value()).isEqualTo(200);
            verify(ntagSvc, times(max)).track(eq("tok-ntag"), eq("click"), eq("k"), argThat(l -> l.length() == 120), anyString(), any());
        }

        @Test
        @DisplayName("marketing-site visit rows stop being written past the per-origin rate; the page still renders")
        void webVisits() {
            PublicWebController c = new PublicWebController(visitRepo, emailService, limiter);
            int max = ipMax(PublicSendLimiter.WEB_VISIT);
            for (int i = 0; i <= max; i++) {
                assertThat(c.page("home", from("10.0.0.1"))).isEqualTo("forward:/web/home.html");
            }
            verify(visitRepo, times(max)).save(any(PublicPageVisit.class));
        }

        @Test
        @DisplayName("GuessIt: code checks are bounded per origin, and joins per origin and per group")
        void guessItCodesAndJoins() {
            GuessItGroupRepository groupRepo = mock(GuessItGroupRepository.class);
            GuessItGroupParticipantRepository participantRepo = mock(GuessItGroupParticipantRepository.class);
            GuessItGroup group = new GuessItGroup();
            group.setId(7L); group.setClientId(OURS); group.setCode("A1768G"); group.setStatus("active");
            when(groupRepo.findFirstByCodeAndStatusAndDeleteFlagFalse("A1768G", "active")).thenReturn(Optional.of(group));
            when(participantRepo.findByGroupIdAndNameKey(anyLong(), anyString())).thenReturn(Optional.empty());
            when(participantRepo.save(any(GuessItGroupParticipant.class))).thenAnswer(i -> { GuessItGroupParticipant p = i.getArgument(0); p.setId(1L); return p; });
            GuessItGroupController c = new GuessItGroupController(groupRepo, participantRepo, mock(GuessItGameRepository.class),
                    mock(GuessItParticipantRepository.class), mock(FamilyMemberRepository.class),
                    new GuessItPlayService(mock(GuessItParticipantRepository.class), participantRepo), limiter);

            // guessing codes: the origin is cut off after the allowance, whatever code it tries
            int verifyMax = ipMax(PublicSendLimiter.GUESSIT_VERIFY);
            for (int i = 0; i < verifyMax; i++) {
                assertThat(c.verifyCode(body("code", "ZZZ" + String.format("%03d", i)), from("10.0.0.1")).getStatusCode().value()).isEqualTo(400);
            }
            assertThat(c.verifyCode(body("code", "A1768G"), from("10.0.0.1")).getStatusCode().value()).isEqualTo(429);
            assertThat(c.verifyCode(body("code", "A1768G"), from("10.0.0.2")).getStatusCode().value()).isEqualTo(200);

            // joins: one participant row each, bounded per group per day
            int joinMax = PublicSendLimiter.GUESSIT_JOIN.rules().stream()
                    .filter(r -> r.scope() == PublicSendLimiter.Scope.TENANT).findFirst().orElseThrow().max();
            int joined = 0;
            for (int ip = 1; joined < joinMax; ip++) {
                for (int k = 0; k < 50 && joined < joinMax; k++, joined++) {
                    ResponseEntity<?> res = c.join(body("code", "A1768G", "name", "p" + joined), from("10.1." + (ip / 250) + "." + (ip % 250)));
                    assertThat(res.getStatusCode().value()).as("join %d from %s", joined, ip).isEqualTo(200);
                }
            }
            ResponseEntity<?> res = c.join(body("code", "A1768G", "name", "one-too-many"), from("10.2.0.1"));
            assertThat(res.getStatusCode().value()).isEqualTo(429);
            verify(participantRepo, times(joinMax)).save(any(GuessItGroupParticipant.class));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // P8 — error bodies
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("P8 — an unhandled exception's message never reaches the caller, only a reference")
    void genericErrorBodies() {
        GlobalExceptionHandler h = new GlobalExceptionHandler();
        ResponseEntity<Map<String, Object>> res = h.handleAll(
                new IllegalStateException("ERROR: duplicate key value violates unique constraint \"uq_signup_username\""),
                from("10.0.0.1"));
        assertThat(res.getStatusCode().value()).isEqualTo(500);
        assertThat(String.valueOf(res.getBody().get("error"))).doesNotContain("duplicate key", "uq_signup_username");
        assertThat(res.getBody()).containsKey("ref");
    }

    // ══════════════════════════════════════════════════════════════════════
    // P9 — headers
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("P9 — security headers")
    class Headers {
        private MockHttpServletResponse run(String path, boolean secure) throws Exception {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
            req.setRequestURI(path); req.setSecure(secure);
            MockHttpServletResponse res = new MockHttpServletResponse();
            new CspFilter().doFilter(req, res, new MockFilterChain());
            return res;
        }

        @Test
        @DisplayName("application pages refuse to be framed and get nosniff / referrer / HSTS")
        void appPages() throws Exception {
            MockHttpServletResponse res = run("/viewusers", true);
            assertThat(res.getHeader("Content-Security-Policy")).contains("frame-ancestors 'self'");
            assertThat(res.getHeader("X-Frame-Options")).isEqualTo("SAMEORIGIN");
            assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(res.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
            assertThat(res.getHeader("Strict-Transport-Security")).isEqualTo("max-age=31536000");
            assertThat(run("/connectAdmin", false).getHeader("X-Frame-Options")).isEqualTo("SAMEORIGIN");
        }

        @Test
        @DisplayName("the public screens stay embeddable on a church's own website; HSTS only over https")
        void publicScreens() throws Exception {
            for (String p : List.of("/donate/tok", "/connect", "/viewEventCalendar", "/event-register/tok", "/web/home", "/kidsCheckin")) {
                MockHttpServletResponse res = run(p, false);
                assertThat(res.getHeader("Content-Security-Policy")).as(p).doesNotContain("frame-ancestors");
                assertThat(res.getHeader("X-Frame-Options")).as(p).isNull();
                assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
                assertThat(res.getHeader("Strict-Transport-Security")).as(p).isNull();
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // P10 — anti-bot tokens
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("P10 — time-trap tokens are not mintable from the JAR")
    class TimeTrap {
        @Test
        void tokensAreBoundToTheProcessKey() {
            PublicFormGuard a = new PublicFormGuard();
            PublicFormGuard b = new PublicFormGuard();
            AtomicLong t = new AtomicLong(1_700_000_000_000L);
            a.setClock(t::get); b.setClock(t::get);
            String token = a.issueToken("tok-cid");
            t.addAndGet((PublicFormGuard.MIN_FORM_SECONDS + 1) * 1000L);
            assertThat(a.checkToken(token, "tok-cid")).as("own token").isNull();
            assertThat(b.checkToken(token, "tok-cid")).as("another process's key").isNotNull();
            assertThat(a.checkToken(token.substring(0, token.length() - 2) + "AA", "tok-cid")).as("tampered signature").isNotNull();
            assertThat(a.checkToken(token, "other-cid")).as("bound to the link").isNotNull();
            assertThat(token).doesNotContain("PFT");   // nothing legible, nothing AES under the legacy key
            assertThatThrownBy(() -> com.churchgeniuspro.util.EncryptionUtil.decrypt(token)).isInstanceOf(Exception.class);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // P12 / P13 / P14 / P15 / P16
    // ══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("P11 — /pub/{token} sends the visitor to the page's own address with the token it reads")
    void legacyPubLinksLandOnThePagesOwnAddress() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("/kidsCheckin", "/kidsCheckin?cid=tok%2B1");
        expected.put("/donate", "/donate/tok%2B1");
        expected.put("/memberSignup", "/memberSignup?token=tok%2B1");
        expected.put("/smsOptIn", "/smsOptIn?token=tok%2B1");
        expected.put("/viewEventCalendar", "/viewEventCalendar?cid=tok%2B1");
        expected.put("/connect", "/connect?c=tok%2B1");
        expected.put("/publicPrayer", "/publicPrayer?c=tok%2B1&src=WEBSITE");
        expected.put("/upcomingEvents", "/upcomingEvents?c=tok%2B1");
        expected.forEach((page, path) -> {
            PublicScreenLink l = new PublicScreenLink();
            l.setPageUrl(page); l.setToken("tok+1");
            assertThat(PublicScreensController.linkPath(l)).as(page).isEqualTo(path);
        });
    }

    @Test
    @DisplayName("P12 — actuator exposure is pinned to health with no details")
    void actuatorPinned() throws Exception {
        Properties p = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) { p.load(in); }
        assertThat(p.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
        assertThat(p.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
    }

    @Test
    @DisplayName("P13 — the registrant self check-in API is reachable without a session")
    void selfCheckinIsWhitelisted() throws Exception {
        AuthFilter f = new AuthFilter(mock(AppUserRepository.class), mock(LoginRepository.class));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/event-checkin/REG1234");
        req.setRequestURI("/api/event-checkin/REG1234");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, res, chain);
        assertThat(res.getStatus()).isNotEqualTo(401);
        assertThat(chain.getRequest()).as("the chain continued").isNotNull();
    }

    @Nested
    @DisplayName("P14 — tenant and internal ids are not in public payloads")
    class Payloads {
        @Mock ChurchEventService svc;
        @Mock ChurchLogoRepository logoRepo;
        @Mock ChurchRegistrationRepository churchRegRepo;
        @Mock SmsOptInRepository optInRepo;
        @Mock FamilyMemberRepository memberRepo;
        @Mock PublicScreenLinkRepository linkRepo;
        @Mock StripeSettingsRepository stripeRepo;
        @Mock SmsService smsService;
        @Mock PublicLinkResolver links;

        @Test
        void eventDetailHasNoTenantId() {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("id", 42); detail.put("eventName", "Picnic"); detail.put("appClientId", OURS);
            when(svc.getPublicDetail("tok-ev")).thenReturn(detail);
            ChurchRegistration cr = new ChurchRegistration(); cr.setChurchName("Church A");
            when(churchRegRepo.findByClientIdAndDeleteFlagFalse(OURS)).thenReturn(Optional.of(cr));
            ChurchEventController c = new ChurchEventController(svc, logoRepo, churchRegRepo, limiter);

            ResponseEntity<Map<String, Object>> res = c.getPublicDetail("tok-ev");
            assertThat(res.getBody()).containsEntry("churchName", "Church A").doesNotContainKey("appClientId");
        }

        @Test
        void smsChurchNameHasNoTenantId() {
            when(links.resolveClientId("tok-sms", PublicPagePolicy.SMS_OPT_IN_URL)).thenReturn(OURS);
            ChurchRegistration cr = new ChurchRegistration(); cr.setChurchName("Church A");
            when(churchRegRepo.findByClientIdAndDeleteFlagFalse(OURS)).thenReturn(Optional.of(cr));
            SmsOptInController c = new SmsOptInController(optInRepo, churchRegRepo, memberRepo, linkRepo, stripeRepo, smsService, links, limiter);

            ResponseEntity<Map<String, Object>> res = c.getChurchName("tok-sms");
            assertThat(res.getBody()).containsEntry("found", true).containsEntry("churchName", "Church A").doesNotContainKey("appClientId");
        }
    }

    @Test
    @DisplayName("P15 — Open Graph URLs come from app.base-url, not from X-Forwarded-Host")
    void ogUrlsIgnoreForwardedHost() {
        ChurchEventRepository eventRepo = mock(ChurchEventRepository.class);
        EventPublicTokenService publicTokens = mock(EventPublicTokenService.class);
        ChurchEvent ev = new ChurchEvent(); ev.setId(1); ev.setEventName("Picnic"); ev.setImagePresent(false);
        when(publicTokens.resolve("tok-ev")).thenReturn(Optional.of(ev));
        EventRegisterPageController c = new EventRegisterPageController(eventRepo, publicTokens);
        ReflectionTestUtils.setField(c, "appBaseUrl", "https://churchgeniuspro.net/");
        MockHttpServletRequest req = from("10.0.0.1");
        req.addHeader("X-Forwarded-Host", "evil.example");
        req.addHeader("X-Forwarded-Proto", "http");

        ResponseEntity<String> res = c.handleEventRegister("tok-ev", req);
        assertThat(res.getStatusCode().value()).as("the page template is on the classpath").isEqualTo(200);
        assertThat(res.getBody()).contains("https://churchgeniuspro.net/event-register/tok-ev").doesNotContain("evil.example");
    }

    @Test
    @DisplayName("P16 — the session debug dump is gone")
    void debugEndpointRemoved() {
        Set<String> mapped = new HashSet<>();
        for (Method m : MembershipFormController.class.getDeclaredMethods()) {
            GetMapping g = m.getAnnotation(GetMapping.class);
            if (g != null) mapped.addAll(Arrays.asList(g.value()));
        }
        assertThat(mapped).doesNotContain("/api/member/debug");
    }
}
