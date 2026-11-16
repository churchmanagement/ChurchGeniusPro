package com.churchgeniuspro.promotion;

import com.churchgeniuspro.controller.ClientPromotionAdminController;
import com.churchgeniuspro.controller.ServiceAdminController;
import com.churchgeniuspro.hibernate.ClientPromotion;
import com.churchgeniuspro.hibernate.Promotion;
import com.churchgeniuspro.hibernate.PromotionHistory;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.model.ServiceClientBO;
import com.churchgeniuspro.repository.ClientPromotionRepository;
import com.churchgeniuspro.repository.PromotionHistoryRepository;
import com.churchgeniuspro.repository.PromotionRepository;
import com.churchgeniuspro.repository.ServiceAdminRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import com.churchgeniuspro.service.BackupService;
import com.churchgeniuspro.service.ChurchVoiceSettingService;
import com.churchgeniuspro.service.ClientPromotionService;
import com.churchgeniuspro.service.ClientPromotionService.AssignForm;
import com.churchgeniuspro.service.ClientPromotionService.ConversionChoice;
import com.churchgeniuspro.service.ClientPromotionService.ConversionPlan;
import com.churchgeniuspro.service.ClientPromotionService.Terms;
import com.churchgeniuspro.service.OpenAiUsageService;
import com.churchgeniuspro.service.PromotionService;
import com.churchgeniuspro.service.ServiceClientService;
import com.churchgeniuspro.util.AppClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Promotions phase 2 — giving customers a promotional deal or a manual discount:
 * Scheduled during the trial, applied (or not) at conversion, next-billing-date start
 * for paying clients, monthly / yearly separation, adjusted terms never longer than
 * the deal, negotiated prices preserved, one live discount per client, plan / frequency
 * change decisions, the audit trail, and the price rule — while nothing in billing
 * reads any of it yet.
 */
@DisplayName("Promotions phase 2 — discounts given to customers")
class ClientPromotionPhase2Test {

    private final Map<Long, ClientPromotion> rows = new LinkedHashMap<>();
    private final Map<Long, Promotion> dealRows = new LinkedHashMap<>();
    private final List<PromotionHistory> historyRows = new ArrayList<>();
    private final Map<Integer, ServiceClient> clientRows = new LinkedHashMap<>();
    private final List<SubscriptionPlan> planRows = new ArrayList<>();
    private ClientPromotionService service;
    private PromotionService dealService;
    private long nextId = 1, nextDealId = 1;
    private final LocalDate today = AppClock.today();

    private static final int TRIAL = 1, PRO_MONTHLY = 2, STD_YEARLY = 3, NEGOTIATED = 4, FREE = 5, SAMPLE = 6, DEMO = 7, DELETED = 8;

