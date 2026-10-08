package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.model.PageSlice;
import com.churchgeniuspro.service.DuplicateImportException;
import com.churchgeniuspro.service.IncomeService;
import com.churchgeniuspro.util.MoneyAmounts;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles requests for the Income page and REST API.
 *
 * <h3>Page routes</h3>
 * <ul>
 *   <li>{@code GET /income} → {@code income.html}</li>
 * </ul>
 *
 * <h3>API routes</h3>
 * <ul>
 *   <li>{@code GET  /api/income/contributors}            → members with includeContributions=true</li>
 *   <li>{@code GET  /api/income/sub-sources}              → all active fund sub-categories</li>
 *   <li>{@code GET  /api/income/member/{memberId}/last}   → last income record for a member</li>
 *   <li>{@code GET  /api/income/quick-add}                → latest 10 quick-add templates</li>
 *   <li>{@code GET  /api/income}                          → all active income records</li>
 *   <li>{@code POST /api/income}                          → create income record</li>
 *   <li>{@code PUT  /api/income/{id}}                     → update income record</li>
 *   <li>{@code DELETE /api/income/{id}}                   → soft-delete income record</li>
 * </ul>
 */
@Controller
public class IncomeController {

    private final IncomeService incomeService;

    public IncomeController(IncomeService incomeService) {
        this.incomeService = incomeService;
    }

    // ── Page route ────────────────────────────────────────────────────────

