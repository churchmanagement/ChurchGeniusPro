package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.DemoDeletionAudit;
import com.churchgeniuspro.repository.DemoDeletionAuditRepository;
import com.churchgeniuspro.service.TestDataService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Service Admin–facing REST endpoints for the Test Data loader on
 * {@code /serviceadminhome}.
 *
 * <p>All three endpoints require a logged-in service admin session — the
 * same {@code serviceAdminId} attribute set by
 * {@link ServiceAdminController#login}. Endpoints are kept under
 * {@code /api/serviceadmin/test-data/*} so they live with the other
 * service-admin APIs and don't collide with tenant-scoped routes.
 */
@Controller
public class TestDataController {

    private final com.churchgeniuspro.service.DemoAccessService demoAccessService;

    private static final Logger log = LoggerFactory.getLogger(TestDataController.class);

    private final TestDataService testDataService;
    private final DemoDeletionAuditRepository deletionAuditRepo;

    public TestDataController(TestDataService testDataService,
                              DemoDeletionAuditRepository deletionAuditRepo,
                              com.churchgeniuspro.service.DemoAccessService demoAccessService) {
        this.demoAccessService = demoAccessService;
        this.testDataService = testDataService;
        this.deletionAuditRepo = deletionAuditRepo;
    }

    /**
     * Loads a demo tenant.
     *
     * <p>The body is optional and every field in it is optional, so the existing
     * no-argument caller keeps working unchanged:
     * <ul>
     *   <li>{@code planCode} — a code from Subscription Plans; omitted means the first
     *       active plan. Never a name this class knows: it is validated against the
     *       {@code subscription_plan} table by the service.</li>
     *   <li>{@code expiresOn} — {@code YYYY-MM-DD}, the last day these accounts may sign
     *       in. Takes precedence over {@code expiresInDays}.</li>
     *   <li>{@code expiresInDays} — a whole number of days from today, which is what the
     *       30/60-day presets send.</li>
     * </ul>
     */
    @ResponseBody
    @PostMapping("/api/serviceadmin/test-data/load")
    public ResponseEntity<?> load(@RequestBody(required = false) Map<String, Object> body,
                                  HttpServletRequest request) {
        ResponseEntity<?> deny = requireServiceAdmin(request);
        if (deny != null) return deny;
        try {
            String planCode    = str(body, "planCode");
            LocalDate expiresOn = parseExpiry(body);

            Map<String, Object> result = testDataService.loadSmallDemo(planCode, expiresOn);
            // Remember the expiry and reminder choices against the tenant, then give
            // every login just created its own access window.
            try {
                String newClientId = String.valueOf(result.get("clientId"));
                Integer expiryDays = body != null && body.get("expiryDays") != null
                        ? Integer.valueOf(String.valueOf(body.get("expiryDays"))) : null;
                String reminderCsv = body != null ? str(body, "reminderDays") : null;
                demoAccessService.configureNewTenant(newClientId, expiresOn, expiryDays, reminderCsv);
                demoAccessService.backfill();
            } catch (Exception cfgEx) {
                // A settings hiccup must not fail a load that already succeeded;
                // the windows are created on first sign-in regardless.
                log.warn("TestDataController: demo access setup deferred — {}", cfgEx.getMessage());
            }
            log.info("TestDataController: demo tenant {} loaded by service admin on plan {} expiring {}",
                    result.get("clientId"), planCode, expiresOn);
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "data",   result));
        } catch (IllegalArgumentException iae) {
            // Unknown plan, no active plans configured, or an expiry in the past — all
            // caller mistakes with an actionable message, not server faults.
            return ResponseEntity.status(400).body(Map.of(
                    "status",  "error",
                    "message", iae.getMessage()));
        } catch (Exception e) {
            log.error("TestDataController: load failed", e);
            return ResponseEntity.status(500).body(Map.of(
                    "status",  "error",
                    "message", "Failed to load test data: " + e.getMessage()));
        }
    }

    /**
     * Views, changes or extends one demo tenant's subscription.
     *
     * <p>Updates that tenant's existing {@code service_client} row in place, so extending
     * an expiry can never leave a second subscription record behind. Both fields are
     * optional: send only {@code expiresOn} to extend, only {@code planCode} to switch
     * plan, or neither to just read the current state back.
     */
    @ResponseBody
    @PutMapping("/api/serviceadmin/test-data/{clientId}/subscription")
    public ResponseEntity<?> updateSubscription(@PathVariable String clientId,
                                                @RequestBody(required = false) Map<String, Object> body,
                                                HttpServletRequest request) {
        ResponseEntity<?> deny = requireServiceAdmin(request);
        if (deny != null) return deny;
        try {
            String planCode     = str(body, "planCode");
            LocalDate expiresOn = parseExpiry(body);

            Map<String, Object> subscription =
                    testDataService.updateDemoSubscription(clientId, planCode, expiresOn);
            log.info("TestDataController: demo subscription for {} set to {}", clientId, subscription);
            return ResponseEntity.ok(Map.of(
                    "status",       "success",
                    "clientId",     clientId,
                    "subscription", subscription));
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.status(400).body(Map.of(
                    "status",  "error",
                    "message", iae.getMessage()));
        } catch (Exception e) {
            log.error("TestDataController: subscription update failed for {}", clientId, e);
            return ResponseEntity.status(500).body(Map.of(
                    "status",  "error",
                    "message", "Failed to update the demo subscription: " + e.getMessage()));
        }
    }

    // ── Request-body helpers ──────────────────────────────────────────────

    private static String str(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Resolves the expiry from {@code expiresOn} (an explicit date) or {@code expiresInDays}
     * (the 30/60-day presets), returning {@code null} when neither was supplied so the
     * service applies its own default.
     *
     * <p>A malformed value is rejected rather than ignored: silently falling back to the
     * default would hand the admin a demo tenant with a different expiry than the one they
     * typed, and they would have no reason to look.
     */
    private static LocalDate parseExpiry(Map<String, Object> body) {
        String explicit = str(body, "expiresOn");
        if (explicit != null) {
            try {
                return LocalDate.parse(explicit);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "Expiration date '" + explicit + "' is not a valid date. Use YYYY-MM-DD.");
            }
        }
        String days = str(body, "expiresInDays");
        if (days != null) {
            try {
                long n = Long.parseLong(days);
                if (n < 1) {
                    throw new IllegalArgumentException(
                            "Expiration must be at least 1 day from today.");
                }
                return LocalDate.now().plusDays(n);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "'" + days + "' is not a whole number of days.");
            }
        }
        return null;
    }

    @ResponseBody
    @PostMapping("/api/serviceadmin/test-data/clear/{clientId}")
    public ResponseEntity<?> clear(@PathVariable String clientId, HttpServletRequest request) {
        ResponseEntity<?> deny = requireServiceAdmin(request);
        if (deny != null) return deny;
        try {
            // Capture the acting service admin for the deletion audit log.
            String performedBy = null;
            HttpSession session = request.getSession(false);
            if (session != null) {
                Object u = session.getAttribute("serviceAdminUsername");
                performedBy = (u != null) ? u.toString()
                        : ("serviceAdminId:" + session.getAttribute("serviceAdminId"));
            }
            Map<String, Object> result = testDataService.clearDemoClient(clientId, performedBy);
            log.info("TestDataController: demo tenant {} cleared by service admin {}", clientId, performedBy);

            // Persist a queryable audit record (outside the delete transaction, so a
            // logging hiccup never rolls back a completed deletion).
            try {
                DemoDeletionAudit audit = new DemoDeletionAudit();
                audit.setDeletedAt(java.time.LocalDateTime.now());
                audit.setTenantName((String) result.get("churchName"));
                audit.setClientId(clientId);
                audit.setAppClientId(clientId);
                audit.setPerformedBy(performedBy == null ? "(unknown service admin)" : performedBy);
                Object total = result.get("total");
                audit.setTotalDeleted(total instanceof Number ? ((Number) total).intValue() : 0);
                audit.setCountsJson(new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(result.get("deleted")));
                deletionAuditRepo.save(audit);
            } catch (Exception auditEx) {
                log.warn("TestDataController: failed to persist deletion audit for {}: {}",
                        clientId, auditEx.toString());
            }
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "data",   result));
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.status(400).body(Map.of(
                    "status",  "error",
                    "message", iae.getMessage()));
        } catch (Exception e) {
            log.error("TestDataController: clear failed for {}", clientId, e);
            return ResponseEntity.status(500).body(Map.of(
                    "status",  "error",
                    "message", "Failed to clear test data: " + e.getMessage()));
        }
    }

    // ── One-off: fill demo data into trial accounts created before it existed ──

    /** Dry run — lists every existing trial account and which areas would be filled. Writes nothing. */
    @ResponseBody
    @GetMapping("/api/serviceadmin/test-data/trial-backfill/preview")
    public ResponseEntity<?> trialBackfillPreview(HttpServletRequest request) {
        ResponseEntity<?> deny = requireServiceAdmin(request);
        if (deny != null) return deny;
        return ResponseEntity.ok(Map.of("status", "success", "trials", testDataService.previewTrialBackfill()));
    }

    /**
     * Fills the empty demo areas of existing trial accounts. Body {@code {"clientIds":[...]}}
     * limits it to those accounts; omitted means every trial account. Each account runs
     * in its own transaction, so one failure is reported and the rest still run.
     */
    @ResponseBody
    @PostMapping("/api/serviceadmin/test-data/trial-backfill")
    public ResponseEntity<?> trialBackfill(@RequestBody(required = false) Map<String, Object> body,
                                           HttpServletRequest request) {
        ResponseEntity<?> deny = requireServiceAdmin(request);
        if (deny != null) return deny;
        List<String> ids = new java.util.ArrayList<>();
        Object raw = body == null ? null : body.get("clientIds");
        if (raw instanceof List<?> l) {
            for (Object o : l) if (o != null && !o.toString().isBlank()) ids.add(o.toString().trim());
        } else {
            for (Map<String, Object> t : testDataService.listTrialTenants()) ids.add(String.valueOf(t.get("clientId")));
        }
        HttpSession session = request.getSession(false);
        Object who = session == null ? null : session.getAttribute("serviceAdminUsername");
        List<Map<String, Object>> results = new java.util.ArrayList<>();
        int ok = 0, failed = 0;
        for (String id : ids) {
            Map<String, Object> r = new java.util.LinkedHashMap<>();
            r.put("clientId", id);
            try {
                r.put("added", testDataService.backfillTrialDemoData(id));
                r.put("status", "done");
                ok++;
            } catch (IllegalArgumentException iae) {
                r.put("status", "skipped"); r.put("message", iae.getMessage());
            } catch (Exception e) {
                log.error("TestDataController: trial backfill failed for {}", id, e);
                r.put("status", "failed"); r.put("message", e.getMessage());
                failed++;
            }
            results.add(r);
        }
        log.info("TestDataController: trial demo backfill by {} — {} done, {} failed, {} requested",
                who, ok, failed, ids.size());
        return ResponseEntity.ok(Map.of("status", "success", "results", results, "done", ok, "failed", failed));
    }

    @ResponseBody
    @GetMapping("/api/serviceadmin/test-data/list")
    public ResponseEntity<?> list(HttpServletRequest request) {
        ResponseEntity<?> deny = requireServiceAdmin(request);
        if (deny != null) return deny;
        List<Map<String, Object>> demos = testDataService.listDemoClients();
        return ResponseEntity.ok(Map.of(
                "status",      "success",
                "demoClients", demos));
    }

    /**
     * Returns the most recent demo-tenant deletion audit records for the
     * Service-Admin viewer (newest first, capped at 100).
     */
    @ResponseBody
    @GetMapping("/api/serviceadmin/test-data/deletion-audit")
    public ResponseEntity<?> deletionAudit(HttpServletRequest request) {
        ResponseEntity<?> deny = requireServiceAdmin(request);
        if (deny != null) return deny;
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (DemoDeletionAudit a : deletionAuditRepo.findTop100ByOrderByDeletedAtDesc()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("deletedAt",    a.getDeletedAt() != null ? a.getDeletedAt().toString() : null);
            m.put("tenantName",   a.getTenantName());
            m.put("clientId",     a.getClientId());
            m.put("appClientId",  a.getAppClientId());
            m.put("performedBy",  a.getPerformedBy());
            m.put("totalDeleted", a.getTotalDeleted());
            m.put("counts",       a.getCountsJson());
            rows.add(m);
        }
        return ResponseEntity.ok(Map.of("status", "success", "audits", rows));
    }

    /**
     * Diagnostic helper used by the Test Data card to confirm exactly what
     * is in the signup table for a generated credential. Returns whether a
     * signup row was found, its stored password, the active/deleted/locked
     * flags, and whether a service_client row exists for the related tenant.
     * Only callable by a logged-in service admin so plaintext passwords
     * never leak to anonymous callers.
     */
    @ResponseBody
    @GetMapping("/api/serviceadmin/test-data/verify-login")
    public ResponseEntity<?> verifyLogin(
            @org.springframework.web.bind.annotation.RequestParam String username,
            HttpServletRequest request) {
        ResponseEntity<?> deny = requireServiceAdmin(request);
        if (deny != null) return deny;
        try {
            Map<String, Object> info = testDataService.verifySignupLogin(username);
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "data",   info));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "status",  "error",
                    "message", e.getMessage()));
        }
    }

    /**
     * Resets the password for a single demo-tenant login and returns the new
     * plaintext. Used by the "Reset & reveal" action for legacy demo tenants
     * whose original password was never stored (only its BCrypt hash). Strictly
     * limited to {@code DEMO-} tenants by the service layer.
     */
    @ResponseBody
    @PostMapping("/api/serviceadmin/test-data/reset-password")
    public ResponseEntity<?> resetPassword(
            @org.springframework.web.bind.annotation.RequestParam String username,
            HttpServletRequest request) {
        ResponseEntity<?> deny = requireServiceAdmin(request);
        if (deny != null) return deny;
        try {
            Map<String, Object> data = testDataService.resetDemoPassword(username);
            log.info("TestDataController: demo password reset for {}", username);
            return ResponseEntity.ok(Map.of("status", "success", "data", data));
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.status(400).body(Map.of("status", "error", "message", iae.getMessage()));
        } catch (Exception e) {
            log.error("TestDataController: reset-password failed for {}", username, e);
            return ResponseEntity.status(500).body(Map.of(
                    "status", "error", "message", "Failed to reset password: " + e.getMessage()));
        }
    }

    // ── Auth guard ────────────────────────────────────────────────────────

    /**
     * Returns a 401 ResponseEntity if there's no live service admin session.
     * Otherwise returns {@code null} so the caller proceeds. We use the same
     * session attribute ({@code serviceAdminId}) the existing
     * {@link ServiceAdminController#login} sets.
     */
    private ResponseEntity<?> requireServiceAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("serviceAdminId") == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "status",  "error",
                    "message", "Service admin login required."));
        }
        return null;
    }
}
