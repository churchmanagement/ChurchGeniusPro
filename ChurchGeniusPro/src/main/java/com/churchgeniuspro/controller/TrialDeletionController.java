package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.DemoDeletionAudit;
import com.churchgeniuspro.repository.DemoDeletionAuditRepository;
import com.churchgeniuspro.service.TrialDeletionService;
import com.churchgeniuspro.service.TrialRegistrationLinkService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deleting trial data from the Service Admin screen.
 *
 * <p>Every endpoint is under {@code /api/serviceadmin/}, so
 * {@code ServiceAdminAuthFilter} has already refused anyone without a service
 * admin session before a method here runs; the extra check below is the same
 * belt-and-braces the sibling admin controllers keep.
 *
 * <p><b>Soft and permanent are separate endpoints, never a flag.</b> A boolean in
 * a request body is one typo away from destroying what the caller meant to hide,
 * and the two operations deserve to be distinguishable in a server log without
 * reading the payload.
 *
 * @see TrialDeletionService for what each strength actually does
 */
@RestController
public class TrialDeletionController {

    private static final Logger log = LoggerFactory.getLogger(TrialDeletionController.class);

    private final TrialDeletionService deletion;
    private final TrialRegistrationLinkService links;
    private final DemoDeletionAuditRepository auditRepo;

    public TrialDeletionController(TrialDeletionService deletion,
                                   TrialRegistrationLinkService links,
                                   DemoDeletionAuditRepository auditRepo) {
        this.deletion  = deletion;
        this.links     = links;
        this.auditRepo = auditRepo;
    }

    /* ══ Registration links ══════════════════════════════════════════════ */

    /** The soft-deleted links, for the screen's "Show deleted" toggle. */
    @GetMapping("/api/serviceadmin/trial-links/deleted")
    public ResponseEntity<?> listDeletedLinks(HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return ResponseEntity.ok(Map.of("status", "success", "data", links.listForAdmin(true)));
    }

    @PostMapping("/api/serviceadmin/trial-links/soft-delete")
    public ResponseEntity<?> softDeleteLinks(@RequestBody Map<String, Object> body,
                                             HttpServletRequest req) {
        return runLinks(req, body, false, false);
    }

    @PostMapping("/api/serviceadmin/trial-links/permanent-delete")
    public ResponseEntity<?> permanentDeleteLinks(@RequestBody Map<String, Object> body,
                                                  HttpServletRequest req) {
        return runLinks(req, body, true, false);
    }

    @PostMapping("/api/serviceadmin/trial-links/restore")
    public ResponseEntity<?> restoreLinks(@RequestBody Map<String, Object> body,
                                          HttpServletRequest req) {
        return runLinks(req, body, false, true);
    }

