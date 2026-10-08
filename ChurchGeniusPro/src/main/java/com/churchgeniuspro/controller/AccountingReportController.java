package com.churchgeniuspro.controller;

import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.PurposeRepository;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for the five Accounting Report endpoints.
 *
 * <p>All date params accept ISO-8601 strings (YYYY-MM-DD).
 * Optional filter params (memberId, subSourceId, purposeId) accept an integer ID
 * or are omitted / null to mean "no filter".
 * Native-SQL queries are used throughout to avoid Hibernate 6 JPQL limitations.
 */
@RestController
@RequestMapping("/api/reports")
public class AccountingReportController {

    private final IncomeRepository              incomeRepo;
    private final ExpenseRepository             expenseRepo;
    private final PurposeRepository             purposeRepo;
    private final ChurchRegistrationRepository  churchRepo;

    /**
     * The editable wording around the contribution table. Field-injected and
     * null-checked so this controller's existing construction sites (and their
     * tests) are unchanged; when it is absent the letter falls back to the
     * built-in wording, which is what every church saw before it was editable.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.service.FinancialReportLetterService letterService;

    /** Test seam — supply the letter text without a Spring context. */
    public void setLetterService(com.churchgeniuspro.service.FinancialReportLetterService s) {
        this.letterService = s;
    }

    public AccountingReportController(IncomeRepository             incomeRepo,
                                      ExpenseRepository            expenseRepo,
                                      PurposeRepository            purposeRepo,
                                      ChurchRegistrationRepository churchRepo) {
        this.incomeRepo  = incomeRepo;
        this.expenseRepo = expenseRepo;
        this.purposeRepo = purposeRepo;
        this.churchRepo  = churchRepo;
    }

    // ── Shared helpers ─────────────────────────────────────────────────────

    /**
     * Convert a native-SQL date object to an ISO-8601 string (YYYY-MM-DD).
     * Hibernate 7 + the PostgreSQL JDBC driver can return DATE columns as
     * java.time.LocalDate, java.sql.Date, or java.util.Date depending on the
     * driver version and column type — all three are handled here.
     */
    private static String toDateStr(Object obj) {
        if (obj == null) return "";
        if (obj instanceof java.time.LocalDate ld)    return ld.toString();
        if (obj instanceof java.sql.Date sd)           return sd.toLocalDate().toString();
        if (obj instanceof java.util.Date ud)          return new java.sql.Date(ud.getTime()).toLocalDate().toString();
        return obj.toString();   // fallback: trust the object's own toString
    }

    private static BigDecimal toBD(Object obj) {
        return obj != null ? new BigDecimal(obj.toString()) : BigDecimal.ZERO;
    }

    private static String str(Object obj) {
        return obj != null ? obj.toString() : "";
    }

    /**
     * Role + session gate shared by every report endpoint — mirrors the report
     * page routes (requireAccountantOrAdmin). Returns null when the caller may proceed.
     */
    private static ResponseEntity<Map<String, Object>> gate(HttpServletRequest request) {
        if (RoleGuard.requireAccountantOrAdmin(request) != null)
            return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        if (SessionUtil.getAppClientId(request) == null)
            return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        return null;
    }

