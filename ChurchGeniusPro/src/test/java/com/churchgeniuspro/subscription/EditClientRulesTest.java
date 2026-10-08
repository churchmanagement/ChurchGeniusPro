package com.churchgeniuspro.subscription;

import com.churchgeniuspro.controller.ServiceAdminController;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SubscriptionChange;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.model.ServiceClientBO;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import com.churchgeniuspro.util.AppClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 4 follow-up (2026-10-05): Edit Client asks for confirmation before saving a
 * CHANGED start date; Trial → paid goes only through Convert; paid-plan edits are
 * unchanged; a missing start date is today's Chicago date.
 */
@DisplayName("Edit Client — start-date confirmation and Trial → paid only via Convert")
class EditClientRulesTest {

    final Map<Integer, ServiceClient> rows = new HashMap<>();
    final List<SubscriptionChange> history = new ArrayList<>();
    ServiceClientRepository repo;
    ServiceClientService svc;
    TestDataService tds;

    static SubscriptionPlan plan(String code, String monthly) {
        SubscriptionPlan p = new SubscriptionPlan();
        p.setPlanCode(code); p.setPlanName(code); p.setActive(true); p.setMonthlyPrice(new BigDecimal(monthly));
        return p;
    }

    @BeforeEach
    void setUp() {
        repo = mock(ServiceClientRepository.class);
        when(repo.findById(anyInt())).thenAnswer(i -> Optional.ofNullable(rows.get((Integer) i.getArgument(0))));
        when(repo.findByClientId(anyString())).thenAnswer(i -> rows.values().stream()
                .filter(c -> i.getArgument(0).equals(c.getClientId())).findFirst());
        when(repo.save(any(ServiceClient.class))).thenAnswer(i -> {
            ServiceClient c = i.getArgument(0);
            if (c.getId() == null) c.setId(100 + rows.size());
            rows.put(c.getId(), c);
            return c;
        });
        SubscriptionPlanRepository plans = mock(SubscriptionPlanRepository.class);
        Map<String, SubscriptionPlan> byCode = Map.of("TRIAL", plan("TRIAL", "0"), "FREE", plan("FREE", "0"),
                "STANDARD", plan("STANDARD", "14.99"), "PRO", plan("PRO", "34.99"));
        when(plans.findByPlanCodeIgnoreCase(anyString())).thenAnswer(i -> Optional.ofNullable(byCode.get(((String) i.getArgument(0)).toUpperCase())));
        SubscriptionChangeRepository changes = mock(SubscriptionChangeRepository.class);
        when(changes.save(any(SubscriptionChange.class))).thenAnswer(i -> { history.add(i.getArgument(0)); return i.getArgument(0); });
        svc = new ServiceClientService(repo, mock(EmailService.class), mock(WhatsAppSenderService.class), mock(LoginRepository.class));
        svc.setLifecycle(new SubscriptionLifecycleService(plans, changes));
        tds = mock(TestDataService.class);
        ReflectionTestUtils.setField(svc, "testDataService", tds);
    }

    ServiceClient client(int id, String cid, String plan, LocalDate start) {
        ServiceClient c = new ServiceClient();
        c.setId(id); c.setClientId(cid); c.setName("Pat"); c.setChurchName("Grace"); c.setEmail("p@g.org");
        c.setSubscriptionType(plan); c.setStartDate(start); c.setActivePeriod(1); c.setActivePeriodUnit("MONTHS");
        c.setEndDate(start.plusMonths(1)); c.setStatus("Active"); c.setBillingFrequency("MONTHLY");
        c.setSubscriptionPrice(BigDecimal.ZERO); c.setPriceOverridden(false);
        rows.put(id, c);
        return c;
    }

    /** What the Edit form posts back for a client when nothing is changed. */
    static ServiceClientBO form(ServiceClient c) {
        ServiceClientBO bo = new ServiceClientBO();
        bo.setName(c.getName()); bo.setChurchName(c.getChurchName()); bo.setEmail(c.getEmail());
        bo.setActivePeriod(c.getActivePeriod()); bo.setActivePeriodUnit(c.getActivePeriodUnit());
        bo.setStartDate(c.getStartDate().toString()); bo.setSubscriptionType(c.getSubscriptionType());
        bo.setBillingFrequency(c.getBillingFrequency()); bo.setStatus(c.getStatus());
        return bo;
    }