    @BeforeEach
    void setUp() {
        ClientPromotionRepository assignments = mock(ClientPromotionRepository.class);
        when(assignments.save(any(ClientPromotion.class))).thenAnswer(inv -> {
            ClientPromotion a = inv.getArgument(0);
            if (a.getId() == null) a.setId(nextId++);
            rows.put(a.getId(), a);
            return a;
        });
        when(assignments.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<Long>getArgument(0))));
        when(assignments.findByClientIdOrderByIdDesc(anyString())).thenAnswer(inv -> rows.values().stream()
                .filter(a -> a.getClientId().equals(inv.getArgument(0)))
                .sorted(Comparator.comparing(ClientPromotion::getId).reversed()).toList());
        when(assignments.findByClientIdAndStatusIn(anyString(), any())).thenAnswer(inv -> rows.values().stream()
                .filter(a -> a.getClientId().equals(inv.getArgument(0))
                        && inv.<Collection<String>>getArgument(1).contains(a.getStatus())).toList());
        when(assignments.findByStatusIn(any())).thenAnswer(inv -> rows.values().stream()
                .filter(a -> inv.<Collection<String>>getArgument(0).contains(a.getStatus())).toList());
        when(assignments.findByStatusAndStartDateLessThanEqual(anyString(), any())).thenAnswer(inv -> rows.values().stream()
                .filter(a -> a.getStatus().equals(inv.getArgument(0)) && a.getStartDate() != null
                        && !a.getStartDate().isAfter(inv.getArgument(1))).toList());
        when(assignments.existsByPromotionId(anyLong())).thenAnswer(inv -> rows.values().stream()
                .anyMatch(a -> inv.getArgument(0).equals(a.getPromotionId())));
        when(assignments.existsByPromotionCode(anyString())).thenAnswer(inv -> rows.values().stream()
                .anyMatch(a -> inv.getArgument(0).equals(a.getPromotionCode())));

        PromotionRepository deals = mock(PromotionRepository.class);
        when(deals.save(any(Promotion.class))).thenAnswer(inv -> {
            Promotion p = inv.getArgument(0);
            if (p.getId() == null) p.setId(nextDealId++);
            p.setVersion(p.getVersion() == null ? 0L : p.getVersion() + 1);
            dealRows.put(p.getId(), p);
            return p;
        });
        when(deals.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(dealRows.get(inv.<Long>getArgument(0))));
        when(deals.findByCode(anyString())).thenAnswer(inv -> dealRows.values().stream()
                .filter(p -> p.getCode().equals(inv.getArgument(0))).toList());
        when(deals.findByCodeAndBillingFrequency(anyString(), anyString())).thenAnswer(inv -> dealRows.values().stream()
                .filter(p -> p.getCode().equals(inv.getArgument(0)) && p.getBillingFrequency().equals(inv.getArgument(1))).findFirst());
        when(deals.findAllByOrderByActiveDescCodeAscBillingFrequencyAsc()).thenAnswer(inv -> new ArrayList<>(dealRows.values()));

        PromotionHistoryRepository history = mock(PromotionHistoryRepository.class);
        when(history.save(any(PromotionHistory.class))).thenAnswer(inv -> {
            PromotionHistory h = inv.getArgument(0);
            h.setId((long) historyRows.size() + 1);
            historyRows.add(h);
            return h;
        });
        when(history.findByClientIdOrderByOccurredAtDescIdDesc(anyString())).thenAnswer(inv -> {
            List<PromotionHistory> out = new ArrayList<>(historyRows.stream()
                    .filter(h -> inv.getArgument(0).equals(h.getClientId())).toList());
            java.util.Collections.reverse(out);
            return out;
        });

        ServiceClientRepository clients = mock(ServiceClientRepository.class);
        when(clients.findById(anyInt())).thenAnswer(inv -> Optional.ofNullable(clientRows.get(inv.<Integer>getArgument(0))));

        SubscriptionPlanRepository plans = mock(SubscriptionPlanRepository.class);
        when(plans.findAllByOrderBySortOrderAscIdAsc()).thenAnswer(inv -> new ArrayList<>(planRows));
        when(plans.findByPlanCodeIgnoreCase(anyString())).thenAnswer(inv -> planRows.stream()
                .filter(p -> p.getPlanCode().equalsIgnoreCase(inv.getArgument(0))).findFirst());

        planRows.add(plan("FREE", "Free", "0", null));
        planRows.add(plan("STANDARD", "Standard", "14.99", "149.00"));
        planRows.add(plan("PRO", "Pro", "40.00", null));
        planRows.add(plan("TRIAL", "Trial", "0", null));

        client(TRIAL, "CHR-trial", "TRIAL", null, null, false, today.plusDays(20));
        client(PRO_MONTHLY, "CHR-pro", "PRO", "MONTHLY", "40.00", false, today.plusDays(10));
        client(STD_YEARLY, "CHR-std", "STANDARD", "YEARLY", "149.00", false, today.plusDays(100));
        client(NEGOTIATED, "CHR-neg", "PRO", "MONTHLY", "30.00", true, today.plusDays(5));
        client(FREE, "CHR-free", "FREE", "MONTHLY", "0", false, null);
        client(SAMPLE, "TRIAL-sample", "TRIAL", null, null, false, today.plusDays(20));
        client(DEMO, "DEMO-1", "PRO", "MONTHLY", "40.00", false, today.plusDays(20));
        client(DELETED, "CHR-gone", "PRO", "MONTHLY", "40.00", false, today.plusDays(20)).setDeleteFlag(true);

        dealService = new PromotionService(deals, history, plans);
        dealService.setAssignments(assignments);
        service = new ClientPromotionService(assignments, deals, history, clients, plans);

        deal("THANKS2026", "Thanksgiving 2026", "MONTHLY", "FIXED_PRICE", "25", 2, "YEARS", List.of("PRO"));
        deal("THANKS2026", "Thanksgiving 2026", "YEARLY", "PERCENT", "20", 1, "YEARS", List.of("STANDARD"));
        deal("BOTH10", "Ten percent", "MONTHLY", "PERCENT", "10", 1, "YEARS", List.of("STANDARD", "PRO"));
        historyRows.clear();
    }

    // ── trial → conversion ─────────────────────────────────────────────────

    @Test
    @DisplayName("Trial: the deal waits as Scheduled with no dates, and becomes Active only when applied at Convert")
    void trialDealAppliedAtConversion() {
        Map<String, Object> d = service.assign(TRIAL, new AssignForm("DEAL", "thanks2026", null, "Met at conference", false), "anson");
        assertEquals("SCHEDULED", d.get("status"));
        assertNull(d.get("startDate"));
        assertNull(d.get("endDate"));
        assertEquals("THANKS2026", d.get("promotionCode"));
        assertEquals(PromotionHistory.ASSIGNED, historyRows.get(0).getEvent());
        assertEquals("anson", historyRows.get(0).getActor());
        assertEquals("CHR-trial", historyRows.get(0).getClientId());
        assertEquals("Met at conference", historyRows.get(0).getNotes());

        // No discount during the trial, and nothing activates by itself.
        assertFalse(service.quote(clientRows.get(TRIAL), today).discounted());
        assertEquals(0, service.activateDue(today.plusYears(1)));
        assertEquals("SCHEDULED", rows.get(1L).getStatus());

        // Convert is refused until the Service Admin chooses.
        for (ConversionChoice none : new ConversionChoice[] {null, new ConversionChoice(null, null, null, null), new ConversionChoice("MAYBE", null, null, null)}) {
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> service.prepareConversion(TRIAL, none, "PRO", "MONTHLY", null, today.toString()))
                    .getMessage().contains("Choose Apply or Do not apply"));
        }

        LocalDate start = today.plusDays(3);
        ConversionPlan plan = service.prepareConversion(TRIAL, new ConversionChoice("APPLY", null, null, null),
                "PRO", "MONTHLY", null, start.toString());
        assertTrue(plan.apply());
        assertEquals(start, plan.start());
        assertEquals(start.plusYears(2).minusDays(1), plan.end());
        assertEquals("SCHEDULED", rows.get(1L).getStatus(), "prepare writes nothing");

        ServiceClient converted = clientRows.get(TRIAL);
        converted.setSubscriptionType("PRO");
        converted.setBillingFrequency("MONTHLY");
        converted.setSubscriptionPrice(new BigDecimal("40.00"));
        Map<String, Object> applied = service.completeConversion(plan, converted, "maria");
        assertEquals("ACTIVE", applied.get("status"));
        assertEquals(start.toString(), applied.get("startDate"));
        assertEquals(start.plusYears(2).minusDays(1).toString(), applied.get("endDate"));
        assertEquals("40.00", applied.get("regularPrice"));
        assertEquals("25.00", applied.get("promotionalPrice"));
        assertEquals("maria", applied.get("appliedBy"));
        assertEquals(false, applied.get("manuallyAdjusted"));
        PromotionHistory h = historyRows.get(historyRows.size() - 1);
        assertEquals(PromotionHistory.APPLIED, h.getEvent());
        assertEquals("maria", h.getActor());
        assertTrue(h.getBeforeJson().contains("SCHEDULED") && h.getAfterJson().contains("ACTIVE"));
        assertEquals("Met at conference", h.getNotes(), "assignment notes travel into every history entry");
        assertEquals(new BigDecimal("40.00"), converted.getSubscriptionPrice(), "the regular price is never overwritten");
    }

    @Test
    @DisplayName("Convert uses the deal for the chosen billing frequency; no matching deal means it cannot be applied")
    void conversionPicksTheMatchingFrequency() {
        service.assign(TRIAL, new AssignForm("DEAL", "THANKS2026", null, null, false), "a");
        ConversionChoice apply = new ConversionChoice("APPLY", null, null, null);

        ConversionPlan yearly = service.prepareConversion(TRIAL, apply, "STANDARD", "YEARLY", null, today.toString());
        assertEquals("YEARLY", yearly.frequency());
        assertEquals("PERCENT", yearly.discountType());
        assertEquals(today.plusYears(1).minusDays(1), yearly.end());

        assertTrue(msg(() -> service.prepareConversion(TRIAL, apply, "STANDARD", "MONTHLY", null, null)).contains("does not cover the Standard plan"));
        assertTrue(msg(() -> service.prepareConversion(TRIAL, apply, "PRO", "YEARLY", null, null)).contains("no yearly price"));
        assertTrue(msg(() -> service.prepareConversion(TRIAL, apply, "TRIAL", "MONTHLY", null, null)).contains("active paid plan"));

        // A code that exists only as a monthly deal cannot be applied to yearly billing.
        rows.clear();
        service.assign(TRIAL, new AssignForm("DEAL", "BOTH10", null, null, false), "a");
        assertTrue(msg(() -> service.prepareConversion(TRIAL, apply, "STANDARD", "YEARLY", null, null)).contains("no yearly deal with code BOTH10"));
        // On a negotiated price the discount is worked out from that price.
        ConversionPlan neg = service.prepareConversion(TRIAL, apply, "PRO", "MONTHLY", "30", null);
        assertNotNull(neg);
        assertTrue(msg(() -> service.prepareConversion(TRIAL, new ConversionChoice("APPLY", null,
                new Terms("FIXED_PRICE", "35", null, null, null), "special"), "PRO", "MONTHLY", "30", null)).contains("would not lower"));
    }

    @Test
    @DisplayName("Do not apply at Convert: reason required, discount Cancelled, row and history kept")
    void notAppliedAtConversion() {
        service.assign(TRIAL, new AssignForm("DEAL", "THANKS2026", null, null, false), "anson");
        assertTrue(msg(() -> service.prepareConversion(TRIAL, new ConversionChoice("DO_NOT_APPLY", " ", null, null), "PRO", "MONTHLY", null, null))
                .contains("reason for not applying"));
        ConversionPlan plan = service.prepareConversion(TRIAL, new ConversionChoice("do_not_apply", "Customer declined", null, null),
                "PRO", "MONTHLY", null, null);
        assertFalse(plan.apply());
        Map<String, Object> out = service.completeConversion(plan, clientRows.get(TRIAL), "maria");
        assertEquals("CANCELLED", out.get("status"));
        assertEquals("Customer declined", out.get("cancelReason"));
        assertEquals("maria", out.get("cancelledBy"));
        assertEquals(1, rows.size(), "the row is kept");
        assertEquals(PromotionHistory.NOT_APPLIED, historyRows.get(historyRows.size() - 1).getEvent());

        // A trial with no waiting discount converts exactly as before: nothing to decide.
        assertNull(service.prepareConversion(TRIAL, null, "PRO", "MONTHLY", null, null));
        assertNull(service.completeConversion(null, clientRows.get(TRIAL), "maria"));
        assertNull(service.prepareConversion(PRO_MONTHLY, null, "PRO", "MONTHLY", null, null));
        assertNull(service.prepareConversion(999, null, "PRO", "MONTHLY", null, null));
    }

    @Test
    @DisplayName("Terms adjusted for one customer: shorter or equal to the deal, never longer; a note is required")
    void adjustedTermsNeverLongerThanTheDeal() {
        service.assign(TRIAL, new AssignForm("DEAL", "THANKS2026", null, null, false), "a");
        assertTrue(msg(() -> service.prepareConversion(TRIAL, new ConversionChoice("APPLY", null,
                new Terms(null, null, 3, "YEARS", null), "longer please"), "PRO", "MONTHLY", null, null)).contains("cannot run longer than the deal's duration"));
        assertTrue(msg(() -> service.prepareConversion(TRIAL, new ConversionChoice("APPLY", null,
                new Terms(null, null, 1, "MONTHS", null), null), "PRO", "MONTHLY", null, null)).contains("Enter a note"));
        assertTrue(msg(() -> service.prepareConversion(TRIAL, new ConversionChoice("APPLY", null,
                new Terms(null, null, null, null, today.plusYears(2).toString()), "n"), "PRO", "MONTHLY", null, today.toString())).contains("cannot run longer"));

        // Exactly the deal's length is allowed; one month is allowed.
        assertNotNull(service.prepareConversion(TRIAL, new ConversionChoice("APPLY", null,
                new Terms(null, null, 24, "MONTHS", null), "same length"), "PRO", "MONTHLY", null, null));
        ConversionPlan oneMonth = service.prepareConversion(TRIAL, new ConversionChoice("APPLY", null,
                new Terms("PERCENT", "50", 1, "MONTHS", null), "First month half price"), "PRO", "MONTHLY", null, today.toString());
        ServiceClient c = clientRows.get(TRIAL);
        c.setSubscriptionType("PRO"); c.setBillingFrequency("MONTHLY"); c.setSubscriptionPrice(new BigDecimal("40.00"));
        Map<String, Object> out = service.completeConversion(oneMonth, c, "maria");
        assertEquals(today.plusMonths(1).minusDays(1).toString(), out.get("endDate"));
        assertEquals(true, out.get("manuallyAdjusted"));
        assertEquals("20.00", out.get("promotionalPrice"));
        assertEquals("FIXED_PRICE", out.get("dealDiscountType"), "the deal's standard terms are kept beside the granted ones");
        assertEquals("2 years", out.get("dealDuration"));
        assertEquals("First month half price", out.get("notes"));

        // During the trial the deal is stored by code only; adjusting waits for Convert.
        rows.clear();
        c.setSubscriptionType("TRIAL"); c.setBillingFrequency(null); c.setSubscriptionPrice(null);
        assertTrue(msg(() -> service.assign(TRIAL, new AssignForm("DEAL", "THANKS2026",
                new Terms(null, null, 1, "MONTHS", null), "n", false), "a")).contains("when you convert the trial"));
    }

    // ── paying clients ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Paying client: the deal starts at the next billing date and becomes Active on that date")
    void payingClientStartsNextBillingPeriod() {
        Map<String, Object> d = service.assign(PRO_MONTHLY, new AssignForm("DEAL", "THANKS2026", null, null, false), "anson");
        LocalDate start = today.plusDays(10);
        assertEquals("SCHEDULED", d.get("status"));
        assertEquals(start.toString(), d.get("startDate"));
        assertEquals(start.plusYears(2).minusDays(1).toString(), d.get("endDate"));
        assertEquals("PRO", d.get("planCode"));
        assertEquals("25.00", d.get("promotionalPrice"));

        assertEquals(0, service.activateDue(start.minusDays(1)));
        assertEquals(1, service.activateDue(start));
        assertEquals(0, service.activateDue(start), "idempotent");
        assertEquals("ACTIVE", rows.get(1L).getStatus());
        PromotionHistory h = historyRows.get(historyRows.size() - 1);
        assertEquals(PromotionHistory.ACTIVATED, h.getEvent());
        assertEquals(ClientPromotionService.SYSTEM, h.getActor());

        // A client whose billing date is today (or lapsed) starts today and is Active at once.
        clientRows.get(NEGOTIATED).setEndDate(today.minusDays(2));
        Map<String, Object> now = service.assign(NEGOTIATED, new AssignForm("DEAL", "BOTH10", null, null, false), "a");
        assertEquals(today.toString(), now.get("startDate"));
        assertEquals("ACTIVE", now.get("status"));
    }

    @Test
    @DisplayName("Monthly deals only for monthly billing, yearly only for yearly; the deal must cover the plan; one discount at a time")
    void frequencyPlanAndOneAtATime() {
        assertTrue(msg(() -> service.assign(STD_YEARLY, new AssignForm("DEAL", "BOTH10", null, null, false), "a"))
                .contains("no yearly deal with code BOTH10"));
        clientRows.get(STD_YEARLY).setBillingFrequency("MONTHLY");
        clientRows.get(STD_YEARLY).setSubscriptionPrice(new BigDecimal("14.99"));
        assertTrue(msg(() -> service.assign(STD_YEARLY, new AssignForm("DEAL", "THANKS2026", null, null, false), "a"))
                .contains("does not cover the Standard plan"));
        clientRows.get(STD_YEARLY).setBillingFrequency("YEARLY");
        clientRows.get(STD_YEARLY).setSubscriptionPrice(new BigDecimal("149.00"));
        assertEquals("YEARLY", service.assign(STD_YEARLY, new AssignForm("DEAL", "THANKS2026", null, null, false), "a").get("billingFrequency"));

        assertTrue(msg(() -> service.assign(PRO_MONTHLY, new AssignForm("DEAL", "NOPE", null, null, false), "a")).contains("no monthly deal with code NOPE"));
        assertTrue(msg(() -> service.assign(PRO_MONTHLY, new AssignForm("DEAL", " ", null, null, false), "a")).contains("Choose the promotional deal"));
        assertTrue(msg(() -> service.assign(PRO_MONTHLY, new AssignForm("OTHER", null, null, null, false), "a")).contains("Choose a promotional deal or a manual discount"));

        // Inactive, or outside its assignable dates: cannot be newly given.
        dealRows.get(3L).setActive(false);
        assertTrue(msg(() -> service.assign(PRO_MONTHLY, new AssignForm("DEAL", "BOTH10", null, null, false), "a")).contains("not active or cannot be assigned today"));
        dealRows.get(3L).setActive(true);
        dealRows.get(3L).setAssignUntil(today.minusDays(1));
        assertTrue(msg(() -> service.assign(PRO_MONTHLY, new AssignForm("DEAL", "BOTH10", null, null, false), "a")).contains("cannot be assigned today"));
        dealRows.get(3L).setAssignUntil(null);

        service.assign(PRO_MONTHLY, new AssignForm("DEAL", "THANKS2026", null, null, false), "a");
        assertTrue(msg(() -> service.assign(PRO_MONTHLY, new AssignForm("DEAL", "BOTH10", null, null, false), "a")).contains("already has a discount"));
        assertTrue(msg(() -> service.assign(PRO_MONTHLY, new AssignForm("MANUAL", null, new Terms("PERCENT", "5", 1, "MONTHS", null), "x", false), "a"))
                .contains("already has a discount"));

        // Adjusted for a paying client: never longer than the deal, note required.
        assertTrue(msg(() -> service.assign(NEGOTIATED, new AssignForm("DEAL", "BOTH10", new Terms(null, null, 2, "YEARS", null), "n", false), "a"))
                .contains("cannot run longer than the deal's duration (1 year)"));
        assertTrue(msg(() -> service.assign(NEGOTIATED, new AssignForm("DEAL", "BOTH10", new Terms(null, null, 3, "MONTHS", null), null, false), "a"))
                .contains("Enter a note"));
        Map<String, Object> adj = service.assign(NEGOTIATED, new AssignForm("DEAL", "BOTH10", new Terms(null, null, 3, "MONTHS", null), "Three months only", false), "a");
        assertEquals(true, adj.get("manuallyAdjusted"));
        assertEquals(today.plusDays(5).plusMonths(3).minusDays(1).toString(), adj.get("endDate"));
    }

    @Test
    @DisplayName("Not eligible: Free plan, sample-data trials, demo accounts, deleted clients, and discounts that do not lower the price")
    void notEligible() {
        AssignForm f = new AssignForm("DEAL", "THANKS2026", null, null, false);
        assertTrue(msg(() -> service.assign(FREE, f, "a")).contains("Free subscriptions are not eligible"));
        assertTrue(msg(() -> service.assign(SAMPLE, f, "a")).contains("Sample-data trials and demo accounts"));
        assertTrue(msg(() -> service.assign(DEMO, f, "a")).contains("Sample-data trials and demo accounts"));
        assertTrue(msg(() -> service.assign(DELETED, f, "a")).contains("deleted"));
        assertTrue(msg(() -> service.assign(999, f, "a")).contains("Client not found"));
        assertTrue(msg(() -> service.assign(PRO_MONTHLY, new AssignForm("MANUAL", null, new Terms("AMOUNT_OFF", "40", 1, "MONTHS", null), "r", false), "a"))
                .contains("is not less than the client's regular price"));
        assertTrue(msg(() -> service.assign(NEGOTIATED, new AssignForm("MANUAL", null, new Terms("FIXED_PRICE", "35", 1, "MONTHS", null), "r", false), "a"))
                .contains("would not lower the client's regular price of $30.00"));
        assertTrue(rows.isEmpty());
        assertEquals(false, ((Map<?, ?>) service.view(FREE).get("client")).get("eligible"));
        assertEquals(List.of(), service.view(FREE).get("deals"));
    }

    // ── manual discounts ───────────────────────────────────────────────────

    @Test
    @DisplayName("Manual Discount – No Promotion Code: reason required, three types, duration or expiration date, 5-year cap, negotiated price preserved")
    void manualDiscount() {
        assertTrue(msg(() -> service.assign(NEGOTIATED, new AssignForm("MANUAL", null, new Terms("PERCENT", "10", 6, "MONTHS", null), "  ", false), "a"))
                .contains("reason for this manual discount"));
        assertTrue(msg(() -> service.assign(NEGOTIATED, new AssignForm("MANUAL", null, null, "r", false), "a")).contains("Enter the discount"));
        assertTrue(msg(() -> service.assign(NEGOTIATED, new AssignForm("MANUAL", null, new Terms("PERCENT", "10", 61, "MONTHS", null), "r", false), "a"))
                .contains("1 to 60 months or 1 to 5 years"));
        assertTrue(msg(() -> service.assign(NEGOTIATED, new AssignForm("MANUAL", null, new Terms("PERCENT", "10", 6, "YEARS", null), "r", false), "a"))
                .contains("1 to 60 months or 1 to 5 years"));
        LocalDate start = today.plusDays(5);
        assertTrue(msg(() -> service.assign(NEGOTIATED, new AssignForm("MANUAL", null,
                new Terms("PERCENT", "10", null, null, start.plusYears(5).toString()), "r", false), "a")).contains("cannot run longer than 5 years"));
        assertTrue(msg(() -> service.assign(NEGOTIATED, new AssignForm("MANUAL", null,
                new Terms("PERCENT", "10", null, null, start.minusDays(1).toString()), "r", false), "a")).contains("cannot be before the discount starts"));
        assertTrue(msg(() -> service.assign(NEGOTIATED, new AssignForm("MANUAL", null, new Terms("BOGO", "10", 1, "MONTHS", null), "r", false), "a"))
                .contains("discount type"));

        Map<String, Object> d = service.assign(NEGOTIATED, new AssignForm("MANUAL", null,
                new Terms("PERCENT", "10", null, null, start.plusDays(44).toString()), "Hardship, approved by Anson", false), "anson");
        assertEquals("MANUAL", d.get("kind"));
        assertEquals(ClientPromotion.MANUAL_LABEL, d.get("label"));
        assertNull(d.get("promotionCode"));
        assertEquals(start.toString(), d.get("startDate"));
        assertEquals(start.plusDays(44).toString(), d.get("endDate"));
        assertEquals("30.00", d.get("regularPrice"));
        assertEquals("27.00", d.get("promotionalPrice"));
        assertEquals("anson", d.get("assignedBy"));
        assertNotNull(d.get("assignedAt"));
        assertEquals(true, d.get("manuallyAdjusted"));
        assertEquals(new BigDecimal("30.00"), clientRows.get(NEGOTIATED).getSubscriptionPrice(), "negotiated price untouched");
        assertEquals(Boolean.TRUE, clientRows.get(NEGOTIATED).getPriceOverridden());

        // All three types; a trial takes a duration (dates come at Convert) and keeps the reason.
        service.assign(PRO_MONTHLY, new AssignForm("MANUAL", null, new Terms("AMOUNT_OFF", "5.50", 2, "YEARS", null), "r", false), "a");
        assertTrue(msg(() -> service.assign(TRIAL, new AssignForm("MANUAL", null, new Terms("FIXED_PRICE", "20", null, null, today.plusMonths(2).toString()), "r", false), "a"))
                .contains("For a trial, enter a duration"));
        Map<String, Object> t = service.assign(TRIAL, new AssignForm("MANUAL", null, new Terms("FIXED_PRICE", "20", 3, "MONTHS", null), "Pilot church", false), "a");
        assertEquals("SCHEDULED", t.get("status"));
        assertNull(t.get("startDate"));
        ConversionPlan plan = service.prepareConversion(TRIAL, new ConversionChoice("APPLY", null, null, null), "PRO", "YEARLY", "400", today.toString());
        assertEquals(today.plusMonths(3).minusDays(1), plan.end(), "a manual discount is the one path outside the monthly / yearly rule");
        assertEquals("FIXED_PRICE", plan.discountType());
    }

    // ── change terms, cancel, repeat ───────────────────────────────────────

    @Test
    @DisplayName("Change terms: note required, shortened never extended, before and after recorded")
    void changeTerms() {
        service.assign(PRO_MONTHLY, new AssignForm("DEAL", "THANKS2026", null, null, false), "anson");
        LocalDate start = today.plusDays(10), end = start.plusYears(2).minusDays(1);
        assertTrue(msg(() -> service.modify(PRO_MONTHLY, new Terms("FIXED_PRICE", "25", 1, "YEARS", null), " ", "maria")).contains("Enter a note"));
        assertTrue(msg(() -> service.modify(PRO_MONTHLY, null, "n", "maria")).contains("Enter the new terms"));
        assertTrue(msg(() -> service.modify(PRO_MONTHLY, new Terms("FIXED_PRICE", "25", 3, "YEARS", null), "n", "maria")).contains("cannot run longer"));
        assertTrue(msg(() -> service.modify(PRO_MONTHLY, new Terms("FIXED_PRICE", "45", 1, "YEARS", null), "n", "maria")).contains("would not lower"));
        assertTrue(msg(() -> service.modify(STD_YEARLY, new Terms("PERCENT", "5", 1, "YEARS", null), "n", "maria")).contains("no discount to change"));

        Map<String, Object> out = service.modify(PRO_MONTHLY, new Terms("FIXED_PRICE", "28", 1, "YEARS", null), "Agreed one year at $28", "maria");
        assertEquals(start.plusYears(1).minusDays(1).toString(), out.get("endDate"));
        assertEquals("28.00", out.get("promotionalPrice"));
        assertEquals(true, out.get("manuallyAdjusted"));
        PromotionHistory h = historyRows.get(historyRows.size() - 1);
        assertEquals(PromotionHistory.TERMS_CHANGED, h.getEvent());
        assertEquals("maria", h.getActor());
        assertTrue(h.getBeforeJson().contains(end.toString()) && h.getBeforeJson().contains("\"discountValue\":\"25.00\""));
        assertTrue(h.getAfterJson().contains("\"discountValue\":\"28.00\""));

        // Once shortened it cannot be stretched back out.
        assertTrue(msg(() -> service.modify(PRO_MONTHLY, new Terms("FIXED_PRICE", "28", 2, "YEARS", null), "n", "maria"))
                .contains("can be shortened but not extended"));
    }

    @Test
    @DisplayName("Cancel: reason always required; the row stays; a new discount can follow; the same deal again asks first")
    void cancelAndRepeat() {
        service.assign(PRO_MONTHLY, new AssignForm("DEAL", "THANKS2026", null, null, false), "anson");
        assertTrue(msg(() -> service.cancel(PRO_MONTHLY, "  ", "maria")).contains("Enter the reason"));
        assertTrue(msg(() -> service.cancel(PRO_MONTHLY, null, "maria")).contains("Enter the reason"));
        Map<String, Object> c = service.cancel(PRO_MONTHLY, "Entered by mistake", "maria");
        assertEquals("CANCELLED", c.get("status"));
        assertEquals("maria", c.get("cancelledBy"));
        assertNotNull(c.get("cancelledAt"));
        assertEquals(PromotionHistory.CANCELLED, historyRows.get(historyRows.size() - 1).getEvent());
        assertEquals("Entered by mistake", historyRows.get(historyRows.size() - 1).getNotes());
        assertTrue(msg(() -> service.cancel(PRO_MONTHLY, "again", "maria")).contains("no discount to cancel"));
        assertFalse(service.quote(clientRows.get(PRO_MONTHLY), today.plusDays(10)).discounted());

        ClientPromotionService.RepeatConfirmationRequired r = assertThrows(ClientPromotionService.RepeatConfirmationRequired.class,
                () -> service.assign(PRO_MONTHLY, new AssignForm("DEAL", "THANKS2026", null, null, false), "anson"));
        assertTrue(r.getMessage().contains("already had the THANKS2026 deal"));
        assertEquals(1, rows.size());
        service.assign(PRO_MONTHLY, new AssignForm("DEAL", "THANKS2026", null, null, true), "anson");
        assertEquals(2, rows.size(), "the cancelled row is kept beside the new one");
        service.cancel(PRO_MONTHLY, "switching", "anson");
        service.assign(PRO_MONTHLY, new AssignForm("DEAL", "BOTH10", null, null, false), "anson");   // a different deal: no question

        Map<String, Object> view = service.view(PRO_MONTHLY);
        assertEquals("BOTH10", ((Map<?, ?>) view.get("current")).get("promotionCode"));
        assertEquals(2, ((List<?>) view.get("past")).size());
        assertTrue(((List<?>) view.get("history")).size() >= 5);
        assertEquals("BOTH10", service.summary().get("CHR-pro").get("label"));

        // Deleting the client cancels its live discount; never throws.
        service.cancelForDeletedClient(PRO_MONTHLY, "anson");
        assertEquals("Client deleted", rows.get(3L).getCancelReason());
        service.cancelForDeletedClient(PRO_MONTHLY, "anson");
        service.cancelForDeletedClient(999, "anson");
        assertTrue(service.summary().isEmpty());
    }

    // ── Edit Client: plan / frequency change ───────────────────────────────

    @Test
    @DisplayName("Plan or frequency change: a decision is required; keep only when eligible, otherwise cancel with a reason; the end date never moves")
    void planOrFrequencyChange() {
        service.assign(PRO_MONTHLY, new AssignForm("DEAL", "BOTH10", null, null, false), "anson");
        String end = today.plusDays(10).plusYears(1).minusDays(1).toString();

        // Nothing relevant changing: no question (blank fields mean unchanged).
        service.checkEdit(PRO_MONTHLY, bo(null, null, null, null));
        service.checkEdit(PRO_MONTHLY, bo("PRO", "MONTHLY", null, null));
        service.checkEdit(STD_YEARLY, bo("PRO", "MONTHLY", null, null));          // no discount: never asks
        service.checkEdit(999, bo("PRO", "MONTHLY", null, null));

        // Pro → Standard: BOTH10 covers Standard, so it may continue.
        ClientPromotionService.DecisionRequired ask = assertThrows(ClientPromotionService.DecisionRequired.class,
                () -> service.checkEdit(PRO_MONTHLY, bo("STANDARD", "MONTHLY", null, null)));
        assertTrue(ask.eligible());
        assertTrue(ask.getMessage().contains("changing plan") && ask.getMessage().contains("Keep it, or cancel it"));
        service.checkEdit(PRO_MONTHLY, bo("STANDARD", "MONTHLY", "KEEP", null));
        ServiceClient c = clientRows.get(PRO_MONTHLY);
        c.setSubscriptionType("STANDARD"); c.setSubscriptionPrice(new BigDecimal("14.99"));
        service.afterEdit(PRO_MONTHLY, bo("STANDARD", "MONTHLY", "KEEP", null), c, "maria");
        ClientPromotion a = rows.get(1L);
        assertEquals("STANDARD", a.getPlanCode());
        assertEquals(new BigDecimal("13.49"), a.getPromotionalPrice());
        assertEquals(end, a.getEndDate().toString(), "the end date never moves");
        assertEquals(PromotionHistory.KEPT_AFTER_CHANGE, historyRows.get(historyRows.size() - 1).getEvent());
        assertEquals("maria", historyRows.get(historyRows.size() - 1).getActor());

        // Monthly → yearly: a monthly deal can never continue on yearly billing.
        ClientPromotionService.DecisionRequired must = assertThrows(ClientPromotionService.DecisionRequired.class,
                () -> service.checkEdit(PRO_MONTHLY, bo("STANDARD", "YEARLY", null, null)));
        assertFalse(must.eligible());
        assertTrue(must.getMessage().contains("cannot continue on yearly billing") && must.getMessage().contains("must be cancelled"));
        assertTrue(msg(() -> service.checkEdit(PRO_MONTHLY, bo("STANDARD", "YEARLY", "KEEP", null))).contains("Cancel the discount to save this change"));
        assertTrue(msg(() -> service.checkEdit(PRO_MONTHLY, bo("STANDARD", "YEARLY", "CANCEL", " "))).contains("Enter the reason"));
        service.checkEdit(PRO_MONTHLY, bo("STANDARD", "YEARLY", "cancel", "Moving to yearly billing"));
        assertEquals("SCHEDULED", a.getStatus(), "checking writes nothing");
        c.setBillingFrequency("YEARLY"); c.setSubscriptionPrice(new BigDecimal("149.00"));
        service.afterEdit(PRO_MONTHLY, bo("STANDARD", "YEARLY", "CANCEL", "Moving to yearly billing"), c, "maria");
        assertEquals("CANCELLED", a.getStatus());
        assertEquals("Moving to yearly billing", a.getCancelReason());
        assertEquals(end, a.getEndDate().toString());

        // A deal that does not cover the new plan, a plan that is not paid, and a price it no longer lowers.
        service.assign(NEGOTIATED, new AssignForm("DEAL", "THANKS2026", null, null, false), "anson");
        assertFalse(assertThrows(ClientPromotionService.DecisionRequired.class,
                () -> service.checkEdit(NEGOTIATED, bo("STANDARD", null, null, null))).eligible());
        assertFalse(assertThrows(ClientPromotionService.DecisionRequired.class,
                () -> service.checkEdit(NEGOTIATED, bo("FREE", null, null, null))).eligible());
        service.cancel(NEGOTIATED, "x", "a");
        service.assign(NEGOTIATED, new AssignForm("MANUAL", null, new Terms("FIXED_PRICE", "20", 6, "MONTHS", null), "r", false), "a");
        assertFalse(assertThrows(ClientPromotionService.DecisionRequired.class,
                () -> service.checkEdit(NEGOTIATED, bo("STANDARD", null, null, null))).eligible(), "$20 is not below Standard's $14.99");
        assertTrue(assertThrows(ClientPromotionService.DecisionRequired.class,
                () -> service.checkEdit(NEGOTIATED, bo("PRO", "YEARLY", null, null, "360.00"))).eligible(), "a manual discount may cross frequencies when it still lowers the price");
    }

    // ── the price rule ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Price rule: regular before the start, discounted from the start through the end date, regular the day after")
    void priceRule() {
        ServiceClient c = clientRows.get(PRO_MONTHLY);
        ClientPromotionService.Quote none = service.quote(c, today);
        assertFalse(none.discounted());
        assertEquals(new BigDecimal("40.00"), none.price());

        service.assign(PRO_MONTHLY, new AssignForm("DEAL", "THANKS2026", new Terms(null, null, 3, "MONTHS", null), "three months", false), "a");
        LocalDate start = today.plusDays(10), end = start.plusMonths(3).minusDays(1);
        assertEquals(new BigDecimal("40.00"), service.quote(c, start.minusDays(1)).price());
        for (LocalDate d : List.of(start, start.plusMonths(1), end)) {
            ClientPromotionService.Quote q = service.quote(c, d);
            assertTrue(q.discounted(), "discounted on " + d);
            assertEquals(new BigDecimal("25.00"), q.price());
            assertEquals(new BigDecimal("40.00"), q.regularPrice());
            assertEquals("THANKS2026", q.discount().getPromotionCode());
        }
        assertEquals(new BigDecimal("40.00"), service.quote(c, end.plusDays(1)).price(), "regular price the day after it ends");
        assertFalse(service.quote(c, end.plusDays(1)).discounted());

        // Safety nets: a different billing frequency, or a regular price the discount no longer lowers, never discounts.
        c.setBillingFrequency("YEARLY");
        assertFalse(service.quote(c, start).discounted());
        c.setBillingFrequency("MONTHLY");
        c.setSubscriptionPrice(new BigDecimal("20.00"));
        assertEquals(new BigDecimal("20.00"), service.quote(c, start).price(), "a discount never raises a price");
        c.setSubscriptionPrice(new BigDecimal("40.00"));
        assertNull(service.quote(null, start).price());
        assertEquals(new BigDecimal("40.00"), c.getSubscriptionPrice());
    }

    // ── deal lock, access, wiring ──────────────────────────────────────────

    @Test
    @DisplayName("A deal given to a customer has its terms locked; name, notes and dates stay editable")
    void dealTermsLockedOnceGiven() {
        Promotion deal = dealRows.get(3L);                                 // BOTH10, not given to anyone yet
        dealService.update(3L, new PromotionService.Form(null, "Ten percent", "MONTHLY", "PERCENT", "12", 1, "YEARS",
                List.of("STANDARD", "PRO"), null, null, null, deal.getVersion()), "anson");
        assertEquals(new BigDecimal("12.00"), deal.getDiscountValue());

        service.assign(TRIAL, new AssignForm("DEAL", "BOTH10", null, null, false), "anson");   // a trial holds it by code
        assertTrue(dealService.inUse(deal));
        assertTrue(msg(() -> dealService.update(3L, new PromotionService.Form(null, "Ten percent", "MONTHLY", "PERCENT", "15", 1, "YEARS",
                List.of("STANDARD", "PRO"), null, null, null, deal.getVersion()), "anson")).contains("already been given to a customer"));
        dealService.update(3L, new PromotionService.Form(null, "Ten percent (autumn)", "MONTHLY", "PERCENT", "12", 1, "YEARS",
                List.of("STANDARD", "PRO"), null, today.plusDays(30).toString(), "extended", deal.getVersion()), "anson");
        assertEquals("Ten percent (autumn)", deal.getName());
        assertFalse(dealService.inUse(dealRows.get(1L)));
    }

    @Test
    @DisplayName("Endpoints need a Service Admin session; Edit, Convert and Delete are wired to the discount checks")
    void accessAndWiring() throws Exception {
        ClientPromotionAdminController c = new ClientPromotionAdminController(service);
        MockHttpServletRequest none = new MockHttpServletRequest();
        MockHttpServletRequest church = new MockHttpServletRequest();
        church.getSession().setAttribute("role", "SuperAdmin");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "DEAL"); body.put("promotionCode", "THANKS2026");
        for (MockHttpServletRequest r : List.of(none, church)) {
            assertEquals(401, c.view(PRO_MONTHLY, r).getStatusCode().value());
            assertEquals(401, c.assign(PRO_MONTHLY, body, r).getStatusCode().value());
            assertEquals(401, c.modify(PRO_MONTHLY, body, r).getStatusCode().value());
            assertEquals(401, c.cancel(PRO_MONTHLY, Map.of("reason", "x"), r).getStatusCode().value());
            assertEquals(401, c.summary(r).getStatusCode().value());
        }
        assertTrue(rows.isEmpty());

        MockHttpServletRequest admin = new MockHttpServletRequest();
        admin.getSession().setAttribute("role", "ServiceAdmin");
        admin.getSession().setAttribute("serviceAdminUsername", "anson");
        assertEquals(200, c.assign(PRO_MONTHLY, body, admin).getStatusCode().value());
        assertEquals("anson", rows.get(1L).getAssignedBy(), "the actor comes from the session");
        assertEquals(200, c.view(PRO_MONTHLY, admin).getStatusCode().value());
        assertEquals(200, c.summary(admin).getStatusCode().value());
        assertEquals(400, c.cancel(PRO_MONTHLY, Map.of(), admin).getStatusCode().value());
        assertEquals(200, c.cancel(PRO_MONTHLY, Map.of("reason", "test"), admin).getStatusCode().value());
        ResponseEntity<Map<String, Object>> again = c.assign(PRO_MONTHLY, body, admin);
        assertEquals(409, again.getStatusCode().value());
        assertEquals("REPEAT_DEAL_CONFIRMATION_REQUIRED", again.getBody().get("code"));
        body.put("confirmRepeat", true);
        assertEquals(200, c.assign(PRO_MONTHLY, body, admin).getStatusCode().value());
        for (Class<?> k : List.of(ClientPromotionAdminController.class)) {
            for (Method m : k.getDeclaredMethods()) assertNull(m.getAnnotation(DeleteMapping.class), "nothing deletes a discount");
        }
        for (Method m : ClientPromotionRepository.class.getDeclaredMethods()) {
            assertTrue(m.getName().startsWith("find") || m.getName().startsWith("exists"), "discount rows are never deleted");
        }

        // Edit Client → 409 PROMOTION_DECISION_REQUIRED and nothing saved; Convert → refused before converting; Delete → cancels.
        ServiceClientService clientService = mock(ServiceClientService.class);
        ServiceAdminController sac = new ServiceAdminController(mock(ServiceAdminRepository.class), clientService,
                mock(ServiceClientRepository.class), mock(BackupService.class), mock(OpenAiUsageService.class), mock(ChurchVoiceSettingService.class));
        sac.setClientPromotions(service);
        ResponseEntity<Map<String, Object>> edit = sac.updateClient(PRO_MONTHLY, bo("PRO", "YEARLY", null, null), admin);
        assertEquals(409, edit.getStatusCode().value());
        assertEquals("PROMOTION_DECISION_REQUIRED", edit.getBody().get("code"));
        assertEquals(false, edit.getBody().get("eligible"));
        verify(clientService, never()).update(anyInt(), any(), any());

        service.cancel(PRO_MONTHLY, "x", "a");
        service.assign(TRIAL, new AssignForm("DEAL", "THANKS2026", null, null, false), "anson");
        Map<String, Object> convert = new LinkedHashMap<>();
        convert.put("planCode", "PRO"); convert.put("billingFrequency", "MONTHLY"); convert.put("endDate", today.plusMonths(1).toString());
        ResponseEntity<Map<String, Object>> refused = sac.convertClient(TRIAL, convert, admin);
        assertEquals(400, refused.getStatusCode().value());
        assertTrue(String.valueOf(refused.getBody().get("message")).contains("Choose Apply or Do not apply"));
        verify(clientService, never()).convert(anyInt(), any(), any());

        ServiceClient converted = clientRows.get(TRIAL);
        converted.setSubscriptionPrice(new BigDecimal("40.00"));
        when(clientService.convert(anyInt(), any(), any())).thenReturn(new ServiceClientService.ConvertResult(converted, "Pro", false, null));
        convert.put("promotion", Map.of("decision", "APPLY"));
        ResponseEntity<Map<String, Object>> ok = sac.convertClient(TRIAL, convert, admin);
        assertEquals(200, ok.getStatusCode().value());
        assertTrue(String.valueOf(ok.getBody().get("message")).contains("The discount was applied from"));
        assertEquals("ACTIVE", service.summary().get("CHR-trial").get("status"));

        assertEquals(200, sac.deleteClient(TRIAL, admin).getStatusCode().value());
        assertFalse(service.summary().containsKey("CHR-trial"));
    }

    @Test
    @DisplayName("Phase 2 changes no invoice: billing, payment and Stripe code still do not reference promotions")
    void billingStillDoesNotReadPromotions() throws Exception {
        Path main = Paths.get("src/main/java/com/churchgeniuspro");
        // Phase 3 connected invoices (BillingService, the receipt, BillingInvoice, the draft-save endpoint).
        for (String f : List.of("service/BillingReminderScheduler.java", "service/SubscriptionLifecycleService.java",
                "service/SubscriptionService.java", "service/ServiceClientService.java",
                "service/SubscriptionExpiryNotifier.java", "service/PlatformStripeService.java",
                "controller/InvoiceController.java",
                "hibernate/ServiceClient.java", "hibernate/BillingInvoiceLine.java")) {
            assertFalse(Files.readString(main.resolve(f)).contains("Promotion"), f + " must not reference promotions");
        }
        String sql = Files.readString(Paths.get("scripts/sql/migrate_production.sql"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS client_promotion ("));
        assertTrue(sql.contains("ux_client_promotion_one_live"));
        assertTrue(sql.contains("ALTER TABLE promotion_history ADD COLUMN IF NOT EXISTS client_promotion_id"));
        String v24 = Files.readString(Paths.get("src/main/resources/db/migration/V24__client_promotion_one_live.sql"));
        assertTrue(v24.contains("to_regclass('public.client_promotion') IS NULL"), "safe on a database that does not have the table yet");
        assertTrue(v24.contains("WHERE status IN ('SCHEDULED', 'ACTIVE')"));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static String msg(org.junit.jupiter.api.function.Executable e) {
        return assertThrows(IllegalArgumentException.class, e).getMessage();
    }

    private static ServiceClientBO bo(String plan, String freq, String decision, String reason) {
        return bo(plan, freq, decision, reason, null);
    }

    private static ServiceClientBO bo(String plan, String freq, String decision, String reason, String customPrice) {
        ServiceClientBO b = new ServiceClientBO();
        b.setSubscriptionType(plan);
        b.setBillingFrequency(freq);
        b.setPromotionDecision(decision);
        b.setPromotionCancelReason(reason);
        if (customPrice != null) { b.setPriceOverridden(true); b.setSubscriptionPrice(customPrice); }
        return b;
    }

    private void deal(String code, String name, String freq, String type, String value, int amount, String unit, List<String> plans) {
        dealService.create(new PromotionService.Form(code, name, freq, type, value, amount, unit, plans, null, null, null, null), "setup");
    }

    private ServiceClient client(int id, String clientId, String plan, String freq, String price, boolean negotiated, LocalDate end) {
        ServiceClient c = new ServiceClient();
        c.setId(id);
        c.setClientId(clientId);
        c.setChurchName("Church " + id);
        c.setSubscriptionType(plan);
        c.setBillingFrequency(freq);
        c.setSubscriptionPrice(price == null ? null : new BigDecimal(price));
        c.setPriceOverridden(negotiated);
        c.setEndDate(end);
        c.setPaymentStatus("PAID");
        clientRows.put(id, c);
        return c;
    }

    private static SubscriptionPlan plan(String code, String name, String monthly, String yearly) {
        SubscriptionPlan p = new SubscriptionPlan();
        p.setPlanCode(code);
        p.setPlanName(name);
        p.setMonthlyPrice(new BigDecimal(monthly));
        p.setYearlyPrice(yearly == null ? null : new BigDecimal(yearly));
        p.setActive(true);
        return p;
    }
}
