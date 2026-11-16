package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.BillingInvoice;
import com.churchgeniuspro.hibernate.ClientPromotion;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.repository.BillingInvoiceRepository;
import com.churchgeniuspro.repository.ClientPromotionRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.util.AppClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Promotions phase 5 — the promotion report (read-only). One row per discount ever
 * given to a client: Scheduled, Active, Expired and Cancelled alike — nothing is
 * left out because it ended.
 *
 * <p>"Discount provided" is what was actually given: the promotion's part of the
 * client's PAID invoices. The promotion's part of invoices that were sent but are
 * not paid yet is reported separately ("on unpaid invoices"); drafts and voided
 * invoices count for nothing.
 */
@Service
public class PromotionReportService {

    static final List<String> STATUSES = List.of(ClientPromotion.SCHEDULED, ClientPromotion.ACTIVE,
            ClientPromotion.EXPIRED, ClientPromotion.CANCELLED);

    private final ClientPromotionRepository assignments;
    private final BillingInvoiceRepository invoices;
    private final ServiceClientRepository clients;
    private final ClientPromotionService promotions;

    public PromotionReportService(ClientPromotionRepository assignments, BillingInvoiceRepository invoices,
                                  ServiceClientRepository clients, ClientPromotionService promotions) {
        this.assignments = assignments;
        this.invoices = invoices;
        this.clients = clients;
        this.promotions = promotions;
    }

    /** All filters optional. {@code from}/{@code to}: discounts whose dates overlap the range (a waiting one: assigned in it). */
    public record Filter(String code, String client, String plan, String frequency, String status, LocalDate from, LocalDate to) {}

