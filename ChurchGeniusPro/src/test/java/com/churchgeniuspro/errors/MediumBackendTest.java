package com.churchgeniuspro.errors;

import com.churchgeniuspro.controller.CheckScanController;
import com.churchgeniuspro.controller.EventVolunteerController;
import com.churchgeniuspro.controller.NtagLoginController;
import com.churchgeniuspro.controller.PledgeController;
import com.churchgeniuspro.controller.SmsOptInController;
import com.churchgeniuspro.controller.VolunteerController;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.hibernate.SmsOptIn;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.hibernate.TransactionType;
import com.churchgeniuspro.hibernate.VolunteerAssignment;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.ChurchEventRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.EventVolunteerRepository;
import com.churchgeniuspro.repository.EventVolunteerRoleRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import com.churchgeniuspro.repository.IncomeCheckImageRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import com.churchgeniuspro.repository.SmsOptInRepository;
import com.churchgeniuspro.repository.StripeSettingsRepository;
import com.churchgeniuspro.repository.SubSourceRepository;
import com.churchgeniuspro.repository.TransactionTypeRepository;
import com.churchgeniuspro.repository.VolunteerAssignmentRepository;
import com.churchgeniuspro.repository.VolunteerAttendanceRepository;
import com.churchgeniuspro.repository.VolunteerProfileRepository;
import com.churchgeniuspro.repository.VolunteerProfileRoleRepository;
import com.churchgeniuspro.repository.VolunteerRoleRepository;
import com.churchgeniuspro.service.CheckExtractor;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.IncomeService;
import com.churchgeniuspro.service.NtagService;
import com.churchgeniuspro.service.PrivateAccessService;
import com.churchgeniuspro.service.PublicLinkResolver;
import com.churchgeniuspro.service.SmsService;
import com.churchgeniuspro.service.TemporaryAccessService;
import com.churchgeniuspro.service.VisionCheckService;
import com.churchgeniuspro.service.VisionUploadService;
import com.churchgeniuspro.service.WebPushService;
import com.churchgeniuspro.util.AfterCommit;
import com.churchgeniuspro.util.PrivatePageCatalog;
import com.churchgeniuspro.util.PublicSendLimiter;
import com.churchgeniuspro.util.SmsOutcomes;
import com.churchgeniuspro.webfilter.PrivatePageFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Error-handling audit 2026-10-28, backend Mediums M1, M2, M3, M7, M9, M10, M14:
 * honest notification flags and SMS counts, the pledge hook in its own transaction,
 * gates that log (or refuse) instead of silently allowing, vision quota charged only
 * for an answer, NTAG credential refusals counted by the throttle, mail after commit.
 * (M11 — Plaid bulk per-row transactions — is covered in {@code plaid/service/PlaidBulkPerRowTest}.)
 */
@DisplayName("Error-handling Mediums — backend")
class MediumBackendTest {

    static final String CID = "CHR-TEST";

    static MockHttpServletRequest admin() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("appClientId", CID);
        s.setAttribute("username", "admin@" + CID);
        s.setAttribute("role", "Admin");
        req.setSession(s);
        return req;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(ResponseEntity<?> r) { return (Map<String, Object>) r.getBody(); }

    // ── M1 ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("M1 — volunteer assignment 'notified' flag")
    class VolunteerNotify {

        VolunteerRoleRepository roleRepo = mock(VolunteerRoleRepository.class);
        VolunteerAssignmentRepository assignmentRepo = mock(VolunteerAssignmentRepository.class);
        FamilyMemberRepository memberRepo = mock(FamilyMemberRepository.class);
        EmailService emailService = mock(EmailService.class);
        WebPushService pushService = mock(WebPushService.class);

        VolunteerController controller() {
            return new VolunteerController(roleRepo, mock(VolunteerProfileRepository.class),
                    mock(VolunteerProfileRoleRepository.class), assignmentRepo, mock(VolunteerAttendanceRepository.class),
                    memberRepo, mock(FamilyRepository.class), mock(ChurchEventRepository.class),
                    emailService, mock(SmsService.class), pushService);
        }

        VolunteerAssignment assignment(long id, int memberId) {
            VolunteerAssignment a = new VolunteerAssignment();
            a.setId(id); a.setAppClientId(CID); a.setFamilyMemberId(memberId); a.setEventLabel("Picnic");
            return a;
        }

