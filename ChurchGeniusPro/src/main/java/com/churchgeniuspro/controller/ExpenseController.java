package com.churchgeniuspro.controller;

import com.churchgeniuspro.model.PageSlice;
import com.churchgeniuspro.service.DuplicateImportException;
import com.churchgeniuspro.service.ExpenseService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles page routing and REST API for the Expense section.
 */
@Controller
public class ExpenseController {

    private final ExpenseService expenseService;

    public ExpenseController(ExpenseService expenseService) {
        this.expenseService = expenseService;
    }

    // ── Page route ────────────────────────────────────────────────────────

    @GetMapping("/expense")
    public String expensePage(HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "accounting.expense");
        if (deny != null) return deny;
        return "forward:/expense.html";
    }

    // ── Dropdown data ─────────────────────────────────────────────────────

    /** GET /api/expense/purposes — all active purposes for the Expense dropdown. */
    @GetMapping("/api/expense/purposes")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getPurposes(HttpServletRequest request) {
        if (RoleGuard.requireAccountantOrAdmin(request) != null) return ResponseEntity.status(403).build();
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(expenseService.getAllPurposes(appClientId));
    }

    /** GET /api/expense/funds — all active main sources for the Fund dropdown. */
    @GetMapping("/api/expense/funds")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getFunds(HttpServletRequest request) {
        if (RoleGuard.requireAccountantOrAdmin(request) != null) return ResponseEntity.status(403).build();
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(expenseService.getAllFunds(appClientId));
    }

    /** GET /api/expense/purpose/{purposeId}/last — last expense for a purpose. */
    @GetMapping("/api/expense/purpose/{purposeId}/last")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getLastForPurpose(
            @PathVariable Integer purposeId,
            HttpServletRequest request) {
        if (RoleGuard.requireAccountantOrAdmin(request) != null) return ResponseEntity.status(403).build();
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(expenseService.getLastExpenseForPurpose(purposeId, appClientId));
    }

    // ── CRUD ──────────────────────────────────────────────────────────────

    /**
     * GET /api/expense — one page of active expense records, most-recent first:
     * {@code ?page=0&size=50} (zero-based page; size clamped to 1..200). The body is
     * a plain JSON array — the same shape as before paging — and the
     * {@code X-Has-More} header says whether a further page exists.
     * Ledger scalability, part A.
     */
    @GetMapping("/api/expense")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getAll(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest request) {
        if (RoleGuard.requireAccountantOrAdmin(request) != null) return ResponseEntity.status(403).build();
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).build();
        PageSlice<Map<String, Object>> slice = expenseService.getRecentExpenses(appClientId, page, size);
        return ResponseEntity.ok()
                .header("X-Has-More", String.valueOf(slice.hasMore()))
                .body(slice.rows());
    }

    /** GET /api/expense/quick-add — latest 10 quick-add expense templates. */
    @GetMapping("/api/expense/quick-add")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getQuickAdd(HttpServletRequest request) {
        if (RoleGuard.requireAccountantOrAdmin(request) != null) return ResponseEntity.status(403).build();
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(expenseService.getQuickAddExpenses(appClientId));
    }

    /** POST /api/expense — create a new expense record. */
    @PostMapping("/api/expense")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny == null) deny = RoleGuard.requirePermission(request, "accounting.expense.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        try {
            Integer    purposeId         = toInt(body.get("purposeId"));
            Integer    mainSourceId      = toInt(body.get("mainSourceId"));
            LocalDate  expenseDate       = parseDate(body.get("expenseDate"));
            Integer    transactionTypeId = toInt(body.get("transactionTypeId"));
            String     refNo             = toString(body.get("refNo"));
            BigDecimal amount            = toBigDecimal(body.get("amount"));
            String     note              = toString(body.get("note"));
            boolean    quickAdd          = Boolean.TRUE.equals(body.get("quickAdd"));
            String     importRef         = toString(body.get("importRef"));
            boolean    force             = Boolean.TRUE.equals(body.get("force"));

            if (purposeId == null)        return bad("Expense category is required.");
            if (mainSourceId == null)     return bad("Fund is required.");
            if (expenseDate == null)      return bad("Date is required.");
            if (transactionTypeId == null) return bad("Method is required.");
            if (amount == null)           return bad("Amount is required.");
            if (amount.compareTo(BigDecimal.ZERO) <= 0) return bad("Amount must be greater than zero.");

            String createdBy   = SessionUtil.getUsername(request);
            com.churchgeniuspro.hibernate.Expense saved = expenseService.createExpense(purposeId, mainSourceId, expenseDate,
                                         transactionTypeId, refNo, amount, note, quickAdd, appClientId, createdBy,
                                         importRef, force);
            Map<String, Object> resp = new java.util.LinkedHashMap<>();
            resp.put("message", "Expense saved successfully.");
            if (saved != null && saved.getId() != null) resp.put("id", saved.getId());
            return ResponseEntity.ok(resp);
        } catch (DuplicateImportException ex) {
            return duplicateConflict(ex);
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Unexpected error: " + ex.getMessage()));
        }
    }

    /** PUT /api/expense/{id} — update an existing expense record. */
    @PutMapping("/api/expense/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> update(@PathVariable Integer id,
                                                       @RequestBody Map<String, Object> body,
                                                       HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny == null) deny = RoleGuard.requirePermission(request, "accounting.expense.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        try {
            Integer    purposeId         = toInt(body.get("purposeId"));
            Integer    mainSourceId      = toInt(body.get("mainSourceId"));
            LocalDate  expenseDate       = parseDate(body.get("expenseDate"));
            Integer    transactionTypeId = toInt(body.get("transactionTypeId"));
            String     refNo             = toString(body.get("refNo"));
            BigDecimal amount            = toBigDecimal(body.get("amount"));
            String     note              = toString(body.get("note"));
            boolean    quickAdd          = Boolean.TRUE.equals(body.get("quickAdd"));

            if (purposeId == null)         return bad("Expense category is required.");
            if (mainSourceId == null)      return bad("Fund is required.");
            if (expenseDate == null)       return bad("Date is required.");
            if (transactionTypeId == null) return bad("Method is required.");
            if (amount == null)            return bad("Amount is required.");
            if (amount.compareTo(BigDecimal.ZERO) <= 0) return bad("Amount must be greater than zero.");

            String updatedBy = SessionUtil.getUsername(request);
            expenseService.updateExpense(id, purposeId, mainSourceId, expenseDate,
                                          transactionTypeId, refNo, amount, note, quickAdd, appClientId, updatedBy);
            return ResponseEntity.ok(Map.of("message", "Expense updated successfully."));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Unexpected error: " + ex.getMessage()));
        }
    }

    /** PATCH /api/expense/{id}/unstar — remove from Recurring Expense panel (sets quickAdd=false). */
    @PatchMapping("/api/expense/{id}/unstar")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> unstar(@PathVariable Integer id,
                                                      HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny == null) deny = RoleGuard.requirePermission(request, "accounting.expense.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        try {
            expenseService.unstarExpense(id, appClientId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    /** DELETE /api/expense/{id} — soft-delete an expense record. */
    @DeleteMapping("/api/expense/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Integer id,
                                                      HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny == null) deny = RoleGuard.requirePermission(request, "accounting.expense.delete");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        try {
            expenseService.deleteExpense(id, appClientId);
            return ResponseEntity.ok(Map.of("message", "Expense deleted."));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        } catch (Exception ex) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Unexpected error: " + ex.getMessage()));
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
        try { return Integer.parseInt(val.toString().trim()); }
        catch (NumberFormatException e) { return null; }
    }

    /** Financial audit M1: rejects (doesn't silently round) sub-cent amounts. */
    private BigDecimal toBigDecimal(Object val) {
        return MoneyAmounts.parseStrict(val);
    }

    private LocalDate parseDate(Object val) {
        if (val == null) return null;
        try { return LocalDate.parse(val.toString()); } catch (Exception e) { return null; }
    }

    private String toString(Object val) {
        return val == null ? null : val.toString().trim();
    }
}
