package com.churchgeniuspro.payroll.controller;

import com.churchgeniuspro.payroll.service.SsnRotationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Service Admin payroll operations (security audit 2026-10-07, Phase 5): SSN last-4
 * encryption-key rotation. Same protection as {@code ServiceAdminPlaidController}:
 * the path is under {@code /api/serviceadmin/*}, so {@code ServiceAdminAuthFilter}
 * requires a Service Admin session ({@code serviceAdminId}) before this class runs,
 * {@code CsrfOriginFilter} checks the Origin/Referer of the POST, and the role is
 * checked again here. Responses carry counts only — never an SSN value or an
 * employee id.
 */
@RestController
public class ServiceAdminPayrollController {

    private final SsnRotationService rotationService;

    public ServiceAdminPayrollController(SsnRotationService rotationService) {
        this.rotationService = rotationService;
    }

    /** What a rotation would do. Read-only — run this before the real thing. */
    @GetMapping("/api/serviceadmin/payroll/ssn-rotation")
    public ResponseEntity<Map<String, Object>> rotationStatus(HttpServletRequest req) {
        if (!isServiceAdmin(req)) return unauthorized();
        return ResponseEntity.ok(rotationService.status());
    }

    /** Re-encrypts stored SSN last-4 values onto the current PAYROLL_SSN_ENC_KEY. Safe to repeat. */
    @PostMapping("/api/serviceadmin/payroll/ssn-rotation")
    public ResponseEntity<Map<String, Object>> rotate(HttpServletRequest req) {
        if (!isServiceAdmin(req)) return unauthorized();
        String actor = String.valueOf(req.getSession(false) == null
                ? "SERVICE_ADMIN" : req.getSession(false).getAttribute("username"));
        return ResponseEntity.ok(rotationService.rotate(false, actor));
    }

    private boolean isServiceAdmin(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null && "ServiceAdmin".equals(s.getAttribute("role"));
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Service admin login required."));
    }
}
