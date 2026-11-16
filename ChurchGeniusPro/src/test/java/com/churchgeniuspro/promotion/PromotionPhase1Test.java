package com.churchgeniuspro.promotion;

import com.churchgeniuspro.controller.PromotionAdminController;
import com.churchgeniuspro.hibernate.Promotion;
import com.churchgeniuspro.hibernate.PromotionHistory;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.repository.PromotionHistoryRepository;
import com.churchgeniuspro.repository.PromotionRepository;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import com.churchgeniuspro.service.PromotionService;
import com.churchgeniuspro.service.PromotionService.Form;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Promotions phase 1 — the deal catalogue in Service Admin. Covers every field rule,
 * the plan checks, uniqueness per code + frequency, the immutable code, stale saves,
 * the append-only history with the acting Service Admin, the access gate, and that
 * nothing in billing or subscriptions reads promotions yet.
 */
@DisplayName("Promotions phase 1 — deal setup")
class PromotionPhase1Test {

    private final Map<Long, Promotion> stored = new LinkedHashMap<>();
    private final List<PromotionHistory> historyRows = new ArrayList<>();
    private final List<SubscriptionPlan> planRows = new ArrayList<>();
    private PromotionService service;
    private long nextId = 1;

    @BeforeEach
    void setUp() {
        PromotionRepository deals = mock(PromotionRepository.class);
        when(deals.save(any(Promotion.class))).thenAnswer(inv -> {
            Promotion p = inv.getArgument(0);
            if (p.getId() == null) p.setId(nextId++);
            p.setVersion(p.getVersion() == null ? 0L : p.getVersion() + 1);
            stored.put(p.getId(), p);
            return p;
        });
        when(deals.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(stored.get(inv.<Long>getArgument(0))));
        when(deals.findByCodeAndBillingFrequency(anyString(), anyString())).thenAnswer(inv ->
                stored.values().stream().filter(p -> p.getCode().equals(inv.getArgument(0))
                        && p.getBillingFrequency().equals(inv.getArgument(1))).findFirst());
        when(deals.findAllByOrderByActiveDescCodeAscBillingFrequencyAsc()).thenAnswer(inv ->
                stored.values().stream().sorted(Comparator.comparing(Promotion::isActive).reversed()
                        .thenComparing(Promotion::getCode).thenComparing(Promotion::getBillingFrequency)).toList());

        PromotionHistoryRepository history = mock(PromotionHistoryRepository.class);
        when(history.save(any(PromotionHistory.class))).thenAnswer(inv -> {
            PromotionHistory h = inv.getArgument(0);
            h.setId((long) historyRows.size() + 1);
            historyRows.add(h);
            return h;
        });
        when(history.findByPromotionIdOrderByOccurredAtDescIdDesc(anyLong())).thenAnswer(inv -> {
            List<PromotionHistory> out = new ArrayList<>(historyRows.stream()
                    .filter(h -> inv.getArgument(0).equals(h.getPromotionId())).toList());
            java.util.Collections.reverse(out);
            return out;
        });

        SubscriptionPlanRepository plans = mock(SubscriptionPlanRepository.class);
        when(plans.findAllByOrderBySortOrderAscIdAsc()).thenAnswer(inv -> new ArrayList<>(planRows));

        planRows.add(plan("FREE", "Free", "0", null, true));
        planRows.add(plan("STANDARD", "Standard", "14.99", "149.00", true));
        planRows.add(plan("PRO", "Pro", "40.00", null, true));          // no yearly price
        planRows.add(plan("TRIAL", "Trial", "0", null, true));
        planRows.add(plan("LEGACY", "Legacy", "20.00", "200.00", false)); // inactive

        service = new PromotionService(deals, history, plans);
    }

    // ── valid deals ────────────────────────────────────────────────────────

