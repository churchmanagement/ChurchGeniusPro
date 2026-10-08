package com.churchgeniuspro.security;

import com.churchgeniuspro.controller.*;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.model.EventRegistrationBO;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import com.churchgeniuspro.util.PublicSendLimiter;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Security regression for audit finding P2 — the public endpoints that send an SMS,
 * a verification code, a confirmation e-mail or an admin notification on an anonymous
 * caller's behalf are bounded per network origin, per target and per church by
 * {@link PublicSendLimiter}, and the SMS opt-in form no longer un-confirms a subscriber.
 *
 * <p>Real {@link PublicSendLimiter} (with a controllable clock) behind each real
 * controller, over mocked repositories and senders.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("P2 — anonymous message-sending endpoints are rate-limited")
class PublicSendLimitsTest {

    private static final String OURS   = "CHURCH-A";
    private static final String THEIRS = "CHURCH-B";

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

    static PublicScreenLink link(String token, String clientId, String page) {
        PublicScreenLink l = new PublicScreenLink();
        l.setToken(token);
        l.setAppClientId(clientId);
        l.setPageUrl(page);
        l.setRevoked(false);
        return l;
    }

    // ══════════════════════════════════════════════════════════════════════
    // The limiter itself
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("limiter")
    class Limiter {

        final PublicSendLimiter.Policy P = new PublicSendLimiter.Policy("t", List.of(
                new PublicSendLimiter.Rule(PublicSendLimiter.Scope.IP, 3, PublicSendLimiter.MIN10),
                new PublicSendLimiter.Rule(PublicSendLimiter.Scope.TARGET, 2, PublicSendLimiter.HOUR),
                new PublicSendLimiter.Rule(PublicSendLimiter.Scope.TENANT, 4, PublicSendLimiter.DAY)));

        @Test
        @DisplayName("the IP window refuses the (max+1)th send and lets it through once the window slides")
        void ipWindowSlides() {
            for (int i = 0; i < 3; i++) assertThat(limiter.check(P, "10.0.0.1", "t" + i, null)).isNull();
            assertThat(limiter.check(P, "10.0.0.1", "t9", null)).isEqualTo(PublicSendLimiter.IP_MSG);
            assertThat(limiter.check(P, "10.0.0.2", "t9", null)).as("another origin is unaffected").isNull();
            now.addAndGet(PublicSendLimiter.MIN10 + 1);
            assertThat(limiter.check(P, "10.0.0.1", "t9", null)).isNull();
        }

        @Test
        @DisplayName("the target rule holds across different (or forged) origins")
        void targetRuleIsOriginIndependent() {
            assertThat(limiter.check(P, "10.0.0.1", "victim@example.org", null)).isNull();
            assertThat(limiter.check(P, "10.0.0.2", "Victim@Example.org ", null)).as("case/space-insensitive").isNull();
            assertThat(limiter.check(P, "10.0.0.3", "victim@example.org", null)).isEqualTo(PublicSendLimiter.TARGET_MSG);
            assertThat(limiter.check(P, "10.0.0.3", "someone.else@example.org", null)).isNull();
        }

        @Test
        @DisplayName("phone numbers are one target however they are typed")
        void phoneFormatsShareOneKey() {
            assertThat(limiter.check(P, "10.0.0.1", "(913) 555-0100", null)).isNull();
            assertThat(limiter.check(P, "10.0.0.2", "913.555.0100", null)).isNull();
            assertThat(limiter.check(P, "10.0.0.3", "9135550100", null)).isEqualTo(PublicSendLimiter.TARGET_MSG);
        }

        @Test
        @DisplayName("the tenant ceiling caps the day whatever the origin or target")
        void tenantCeiling() {
            for (int i = 0; i < 4; i++) assertThat(limiter.check(P, "10.0.0." + i, "t" + i, OURS)).isNull();
            assertThat(limiter.check(P, "10.0.0.9", "t9", OURS)).isEqualTo(PublicSendLimiter.TENANT_MSG);
            assertThat(limiter.check(P, "10.0.0.9", "t9", THEIRS)).as("another church is unaffected").isNull();
            now.addAndGet(PublicSendLimiter.DAY + 1);
            assertThat(limiter.check(P, "10.0.0.9", "t9", OURS)).isNull();
        }

