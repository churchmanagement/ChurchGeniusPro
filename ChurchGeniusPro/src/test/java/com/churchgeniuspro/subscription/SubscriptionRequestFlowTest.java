package com.churchgeniuspro.subscription;

import com.churchgeniuspro.controller.ServiceAdminSubscriptionRequestController;
import com.churchgeniuspro.controller.SubscriptionRequestController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import com.churchgeniuspro.util.AppClock;
import com.churchgeniuspro.util.PublicFormGuard;
import com.churchgeniuspro.webfilter.AccountStatusFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 3: Request for Subscription (/subscriptionReq), the Service Admin list, the
 * trial reminder link, and Convert (empty-account trial → paid plan, same account).
 */
@DisplayName("Subscription requests and conversion")
class SubscriptionRequestFlowTest {

    final Map<Long, SubscriptionRequest> rows = new LinkedHashMap<>();
    final Map<String, ServiceClient> clientsById = new HashMap<>();
    final List<SubscriptionChange> history = new ArrayList<>();
    final AtomicLong ids = new AtomicLong();

    SubscriptionRequestRepository repo;
    ServiceClientRepository clientRepo;
    SubscriptionPlanRepository planRepo;
    ChurchRegistrationRepository churchRepo;
    AppUserRepository userRepo;
    EmailService email;
    PlatformSettingService settings;
    SubscriptionRequestService service;
    ServiceClient chr;      // empty-account trial
    ServiceClient sample;   // sample-data trial

    static SubscriptionPlan plan(String code, String name, String monthly, String yearly, boolean active, int sort) {
        SubscriptionPlan p = new SubscriptionPlan();
        p.setPlanCode(code); p.setPlanName(name); p.setActive(active); p.setSortOrder(sort);
        p.setMonthlyPrice(monthly == null ? null : new BigDecimal(monthly));
        p.setYearlyPrice(yearly == null ? null : new BigDecimal(yearly));
        return p;
    }

    static ServiceClient client(Integer id, String cid, String church, String email, String plan) {
        ServiceClient c = new ServiceClient();
        c.setId(id); c.setClientId(cid); c.setChurchName(church); c.setEmail(email); c.setName("Pat Pastor");
        c.setSubscriptionType(plan); c.setStatus("Active"); c.setDeleteFlag(false);
        c.setStartDate(AppClock.today().minusDays(50)); c.setEndDate(AppClock.today().plusDays(10));
        return c;
    }

