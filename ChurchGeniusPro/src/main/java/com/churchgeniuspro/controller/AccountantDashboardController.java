package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.Expense;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.hibernate.MainSource;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.MainSourceRepository;
import com.churchgeniuspro.service.ExpenseService;
import com.churchgeniuspro.service.IncomeService;
import com.churchgeniuspro.service.LedgerSummaryService;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregated financial statistics for the Accountant home dashboard.
 * GET /api/accountant/dashboard[?year=YYYY] — tenant is taken from the session;
 * a client-supplied {@code clientId} query param is ignored.
 *
 * <p>Every total, per-fund and per-month figure comes from {@code ledger_month_summary}
 * via {@link LedgerSummaryService} (ledger scalability, part B); only the activity
 * feed and the Quick Add panels still read individual ledger rows, with a LIMIT.
 */
@RestController
public class AccountantDashboardController {

    private final ChurchRegistrationRepository churchRepo;
    private final IncomeRepository             incomeRepo;
    private final ExpenseRepository            expenseRepo;
    private final MainSourceRepository         mainSourceRepo;
    private final IncomeService                incomeService;
    private final ExpenseService               expenseService;
    private final LedgerSummaryService         summary;

    public AccountantDashboardController(ChurchRegistrationRepository churchRepo,
                                         IncomeRepository incomeRepo,
                                         ExpenseRepository expenseRepo,
                                         MainSourceRepository mainSourceRepo,
                                         IncomeService incomeService,
                                         ExpenseService expenseService,
                                         LedgerSummaryService summary) {
        this.churchRepo     = churchRepo;
        this.incomeRepo     = incomeRepo;
        this.expenseRepo    = expenseRepo;
        this.mainSourceRepo = mainSourceRepo;
        this.incomeService  = incomeService;
        this.expenseService = expenseService;
        this.summary        = summary;
    }

    @GetMapping("/api/accountant/dashboard")
    public ResponseEntity<Map<String, Object>> dashboard(
            @RequestParam(required = false, defaultValue = "0") int year,
            HttpServletRequest request) {

        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        Map<String, Object> res = new LinkedHashMap<>();

        // ── Church name — resolved from the session tenant only ───────────
        String churchName = churchRepo.findByClientIdAndDeleteFlagFalse(appClientId)
                .map(ChurchRegistration::getChurchName)
                .orElse("");
        res.put("churchName", churchName);

        int currentYear = (year > 0) ? year : LocalDate.now().getYear();

        // ── Total Income — from the monthly summary (kept by DB triggers) ──
        BigDecimal totalIncome = summary.totalIncome(appClientId);
        if (totalIncome == null) totalIncome = BigDecimal.ZERO;
        res.put("totalIncome", totalIncome);

        // ── ALL main sources (A-Z) — drives card list including $0 ones ───
        List<MainSource> allMainSources =
                mainSourceRepo.findActiveByAppUser(appClientId);

        // ── Subcategory totals via native SQL ─────────────────────────────
        // Row: [sub_id, sub_name, main_id, main_name, total]
        // Build: mainId (Integer) → [ mainName, runningTotal, List<subEntry> ]
        Map<Integer, Object[]> mainAgg = new LinkedHashMap<>();

        // Fetched once and reused for the sub-category pie below.
        List<Object[]> subCategoryRows = summary.incomeBySubCategory(appClientId);

        for (Object[] row : subCategoryRows) {
            Integer    mainId   = ((Number) row[2]).intValue();
            String     mainName = (String)  row[3];
            String     subName  = (String)  row[1];
            BigDecimal subTotal = row[4] != null
                                  ? new BigDecimal(row[4].toString())
                                  : BigDecimal.ZERO;

            if (mainAgg.containsKey(mainId)) {
                Object[] main = mainAgg.get(mainId);
                main[1] = ((BigDecimal) main[1]).add(subTotal);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> subs = (List<Map<String, Object>>) main[2];
                Map<String, Object> subEntry = new LinkedHashMap<>();
                subEntry.put("name",  subName);
                subEntry.put("total", subTotal);
                subs.add(subEntry);
            } else {
                List<Map<String, Object>> subs = new ArrayList<>();
                Map<String, Object> subEntry = new LinkedHashMap<>();
                subEntry.put("name",  subName);
                subEntry.put("total", subTotal);
                subs.add(subEntry);
                mainAgg.put(mainId, new Object[]{mainName, subTotal, subs});
            }
        }

        // ── Build category list from ALL main sources (incl. $0 ones) ─────
        List<Map<String, Object>> incomeCatList = allMainSources.stream()
                .map(ms -> {
                    Object[] agg = mainAgg.get(ms.getId());
                    BigDecimal tot = agg != null
                                     ? (BigDecimal) agg[1]
                                     : BigDecimal.ZERO;
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> subs = agg != null
                                                     ? (List<Map<String, Object>>) agg[2]
                                                     : Collections.emptyList();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name",          ms.getSourceName());
                    m.put("total",         tot);
                    m.put("subcategories", subs);
                    return m;
                })
                .collect(Collectors.toList());

        res.put("incomeByCategory", incomeCatList);

        // ── Income by sub-category (for the Income Statistics pie chart) ───
        // Row from LedgerSummaryService.incomeBySubCategory: [sub_id, sub_name, main_id, main_name, total]
        List<Map<String, Object>> incomeSubCatList = new ArrayList<>();
        for (Object[] row : subCategoryRows) {
            String     subName = (String) row[1];
            BigDecimal subTot  = row[4] != null
                                 ? new BigDecimal(row[4].toString())
                                 : BigDecimal.ZERO;
            if (subTot.compareTo(BigDecimal.ZERO) > 0) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name",  subName);
                m.put("total", subTot);
                incomeSubCatList.add(m);
            }
        }
        res.put("incomeBySubCategory", incomeSubCatList);