    @GetMapping("/income")
    public String incomePage(HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "accounting.income");
        if (deny != null) return deny;
        return "forward:/income.html";
    }

    // ── Lookups ───────────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/income/contributors")
    public ResponseEntity<List<Map<String, Object>>> getContributors(HttpServletRequest request) {
        if (RoleGuard.requireAccountantOrAdmin(request) != null) return ResponseEntity.status(403).build();
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(incomeService.getContributors(appClientId));
    }

    @ResponseBody
    @GetMapping("/api/income/sub-sources")
    public ResponseEntity<List<Map<String, Object>>> getSubSources(HttpServletRequest request) {
        if (RoleGuard.requireAccountantOrAdmin(request) != null) return ResponseEntity.status(403).build();
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(incomeService.getAllSubSources(appClientId));
    }

    @ResponseBody
    @GetMapping("/api/income/member/{memberId}/last")
    public ResponseEntity<Map<String, Object>> getLastIncomeForMember(
            @PathVariable Integer memberId,
            HttpServletRequest request) {
        if (RoleGuard.requireAccountantOrAdmin(request) != null) return ResponseEntity.status(403).build();
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(incomeService.getLastIncomeForMember(memberId, appClientId));
    }

    // ── Income – List ─────────────────────────────────────────────────────

    /**
     * One page of income records, most-recent first: {@code ?page=0&size=50}
     * (zero-based page; size clamped to 1..200). The body is a plain JSON array —
     * the same shape as before paging — and the {@code X-Has-More} header says
     * whether a further page exists. Ledger scalability, part A.
     */
    @ResponseBody
    @GetMapping("/api/income")
    public ResponseEntity<List<Map<String, Object>>> getRecentIncomes(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest request) {
        if (RoleGuard.requireAccountantOrAdmin(request) != null) return ResponseEntity.status(403).build();
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).build();
        PageSlice<Map<String, Object>> slice = incomeService.getRecentIncomes(appClientId, page, size);
        return ResponseEntity.ok()
                .header("X-Has-More", String.valueOf(slice.hasMore()))
                .body(slice.rows());
    }

    @ResponseBody
    @GetMapping("/api/income/quick-add")
    public ResponseEntity<List<Map<String, Object>>> getQuickAddIncomes(HttpServletRequest request) {
        if (RoleGuard.requireAccountantOrAdmin(request) != null) return ResponseEntity.status(403).build();
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(incomeService.getQuickAddIncomes(appClientId));
    }

    // ── Income – Create ───────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/income")
    public ResponseEntity<Map<String, Object>> createIncome(
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny == null) deny = RoleGuard.requirePermission(request, "accounting.income.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        try {
            Integer    memberId          = toInt(body.get("memberId"));
            Integer    subSourceId       = toInt(body.get("subSourceId"));
            LocalDate  incomeDate        = parseDate(body.get("incomeDate"));
            Integer    transactionTypeId = toInt(body.get("transactionTypeId"));
            String     refNo             = str(body.get("refNo"));
            BigDecimal amount            = toBigDecimal(body.get("amount"));
            String     note              = str(body.get("note"));
            String     guestName         = str(body.get("guestName"));
            boolean    quickAdd          = Boolean.TRUE.equals(body.get("quickAdd"));
            String     importRef         = str(body.get("importRef"));
            boolean    force             = Boolean.TRUE.equals(body.get("force"));

            if (subSourceId == null)       return bad("Fund (sub-category) is required.");
            if (incomeDate == null)        return bad("Date is required.");
            if (transactionTypeId == null) return bad("Method is required.");
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0)
                return bad("Amount must be greater than zero.");

            String createdBy   = SessionUtil.getUsername(request);
            Income saved = incomeService.createIncome(
                    memberId, subSourceId, incomeDate, transactionTypeId, refNo, amount, note, guestName, quickAdd,
                    appClientId, createdBy, importRef, force);
            return ResponseEntity.ok(Map.of("id", saved.getId(), "success", true));
        } catch (DuplicateImportException ex) {
            return duplicateConflict(ex);
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    // ── Income – Update ───────────────────────────────────────────────────

    @ResponseBody
    @PutMapping("/api/income/{id}")
    public ResponseEntity<Map<String, Object>> updateIncome(
            @PathVariable Integer id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny == null) deny = RoleGuard.requirePermission(request, "accounting.income.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        try {
            Integer    memberId          = toInt(body.get("memberId"));
            Integer    subSourceId       = toInt(body.get("subSourceId"));
            LocalDate  incomeDate        = parseDate(body.get("incomeDate"));
            Integer    transactionTypeId = toInt(body.get("transactionTypeId"));
            String     refNo             = str(body.get("refNo"));
            BigDecimal amount            = toBigDecimal(body.get("amount"));
            String     note              = str(body.get("note"));
            String     guestName         = str(body.get("guestName"));
            boolean    quickAdd          = Boolean.TRUE.equals(body.get("quickAdd"));

            if (subSourceId == null)       return bad("Fund (sub-category) is required.");
            if (incomeDate == null)        return bad("Date is required.");
            if (transactionTypeId == null) return bad("Method is required.");
            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0)
                return bad("Amount must be greater than zero.");

            String updatedBy = SessionUtil.getUsername(request);
            Income saved = incomeService.updateIncome(
                    id, memberId, subSourceId, incomeDate, transactionTypeId, refNo, amount, note, guestName, quickAdd,
                    appClientId, updatedBy);
            return ResponseEntity.ok(Map.of("id", saved.getId(), "success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    // ── Income – Unstar (remove from Recurring panel) ────────────────────

    @ResponseBody
    @PatchMapping("/api/income/{id}/unstar")
    public ResponseEntity<Map<String, Object>> unstarIncome(@PathVariable Integer id,
                                                            HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny == null) deny = RoleGuard.requirePermission(request, "accounting.income.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        try {
            incomeService.unstarIncome(id, appClientId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    // ── Income – Soft-Delete ──────────────────────────────────────────────

    @ResponseBody
    @DeleteMapping("/api/income/{id}")
    public ResponseEntity<Map<String, Object>> deleteIncome(@PathVariable Integer id,
                                                            HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny == null) deny = RoleGuard.requirePermission(request, "accounting.income.delete");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        try {
            incomeService.deleteIncome(id, appClientId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    /** Financial audit H8: an import (Bank Import / Plaid) collided with an existing row. */
    private ResponseEntity<Map<String, Object>> duplicateConflict(DuplicateImportException ex) {
        Map<String, Object> dup = new LinkedHashMap<>();
        dup.put("type", ex.type);
        dup.put("id", ex.existingId);
        dup.put("date", ex.existingDate);
        dup.put("amount", ex.existingAmount);
        dup.put("refNo", ex.existingRefNo);
        dup.put("hard", ex.hard);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.getMessage());
        body.put("duplicate", dup);
        return ResponseEntity.status(409).body(body);
    }

    private Integer toInt(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.intValue();
        try { return Integer.parseInt(val.toString()); } catch (NumberFormatException e) { return null; }
    }

    private LocalDate parseDate(Object val) {
        if (val == null) return null;
        try { return LocalDate.parse(val.toString()); } catch (Exception e) { return null; }
    }

    /** Financial audit M1: rejects (doesn't silently round) sub-cent amounts. */
    private BigDecimal toBigDecimal(Object val) {
        return MoneyAmounts.parseStrict(val);
    }

    private String str(Object o) { return o == null ? null : o.toString().trim(); }
}
