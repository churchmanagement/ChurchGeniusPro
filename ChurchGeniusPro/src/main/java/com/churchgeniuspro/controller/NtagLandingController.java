package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.NtagLandingService;
import com.churchgeniuspro.util.PublicSendLimiter;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Public NTAG landing page + the dedicated public Upcoming Events page, plus the
 * admin editor / analytics. Public routes require no login (whitelisted in AuthFilter);
 * the church is identified by an encrypted {@code c}/{@code cid} token in the URL.
 */
@Controller
public class NtagLandingController {

    private final NtagLandingService svc;
    private final PublicSendLimiter sendLimiter;

    public NtagLandingController(NtagLandingService svc, PublicSendLimiter sendLimiter) {
        this.svc = svc;
        this.sendLimiter = sendLimiter;
    }

    // ── Public page routes (no login) ──
    @GetMapping("/ntagLanding")
    public String landingPage() { return "forward:/ntag-landing.html"; }

    @GetMapping("/upcomingEvents")
    public String upcomingEventsPage() { return "forward:/upcoming-events.html"; }

    // ── Admin page ──
    @GetMapping("/ntagLandingAdmin")
    public String adminPage(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrChurch(request);
        if (deny != null) return deny;
        return "forward:/ntag-landing-admin.html";
    }

    // ── Public APIs (whitelisted via /api/public) ──
    @ResponseBody
    @GetMapping("/api/public/ntag-landing")
    public ResponseEntity<?> publicLanding(@RequestParam(name = "cid") String cid, HttpServletRequest request) {
        Map<String, Object> payload = svc.publicPayload(cid, clientIp(request), request.getHeader("User-Agent"));
        if (payload == null) return ResponseEntity.status(404).body(Map.of("error", "This page is unavailable."));
        return ResponseEntity.ok(payload);
    }

    @ResponseBody
    @PostMapping("/api/public/ntag-landing/track")
    public ResponseEntity<?> track(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        // Anonymous analytics rows with caller-chosen labels (security audit P7): keep the
        // strings to their columns and bound the volume per origin and per landing link.
        // A refused event is simply not recorded — the page never fails on analytics.
        String cid = str(body, "cid");
        if (sendLimiter.check(PublicSendLimiter.NTAG_TRACK, request, null, cid) != null) {
            return ResponseEntity.ok(Map.of("ok", true));
        }
        svc.track(cid, str(body, "type"), clip(str(body, "buttonKey"), 60), clip(str(body, "label"), 120),
                clientIp(request), request.getHeader("User-Agent"));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @ResponseBody
    @GetMapping("/api/public/upcoming-events")
    public ResponseEntity<?> publicUpcomingEvents(@RequestParam(name = "cid") String cid) {
        return ResponseEntity.ok(svc.upcomingEvents(cid));
    }

    // ── Admin APIs ──
    @ResponseBody
    @GetMapping("/api/ntag-landing/config")
    public ResponseEntity<?> config(HttpServletRequest request) {
        String deny = adminGuard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        return ResponseEntity.ok(svc.configMap(SessionUtil.getAppClientId(request)));
    }

    @ResponseBody
    @PutMapping("/api/ntag-landing/config")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> saveConfig(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String deny = adminGuard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        List<Map<String, Object>> buttons = body.get("buttons") instanceof List<?> l ? (List<Map<String, Object>>) l : null;
        svc.save(SessionUtil.getAppClientId(request), str(body, "welcomeMessage"), str(body, "themeColor"),
                str(body, "bannerImage"), boolN(body.get("showLogo")), boolN(body.get("enabled")), buttons);
        return ResponseEntity.ok(Map.of("success", true));
    }

    @ResponseBody
    @GetMapping("/api/ntag-landing/report")
    public ResponseEntity<?> report(@RequestParam(defaultValue = "100") int limit, HttpServletRequest request) {
        String deny = adminGuard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", deny));
        return ResponseEntity.ok(svc.report(SessionUtil.getAppClientId(request), limit));
    }

    // ── helpers ──
    private String adminGuard(HttpServletRequest request) {
        if (RoleGuard.requireAdminOrChurch(request) != null) return "Permission denied";
        if (SessionUtil.getAppClientId(request) == null) return "Not authenticated";
        return null;
    }
    private static String str(Map<String, Object> b, String k) { Object v = b.get(k); return v == null ? null : v.toString(); }
    private static String clip(String s, int max) { return s == null || s.length() <= max ? s : s.substring(0, max); }
    private static Boolean boolN(Object v) { if (v == null) return null; return (v instanceof Boolean b) ? b : Boolean.parseBoolean(v.toString()); }
    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