    // ── Lookup: Purposes list ──────────────────────────────────────────────
    /**
     * Returns all active purposes for the Expense Report filter dropdown.
     * Response: [{id, name}, ...]
     */
    @GetMapping("/purposes")
    public ResponseEntity<?> getPurposes(HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> denied = gate(request);
        if (denied != null) return denied;
        String appClientId = SessionUtil.getAppClientId(request);
        return ResponseEntity.ok(purposeRepo.findActiveByAppUser(appClientId).stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",   p.getId());
                    m.put("name", p.getPurposeName());
                    return m;
                })
                .collect(Collectors.toList()));
    }

    // ── 1. Income Report ───────────────────────────────────────────────────
    /**
     * Returns individual income transaction rows for a date range, ordered by date.
     * Scoped to the logged-in org via session appClientId so that records from other
     * tenants are never returned.
     * Optional: memberId filters to a single contributor;
     *           subSourceId filters to a single sub-source/fund.
     * Query params: startDate, endDate (required); memberId, subSourceId (optional).
     *
     * Response shape:
     *   { startDate, endDate, memberSelected (bool), transactions[], grandTotal }
     * Each transaction: { date, contributor (only when all-members), fund, method, refNo, note, amount }
     */
    @GetMapping("/income")
    public ResponseEntity<?> incomeReport(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(required = false) Integer memberId,
            @RequestParam(required = false) Integer subSourceId,
            HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> denied = gate(request);
        if (denied != null) return denied;

        Date sd = Date.valueOf(LocalDate.parse(startDate));
        Date ed = Date.valueOf(LocalDate.parse(endDate));

        // Scope to the current org — never leak another tenant's transactions
        String appClientId = SessionUtil.getAppClientId(request);

        // [id, income_date, first_name, last_name, guest_name, sub_name, main_name, amount, method, ref_no, note]
        List<Object[]> rows = incomeRepo.reportIncomeTransactionsFiltered(sd, ed, appClientId, memberId, subSourceId);

        List<Map<String, Object>> transactions = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;

        for (Object[] r : rows) {
            Map<String, Object> tx = new LinkedHashMap<>();
            tx.put("id",          r[0] != null ? ((Number) r[0]).longValue() : null);
            tx.put("date",        toDateStr(r[1]));
            // r[2]=firstName, r[3]=lastName, r[4]=guest_name — use guest_name when no member linked
            String firstName  = str(r[2]);
            String lastName   = str(r[3]);
            String guestName  = str(r[4]);
            String contributor;
            if (!lastName.isBlank() || !firstName.isBlank()) {
                contributor = (lastName + (!lastName.isBlank() && !firstName.isBlank() ? ", " : "") + firstName).trim();
            } else if (!guestName.isBlank()) {
                contributor = guestName;
            } else {
                contributor = "—";
            }
            tx.put("contributor", contributor);
            tx.put("fund",        str(r[5]));                       // sub_name  (was r[4])
            tx.put("method",      str(r[8]));                       // method    (was r[7])
            tx.put("refNo",       str(r[9]));                       // ref_no    (was r[8])
            tx.put("note",        str(r[10]));                      // note      (was r[9])
            BigDecimal amt = toBD(r[7]);                            // amount    (was r[6])
            tx.put("amount", amt);
            transactions.add(tx);
            grandTotal = grandTotal.add(amt);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("startDate",      startDate);
        res.put("endDate",        endDate);
        res.put("memberSelected", memberId != null);
        res.put("transactions",   transactions);
        res.put("grandTotal",     grandTotal);
        return ResponseEntity.ok(res);
    }

    // ── 2. Expense Report ──────────────────────────────────────────────────
    /**
     * Returns expense entries grouped by main-source for a date range.
     * Scoped to the logged-in org via session appClientId.
     * Optional: purposeId filters to a single expense purpose.
     * Query params: startDate, endDate (required); purposeId (optional).
     */
    @GetMapping("/expense")
    public ResponseEntity<?> expenseReport(
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(required = false) Integer purposeId,
            HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> denied = gate(request);
        if (denied != null) return denied;

        Date sd = Date.valueOf(LocalDate.parse(startDate));
        Date ed = Date.valueOf(LocalDate.parse(endDate));
        String appClientId = SessionUtil.getAppClientId(request);

        // [id, main_name, purpose_name, expense_date, amount, method, ref_no, note]
        List<Object[]> rows = expenseRepo.reportExpenseFiltered(sd, ed, appClientId, purposeId);

        Map<String, Map<String, Object>> mainMap = new LinkedHashMap<>();

        for (Object[] r : rows) {
            Long       expId    = r[0] != null ? ((Number) r[0]).longValue() : null;
            String     mainName = str(r[1]);
            String     purpose  = str(r[2]);
            String     dateStr  = toDateStr(r[3]);
            BigDecimal amount   = toBD(r[4]);
            String     method   = str(r[5]);
            String     refNo    = str(r[6]);
            String     note     = str(r[7]);

            mainMap.computeIfAbsent(mainName, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name",      mainName);
                m.put("entries",   new ArrayList<Map<String, Object>>());
                m.put("mainTotal", BigDecimal.ZERO);
                return m;
            });
            Map<String, Object> cat = mainMap.get(mainName);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) cat.get("entries");
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id",      expId);
            entry.put("purpose", purpose);
            entry.put("date",    dateStr);
            entry.put("amount",  amount);
            entry.put("method",  method);
            entry.put("refNo",   refNo);
            entry.put("note",    note);
            entries.add(entry);
            cat.put("mainTotal", ((BigDecimal) cat.get("mainTotal")).add(amount));
        }

        BigDecimal grandTotal = mainMap.values().stream()
                .map(m -> (BigDecimal) m.get("mainTotal"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("startDate",  startDate);
        res.put("endDate",    endDate);
        res.put("categories", new ArrayList<>(mainMap.values()));
        res.put("grandTotal", grandTotal);
        return ResponseEntity.ok(res);
    }

    // ── 3. Date Range Transactions ─────────────────────────────────────────
    /**
     * Returns all income and expense transactions for a date range,
     * separated into two lists with a combined summary.
     * Scoped to the logged-in org via session appClientId.
     */
    @GetMapping("/transactions")
    public ResponseEntity<?> transactionsReport(
            @RequestParam String startDate,
            @RequestParam String endDate,
            HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> denied = gate(request);
        if (denied != null) return denied;

        Date sd = Date.valueOf(LocalDate.parse(startDate));
        Date ed = Date.valueOf(LocalDate.parse(endDate));
        String appClientId = SessionUtil.getAppClientId(request);

        // Income: [id, income_date, first_name, last_name, guest_name, sub_name, main_name,
        //          amount, method, ref_no, note]
        List<Object[]> incRows = incomeRepo.reportIncomeTransactionsInRange(sd, ed, appClientId);
        List<Map<String, Object>> incList = new ArrayList<>();
        BigDecimal totalIncome = BigDecimal.ZERO;
        for (Object[] r : incRows) {
            BigDecimal amt = toBD(r[7]);                            // amount (was r[6])
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",       r[0] != null ? ((Number) r[0]).longValue() : null);
            m.put("date",     toDateStr(r[1]));
            String fn = str(r[2]), ln = str(r[3]), gn = str(r[4]);
            String memberName;
            if (!ln.isBlank() || !fn.isBlank()) {
                memberName = (ln + (!ln.isBlank() && !fn.isBlank() ? ", " : "") + fn).trim();
            } else if (!gn.isBlank()) {
                memberName = gn;
            } else {
                memberName = "—";
            }
            m.put("member",   memberName);
            m.put("mainCat",  str(r[6]));                          // main_name (was r[5])
            m.put("subCat",   str(r[5]));                          // sub_name  (was r[4])
            m.put("amount",   amt);
            m.put("method",   str(r[8]));                          // method    (was r[7])
            m.put("refNo",    str(r[9]));                          // ref_no    (was r[8])
            m.put("note",     str(r[10]));                         // note      (was r[9])
            incList.add(m);
            totalIncome = totalIncome.add(amt);
        }

        // Expense: [id, expense_date, main_name, purpose_name,
        //           amount, method, ref_no, note]
        List<Object[]> expRows = expenseRepo.reportExpenseTransactionsInRange(sd, ed, appClientId);
        List<Map<String, Object>> expList = new ArrayList<>();
        BigDecimal totalExpense = BigDecimal.ZERO;
        for (Object[] r : expRows) {
            BigDecimal amt = toBD(r[4]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",      r[0] != null ? ((Number) r[0]).longValue() : null);
            m.put("date",    toDateStr(r[1]));
            m.put("mainCat", str(r[2]));
            m.put("purpose", str(r[3]));
            m.put("amount",  amt);
            m.put("method",  str(r[5]));
            m.put("refNo",   str(r[6]));
            m.put("note",    str(r[7]));
            expList.add(m);
            totalExpense = totalExpense.add(amt);
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("startDate",    startDate);
        res.put("endDate",      endDate);
        res.put("income",       incList);
        res.put("expense",      expList);
        res.put("totalIncome",  totalIncome);
        res.put("totalExpense", totalExpense);
        res.put("netBalance",   totalIncome.subtract(totalExpense));
        return ResponseEntity.ok(res);
    }

    // ── 4. Year-End Tax Report ─────────────────────────────────────────────
    /**
     * Returns annual giving data per member for the given year.
     * Includes both a grouped summary (contributions) and individual entries (entries)
     * needed for letter-format PDFs.
     * The church name is resolved from the {@code church_registration} table via
     * the session tenant so the PDF letter always shows the correct registered name.
     * Optional: memberId filters to a single member.
     * Query params: year (required); memberId, guestName (optional). A client-supplied
     * {@code clientId} is ignored — the tenant always comes from the session.
     */
    @GetMapping("/tax")
    public ResponseEntity<?> taxReport(
            @RequestParam int year,
            @RequestParam(required = false) Integer memberId,
            @RequestParam(required = false) String  guestName,
            HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> denied = gate(request);
        if (denied != null) return denied;

        String appClientId = SessionUtil.getAppClientId(request);

        // Resolve church name from DB via the session tenant only
        String churchName = churchRepo.findByClientIdAndDeleteFlagFalse(appClientId)
                            .map(cr -> cr.getChurchName() != null ? cr.getChurchName() : "")
                            .orElse("");

        // Normalise: blank string → null so SQL CAST(:x IS NULL) short-circuits correctly
        String guestNameFilter = (guestName != null && !guestName.isBlank()) ? guestName.trim() : null;

        // ── Grouped summary (contributions per sub-source) ─────────────────
        // [member_id (null for guests), first_name, last_name, guest_name,
        //  sub_name, main_name, total, cnt]
        List<Object[]> summaryRows = incomeRepo.reportAnnualIncomeByMemberFiltered(
                year, appClientId, memberId, guestNameFilter);

        // Key: memberId (for members) or "guest::<guestName>" (for guest rows)
        Map<String, Map<String, Object>> memberMap = new LinkedHashMap<>();

        // Financial audit M5: income with neither a matched member nor a guest
        // name (an unattributed lump sum — e.g. an uncounted plate-offering
        // total) has no one a contribution statement could be addressed to.
        // Tallied here and reported separately instead of being folded into a
        // "guest::" bucket keyed by an empty name.
        BigDecimal unattributedTotal = BigDecimal.ZERO;
        int        unattributedCount = 0;

        for (Object[] r : summaryRows) {
            boolean isGuest   = r[0] == null;
            String  firstName = str(r[1]);
            String  lastName  = str(r[2]);
            String  gName     = str(r[3]);
            String  subName   = str(r[4]);
            String  mainName  = str(r[5]);
            BigDecimal total  = toBD(r[6]);

            if (isGuest && gName.isBlank()) {
                unattributedTotal = unattributedTotal.add(total);
                unattributedCount++;
                continue;
            }

            String mapKey;
            if (!isGuest) {
                mapKey = "member::" + ((Number) r[0]).intValue();
            } else {
                mapKey = "guest::" + gName;
            }

            memberMap.computeIfAbsent(mapKey, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                if (!isGuest) {
                    m.put("id",        ((Number) r[0]).intValue());
                    m.put("name",      (lastName.isBlank() ? "" : lastName + ", ") + firstName);
                    m.put("firstName", firstName);
                    m.put("lastName",  lastName);
                } else {
                    m.put("id",        null);
                    m.put("name",      gName);
                    m.put("firstName", gName);
                    m.put("lastName",  "");
                    // Financial audit M5: a guest is matched by name text alone —
                    // the only identity signal a walk-in/anonymous gift carries,
                    // so two different people who happen to share a name are
                    // combined into one statement. This flag lets a reviewer (or
                    // a future UI) know to double-check before mailing rather
                    // than hiding that the match is unverified.
                    m.put("nameOnly",  true);
                }
                m.put("contributions", new ArrayList<Map<String, Object>>());
                m.put("entries",       new ArrayList<Map<String, Object>>());
                m.put("memberTotal",   BigDecimal.ZERO);
                m.put("address1",  "");
                m.put("address2",  "");
                m.put("city",      "");
                m.put("state",     "");
                m.put("pinCode",   "");
                return m;
            });
            Map<String, Object> member = memberMap.get(mapKey);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> contribs = (List<Map<String, Object>>) member.get("contributions");
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("mainCategory", mainName);
            c.put("subCategory",  subName);
            c.put("total",        total);
            contribs.add(c);
            member.put("memberTotal", ((BigDecimal) member.get("memberTotal")).add(total));
        }

        // ── Individual entries (for letter-format PDF) ─────────────────────
        // [member_id (null for guests), first_name, last_name, guest_name,
        //  address1, address2, city, state, pin_code,
        //  income_date, main_name, sub_name, amount, method]
        List<Object[]> entryRows = incomeRepo.reportIncomeEntriesForTaxYear(
                year, appClientId, memberId, guestNameFilter);

        for (Object[] r : entryRows) {
            boolean isGuest = r[0] == null;
            String  gName   = str(r[3]);
            // Financial audit M5: same exclusion as the summary loop above —
            // an unattributed entry never got a memberMap bucket to land in.
            if (isGuest && gName.isBlank()) continue;
            String  mapKey  = isGuest ? "guest::" + gName
                                      : "member::" + ((Number) r[0]).intValue();

            Map<String, Object> member = memberMap.get(mapKey);
            if (member == null) continue;

            if ("".equals(member.get("address1"))) {
                member.put("address1", str(r[4]));
                member.put("address2", str(r[5]));
                member.put("city",     str(r[6]));
                member.put("state",    str(r[7]));
                member.put("pinCode",  str(r[8]));
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) member.get("entries");
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("date",         toDateStr(r[9]));
            e.put("mainCategory", str(r[10]));
            e.put("subCategory",  str(r[11]));
            e.put("amount",       toBD(r[12]));
            e.put("method",       str(r[13]));
            entries.add(e);
        }

        BigDecimal grandTotal = memberMap.values().stream()
                .map(m -> (BigDecimal) m.get("memberTotal"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("year",        year);
        res.put("memberId",    memberId);
        res.put("guestName",   guestNameFilter);
        res.put("churchName",  churchName);
        res.put("members",     new ArrayList<>(memberMap.values()));
        res.put("grandTotal",  grandTotal);
        // Financial audit M5: income excluded from the statements above because
        // it has neither a matched member nor a guest name — surfaced here so
        // it stays visible to staff instead of silently vanishing from the
        // report, or worse, becoming a letter addressed to nobody.
        res.put("unattributedTotal", unattributedTotal);
        res.put("unattributedCount", unattributedCount);
        // The letter wording around the contribution table, this church's own if it
        // has edited it. Carried in the report payload rather than fetched
        // separately so the letters cannot render before their text arrives.
        res.putAll(letterText(appClientId, churchName, year));
        return ResponseEntity.ok(res);
    }

    // ── 4a. The editable wording around the contribution table ─────────────

    /**
     * This church's letter text, as authored, for the editor on the report page.
     *
     * <p>Same gate as the report itself: whoever may generate the statements may
     * word them. The tenant comes from the session, so this can only ever return
     * the caller's own church's text.
     */
    @GetMapping("/letter-content")
    public ResponseEntity<?> getLetterContent(HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> denied = gate(request);
        if (denied != null) return denied;
        if (letterService == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Letter text is unavailable."));
        }
        return ResponseEntity.ok(letterService.forEditing(SessionUtil.getAppClientId(request)));
    }

    /** Saves this church's wording. Sanitised in the service — see RichTextSanitizer. */
    @PostMapping("/letter-content")
    public ResponseEntity<?> saveLetterContent(@RequestBody Map<String, String> body,
                                               HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> denied = gate(request);
        if (denied != null) return denied;
        if (letterService == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Letter text is unavailable."));
        }
        try {
            letterService.save(SessionUtil.getAppClientId(request),
                               body.get("introHtml"),
                               body.get("closingHtml"),
                               body.get("signatureName"),
                               body.get("signatureTitle"),
                               SessionUtil.getUsername(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(letterService.forEditing(SessionUtil.getAppClientId(request)));
    }

    /** Drops this church's wording so the original letter text is used again. */
    @PostMapping("/letter-content/reset")
    public ResponseEntity<?> resetLetterContent(HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> denied = gate(request);
        if (denied != null) return denied;
        if (letterService == null) {
            return ResponseEntity.status(503).body(Map.of("error", "Letter text is unavailable."));
        }
        letterService.resetToDefault(SessionUtil.getAppClientId(request));
        return ResponseEntity.ok(letterService.forEditing(SessionUtil.getAppClientId(request)));
    }

    /** Letter wording for a report payload; the built-in default when unavailable. */
    private Map<String, String> letterText(String appClientId, String churchName, Object year) {
        return letterService == null
                ? com.churchgeniuspro.service.FinancialReportLetterService.defaults(churchName, year)
                : letterService.rendered(appClientId, churchName, year);
    }

    /**
     * Returns the combined list of member contributors and distinct guest-name contributors
     * for the given year. Used to populate the tax-report filter dropdown.
     * Response: [{type:"member", id, name}, {type:"guest", guestName, name}, ...]
     */
    @GetMapping("/contributors")
    public ResponseEntity<?> getContributors(
            @RequestParam(required = false) Integer year,
            HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> denied = gate(request);
        if (denied != null) return denied;

        String appClientId = SessionUtil.getAppClientId(request);
        List<Map<String, Object>> result = new ArrayList<>();

        // Member contributors (from the income/contributors endpoint logic)
        // Reuse incomeRepo: find distinct member IDs that have income records for the year
        // We'll use a simpler approach: get all member contributors then filter by year if needed
        java.util.List<String> guestNames = incomeRepo.findDistinctGuestNames(appClientId, year);
        for (String gn : guestNames) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type",      "guest");
            m.put("id",        null);
            m.put("guestName", gn);
            m.put("name",      gn);
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    // ── 5. Financial Report ────────────────────────────────────────────────
    /**
     * Returns income (by main + sub categories) and expense (by main category)
     * totals for the given year, with net balance.
     * Scoped to the logged-in org via session appClientId.
     */
    @GetMapping("/financial")
    public ResponseEntity<?> financialReport(
            @RequestParam int year,
            HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> denied = gate(request);
        if (denied != null) return denied;

        String appClientId = SessionUtil.getAppClientId(request);

        // Income: [main_name, sub_name, total]
        List<Object[]> incRows = incomeRepo.reportFinancialIncomeByYear(year, appClientId);
        Map<String, Map<String, Object>> mainMap = new LinkedHashMap<>();

        for (Object[] r : incRows) {
            String     mainName = str(r[0]);
            String     subName  = str(r[1]);
            BigDecimal total    = toBD(r[2]);

            mainMap.computeIfAbsent(mainName, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name",          mainName);
                m.put("subcategories", new ArrayList<Map<String, Object>>());
                m.put("mainTotal",     BigDecimal.ZERO);
                return m;
            });
            Map<String, Object> cat = mainMap.get(mainName);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> subs = (List<Map<String, Object>>) cat.get("subcategories");
            Map<String, Object> sub = new LinkedHashMap<>();
            sub.put("name",  subName);
            sub.put("total", total);
            subs.add(sub);
            cat.put("mainTotal", ((BigDecimal) cat.get("mainTotal")).add(total));
        }

        BigDecimal totalIncome = mainMap.values().stream()
                .map(m -> (BigDecimal) m.get("mainTotal"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Expense: [main_name, purpose_name, total] — nested by main category → purposes
        List<Object[]> expRows = expenseRepo.reportFinancialExpenseByYearWithPurpose(year, appClientId);
        Map<String, Map<String, Object>> expMap = new LinkedHashMap<>();
        BigDecimal totalExpense = BigDecimal.ZERO;
        for (Object[] r : expRows) {
            String     mainName    = str(r[0]);
            String     purposeName = str(r[1]);
            BigDecimal amt         = toBD(r[2]);

            expMap.computeIfAbsent(mainName, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name",      mainName);
                m.put("purposes",  new ArrayList<Map<String, Object>>());
                m.put("mainTotal", BigDecimal.ZERO);
                return m;
            });
            Map<String, Object> cat = expMap.get(mainName);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> purposes = (List<Map<String, Object>>) cat.get("purposes");
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name",  purposeName);
            p.put("total", amt);
            purposes.add(p);
            cat.put("mainTotal", ((BigDecimal) cat.get("mainTotal")).add(amt));
            totalExpense = totalExpense.add(amt);
        }
        List<Map<String, Object>> expList = new ArrayList<>(expMap.values());

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("year",               year);
        res.put("incomeCategories",   new ArrayList<>(mainMap.values()));
        res.put("totalIncome",        totalIncome);
        res.put("expenseCategories",  expList);
        res.put("totalExpense",       totalExpense);
        res.put("netBalance",         totalIncome.subtract(totalExpense));
        return ResponseEntity.ok(res);
    }
}
