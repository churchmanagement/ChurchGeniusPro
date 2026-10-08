package com.churchgeniuspro.subscription;

import com.churchgeniuspro.controller.SubscriptionPlanAdminController;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SubscriptionChange;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.model.ServiceClientBO;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.SubscriptionChangeRepository;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import com.churchgeniuspro.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 1 of the billing plan: yearly plan price, what each client pays, plan
 * validation, subscription history, and immediate cache refresh.
 */
@DisplayName("Client subscription pricing and history")
class ClientSubscriptionPricingTest {

    final Map<String, SubscriptionPlan> plans = new HashMap<>();
    final List<SubscriptionChange> history = new ArrayList<>();
    SubscriptionPlanRepository planRepo;
    SubscriptionLifecycleService lifecycle;
    SubscriptionService subscriptionService;
    AccountStatusService accountStatus;

    static SubscriptionPlan plan(String code, String monthly, String yearly, boolean active) {
        SubscriptionPlan p = new SubscriptionPlan();
        p.setPlanCode(code);
        p.setPlanName(code.charAt(0) + code.substring(1).toLowerCase());
        p.setMonthlyPrice(monthly == null ? null : new BigDecimal(monthly));
        p.setYearlyPrice(yearly == null ? null : new BigDecimal(yearly));
        p.setActive(active);
        return p;
    }

    @BeforeEach
    void setUp() throws Exception {
        plans.put("FREE", plan("FREE", "0", null, true));
        plans.put("STANDARD", plan("STANDARD", "14.99", "149.00", true));
        plans.put("PRO", plan("PRO", "34.99", null, true));
        plans.put("TRIAL", plan("TRIAL", "0", null, true));
        plans.put("OLDPLAN", plan("OLDPLAN", "9.00", null, false));
        plans.put("CHURCH_PARTNER_2026", plan("CHURCH_PARTNER_2026", "5.00", null, true));
        planRepo = mock(SubscriptionPlanRepository.class);
        when(planRepo.findByPlanCodeIgnoreCase(anyString()))
                .thenAnswer(i -> Optional.ofNullable(plans.get(((String) i.getArgument(0)).toUpperCase())));
        SubscriptionChangeRepository changeRepo = mock(SubscriptionChangeRepository.class);
        when(changeRepo.save(any(SubscriptionChange.class))).thenAnswer(i -> { history.add(i.getArgument(0)); return i.getArgument(0); });
        lifecycle = new SubscriptionLifecycleService(planRepo, changeRepo);
        subscriptionService = mock(SubscriptionService.class);
        accountStatus = mock(AccountStatusService.class);
        invoke("setSubscriptionService", SubscriptionService.class, subscriptionService);
        invoke("setAccountStatus", AccountStatusService.class, accountStatus);
    }

    private void invoke(String name, Class<?> type, Object arg) throws Exception {
        Method m = SubscriptionLifecycleService.class.getDeclaredMethod(name, type);
        m.setAccessible(true);
        m.invoke(lifecycle, arg);
    }

    static ServiceClient client(String type, String freq, String price, Boolean overridden) {
        ServiceClient c = new ServiceClient();
        c.setClientId("CHR-1");
        c.setSubscriptionType(type);
        c.setBillingFrequency(freq);
        c.setSubscriptionPrice(price == null ? null : new BigDecimal(price));
        c.setPriceOverridden(overridden);
        c.setStartDate(LocalDate.of(2026, 10, 1));
        c.setEndDate(LocalDate.of(2026, 11, 1));
        c.setStatus("Active");
        return c;
    }

    // ── Price rules ────────────────────────────────────────────────────────

    @Test @DisplayName("a new client gets the plan's monthly price, or its yearly price on yearly billing")
    void copiedFromPlan() {
        ServiceClient m = client("STANDARD", null, null, null);
        lifecycle.applyPricing(m, null, "MONTHLY", null, false, false);
        assertThat(m.getSubscriptionPrice()).isEqualByComparingTo("14.99");
        assertThat(m.getBillingFrequency()).isEqualTo("MONTHLY");
        assertThat(m.getPriceOverridden()).isFalse();

        ServiceClient y = client("STANDARD", null, null, null);
        lifecycle.applyPricing(y, null, "Yearly", null, false, false);
        assertThat(y.getSubscriptionPrice()).isEqualByComparingTo("149.00");
        assertThat(y.getBillingFrequency()).isEqualTo("YEARLY");
    }

