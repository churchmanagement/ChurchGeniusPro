package com.churchgeniuspro.service;

import com.churchgeniuspro.controller.PromotionReportController;
import com.churchgeniuspro.hibernate.BillingInvoice;
import com.churchgeniuspro.hibernate.ClientPromotion;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.repository.BillingInvoiceRepository;
import com.churchgeniuspro.repository.ClientPromotionRepository;
import com.churchgeniuspro.repository.PromotionHistoryRepository;
import com.churchgeniuspro.repository.PromotionRepository;
import com.churchgeniuspro.repository.ReminderSentLogRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import com.churchgeniuspro.util.AppClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Promotions phase 5 — the report (filters, actual discount, CSV), the church's own view, the applied-at-Convert email. */
@DisplayName("Promotions phase 5 — report and displays")
class PromotionReportPhase5Test {

    static final LocalDate TODAY = AppClock.today();

    final Map<Long, ClientPromotion> rows = new LinkedHashMap<>();
    final Map<String, ServiceClient> clientRows = new LinkedHashMap<>();
    final List<BillingInvoice> invoiceRows = new ArrayList<>();
    final List<Object[]> mails = new ArrayList<>();
    long ids = 0;

    PromotionReportService reports;
    ClientPromotionService promotions;
    PromotionExpiryScheduler mailer;

    @BeforeEach
    void setUp() throws Exception {
        ClientPromotionRepository assignments = mock(ClientPromotionRepository.class);
        when(assignments.findAll()).thenAnswer(i -> new ArrayList<>(rows.values()));
        when(assignments.findByClientIdAndStatusIn(anyString(), anyCollection())).thenAnswer(i -> rows.values().stream()
                .filter(a -> a.getClientId().equals(i.getArgument(0)) && ((Collection<?>) i.getArgument(1)).contains(a.getStatus())).toList());
        ServiceClientRepository clients = mock(ServiceClientRepository.class);
        when(clients.findAll()).thenAnswer(i -> new ArrayList<>(clientRows.values()));
        when(clients.findByClientId(anyString())).thenAnswer(i -> Optional.ofNullable(clientRows.get((String) i.getArgument(0))));
        SubscriptionPlanRepository plans = mock(SubscriptionPlanRepository.class);
        SubscriptionPlan pro = new SubscriptionPlan();
        pro.setPlanCode("PRO"); pro.setPlanName("Pro"); pro.setActive(true); pro.setMonthlyPrice(new BigDecimal("40.00"));
        when(plans.findByPlanCodeIgnoreCase(anyString())).thenReturn(Optional.of(pro));
        promotions = new ClientPromotionService(assignments, mock(PromotionRepository.class), mock(PromotionHistoryRepository.class), clients, plans);
        BillingInvoiceRepository invoices = mock(BillingInvoiceRepository.class);
        when(invoices.findByClientPromotionIdIsNotNull()).thenAnswer(i -> invoiceRows.stream().filter(b -> b.getClientPromotionId() != null).toList());
        reports = new PromotionReportService(assignments, invoices, clients, promotions);

        EmailService email = mock(EmailService.class);
        doAnswer(i -> { mails.add(new Object[]{ ((List<?>) i.getArgument(0)).get(0), i.getArgument(1), i.getArgument(2), i.getArgument(3) }); return null; })
                .when(email).sendComposed(anyList(), any(), anyString(), anyString(), any(), anyString());
        PlatformSettingService settings = mock(PlatformSettingService.class);
        when(settings.supportEmail()).thenReturn("support@cgp.test");
        mailer = new PromotionExpiryScheduler(promotions, clients, settings, mock(ReminderSentLogRepository.class), email);
    }

    ServiceClient client(String cid, String name) {
        ServiceClient c = new ServiceClient();
        c.setId(clientRows.size() + 1); c.setClientId(cid); c.setChurchName(name); c.setEmail(cid.toLowerCase() + "@church.test");
        c.setSubscriptionType("PRO"); c.setStatus("Active"); c.setDeleteFlag(false); c.setBillingFrequency("MONTHLY");
        c.setSubscriptionPrice(new BigDecimal("40.00"));
        clientRows.put(cid, c);
        return c;
    }

    ClientPromotion discount(String cid, String kind, String code, String status, LocalDate start, LocalDate end) {
        ClientPromotion a = new ClientPromotion();
        a.setId(++ids); a.setClientId(cid); a.setKind(kind); a.setPromotionCode(code); a.setPromotionName(code == null ? ClientPromotion.MANUAL_LABEL : "Deal " + code);
        a.setPlanCode("PRO"); a.setBillingFrequency("MONTHLY"); a.setDiscountType("FIXED_PRICE"); a.setDiscountValue(new BigDecimal("25.00"));
        a.setDurationAmount(2); a.setDurationUnit("YEARS"); a.setStatus(status); a.setStartDate(start); a.setEndDate(end);
        a.setRegularPrice(new BigDecimal("40.00")); a.setPromotionalPrice(new BigDecimal("25.00"));
        a.setAssignedBy("anson"); a.setAssignedAt(LocalDateTime.now().minusDays(3)); a.setNotes("=cmd|' /C calc'!A0");
        rows.put(a.getId(), a);
        return a;
    }

