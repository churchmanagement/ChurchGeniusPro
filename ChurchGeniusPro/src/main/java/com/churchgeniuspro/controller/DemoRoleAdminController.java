package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.DemoClientSettings;
import com.churchgeniuspro.hibernate.DemoRoleAccess;
import com.churchgeniuspro.service.DemoAccessService;
import com.churchgeniuspro.service.DemoReminderScheduler;
import com.churchgeniuspro.service.TestDataService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * Service-Admin management of demo/test roles: add, reissue, re-date, block, and
 * the per-tenant delivery switches.
 *
 * <p>Guarded on the same {@code role=ServiceAdmin} session attribute as the other
 * service-admin APIs. Nothing here touches demo DATA — the one operation that
 * sounds like it might, Reset, only reissues the login.
 */
@RestController
public class DemoRoleAdminController {

    private static final Logger log = LoggerFactory.getLogger(DemoRoleAdminController.class);

    private final DemoAccessService     demoAccess;
    private final TestDataService       testData;
    private final DemoReminderScheduler reminders;

    public DemoRoleAdminController(DemoAccessService demoAccess,
                                   TestDataService testData,
                                   DemoReminderScheduler reminders) {
        this.demoAccess = demoAccess;
        this.testData   = testData;
        this.reminders  = reminders;
    }

    private boolean isServiceAdmin(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null && "ServiceAdmin".equals(s.getAttribute("role"));
    }
    private ResponseEntity<Map<String, Object>> denied() {
        return ResponseEntity.status(401).body(Map.of("error", "Not signed in as Service Admin"));
    }
    private static ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
    private static LocalDate date(Object v) {
        if (v == null || String.valueOf(v).isBlank()) return null;
        return LocalDate.parse(String.valueOf(v).substring(0, 10));
    }

    /* ── list ───────────────────────────────────────────────────────────── */

    @GetMapping("/api/serviceadmin/demo-roles")
    public ResponseEntity<?> list(HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        // Backfill on read: opening the screen is the natural moment to give any
        // pre-existing demo login a window, and it is idempotent.
        int created = demoAccess.backfill();
        return ResponseEntity.ok(Map.of(
                "roles",      demoAccess.listForAdmin(),
                "backfilled", created));
    }

    /* ── the roles this server can actually build ───────────────────────── */

    /**
     * The role list the Add Role dialog offers.
     *
     * <p>Served rather than hardcoded in the page because the three login shapes
     * are not interchangeable: a server that predates them would take "Church" or
     * "Child Portal" and quietly build an ordinary staff login with that string
     * written into {@code app_user.role}. The screen falls back to its own list on
     * 404, so an older server keeps working — it just offers what it can build.
     */
    @GetMapping("/api/serviceadmin/demo-roles/options")
    public ResponseEntity<?> options(HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return ResponseEntity.ok(Map.of("roles", TestDataService.DEMO_ROLES));
    }

    /* ── add a role to an existing tenant ───────────────────────────────── */

