package com.churchgeniuspro.plaid.controller;

import com.churchgeniuspro.plaid.service.PlaidReviewService;
import com.churchgeniuspro.plaid.util.PlaidGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Review-queue endpoints. Every imported Plaid transaction must pass through here
 * before it can reach the ledger. Session-authenticated, tenant-scoped, guarded.
 */
@RestController
@RequestMapping("/api/plaid/review")
public class PlaidReviewController {

    private static final Logger log = LoggerFactory.getLogger(PlaidReviewController.class);

    private final PlaidGuard guard;
    private final PlaidReviewService review;

    public PlaidReviewController(PlaidGuard guard, PlaidReviewService review) {
        this.guard = guard;
        this.review = review;
    }

    /** List staged transactions, optionally filtered by status and direction. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String direction,
                                                    HttpServletRequest req) {
        String deny = guard.requireBankAccess(req);
        if (deny != null) return forbidden(deny);
        String clientId = SessionUtil.getAppClientId(req);
        return ResponseEntity.ok(ok("transactions", review.list(clientId, status, direction)));
    }

    /** Dropdown data (funds, categories, methods) for the review UI. */
    @GetMapping("/lookups")
    public ResponseEntity<Map<String, Object>> lookups(HttpServletRequest req) {
        String deny = guard.requireBankAccess(req);
        if (deny != null) return forbidden(deny);
        String clientId = SessionUtil.getAppClientId(req);
        Map<String, Object> resp = new LinkedHashMap<>(review.lookups(clientId));
        resp.put("status", "success");
        return ResponseEntity.ok(resp);
    }

    /** Edit amount / direction / description / date / mapping of a staged transaction. */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> edit(@PathVariable Integer id,
                                                    @RequestBody Map<String, Object> body,
                                                    HttpServletRequest req) {
        String deny = guard.requireBankAccess(req);
        if (deny != null) return forbidden(deny);
        String clientId = SessionUtil.getAppClientId(req);
        try {
            Map<String, Object> row = review.edit(clientId, id, body, SessionUtil.getUsername(req));
            return ResponseEntity.ok(ok("transaction", row));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable Integer id, HttpServletRequest req) {
        String deny = guard.requireBankAccess(req);
        if (deny != null) return forbidden(deny);
        String clientId = SessionUtil.getAppClientId(req);
        try {
            Map<String, Object> row = review.approve(clientId, id, SessionUtil.getUsername(req));
            return ResponseEntity.ok(ok("transaction", row));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            log.warn("approve failed for {}: {}", id, e.getMessage());
            return error("Could not approve this transaction.");
        }
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable Integer id, HttpServletRequest req) {
        String deny = guard.requireBankAccess(req);
        if (deny != null) return forbidden(deny);
        String clientId = SessionUtil.getAppClientId(req);
        try {
            Map<String, Object> row = review.reject(clientId, id, SessionUtil.getUsername(req));
            return ResponseEntity.ok(ok("transaction", row));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    /** Bulk approve/reject: body { ids:[...], action:"approve"|"reject" }. */
    @PostMapping("/bulk")
    public ResponseEntity<Map<String, Object>> bulk(@RequestBody Map<String, Object> body,
                                                    HttpServletRequest req) {
        String deny = guard.requireBankAccess(req);
        if (deny != null) return forbidden(deny);
        String clientId = SessionUtil.getAppClientId(req);
        String action = body.get("action") != null ? body.get("action").toString() : "";
        if (!"approve".equalsIgnoreCase(action) && !"reject".equalsIgnoreCase(action)) {
            return error("action must be 'approve' or 'reject'.");
        }
        List<Integer> ids = new ArrayList<>();
        Object rawIds = body.get("ids");
        if (rawIds instanceof List<?> l) {
            for (Object o : l) {
                try { ids.add(Integer.valueOf(o.toString())); } catch (NumberFormatException ignored) { }
            }
        }
        if (ids.isEmpty()) return error("No transactions selected.");
        Map<String, Object> result = new LinkedHashMap<>(review.bulk(clientId, ids, action, SessionUtil.getUsername(req)));
        result.put("status", "success");
        return ResponseEntity.ok(result);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Map<String, Object> ok(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "success");
        m.put(key, value);
        return m;
    }

    private ResponseEntity<Map<String, Object>> forbidden(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("message", message);
        return ResponseEntity.status(403).body(m);
    }

    private ResponseEntity<Map<String, Object>> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("message", message);
        return ResponseEntity.status(400).body(m);
    }
}