    @BeforeEach
    void setUp() {
        repo = mock(SubscriptionRequestRepository.class);
        when(repo.save(any(SubscriptionRequest.class))).thenAnswer(i -> {
            SubscriptionRequest r = i.getArgument(0);
            if (r.getId() == null) r.setId(ids.incrementAndGet());
            if (r.getCreatedAt() == null) r.setCreatedAt(LocalDateTime.now());
            rows.put(r.getId(), r);
            return r;
        });
        when(repo.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(rows.get((Long) i.getArgument(0))));
        when(repo.findAllByOrderByCreatedAtDesc()).thenAnswer(i -> new ArrayList<>(rows.values()));
        when(repo.findByClientIdAndStatusInOrderByCreatedAtDesc(anyString(), any())).thenAnswer(i -> {
            Collection<String> st = i.getArgument(1);
            List<SubscriptionRequest> out = new ArrayList<>();
            for (SubscriptionRequest r : rows.values())
                if (r.getClientId().equals(i.getArgument(0)) && st.contains(r.getStatus())) out.add(0, r);
            return out;
        });
        when(repo.transition(anyLong(), any(), anyString(), any(), any())).thenAnswer(i -> {
            SubscriptionRequest r = rows.get((Long) i.getArgument(0));
            Collection<String> from = i.getArgument(1);
            if (r == null || !from.contains(r.getStatus())) return 0;
            r.setStatus(i.getArgument(2)); r.setDecidedBy(i.getArgument(3)); r.setDecidedAt(i.getArgument(4));
            return 1;
        });

        chr = client(1, "CHR-1", "Grace Chapel", "pat@grace.org", "TRIAL");
        sample = client(2, "TRIAL-99", "Hope Church", "ann@hope.org", "TRIAL");
        clientsById.put(chr.getClientId(), chr);
        clientsById.put(sample.getClientId(), sample);
        clientRepo = mock(ServiceClientRepository.class);
        when(clientRepo.findByClientId(anyString())).thenAnswer(i -> Optional.ofNullable(clientsById.get((String) i.getArgument(0))));
        when(clientRepo.findBySubscriptionRequestTokenAndDeleteFlagFalse(anyString())).thenAnswer(i ->
                clientsById.values().stream().filter(c -> i.getArgument(0).equals(c.getSubscriptionRequestToken())).findFirst());
        when(clientRepo.findById(anyInt())).thenAnswer(i -> clientsById.values().stream()
                .filter(c -> c.getId().equals(i.getArgument(0))).findFirst());
        when(clientRepo.save(any(ServiceClient.class))).thenAnswer(i -> i.getArgument(0));

        planRepo = mock(SubscriptionPlanRepository.class);
        List<SubscriptionPlan> plans = List.of(plan("TRIAL", "Trial", "0", null, true, 0),
                plan("FREE", "Free", "0", null, true, 1), plan("STANDARD", "Standard", "14.99", "149.00", true, 2),
                plan("PRO", "Pro", "34.99", null, true, 3), plan("OLD", "Old", "9", null, false, 4));
        when(planRepo.findAllByOrderBySortOrderAscIdAsc()).thenReturn(plans);
        when(planRepo.findByPlanCodeIgnoreCase(anyString())).thenAnswer(i -> plans.stream()
                .filter(p -> p.getPlanCode().equalsIgnoreCase(i.getArgument(0))).findFirst());

        churchRepo = mock(ChurchRegistrationRepository.class);
        ChurchRegistration cr = new ChurchRegistration();
        cr.setEmail("office@grace.org");
        when(churchRepo.findByClientIdAndDeleteFlagFalse("CHR-1")).thenReturn(Optional.of(cr));
        userRepo = mock(AppUserRepository.class);
        AppUser staff = new AppUser(); staff.setEmail("Staff@Grace.org"); staff.setEnabled(true);
        AppUser off = new AppUser(); off.setEmail("gone@grace.org"); off.setEnabled(false);
        when(userRepo.findByClientIdAndDeleteFlagFalseOrderByLastNameAscFirstNameAsc("CHR-1")).thenReturn(List.of(staff, off));

        email = mock(EmailService.class);
        PlatformSettingRepository sRepo = mock(PlatformSettingRepository.class);
        Map<String, PlatformSetting> settingRows = new HashMap<>();
        when(sRepo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(settingRows.get((String) i.getArgument(0))));
        when(sRepo.save(any(PlatformSetting.class))).thenAnswer(i -> { PlatformSetting s = i.getArgument(0); settingRows.put(s.getKey(), s); return s; });
        settings = new PlatformSettingService(sRepo);

        service = new SubscriptionRequestService(repo, clientRepo, planRepo, churchRepo, userRepo, email, settings);
        service.setBaseUrl("https://app.example/");
    }

    static SubscriptionRequestService.Form form(String email, String plan, String freq) {
        return new SubscriptionRequestService.Form("Pat", "Pastor", email, "555-0100", plan, freq, "Please start next month");
    }

    // ── The link ────────────────────────────────────────────────────────────

