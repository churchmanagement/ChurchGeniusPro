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
import org.springframework.web.bind.annotation.ResponseBody;

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

    private static final Logger log = LoggerFactory.getLogger(TestDataController.class);

    private final TestDataService testDataService;
    private final DemoDeletionAuditRepository deletionAuditRepo;

    public TestDataController(TestDataService testDataService,
                              DemoDeletionAuditRepository deletionAuditRepo) {
        this.testDataService = testDataService;
        this.deletionAuditRepo = deletionAuditRepo;
    }

    @ResponseBody
    @PostMapping("/api/serviceadmin/test-data/load")
    public ResponseEntity<?> load(HttpServletRequest request) {
        ResponseEntity<?> deny = requireServiceAdmin(request);
        if (deny != null) return deny;
        try {
            Map<String, Object> result = testDataService.loadSmallDemo();
            log.info("TestDataController: demo tenant {} loaded by service admin", result.get("clientId"));
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "data",   result));
        } catch (Exception e) {
            log.error("TestDataController: load failed", e);
            return ResponseEntity.status(500).body(Map.of(
                    "status",  "error",
                    "message", "Failed to load test data: " + e.getMessage()));
        }
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
