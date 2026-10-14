package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.DonationSettingsService;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Donation Settings (the ⚙ on the Donation Review page) — how this church's online
 * donations and Stripe fees are posted to accounting.
 *
 * <p>Access is exactly the Donation Review page's: Accountant or Admin role plus the
 * {@code accounting.donation} permission (approval decision 5: no separate settings
 * permission in this phase). Everything is scoped to the session's church; the
 * service refuses any fund/method/purpose id that is not this church's.
 */
@RestController
public class DonationSettingsController {

    private static final Logger log = LoggerFactory.getLogger(DonationSettingsController.class);

    private final DonationSettingsService settings;

    public DonationSettingsController(DonationSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping("/api/donation-settings")
    public ResponseEntity<?> get(HttpServletRequest request) {
        String deny = guard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied."));
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in again."));
        return ResponseEntity.ok(settings.describe(clientId));
    }

    @PutMapping("/api/donation-settings")
    public ResponseEntity<?> put(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        String deny = guard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied."));
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in again."));
        try {
            settings.save(clientId, body, username(request));
            log.info("Donation settings saved: client={} by={}", clientId, username(request));
            return ResponseEntity.ok(settings.describe(clientId));
        } catch (DonationSettingsService.InvalidSettingsException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    static String guard(HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny == null) deny = RoleGuard.requirePermission(request, "accounting.donation");
        return deny;
    }

    private static String username(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object u = session != null ? session.getAttribute("username") : null;
        return u == null ? null : String.valueOf(u);
    }
}
