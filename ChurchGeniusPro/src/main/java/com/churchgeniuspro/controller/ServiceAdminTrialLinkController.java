package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.TrialRegistrationLink;
import com.churchgeniuspro.service.TrialRegistrationLinkService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service-Admin management of Trial Registration invitation links: issue one for a
 * prospect, see what happened to the ones already out, and revoke early.
 *
 * <p>Guarded on the same {@code role=ServiceAdmin} session attribute as the other
 * service-admin APIs.
 */
@RestController
public class ServiceAdminTrialLinkController {

    private static final Logger log = LoggerFactory.getLogger(ServiceAdminTrialLinkController.class);

    private final TrialRegistrationLinkService links;

    public ServiceAdminTrialLinkController(TrialRegistrationLinkService links) {
        this.links = links;
    }

    /* ── list ───────────────────────────────────────────────────────────── */

    @GetMapping("/api/serviceadmin/trial-links")
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "success");
        out.put("data", links.listForAdmin());
        out.put("defaultValidDays", TrialRegistrationLinkService.DEFAULT_VALID_DAYS);
        out.put("trialDayOptions",  TrialRegistrationLinkService.TRIAL_DAY_OPTIONS);
        return ResponseEntity.ok(out);
    }

    /* ── issue ──────────────────────────────────────────────────────────── */

    /**
     * Issues a link for one prospect.
     *
     * <p>Body: {@code { prospectName, prospectEmail, note, validDays }} — all
     * optional; {@code validDays} defaults to seven. Issuing for an email that
     * already holds an open link revokes that one, so only the newest URL works.
     */
    @PostMapping("/api/serviceadmin/trial-links")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody(required = false) Map<String, Object> body,
                                                        HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        Map<String, Object> b = body == null ? Map.of() : body;
        try {
            TrialRegistrationLink link = links.generate(
                    str(b.get("prospectName")),
                    str(b.get("prospectEmail")),
                    str(b.get("note")),
                    intOrNull(b.get("validDays")),
                    intOrNull(b.get("trialDays")),
                    actor(req));

            Map<String, Object> out = new LinkedHashMap<>(links.toRow(link));
            out.put("status", "success");
            // Returned once here and again in the listing while the link is usable,
            // so an admin who closes the dialog can still copy it.
            out.put("url", links.urlFor(link.getToken()));
            out.put("message", "Registration link created. It expires on "
                             + link.getExpiresAt().toLocalDate() + ".");
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.error("Could not issue a trial registration link", e);
            return ResponseEntity.status(500).body(error("Could not create a registration link."));
        }
    }

    /* ── revoke ─────────────────────────────────────────────────────────── */

    @PostMapping("/api/serviceadmin/trial-links/{id}/revoke")
    public ResponseEntity<Map<String, Object>> revoke(@PathVariable Integer id, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        try {
            TrialRegistrationLink link = links.revoke(id, actor(req));
            Map<String, Object> out = new LinkedHashMap<>(links.toRow(link));
            out.put("status", "success");
            out.put("message", "Registration link revoked. It can no longer be used.");
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        }
    }

    /* ── helpers ────────────────────────────────────────────────────────── */

    private boolean isServiceAdmin(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null && "ServiceAdmin".equals(s.getAttribute("role"));
    }

    private static String actor(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        Object u = s == null ? null : s.getAttribute("username");
        return u == null ? "ServiceAdmin" : String.valueOf(u);
    }

    private ResponseEntity<Map<String, Object>> denied() {
        return ResponseEntity.status(401).body(error("Not signed in as Service Admin"));
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("message", message);
        return m;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    private static Integer intOrNull(Object o) {
        if (o == null) return null;
        try { return Integer.valueOf(o.toString().trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
