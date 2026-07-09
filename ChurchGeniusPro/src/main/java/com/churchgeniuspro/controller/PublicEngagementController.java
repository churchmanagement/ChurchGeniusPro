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

    public PublicEngagementController(PublicEngagementService svc) { this.svc = svc; }

    // ── Public page routes (no login) ──
    @GetMapping("/connect")
    public String connectPage() { return "forward:/connect.html"; }

    @GetMapping("/publicPrayer")
    public String publicPrayerPage() { return "forward:/public-prayer.html"; }

    // ── Staff admin page ──
    @GetMapping("/publicPrayerAdmin")
    public String adminPage(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        return "forward:/public-prayer-admin.html";
    }

    // ── Public APIs (whitelisted via /api/public) ──
    @ResponseBody
    @GetMapping("/api/public/engage-info")
    public ResponseEntity<?> info(@RequestParam(name = "cid") String cid) {
        return ResponseEntity.ok(svc.churchInfo(cid));
    }

    @ResponseBody
    @PostMapping("/api/public/connect")
    public ResponseEntity<?> connect(@RequestBody Map<String, Object> body) {
        try {
            svc.connect(str(body, "cid"), body);
            return ResponseEntity.ok(Map.of("status", "submitted"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @ResponseBody
    @PostMapping("/api/public/prayer-request")
    public ResponseEntity<?> prayer(@RequestBody Map<String, Object> body) {
        try {
            String source = str(body, "source");
            Long id = svc.prayer(str(body, "cid"), body, normalizeSource(source));
            return ResponseEntity.ok(Map.of("status", "submitted", "id", id));
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
