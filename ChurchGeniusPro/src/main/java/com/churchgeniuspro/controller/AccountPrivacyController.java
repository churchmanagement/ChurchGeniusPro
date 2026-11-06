package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.ChurchDeletionRequest;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.ChurchDeletionService;
import com.churchgeniuspro.service.RememberMeService;
import com.churchgeniuspro.service.SecurityAuditService;
import com.churchgeniuspro.service.StaffAccountDeletionService;
import com.churchgeniuspro.util.PublicSendLimiter;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App Store readiness D7 (2026-11-06) — the "Account &amp; Privacy" page for staff and church logins:
 * privacy / terms links, delete my staff login, request deletion of the whole church, the public
 * verification link, and the Service Admin review list.
 *
 * <pre>
 *  GET  /accountPrivacy                          page (staff or church login)
 *  GET  /api/account/privacy                     what this login may do + the open church request
 *  POST /api/account/staff/delete                {password, confirm}  — own staff login
 *  POST /api/account/church-deletion             {password, churchName, confirm} — church login or SuperAdmin
 *  POST /api/account/church-deletion/cancel
 *  GET  /church-deletion/verify?token=…          public page (button posts the token)
 *  POST /api/public/church-deletion/verify       {token}
 *  GET  /api/serviceadmin/church-deletions       list (ServiceAdminAuthFilter)
 *  GET  /api/serviceadmin/church-deletions/{id}/dry-run
 *  POST /api/serviceadmin/church-deletions/{id}/delete   {clientId}
 *  POST /api/serviceadmin/church-deletions/{id}/cancel
 * </pre>
 */
@Controller
public class AccountPrivacyController {

