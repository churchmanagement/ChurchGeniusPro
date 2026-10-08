package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.TransactionType;
import com.churchgeniuspro.service.TransactionTypeService;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;

/**
 * Handles requests for the Transaction Type admin page and REST API.
 *
 * <h3>Page route</h3>
 * <ul>
 *   <li>{@code GET /transactiontype} → {@code transactionType.html}</li>
 * </ul>
 *
 * <h3>REST API</h3>
 * <ul>
 *   <li>{@code GET    /api/transaction-types}      → list all transaction types</li>
 *   <li>{@code POST   /api/transaction-types}      → create a transaction type</li>
 *   <li>{@code PUT    /api/transaction-types/{id}} → update a transaction type</li>
 *   <li>{@code DELETE /api/transaction-types/{id}} → soft-delete a transaction type</li>
 * </ul>
 */
@Controller
public class TransactionTypeController {

    private final TransactionTypeService transactionTypeService;

    public TransactionTypeController(TransactionTypeService transactionTypeService) {
        this.transactionTypeService = transactionTypeService;
    }

    // ── Page route ────────────────────────────────────────────────────────

    @GetMapping("/transactiontype")
    public String transactionTypePage(HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "accounting.settings");
        if (deny != null) return deny;
        return "forward:/transactionType.html";
    }

    // ── REST ──────────────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/transaction-types")
    public ResponseEntity<List<Map<String, Object>>> getAll(HttpServletRequest request) {
        if (RoleGuard.requireAccountantOrAdmin(request) != null) return ResponseEntity.status(403).build();
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(transactionTypeService.getAll(appClientId));
    }

    @ResponseBody
    @PostMapping("/api/transaction-types")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        String name = body.get("typeName");
        if (name == null || name.isBlank()) {
            return bad("Transaction type name is required.");
        }
        try {
            TransactionType saved = transactionTypeService.create(name, appClientId);
            return ResponseEntity.ok(Map.of("id", saved.getId(), "success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    @ResponseBody
    @PutMapping("/api/transaction-types/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Integer id,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        String name = body.get("typeName");
        if (name == null || name.isBlank()) {
            return bad("Transaction type name is required.");
        }
        try {
            TransactionType saved = transactionTypeService.update(id, name, appClientId);
            return ResponseEntity.ok(Map.of("id", saved.getId(), "success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    @ResponseBody
    @DeleteMapping("/api/transaction-types/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Integer id,
                                                      HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        try {
            transactionTypeService.delete(id, appClientId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