        @Test
        @DisplayName("a refused request is not recorded, so a blocked caller cannot extend its own block")
        void refusalsAreNotRecorded() {
            for (int i = 0; i < 3; i++) limiter.check(P, "10.0.0.1", "t" + i, null);
            now.addAndGet(PublicSendLimiter.MIN10 - 1_000);
            assertThat(limiter.check(P, "10.0.0.1", "x", null)).isEqualTo(PublicSendLimiter.IP_MSG);
            now.addAndGet(2_000);   // the original three have now aged out; the refusal must not count
            assertThat(limiter.check(P, "10.0.0.1", "x", null)).isNull();
        }

        @Test
        @DisplayName("null target/tenant simply skip those rules; the emergency switch disables every rule")
        void nullsAndSwitch() {
            for (int i = 0; i < 3; i++) assertThat(limiter.check(P, "10.0.0.1", null, null)).isNull();
            assertThat(limiter.check(P, "10.0.0.1", null, null)).isEqualTo(PublicSendLimiter.IP_MSG);
            limiter.setEnabled(false);
            assertThat(limiter.check(P, "10.0.0.1", null, null)).isNull();
        }

        @Test
        @DisplayName("X-Forwarded-For is honoured or ignored exactly as the login protection is configured")
        void forwardedHeaderPolicy() {
            MockHttpServletRequest r = from("10.0.0.1");
            r.addHeader("X-Forwarded-For", "203.0.113.7:54321");
            assertThat(limiter.clientIp(r)).isEqualTo("203.0.113.7");
            limiter.setTrustForwardedHeaders(false);
            assertThat(limiter.clientIp(r)).isEqualTo("10.0.0.1");
        }