    @PostMapping("/api/serviceadmin/demo-roles")
    public ResponseEntity<?> add(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        String clientId = Objects.toString(body.get("clientId"), "").trim();
        String role     = Objects.toString(body.get("role"), "User").trim();
        if (clientId.isEmpty()) return bad("A demo tenant (Client ID) is required.");

        LocalDate end;
        try { end = date(body.get("endDate")); }
        catch (Exception e) { return bad("End date must be YYYY-MM-DD."); }
        if (end == null) {
            // Default to the expiry chosen when this tenant's data was loaded.
            DemoClientSettings cfg = demoAccess.settingsFor(clientId);
            end = cfg.getDefaultEndDate();
        }
        if (end != null && end.isBefore(LocalDate.now())) return bad("End date is in the past.");

        try {
            Map<String, Object> created = testData.addDemoRole(clientId, role);
            DemoRoleAccess w = demoAccess.record(
                    (Integer) created.get("signupId"), clientId,
                    (String) created.get("username"), (String) created.get("role"),
                    (String) created.get("memberName"), end);
            log.info("Demo role added to {} by service admin — {} ({})",
                     clientId, created.get("username"), role);
            Map<String, Object> out = new LinkedHashMap<>(created);
            out.put("accessId",  w.getId());
            out.put("endDate",   String.valueOf(w.getEndDate()));
            out.put("status",    w.getStatus());
            out.put("startDate", String.valueOf(w.getStartDate()));
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (Exception e) {
            log.error("Demo role add failed for {}", clientId, e);
            return ResponseEntity.status(500).body(Map.of("error", "Could not add the role: " + e.getMessage()));
        }
    }

    /* ── reset (reissue login only) ─────────────────────────────────────── */

    @PostMapping("/api/serviceadmin/demo-roles/{accessId}/reset")
    public ResponseEntity<?> reset(@PathVariable Long accessId, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        try {
            DemoRoleAccess w = demoAccess.forAccessId(accessId);
            Map<String, Object> reissued = testData.reissueDemoLogin(w.getUsername());
            DemoRoleAccess updated = demoAccess.reissue(accessId, (String) reissued.get("username"));
            log.info("Demo login reissued for {} by service admin — new username {}",
                     w.getClientId(), reissued.get("username"));
            Map<String, Object> out = new LinkedHashMap<>(reissued);
            out.put("endDate", String.valueOf(updated.getEndDate()));
            out.put("status",  updated.getStatus());
            out.put("message", "New credentials issued. Access runs to "
                             + updated.getEndDate() + ". Demo data was not changed.");
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (Exception e) {
            log.error("Demo login reset failed for access {}", accessId, e);
            return ResponseEntity.status(500).body(Map.of("error", "Could not reset: " + e.getMessage()));
        }
    }

    /* ── end date / block ───────────────────────────────────────────────── */

    @PutMapping("/api/serviceadmin/demo-roles/{accessId}/end-date")
    public ResponseEntity<?> endDate(@PathVariable Long accessId,
                                     @RequestBody Map<String, Object> body,
                                     HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        try {
            LocalDate d = date(body.get("endDate"));
            DemoRoleAccess w = demoAccess.setEndDate(accessId, d);
            return ResponseEntity.ok(Map.of("endDate", String.valueOf(w.getEndDate()),
                                            "status",  w.getStatus()));
        } catch (Exception e) { return bad("Could not set the end date: " + e.getMessage()); }
    }

    @PutMapping("/api/serviceadmin/demo-roles/{accessId}/blocked")
    public ResponseEntity<?> blocked(@PathVariable Long accessId,
                                     @RequestBody Map<String, Object> body,
                                     HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        try {
            boolean b = Boolean.parseBoolean(String.valueOf(body.get("blocked")));
            DemoRoleAccess w = demoAccess.setBlocked(accessId, b);
            return ResponseEntity.ok(Map.of("status", w.getStatus()));
        } catch (Exception e) { return bad("Could not change the block: " + e.getMessage()); }
    }

    /* ── per-tenant delivery switches and reminder intervals ────────────── */

    @PutMapping("/api/serviceadmin/demo-clients/{clientId}/sending")
    public ResponseEntity<?> sending(@PathVariable String clientId,
                                     @RequestBody Map<String, Object> body,
                                     HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        Boolean sms   = body.get("allowSms")   == null ? null : Boolean.parseBoolean(String.valueOf(body.get("allowSms")));
        Boolean email = body.get("allowEmail") == null ? null : Boolean.parseBoolean(String.valueOf(body.get("allowEmail")));
        DemoClientSettings c = demoAccess.setSending(clientId, sms, email);
        log.info("Demo client {} delivery set by service admin — sms={} email={}",
                 clientId, c.getAllowSms(), c.getAllowEmail());
        return ResponseEntity.ok(Map.of("allowSms", c.getAllowSms(), "allowEmail", c.getAllowEmail()));
    }

    @PutMapping("/api/serviceadmin/demo-clients/{clientId}/reminders")
    public ResponseEntity<?> reminderDays(@PathVariable String clientId,
                                          @RequestBody Map<String, Object> body,
                                          HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        DemoClientSettings c = demoAccess.setReminderDays(clientId, Objects.toString(body.get("days"), ""));
        return ResponseEntity.ok(Map.of("reminderDays", c.getReminderDays()));
    }

    /** Runs the reminder sweep immediately, for testing the configuration. */
    @PostMapping("/api/serviceadmin/demo-roles/run-reminders")
    public ResponseEntity<?> runReminders(@RequestBody(required = false) Map<String, Object> body,
                                          HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        LocalDate on;
        try { on = body == null ? LocalDate.now() : Optional.ofNullable(date(body.get("asOf"))).orElse(LocalDate.now()); }
        catch (Exception e) { return bad("asOf must be YYYY-MM-DD."); }
        int sent = reminders.sweep(on);
        return ResponseEntity.ok(Map.of("sent", sent, "asOf", String.valueOf(on)));
    }
}