        FamilyMember member(int id, String email) {
            FamilyMember m = new FamilyMember();
            m.setId(id); m.setAppClientId(CID); m.setFirstName("Vol"); m.setLastName("" + id); m.setEmail(email);
            when(memberRepo.findByIdAndTenant(id, CID)).thenReturn(Optional.of(m));
            return m;
        }

        @Test
        @DisplayName("a refused e-mail (unsubscribed) and a failed push leave the row un-notified and name the person")
        void failedChannelsAreReported() throws Exception {
            VolunteerAssignment a = assignment(1L, 10);
            member(10, "vol@example.org");
            when(assignmentRepo.findByAppClientIdAndNotificationSentFalseAndDeleteFlagFalse(CID)).thenReturn(List.of(a));
            when(roleRepo.findByAppClientIdAndDeleteFlagFalseOrderByMinistryAscRoleNameAsc(CID)).thenReturn(List.of());
            doThrow(new IllegalStateException("Recipient has unsubscribed"))
                    .when(emailService).sendOrgEmailOrThrow(anyString(), anyString(), anyString(), anyString());
            doThrow(new RuntimeException("push down")).when(pushService)
                    .logAndSendToUser(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());

            Map<String, Object> out = body(controller().notifyVolunteers(new HashMap<>(), admin()));

            assertThat(out.get("sent")).isEqualTo(0);
            @SuppressWarnings("unchecked") List<Map<String, Object>> failed = (List<Map<String, Object>>) out.get("failed");
            assertThat(failed).hasSize(1);
            assertThat(failed.get(0).get("name")).isEqualTo("Vol 10");
            assertThat(failed.get(0).get("reason")).isEqualTo("Recipient has unsubscribed");
            assertThat(a.isNotificationSent()).isFalse();
            verify(assignmentRepo, never()).save(any(VolunteerAssignment.class));
        }

        @Test
        @DisplayName("a delivered e-mail flags the row; a member with no e-mail is reported, never flagged")
        void successFlagsOnlyWhenAChannelWorked() throws Exception {
            VolunteerAssignment ok = assignment(1L, 10), none = assignment(2L, 11);
            member(10, "vol@example.org");
            member(11, null);
            when(assignmentRepo.findByAppClientIdAndNotificationSentFalseAndDeleteFlagFalse(CID)).thenReturn(List.of(ok, none));
            when(roleRepo.findByAppClientIdAndDeleteFlagFalseOrderByMinistryAscRoleNameAsc(CID)).thenReturn(List.of());

            Map<String, Object> out = body(controller().notifyVolunteers(new HashMap<>(), admin()));

            assertThat(out.get("sent")).isEqualTo(1);
            assertThat(ok.isNotificationSent()).isTrue();
            assertThat(none.isNotificationSent()).isFalse();
            @SuppressWarnings("unchecked") List<Map<String, Object>> failed = (List<Map<String, Object>>) out.get("failed");
            assertThat(failed).hasSize(1);
            assertThat(failed.get(0).get("reason")).isEqualTo("No email address on file");
        }
    }

    // ── M2 ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("M2 — income ↔ pledge hook")
    class PledgeHook {

        @Test
        @DisplayName("both pledge hooks run in their own transaction (REQUIRES_NEW)")
        void hooksAreRequiresNew() throws Exception {
            for (String name : List.of("applyIncomeToPledge", "adjustPledgeByDelta")) {
                Method m = PledgeController.class.getMethod(name, String.class, Integer.class, Integer.class,
                        BigDecimal.class, LocalDate.class);
                Transactional t = m.getAnnotation(Transactional.class);
                assertThat(t).as(name).isNotNull();
                assertThat(t.propagation()).as(name).isEqualTo(Propagation.REQUIRES_NEW);
            }
        }