        @Test
        @DisplayName("every policy has an IP rule, and every sending policy a target or tenant rule")
        void policiesAreComplete() {
            List<PublicSendLimiter.Policy> all = List.of(
                    PublicSendLimiter.SMS_OPT_IN, PublicSendLimiter.EVENT_REGISTRATION,
                    PublicSendLimiter.MEMBERSHIP_SUBMIT, PublicSendLimiter.MEMBERSHIP_OTP,
                    PublicSendLimiter.MEMBER_SIGNUP_LOOKUP, PublicSendLimiter.MEMBER_SIGNUP_OTP,
                    PublicSendLimiter.CHURCH_REGISTRATION_OTP, PublicSendLimiter.SIGNUP_OTP,
                    PublicSendLimiter.WEB_CONTACT);
            Set<String> names = new HashSet<>();
            for (PublicSendLimiter.Policy p : all) {
                assertThat(names.add(p.form())).as("distinct form key: " + p.form()).isTrue();
                assertThat(p.rules()).anyMatch(r -> r.scope() == PublicSendLimiter.Scope.IP);
                if (p != PublicSendLimiter.MEMBER_SIGNUP_LOOKUP) {
                    assertThat(p.rules()).as(p.form()).anyMatch(r -> r.scope() != PublicSendLimiter.Scope.IP);
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // SMS opt-in
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/public/sms-opt-in")
    class SmsOptInForm {
        @Mock SmsOptInRepository optInRepo;
        @Mock ChurchRegistrationRepository churchRepo;
        @Mock FamilyMemberRepository memberRepo;
        @Mock PublicScreenLinkRepository linkRepo;
        @Mock StripeSettingsRepository stripeRepo;
        @Mock SmsService smsService;
        @Mock SubscriptionService subscriptions;
        @Mock MessagingPolicy messagingPolicy;
        SmsOptInController controller;

        @BeforeEach
        void setUp() {
            when(linkRepo.findByToken("tok-sms")).thenReturn(Optional.of(link("tok-sms", OURS, "/smsOptIn")));
            when(optInRepo.findByPhoneNumberAndAppClientId(anyString(), anyString())).thenReturn(Optional.empty());
            when(smsService.sendConfirmationRequest(anyString(), anyString(), anyString())).thenReturn(true);
            controller = new SmsOptInController(optInRepo, churchRepo, memberRepo, linkRepo, stripeRepo, smsService,
                    new PublicLinkResolver(linkRepo, new PublicPagePolicy(subscriptions, messagingPolicy)), limiter);
        }

        private Map<String, Object> body(String phone) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("token", "tok-sms"); b.put("firstName", "Ada"); b.put("lastName", "Lovelace");
            b.put("phoneNumber", phone); b.put("consent", true);
            return b;
        }

        @Test
        @DisplayName("a third text to the same number within an hour is refused and not sent")
        void perNumber() {
            assertThat(controller.submitOptIn(body("913-555-0100"), from("10.0.0.1")).getStatusCode().value()).isEqualTo(200);
            assertThat(controller.submitOptIn(body("(913) 555-0100"), from("10.0.0.2")).getStatusCode().value()).isEqualTo(200);
            ResponseEntity<Map<String, Object>> third = controller.submitOptIn(body("9135550100"), from("10.0.0.3"));
            assertThat(third.getStatusCode().value()).isEqualTo(429);
            assertThat(third.getBody()).containsEntry("status", "error").containsEntry("message", PublicSendLimiter.TARGET_MSG);
            verify(smsService, times(2)).sendConfirmationRequest(anyString(), anyString(), anyString());
            verify(optInRepo, times(2)).save(any(SmsOptIn.class));
        }

        @Test
        @DisplayName("one origin cannot text more than ten numbers in ten minutes")
        void perOrigin() {
            for (int i = 0; i < 10; i++) {
                assertThat(controller.submitOptIn(body("91355501" + String.format("%02d", 10 + i)), from("10.0.0.1"))
                        .getStatusCode().value()).isEqualTo(200);
            }
            assertThat(controller.submitOptIn(body("9135550199"), from("10.0.0.1")).getStatusCode().value()).isEqualTo(429);
            assertThat(controller.submitOptIn(body("9135550199"), from("10.0.0.2")).getStatusCode().value()).isEqualTo(200);
            verify(smsService, times(11)).sendConfirmationRequest(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("a confirmed subscriber is left exactly as they are — no rename, no un-confirm, no text")
        void confirmedSubscriberIsUntouched() {
            SmsOptIn existing = new SmsOptIn();
            ReflectionTestUtils.setField(existing, "id", 5); existing.setAppClientId(OURS); existing.setPhoneNumber("+19135550100");
            existing.setFirstName("Real"); existing.setLastName("Subscriber");
            existing.setConsent(true); existing.setConfirmed(true);
            when(optInRepo.findByPhoneNumberAndAppClientId("+19135550100", OURS)).thenReturn(Optional.of(existing));

            Map<String, Object> b = body("9135550100");
            b.put("firstName", "Mallory"); b.put("lastName", "Intruder");
            ResponseEntity<Map<String, Object>> res = controller.submitOptIn(b, from("10.0.0.1"));

            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(res.getBody()).containsEntry("status", "success").containsEntry("smsSent", false);
            assertThat(existing.isConfirmed()).isTrue();
            assertThat(existing.getFirstName()).isEqualTo("Real");
            verify(optInRepo, never()).save(any());
            verify(smsService, never()).sendConfirmationRequest(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("an unconfirmed (or opted-out) number still gets its confirmation text")
        void pendingSubscriberStillGetsTheText() {
            SmsOptIn pending = new SmsOptIn();
            ReflectionTestUtils.setField(pending, "id", 6); pending.setAppClientId(OURS); pending.setPhoneNumber("+19135550100");
            pending.setConsent(false); pending.setConfirmed(false);
            when(optInRepo.findByPhoneNumberAndAppClientId("+19135550100", OURS)).thenReturn(Optional.of(pending));

            ResponseEntity<Map<String, Object>> res = controller.submitOptIn(body("9135550100"), from("10.0.0.1"));

            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(pending.isConsent()).isTrue();
            verify(optInRepo).save(pending);
            verify(smsService).sendConfirmationRequest(eq("+19135550100"), anyString(), eq(OURS));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Event RSVP
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST/PUT /api/event-register/{token}")
    class EventRsvp {
        @Mock ChurchEventService svc;
        @Mock ChurchLogoRepository logoRepo;
        @Mock ChurchRegistrationRepository churchRegRepo;
        ChurchEventController controller;

        @BeforeEach
        void setUp() {
            EventRegistration reg = new EventRegistration();
            reg.setId(1);
            when(svc.registerByToken(anyString(), any())).thenReturn(reg);
            when(svc.updateRegistration(anyString(), anyString(), any())).thenReturn(reg);
            controller = new ChurchEventController(svc, logoRepo, churchRegRepo, limiter);
        }

        private EventRegistrationBO rsvp(String email) {
            EventRegistrationBO bo = new EventRegistrationBO();
            bo.setFirstName("Ada"); bo.setLastName("Lovelace"); bo.setEmail(email);
            return bo;
        }

        @Test
        @DisplayName("a fourth RSVP for one address within ten minutes is refused before anything is saved or e-mailed")
        void perAddress() {
            for (int i = 0; i < 3; i++) {
                assertThat(controller.submitRegistration("tok-ev", rsvp("ada@example.org"), from("10.0.0." + i))
                        .getStatusCode().value()).isEqualTo(200);
            }
            ResponseEntity<Map<String, Object>> fourth = controller.updateRegistration("tok-ev", "ada@example.org", rsvp("ADA@example.org"), from("10.0.0.9"));
            assertThat(fourth.getStatusCode().value()).isEqualTo(429);
            assertThat(fourth.getBody()).containsEntry("error", PublicSendLimiter.TARGET_MSG);
            verify(svc, times(3)).registerByToken(anyString(), any());
            verify(svc, never()).updateRegistration(anyString(), anyString(), any());
            assertThat(controller.submitRegistration("tok-ev", rsvp("bob@example.org"), from("10.0.0.9")).getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("one origin cannot register more than thirty times in ten minutes")
        void perOrigin() {
            for (int i = 0; i < 30; i++) {
                assertThat(controller.submitRegistration("tok-ev", rsvp("p" + i + "@example.org"), from("10.0.0.1"))
                        .getStatusCode().value()).isEqualTo(200);
            }
            assertThat(controller.submitRegistration("tok-ev", rsvp("p99@example.org"), from("10.0.0.1")).getStatusCode().value()).isEqualTo(429);
            assertThat(controller.submitRegistration("tok-ev", rsvp("p99@example.org"), from("10.0.0.2")).getStatusCode().value()).isEqualTo(200);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Membership form
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("membership form: submit / resend-otp")
    class MembershipForm {
        @Mock PublicScreenLinkRepository        linkRepo;
        @Mock MembershipFamilyRepository        mfRepo;
        @Mock MembershipFamilyMemberRepository  mfmRepo;
        @Mock FamilyRepository                  familyRepo;
        @Mock FamilyMemberRepository            familyMemberRepo;
        @Mock ChurchRegistrationRepository      churchRegRepo;
        @Mock ChurchLogoRepository              logoRepo;
        @Mock VerificationCodeRepository        codeRepo;
        @Mock EmailService                      emailService;
        @Mock LoginRepository                   loginRepository;
        @Mock AppUserRepository                 appUserRepository;
        @Mock SubSourceRepository               subSourceRepo;
        @Mock IncomeRepository                  incomeRepo;
        @Mock MeetingRepository                 meetingRepo;
        @Mock ChurchEventRepository             churchEventRepo;
        @Mock ChurchEventDayRepository          churchEventDayRepo;
        @Mock MemberMessageRepository           memberMessageRepo;
        @Mock MemberPreferenceRepository        memberPrefRepo;
        @Mock WhatsAppSenderService             whatsAppSenderService;
        @Mock GroupRepository                   groupRepo;
        @Mock GroupMemberRepository             groupMemberRepo;
        @Mock WorshipGroupRepository            worshipGroupRepo;
        @Mock WorshipGroupMemberRepository      worshipGroupMemberRepo;
        @Mock WorshipInstrumentRepository       worshipInstrumentRepo;
        @Mock WorshipAssignmentRepository       worshipAssignmentRepo;
        @Mock WorshipAssignmentMemberRepository worshipAssignmentMemberRepo;
        @Mock WorshipSongRepository             worshipSongRepo;
        @Mock EventCalendarController           eventCalendarController;
        @Mock PledgeCampaignRepository          pledgeCampaignRepo;
        @Mock PledgeMemberRepository            pledgeMemberRepo;
        @Mock PledgeController                  pledgeController;
        MembershipFormController controller;
        final AtomicReference<VerificationCode> stored = new AtomicReference<>();
        final List<MembershipFamily> staged = new ArrayList<>();

        @BeforeEach
        void setUp() {
            when(codeRepo.findByClientIdAndType(anyString(), anyString())).thenAnswer(i -> Optional.ofNullable(stored.get()));
            doAnswer(i -> { stored.set(null); return null; }).when(codeRepo).deleteByClientIdAndType(anyString(), anyString());
            when(codeRepo.save(any(VerificationCode.class))).thenAnswer(i -> { stored.set(i.getArgument(0)); return i.getArgument(0); });
            when(mfRepo.save(any(MembershipFamily.class))).thenAnswer(i -> { MembershipFamily mf = i.getArgument(0); mf.setId(500 + staged.size()); staged.add(mf); return mf; });
            when(mfmRepo.save(any(MembershipFamilyMember.class))).thenAnswer(i -> i.getArgument(0));
            when(linkRepo.findByToken("tok-form")).thenReturn(Optional.of(link("tok-form", OURS, "/membershipForm")));
            Family f = new Family(); f.setId(77); f.setAppClientId(OURS); f.setDeleteFlag(false);
            when(familyRepo.findById(77)).thenReturn(Optional.of(f));
            AppUser admin = new AppUser(); admin.setEmail("pastor@church-a.org"); admin.setFirstName("Pat");
            when(appUserRepository.findAdminsByClientId(anyString())).thenReturn(List.of(admin));
            controller = new MembershipFormController(linkRepo, mfRepo, mfmRepo, familyRepo, familyMemberRepo,
                    churchRegRepo, logoRepo, new VerificationStore(codeRepo), emailService, loginRepository, appUserRepository,
                    subSourceRepo, incomeRepo, meetingRepo, churchEventRepo, churchEventDayRepo, memberMessageRepo,
                    memberPrefRepo, whatsAppSenderService, groupRepo, groupMemberRepo, worshipGroupRepo,
                    worshipGroupMemberRepo, worshipInstrumentRepo, worshipAssignmentRepo, worshipAssignmentMemberRepo,
                    worshipSongRepo, eventCalendarController, pledgeCampaignRepo, pledgeMemberRepo, pledgeController,
                    limiter);
        }

        private Map<String, Object> submission(Object photo) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", "Head"); m.put("firstName", "Ada"); m.put("lastName", "Lovelace");
            if (photo != null) m.put("photo", photo);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("cid", "tok-form"); body.put("declarationAccepted", true); body.put("members", List.of(m));
            return body;
        }

        @Test
        @DisplayName("an eleventh submission from one origin in ten minutes is refused: nothing staged, no admin mail")
        void submitPerOrigin() {
            for (int i = 0; i < 10; i++) {
                assertThat(controller.submit(submission(null), from("10.0.0.1")).getStatusCode().value()).isEqualTo(200);
            }
            ResponseEntity<Map<String, Object>> res = controller.submit(submission(null), from("10.0.0.1"));
            assertThat(res.getStatusCode().value()).isEqualTo(429);
            assertThat(staged).hasSize(10);
            verify(emailService, times(10)).sendOrgEmail(anyString(), anyString(), anyString(), anyString());
            assertThat(controller.submit(submission(null), from("10.0.0.2")).getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("a photo must be an image data-URL under the page's 2 MB cap")
        void photoCap() {
            assertThat(controller.submit(submission("data:image/png;base64,AAAA"), from("10.0.0.1")).getStatusCode().value()).isEqualTo(200);
            ResponseEntity<Map<String, Object>> notImage = controller.submit(submission("data:text/html;base64,PHNjcmlwdD4="), from("10.0.0.1"));
            assertThat(notImage.getStatusCode().value()).isEqualTo(400);
            ResponseEntity<Map<String, Object>> huge = controller.submit(
                    submission("data:image/png;base64," + "A".repeat(MembershipFormController.MAX_PHOTO_CHARS)), from("10.0.0.1"));
            assertThat(huge.getStatusCode().value()).isEqualTo(400);
            assertThat(staged).hasSize(1);
        }

        @Test
        @DisplayName("a fourth code for one family within ten minutes is refused and not e-mailed")
        void resendPerFamily() {
            VerificationCode vc = new VerificationCode();
            vc.setClientId("77"); vc.setType("member-lookup"); vc.setCode("123456"); vc.setEmail("head@example.org");
            vc.setExpiresAt(LocalDateTime.now().plusMinutes(10));
            stored.set(vc);
            Map<String, Object> body = Map.of("cid", "tok-form", "familyId", 77);

            for (int i = 0; i < 3; i++) {
                assertThat(controller.resendOtp(body, from("10.0.0." + i)).getStatusCode().value()).isEqualTo(200);
            }
            ResponseEntity<Map<String, Object>> fourth = controller.resendOtp(body, from("10.0.0.9"));
            assertThat(fourth.getStatusCode().value()).isEqualTo(429);
            assertThat(fourth.getBody()).containsEntry("error", PublicSendLimiter.TARGET_MSG);
            verify(emailService, times(3)).sendGenericEmail(eq("head@example.org"), anyString(), anyString());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Member signup
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("member signup: lookup / send-code")
    class MemberSignup {
        @Mock PublicScreenLinkRepository linkRepo;
        @Mock FamilyMemberRepository familyMemberRepository;
        @Mock LoginRepository loginRepository;
        @Mock ChurchRegistrationRepository churchRegistrationRepository;
        @Mock VerificationStore verificationStore;
        @Mock EmailService emailService;
        @Mock SubscriptionService subscriptionService;
        MemberSignupController controller;

        @BeforeEach
        void setUp() {
            when(linkRepo.findByToken("tok-ms")).thenReturn(Optional.of(link("tok-ms", OURS, "/memberSignup")));
            FamilyMember ada = new FamilyMember();
            ada.setId(1); ada.setFirstName("Ada"); ada.setLastName("Lovelace"); ada.setEmail("ada@example.org");
            ada.setAppClientId(OURS); ada.setMemberRef("MBR-ada");
            when(familyMemberRepository.findByLastNameAndContact(eq("Lovelace"), anyString(), eq(OURS))).thenReturn(List.of(ada));
            when(familyMemberRepository.findByMemberRef("MBR-ada")).thenReturn(Optional.of(ada));
            when(loginRepository.findByClientId(anyString())).thenReturn(Optional.empty());
            when(loginRepository.existsByUsername(anyString())).thenReturn(false);
            when(verificationStore.generateAndStore(anyString(), anyString(), anyString())).thenReturn("123456");
            when(emailService.getChurchName(anyString())).thenReturn("Church A");
            controller = new MemberSignupController(linkRepo, familyMemberRepository, loginRepository,
                    churchRegistrationRepository, verificationStore, emailService, subscriptionService, limiter);
        }

        private Map<String, String> lookupBody() {
            return Map.of("appClientId", "tok-ms", "firstName", "Ada", "lastName", "Lovelace", "contact", "ada@example.org");
        }

        @Test
        @DisplayName("a fourth code for one member within ten minutes is refused")
        void sendCodePerMember() {
            ResponseEntity<Map<String, Object>> found = controller.lookup(lookupBody(), from("10.0.0.1"));
            assertThat(found.getStatusCode().value()).as("%s", found.getBody()).isEqualTo(200);
            String handle = String.valueOf(found.getBody().get("memberRef"));
            Map<String, String> send = Map.of("appClientId", "tok-ms", "memberRef", handle, "username", "ada", "password", "Str0ng!Passw0rd");

            for (int i = 0; i < 3; i++) {
                assertThat(controller.sendCode(send, from("10.0.0." + i)).getStatusCode().value()).isEqualTo(200);
            }
            ResponseEntity<Map<String, Object>> fourth = controller.sendCode(send, from("10.0.0.9"));
            assertThat(fourth.getStatusCode().value()).isEqualTo(429);
            assertThat(fourth.getBody()).containsEntry("status", "error").containsEntry("message", PublicSendLimiter.TARGET_MSG);
            verify(emailService, times(3)).sendAccountEmail(eq("ada@example.org"), anyString(), anyString(), eq(OURS));
        }

        @Test
        @DisplayName("one origin cannot probe the roster more than twenty times in ten minutes")
        void lookupPerOrigin() {
            for (int i = 0; i < 20; i++) {
                assertThat(controller.lookup(lookupBody(), from("10.0.0.1")).getStatusCode().value()).isEqualTo(200);
            }
            assertThat(controller.lookup(lookupBody(), from("10.0.0.1")).getStatusCode().value()).isEqualTo(429);
            assertThat(controller.lookup(lookupBody(), from("10.0.0.2")).getStatusCode().value()).isEqualTo(200);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Church registration initiate / invitation send-code / website contact
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("church registration initiate")
    class ChurchRegistration {
        @Mock ChurchRegistrationService regService;
        @Mock LoginRepository loginRepo;
        @Mock VerificationStore otp;
        @Mock EmailService email;
        @Mock ServiceClientRepository scRepo;
        @Mock PolicyAcceptanceRepository policyRepo;

        @Test
        @DisplayName("a fourth code for one church within ten minutes is refused and not e-mailed")
        void perChurch() {
            ServiceClient sc = new ServiceClient();
            sc.setClientId(OURS); sc.setEmail("pastor@church-a.org"); sc.setChurchName("Church A");
            when(scRepo.findByRegistrationTokenAndStatusAndDeleteFlagFalse("tok-reg", "Active")).thenReturn(Optional.of(sc));
            when(loginRepo.existsByUsername(anyString())).thenReturn(false);
            when(loginRepo.findByClientId(anyString())).thenReturn(Optional.empty());
            when(otp.generateAndStore(anyString(), anyString(), anyString())).thenReturn("123456");
            ChurchRegistrationController c = new ChurchRegistrationController(regService, loginRepo, otp, email, scRepo, policyRepo, limiter);
            Map<String, Object> body = new HashMap<>(Map.of("clientId", "tok-reg", "firstName", "A", "lastName", "B",
                    "email", "owner@church-a.org", "username", "owner.a", "password", "Str0ng!Passw0rd",
                    "declarationCheck", true, "privacyCheck", true));

            for (int i = 0; i < 3; i++) {
                assertThat(c.initiate(body, from("10.0.0." + i)).getStatusCode().value()).isEqualTo(200);
            }
            ResponseEntity<Map<String, Object>> fourth = c.initiate(body, from("10.0.0.9"));
            assertThat(fourth.getStatusCode().value()).isEqualTo(429);
            verify(email, times(3)).sendGenericEmail(eq("pastor@church-a.org"), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("invitation sign-up send-code")
    class InvitationSignup {
        @Mock SignupService signupService;
        @Mock LoginRepository loginRepo;
        @Mock AppUserRepository appUserRepo;
        @Mock ChurchRegistrationRepository churchRepo;
        @Mock ServiceClientRepository scRepo;
        @Mock VerificationStore otp;
        @Mock EmailService email;

        @Test
        @DisplayName("a fourth code for one invitation within ten minutes is refused and not e-mailed")
        void perInvitation() {
            AppUser invitee = new AppUser();
            invitee.setUserId("USR-1"); invitee.setEmail("new@ours.org"); invitee.setClientId(OURS); invitee.setInviteToken("inv-3");
            when(appUserRepo.findByInviteTokenAndDeleteFlagFalse("inv-3")).thenReturn(Optional.of(invitee));
            when(appUserRepo.findByUserIdAndDeleteFlagFalse("USR-1")).thenReturn(Optional.of(invitee));
            when(loginRepo.existsByUsername(anyString())).thenReturn(false);
            when(otp.generateAndStore(anyString(), anyString(), anyString())).thenReturn("123456");
            when(email.getChurchName(anyString())).thenReturn("Ours");
            SignupController c = new SignupController(signupService, loginRepo, appUserRepo, churchRepo, scRepo, otp, email, limiter);
            Map<String, String> body = Map.of("clientId", "inv-3", "username", "new.user", "password", "Str0ng!Passw0rd");

            for (int i = 0; i < 3; i++) {
                assertThat(c.sendCode(body, from("10.0.0." + i)).getStatusCode().value()).isEqualTo(200);
            }
            assertThat(c.sendCode(body, from("10.0.0.9")).getStatusCode().value()).isEqualTo(429);
            verify(email, times(3)).sendGenericEmail(eq("new@ours.org"), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("website contact form")
    class WebContact {
        @Mock PublicPageVisitRepository visitRepo;
        @Mock EmailService email;

        @Test
        @DisplayName("a fourth message from one origin in ten minutes is refused and not e-mailed")
        void perOrigin() {
            PublicWebController c = new PublicWebController(visitRepo, email, limiter);
            for (int i = 0; i < 3; i++) {
                Map<String, Object> body = Map.of("name", "Visitor", "email", "v" + i + "@example.org", "request", "Hello");
                assertThat(c.contact(body, from("10.0.0.1")).getStatusCode().value()).isEqualTo(200);
            }
            ResponseEntity<Map<String, Object>> fourth = c.contact(Map.of("name", "Visitor", "email", "v9@example.org", "request", "Hello"), from("10.0.0.1"));
            assertThat(fourth.getStatusCode().value()).isEqualTo(429);
            assertThat(fourth.getBody()).containsEntry("status", "error");
            verify(email, times(3)).sendGenericEmail(anyString(), anyString(), anyString());
        }
    }
}
