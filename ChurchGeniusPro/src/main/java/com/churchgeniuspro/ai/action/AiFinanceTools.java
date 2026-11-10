package com.churchgeniuspro.ai.action;

import com.churchgeniuspro.ai.action.AiActionLookup.Hit;
import com.churchgeniuspro.controller.ExpenseController;
import com.churchgeniuspro.controller.IncomeController;
import com.churchgeniuspro.hibernate.Expense;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.AiAccessPolicy.CrossAccess;
import com.churchgeniuspro.service.ExpenseService;
import com.churchgeniuspro.service.IncomeService;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 2026-11-08 — income and expense creation for the guided AI Search conversation (decisions 1A–4A of the plan
 * "AI Search Conversations (Meeting, Income, Expense)"). Both tools are {@link AiTool#guidedOnly() guided-only}:
 * the conversation asks for every required field and shows a review card, then the user's explicit Save goes
 * through {@code AiActionService.confirm} — one-time token, permission re-check, by-id re-resolution, one
 * transaction — and the SAME service method the Income / Expense page uses ({@link IncomeService#createIncome},
 * {@link ExpenseService#createExpense}). No second save path.
 *
 * <p>Permission = the page's own write rule, checked when the conversation starts, at every step and again at
 * confirm time: SuperAdmin / Admin / Accountant ({@code requireAccountantOrAdmin}) AND the page
 * ({@code accounting.income} / {@code accounting.expense}) AND its edit key ({@code accounting.income.edit} /
 * {@code accounting.expense.edit}). The plan feature {@code aiActions} is checked by {@code AiActionService}.
 */
public final class AiFinanceTools {

    private AiFinanceTools() {}

    static String s(Map<String, Object> m, String k) { Object v = m.get(k); return v == null ? "" : String.valueOf(v).trim(); }
    static Long l(Map<String, Object> m, String k) { Object v = m.get(k); if (v == null) return null; try { return Long.valueOf(String.valueOf(v).trim()); } catch (Exception e) { return null; } }

    /** The Income page's own write rule. */
    public static boolean mayCreateIncome(HttpServletRequest req) {
        return RoleGuard.requireAccountantOrAdmin(req) == null
            && RoleGuard.requirePagePermission(req, "accounting.income") == null
            && RoleGuard.requirePermission(req, "accounting.income.edit") == null;
    }
    /** The Expense page's own write rule. */
    public static boolean mayCreateExpense(HttpServletRequest req) {
        return RoleGuard.requireAccountantOrAdmin(req) == null
            && RoleGuard.requirePagePermission(req, "accounting.expense") == null
            && RoleGuard.requirePermission(req, "accounting.expense.edit") == null;
    }

    /** Amount check shared by both tools — greater than zero, at most the page's maximum, two decimals. */
    static BigDecimal amount(String raw, BigDecimal max) {
        if (raw == null || raw.isBlank()) return null;
        try {
            BigDecimal a = new BigDecimal(raw.replace(",", "").replace("$", "").trim());
            if (a.signum() <= 0 || a.compareTo(max) > 0 || a.scale() > 2) return null;
            return a.setScale(2, RoundingMode.UNNECESSARY);
        } catch (Exception e) { return null; }
    }
    static LocalDate date(String raw) { try { return LocalDate.parse(raw); } catch (Exception e) { return null; } }
    public static String money(BigDecimal a) { NumberFormat f = NumberFormat.getCurrencyInstance(Locale.US); return f.format(a); }
    static String cap(String v, int n) { return v == null ? null : v.length() > n ? v.substring(0, n) : v; }
    static String opt(String v) { return v == null || v.isBlank() ? null : v.trim(); }

    static final java.time.format.DateTimeFormatter US = java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy");

    /**
     * 2026-11-09 — the saved-entry confirmation (spec §3): what was saved and the fund's total before and after.
     * Totals are shown only when both were read and differ by exactly the saved amount — otherwise (another entry
     * saved at the same moment, a lookup failure) the confirmation says the total could not be shown rather than
     * showing a misleading figure.
     */
    static Map<String, Object> receipt(String kind, BigDecimal amount, java.util.List<String[]> rows,
                                       String totalLabel, BigDecimal before, BigDecimal after) {
        Map<String, Object> r = new LinkedHashMap<>();
        boolean income = "income".equals(kind);
        r.put("kind", kind);
        r.put("title", (income ? "Income of " : "Expense of ") + money(amount) + " was added successfully.");
        java.util.List<Map<String, String>> list = new java.util.ArrayList<>();
        for (String[] row : rows) if (row[1] != null && !row[1].isBlank()) list.add(Map.of("label", row[0], "value", row[1]));
        r.put("rows", list);
        if (before != null && after != null && after.subtract(before).compareTo(amount) == 0) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("label", totalLabel);
            t.put("previous", money(before));
            t.put("addedLabel", income ? "Added income" : "Added expense");
            t.put("added", money(amount));
            t.put("next", money(after));
            r.put("totals", t);
        } else {
            r.put("totalsNote", "The fund total could not be shown reliably right now, so it is left out. The entry itself was saved.");
        }
        return r;
    }

    /** The plain-text version of the confirmation (voice, logs, older clients). */
    static String receiptText(Map<String, Object> r) {
        StringBuilder b = new StringBuilder(String.valueOf(r.get("title")));
        Object rows = r.get("rows");
        if (rows instanceof java.util.List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) b.append(" ").append(m.get("label")).append(": ").append(m.get("value")).append(".");
        if (r.get("totals") instanceof Map<?, ?> t)
            b.append(" ").append(t.get("label")).append(": previous ").append(t.get("previous")).append(", ")
             .append(String.valueOf(t.get("addedLabel")).toLowerCase(Locale.ROOT)).append(" ").append(t.get("added")).append(", new total ").append(t.get("next")).append(".");
        return b.toString();
    }

    /* ─────────────────────────── create_income ─────────────────────────── */
    @Component
    public static class CreateIncome implements AiTool {
        private final AiActionLookup people; private final AiFinanceLookup lookup; private final IncomeService incomes;
        public CreateIncome(AiActionLookup people, AiFinanceLookup lookup, IncomeService incomes) { this.people = people; this.lookup = lookup; this.incomes = incomes; }
        public String name() { return "create_income"; }
        public String label() { return "Save income"; }
        public String description() { return "Record one income entry (contributor, fund, amount, date, method; optional reference number and notes). Guided conversation only."; }
        public Map<String, String> parameters() {
            return Map.of("memberId", "member id (or guestName)", "guestName", "guest / Anonymous / Loose Offering", "fundId", "income fund id",
                          "amount", "amount", "date", "YYYY-MM-DD", "methodId", "payment method id", "refNo", "optional", "note", "optional");
        }
        @Override public boolean guidedOnly() { return true; }
        public boolean allowed(HttpServletRequest req, AiAccess a, CrossAccess x, String cid) { return mayCreateIncome(req); }

        public Resolution resolve(Map<String, Object> raw, String cid, String username) {
            Map<String, Object> out = new LinkedHashMap<>();
            Long memberId = l(raw, "memberId");
            String guest = opt(s(raw, "guestName"));
            if (memberId != null) {
                Hit m = people.memberById(cid, memberId);
                if (m == null) return Resolution.refuse("That member is not in this church's records.");
                out.put("memberId", m.id()); out.put("contributorLabel", m.label());
            } else if (guest != null) {
                out.put("guestName", cap(guest, 300)); out.put("contributorLabel", cap(guest, 300) + " (guest)");
            } else return Resolution.refuse("The contributor is missing.");
            Long fundId = l(raw, "fundId");
            Hit fund = fundId == null ? null : lookup.incomeFundById(cid, fundId);
            if (fund == null) return Resolution.refuse("That fund is not one of this church's income funds.");
            out.put("fundId", fund.id()); out.put("fundLabel", fund.label());
            BigDecimal amt = amount(s(raw, "amount"), IncomeController.AMOUNT_MAX);
            if (amt == null) return Resolution.refuse("The amount must be greater than zero and at most " + IncomeController.AMOUNT_MAX.toPlainString() + ".");
            out.put("amount", amt.toPlainString()); out.put("amountLabel", money(amt));
            LocalDate d = date(s(raw, "date"));
            if (d == null) return Resolution.refuse("The date is missing.");
            out.put("date", d.toString());
            Long methodId = l(raw, "methodId");
            Hit method = methodId == null ? null : lookup.methodById(cid, methodId);
            if (method == null) return Resolution.refuse("That payment method is not one of this church's methods.");
            out.put("methodId", method.id()); out.put("methodLabel", method.label());
            String ref = opt(s(raw, "refNo")); if (ref != null) out.put("refNo", cap(ref, 100));
            String note = opt(s(raw, "note")); if (note != null) out.put("note", cap(note, 2000));
            return Resolution.ok(out);
        }
        public String summary(Map<String, Object> r) {
            return "Record income of " + r.get("amountLabel") + " from " + r.get("contributorLabel") + " to " + r.get("fundLabel")
                 + " by " + r.get("methodLabel") + " on " + AiTools.D.format(LocalDate.parse(String.valueOf(r.get("date")))) + ".";
        }
        public Map<String, Object> execute(Map<String, Object> r, String cid, String username) {
            Long memberId = l(r, "memberId");
            BigDecimal amt = new BigDecimal(s(r, "amount"));
            BigDecimal before = lookup.incomeFundTotal(cid, l(r, "fundId"));
            Income saved = incomes.createIncome(memberId == null ? null : memberId.intValue(), l(r, "fundId").intValue(), LocalDate.parse(s(r, "date")),
                    l(r, "methodId").intValue(), opt(s(r, "refNo")), new BigDecimal(s(r, "amount")), opt(s(r, "note")),
                    memberId == null ? opt(s(r, "guestName")) : null, false, cid, username);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("incomeId", saved.getId());
            res.put("link", Map.of("label", "Open Income", "url", "/income"));
            BigDecimal after = lookup.incomeFundTotal(cid, l(r, "fundId"));
            Map<String, Object> receipt = receipt("income", amt, java.util.List.of(
                    new String[]{"Contributor", s(r, "contributorLabel")}, new String[]{"Fund", s(r, "fundLabel")},
                    new String[]{"Payment Method", s(r, "methodLabel")}, new String[]{"Transaction Date", US.format(LocalDate.parse(s(r, "date")))},
                    new String[]{"Reference Number", opt(s(r, "refNo"))}),
                    "Total income recorded to " + s(r, "fundLabel") + " (all dates)", before, after);
            res.put("receipt", receipt);
            res.put("answer", receiptText(receipt));
            return res;
        }
    }

    /* ─────────────────────────── create_expense ─────────────────────────── */
    @Component
    public static class CreateExpense implements AiTool {
        private final AiFinanceLookup lookup; private final ExpenseService expenses;
        public CreateExpense(AiFinanceLookup lookup, ExpenseService expenses) { this.lookup = lookup; this.expenses = expenses; }
        public String name() { return "create_expense"; }
        public String label() { return "Save expense"; }
        public String description() { return "Record one expense entry (category, fund, amount, date, method; optional reference number and notes). Guided conversation only."; }
        public Map<String, String> parameters() {
            return Map.of("categoryId", "expense category id", "fundId", "expense fund id", "amount", "amount", "date", "YYYY-MM-DD",
                          "methodId", "payment method id", "refNo", "optional", "note", "optional");
        }
        @Override public boolean guidedOnly() { return true; }
        public boolean allowed(HttpServletRequest req, AiAccess a, CrossAccess x, String cid) { return mayCreateExpense(req); }

        public Resolution resolve(Map<String, Object> raw, String cid, String username) {
            Map<String, Object> out = new LinkedHashMap<>();
            Long catId = l(raw, "categoryId");
            Hit cat = catId == null ? null : lookup.expenseCategoryById(cid, catId);
            if (cat == null) return Resolution.refuse("That category is not one of this church's expense categories.");
            out.put("categoryId", cat.id()); out.put("categoryLabel", cat.label());
            Long fundId = l(raw, "fundId");
            Hit fund = fundId == null ? null : lookup.expenseFundById(cid, fundId);
            if (fund == null) return Resolution.refuse("That fund is not one of this church's expense funds.");
            out.put("fundId", fund.id()); out.put("fundLabel", fund.label());
            BigDecimal amt = amount(s(raw, "amount"), ExpenseController.AMOUNT_MAX);
            if (amt == null) return Resolution.refuse("The amount must be greater than zero and at most " + ExpenseController.AMOUNT_MAX.toPlainString() + ".");
            out.put("amount", amt.toPlainString()); out.put("amountLabel", money(amt));
            LocalDate d = date(s(raw, "date"));
            if (d == null) return Resolution.refuse("The date is missing.");
            out.put("date", d.toString());
            Long methodId = l(raw, "methodId");
            Hit method = methodId == null ? null : lookup.methodById(cid, methodId);
            if (method == null) return Resolution.refuse("That payment method is not one of this church's methods.");
            out.put("methodId", method.id()); out.put("methodLabel", method.label());
            String ref = opt(s(raw, "refNo")); if (ref != null) out.put("refNo", cap(ref, 100));
            String note = opt(s(raw, "note")); if (note != null) out.put("note", cap(note, 2000));
            return Resolution.ok(out);
        }
        public String summary(Map<String, Object> r) {
            return "Record an expense of " + r.get("amountLabel") + " for " + r.get("categoryLabel") + " from " + r.get("fundLabel")
                 + " by " + r.get("methodLabel") + " on " + AiTools.D.format(LocalDate.parse(String.valueOf(r.get("date")))) + ".";
        }
        public Map<String, Object> execute(Map<String, Object> r, String cid, String username) {
            BigDecimal amt = new BigDecimal(s(r, "amount"));
            BigDecimal before = lookup.expenseFundTotal(cid, l(r, "fundId"));
            Expense saved = expenses.createExpense(l(r, "categoryId").intValue(), l(r, "fundId").intValue(), LocalDate.parse(s(r, "date")),
                    l(r, "methodId").intValue(), opt(s(r, "refNo")), new BigDecimal(s(r, "amount")), opt(s(r, "note")), false, cid, username);
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("expenseId", saved.getId());
            res.put("link", Map.of("label", "Open Expenses", "url", "/expense"));
            BigDecimal after = lookup.expenseFundTotal(cid, l(r, "fundId"));
            Map<String, Object> receipt = receipt("expense", amt, java.util.List.of(
                    new String[]{"Expense Category", s(r, "categoryLabel")}, new String[]{"Fund", s(r, "fundLabel")},
                    new String[]{"Payment Method", s(r, "methodLabel")}, new String[]{"Transaction Date", US.format(LocalDate.parse(s(r, "date")))},
                    new String[]{"Reference Number", opt(s(r, "refNo"))}),
                    "Total expenses paid from " + s(r, "fundLabel") + " (all dates)", before, after);
            res.put("receipt", receipt);
            res.put("answer", receiptText(receipt));
            return res;
        }
    }
}
