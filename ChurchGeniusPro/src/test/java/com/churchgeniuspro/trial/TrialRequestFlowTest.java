package com.churchgeniuspro.trial;

import com.churchgeniuspro.controller.ServiceAdminTrialRequestController;
import com.churchgeniuspro.controller.TrialRequestController;
import com.churchgeniuspro.hibernate.PlatformSetting;
import com.churchgeniuspro.hibernate.TrialRegistrationLink;
import com.churchgeniuspro.hibernate.TrialRequest;
import com.churchgeniuspro.repository.PlatformSettingRepository;
import com.churchgeniuspro.repository.TrialRequestRepository;
import com.churchgeniuspro.service.*;
import com.churchgeniuspro.util.PublicFormGuard;
import com.churchgeniuspro.webfilter.TrialRequestLinkFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Public Trial Request flow: token-gated page → submit → emailed code → verified
 * (Support Email notified) → Service Admin Approve issues an ordinary Trial
 * Registration Link through the EXISTING link service, or Reject closes it.
 */
@DisplayName("Trial Request flow")
class TrialRequestFlowTest {

    // ── fakes ──────────────────────────────────────────────────────────────
    final Map<Long, TrialRequest> rows = new LinkedHashMap<>();
    final Map<String, PlatformSetting> settingRows = new HashMap<>();
    final Map<String, String> otp = new HashMap<>();
    final AtomicLong ids = new AtomicLong();

    TrialRequestRepository repo;
    VerificationStore codes;
    EmailService email;
    PlatformSettingService settings;
    TrialPolicy policy;
    TrialRegistrationLinkService links;
    TrialRequestService service;

    @BeforeEach
    void setUp() throws Exception {
        repo = mock(TrialRequestRepository.class);
        when(repo.save(any(TrialRequest.class))).thenAnswer(i -> {
            TrialRequest r = i.getArgument(0);
            if (r.getId() == null) r.setId(ids.incrementAndGet());
            if (r.getCreatedAt() == null) r.setCreatedAt(LocalDateTime.now());
            rows.put(r.getId(), r);
            return r;
        });
        when(repo.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(rows.get((Long) i.getArgument(0))));
        when(repo.findByReference(anyString())).thenAnswer(i -> rows.values().stream()
                .filter(r -> r.getReference().equals(i.getArgument(0))).findFirst());
        when(repo.existsByReference(anyString())).thenReturn(false);
        when(repo.findAllByOrderByCreatedAtDesc()).thenAnswer(i -> new ArrayList<>(rows.values()));
        when(repo.findByEmailAndStatusInOrderByCreatedAtDesc(anyString(), any())).thenAnswer(i -> {
            String em = i.getArgument(0);
            java.util.Collection<String> st = i.getArgument(1);
            List<TrialRequest> out = new ArrayList<>();
            for (TrialRequest r : rows.values()) if (em.equals(r.getEmail()) && st.contains(r.getStatus())) out.add(0, r);
            return out;
        });
        when(repo.expireUnverified(any())).thenAnswer(i -> {
            LocalDateTime cutoff = i.getArgument(0);
            int n = 0;
            for (TrialRequest r : rows.values()) {
                if (TrialRequest.PENDING_VERIFICATION.equals(r.getStatus()) && r.getCreatedAt().isBefore(cutoff)) {
                    r.setStatus(TrialRequest.VERIFICATION_EXPIRED); n++;
                }
            }
            return n;
        });
        when(repo.transition(anyLong(), anyString(), anyString(), any(), any())).thenAnswer(i -> {
            TrialRequest r = rows.get((Long) i.getArgument(0));
            if (r == null || !r.getStatus().equals(i.getArgument(1))) return 0;
            r.setStatus(i.getArgument(2));
            r.setDecidedBy(i.getArgument(3));
            r.setDecidedAt(i.getArgument(4));
            return 1;
        });

        codes = mock(VerificationStore.class);
        when(codes.generateAndStore(anyString(), anyString(), anyString())).thenAnswer(i -> {
            String c = String.format("%06d", otp.size() + 123456);
            otp.put(i.getArgument(0), c);
            return c;
        });
        when(codes.validate(anyString(), anyString(), anyString()))
                .thenAnswer(i -> i.getArgument(2).equals(otp.get((String) i.getArgument(0))));

        email = mock(EmailService.class);

        PlatformSettingRepository sRepo = mock(PlatformSettingRepository.class);
        when(sRepo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(settingRows.get((String) i.getArgument(0))));
        when(sRepo.save(any(PlatformSetting.class))).thenAnswer(i -> {
            PlatformSetting s = i.getArgument(0); settingRows.put(s.getKey(), s); return s;
        });
        settings = new PlatformSettingService(sRepo);

        policy = mock(TrialPolicy.class);
        when(policy.trialDays()).thenReturn(45);

        links = mock(TrialRegistrationLinkService.class);
        when(links.generate(anyString(), anyString(), anyString(), isNull(), isNull(), anyString())).thenAnswer(i -> {
            TrialRegistrationLink l = new TrialRegistrationLink();
            l.setId(77);
            l.setToken("reg-token");
            l.setProspectEmail(i.getArgument(1));
            l.setTrialDays(45);                       // what the real service captures from TrialPolicy
            l.setExpiresAt(LocalDateTime.now().plusDays(7));
            return l;
        });
        when(links.urlFor("reg-token")).thenReturn("https://app.example/trialRegistration.html?t=reg-token");

        service = new TrialRequestService(repo, codes, email, settings, policy, links);
        service.setBaseUrl("https://app.example/");
    }