    @Test @DisplayName("yearly billing on a plan with no yearly price is refused with a clear message")
    void noYearlyPrice() {
        ServiceClient c = client("PRO", null, null, null);
        assertThatThrownBy(() -> lifecycle.applyPricing(c, null, "YEARLY", null, false, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no yearly price");
        // ...but a negotiated yearly price is fine
        lifecycle.applyPricing(c, null, "YEARLY", new BigDecimal("300.00"), true, false);
        assertThat(c.getSubscriptionPrice()).isEqualByComparingTo("300.00");
        assertThat(c.getPriceOverridden()).isTrue();
    }

    @Test @DisplayName("a later plan price change does not alter an existing client's price; reset re-copies it")
    void snapshotKept() {
        ServiceClient c = client("STANDARD", "MONTHLY", "14.99", false);
        plans.get("STANDARD").setMonthlyPrice(new BigDecimal("19.99"));          // list price raised
        var before = SubscriptionLifecycleService.snapshot(c);
        lifecycle.applyPricing(c, before, "MONTHLY", null, false, false);       // form re-saved unchanged
        assertThat(c.getSubscriptionPrice()).isEqualByComparingTo("14.99");
        lifecycle.applyPricing(c, before, "MONTHLY", null, false, true);        // "Reset to plan price"
        assertThat(c.getSubscriptionPrice()).isEqualByComparingTo("19.99");
    }

    @Test @DisplayName("a negotiated price survives a plan change; unticking Custom returns to the plan price")
    void overrideRules() {
        ServiceClient c = client("STANDARD", "MONTHLY", "10.00", true);
        var before = SubscriptionLifecycleService.snapshot(c);
        c.setSubscriptionType("PRO");
        lifecycle.applyPricing(c, before, null, null, null, false);             // override state unchanged
        assertThat(c.getSubscriptionPrice()).isEqualByComparingTo("10.00");
        assertThat(c.getPriceOverridden()).isTrue();

        var before2 = SubscriptionLifecycleService.snapshot(c);
        lifecycle.applyPricing(c, before2, null, null, false, false);           // Custom unticked
        assertThat(c.getSubscriptionPrice()).isEqualByComparingTo("34.99");
        assertThat(c.getPriceOverridden()).isFalse();

        assertThatThrownBy(() -> lifecycle.applyPricing(client("FREE", null, null, null), null, null, null, true, false))
                .hasMessageContaining("custom price");
    }

    @Test @DisplayName("a plan change re-copies the new plan's price; legacy LIMITED/FULL resolve to Standard/Pro")
    void planChangeAndLegacy() {
        ServiceClient c = client("FREE", "MONTHLY", "0", false);
        var before = SubscriptionLifecycleService.snapshot(c);
        c.setSubscriptionType("FULL");
        lifecycle.applyPricing(c, before, null, null, false, false);
        assertThat(c.getSubscriptionPrice()).isEqualByComparingTo("34.99");

        ServiceClient legacy = client("LIMITED", null, null, null);
        lifecycle.applyPricing(legacy, null, null, null, null, false);
        assertThat(legacy.getSubscriptionPrice()).isEqualByComparingTo("14.99");
        assertThat(legacy.getBillingFrequency()).isEqualTo("MONTHLY");
    }

    @Test @DisplayName("price and frequency input are validated")
    void parsing() {
        assertThat(SubscriptionLifecycleService.parsePrice("$1,200")).isEqualByComparingTo("1200.00");
        assertThat(SubscriptionLifecycleService.parsePrice(" ")).isNull();
        assertThatThrownBy(() -> SubscriptionLifecycleService.parsePrice("-1")).hasMessageContaining("negative");
        assertThatThrownBy(() -> SubscriptionLifecycleService.parsePrice("abc")).hasMessageContaining("number");
        assertThatThrownBy(() -> SubscriptionLifecycleService.normaliseFrequency("weekly")).hasMessageContaining("Monthly or Yearly");
    }

    // ── Plan validation ─────────────────────────────────────────────────────

    @Test @DisplayName("a newly assigned plan must be an active plan; an unchanged deactivated plan still saves")
    void planValidation() {
        ServiceClient c = client("FREE", "MONTHLY", "0", false);
        var before = SubscriptionLifecycleService.snapshot(c);
        c.setSubscriptionType("NOPE");
        assertThatThrownBy(() -> lifecycle.validatePlanChange(before, c)).hasMessageContaining("does not exist or is inactive");
        c.setSubscriptionType("OLDPLAN");
        assertThatThrownBy(() -> lifecycle.validatePlanChange(before, c)).hasMessageContaining("inactive");

        ServiceClient onOld = client("OLDPLAN", "MONTHLY", "9.00", false);
        lifecycle.validatePlanChange(SubscriptionLifecycleService.snapshot(onOld), onOld);   // no throw

        ServiceClient custom = client("CHURCH_PARTNER_2026", null, null, null);   // 19 chars > old 20? no: 19
        lifecycle.validatePlanChange(null, custom);
        lifecycle.applyPricing(custom, null, null, null, null, false);
        assertThat(custom.getSubscriptionPrice()).isEqualByComparingTo("5.00");
    }

    // ── History + cache ─────────────────────────────────────────────────────

    @Test @DisplayName("a change writes one history row and drops the client's caches; a no-op writes none")
    void historyAndCaches() {
        ServiceClient c = client("FREE", "MONTHLY", "0", false);
        var before = SubscriptionLifecycleService.snapshot(c);
        lifecycle.recordAndRefresh(before, c, "ops", "EDIT");
        assertThat(history).isEmpty();
        verify(subscriptionService).evict("CHR-1");          // caches dropped even so
        verify(accountStatus).invalidateTenant("CHR-1");

        c.setSubscriptionType("STANDARD");
        c.setSubscriptionPrice(new BigDecimal("14.99"));
        lifecycle.recordAndRefresh(before, c, "ops", "EDIT");
        assertThat(history).hasSize(1);
        SubscriptionChange h = history.get(0);
        assertThat(h.getChangedBy()).isEqualTo("ops");
        assertThat(h.getFromPlan()).isEqualTo("FREE");
        assertThat(h.getToPlan()).isEqualTo("STANDARD");
        assertThat(h.getFromPrice()).isEqualByComparingTo("0");
        assertThat(h.getToPrice()).isEqualByComparingTo("14.99");

        lifecycle.recordAndRefresh(null, c, null, "EDIT");
        assertThat(history.get(1).getReason()).isEqualTo("CREATED");
    }

    // ── Through ServiceClientService (the Register / Edit Client endpoint) ───

    @Test @DisplayName("Register Client: plan validated, price set, history row with the admin's name")
    void registerAndEditClient() {
        ServiceClientRepository repo = mock(ServiceClientRepository.class);
        Map<Integer, ServiceClient> rows = new HashMap<>();
        when(repo.save(any(ServiceClient.class))).thenAnswer(i -> {
            ServiceClient sc = i.getArgument(0);
            if (sc.getId() == null) sc.setId(rows.size() + 1);
            rows.put(sc.getId(), sc);
            return sc;
        });
        when(repo.findById(anyInt())).thenAnswer(i -> Optional.ofNullable(rows.get((Integer) i.getArgument(0))));
        ServiceClientService svc = new ServiceClientService(repo, mock(EmailService.class),
                mock(WhatsAppSenderService.class), mock(LoginRepository.class));
        svc.setLifecycle(lifecycle);

        ServiceClientBO bo = new ServiceClientBO();
        bo.setName("Pat"); bo.setChurchName("Grace"); bo.setEmail("p@example.org");
        bo.setActivePeriod(1); bo.setActivePeriodUnit("YEARS"); bo.setStartDate("2026-10-05");
        bo.setSubscriptionType("STANDARD"); bo.setBillingFrequency("YEARLY");
        ServiceClient saved = svc.save(bo, "ops");
        assertThat(saved.getClientId()).startsWith("CHR");
        assertThat(saved.getSubscriptionPrice()).isEqualByComparingTo("149.00");
        assertThat(saved.getBillingFrequency()).isEqualTo("YEARLY");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).getReason()).isEqualTo("CREATED");
        assertThat(history.get(0).getChangedBy()).isEqualTo("ops");

        // Negotiated price on edit
        bo.setPriceOverridden(true); bo.setSubscriptionPrice("120");
        ServiceClient edited = svc.update(saved.getId(), bo, "ops");
        assertThat(edited.getSubscriptionPrice()).isEqualByComparingTo("120.00");
        assertThat(history).hasSize(2);

        // An unknown plan is refused and nothing more is recorded
        bo.setSubscriptionType("BOGUS");
        assertThatThrownBy(() -> svc.update(saved.getId(), bo, "ops")).hasMessageContaining("does not exist");
        assertThat(history).hasSize(2);
    }

