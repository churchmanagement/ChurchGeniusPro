package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.MemberSafetyService;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Staff review of member reports (mobile Phase 2, M5) — Follow-Ups → Reported Content.
 * Guarded like its siblings {@code /api/connect-admin} and {@code /api/ppr}: staff role
 * plus the session's church. The page itself carries the {@code more.followups} check.
 */
@RestController
public class ContentReportController {

    private final MemberSafetyService safety;

    public ContentReportController(MemberSafetyService safety) {
        this.safety = safety;
    }

    /**
     * Staff only. {@code requireAdminOrUser} deliberately lets Member sessions through
     * (page routes rely on the later permission check for a readable denial); an API
     * for reviewing other members' reports must not, so members are refused here.
     */
    private static boolean staff(HttpServletRequest request) {
        if (RoleGuard.requireAdminOrUser(request) != null) return false;
        Object role = request.getSession(false) == null ? null : request.getSession(false).getAttribute("role");
        return !"Member".equals(role);
    }

    @GetMapping("/api/content-reports")
    public ResponseEntity<?> list(@RequestParam(required = false) String status, HttpServletRequest request) {
        if (!staff(request)) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String clientId = SessionUtil.getAppClientId(request);
        return ResponseEntity.ok(Map.of(
                "reports", safety.listReports(clientId, status),
                "counts",  safety.reportCounts(clientId)));
    }

    @PostMapping("/api/content-reports/{id}/resolve")
    public ResponseEntity<?> resolve(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body,
                                     HttpServletRequest request) {
        if (!staff(request)) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String clientId = SessionUtil.getAppClientId(request);
        String action = body == null ? null : String.valueOf(body.getOrDefault("action", ""));
        String note   = body == null || body.get("note") == null ? null : String.valueOf(body.get("note"));
        Object user   = request.getSession(false) == null ? null : request.getSession(false).getAttribute("username");
        return safety.resolve(clientId, id, action, note, user == null ? null : String.valueOf(user))
                .<ResponseEntity<?>>map(r -> ResponseEntity.ok(Map.of("success", true, "report", r)))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "Report not found or unknown action.")));
    }
}