    static TrialRequestService.Form form() {
        return new TrialRequestService.Form("Mary", "Lee", "Grace Chapel", "Mary@Example.org",
                "555-0100", "Pastor", "We have 120 members");
    }

    TrialRequest submitted() {
        return service.submit(form(), "10.0.0.1");
    }

    TrialRequest verified() {
        TrialRequest r = submitted();
        service.verify(r.getReference(), otp.get(r.getReference()));
        return r;
    }

    // ── form & submit ─────────────────────────────────────────────────────

    @Test @DisplayName("First/Last/Church/Email are required; Phone/Designation/Note optional")
    void validation() {
        assertThat(TrialRequestService.validate(new TrialRequestService.Form(null, "L", "C", "a@b.co", null, null, null)))
                .contains("First Name");
        assertThat(TrialRequestService.validate(new TrialRequestService.Form("F", " ", "C", "a@b.co", null, null, null)))
                .contains("Last Name");
        assertThat(TrialRequestService.validate(new TrialRequestService.Form("F", "L", null, "a@b.co", null, null, null)))
                .contains("Church Name");
        assertThat(TrialRequestService.validate(new TrialRequestService.Form("F", "L", "C", null, null, null, null)))
                .contains("Email");
        assertThat(TrialRequestService.validate(new TrialRequestService.Form("F", "L", "C", "not-an-email", null, null, null)))
                .contains("valid email");
        assertThat(TrialRequestService.validate(new TrialRequestService.Form("F", "L", "C", "a@b.co", null, null, null)))
                .isNull();
    }