        @Test
        @DisplayName("a pledge failure never fails the income save")
        void pledgeFailureDoesNotBreakIncome() {
            IncomeRepository incomeRepo = mock(IncomeRepository.class);
            FamilyMemberRepository memberRepo = mock(FamilyMemberRepository.class);
            SubSourceRepository subSourceRepo = mock(SubSourceRepository.class);
            TransactionTypeRepository ttRepo = mock(TransactionTypeRepository.class);
            PledgeController pledges = mock(PledgeController.class);
            IncomeService svc = new IncomeService(incomeRepo, memberRepo, subSourceRepo, ttRepo, pledges);

            FamilyMember m = new FamilyMember(); m.setId(7); m.setAppClientId(CID);
            SubSource f = new SubSource(); f.setId(3); f.setAppClientId(CID);
            TransactionType tt = new TransactionType(); tt.setId(1); tt.setAppClientId(CID);
            when(memberRepo.findByIdAndTenant(7, CID)).thenReturn(Optional.of(m));
            when(subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(3, CID)).thenReturn(Optional.of(f));
            when(ttRepo.findByIdAndAppClientIdAndDeleteFlagFalse(1, CID)).thenReturn(Optional.of(tt));
            when(incomeRepo.save(any(Income.class))).thenAnswer(i -> { Income x = i.getArgument(0); x.setId(99); return x; });
            doThrow(new RuntimeException("pledge table locked"))
                    .when(pledges).applyIncomeToPledge(anyString(), anyInt(), anyInt(), any(), any());

            Income saved = svc.createIncome(7, 3, LocalDate.of(2026, 10, 1), 1, null, new BigDecimal("25.00"),
                    null, null, false, CID, "tester", null, false);

            assertThat(saved.getId()).isEqualTo(99);
            verify(pledges).applyIncomeToPledge(eq(CID), eq(3), eq(7), eq(new BigDecimal("25.00")), eq(LocalDate.of(2026, 10, 1)));
        }
    }

    // ── M3 ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("M3 — PrivatePageFilter fails closed for API requests")
    class PrivatePage {

        PrivateAccessService service = mock(PrivateAccessService.class);

        MockHttpServletResponse run(String uri) throws Exception {
            when(service.getSetting(CID)).thenThrow(new RuntimeException("connection refused"));
            PrivatePageFilter filter = new PrivatePageFilter(service, mock(LoginRepository.class), "", mock(PublicLinkResolver.class));
            MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
            MockHttpSession s = new MockHttpSession(); s.setAttribute("appClientId", CID); req.setSession(s);
            MockHttpServletResponse res = new MockHttpServletResponse();
            FilterChain chain = (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(200);
            filter.doFilter(req, res, chain);
            return res;
        }

        static String gatedPrefix(boolean api) {
            for (PrivatePageCatalog.Page p : PrivatePageCatalog.PAGES)
                for (String prefix : p.pathPrefixes())
                    if (prefix.startsWith("/api/") == api && !p.preAuth()) return prefix;
            throw new AssertionError("no gated prefix");
        }

        @Test
        @DisplayName("a gated /api/ path answers 403 JSON with a reference when the rules cannot be evaluated")
        void apiFailsClosed() throws Exception {
            MockHttpServletResponse res = run(gatedPrefix(true) + "/children");
            assertThat(res.getStatus()).isEqualTo(403);
            assertThat(res.getContentAsString()).contains("NETWORK_CHECK_FAILED").containsPattern("\"ref\":\"[0-9a-f]{8}\"")
                    .doesNotContain("connection refused");
        }

        @Test
        @DisplayName("a gated page still fails open (staff are never locked out of the screen by a lookup error)")
        void pageFailsOpen() throws Exception {
            assertThat(run(gatedPrefix(false)).getStatus()).isEqualTo(200);
        }
    }

    // ── M7 ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("M7 — SMS outcomes")
    class Sms {

