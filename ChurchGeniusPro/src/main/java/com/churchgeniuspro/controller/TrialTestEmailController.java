package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.TrialTestEmailService;
import com.churchgeniuspro.util.PublicSendLimiter;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin Settings → Email Settings → Trial/Demo test email (Phase B).
 *
 * <p>Same authorization as the Email Settings API it sits beside: a SuperAdmin or
 * Admin staff login ({@link RoleGuard#requireAdmin}); Member, Child and Limited
 * sessions are refused. The tenant is always the session's — a client id in the
 * request is never read — so one church can neither see nor set another's address.
 * Data APIs stay role-only, per the standing rule; the page itself carries the
 * {@code general.emailsettings} permission.
 */
@RestController
@RequestMapping("/api/email-settings/test-email")
public class TrialTestEmailController {

    private final TrialTestEmailService service;
    private final PublicSendLimiter limiter;

    public TrialTestEmailController(TrialTestEmailService service, PublicSendLimiter limiter) {
        this.service = service;
        this.limiter = limiter;
    }

    private static String denied(HttpServletRequest req) {
        return RoleGuard.requireAdmin(req);
    }

    @GetMapping
    public ResponseEntity<?> status(HttpServletRequest req) {
        if (denied(req) != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied."));
        String clientId = RoleGuard.clientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in again."));
        return ResponseEntity.ok(service.status(clientId));
    }

    /** Body: {@code { "email": "..." }}. Sends the verification code to that address. */
    @PostMapping("/request")
    public ResponseEntity<?> request(@RequestBody(required = false) Map<String, String> body, HttpServletRequest req) {
        if (denied(req) != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied."));
        String clientId = RoleGuard.clientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in again."));
        try {
            return ResponseEntity.ok(service.requestCode(clientId, body != null ? body.get("email") : null,
                    limiter.clientIp(req), username(req)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error",
                    "The verification email could not be sent. Please check the address and try again."));
        }
    }

    /** Body: {@code { "code": "123456" }}. */
    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody(required = false) Map<String, String> body, HttpServletRequest req) {
        if (denied(req) != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied."));
        String clientId = RoleGuard.clientId(req);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in again."));
        try {
            return ResponseEntity.ok(service.verify(clientId, body != null ? body.get("code") : null, username(req)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static String username(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        Object u = s != null ? s.getAttribute("username") : null;
        return u == null ? null : String.valueOf(u);
    }
}