    // ── 1. Start-date confirmation ─────────────────────────────────────────

    @Test @DisplayName("start date unchanged → saves with no confirmation")
    void unchangedStartNoConfirmation() {
        ServiceClient c = client(1, "CHR-1", "STANDARD", AppClock.today().minusDays(40));
        ServiceClientBO bo = form(c);
        bo.setNote("updated note");
        ServiceClient saved = svc.update(1, bo, "ops");
        assertThat(saved.getNote()).isEqualTo("updated note");
        assertThat(saved.getStartDate()).isEqualTo(AppClock.today().minusDays(40));
    }

    @Test @DisplayName("start date changed without confirmation → refused with the warning; nothing saved")
    void changedStartNeedsConfirmation() {
        LocalDate oldStart = AppClock.today().minusDays(40);
        ServiceClient c = client(1, "CHR-1", "STANDARD", oldStart);
        ServiceClientBO bo = form(c);
        bo.setStartDate(AppClock.today().toString());
        assertThatThrownBy(() -> svc.update(1, bo, "ops"))
                .isInstanceOf(ServiceClientService.StartDateConfirmationRequired.class)
                .hasMessageContaining("end date")
                .hasMessageContaining("30-day usage period")
                .hasMessageContaining("email, SMS and online-giving usage");
        verify(repo, never()).save(any());
        assertThat(c.getStartDate()).isEqualTo(oldStart);
    }

    @Test @DisplayName("start date changed WITH confirmation → saved, end date moves, history records it")
    void changedStartConfirmed() {
        LocalDate oldStart = AppClock.today().minusDays(40);
        ServiceClient c = client(1, "CHR-1", "STANDARD", oldStart);
        ServiceClientBO bo = form(c);
        LocalDate newStart = AppClock.today();
        bo.setStartDate(newStart.toString());
        bo.setConfirmStartDateChange(true);
        ServiceClient saved = svc.update(1, bo, "ops");
        assertThat(saved.getStartDate()).isEqualTo(newStart);
        assertThat(saved.getEndDate()).isEqualTo(newStart.plusMonths(1));
        assertThat(history).anyMatch(h -> oldStart.equals(h.getFromStartDate()) && newStart.equals(h.getToStartDate())
                                          && "ops".equals(h.getChangedBy()));
    }

    @Test @DisplayName("trial/demo tenants are asked too before their start date changes")
    void managedTenantAsked() {
        ServiceClient c = client(2, "TRIAL-1", "TRIAL", AppClock.today().minusDays(5));
        ServiceClientBO bo = form(c);
        bo.setStartDate(AppClock.today().minusDays(2).toString());
        assertThatThrownBy(() -> svc.update(2, bo, "ops")).isInstanceOf(ServiceClientService.StartDateConfirmationRequired.class);
        verifyNoInteractions(tds);
    }