        @Test
        @DisplayName("SmsOutcomes: provider faults vs recipient faults, honest counts, 502 only for provider faults")
        void outcomes() {
            assertThat(SmsOutcomes.recipientFault(SmsService.SendOutcome.ok())).isFalse();
            assertThat(SmsOutcomes.recipientFault(new SmsService.SendOutcome(false, 21211, "invalid"))).isTrue();
            assertThat(SmsOutcomes.recipientFault(new SmsService.SendOutcome(false, 21610, "STOP"))).isTrue();
            assertThat(SmsOutcomes.recipientFault(SmsService.SendOutcome.fail(SmsService.NOT_OPTED_IN_MSG))).isTrue();
            assertThat(SmsOutcomes.providerFault(new SmsService.SendOutcome(false, 30034, "unregistered"))).isTrue();
            assertThat(SmsOutcomes.providerFault(new SmsService.SendOutcome(false, 20003, "auth"))).isTrue();

            SmsOutcomes.Batch mixed = new SmsOutcomes.Batch();
            mixed.record("Ann", "+15555550100", SmsService.SendOutcome.ok());
            mixed.record("Bob", "+15555550101", new SmsService.SendOutcome(false, 21610, "Twilio 21610: STOP"));
            assertThat(mixed.sent).isEqualTo(1);
            assertThat(mixed.noneSentForProviderReason()).isFalse();
            assertThat(mixed.body()).containsEntry("success", true).containsEntry("sent", 1).containsKey("failed");

            SmsOutcomes.Batch allRecipient = new SmsOutcomes.Batch();
            allRecipient.record(null, "+15555550101", new SmsService.SendOutcome(false, 21211, "invalid"));
            assertThat(allRecipient.noneSentForProviderReason()).isFalse();
            assertThat(allRecipient.body()).containsEntry("success", false).containsKey("error");

            SmsOutcomes.Batch provider = new SmsOutcomes.Batch();
            provider.record(null, "+15555550101", new SmsService.SendOutcome(false, 30034, "Twilio 30034: not registered"));
            assertThat(provider.noneSentForProviderReason()).isTrue();
            assertThat(String.valueOf(provider.body().get("error"))).contains("30034");
        }

        @Test
        @DisplayName("event-volunteer SMS: real count + failed[] with reasons; 502 when nothing went out for a provider reason")
        void eventVolunteerSms() {
            SmsService sms = mock(SmsService.class);
            when(sms.isConfigured()).thenReturn(true);
            EventVolunteerController c = new EventVolunteerController(mock(EventVolunteerRepository.class),
                    mock(EventVolunteerRoleRepository.class), mock(FamilyMemberRepository.class), mock(VolunteerRoleRepository.class),
                    mock(ChurchEventRepository.class), mock(AppUserRepository.class), mock(EmailService.class), sms, mock(WebPushService.class));

            when(sms.sendForClient(CID, "+15555550100", "hi")).thenReturn(SmsService.SendOutcome.ok());
            when(sms.sendForClient(CID, "+15555550101", "hi")).thenReturn(new SmsService.SendOutcome(false, 21610, "Twilio 21610: STOP"));
            ResponseEntity<?> r = c.sendSms(1, Map.of("to", List.of("+15555550100", "+15555550101"), "body", "hi"), admin());
            assertThat(r.getStatusCode().value()).isEqualTo(200);
            assertThat(body(r)).containsEntry("sent", 1);
            @SuppressWarnings("unchecked") List<Map<String, Object>> failed = (List<Map<String, Object>>) body(r).get("failed");
            assertThat(failed).hasSize(1);
            assertThat(failed.get(0)).containsEntry("to", "+15555550101").containsEntry("code", 21610);

            when(sms.sendForClient(CID, "+15555550100", "hi")).thenReturn(new SmsService.SendOutcome(false, 30034, "Twilio 30034: not registered"));
            ResponseEntity<?> none = c.sendSms(1, Map.of("to", List.of("+15555550100"), "body", "hi"), admin());
            assertThat(none.getStatusCode().value()).isEqualTo(502);
            assertThat(body(none)).containsEntry("sent", 0);

            when(sms.isConfigured()).thenReturn(false);
            assertThat(c.sendSms(1, Map.of("to", List.of("+15555550100"), "body", "hi"), admin()).getStatusCode().value()).isEqualTo(409);
        }

        SmsOptInController optIn(SmsService sms, SmsOptInRepository repo) {
            PublicLinkResolver links = mock(PublicLinkResolver.class);
            when(links.resolveClientId(eq("tok"), anyString())).thenReturn(CID);
            PublicSendLimiter limiter = mock(PublicSendLimiter.class);
            when(limiter.check(any(PublicSendLimiter.Policy.class), any(jakarta.servlet.http.HttpServletRequest.class), anyString(), anyString())).thenReturn(null);
            ChurchRegistrationRepository churches = mock(ChurchRegistrationRepository.class);
            ChurchRegistration cr = new ChurchRegistration(); cr.setChurchName("Grace");
            when(churches.findByClientIdAndDeleteFlagFalse(CID)).thenReturn(Optional.of(cr));
            when(repo.findByPhoneNumberAndAppClientId(anyString(), anyString())).thenReturn(Optional.empty());
            return new SmsOptInController(repo, churches, mock(FamilyMemberRepository.class),
                    mock(PublicScreenLinkRepository.class), mock(StripeSettingsRepository.class), sms, links, limiter);
        }

