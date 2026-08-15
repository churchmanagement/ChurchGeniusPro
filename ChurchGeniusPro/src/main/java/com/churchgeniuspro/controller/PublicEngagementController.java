package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.PublicEngagementService;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Public "Connect With Us" + public "Prayer Request" pages and APIs, plus the staff
 * management of public prayer requests (assignment, status, activity notes). Public
 * submit/info endpoints live under {@code /api/public/*} (whitelisted in AuthFilter);
 * the staff endpoints use {@code /api/ppr/*} so they stay session-protected.
 */
@Controller
public class PublicEngagementController {

    private final PublicEngagementService svc;
    private final com.churchgeniuspro.util.PublicFormGuard formGuard;

    public PublicEngagementController(PublicEngagementService svc,
                                      com.churchgeniuspro.util.PublicFormGuard formGuard) {
        this.svc = svc;
        this.formGuard = formGuard;
    }

    // ── Public page routes (no login) ──
    @GetMapping("/connect")
    public String connectPage() { return "forward:/connect.html"; }

    @GetMapping("/publicPrayer")
    public String publicPrayerPage() { return "forward:/public-prayer.html"; }

    // ── Staff admin pages ──
    @GetMapping("/publicPrayerAdmin")
    public String adminPage(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        return "forward:/public-prayer-admin.html";
    }

    /** Admin page listing all Connect With Us submissions. */
    @GetMapping("/connectAdmin")
    public String connectAdminPage(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        return "forward:/connect-admin.html";
    }

    // ── Public APIs (whitelisted via /api/public) ──
    @ResponseBody
    @GetMapping("/api/public/engage-info")
    public ResponseEntity<?> info(@RequestParam(name = "cid") String cid) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(svc.churchInfo(cid));
        // Anti-bot form token (time-trap) + optional captcha site key
        m.put("formToken", formGuard.issueToken(cid));
        if (formGuard.captchaSiteKey() != null) m.put("captchaSiteKey", formGuard.captchaSiteKey());
        return ResponseEntity.ok(m);
    }

    /**
     * Runs the shared spam/bot checks for a public form submission.
     * Returns a ResponseEntity to short-circuit with, or null when clean.
     */
    private ResponseEntity<?> spamCheck(Map<String, Object> body, HttpServletRequest request, String form) {
        // 1. Honeypot — pretend success so bots don't adapt
        if (formGuard.isHoneypotTripped(body)) {
            return ResponseEntity.ok(Map.of("status", "submitted"));
        }
        String ip = com.churchgeniuspro.util.PublicFormGuard.clientIp(request);
        // 2. Rate limit per IP per form
        String err = formGuard.checkRate(ip, form);
        if (err != null) return ResponseEntity.status(429).body(Map.of("error", err));
        // 3. Time-trap token
        err = formGuard.checkToken(str(body, "formToken"), str(body, "cid"));
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        // 4. Optional reCAPTCHA
        err = formGuard.checkCaptcha(str(body, "captchaToken"), ip);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        // 5. Field validation (shared shapes)
        err = formGuard.checkEmail(str(body, "email"));
        if (err == null) err = formGuard.checkPhone(str(body, "phone"));
        if (err == null) err = formGuard.checkText(str(body, "howHeard"), 2000, 0);
        if (err == null) err = formGuard.checkText(str(body, "requestText"), 5000, 1);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        return null;
    }

    @ResponseBody
    @PostMapping("/api/public/connect")
    public ResponseEntity<?> connect(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        ResponseEntity<?> blocked = spamCheck(body, request, "connect");
        if (blocked != null) return blocked;
        try {
            svc.connect(str(body, "cid"), body);
            return ResponseEntity.ok(Map.of("status", "submitted",
                    "message", PublicEngagementService.CONFIRMATION_MSG));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @ResponseBody
    @PostMapping("/api/public/prayer-request")
    public ResponseEntity<?> prayer(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        ResponseEntity<?> blocked = spamCheck(body, request, "prayer");
        if (blocked != null) return blocked;
        try {
            String source = str(body, "source");
            Long id = svc.prayer(str(body, "cid"), body, normalizeSource(source));
            return ResponseEntity.ok(Map.of("status", "submitted", "id", id,
                    "message", PublicEngagementService.CONFIRMATION_MSG));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Staff management APIs: Connect submissions (session-protected) ──
    @ResponseBody
    @GetMapping("/api/connect-admin")
    public ResponseEntity<?> connectList(@RequestParam(required = false) String status, HttpServletRequest request) {
        String deny = guard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        return ResponseEntity.ok(svc.listConnect(SessionUtil.getAppClientId(request), status));
    }

    @ResponseBody
    @PostMapping("/api/connect-admin/{id}/assign")
    public ResponseEntity<?> connectAssign(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        String deny = guard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        Integer mid = body.get("memberId") == null || str(body, "memberId").isBlank()
                ? null : Integer.valueOf(str(body, "memberId"));
        try {
            svc.assignConnect(SessionUtil.getAppClientId(request), id, mid, str(body, "memberName"),
                    SessionUtil.getUsername(request));
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @ResponseBody
    @PostMapping("/api/connect-admin/{id}/status")
    public ResponseEntity<?> connectStatus(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        String deny = guard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        try {
            svc.setConnectStatus(SessionUtil.getAppClientId(request), id, str(body, "status"),
                    SessionUtil.getUsername(request));
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @ResponseBody
    @DeleteMapping("/api/connect-admin/{id}")
    public ResponseEntity<?> connectDelete(@PathVariable Long id, HttpServletRequest request) {
        String deny = guard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        try {
            svc.deleteConnect(SessionUtil.getAppClientId(request), id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Staff management APIs (session-protected; NOT under /api/public) ──
    @ResponseBody
    @GetMapping("/api/ppr")
    public ResponseEntity<?> list(@RequestParam(required = false) String source, HttpServletRequest request) {
        String deny = guard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        return ResponseEntity.ok(svc.listPrayer(SessionUtil.getAppClientId(request), source));
    }

    @ResponseBody
    @GetMapping("/api/ppr/volunteers")
    public ResponseEntity<?> volunteers(HttpServletRequest request) {
        String deny = guard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        return ResponseEntity.ok(svc.volunteers(SessionUtil.getAppClientId(request)));
    }

    @ResponseBody
    @PostMapping("/api/ppr/{id}/assign")
    public ResponseEntity<?> assign(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        String deny = guard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        Integer vid = body.get("volunteerId") == null || str(body, "volunteerId").isBlank() ? null : Integer.valueOf(str(body, "volunteerId"));
        try { svc.assign(SessionUtil.getAppClientId(request), id, vid, SessionUtil.getUsername(request)); return ResponseEntity.ok(Map.of("success", true)); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @ResponseBody
    @PostMapping("/api/ppr/{id}/status")
    public ResponseEntity<?> status(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        String deny = guard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        try { svc.setStatus(SessionUtil.getAppClientId(request), id, str(body, "status"), SessionUtil.getUsername(request)); return ResponseEntity.ok(Map.of("success", true)); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @ResponseBody
    @PostMapping("/api/ppr/{id}/note")
    public ResponseEntity<?> note(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        String deny = guard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        try { svc.addNote(SessionUtil.getAppClientId(request), id, str(body, "type"), str(body, "text"), SessionUtil.getUsername(request)); return ResponseEntity.ok(Map.of("success", true)); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @ResponseBody
    @GetMapping("/api/ppr/{id}/notes")
    public ResponseEntity<?> notes(@PathVariable Long id, HttpServletRequest request) {
        String deny = guard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        try { return ResponseEntity.ok(svc.notes(SessionUtil.getAppClientId(request), id)); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    @ResponseBody
    @DeleteMapping("/api/ppr/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, HttpServletRequest request) {
        String deny = guard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        try { svc.deletePrayer(SessionUtil.getAppClientId(request), id); return ResponseEntity.ok(Map.of("success", true)); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
    }

    // ── helpers ──
    private String guard(HttpServletRequest request) {
        if (RoleGuard.requireAdminOrUser(request) != null) return "Permission denied";
        if (SessionUtil.getAppClientId(request) == null) return "Not authenticated";
        return null;
    }
    private static String normalizeSource(String s) {
        if (s == null) return "PUBLIC";
        s = s.trim().toUpperCase();
        return (s.equals("NTAG") || s.equals("WEBSITE") || s.equals("PUBLIC")) ? s : "PUBLIC";
    }
    private static String str(Map<String, Object> b, String k) { Object v = b.get(k); return v == null ? null : v.toString().trim(); }
}