    @Test @DisplayName("the API answers 409 START_DATE_CONFIRMATION_REQUIRED; a Trial → paid edit answers 400")
    void controllerResponses() {
        client(1, "CHR-1", "STANDARD", AppClock.today().minusDays(40));
        client(3, "CHR-3", "TRIAL", AppClock.today().minusDays(10));
        ServiceAdminController c = mock(ServiceAdminController.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(c, "serviceClientService", svc);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.getSession(true).setAttribute("serviceAdminUsername", "ops");

        ServiceClientBO bo = form(rows.get(1));
        bo.setStartDate(AppClock.today().toString());
        ResponseEntity<Map<String, Object>> r = c.updateClient(1, bo, req);
        assertThat(r.getStatusCode().value()).isEqualTo(409);
        assertThat(r.getBody()).containsEntry("code", "START_DATE_CONFIRMATION_REQUIRED");

        ServiceClientBO trial = form(rows.get(3));
        trial.setSubscriptionType("PRO");
        ResponseEntity<Map<String, Object>> t = c.updateClient(3, trial, req);
        assertThat(t.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(t.getBody().get("message"))).contains("use the Convert action");
    }

    @Test @DisplayName("a missing start date on Register Client is today's America/Chicago date")
    void missingStartIsChicagoToday() {
        ServiceClientBO bo = new ServiceClientBO();
        bo.setName("New"); bo.setChurchName("New Church"); bo.setEmail("n@c.org");
        bo.setActivePeriod(1); bo.setActivePeriodUnit("MONTHS"); bo.setSubscriptionType("FREE");
        ServiceClient saved = svc.save(bo, "ops");
        assertThat(saved.getStartDate()).isEqualTo(AppClock.today());
    }

    // ── 3. Trial → paid only via Convert ───────────────────────────────────

    @Test @DisplayName("Trial (CHR) → Standard/Pro through Edit is refused; nothing saved")
    void trialToPaidViaEditRefused() {
        ServiceClient c = client(3, "CHR-3", "TRIAL", AppClock.today().minusDays(10));
        for (String target : List.of("STANDARD", "PRO", "FULL", "FREE")) {
            ServiceClientBO bo = form(c);
            bo.setSubscriptionType(target);
            assertThatThrownBy(() -> svc.update(3, bo, "ops"))
                    .as(target).hasMessage(ServiceClientService.TRIAL_EDIT_REFUSED);
        }
        verify(repo, never()).save(any());
        assertThat(c.getSubscriptionType()).isEqualTo("TRIAL");
    }

    @Test @DisplayName("a Trial client can still be edited on the Trial plan (contact details, period)")
    void trialEditOnTrialAllowed() {
        ServiceClient c = client(3, "CHR-3", "TRIAL", AppClock.today().minusDays(10));
        ServiceClientBO bo = form(c);
        bo.setPhone("555-0100");
        bo.setActivePeriod(2);
        ServiceClient saved = svc.update(3, bo, "ops");
        assertThat(saved.getPhone()).isEqualTo("555-0100");
        assertThat(saved.getSubscriptionType()).isEqualTo("TRIAL");
    }

    @Test @DisplayName("Trial (CHR) → paid through Convert still works: new plan, new start date, same account")
    void trialToPaidViaConvert() {
        client(3, "CHR-3", "TRIAL", AppClock.today().minusDays(10));
        ServiceClientService.ConvertResult r = svc.convert(3, new ServiceClientService.ConvertCommand(
                "STANDARD", "MONTHLY", null, AppClock.today().toString(),
                AppClock.today().plusMonths(1).toString(), "PAID", false, null), "ops");
        assertThat(r.client().getClientId()).isEqualTo("CHR-3");
        assertThat(r.client().getSubscriptionType()).isEqualTo("STANDARD");
        assertThat(r.client().getStartDate()).isEqualTo(AppClock.today());   // → fresh usage period
        assertThat(history).anyMatch(h -> "CONVERSION".equals(h.getReason()));
    }

    @Test @DisplayName("paid-plan upgrades and downgrades through Edit are unchanged (start date and usage period kept)")
    void paidPlanEditsUnchanged() {
        LocalDate start = AppClock.today().minusDays(40);
        ServiceClient c = client(1, "CHR-1", "STANDARD", start);
        ServiceClientBO up = form(c);
        up.setSubscriptionType("PRO");
        ServiceClient s1 = svc.update(1, up, "ops");
        assertThat(s1.getSubscriptionType()).isEqualTo("PRO");
        assertThat(s1.getSubscriptionPrice()).isEqualByComparingTo("34.99");
        ServiceClientBO down = form(s1);
        down.setSubscriptionType("FREE");
        ServiceClient s2 = svc.update(1, down, "ops");
        assertThat(s2.getSubscriptionType()).isEqualTo("FREE");
        assertThat(s2.getStartDate()).isEqualTo(start);
        assertThat(SubscriptionService.periodStartFor(s2.getStartDate(), AppClock.today()))
                .isEqualTo(SubscriptionService.periodStartFor(start, AppClock.today()));   // same usage period
        // paid → Trial through Edit stays possible, as before
        ServiceClientBO toTrial = form(s2);
        toTrial.setSubscriptionType("TRIAL");
        assertThat(svc.update(1, toTrial, "ops").getSubscriptionType()).isEqualTo("TRIAL");
    }
}