        Map<String, Object> form() {
            Map<String, Object> m = new HashMap<>();
            m.put("token", "tok"); m.put("firstName", "Ann"); m.put("lastName", "Lee");
            m.put("phoneNumber", "(312) 555-0147"); m.put("consent", true);
            return m;
        }

        @Test
        @DisplayName("opt-in: an invalid number is a 400; a provider failure says so and never promises a text")
        void optInHonest() {
            SmsService sms = mock(SmsService.class);
            SmsOptInRepository repo = mock(SmsOptInRepository.class);
            SmsOptInController c = optIn(sms, repo);

            when(sms.sendConfirmationRequestWithOutcome(anyString(), anyString(), anyString()))
                    .thenReturn(new SmsService.SendOutcome(false, 21211, "Twilio 21211: invalid"));
            ResponseEntity<Map<String, Object>> bad = c.submitOptIn(form(), new MockHttpServletRequest());
            assertThat(bad.getStatusCode().value()).isEqualTo(400);
            assertThat(String.valueOf(bad.getBody().get("message"))).contains("cannot receive text messages");

            when(sms.sendConfirmationRequestWithOutcome(anyString(), anyString(), anyString()))
                    .thenReturn(new SmsService.SendOutcome(false, 30034, "Twilio 30034"));
            ResponseEntity<Map<String, Object>> down = c.submitOptIn(form(), new MockHttpServletRequest());
            assertThat(down.getStatusCode().value()).isEqualTo(200);
            assertThat(down.getBody()).containsEntry("smsSent", false);
            assertThat(String.valueOf(down.getBody().get("message"))).doesNotContain("You will receive a confirmation shortly")
                    .isEqualTo(SmsOptInController.NO_TEXT_MSG);

            when(sms.sendConfirmationRequestWithOutcome(anyString(), anyString(), anyString())).thenReturn(SmsService.SendOutcome.ok());
            ResponseEntity<Map<String, Object>> ok = c.submitOptIn(form(), new MockHttpServletRequest());
            assertThat(ok.getBody()).containsEntry("smsSent", true);
        }

        @Test
        @DisplayName("opt-in: an already-confirmed number gets exactly the no-text wording (no subscriber oracle)")
        void confirmedNumberSameWording() {
            SmsService sms = mock(SmsService.class);
            SmsOptInRepository repo = mock(SmsOptInRepository.class);
            SmsOptInController c = optIn(sms, repo);
            SmsOptIn existing = new SmsOptIn(); org.springframework.test.util.ReflectionTestUtils.setField(existing, "id", 5); existing.setConfirmed(true); existing.setConsent(true);
            when(repo.findByPhoneNumberAndAppClientId(anyString(), anyString())).thenReturn(Optional.of(existing));
            ResponseEntity<Map<String, Object>> r = c.submitOptIn(form(), new MockHttpServletRequest());
            assertThat(r.getBody().get("message")).isEqualTo(SmsOptInController.NO_TEXT_MSG);
            verify(sms, never()).sendConfirmationRequestWithOutcome(anyString(), anyString(), anyString());
        }
    }

    // ── M9 ────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("M9 — vision quota")
    class Vision {