    @Transactional(readOnly = true)
    public Map<String, Object> report(Filter f) {
        Filter x = normalise(f);
        Map<String, ServiceClient> byClient = new HashMap<>();
        for (ServiceClient sc : clients.findAll()) if (sc.getClientId() != null) byClient.put(sc.getClientId(), sc);

        Map<Long, BigDecimal[]> money = new HashMap<>();      // discount id → {paid, unpaid}
        Map<Long, int[]> counts = new HashMap<>();            // discount id → {paid invoices, unpaid invoices}
        for (BillingInvoice i : invoices.findByClientPromotionIdIsNotNull()) {
            if (i.getPromotionDiscount() == null || i.getPromotionDiscount().signum() <= 0) continue;
            int slot = BillingInvoice.PAID.equals(i.getStatus()) ? 0 : BillingInvoice.SENT.equals(i.getStatus()) ? 1 : -1;
            if (slot < 0) continue;
            BigDecimal[] m = money.computeIfAbsent(i.getClientPromotionId(), k -> new BigDecimal[]{ BigDecimal.ZERO, BigDecimal.ZERO });
            m[slot] = m[slot].add(i.getPromotionDiscount());
            counts.computeIfAbsent(i.getClientPromotionId(), k -> new int[2])[slot]++;
        }

        List<ClientPromotion> all = new ArrayList<>(assignments.findAll());
        all.sort(Comparator.comparing(ClientPromotion::getId).reversed());
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (String s : STATUSES) byStatus.put(s, 0);
        BigDecimal provided = BigDecimal.ZERO, pending = BigDecimal.ZERO;
        for (ClientPromotion a : all) {
            ServiceClient sc = byClient.get(a.getClientId());
            if (!matches(a, sc, x)) continue;
            BigDecimal[] m = money.getOrDefault(a.getId(), new BigDecimal[]{ BigDecimal.ZERO, BigDecimal.ZERO });
            int[] c = counts.getOrDefault(a.getId(), new int[2]);
            rows.add(row(a, sc, m, c));
            byStatus.merge(a.getStatus(), 1, Integer::sum);
            provided = provided.add(m[0]);
            pending = pending.add(m[1]);
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("count", rows.size());
        totals.put("byStatus", byStatus);
        totals.put("discountProvided", provided.setScale(2, RoundingMode.HALF_UP));
        totals.put("discountOnUnpaidInvoices", pending.setScale(2, RoundingMode.HALF_UP));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("totals", totals);
        out.put("filters", summary(x));
        out.put("generatedAt", LocalDateTime.now().withNano(0).toString());
        out.put("today", AppClock.today().toString());
        return out;
    }

    private static Filter normalise(Filter f) {
        if (f == null) return new Filter(null, null, null, null, null, null, null);
        String status = up(f.status());
        if (status != null && !STATUSES.contains(status)) throw new IllegalArgumentException("Unknown status: " + f.status());
        String freq = up(f.frequency());
        if (freq != null && !List.of("MONTHLY", "YEARLY").contains(freq)) throw new IllegalArgumentException("Billing frequency must be Monthly or Yearly.");
        if (f.from() != null && f.to() != null && f.to().isBefore(f.from())) throw new IllegalArgumentException("The end date is before the start date.");
        return new Filter(up(f.code()), f.client() == null || f.client().isBlank() ? null : f.client().trim().toLowerCase(Locale.ROOT),
                up(f.plan()), freq, status, f.from(), f.to());
    }

    private static boolean matches(ClientPromotion a, ServiceClient sc, Filter f) {
        if (f.status() != null && !f.status().equals(a.getStatus())) return false;
        if (f.code() != null) {
            boolean manual = ClientPromotion.MANUAL.equals(a.getKind());
            if ("MANUAL".equals(f.code())) { if (!manual) return false; }
            else if (manual || a.getPromotionCode() == null || !a.getPromotionCode().toUpperCase(Locale.ROOT).contains(f.code())) return false;
        }
        if (f.plan() != null && (a.getPlanCode() == null || !a.getPlanCode().equalsIgnoreCase(f.plan()))) return false;
        if (f.frequency() != null && !f.frequency().equals(a.getBillingFrequency())) return false;
        if (f.client() != null) {
            String hay = (a.getClientId() + " " + (sc == null || sc.getChurchName() == null ? "" : sc.getChurchName())).toLowerCase(Locale.ROOT);
            if (!hay.contains(f.client())) return false;
        }
        if (f.from() != null || f.to() != null) {
            LocalDate s = a.getStartDate(), e = a.getEndDate();
            if (s == null) {                                   // waiting on a trial: by the day it was assigned
                s = a.getAssignedAt() == null ? null : a.getAssignedAt().toLocalDate();
                e = s;
            }
            if (s == null) return false;
            if (e == null) e = s;
            if (f.from() != null && e.isBefore(f.from())) return false;
            if (f.to() != null && s.isAfter(f.to())) return false;
        }
        return true;
    }

    private static Map<String, Object> row(ClientPromotion a, ServiceClient sc, BigDecimal[] money, int[] counts) {
        boolean manual = ClientPromotion.MANUAL.equals(a.getKind());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("clientNumericId", sc == null ? null : sc.getId());
        m.put("clientId", a.getClientId());
        m.put("churchName", sc == null ? null : sc.getChurchName());
        m.put("clientDeleted", sc == null || Boolean.TRUE.equals(sc.getDeleteFlag()));
        m.put("kind", a.getKind());
        m.put("label", manual ? ClientPromotion.MANUAL_LABEL : a.getPromotionCode());
        m.put("dealName", manual ? null : a.getPromotionName());
        m.put("planCode", a.getPlanCode());
        m.put("billingFrequency", a.getBillingFrequency());
        String type = a.getDiscountType() != null ? a.getDiscountType() : a.getDealDiscountType();
        BigDecimal value = a.getDiscountValue() != null ? a.getDiscountValue() : a.getDealDiscountValue();
        m.put("discount", discountText(type, value));
        m.put("duration", ClientPromotionService.durationText(
                a.getDurationAmount() != null ? a.getDurationAmount() : a.getDealDurationAmount(),
                a.getDurationUnit() != null ? a.getDurationUnit() : a.getDealDurationUnit()));
        m.put("manuallyAdjusted", a.isManuallyAdjusted());
        m.put("status", a.getStatus());
        m.put("startDate", str(a.getStartDate()));
        m.put("endDate", str(a.getEndDate()));
        m.put("regularPrice", a.getRegularPrice());
        m.put("promotionalPrice", a.getPromotionalPrice());
        m.put("discountProvided", money[0].setScale(2, RoundingMode.HALF_UP));
        m.put("paidInvoices", counts[0]);
        m.put("discountOnUnpaidInvoices", money[1].setScale(2, RoundingMode.HALF_UP));
        m.put("unpaidInvoices", counts[1]);
        m.put("assignedBy", a.getAssignedBy());
        m.put("assignedAt", str(a.getAssignedAt()));
        m.put("appliedBy", a.getAppliedBy());
        m.put("appliedAt", str(a.getAppliedAt()));
        m.put("cancelledBy", a.getCancelledBy());
        m.put("cancelledAt", str(a.getCancelledAt()));
        m.put("cancelReason", a.getCancelReason());
        m.put("regularPricingRestoredOn", str(a.getRestoredOn()));
        m.put("notes", a.getNotes());
        return m;
    }

    static String discountText(String type, BigDecimal value) {
        if (type == null || value == null) return null;
        return switch (type) {
            case "PERCENT" -> value.stripTrailingZeros().toPlainString() + "% off";
            case "AMOUNT_OFF" -> BillingService.money(value) + " off";
            case "FIXED_PRICE" -> BillingService.money(value) + " fixed price";
            default -> type + " " + value.toPlainString();
        };
    }

    private static String summary(Filter f) {
        List<String> parts = new ArrayList<>();
        if (f.code() != null) parts.add("Deal code: " + ("MANUAL".equals(f.code()) ? "manual discounts" : f.code()));
        if (f.client() != null) parts.add("Customer: " + f.client());
        if (f.plan() != null) parts.add("Plan: " + f.plan());
        if (f.frequency() != null) parts.add("Billing: " + ("YEARLY".equals(f.frequency()) ? "Yearly" : "Monthly"));
        if (f.status() != null) parts.add("Status: " + f.status().charAt(0) + f.status().substring(1).toLowerCase(Locale.ROOT));
        if (f.from() != null) parts.add("From " + f.from());
        if (f.to() != null) parts.add("To " + f.to());
        return parts.isEmpty() ? "All promotions" : String.join(" · ", parts);
    }

    // ── CSV ────────────────────────────────────────────────────────────────

    private static final String[][] COLUMNS = {
        {"churchName", "Customer"}, {"clientId", "Client ID"}, {"label", "Promotion"}, {"dealName", "Deal name"},
        {"planCode", "Plan"}, {"billingFrequency", "Billing"}, {"discount", "Discount"}, {"duration", "Duration"},
        {"manuallyAdjusted", "Adjusted for this customer"}, {"status", "Status"}, {"startDate", "Start date"}, {"endDate", "End date"},
        {"regularPrice", "Regular price"}, {"promotionalPrice", "Promotional price"},
        {"discountProvided", "Discount provided (paid invoices)"}, {"paidInvoices", "Paid invoices"},
        {"discountOnUnpaidInvoices", "Discount on unpaid invoices"}, {"unpaidInvoices", "Unpaid invoices"},
        {"assignedBy", "Assigned by"}, {"assignedAt", "Assigned at"}, {"appliedBy", "Applied by"}, {"appliedAt", "Applied at"},
        {"cancelledBy", "Cancelled by"}, {"cancelledAt", "Cancelled at"}, {"cancelReason", "Cancel reason"},
        {"regularPricingRestoredOn", "Regular pricing restored on"}, {"notes", "Notes"}
    };

    @SuppressWarnings("unchecked")
    public String csv(Filter f) {
        Map<String, Object> r = report(f);
        Map<String, Object> totals = (Map<String, Object>) r.get("totals");
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(cell("ChurchGeniusPro — Promotion report")).append("\r\n");
        sb.append(cell("Generated")).append(',').append(cell(r.get("generatedAt"))).append("\r\n");
        sb.append(cell("Filters")).append(',').append(cell(r.get("filters"))).append("\r\n");
        sb.append(cell("Discount provided (paid invoices)")).append(',').append(cell(totals.get("discountProvided"))).append("\r\n");
        sb.append(cell("Discount on unpaid invoices")).append(',').append(cell(totals.get("discountOnUnpaidInvoices"))).append("\r\n\r\n");
        for (int i = 0; i < COLUMNS.length; i++) sb.append(i == 0 ? "" : ",").append(cell(COLUMNS[i][1]));
        sb.append("\r\n");
        for (Map<String, Object> row : (List<Map<String, Object>>) r.get("rows")) {
            for (int i = 0; i < COLUMNS.length; i++) {
                Object v = row.get(COLUMNS[i][0]);
                if (v instanceof Boolean b) v = b ? "Yes" : "";
                sb.append(i == 0 ? "" : ",").append(cell(v));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    /** Quoted; a value a spreadsheet would run as a formula is neutralised. */
    static String cell(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !(v instanceof Number)) s = "'" + s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    // ── the church's own view ──────────────────────────────────────────────

    /**
     * What a church sees about its own promotional pricing: only while a promotional
     * price is in force today. No codes of other deals, no notes, no who-assigned-it.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> churchView(String clientId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", false);
        ServiceClient sc = clientId == null ? null : clients.findByClientId(clientId).orElse(null);
        if (sc == null) return out;
        ClientPromotionService.Quote q = promotions.quote(sc, AppClock.today());
        if (!q.discounted()) return out;
        ClientPromotion a = q.discount();
        out.put("active", true);
        out.put("label", ClientPromotionService.invoiceLabel(a));
        out.put("name", ClientPromotion.MANUAL.equals(a.getKind()) ? null : a.getPromotionName());
        out.put("billingFrequency", a.getBillingFrequency());
        out.put("promotionalPrice", q.price());
        out.put("regularPrice", q.regularPrice());
        out.put("endDate", str(a.getEndDate()));
        out.put("regularPriceFrom", a.getEndDate() == null ? null : a.getEndDate().plusDays(1).toString());
        return out;
    }

    private static String up(String s) { return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT); }
    private static String str(Object o) { return o == null ? null : o.toString(); }
}