    private static final ExecutorService MAIL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "staff-deletion-mail");
        t.setDaemon(true);
        return t;
    });

    private final StaffAccountDeletionService staffDeletion;
    private final ChurchDeletionService churchDeletion;
    private final RememberMeService rememberMe;
    private final SecurityAuditService audit;
    private final PublicSendLimiter limiter;

    public AccountPrivacyController(StaffAccountDeletionService staffDeletion, ChurchDeletionService churchDeletion,
                                    RememberMeService rememberMe, SecurityAuditService audit, PublicSendLimiter limiter) {
        this.staffDeletion = staffDeletion; this.churchDeletion = churchDeletion;
        this.rememberMe = rememberMe; this.audit = audit; this.limiter = limiter;
    }

    /** STAFF, CHURCH, or null for every other session (member, kids, temp-access, NTAG, API token, none). */
    record Who(String kind, String role, String churchClientId, String staffRef, Integer appUserId,
               Integer loginId, String username) {
        boolean church() { return "CHURCH".equals(kind); }
        boolean superAdmin() { return "superadmin".equalsIgnoreCase(role); }
        boolean mayRequestChurchDeletion() { return church() || superAdmin(); }
    }

    static Who who(HttpServletRequest request) {
        HttpSession s = request.getSession(false);
        if (s == null || s.getAttribute("username") == null) return null;
        if (AiAccessPolicy.isTemporaryAccess(s) || s.getAttribute("ntagCredId") != null
                || Boolean.TRUE.equals(s.getAttribute("apiToken")) || RoleGuard.isKidsPortalSession(s)) return null;
        if (RoleGuard.isMemberPortalSession(s) || "Member".equals(s.getAttribute("role"))) return null;
        Integer loginId = s.getAttribute(com.churchgeniuspro.ai.consent.AiConsentService.SESSION_LOGIN_ID) instanceof Integer i ? i : null;
        String username = String.valueOf(s.getAttribute("username"));
        if (RoleGuard.isChurch(request)) {
            String cid = s.getAttribute("clientId") instanceof String c && !c.isBlank() ? c : null;
            return cid == null ? null : new Who("CHURCH", "Church", cid, null, null, loginId, username);
        }
        String app = s.getAttribute("appClientId") instanceof String a && !a.isBlank() ? a : null;
        Integer appUserId = s.getAttribute("appUserId") instanceof Integer i ? i : null;
        String ref = s.getAttribute("clientId") instanceof String c && !c.isBlank() ? c : null;
        if (app == null || appUserId == null || ref == null) return null;
        String role = s.getAttribute("role") == null ? "" : String.valueOf(s.getAttribute("role"));
        return new Who("STAFF", role, app, ref, appUserId, loginId, username);
    }

    private static ResponseEntity<Map<String, Object>> err(int status, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        m.put("message", message);
        if (code != null) m.put("code", code);
        return ResponseEntity.status(status).body(m);
    }

    private static Map<String, Object> view(ChurchDeletionRequest r) {
        if (r == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("status", r.getStatus());
        m.put("churchClientId", r.getChurchClientId());
        m.put("churchName", r.getChurchName());
        m.put("requestedBy", r.getRequestedBy());
        m.put("requestedRole", r.getRequestedRole());
        m.put("requestedAt", r.getRequestedAt() == null ? null : r.getRequestedAt().toString());
        m.put("verifiedAt", r.getVerifiedAt() == null ? null : r.getVerifiedAt().toString());
        m.put("completedAt", r.getCompletedAt() == null ? null : r.getCompletedAt().toString());
        m.put("completedBy", r.getCompletedBy());
        m.put("cancelledAt", r.getCancelledAt() == null ? null : r.getCancelledAt().toString());
        m.put("cancelledBy", r.getCancelledBy());
        m.put("deletedRows", r.getDeletedRows());
        return m;
    }

    // ── Page ─────────────────────────────────────────────────────────────────

    @GetMapping("/accountPrivacy")
    public String page(HttpServletRequest request) {
        HttpSession s = request.getSession(false);
        if (s == null || s.getAttribute("username") == null) return RoleGuard.REDIRECT_LOGIN;
        if ("Member".equals(s.getAttribute("role"))) return "redirect:/memberHome";
        return "forward:/accountPrivacy.html";
    }

    @GetMapping("/church-deletion/verify")
    public String verifyPage() {
        return "forward:/churchDeletionVerify.html";
    }

    // ── Church / staff API ───────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/account/privacy")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        Who w = who(request);
        if (w == null) return err(403, "NOT_AVAILABLE", "This page is for staff and church logins.");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", w.kind());
        m.put("role", w.role());
        m.put("canDeleteLogin", !w.church());
        m.put("canRequestChurchDeletion", w.mayRequestChurchDeletion());
        if (w.mayRequestChurchDeletion()) {
            m.put("churchName", churchDeletion.churchName(w.churchClientId()));
            m.put("request", view(churchDeletion.current(w.churchClientId())));
        }
        return ResponseEntity.ok(m);
    }

    @ResponseBody
    @PostMapping("/api/account/staff/delete")
    public ResponseEntity<Map<String, Object>> deleteStaff(@RequestBody(required = false) Map<String, Object> body,
                                                           HttpServletRequest request, HttpServletResponse response) {
        Who w = who(request);
        if (w == null || w.church()) return err(403, "NOT_STAFF", "Only a staff login can be deleted here.");
        if (body == null || !Boolean.TRUE.equals(body.get("confirm"))) {
            return err(400, "CONFIRM", "Please confirm that you understand what will be deleted.");
        }
        String password = body.get("password") == null ? "" : String.valueOf(body.get("password"));
        StaffAccountDeletionService.Outcome out = staffDeletion.deleteOwnLogin(w.staffRef(), w.appUserId(), w.churchClientId(), password);
        if (!out.ok()) return err(out.status(), out.code(), out.error());
        HttpSession session = request.getSession(false);
        if (session != null) {
            try { audit.recordLogout(request, session); } catch (Exception ignored) { /* audit never blocks */ }
            session.invalidate();
        }
        rememberMe.clearCookie(response);
        String to = out.email(), name = out.name(), church = w.churchClientId();
        MAIL.execute(() -> staffDeletion.sendConfirmation(to, name, church));
        return ResponseEntity.ok(Map.of("success", true, "message", "Your staff login has been deleted."));
    }

    @ResponseBody
    @PostMapping("/api/account/church-deletion")
    public ResponseEntity<Map<String, Object>> requestChurch(@RequestBody(required = false) Map<String, Object> body,
                                                             HttpServletRequest request) {
        Who w = who(request);
        if (w == null || !w.mayRequestChurchDeletion()) {
            return err(403, "NOT_ALLOWED", "Only the church account or a SuperAdmin can request deletion of the church.");
        }
        if (body == null || !Boolean.TRUE.equals(body.get("confirm"))) {
            return err(400, "CONFIRM", "Please confirm that you understand what will be deleted.");
        }
        ChurchDeletionService.Outcome out = churchDeletion.request(w.churchClientId(), w.loginId(),
                w.username(), w.role(), str(body.get("password")), str(body.get("churchName")));
        if (!out.ok()) {
            ResponseEntity<Map<String, Object>> e = err(out.status(), out.code(), out.message());
            if (out.request() != null) e.getBody().put("request", view(out.request()));
            return e;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("message", out.message());
        m.put("request", view(out.request()));
        return ResponseEntity.ok(m);
    }

    @ResponseBody
    @PostMapping("/api/account/church-deletion/cancel")
    public ResponseEntity<Map<String, Object>> cancelChurch(HttpServletRequest request) {
        Who w = who(request);
        if (w == null || !w.mayRequestChurchDeletion()) {
            return err(403, "NOT_ALLOWED", "Only the church account or a SuperAdmin can cancel the request.");
        }
        ChurchDeletionService.Outcome out = churchDeletion.cancel(w.churchClientId(), w.username());
        if (!out.ok()) return err(out.status(), out.code(), out.message());
        return ResponseEntity.ok(Map.of("success", true, "message", out.message()));
    }

    @ResponseBody
    @PostMapping("/api/public/church-deletion/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody(required = false) Map<String, Object> body,
                                                      HttpServletRequest request) {
        String limited = limiter.check(PublicSendLimiter.CHURCH_DELETION_VERIFY, request, null, null);
        if (limited != null) return err(429, "RATE_LIMITED", limited);
        ChurchDeletionService.Outcome out = churchDeletion.verify(body == null ? null : str(body.get("token")));
        if (!out.ok()) return err(out.status(), out.code(), out.message());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("message", out.message());
        m.put("churchName", out.request().getChurchName());
        return ResponseEntity.ok(m);
    }

    // ── Service Admin ────────────────────────────────────────────────────────

    private static String serviceAdmin(HttpServletRequest request) {
        HttpSession s = request.getSession(false);
        if (s == null || s.getAttribute("serviceAdminId") == null) return null;
        Object u = s.getAttribute("serviceAdminUsername");
        return u == null ? "service-admin" : "service-admin:" + u;
    }

    @ResponseBody
    @GetMapping("/api/serviceadmin/church-deletions")
    public ResponseEntity<?> adminList(HttpServletRequest request) {
        if (serviceAdmin(request) == null) return err(401, null, "Service admin login required");
        List<Map<String, Object>> rows = churchDeletion.list().stream().map(AccountPrivacyController::view).toList();
        return ResponseEntity.ok(Map.of("requests", rows));
    }

    @ResponseBody
    @GetMapping("/api/serviceadmin/church-deletions/{id}/dry-run")
    public ResponseEntity<?> adminDryRun(@PathVariable Long id, HttpServletRequest request) {
        if (serviceAdmin(request) == null) return err(401, null, "Service admin login required");
        try {
            Map<String, Integer> counts = churchDeletion.dryRun(id);
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            return ResponseEntity.ok(Map.of("counts", counts, "total", total, "kept", ChurchDeletionService.KEEP));
        } catch (IllegalArgumentException e) {
            return err(404, "NOT_FOUND", e.getMessage());
        }
    }

    @ResponseBody
    @PostMapping("/api/serviceadmin/church-deletions/{id}/delete")
    public ResponseEntity<?> adminDelete(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body,
                                         HttpServletRequest request) {
        String actor = serviceAdmin(request);
        if (actor == null) return err(401, null, "Service admin login required");
        ChurchDeletionService.Outcome out = churchDeletion.execute(id, actor, body == null ? null : str(body.get("clientId")));
        if (!out.ok()) return err(out.status(), out.code(), out.message());
        return ResponseEntity.ok(Map.of("success", true, "message", out.message(), "request", view(out.request())));
    }

    @ResponseBody
    @PostMapping("/api/serviceadmin/church-deletions/{id}/cancel")
    public ResponseEntity<?> adminCancel(@PathVariable Long id, HttpServletRequest request) {
        String actor = serviceAdmin(request);
        if (actor == null) return err(401, null, "Service admin login required");
        ChurchDeletionService.Outcome out = churchDeletion.adminCancel(id, actor);
        if (!out.ok()) return err(out.status(), out.code(), out.message());
        return ResponseEntity.ok(Map.of("success", true, "message", out.message()));
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