    @Test
    @DisplayName("A fixed-price Thanksgiving deal is saved in capitals, active, with a Deal created history row")
    void createFixedPrice() {
        Map<String, Object> d = service.create(form("thanks2026", "Thanksgiving 2026", "MONTHLY", "FIXED_PRICE", "25",
                2, "YEARS", List.of("PRO")), "anson");

        assertEquals("THANKS2026", d.get("code"));
        assertEquals(true, d.get("active"));
        assertEquals("25.00", d.get("discountValue"));
        @SuppressWarnings("unchecked")
        Map<String, Object> row = ((List<Map<String, Object>>) d.get("preview")).get(0);
        assertEquals(new BigDecimal("40.00"), row.get("regularPrice"));
        assertEquals(new BigDecimal("25.00"), row.get("promotionalPrice"));

        assertEquals(1, historyRows.size());
        PromotionHistory h = historyRows.get(0);
        assertEquals(PromotionHistory.DEAL_CREATED, h.getEvent());
        assertEquals("anson", h.getActor());
        assertNotNull(h.getOccurredAt());
        assertNull(h.getBeforeJson());
        assertTrue(h.getAfterJson().contains("THANKS2026"));
        assertNull(h.getClientId(), "deal events concern no church");
    }

    @Test
    @DisplayName("All three discount types work, and the price is right to the cent")
    void discountTypesAndRounding() {
        assertEquals(new BigDecimal("32.00"), PromotionService.promotionalPrice(Promotion.PERCENT, new BigDecimal("20"), new BigDecimal("40.00")));
        assertEquals(new BigDecimal("11.99"), PromotionService.promotionalPrice(Promotion.PERCENT, new BigDecimal("20"), new BigDecimal("14.99")));
        assertEquals(new BigDecimal("9.99"),  PromotionService.promotionalPrice(Promotion.PERCENT, new BigDecimal("33.33"), new BigDecimal("14.99")));
        assertEquals(new BigDecimal("0.00"),  PromotionService.promotionalPrice(Promotion.PERCENT, new BigDecimal("100"), new BigDecimal("40")));
        assertEquals(new BigDecimal("30.00"), PromotionService.promotionalPrice(Promotion.AMOUNT_OFF, new BigDecimal("10"), new BigDecimal("40")));
        assertEquals(new BigDecimal("0.00"),  PromotionService.promotionalPrice(Promotion.AMOUNT_OFF, new BigDecimal("50"), new BigDecimal("40")));
        assertEquals(new BigDecimal("25.00"), PromotionService.promotionalPrice(Promotion.FIXED_PRICE, new BigDecimal("25"), new BigDecimal("40")));

        service.create(form("PCT20", "Twenty percent", "MONTHLY", "PERCENT", "20", 6, "MONTHS", List.of("STANDARD", "PRO")), "a");
        service.create(form("TENOFF", "Ten off", "MONTHLY", "AMOUNT_OFF", "$10.00", 1, "MONTHS", List.of("STANDARD", "PRO")), "a");
        service.create(form("YR-STD", "Yearly Standard", "YEARLY", "PERCENT", "15%", 3, "YEARS", List.of("STANDARD")), "a");
        assertEquals(3, stored.size());
    }

    @Test
    @DisplayName("One code can have a monthly deal and a yearly deal, but not two of the same frequency")
    void codePerFrequency() {
        service.create(form("THANKS2026", "Monthly", "MONTHLY", "PERCENT", "20", 1, "YEARS", List.of("STANDARD")), "a");
        service.create(form("THANKS2026", "Yearly", "YEARLY", "PERCENT", "20", 1, "YEARS", List.of("STANDARD")), "a");
        assertEquals(2, stored.size());

        PromotionService.Conflict c = assertThrows(PromotionService.Conflict.class, () ->
                service.create(form("thanks2026", "Again", "Monthly", "PERCENT", "10", 1, "YEARS", List.of("STANDARD")), "a"));
        assertTrue(c.getMessage().contains("monthly deal with code THANKS2026 already exists"));
        assertEquals(2, stored.size());
    }