    /**
     * One handler for the three link operations.
     *
     * <p>Body: {@code {"ids":[1,2,3]}} for a selection, or {@code {"all":true}} for
     * everything. "All" means every row currently in the list the caller is looking
     * at — the live ones for delete, which is why restore has no all-form: bringing
     * back rows nobody has looked at is not something to offer behind one click.
     */
    private ResponseEntity<?> runLinks(HttpServletRequest req, Map<String, Object> body,
                                       boolean permanent, boolean restore) {
        if (!isServiceAdmin(req)) return denied();
        String by = actor(req);
        boolean all = Boolean.TRUE.equals(body.get("all"));
        List<Integer> ids = intIds(body.get("ids"));

        if (!all && ids.isEmpty()) return bad("Select at least one registration link.");

        try {
            TrialDeletionService.Outcome out;
            if (restore) {
                out = deletion.restoreLinks(ids, by);
            } else if (permanent) {
                out = all ? deletion.permanentDeleteAllLinks(by) : deletion.permanentDeleteLinks(ids, by);
            } else {
                out = all ? deletion.softDeleteAllLinks(by) : deletion.softDeleteLinks(ids, by);
            }
            if (permanent) recordAudit(out, by, "trial-registration-links");
            return ok(out, message(out));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (Exception e) {
            log.error("Trial link deletion failed", e);
            return error("Could not complete the deletion: " + e.getMessage());
        }
    }

    /* ══ Trial accounts ══════════════════════════════════════════════════ */

    @PostMapping("/api/serviceadmin/trial-accounts/{clientId}/soft-delete")
    public ResponseEntity<?> softDeleteAccount(@PathVariable String clientId, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        try {
            TrialDeletionService.Outcome out = deletion.softDeleteAccount(clientId, actor(req));
            return ok(out, "Trial account " + clientId + " was hidden. Nothing was destroyed — "
                         + "it can be restored.");
        } catch (IllegalArgumentException e) { return bad(e.getMessage()); }
          catch (Exception e) { log.error("Trial soft delete failed for {}", clientId, e);
                                return error("Could not hide the account: " + e.getMessage()); }
    }

    @PostMapping("/api/serviceadmin/trial-accounts/{clientId}/restore")
    public ResponseEntity<?> restoreAccount(@PathVariable String clientId, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        try {
            TrialDeletionService.Outcome out = deletion.restoreAccount(clientId, actor(req));
            return ok(out, "Trial account " + clientId + " was restored.");
        } catch (IllegalArgumentException e) { return bad(e.getMessage()); }
          catch (Exception e) { return error("Could not restore the account: " + e.getMessage()); }
    }

    /**
     * Removes a trial account and everything it owns.
     *
     * <p>Requires {@code confirm} to equal the Client ID. The screen asks the admin
     * to type it, and the server insists on it too — a confirmation that lives only
     * in the browser is no confirmation at all for an endpoint that can be called
     * directly.
     */
    @PostMapping("/api/serviceadmin/trial-accounts/{clientId}/permanent-delete")
    public ResponseEntity<?> permanentDeleteAccount(@PathVariable String clientId,
                                                    @RequestBody(required = false) Map<String, Object> body,
                                                    HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        String confirm = body == null ? null : String.valueOf(body.get("confirm"));
        if (confirm == null || !clientId.equals(confirm.trim())) {
            return bad("Type the Trial ID (" + clientId + ") to confirm permanent deletion.");
        }
        try {
            String by = actor(req);
            Map<String, Object> result = deletion.permanentDeleteAccount(clientId, by);
            recordTenantAudit(clientId, result, by);
            log.warn("Trial account {} permanently deleted by {}", clientId, by);
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Trial account " + clientId + " and all of its data were permanently deleted.",
                    "data", result));
        } catch (IllegalArgumentException e) { return bad(e.getMessage()); }
          catch (Exception e) { log.error("Trial permanent delete failed for {}", clientId, e);
                                return error("Could not delete the account: " + e.getMessage()); }
    }

    /* ══ Individual roles ════════════════════════════════════════════════ */

    @PostMapping("/api/serviceadmin/trial-roles/soft-delete")
    public ResponseEntity<?> softDeleteRoles(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        return runRoles(req, body, false);
    }

    @PostMapping("/api/serviceadmin/trial-roles/permanent-delete")
    public ResponseEntity<?> permanentDeleteRoles(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        return runRoles(req, body, true);
    }

    /** Body: {@code {"accessIds":[12,13]}} — one id or several, the same handler. */
    private ResponseEntity<?> runRoles(HttpServletRequest req, Map<String, Object> body, boolean permanent) {
        if (!isServiceAdmin(req)) return denied();
        List<Long> ids = longIds(body.get("accessIds"));
        if (ids.isEmpty()) return bad("Select at least one role.");
        try {
            String by = actor(req);
            TrialDeletionService.Outcome out = permanent
                    ? deletion.permanentDeleteRoles(ids, by)
                    : deletion.softDeleteRoles(ids, by);
            if (permanent) recordAudit(out, by, "trial-roles");
            return ok(out, message(out));
        } catch (IllegalArgumentException e) {
            // The Church-login refusal lands here, and its wording is written for
            // the admin reading it.
            return bad(e.getMessage());
        } catch (Exception e) {
            log.error("Trial role deletion failed", e);
            return error("Could not complete the deletion: " + e.getMessage());
        }
    }

    /* ══ Helpers ═════════════════════════════════════════════════════════ */

    private static String message(TrialDeletionService.Outcome out) {
        String verb = switch (out.operation()) {
            case "soft-delete"      -> "hidden";
            case "permanent-delete" -> "permanently deleted";
            default                 -> "restored";
        };
        if (out.affected() == 0) return "Nothing to do — nothing matched.";
        return out.affected() + " record" + (out.affected() == 1 ? " was " : "s were ") + verb + ".";
    }

    private ResponseEntity<?> ok(TrialDeletionService.Outcome out, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status",    "success");
        m.put("message",   message);
        m.put("operation", out.operation());
        m.put("scope",     out.scope());
        m.put("affected",  out.affected());
        if (!out.counts().isEmpty()) m.put("counts", out.counts());
        return ResponseEntity.ok(m);
    }

    /** A queryable audit row, in the table demo-tenant deletion already writes to. */
    private void recordAudit(TrialDeletionService.Outcome out, String by, String kind) {
        try {
            DemoDeletionAudit a = new DemoDeletionAudit();
            a.setDeletedAt(java.time.LocalDateTime.now());
            a.setTenantName(kind);
            a.setClientId(out.scope());
            a.setAppClientId(out.scope());
            a.setPerformedBy(by);
            a.setTotalDeleted(out.affected());
            a.setCountsJson(new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(out.counts()));
            auditRepo.save(a);
        } catch (Exception e) {
            // Outside the delete transaction on purpose: a logging hiccup must never
            // roll back a deletion that has already happened.
            log.warn("Could not persist the trial deletion audit row — {}", e.getMessage());
        }
    }

    private void recordTenantAudit(String clientId, Map<String, Object> result, String by) {
        try {
            DemoDeletionAudit a = new DemoDeletionAudit();
            a.setDeletedAt(java.time.LocalDateTime.now());
            a.setTenantName((String) result.get("churchName"));
            a.setClientId(clientId);
            a.setAppClientId(clientId);
            a.setPerformedBy(by);
            Object total = result.get("total");
            a.setTotalDeleted(total instanceof Number n ? n.intValue() : 0);
            a.setCountsJson(new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(result.get("deleted")));
            auditRepo.save(a);
        } catch (Exception e) {
            log.warn("Could not persist the trial deletion audit row for {} — {}", clientId, e.getMessage());
        }
    }

    private static List<Integer> intIds(Object raw) {
        List<Integer> out = new ArrayList<>();
        if (raw instanceof Collection<?> c) {
            for (Object o : c) {
                try { out.add(Integer.valueOf(String.valueOf(o).trim())); }
                catch (NumberFormatException ignored) { /* a junk id is simply not a row */ }
            }
        }
        return out;
    }

    private static List<Long> longIds(Object raw) {
        List<Long> out = new ArrayList<>();
        if (raw instanceof Collection<?> c) {
            for (Object o : c) {
                try { out.add(Long.valueOf(String.valueOf(o).trim())); }
                catch (NumberFormatException ignored) { }
            }
        }
        return out;
    }

    private static boolean isServiceAdmin(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null && s.getAttribute("serviceAdminId") != null;
    }

    private static String actor(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null) return "(unknown service admin)";
        Object u = s.getAttribute("serviceAdminUsername");
        return u != null ? u.toString() : ("serviceAdminId:" + s.getAttribute("serviceAdminId"));
    }

    private static ResponseEntity<?> denied() {
        return ResponseEntity.status(401).body(Map.of("status", "error",
                "message", "Service admin login required."));
    }

    private static ResponseEntity<?> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("status", "error", "message", message));
    }

    private static ResponseEntity<?> error(String message) {
        return ResponseEntity.status(500).body(Map.of("status", "error", "message", message));
    }
}