    @Test @DisplayName("the history repository's derived finder parses against the entity")
    void historyFinderParses() {
        new org.springframework.data.repository.query.parser.PartTree(
                "findByClientIdOrderByChangedAtDescIdDesc", SubscriptionChange.class);
    }

    // ── Plan admin API ──────────────────────────────────────────────────────

    @Test @DisplayName("Subscription Plans API saves and returns the yearly price; blank = not offered")
    @SuppressWarnings("unchecked")
    void planYearlyPriceApi() {
        SubscriptionPlan p = plan("STANDARD", "14.99", null, true);
        p.setId(3L);
        when(planRepo.findById(3L)).thenReturn(Optional.of(p));
        when(planRepo.save(any(SubscriptionPlan.class))).thenAnswer(i -> i.getArgument(0));
        SubscriptionPlanAdminController c = new SubscriptionPlanAdminController(planRepo, mock(SubscriptionService.class));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.getSession(true).setAttribute("serviceAdminId", 1);
        req.getSession().setAttribute("role", "ServiceAdmin");

        ResponseEntity<?> r = c.update(3L, new HashMap<>(Map.of("yearlyPrice", "149")), req);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(p.getYearlyPrice()).isEqualByComparingTo("149.00");
        Map<String, Object> out = (Map<String, Object>) ((Map<String, Object>) r.getBody()).get("plan");
        assertThat(out.get("yearlyPrice")).isEqualTo(new BigDecimal("149.00"));

        Map<String, Object> blank = new HashMap<>();
        blank.put("yearlyPrice", "");
        c.update(3L, blank, req);
        assertThat(p.getYearlyPrice()).isNull();
    }
}