    void invoice(Long promoId, String status, String promoDiscount) {
        BillingInvoice b = new BillingInvoice();
        b.setClientPromotionId(promoId); b.setStatus(status); b.setPromotionDiscount(promoDiscount == null ? null : new BigDecimal(promoDiscount));
        invoiceRows.add(b);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rowsOf(Map<String, Object> r) { return (List<Map<String, Object>>) r.get("rows"); }
    @SuppressWarnings("unchecked")
    static Map<String, Object> totals(Map<String, Object> r) { return (Map<String, Object>) r.get("totals"); }
    static PromotionReportService.Filter f(String code, String client, String plan, String freq, String status, LocalDate from, LocalDate to) {
        return new PromotionReportService.Filter(code, client, plan, freq, status, from, to);
    }

    void seed() {
        client("CHR-1", "Grace Church"); client("CHR-2", "Hope Chapel"); client("CHR-3", "Faith Center"); client("CHR-4", "New Life");
        ClientPromotion active = discount("CHR-1", ClientPromotion.DEAL, "THANKS2026", ClientPromotion.ACTIVE, TODAY.minusMonths(3), TODAY.plusMonths(21));
        ClientPromotion expired = discount("CHR-2", ClientPromotion.DEAL, "SPRING25", ClientPromotion.EXPIRED, TODAY.minusYears(2), TODAY.minusYears(1));
        expired.setRestoredOn(TODAY.minusYears(1).plusDays(1));
        ClientPromotion cancelled = discount("CHR-3", ClientPromotion.MANUAL, null, ClientPromotion.CANCELLED, TODAY.minusMonths(2), TODAY.plusMonths(4));
        cancelled.setCancelledBy("anson"); cancelled.setCancelledAt(LocalDateTime.now()); cancelled.setCancelReason("Requested by church");
        ClientPromotion waiting = discount("CHR-4", ClientPromotion.DEAL, "THANKS2026", ClientPromotion.SCHEDULED, null, null);
        waiting.setDiscountType(null); waiting.setDiscountValue(null); waiting.setDealDiscountType("PERCENT"); waiting.setDealDiscountValue(new BigDecimal("50.00"));
        waiting.setRegularPrice(null); waiting.setPromotionalPrice(null); waiting.setBillingFrequency(null); waiting.setPlanCode(null);
        invoice(active.getId(), BillingInvoice.PAID, "15.00");
        invoice(active.getId(), BillingInvoice.PAID, "15.00");
        invoice(active.getId(), BillingInvoice.SENT, "15.00");
        invoice(active.getId(), BillingInvoice.DRAFT, "15.00");
        invoice(active.getId(), BillingInvoice.VOID, "15.00");
        invoice(expired.getId(), BillingInvoice.PAID, "120.00");
        invoice(null, BillingInvoice.PAID, null);
    }

    @Test @DisplayName("Every discount is listed with its status; discount provided = paid invoices only, unpaid shown separately")
    void report() {
        seed();
        Map<String, Object> r = reports.report(null);
        assertThat(rowsOf(r)).hasSize(4);
        Map<String, Object> t = totals(r);
        assertThat(t.get("byStatus").toString()).isEqualTo("{SCHEDULED=1, ACTIVE=1, EXPIRED=1, CANCELLED=1}");
        assertThat((BigDecimal) t.get("discountProvided")).isEqualByComparingTo("150.00");
        assertThat((BigDecimal) t.get("discountOnUnpaidInvoices")).isEqualByComparingTo("15.00");
        Map<String, Object> active = rowsOf(r).stream().filter(x -> "CHR-1".equals(x.get("clientId"))).findFirst().orElseThrow();
        assertThat((BigDecimal) active.get("discountProvided")).isEqualByComparingTo("30.00");
        assertThat(active.get("paidInvoices")).isEqualTo(2);
        assertThat(active.get("unpaidInvoices")).isEqualTo(1);
        assertThat(active.get("discount")).isEqualTo("$25.00 fixed price");
        assertThat(active.get("assignedBy")).isEqualTo("anson");
        Map<String, Object> manual = rowsOf(r).stream().filter(x -> "CHR-3".equals(x.get("clientId"))).findFirst().orElseThrow();
        assertThat(manual.get("label")).isEqualTo("Manual Discount – No Promotion Code");
        assertThat(manual.get("cancelReason")).isEqualTo("Requested by church");
        Map<String, Object> waiting = rowsOf(r).stream().filter(x -> "CHR-4".equals(x.get("clientId"))).findFirst().orElseThrow();
        assertThat(waiting.get("discount")).isEqualTo("50% off");
        assertThat(waiting.get("startDate")).isNull();
        Map<String, Object> expired = rowsOf(r).stream().filter(x -> "CHR-2".equals(x.get("clientId"))).findFirst().orElseThrow();
        assertThat(expired.get("regularPricingRestoredOn")).isEqualTo(TODAY.minusYears(1).plusDays(1).toString());
    }

    @Test @DisplayName("Filters: deal code, customer, plan, frequency, status and date range")
    void filters() {
        seed();
        assertThat(rowsOf(reports.report(f("thanks", null, null, null, null, null, null)))).hasSize(2);
        assertThat(rowsOf(reports.report(f("MANUAL", null, null, null, null, null, null)))).extracting(x -> x.get("clientId")).containsExactly("CHR-3");
        assertThat(rowsOf(reports.report(f(null, "hope", null, null, null, null, null)))).extracting(x -> x.get("clientId")).containsExactly("CHR-2");
        assertThat(rowsOf(reports.report(f(null, "chr-1", null, null, null, null, null)))).hasSize(1);
        assertThat(rowsOf(reports.report(f(null, null, "pro", null, null, null, null)))).hasSize(3);
        assertThat(rowsOf(reports.report(f(null, null, null, "monthly", null, null, null)))).hasSize(3);
        assertThat(rowsOf(reports.report(f(null, null, null, "YEARLY", null, null, null)))).isEmpty();
        assertThat(rowsOf(reports.report(f(null, null, null, null, "expired", null, null)))).extracting(x -> x.get("clientId")).containsExactly("CHR-2");
        // range: overlaps for dated discounts, the assignment day for a waiting one
        assertThat(rowsOf(reports.report(f(null, null, null, null, null, TODAY.minusDays(10), TODAY)))).extracting(x -> x.get("clientId"))
                .containsExactlyInAnyOrder("CHR-1", "CHR-3", "CHR-4");
        assertThat(rowsOf(reports.report(f(null, null, null, null, null, TODAY.minusYears(3), TODAY.minusMonths(18))))).extracting(x -> x.get("clientId"))
                .containsExactly("CHR-2");
        Map<String, Object> combined = reports.report(f("THANKS2026", null, null, null, "ACTIVE", null, null));
        assertThat(rowsOf(combined)).hasSize(1);
        assertThat((BigDecimal) totals(combined).get("discountProvided")).isEqualByComparingTo("30.00");
        assertThat((String) combined.get("filters")).contains("Deal code: THANKS2026").contains("Status: Active");
        assertThatThrownBy(() -> reports.report(f(null, null, null, null, "PAUSED", null, null))).hasMessageContaining("Unknown status");
        assertThatThrownBy(() -> reports.report(f(null, null, null, "WEEKLY", null, null, null))).hasMessageContaining("Monthly or Yearly");
        assertThatThrownBy(() -> reports.report(f(null, null, null, null, null, TODAY, TODAY.minusDays(1)))).hasMessageContaining("before the start");
    }

    @Test @DisplayName("CSV: heading, filters, totals, every row; formula-looking text is neutralised")
    void csv() {
        seed();
        String csv = reports.csv(f(null, null, null, null, null, null, null));
        assertThat(csv).startsWith("﻿\"ChurchGeniusPro — Promotion report\"");
        assertThat(csv).contains("\"Filters\",\"All promotions\"").contains("\"Discount provided (paid invoices)\",\"150.00\"")
                .contains("\"Customer\",\"Client ID\",\"Promotion\"").contains("\"Grace Church\",\"CHR-1\",\"THANKS2026\"")
                .contains("\"Manual Discount – No Promotion Code\"").contains("\"Requested by church\"");
        assertThat(csv).contains("\"'=cmd|' /C calc'!A0\"").doesNotContain(",\"=cmd");
        assertThat(csv.split("\r\n")).hasSize(6 + 1 + 4);
        assertThat(PromotionReportService.cell(new BigDecimal("-5.00"))).isEqualTo("\"-5.00\"");
        assertThat(PromotionReportService.cell("a\"b")).isEqualTo("\"a\"\"b\"");
    }

    @Test @DisplayName("A church sees only its own promotional price, and only while it is in force")
    void churchView() {
        seed();
        Map<String, Object> mine = reports.churchView("CHR-1");
        assertThat(mine.get("active")).isEqualTo(true);
        assertThat(mine.get("label")).isEqualTo("Promotion THANKS2026");
        assertThat((BigDecimal) mine.get("promotionalPrice")).isEqualByComparingTo("25.00");
        assertThat((BigDecimal) mine.get("regularPrice")).isEqualByComparingTo("40.00");
        assertThat(mine.get("endDate")).isEqualTo(TODAY.plusMonths(21).toString());
        assertThat(mine).doesNotContainKeys("notes", "assignedBy", "clientId");
        for (String cid : List.of("CHR-2", "CHR-3", "CHR-4", "CHR-404")) {
            assertThat(reports.churchView(cid)).as(cid).containsEntry("active", false).hasSize(1);
        }
        assertThat(reports.churchView(null)).containsEntry("active", false);
    }

    @Test @DisplayName("Endpoints: the report is Service Admin only; a church's view needs an Admin or Church session of that church")
    void endpoints() {
        seed();
        PromotionReportController c = new PromotionReportController(reports);
        MockHttpServletRequest none = new MockHttpServletRequest();
        assertThat(c.report(Map.of(), none).getStatusCode().value()).isEqualTo(401);
        assertThat(c.csv(Map.of(), none).getStatusCode().value()).isEqualTo(401);
        assertThat(c.own(none).getStatusCode().value()).isEqualTo(401);

        MockHttpServletRequest tenantAdmin = new MockHttpServletRequest();
        tenantAdmin.getSession().setAttribute("role", "SuperAdmin");
        tenantAdmin.getSession().setAttribute("username", "pat");
        tenantAdmin.getSession().setAttribute("clientId", "USR-1");
        tenantAdmin.getSession().setAttribute("appClientId", "CHR-1");
        assertThat(c.report(Map.of(), tenantAdmin).getStatusCode().value()).isEqualTo(401);
        assertThat(c.own(tenantAdmin).getBody()).containsEntry("active", true).containsEntry("label", "Promotion THANKS2026");

        MockHttpServletRequest accountant = new MockHttpServletRequest();
        accountant.getSession().setAttribute("role", "Accountant");
        accountant.getSession().setAttribute("username", "acct");
        accountant.getSession().setAttribute("appClientId", "CHR-1");
        assertThat(c.own(accountant).getStatusCode().value()).isEqualTo(403);

        MockHttpServletRequest admin = new MockHttpServletRequest();
        admin.getSession().setAttribute("role", "ServiceAdmin");
        assertThat(c.report(Map.of("status", "ACTIVE"), admin).getBody()).containsEntry("status", "success");
        assertThat(c.report(Map.of("from", "11/01/2026"), admin).getStatusCode().value()).isEqualTo(400);
        var csv = c.csv(Map.of(), admin);
        assertThat(csv.getStatusCode().value()).isEqualTo(200);
        assertThat(csv.getHeaders().getFirst("Content-Disposition")).contains("promotion-report-");
    }

    @Test @DisplayName("After Convert with the discount applied: the church is emailed its price, period and the regular price")
    void appliedEmail() {
        ServiceClient sc = client("CHR-9", "Grace <Church>");
        LocalDate start = TODAY.plusDays(2);
        discount("CHR-9", ClientPromotion.DEAL, "THANKS2026", ClientPromotion.ACTIVE, start, start.plusYears(2).minusDays(1));
        assertThat(mailer.sendAppliedConfirmation(sc)).isTrue();
        assertThat(mails.get(0)[0]).isEqualTo("chr-9@church.test");
        assertThat(String.valueOf(mails.get(0)[1])).isEqualTo("[support@cgp.test]");
        String html = (String) mails.get(0)[3];
        assertThat(html).contains("Promotion THANKS2026").contains("$25.00 per month").contains("$40.00 per month")
                .contains("Grace &lt;Church&gt;").doesNotContain("=cmd");
        // nothing to send: no discount, a waiting one, no email address
        assertThat(mailer.sendAppliedConfirmation(client("CHR-10", "None"))).isFalse();
        ServiceClient noMail = client("CHR-11", "No mail"); noMail.setEmail(" ");
        assertThat(mailer.sendAppliedConfirmation(noMail)).isFalse();
        assertThat(mailer.sendAppliedConfirmation(null)).isFalse();
        assertThat(mails).hasSize(1);
    }

    @Test @DisplayName("Pages: the report card and the church's pricing card")
    void pages() throws Exception {
        String admin = Files.readString(Paths.get("src/main/resources/static/serviceadminhome.html"));
        assertThat(admin).contains("id=\"promoReportCard\"").contains("/api/serviceadmin/promotions/report-csv").contains("id=\"prStatus\"");
        String logo = Files.readString(Paths.get("src/main/resources/static/logo.html"));
        assertThat(logo).contains("id=\"promoPricingCard\"").contains("/api/subscription/promotion").contains("if (!r.ok) return;");
    }
}