    // ── rules ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Field rules: code, name, frequency, type, value, dates")
    void fieldRules() {
        refused("AB", "N", "MONTHLY", "PERCENT", "20", 1, "YEARS", List.of("PRO"), "3 to 30 characters");
        refused("BAD CODE", "N", "MONTHLY", "PERCENT", "20", 1, "YEARS", List.of("PRO"), "3 to 30 characters");
        refused("-LEAD", "N", "MONTHLY", "PERCENT", "20", 1, "YEARS", List.of("PRO"), "3 to 30 characters");
        refused("A".repeat(31), "N", "MONTHLY", "PERCENT", "20", 1, "YEARS", List.of("PRO"), "3 to 30 characters");
        refused("OKCODE", " ", "MONTHLY", "PERCENT", "20", 1, "YEARS", List.of("PRO"), "Enter a name");
        refused("OKCODE", "N".repeat(101), "MONTHLY", "PERCENT", "20", 1, "YEARS", List.of("PRO"), "at most 100");
        refused("OKCODE", "N", null, "PERCENT", "20", 1, "YEARS", List.of("PRO"), "Monthly or Yearly");
        refused("OKCODE", "N", "WEEKLY", "PERCENT", "20", 1, "YEARS", List.of("PRO"), "Monthly or Yearly");
        refused("OKCODE", "N", "MONTHLY", "BOGO", "20", 1, "YEARS", List.of("PRO"), "discount type");
        refused("OKCODE", "N", "MONTHLY", "PERCENT", "", 1, "YEARS", List.of("PRO"), "Enter the discount value");
        refused("OKCODE", "N", "MONTHLY", "PERCENT", "abc", 1, "YEARS", List.of("PRO"), "must be a number");
        refused("OKCODE", "N", "MONTHLY", "PERCENT", "0.5", 1, "YEARS", List.of("PRO"), "between 1 and 100");
        refused("OKCODE", "N", "MONTHLY", "PERCENT", "101", 1, "YEARS", List.of("PRO"), "between 1 and 100");
        refused("OKCODE", "N", "MONTHLY", "PERCENT", "12.345", 1, "YEARS", List.of("PRO"), "two decimal");
        refused("OKCODE", "N", "MONTHLY", "AMOUNT_OFF", "0", 1, "YEARS", List.of("PRO"), "more than $0");
        refused("OKCODE", "N", "MONTHLY", "AMOUNT_OFF", "-5", 1, "YEARS", List.of("PRO"), "more than $0");

        Form badDates = new Form("OKCODE", "N", "MONTHLY", "PERCENT", "20", 1, "YEARS", List.of("PRO"),
                "2026-12-01", "2026-11-01", null, null);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.create(badDates, "a"))
                .getMessage().contains("cannot be before"));
        Form notADate = new Form("OKCODE", "N", "MONTHLY", "PERCENT", "20", 1, "YEARS", List.of("PRO"),
                "next week", null, null, null);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.create(notADate, "a"))
                .getMessage().contains("YYYY-MM-DD"));

        assertTrue(stored.isEmpty(), "nothing is saved while anything is invalid");
        assertTrue(historyRows.isEmpty());
    }

    @Test
    @DisplayName("Duration: monthly deals 1, 3, 6 months or 1–5 years; yearly deals 1–5 years only")
    void durations() {
        for (int m : new int[] {1, 3, 6}) {
            service.create(form("M" + m + "MO", "N", "MONTHLY", "PERCENT", "10", m, "MONTHS", List.of("PRO")), "a");
        }
        for (int y = 1; y <= 5; y++) {
            service.create(form("M" + y + "YR", "N", "MONTHLY", "PERCENT", "10", y, "YEARS", List.of("PRO")), "a");
            service.create(form("Y" + y + "YR", "N", "YEARLY", "PERCENT", "10", y, "YEARS", List.of("STANDARD")), "a");
        }
        assertEquals(13, stored.size());

        refused("BADDUR", "N", "MONTHLY", "PERCENT", "10", 2, "MONTHS", List.of("PRO"), "1, 3 or 6 months");
        refused("BADDUR", "N", "MONTHLY", "PERCENT", "10", 12, "MONTHS", List.of("PRO"), "1, 3 or 6 months");
        refused("BADDUR", "N", "MONTHLY", "PERCENT", "10", 6, "YEARS", List.of("PRO"), "1, 3 or 6 months");
        refused("BADDUR", "N", "MONTHLY", "PERCENT", "10", 0, "YEARS", List.of("PRO"), "1, 3 or 6 months");
        refused("BADDUR", "N", "MONTHLY", "PERCENT", "10", null, "YEARS", List.of("PRO"), "1, 3 or 6 months");
        refused("BADDUR", "N", "MONTHLY", "PERCENT", "10", 1, "WEEKS", List.of("PRO"), "1, 3 or 6 months");
        refused("BADDUR", "N", "YEARLY", "PERCENT", "10", 6, "MONTHS", List.of("STANDARD"), "yearly deal lasts 1 to 5 years");
        refused("BADDUR", "N", "YEARLY", "PERCENT", "10", 6, "YEARS", List.of("STANDARD"), "yearly deal lasts 1 to 5 years");
    }

    @Test
    @DisplayName("Plans: paid and active only; a price for the frequency; the discount must really lower it")
    void planRules() {
        refused("PLANS", "N", "MONTHLY", "PERCENT", "10", 1, "YEARS", List.of(), "at least one plan");
        refused("PLANS", "N", "MONTHLY", "PERCENT", "10", 1, "YEARS", List.of("NOPE"), "does not exist");
        refused("PLANS", "N", "MONTHLY", "PERCENT", "10", 1, "YEARS", List.of("FREE"), "Free and Trial are never eligible");
        refused("PLANS", "N", "MONTHLY", "PERCENT", "10", 1, "YEARS", List.of("TRIAL"), "Free and Trial are never eligible");
        refused("PLANS", "N", "MONTHLY", "PERCENT", "10", 1, "YEARS", List.of("PRO", "LEGACY"), "cannot have a promotional deal");
        refused("PLANS", "N", "YEARLY", "PERCENT", "10", 1, "YEARS", List.of("PRO"), "has no yearly price");
        refused("PLANS", "N", "MONTHLY", "AMOUNT_OFF", "14.99", 1, "YEARS", List.of("STANDARD"), "not less than");
        refused("PLANS", "N", "MONTHLY", "AMOUNT_OFF", "20", 1, "YEARS", List.of("STANDARD", "PRO"), "not less than");
        refused("PLANS", "N", "MONTHLY", "FIXED_PRICE", "40", 1, "YEARS", List.of("PRO"), "not lower than");
        refused("PLANS", "N", "MONTHLY", "FIXED_PRICE", "10", 1, "YEARS", List.of("STANDARD", "PRO"), "exactly one plan");
        assertTrue(stored.isEmpty());

        List<Map<String, Object>> eligible = service.eligiblePlans();
        assertEquals(List.of("STANDARD", "PRO"), eligible.stream().map(m -> m.get("planCode")).toList());
    }

    // ── edit, stale saves, activate ────────────────────────────────────────

    @Test
    @DisplayName("Edit: the code never changes; a stale save is refused; history keeps before and after")
    void edit() {
        Map<String, Object> d = service.create(form("THANKS2026", "Thanksgiving", "MONTHLY", "PERCENT", "20",
                1, "YEARS", List.of("PRO")), "anson");
        Long id = (Long) d.get("id");
        Long v = (Long) d.get("version");

        Form rename = new Form("OTHER", "Thanksgiving", "MONTHLY", "PERCENT", "20", 1, "YEARS", List.of("PRO"), null, null, null, v);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.update(id, rename, "maria"))
                .getMessage().contains("code cannot be changed"));

        Form stale = new Form(null, "Thanksgiving", "MONTHLY", "PERCENT", "25", 1, "YEARS", List.of("PRO"), null, null, null, v + 7);
        assertTrue(assertThrows(PromotionService.Conflict.class, () -> service.update(id, stale, "maria"))
                .getMessage().contains("changed by someone else"));

        // Saving with nothing changed writes nothing.
        Form same = new Form("thanks2026", "Thanksgiving", "MONTHLY", "PERCENT", "20.00", 1, "YEARS", List.of("PRO"), null, null, null, v);
        service.update(id, same, "maria");
        assertEquals(1, historyRows.size());

        Form change = new Form(null, "Thanksgiving 2026", "MONTHLY", "PERCENT", "25", 2, "YEARS", List.of("PRO"),
                "2026-11-20", "2026-12-05", "Black Friday week", v);
        Map<String, Object> after = service.update(id, change, "maria");
        assertEquals("THANKS2026", after.get("code"));
        assertEquals("25.00", after.get("discountValue"));
        assertEquals("maria", after.get("updatedBy"));
        assertEquals("anson", after.get("createdBy"));

        assertEquals(2, historyRows.size());
        PromotionHistory h = historyRows.get(1);
        assertEquals(PromotionHistory.DEAL_EDITED, h.getEvent());
        assertEquals("maria", h.getActor());
        assertTrue(h.getBeforeJson().contains("\"discountValue\":\"20.00\""));
        assertTrue(h.getAfterJson().contains("\"discountValue\":\"25.00\""));
        assertEquals("Black Friday week", h.getNotes());

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.update(999L, change, "maria")).getMessage().contains("not found"));
    }

    @Test
    @DisplayName("Changing a deal's frequency onto an existing code + frequency is refused before anything is written")
    void editFrequencyClash() {
        service.create(form("THANKS2026", "Monthly", "MONTHLY", "PERCENT", "20", 1, "YEARS", List.of("STANDARD")), "a");
        Map<String, Object> y = service.create(form("THANKS2026", "Yearly", "YEARLY", "PERCENT", "20", 1, "YEARS", List.of("STANDARD")), "a");
        Form clash = new Form(null, "Yearly", "MONTHLY", "PERCENT", "20", 1, "YEARS", List.of("STANDARD"), null, null, null, (Long) y.get("version"));
        assertThrows(PromotionService.Conflict.class, () -> service.update((Long) y.get("id"), clash, "a"));
        assertEquals("YEARLY", stored.get((Long) y.get("id")).getBillingFrequency());
    }

    @Test
    @DisplayName("Deactivate and reactivate: recorded with who and when; never deleted; revalidated on reactivation")
    void activate() {
        Map<String, Object> d = service.create(form("THANKS2026", "T", "MONTHLY", "FIXED_PRICE", "25", 1, "YEARS", List.of("PRO")), "anson");
        Long id = (Long) d.get("id");

        Map<String, Object> off = service.setActive(id, false, (Long) d.get("version"), "maria");
        assertEquals(false, off.get("active"));
        assertEquals(1, stored.size(), "the deal is still stored");
        assertEquals(PromotionHistory.DEAL_DEACTIVATED, historyRows.get(1).getEvent());
        assertEquals("maria", historyRows.get(1).getActor());

        service.setActive(id, false, null, "maria");                       // already inactive: nothing recorded
        assertEquals(2, historyRows.size());

        assertThrows(PromotionService.Conflict.class, () -> service.setActive(id, true, 99L, "maria"));

        // The Pro price dropped to the promotional price meanwhile: the deal no longer discounts anything.
        planRows.get(2).setMonthlyPrice(new BigDecimal("25.00"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.setActive(id, true, null, "maria"))
                .getMessage().contains("not lower than"));
        planRows.get(2).setMonthlyPrice(new BigDecimal("40.00"));

        Map<String, Object> on = service.setActive(id, true, null, "anson");
        assertEquals(true, on.get("active"));
        assertEquals(PromotionHistory.DEAL_REACTIVATED, historyRows.get(2).getEvent());

        List<Map<String, Object>> hist = service.historyOf(id);
        assertEquals(3, hist.size());
        assertEquals(PromotionHistory.DEAL_REACTIVATED, hist.get(0).get("event"), "newest first");
        assertNotNull(hist.get(0).get("before"));
    }

    // ── access ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Every endpoint needs a Service Admin session; the actor comes from the session; there is no delete")
    void accessGate() {
        PromotionAdminController c = new PromotionAdminController(service);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "GATE1"); body.put("name", "Gate"); body.put("billingFrequency", "MONTHLY");
        body.put("discountType", "PERCENT"); body.put("discountValue", "10");
        body.put("durationAmount", 1); body.put("durationUnit", "YEARS"); body.put("planCodes", List.of("PRO"));
        body.put("actor", "someone-else");                       // ignored: never read from the request body

        MockHttpServletRequest none = new MockHttpServletRequest();
        MockHttpServletRequest church = new MockHttpServletRequest();
        church.getSession().setAttribute("role", "SuperAdmin");
        church.getSession().setAttribute("clientId", "CHR-1");
        for (MockHttpServletRequest r : List.of(none, church)) {
            assertEquals(401, c.list(r).getStatusCode().value());
            assertEquals(401, c.create(body, r).getStatusCode().value());
            assertEquals(401, c.update(1L, body, r).getStatusCode().value());
            assertEquals(401, c.setActive(1L, Map.of("active", false), r).getStatusCode().value());
            assertEquals(401, c.history(1L, r).getStatusCode().value());
        }
        assertTrue(stored.isEmpty());

        MockHttpServletRequest admin = new MockHttpServletRequest();
        admin.getSession().setAttribute("role", "ServiceAdmin");
        admin.getSession().setAttribute("serviceAdminUsername", "anson");
        assertEquals(200, c.create(body, admin).getStatusCode().value());
        assertEquals("anson", historyRows.get(0).getActor());
        assertEquals(200, c.list(admin).getStatusCode().value());
        assertEquals(409, c.create(body, admin).getStatusCode().value(), "duplicate code + frequency");
        body.put("discountValue", "500");
        body.put("code", "GATE2");
        assertEquals(400, c.create(body, admin).getStatusCode().value());
        assertEquals(400, c.setActive(1L, Map.of(), admin).getStatusCode().value());
        assertEquals(400, c.history(77L, admin).getStatusCode().value());

        for (Method m : PromotionAdminController.class.getDeclaredMethods()) {
            assertNull(m.getAnnotation(DeleteMapping.class), "a deal is deactivated, never deleted");
        }
        for (Method m : PromotionHistoryRepository.class.getDeclaredMethods()) {
            assertTrue(m.getName().startsWith("find"), "promotion history is insert + read only");
        }
    }

    // ── nothing reads the catalogue yet ────────────────────────────────────

    @Test
    @DisplayName("Phase 1 changes no price: billing, subscription, client and Stripe code do not reference promotions")
    void nothingReadsPromotionsYet() throws Exception {
        Path main = Paths.get("src/main/java/com/churchgeniuspro");
        // Phase 3 connected invoices deliberately (BillingService, BillingPaymentService's receipt,
        // BillingInvoice and the draft-save endpoint). Everything else still knows nothing of promotions.
        for (String f : List.of("service/BillingReminderScheduler.java", "service/SubscriptionLifecycleService.java",
                "service/SubscriptionService.java", "service/ServiceClientService.java",
                "service/SubscriptionExpiryNotifier.java", "service/PlatformStripeService.java",
                "service/SubscriptionRequestService.java",
                "controller/InvoiceController.java", "hibernate/ServiceClient.java",
                "hibernate/BillingInvoiceLine.java")) {
            String src = Files.readString(main.resolve(f));
            assertFalse(src.contains("Promotion"), f + " must not reference promotions");
        }
    }

    @Test
    @DisplayName("The Service Admin page has the card (no delete), and the production script creates both tables")
    void pageAndMigration() throws Exception {
        String page = Files.readString(Paths.get("src/main/resources/static/serviceadminhome.html"));
        assertTrue(page.contains("id=\"promoCard\""));
        assertTrue(page.contains("/api/serviceadmin/promotions"));
        int start = page.indexOf("Promotional Deals card (promotions phase 1)");
        String script = page.substring(start);
        assertFalse(script.contains("'DELETE'"), "the card never deletes a deal");
        assertFalse(script.contains("confirm("), "no browser dialogs");
        assertTrue(script.contains("CGP_api"), "status is checked before the body is used");

        String sql = Files.readString(Paths.get("scripts/sql/migrate_production.sql"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS promotion ("));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS promotion_history ("));
        assertTrue(sql.contains("ux_promotion_code_frequency"));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void refused(String code, String name, String freq, String type, String value,
                         Integer amount, String unit, List<String> plans, String expected) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.create(form(code, name, freq, type, value, amount, unit, plans), "a"));
        assertTrue(e.getMessage().contains(expected), "expected \"" + expected + "\" in: " + e.getMessage());
    }

    private static Form form(String code, String name, String freq, String type, String value,
                             Integer amount, String unit, List<String> plans) {
        return new Form(code, name, freq, type, value, amount, unit, plans, null, null, null, null);
    }

    private static SubscriptionPlan plan(String code, String name, String monthly, String yearly, boolean active) {
        SubscriptionPlan p = new SubscriptionPlan();
        p.setPlanCode(code);
        p.setPlanName(name);
        p.setMonthlyPrice(new BigDecimal(monthly));
        p.setYearlyPrice(yearly == null ? null : new BigDecimal(yearly));
        p.setActive(active);
        return p;
    }
}