    @Test @DisplayName("the request link is issued once, reused by every reminder, and renewed after it expires")
    void link() {
        String a = service.linkFor(chr);
        assertThat(a).startsWith("https://app.example/subscriptionReq.html?t=");
        assertThat(chr.getSubscriptionRequestTokenExpires()).isEqualTo(chr.getEndDate().plusDays(60));
        assertThat(service.linkFor(chr)).isEqualTo(a);                     // same link in the next reminder
        String token = a.substring(a.indexOf("t=") + 2);
        assertThat(service.clientForToken(token)).contains(chr);
        assertThat(service.clientForToken("nope")).isEmpty();
        assertThat(service.clientForToken(null)).isEmpty();

        chr.setSubscriptionRequestTokenExpires(AppClock.today().minusDays(1));
        assertThat(service.clientForToken(token)).isEmpty();               // expired
        String b = service.linkFor(chr);
        assertThat(b).isNotEqualTo(a);                                     // renewed
    }

    @Test @DisplayName("a signed-in church owner/admin reaches the page for their own church; members do not")
    void sessionAccess() {
        MockHttpServletRequest admin = new MockHttpServletRequest();
        admin.getSession(true).setAttribute("username", "pastor");
        admin.getSession().setAttribute("role", "Admin");
        admin.getSession().setAttribute("appClientId", "CHR-1");
        assertThat(service.clientForSession(admin)).contains(chr);

        MockHttpServletRequest member = new MockHttpServletRequest();
        member.getSession(true).setAttribute("username", "m");
        member.getSession().setAttribute("role", "Member");
        member.getSession().setAttribute("memberId", 5);
        member.getSession().setAttribute("appClientId", "CHR-1");
        assertThat(service.clientForSession(member)).isEmpty();
        assertThat(service.clientForSession(new MockHttpServletRequest())).isEmpty();
    }

    // ── The page ────────────────────────────────────────────────────────────

    @Test @DisplayName("form info: church name from the account; active plans except Trial, with yearly price where set")
    @SuppressWarnings("unchecked")
    void formInfo() {
        Map<String, Object> info = service.formInfo(chr);
        assertThat(info).containsEntry("churchName", "Grace Chapel").containsEntry("registerAsNewClient", false);
        List<Map<String, Object>> plans = (List<Map<String, Object>>) info.get("plans");
        assertThat(plans).extracting(p -> p.get("planCode")).containsExactly("FREE", "STANDARD", "PRO");
        assertThat(plans.get(1).get("yearlyPrice")).isEqualTo(new BigDecimal("149.00"));
        assertThat(plans.get(2).get("yearlyPrice")).isNull();
        assertThat(service.formInfo(sample)).containsEntry("registerAsNewClient", true);
    }

    // ── Submit ──────────────────────────────────────────────────────────────