        // ── Expense by main category — from the summary ────────────────────
        // Row: [main_id, main_name, total]
        Map<Integer, Object[]> expenseMainAgg = new LinkedHashMap<>();
        for (Object[] row : summary.expenseByMainSource(appClientId)) {
            Integer    mainId   = ((Number) row[0]).intValue();
            String     mainName = (String)  row[1];
            BigDecimal total    = row[2] != null
                                  ? new BigDecimal(row[2].toString())
                                  : BigDecimal.ZERO;
            expenseMainAgg.put(mainId, new Object[]{mainName, total});
        }

        BigDecimal totalExpense = expenseMainAgg.values().stream()
                .map(r -> (BigDecimal) r[1])
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Map<String, Object>> expenseCatList = expenseMainAgg.entrySet().stream()
                .sorted((a, b) -> ((BigDecimal) b.getValue()[1])
                        .compareTo((BigDecimal) a.getValue()[1]))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name",  e.getValue()[0]);
                    m.put("total", e.getValue()[1]);
                    return m;
                }).collect(Collectors.toList());

        res.put("totalExpense",      totalExpense);
        res.put("expenseByCategory", expenseCatList);

        // ── Expense by purpose (for the Expense Statistics pie chart) ──────
        // Row from LedgerSummaryService.expenseByPurpose: [purpose_id, purpose_name, total]
        List<Map<String, Object>> expensePurposeList = new ArrayList<>();
        for (Object[] row : summary.expenseByPurpose(appClientId)) {
            String     purposeName = (String) row[1];
            BigDecimal purpTot     = row[2] != null
                                     ? new BigDecimal(row[2].toString())
                                     : BigDecimal.ZERO;
            if (purpTot.compareTo(BigDecimal.ZERO) > 0) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name",  purposeName);
                m.put("total", purpTot);
                expensePurposeList.add(m);
            }
        }
        res.put("expenseByPurpose", expensePurposeList);

        // ── Expense by purpose — current year only (for the pie chart YTD) ─
        List<Map<String, Object>> expensePurposeYTD = new ArrayList<>();
        for (Object[] row : summary.expenseByPurposeForYear(currentYear, appClientId)) {
            String     purposeName = (String) row[1];
            BigDecimal purpTot     = row[2] != null
                                     ? new BigDecimal(row[2].toString())
                                     : BigDecimal.ZERO;
            if (purpTot.compareTo(BigDecimal.ZERO) > 0) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name",  purposeName);
                m.put("total", purpTot);
                expensePurposeYTD.add(m);
            }
        }
        res.put("expenseByPurposeYTD", expensePurposeYTD);

        // ── Net Balance by Main Category (income − expense per fund) ───────
        List<Map<String, Object>> netBalanceCatList = allMainSources.stream()
                .map(ms -> {
                    Object[] incAgg = mainAgg.get(ms.getId());
                    BigDecimal inc = incAgg != null ? (BigDecimal) incAgg[1] : BigDecimal.ZERO;

                    Object[] expAgg = expenseMainAgg.get(ms.getId());
                    BigDecimal exp = expAgg != null ? (BigDecimal) expAgg[1] : BigDecimal.ZERO;

                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name",       ms.getSourceName());
                    m.put("income",     inc);
                    m.put("expense",    exp);
                    m.put("netBalance", inc.subtract(exp));
                    return m;
                })
                .collect(Collectors.toList());

        res.put("netBalanceByCategory", netBalanceCatList);

        // ── Income by month (current year) — native SQL aggregate ─────────
        BigDecimal[] incomeByMonth  = new BigDecimal[12];
        BigDecimal[] expenseByMonth = new BigDecimal[12];
        Arrays.fill(incomeByMonth,  BigDecimal.ZERO);
        Arrays.fill(expenseByMonth, BigDecimal.ZERO);

        for (Object[] row : summary.incomeByMonthForYear(currentYear, appClientId)) {
            int        idx   = ((Number) row[0]).intValue() - 1;  // 1-based → 0-based
            BigDecimal total = row[1] != null
                               ? new BigDecimal(row[1].toString())
                               : BigDecimal.ZERO;
            if (idx >= 0 && idx < 12) incomeByMonth[idx] = total;
        }

        // Expense per month is the fund-by-month aggregate (also used for the Expense
        // Statistics chart below) summed across funds.
        // Row: [month, main_id, main_name, total]
        List<Object[]> expenseMainMonthRows = summary.expenseByMainSourceAndMonthForYear(currentYear, appClientId);
        for (Object[] row : expenseMainMonthRows) {
            int        idx   = ((Number) row[0]).intValue() - 1;
            BigDecimal total = row[3] != null
                               ? new BigDecimal(row[3].toString())
                               : BigDecimal.ZERO;
            if (idx >= 0 && idx < 12) expenseByMonth[idx] = expenseByMonth[idx].add(total);
        }

        res.put("incomeByMonth",  incomeByMonth);
        res.put("expenseByMonth", expenseByMonth);

        // ── Income Statistics: per sub-source per month (current year) ─────
        // Row: [month, sub_id, sub_name, total]
        Map<String, BigDecimal[]> incSubMap = new LinkedHashMap<>();
        for (Object[] row : summary.incomeBySubCategoryAndMonthForYear(currentYear, appClientId)) {
            int        m       = ((Number) row[0]).intValue() - 1;  // 0-based
            String     subName = (String) row[2];
            BigDecimal total   = row[3] != null
                                 ? new BigDecimal(row[3].toString())
                                 : BigDecimal.ZERO;
            incSubMap.computeIfAbsent(subName, k -> new BigDecimal[12]);
            if (m >= 0 && m < 12) incSubMap.get(subName)[m] = total;
        }
        List<Map<String, Object>> incSubSeriesList = new ArrayList<>();
        incSubMap.forEach((name, arr) -> {
            List<BigDecimal> data = new ArrayList<>(12);
            for (int i = 0; i < 12; i++) data.add(arr[i] != null ? arr[i] : BigDecimal.ZERO);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", name);
            s.put("data", data);
            incSubSeriesList.add(s);
        });
        res.put("incomeStatsBySubCategory", incSubSeriesList);

        // ── Expense Statistics: per main source per month (current year) ───
        // Row: [month, main_id, main_name, total]
        Map<String, BigDecimal[]> expMonthMap = new LinkedHashMap<>();
        for (Object[] row : expenseMainMonthRows) {
            int        m        = ((Number) row[0]).intValue() - 1;  // 0-based
            String     mainName = (String) row[2];
            BigDecimal total    = row[3] != null
                                  ? new BigDecimal(row[3].toString())
                                  : BigDecimal.ZERO;
            expMonthMap.computeIfAbsent(mainName, k -> new BigDecimal[12]);
            if (m >= 0 && m < 12) expMonthMap.get(mainName)[m] = total;
        }
        List<Map<String, Object>> expMainSeriesList = new ArrayList<>();
        expMonthMap.forEach((name, arr) -> {
            List<BigDecimal> data = new ArrayList<>(12);
            for (int i = 0; i < 12; i++) data.add(arr[i] != null ? arr[i] : BigDecimal.ZERO);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", name);
            s.put("data", data);
            expMainSeriesList.add(s);
        });
        res.put("expenseStatsByMainCategory", expMainSeriesList);

        // ── Expense Statistics: per purpose per month (current year) ──────
        // Row: [month, purpose_id, purpose_name, total]
        Map<String, BigDecimal[]> expPurpMonthMap = new LinkedHashMap<>();
        for (Object[] row : summary.expenseByPurposeAndMonthForYear(currentYear, appClientId)) {
            int        m           = ((Number) row[0]).intValue() - 1;  // 0-based
            String     purposeName = (String) row[2];
            BigDecimal total       = row[3] != null
                                     ? new BigDecimal(row[3].toString())
                                     : BigDecimal.ZERO;
            expPurpMonthMap.computeIfAbsent(purposeName, k -> new BigDecimal[12]);
            if (m >= 0 && m < 12) expPurpMonthMap.get(purposeName)[m] = total;
        }
        List<Map<String, Object>> expPurpSeriesList = new ArrayList<>();
        expPurpMonthMap.forEach((name, arr) -> {
            List<BigDecimal> data = new ArrayList<>(12);
            for (int i = 0; i < 12; i++) data.add(arr[i] != null ? arr[i] : BigDecimal.ZERO);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", name);
            s.put("data", data);
            expPurpSeriesList.add(s);
        });
        res.put("expenseStatsByPurpose", expPurpSeriesList);

        // ── Recent activity (income + expense combined, latest 10) ────────
        // Each side is fetched with LIMIT 10 (the feed shows 10 in total, so no more
        // than 10 of either kind can appear); it used to load every row of both
        // tables and discard all but 30. Ledger scalability, part A.
        final int recentLimit = 10;
        List<Map<String, Object>> activity = new ArrayList<>();

        try {
            List<Income> recentIncomes =
                    incomeRepo.findRecentForDashboardByAppUser(appClientId, PageRequest.of(0, recentLimit));
            recentIncomes.forEach(inc -> {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("id",     inc.getId());
                a.put("type",   "income");
                a.put("date",   inc.getIncomeDate() != null
                                ? inc.getIncomeDate().toString()
                                : "");
                a.put("label",  inc.getSubSource() != null && inc.getSubSource().getMainSource() != null
                                ? inc.getSubSource().getMainSource().getSourceName() : "Income");
                a.put("sub",    inc.getSubSource() != null
                                ? inc.getSubSource().getSourceName() : "");
                a.put("amount", inc.getAmount() != null ? inc.getAmount() : BigDecimal.ZERO);
                a.put("method", inc.getTransactionType() != null ? inc.getTransactionType().getTypeName() : "");
                activity.add(a);
            });
        } catch (Exception ignored) {
            // Recent income feed is non-critical; skip if unavailable
        }

        List<Expense> recentExpenses =
                expenseRepo.findActivePageByAppUser(appClientId, PageRequest.of(0, recentLimit));
        recentExpenses.forEach(exp -> {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("id",     exp.getId());
            a.put("type",   "expense");
            a.put("date",   exp.getExpenseDate() != null
                            ? exp.getExpenseDate().toString()
                            : "");
            a.put("label",  exp.getPurpose()    != null ? exp.getPurpose().getPurposeName()   : "Expense");
            a.put("sub",    exp.getMainSource() != null ? exp.getMainSource().getSourceName() : "");
            a.put("amount", exp.getAmount()  != null ? exp.getAmount()  : BigDecimal.ZERO);
            a.put("method", exp.getTransactionType() != null ? exp.getTransactionType().getTypeName() : "");
            activity.add(a);
        });

        activity.sort((a, b) -> String.valueOf(b.get("date")).compareTo(String.valueOf(a.get("date"))));
        res.put("recentActivity", activity.stream().limit(recentLimit).collect(Collectors.toList()));

        // ── Quick Add panels (latest 10 templates each) ───────────────────
        res.put("quickAddIncome",  incomeService.getQuickAddIncomes(appClientId));
        res.put("quickAddExpense", expenseService.getQuickAddExpenses(appClientId));

        return ResponseEntity.ok(res);
    }

    /**
     * POST /api/accountant/ledger-summary/rebuild — regenerates this church's
     * {@code ledger_month_summary} rows from its income/expense rows (ledger
     * scalability, part B). The summary is kept exact by database triggers and
     * checked nightly, so this is for operators: after a database restore, or when a
     * dashboard figure is in doubt. Always safe — it only ever rewrites the derived
     * table, never the ledger. Scoped to the session's church.
     */
    @PostMapping("/api/accountant/ledger-summary/rebuild")
    public ResponseEntity<Map<String, Object>> rebuildLedgerSummary(HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        int rows = summary.rebuild(appClientId);
        return ResponseEntity.ok(Map.of("status", "ok", "rows", rows));
    }
}