        @Test
        @DisplayName("a vision call that returned nothing is not charged and the page is told; an answer is charged")
        void quotaOnlyOnAnswer() throws Exception {
            CheckExtractor extractor = mock(CheckExtractor.class);
            CheckExtractor.Result needsOcr = new CheckExtractor.Result();
            needsOcr.method = "image-needs-ocr";
            when(extractor.extract(any(), any(), any())).thenReturn(needsOcr);
            VisionCheckService vision = mock(VisionCheckService.class);
            when(vision.isEnabled()).thenReturn(true);
            VisionUploadService upload = mock(VisionUploadService.class);
            when(upload.available(CID)).thenReturn(true);
            when(upload.prepare(eq(CID), any(), any(), any())).thenReturn(new VisionUploadService.Prepared(new byte[]{1}, "image/jpeg"));
            CheckScanController c = new CheckScanController(extractor, mock(IncomeService.class),
                    mock(IncomeCheckImageRepository.class), mock(IncomeRepository.class), vision, upload);
            MockMultipartFile file = new MockMultipartFile("file", "check.jpg", "image/jpeg", new byte[]{1, 2, 3});

            when(vision.extractCheck(any(), any())).thenReturn(null);          // 429 / 5xx / timeout
            ResponseEntity<Map<String, Object>> r = c.scan(file, admin());
            assertThat(r.getStatusCode().value()).isEqualTo(200);
            assertThat(r.getBody()).containsKey("visionError").containsEntry("method", "image-needs-ocr");
            verify(upload, never()).recordUse(anyString());

            when(vision.extractCheck(any(), any())).thenReturn(Map.of("amount", "25.00"));
            ResponseEntity<Map<String, Object>> ok = c.scan(file, admin());
            assertThat(ok.getBody()).doesNotContainKey("visionError");
            verify(upload).recordUse(CID);
        }
    }

    // ── M10 ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("M10 — NTAG wrong PIN / code")
    class Ntag {

        @Test
        @DisplayName("a wrong PIN or code answers 401 with the same message; other refusals stay 400")
        void credentialRefusalsAre401() {
            NtagService svc = mock(NtagService.class);
            NtagLoginController c = new NtagLoginController(svc, mock(TemporaryAccessService.class));
            MockHttpServletRequest req = new MockHttpServletRequest();

            doThrow(new NtagService.NtagLoginException("Incorrect PIN.")).when(svc).verifyPin(any(), any(), any(), any());
            ResponseEntity<?> pin = c.pin(Map.of("token", "t", "pin", "000000"), req);
            assertThat(pin.getStatusCode().value()).isEqualTo(401);
            assertThat(body(pin)).containsEntry("message", "Incorrect PIN.");

            doThrow(new NtagService.NtagLoginException("Incorrect verification code.")).when(svc).verifyOtp(any(), any(), any(), any());
            assertThat(c.otp(Map.of("token", "t", "otp", "000000"), req).getStatusCode().value()).isEqualTo(401);

            doThrow(new NtagService.NtagLoginException("Too many attempts. Please re-scan your tag to start over."))
                    .when(svc).verifyPin(any(), any(), any(), any());
            assertThat(c.pin(Map.of("token", "t", "pin", "000000"), req).getStatusCode().value()).isEqualTo(400);
        }
    }

    // ── M14 ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("M14 — AfterCommit")
    class AfterCommitHelper {

        @Test
        @DisplayName("runs immediately outside a transaction, after commit inside one, and never throws")
        void afterCommit() {
            AtomicInteger ran = new AtomicInteger();
            AfterCommit.run("x", ran::incrementAndGet);
            assertThat(ran.get()).isEqualTo(1);
            AfterCommit.run("boom", () -> { throw new IllegalStateException("smtp"); });   // swallowed

            TransactionSynchronizationManager.initSynchronization();
            try {
                AfterCommit.run("y", ran::incrementAndGet);
                assertThat(ran.get()).as("deferred until commit").isEqualTo(1);
                TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
                assertThat(ran.get()).isEqualTo(2);
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }

        @Test
        @DisplayName("the Sunday School send-result endpoints are no longer transactional; submitExam still is (mail deferred)")
        void sundaySchoolAnnotations() throws Exception {
            Class<?> ss = Class.forName("com.churchgeniuspro.controller.SundaySchoolController");
            List<String> transactional = new ArrayList<>();
            // The controller uses jakarta.transaction.Transactional (Spring honours it the same way).
            for (Method m : ss.getDeclaredMethods())
                if (m.getAnnotation(Transactional.class) != null
                        || m.getAnnotation(jakarta.transaction.Transactional.class) != null) transactional.add(m.getName());
            assertThat(transactional).doesNotContain("adminSendResult", "teacherSendResult").contains("submitExam");
            String src = java.nio.file.Files.readString(java.nio.file.Paths.get(
                    "src/main/java/com/churchgeniuspro/controller/SundaySchoolController.java"));
            assertThat(src).contains("AfterCommit.run(\"exam submission e-mails\"");
        }
    }
}