    @Test @DisplayName("submit saves the request (church from the account) and notifies the configured Support Email")
    void submit() throws Exception {
        settings.set(PlatformSettingService.SUPPORT_EMAIL, "billing@cgp.org", "ops");
        SubscriptionRequest r = service.submit(chr, form("PAT@grace.org", "STANDARD", "YEARLY"), "1.2.3.4");
        assertThat(r.getChurchName()).isEqualTo("Grace Chapel");
        assertThat(r.getRegisteredEmail()).isEqualTo("pat@grace.org");
        assertThat(r.getPlanCode()).isEqualTo("STANDARD");
        assertThat(r.getBillingFrequency()).isEqualTo("YEARLY");
        assertThat(r.getStatus()).isEqualTo(SubscriptionRequest.NEW);
        assertThat(r.getRegisterAsNewClient()).isFalse();
        assertThat(r.getSupportEmailSent()).isTrue();
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email).sendComposed(eq(List.of("billing@cgp.org")), isNull(), contains("Grace Chapel"), html.capture(), isNull(), anyString());
        assertThat(html.getValue()).contains("Pat", "Pastor", "pat@grace.org", "555-0100", "Grace Chapel",
                "Standard (STANDARD)", "Yearly", "Please start next month", "Request Date/Time", "Convert this account");
    }

    @Test @DisplayName("Registered Email may be the client email, the church registration email, or an active user's")
    void registeredEmailMatching() {
        service.submit(chr, form("office@grace.org", "FREE", "MONTHLY"), "ip");
        rows.clear();
        service.submit(chr, form("staff@grace.org", "FREE", "MONTHLY"), "ip");
        rows.clear();
        assertThatThrownBy(() -> service.submit(chr, form("gone@grace.org", "FREE", null), "ip"))
                .hasMessageContaining("does not match the email registered for Grace Chapel");
        assertThatThrownBy(() -> service.submit(chr, form("someone@else.org", "FREE", null), "ip"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("plans: only listed ones; Yearly only when the plan has a yearly price")
    void planRules() {
        assertThatThrownBy(() -> service.submit(chr, form("pat@grace.org", "TRIAL", null), "ip")).hasMessageContaining("plans listed");
        assertThatThrownBy(() -> service.submit(chr, form("pat@grace.org", "OLD", null), "ip")).hasMessageContaining("plans listed");
        assertThatThrownBy(() -> service.submit(chr, form("pat@grace.org", "PRO", "YEARLY"), "ip")).hasMessageContaining("monthly only");
        assertThat(SubscriptionRequestService.validate(new SubscriptionRequestService.Form(null, "P", "a@b.co", null, "FREE", null, null)))
                .contains("First Name");
        assertThat(SubscriptionRequestService.validate(new SubscriptionRequestService.Form("A", "P", "a@b.co", null, null, null, null)))
                .contains("choose a plan");
    }

    @Test @DisplayName("one open request per church; a closed one does not block a new request")
    void oneOpenRequest() {
        SubscriptionRequest first = service.submit(chr, form("pat@grace.org", "FREE", null), "ip");
        assertThatThrownBy(() -> service.submit(chr, form("pat@grace.org", "PRO", null), "ip"))
                .isInstanceOf(SubscriptionRequestService.DuplicateRequestException.class)
                .hasMessageContaining("already open");
        service.decline(first.getId(), "ops", "duplicate");
        assertThat(service.submit(chr, form("pat@grace.org", "PRO", null), "ip").getStatus()).isEqualTo("NEW");
    }

    @Test @DisplayName("two simultaneous submits: the loser of the unique index gets the same refusal")
    void race() {
        when(repo.save(any(SubscriptionRequest.class))).thenThrow(new org.springframework.dao.DataIntegrityViolationException("ux"));
        assertThatThrownBy(() -> service.submit(chr, form("pat@grace.org", "FREE", null), "ip"))
                .isInstanceOf(SubscriptionRequestService.DuplicateRequestException.class);
    }

    @Test @DisplayName("a Support Email outage never loses the request")
    void supportEmailFailure() throws Exception {
        doThrow(new RuntimeException("smtp")).when(email).sendComposed(anyList(), any(), anyString(), anyString(), any(), any());
        SubscriptionRequest r = service.submit(chr, form("pat@grace.org", "FREE", null), "ip");
        assertThat(rows).containsKey(r.getId());
        assertThat(r.getSupportEmailSent()).isFalse();
    }

    @Test @DisplayName("a sample-data trial's request is accepted and flagged Register as New Client")
    void sampleTrialFlagged() throws Exception {
        SubscriptionRequest r = service.submit(sample, form("ann@hope.org", "STANDARD", null), "ip");
        assertThat(r.getRegisterAsNewClient()).isTrue();
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email).sendComposed(anyList(), any(), anyString(), html.capture(), any(), any());
        assertThat(html.getValue()).contains("Register as New Client");
    }

    // ── Service Admin ───────────────────────────────────────────────────────

    @Test @DisplayName("Service Admin: in progress, decline (reason kept), complete; closed requests cannot move again")
    void adminTransitions() throws Exception {
        SubscriptionRequest r = service.submit(chr, form("pat@grace.org", "FREE", null), "ip");
        service.markInProgress(r.getId(), "ops");
        assertThat(r.getStatus()).isEqualTo("IN_PROGRESS");
        assertThatThrownBy(() -> service.markInProgress(r.getId(), "ops")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.complete(r.getId(), "CHR-other", "ops")).hasMessageContaining("different church");
        service.complete(r.getId(), "CHR-1", "ops");
        assertThat(r.getStatus()).isEqualTo("COMPLETED");
        assertThatThrownBy(() -> service.decline(r.getId(), "ops", null)).hasMessageContaining("not open");

        SubscriptionRequest s = service.submit(chr, form("pat@grace.org", "PRO", null), "ip");
        service.decline(s.getId(), "ops", "Chose to wait");
        assertThat(s.getStatus()).isEqualTo("DECLINED");
        assertThat(s.getDeclineReason()).isEqualTo("Chose to wait");
        verify(email, never()).sendAccountEmailOrThrow(anyString(), anyString(), anyString(), any());   // nothing to the church
    }

    // ── Controllers & filters ───────────────────────────────────────────────

    @Test @DisplayName("public API: no token and no admin session → 403; a valid link works end to end; honeypot stores nothing")
    void publicApi() {
        PublicFormGuard guard = mock(PublicFormGuard.class);
        SubscriptionRequestController c = new SubscriptionRequestController(service, guard);
        assertThat(c.formInfo(null, new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(403);
        assertThat(c.formInfo("bad", new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(403);

        String t = service.linkFor(chr).replaceAll(".*t=", "");
        ResponseEntity<Map<String, Object>> info = c.formInfo(t, new MockHttpServletRequest());
        assertThat(info.getStatusCode().value()).isEqualTo(200);
        assertThat(info.getBody()).containsEntry("churchName", "Grace Chapel");

        Map<String, Object> body = new HashMap<>(Map.of("t", t, "firstName", "Pat", "lastName", "Pastor",
                "registeredEmail", "pat@grace.org", "planCode", "STANDARD", "formToken", "ok"));
        assertThat(c.submit(body, new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(200);
        assertThat(c.submit(body, new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(409);

        when(guard.isHoneypotTripped(any())).thenReturn(true);
        int before = rows.size();
        assertThat(c.submit(new HashMap<>(Map.of("website", "x")), new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(200);
        assertThat(rows).hasSize(before);
    }

    @Test @DisplayName("Service Admin endpoints return 401 without a Service Admin session")
    void adminOnly() {
        ServiceAdminSubscriptionRequestController c = new ServiceAdminSubscriptionRequestController(service);
        MockHttpServletRequest anon = new MockHttpServletRequest();
        assertThat(c.list(anon).getStatusCode().value()).isEqualTo(401);
        assertThat(c.inProgress(1L, anon).getStatusCode().value()).isEqualTo(401);
        assertThat(c.decline(1L, null, anon).getStatusCode().value()).isEqualTo(401);
        assertThat(c.complete(1L, anon).getStatusCode().value()).isEqualTo(401);
    }

    @Test @DisplayName("an expired church session can still reach /api/subscription-request, but nothing else")
    void expiredSessionAllowed() throws Exception {
        ServiceClientRepository cr = mock(ServiceClientRepository.class);
        ServiceClient expired = client(1, "CHR-1", "Grace", "p@g.org", "TRIAL");
        expired.setEndDate(AppClock.today());
        when(cr.findByClientId("CHR-1")).thenReturn(Optional.of(expired));
        DemoRoleAccessRepository access = mock(DemoRoleAccessRepository.class);
        when(access.findByUsername(anyString())).thenReturn(Optional.empty());
        AccountStatusService status = new AccountStatusService(cr, access);
        for (String uri : List.of("/api/subscription-request/form-info", "/api/members")) {
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("appClientId", "CHR-1");
            session.setAttribute("username", "pastor");
            MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
            req.setSession(session);
            MockHttpServletResponse res = new MockHttpServletResponse();
            FilterChain chain = (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(200);
            new AccountStatusFilter(status).doFilter(req, res, chain);
            assertThat(res.getStatus()).as(uri).isEqualTo(uri.startsWith("/api/subscription-request") ? 200 : 403);
        }
    }

    @Test @DisplayName("AuthFilter and the trial-agreement filter let the request API through")
    void filterLists() throws Exception {
        String auth = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/churchgeniuspro/webfilter/AuthFilter.java"));
        String agree = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/churchgeniuspro/webfilter/DemoTrialAgreementFilter.java"));
        assertThat(auth).contains("\"/api/subscription-request\",");
        assertThat(agree).contains("\"/api/subscription-request\"");
    }

    // ── Convert ─────────────────────────────────────────────────────────────

    @Nested @DisplayName("Convert (empty-account trial → paid plan)")
    class Convert {
        ServiceClientService svc;
        SubscriptionLifecycleService lifecycle;

        @BeforeEach
        void wire() throws Exception {
            SubscriptionChangeRepository changes = mock(SubscriptionChangeRepository.class);
            when(changes.save(any(SubscriptionChange.class))).thenAnswer(i -> { history.add(i.getArgument(0)); return i.getArgument(0); });
            lifecycle = new SubscriptionLifecycleService(planRepo, changes);
            svc = new ServiceClientService(clientRepo, email, mock(WhatsAppSenderService.class), mock(LoginRepository.class));
            svc.setLifecycle(lifecycle);
            svc.setSubscriptionRequests(service);
        }

        ServiceClientService.ConvertCommand cmd(String plan, String freq, String price, String end, boolean send, Long req) {
            return new ServiceClientService.ConvertCommand(plan, freq, price, AppClock.today().toString(), end, "PAID", send, req);
        }

        @Test @DisplayName("converts in place: same client and email, new plan, yearly price, dates, history, request completed, confirmation sent")
        void converts() throws Exception {
            SubscriptionRequest r = service.submit(chr, form("pat@grace.org", "STANDARD", "YEARLY"), "ip");
            String end = AppClock.today().plusYears(1).toString();
            ServiceClientService.ConvertResult res = svc.convert(1, cmd("STANDARD", "YEARLY", null, end, true, r.getId()), "ops");
            ServiceClient c = res.client();
            assertThat(c.getClientId()).isEqualTo("CHR-1");
            assertThat(c.getSubscriptionType()).isEqualTo("STANDARD");
            assertThat(c.getBillingFrequency()).isEqualTo("YEARLY");
            assertThat(c.getSubscriptionPrice()).isEqualByComparingTo("149.00");
            assertThat(c.getStartDate()).isEqualTo(AppClock.today());
            assertThat(c.getEndDate()).isEqualTo(AppClock.today().plusYears(1));
            assertThat(c.getActivePeriod()).isEqualTo(1);
            assertThat(c.getActivePeriodUnit()).isEqualTo("YEARS");
            assertThat(c.getPaymentStatus()).isEqualTo("PAID");
            assertThat(c.getStatus()).isEqualTo("Active");
            assertThat(history).anyMatch(h -> "CONVERSION".equals(h.getReason()) && "TRIAL".equals(h.getFromPlan())
                                               && "STANDARD".equals(h.getToPlan()) && "ops".equals(h.getChangedBy()));
            assertThat(r.getStatus()).isEqualTo("COMPLETED");
            ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
            verify(email).sendAccountEmailOrThrow(eq("pat@grace.org"), contains("Standard"), html.capture(), isNull());
            assertThat(html.getValue()).contains("You can continue using the application with the <strong>Standard</strong> subscription",
                    "username, password and account are unchanged", "$149.00 per year");
            assertThat(res.emailSent()).isTrue();
        }

        @Test @DisplayName("a negotiated price and a monthly term; no confirmation when not ticked")
        void customPriceMonthly() throws Exception {
            String end = AppClock.today().plusMonths(1).toString();
            ServiceClient c = svc.convert(1, cmd("PRO", "MONTHLY", "25", end, false, null), "ops").client();
            assertThat(c.getSubscriptionPrice()).isEqualByComparingTo("25.00");
            assertThat(c.getPriceOverridden()).isTrue();
            assertThat(c.getActivePeriodUnit()).isEqualTo("MONTHS");
            verify(email, never()).sendAccountEmailOrThrow(anyString(), anyString(), anyString(), any());
        }

        @Test @DisplayName("a failed confirmation email does not undo the conversion")
        void emailFailure() throws Exception {
            doThrow(new RuntimeException("smtp")).when(email).sendAccountEmailOrThrow(anyString(), anyString(), anyString(), any());
            ServiceClientService.ConvertResult res = svc.convert(1, cmd("FREE", null, null, AppClock.today().plusMonths(1).toString(), true, null), "ops");
            assertThat(res.client().getSubscriptionType()).isEqualTo("FREE");
            assertThat(res.emailSent()).isFalse();
            assertThat(res.emailError()).contains("could not be sent");
        }

        @Test @DisplayName("refused: sample-data trial, non-trial client, Trial as target, past end date, another church's request")
        void refusals() {
            String end = AppClock.today().plusMonths(1).toString();
            assertThatThrownBy(() -> svc.convert(2, cmd("PRO", null, null, end, false, null), "ops"))
                    .hasMessageContaining("cannot be converted");
            assertThatThrownBy(() -> svc.convert(1, cmd("TRIAL", null, null, end, false, null), "ops"))
                    .hasMessageContaining("Choose a paid plan");
            assertThatThrownBy(() -> svc.convert(1, cmd("PRO", null, null, AppClock.today().toString(), false, null), "ops"))
                    .hasMessageContaining("after");
            SubscriptionRequest other = service.submit(sample, form("ann@hope.org", "PRO", null), "ip");
            assertThatThrownBy(() -> svc.convert(1, cmd("PRO", null, null, end, false, other.getId()), "ops"))
                    .hasMessageContaining("different church");
            assertThat(chr.getSubscriptionType()).isEqualTo("TRIAL");          // nothing written
            chr.setSubscriptionType("PRO");
            assertThatThrownBy(() -> svc.convert(1, cmd("STANDARD", null, null, end, false, null), "ops"))
                    .hasMessageContaining("Use Edit");
        }
    }

    // ── Reminder emails ─────────────────────────────────────────────────────

    @Test @DisplayName("trial reminders carry the request link; sample-data wording differs; paid notices are unchanged")
    void reminders() throws Exception {
        ReminderSentLogRepository logRepo = mock(ReminderSentLogRepository.class);
        ServiceClient paid = client(3, "CHR-3", "Paid Church", "paid@x.org", "PRO");
        LocalDate in10 = LocalDate.now().plusDays(10);      // the notifier keeps its own date source (unchanged)
        for (ServiceClient c : List.of(chr, sample, paid)) c.setEndDate(in10);
        when(clientRepo.findByEndDateAndStatusAndDeleteFlagFalse(any(), eq("Active"))).thenAnswer(i ->
                in10.equals(i.getArgument(0)) ? List.of(chr, sample, paid) : List.of());
        SubscriptionExpiryNotifier n = new SubscriptionExpiryNotifier(clientRepo, logRepo, email);
        n.setSubscriptionRequests(service);
        n.run();
        ArgumentCaptor<String> subj = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(email, times(3)).sendGenericEmail(anyString(), subj.capture(), body.capture());
        Map<String, String[]> byChurch = new HashMap<>();
        for (int i = 0; i < 3; i++) byChurch.put(subj.getAllValues().get(i).split(" — ")[0], new String[]{subj.getAllValues().get(i), body.getAllValues().get(i)});
        assertThat(byChurch.get("⏰ Grace Chapel")[0]).contains("trial ends in 10 days");
        assertThat(byChurch.get("⏰ Grace Chapel")[1]).contains("/subscriptionReq.html?t=", "you keep this account");
        assertThat(byChurch.get("⏰ Hope Church")[1]).contains("/subscriptionReq.html?t=", "cannot be turned into a paid account");
        assertThat(byChurch.get("⏰ Paid Church")[0]).contains("subscription expires in 10 days");
        assertThat(byChurch.get("⏰ Paid Church")[1]).doesNotContain("subscriptionReq").contains("info@churchgeniuspro.com");
    }
}