    @Test @DisplayName("submit stores PENDING_VERIFICATION and emails a code to the requester only — nothing to Support")
    void submitSendsCodeOnly() throws Exception {
        TrialRequest r = submitted();
        assertThat(r.getStatus()).isEqualTo(TrialRequest.PENDING_VERIFICATION);
        assertThat(r.getEmail()).isEqualTo("mary@example.org");
        assertThat(r.getReference()).startsWith("TRQ-");
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email).sendAccountEmailOrThrow(eq("mary@example.org"), contains(otp.get(r.getReference())), html.capture(), isNull());
        assertThat(html.getValue()).contains(otp.get(r.getReference()));
        verify(email, never()).sendComposed(anyList(), any(), anyString(), anyString(), any(), any());
    }

    @Test @DisplayName("a code that cannot be emailed fails the submit with a clear message")
    void submitEmailFailure() throws Exception {
        doThrow(new RuntimeException("smtp down")).when(email)
                .sendAccountEmailOrThrow(anyString(), anyString(), anyString(), any());
        assertThatThrownBy(this::submitted).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not send the verification email");
    }

    // ── verify ────────────────────────────────────────────────────────────

    @Test @DisplayName("a wrong code is refused and nothing reaches the Support Email")
    void wrongCode() throws Exception {
        TrialRequest r = submitted();
        assertThatThrownBy(() -> service.verify(r.getReference(), "000000"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not correct");
        assertThat(r.getStatus()).isEqualTo(TrialRequest.PENDING_VERIFICATION);
        verify(email, never()).sendComposed(anyList(), any(), anyString(), anyString(), any(), any());
    }

    @Test @DisplayName("the right code verifies; the CONFIGURED Support Email gets every field, date/time and status")
    void verifyNotifiesSupport() throws Exception {
        settings.set(PlatformSettingService.SUPPORT_EMAIL, "help@mychurchsoft.org", "ops");
        TrialRequest r = verified();
        assertThat(r.getStatus()).isEqualTo(TrialRequest.VERIFIED);
        assertThat(r.getVerifiedAt()).isNotNull();
        assertThat(r.getSupportEmailSent()).isTrue();

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email).sendComposed(eq(List.of("help@mychurchsoft.org")), isNull(), contains(r.getReference()),
                html.capture(), isNull(), anyString());
        assertThat(html.getValue()).contains("Mary", "Lee", "Grace Chapel", "mary@example.org", "555-0100",
                "Pastor", "We have 120 members", "Request Date/Time", "Verification Status", "Email verified");
    }

    @Test @DisplayName("a Support Email outage does not undo the requester's verification")
    void supportEmailFailure() throws Exception {
        doThrow(new RuntimeException("smtp down")).when(email)
                .sendComposed(anyList(), any(), anyString(), anyString(), any(), any());
        TrialRequest r = verified();
        assertThat(r.getStatus()).isEqualTo(TrialRequest.VERIFIED);
        assertThat(r.getSupportEmailSent()).isFalse();
    }

    @Test @DisplayName("resend honours the 60-second cooldown and the per-request cap")
    void resendLimits() {
        TrialRequest r = submitted();
        assertThatThrownBy(() -> service.resend(r.getReference())).hasMessageContaining("wait a minute");
        r.setCodeSentAt(LocalDateTime.now().minusMinutes(2));
        service.resend(r.getReference());
        assertThat(r.getCodesSent()).isEqualTo(2);
        r.setCodesSent(TrialRequestService.MAX_CODES);
        r.setCodeSentAt(LocalDateTime.now().minusMinutes(2));
        assertThatThrownBy(() -> service.resend(r.getReference())).hasMessageContaining("Too many codes");
    }

    // ── approve / reject ──────────────────────────────────────────────────

    @Test @DisplayName("an unverified request cannot be approved")
    void cannotApproveUnverified() {
        TrialRequest r = submitted();
        assertThatThrownBy(() -> service.approve(r.getId(), "ops")).hasMessageContaining("not been verified");
        verifyNoInteractions(links);
    }

    @Test @DisplayName("approve reuses the existing link service (no own trial length) and emails the link to the verified address")
    void approveIssuesExistingStyleLink() throws Exception {
        TrialRequest r = verified();
        TrialRequestService.Approval a = service.approve(r.getId(), "ops");

        // Same call as Service Admin's "Generate Link": default validity, trialDays null → TrialPolicy default captured.
        verify(links).generate(eq("Mary Lee"), eq("mary@example.org"), contains(r.getReference()), isNull(), isNull(), eq("ops"));
        assertThat(a.registrationUrl()).isEqualTo("https://app.example/trialRegistration.html?t=reg-token");
        assertThat(a.trialDays()).isEqualTo(45);
        assertThat(a.emailSent()).isTrue();

        TrialRequest saved = rows.get(r.getId());
        assertThat(saved.getStatus()).isEqualTo(TrialRequest.APPROVED);
        assertThat(saved.getDecidedBy()).isEqualTo("ops");
        assertThat(saved.getTrialLinkId()).isEqualTo(77);

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email).sendAccountEmailOrThrow(eq("mary@example.org"), contains("approved"), html.capture(), isNull());
        assertThat(html.getValue()).contains("reg-token", "45-day free trial");
    }

    @Test @DisplayName("a second approve (double click / two admins) is refused and issues no second link")
    void doubleApprove() {
        TrialRequest r = verified();
        service.approve(r.getId(), "ops");
        assertThatThrownBy(() -> service.approve(r.getId(), "ops2")).hasMessageContaining("already been approved");
        verify(links, times(1)).generate(anyString(), anyString(), anyString(), isNull(), isNull(), anyString());
    }

    @Test @DisplayName("losing the atomic claim refuses the approval")
    void lostRace() {
        TrialRequest r = verified();
        when(repo.transition(eq(r.getId()), eq(TrialRequest.VERIFIED), eq(TrialRequest.APPROVED), any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.approve(r.getId(), "ops")).hasMessageContaining("just decided");
        verifyNoInteractions(links);
    }

    @Test @DisplayName("if the link cannot be created the request returns to VERIFIED")
    void linkFailureRollsBack() {
        TrialRequest r = verified();
        when(links.generate(anyString(), anyString(), anyString(), isNull(), isNull(), anyString()))
                .thenThrow(new RuntimeException("db"));
        assertThatThrownBy(() -> service.approve(r.getId(), "ops")).hasMessage("db");
        assertThat(rows.get(r.getId()).getStatus()).isEqualTo(TrialRequest.VERIFIED);
    }

    @Test @DisplayName("approval email failure keeps the approval and hands the admin the link")
    void approvalEmailFailure() throws Exception {
        TrialRequest r = verified();
        doThrow(new RuntimeException("smtp down")).when(email)
                .sendAccountEmailOrThrow(anyString(), contains("approved"), anyString(), any());
        TrialRequestService.Approval a = service.approve(r.getId(), "ops");
        assertThat(a.emailSent()).isFalse();
        assertThat(a.registrationUrl()).contains("reg-token");
        assertThat(rows.get(r.getId()).getStatus()).isEqualTo(TrialRequest.APPROVED);
        assertThat(rows.get(r.getId()).getApprovalEmailSent()).isFalse();
    }

    @Test @DisplayName("reject works from Pending Verification or Verified, sends no email, and is final")
    void reject() throws Exception {
        // CHANGED 2026-10-05 (Phase 2): one open request per email. The pending request
        // now uses a second address; before, both requests shared one email.
        TrialRequest pending = service.submit(new TrialRequestService.Form("Paul", "King", "Hope Church",
                "paul@example.org", null, null, null), "10.0.0.3");
        TrialRequest ver = verified();
        clearInvocations(email);
        service.reject(pending.getId(), "ops", null);
        service.reject(ver.getId(), "ops", "Duplicate");
        assertThat(rows.get(pending.getId()).getStatus()).isEqualTo(TrialRequest.REJECTED);
        assertThat(rows.get(ver.getId()).getRejectReason()).isEqualTo("Duplicate");
        verifyNoInteractions(email);
        assertThatThrownBy(() -> service.approve(ver.getId(), "ops")).hasMessageContaining("already been rejected");
        assertThatThrownBy(() -> service.reject(ver.getId(), "ops", null)).hasMessageContaining("already been rejected");
    }

    // ── the request-page link ─────────────────────────────────────────────

    @Test @DisplayName("no link until generated; regenerate kills the old link; revoke closes the page")
    void linkLifecycle() {
        assertThat(service.linkUrl()).isNull();
        assertThat(service.isLinkToken("anything")).isFalse();

        String url1 = service.regenerateLink("ops");
        assertThat(url1).startsWith("https://app.example/trialRequest.html?k=");
        String t1 = url1.substring(url1.indexOf("k=") + 2);
        assertThat(t1).hasSizeGreaterThanOrEqualTo(40);
        assertThat(service.isLinkToken(t1)).isTrue();

        String t2 = service.regenerateLink("ops").replaceAll(".*k=", "");
        assertThat(t2).isNotEqualTo(t1);
        assertThat(service.isLinkToken(t1)).isFalse();
        assertThat(service.isLinkToken(t2)).isTrue();

        service.revokeLink("ops");
        assertThat(service.isLinkToken(t2)).isFalse();
        assertThat(service.linkUrl()).isNull();
        assertThat(service.isLinkToken(null)).isFalse();
        assertThat(service.isLinkToken("")).isFalse();
    }

    @Test @DisplayName("the page filter forwards to the invalid page with 403 unless k is the current token")
    void pageFilter() throws Exception {
        TrialRequestLinkFilter f = new TrialRequestLinkFilter(service);
        String t = service.regenerateLink("ops").replaceAll(".*k=", "");

        MockHttpServletRequest bad = new MockHttpServletRequest("GET", "/trialRequest.html");
        bad.setParameter("k", "wrong");
        MockHttpServletResponse badRes = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        f.doFilter(bad, badRes, chain);
        assertThat(badRes.getStatus()).isEqualTo(403);
        assertThat(badRes.getForwardedUrl()).isEqualTo("/trialRequestInvalid.html");
        verifyNoInteractions(chain);

        MockHttpServletRequest none = new MockHttpServletRequest("GET", "/trialRequest");
        MockHttpServletResponse noneRes = new MockHttpServletResponse();
        f.doFilter(none, noneRes, chain);
        assertThat(noneRes.getStatus()).isEqualTo(403);

        MockHttpServletRequest good = new MockHttpServletRequest("GET", "/trialRequest.html");
        good.setParameter("k", t);
        f.doFilter(good, new MockHttpServletResponse(), chain);
        verify(chain).doFilter(eq(good), any());
    }

    // ── controllers ───────────────────────────────────────────────────────

    @Test @DisplayName("public API refuses form-info, submit and verify without the current link token")
    void publicApiNeedsLink() {
        PublicFormGuard guard = mock(PublicFormGuard.class);
        TrialRequestController c = new TrialRequestController(service, guard);
        service.regenerateLink("ops");

        assertThat(c.formInfo("wrong").getStatusCode().value()).isEqualTo(403);
        Map<String, Object> body = new HashMap<>(Map.of("k", "wrong", "firstName", "Mary", "lastName", "Lee",
                "churchName", "Grace", "email", "m@example.org", "formToken", "x"));
        assertThat(c.submit(body, new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(403);
        assertThat(c.verify(new HashMap<>(Map.of("k", "wrong", "reference", "TRQ-X", "code", "1")),
                new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(403);
        assertThat(c.resend(new HashMap<>(Map.of("k", "wrong", "reference", "TRQ-X")),
                new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(403);
        assertThat(rows).isEmpty();
    }

    @Test @DisplayName("public API: valid link → submit → verify end to end; form-info reports the configured trial days")
    void publicApiHappyPath() {
        PublicFormGuard guard = mock(PublicFormGuard.class);   // all checks pass (return null)
        TrialRequestController c = new TrialRequestController(service, guard);
        String k = service.regenerateLink("ops").replaceAll(".*k=", "");

        ResponseEntity<Map<String, Object>> info = c.formInfo(k);
        assertThat(info.getStatusCode().value()).isEqualTo(200);
        assertThat(info.getBody()).containsEntry("trialDays", 45);

        Map<String, Object> body = new HashMap<>(Map.of("k", k, "firstName", "Mary", "lastName", "Lee",
                "churchName", "Grace Chapel", "email", "mary@example.org", "formToken", "ok"));
        ResponseEntity<Map<String, Object>> sub = c.submit(body, new MockHttpServletRequest());
        assertThat(sub.getStatusCode().value()).isEqualTo(200);
        String ref = (String) sub.getBody().get("reference");

        ResponseEntity<Map<String, Object>> ver = c.verify(
                new HashMap<>(Map.of("k", k, "reference", ref, "code", otp.get(ref))), new MockHttpServletRequest());
        assertThat(ver.getStatusCode().value()).isEqualTo(200);
        assertThat(rows.values().iterator().next().getStatus()).isEqualTo(TrialRequest.VERIFIED);
    }

    @Test @DisplayName("public API: missing required field is a 400 and nothing is stored")
    void publicApiMissingField() {
        TrialRequestController c = new TrialRequestController(service, mock(PublicFormGuard.class));
        String k = service.regenerateLink("ops").replaceAll(".*k=", "");
        Map<String, Object> body = new HashMap<>(Map.of("k", k, "firstName", "Mary", "lastName", "Lee",
                "email", "mary@example.org", "formToken", "ok"));
        ResponseEntity<Map<String, Object>> r = c.submit(body, new MockHttpServletRequest());
        assertThat(r.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(r.getBody().get("message"))).contains("Church Name");
        assertThat(rows).isEmpty();
    }

    @Test @DisplayName("public API: honeypot answers success but stores and sends nothing")
    void honeypot() {
        PublicFormGuard guard = mock(PublicFormGuard.class);
        when(guard.isHoneypotTripped(any())).thenReturn(true);
        TrialRequestController c = new TrialRequestController(service, guard);
        ResponseEntity<Map<String, Object>> r = c.submit(new HashMap<>(Map.of("website", "x")), new MockHttpServletRequest());
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(rows).isEmpty();
        verifyNoInteractions(email);
    }

    @Test @DisplayName("Service Admin endpoints return 401 without a Service Admin session")
    void adminOnly() {
        ServiceAdminTrialRequestController c = new ServiceAdminTrialRequestController(service, settings);
        MockHttpServletRequest anon = new MockHttpServletRequest();
        MockHttpServletRequest churchAdmin = new MockHttpServletRequest();
        churchAdmin.getSession(true).setAttribute("role", "Admin");
        for (MockHttpServletRequest req : List.of(anon, churchAdmin)) {
            assertThat(c.list(req).getStatusCode().value()).isEqualTo(401);
            assertThat(c.regenerate(req).getStatusCode().value()).isEqualTo(401);
            assertThat(c.revoke(req).getStatusCode().value()).isEqualTo(401);
            assertThat(c.approve(1L, req).getStatusCode().value()).isEqualTo(401);
            assertThat(c.reject(1L, null, req).getStatusCode().value()).isEqualTo(401);
        }
        assertThat(settingRows).isEmpty();
    }

    @Test @DisplayName("Service Admin list shows link, trial days, Support Email and requests; approve via controller")
    void adminFlow() {
        ServiceAdminTrialRequestController c = new ServiceAdminTrialRequestController(service, settings);
        MockHttpServletRequest sa = new MockHttpServletRequest();
        sa.getSession(true).setAttribute("role", "ServiceAdmin");
        sa.getSession().setAttribute("serviceAdminUsername", "ops");

        assertThat(c.regenerate(sa).getStatusCode().value()).isEqualTo(200);
        TrialRequest r = verified();
        Map<String, Object> list = c.list(sa).getBody();
        assertThat(list).containsEntry("trialDays", 45).containsEntry("supportEmail", "support@churchgeniuspro.com");
        assertThat((String) list.get("linkUrl")).contains("/trialRequest.html?k=");
        assertThat((List<?>) list.get("requests")).hasSize(1);

        ResponseEntity<Map<String, Object>> a = c.approve(r.getId(), sa);
        assertThat(a.getStatusCode().value()).isEqualTo(200);
        assertThat(a.getBody()).containsEntry("emailSent", true);
        assertThat(c.approve(r.getId(), sa).getStatusCode().value()).isEqualTo(400);
    }

    // ── Phase 2: one open request per email; 24-hour verification window ─────

    @Test @DisplayName("a second request while the first awaits approval is refused with the 'still being reviewed' message")
    void duplicateWhileVerified() throws Exception {
        verified();
        clearInvocations(email);
        assertThatThrownBy(this::submitted)
                .isInstanceOf(TrialRequestService.DuplicateRequestException.class)
                .hasMessage(TrialRequestService.STILL_PENDING_MESSAGE)
                .satisfies(e -> assertThat(((TrialRequestService.DuplicateRequestException) e).reference()).isNull());
        assertThat(rows).hasSize(1);
        verifyNoInteractions(email);                       // no second code goes out
    }

    @Test @DisplayName("a second request while the first awaits its code resumes that one (refresh / closed tab)")
    void duplicateWhileAwaitingCode() {
        TrialRequest first = submitted();
        TrialRequestService.Form sameEmailOtherCase = new TrialRequestService.Form("Mary", "Lee", "Grace Chapel",
                "  MARY@example.ORG ", null, null, null);
        assertThatThrownBy(() -> service.submit(sameEmailOtherCase, "10.0.0.2"))
                .isInstanceOf(TrialRequestService.DuplicateRequestException.class)
                .hasMessage(TrialRequestService.AWAITING_CODE_MESSAGE)
                .satisfies(e -> assertThat(((TrialRequestService.DuplicateRequestException) e).reference())
                        .isEqualTo(first.getReference()));
        assertThat(rows).hasSize(1);

        // Through the API: 409 with the reference, so the page jumps to code entry
        PublicFormGuard guard = mock(PublicFormGuard.class);
        TrialRequestController c = new TrialRequestController(service, guard);
        String k = service.regenerateLink("ops").replaceAll(".*k=", "");
        ResponseEntity<Map<String, Object>> r = c.submit(new HashMap<>(Map.of("k", k, "firstName", "Mary",
                "lastName", "Lee", "churchName", "Grace", "email", "mary@example.org", "formToken", "ok")),
                new MockHttpServletRequest());
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(r.getBody()).containsEntry("code", "AWAITING_VERIFICATION")
                               .containsEntry("reference", first.getReference());
    }

    @Test @DisplayName("after 24 hours an unverified request expires, no longer blocks, and its code is refused")
    void verificationWindow() {
        TrialRequest old = submitted();
        old.setCreatedAt(LocalDateTime.now().minusHours(25));
        String oldCode = otp.get(old.getReference());

        TrialRequest fresh = submitted();                  // accepted: the old one expired
        assertThat(old.getStatus()).isEqualTo(TrialRequest.VERIFICATION_EXPIRED);
        assertThat(fresh.getStatus()).isEqualTo(TrialRequest.PENDING_VERIFICATION);
        assertThatThrownBy(() -> service.verify(old.getReference(), oldCode))
                .hasMessage(TrialRequestService.EXPIRED_MESSAGE);
        assertThatThrownBy(() -> service.resend(old.getReference()))
                .hasMessage(TrialRequestService.EXPIRED_MESSAGE);
    }

    @Test @DisplayName("an unverified request past 24 hours is refused at verify even before the sweep marks it")
    void lazyExpiryAtVerify() {
        TrialRequest r = submitted();
        r.setCreatedAt(LocalDateTime.now().minusHours(30));
        assertThatThrownBy(() -> service.verify(r.getReference(), otp.get(r.getReference())))
                .hasMessage(TrialRequestService.EXPIRED_MESSAGE);
        assertThat(r.getStatus()).isEqualTo(TrialRequest.VERIFICATION_EXPIRED);
    }

    @Test @DisplayName("once a request is approved or rejected, the same email may request again")
    void decidedRequestsDoNotBlock() {
        TrialRequest a = verified();
        service.approve(a.getId(), "ops");
        TrialRequest b = verified();                       // allowed after approval
        service.reject(b.getId(), "ops", null);
        assertThat(submitted().getStatus()).isEqualTo(TrialRequest.PENDING_VERIFICATION);
    }

    @Test @DisplayName("two simultaneous submits: the one that loses the unique index gets the same refusal")
    void simultaneousSubmit() {
        when(repo.save(any(TrialRequest.class))).thenThrow(new org.springframework.dao.DataIntegrityViolationException("ux_trial_request_open_email"));
        assertThatThrownBy(this::submitted)
                .isInstanceOf(TrialRequestService.DuplicateRequestException.class);
    }

    @Test @DisplayName("if the code email cannot be sent, the request does not block the address")
    void unsentCodeDoesNotBlock() throws Exception {
        doThrow(new RuntimeException("smtp down")).when(email)
                .sendAccountEmailOrThrow(anyString(), anyString(), anyString(), any());
        assertThatThrownBy(this::submitted).isInstanceOf(IllegalStateException.class);
        assertThat(rows.values().iterator().next().getStatus()).isEqualTo(TrialRequest.VERIFICATION_EXPIRED);
        doNothing().when(email).sendAccountEmailOrThrow(anyString(), anyString(), anyString(), any());
        assertThat(submitted().getStatus()).isEqualTo(TrialRequest.PENDING_VERIFICATION);
    }

    @Test @DisplayName("V8 is re-applied after Hibernate creates the table (startup safeguard for a fresh database)")
    void startupSafeguardRunsV8() throws Exception {
        org.springframework.jdbc.core.JdbcTemplate jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        new com.churchgeniuspro.config.TrialRequestSchemaInitializer(jdbc).apply();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).execute(sql.capture());
        assertThat(sql.getValue()).contains("CREATE UNIQUE INDEX ux_trial_request_open_email")
                                  .contains("lower(email)")
                                  .contains("'PENDING_VERIFICATION', 'VERIFIED'")
                                  .contains("interval '24 hours'");
        // ...and a failure (e.g. H2) never stops startup
        doThrow(new RuntimeException("H2")).when(jdbc).execute(anyString());
        new com.churchgeniuspro.config.TrialRequestSchemaInitializer(jdbc).apply();
    }

    @Test @DisplayName("AuthFilter lets the anonymous requester reach /api/trial-request")
    void authWhitelist() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/churchgeniuspro/webfilter/AuthFilter.java"));
        assertThat(src).contains("\"/api/trial-request\"");
    }
}
