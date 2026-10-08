package com.churchgeniuspro.plaid.controller;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.plaid.util.PlaidGuard;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.VerificationStore;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Email-verification OTP flow for the Plaid bank-connect gate. A user must verify
 * control of their email before connecting a bank (requirement: verified email +
 * financial role). Reuses the existing {@link VerificationStore} and
 * {@link EmailService}. Session-authenticated (AuthFilter protects /api/plaid/*
 * except the webhook).
 */
@RestController
@RequestMapping("/api/plaid/verify-email")
public class PlaidEmailVerificationController {

    private static final Logger log = LoggerFactory.getLogger(PlaidEmailVerificationController.class);
    private static final String TYPE_PREFIX = "PLAID_EMAIL_";

    private final VerificationStore verificationStore;
    private final EmailService emailService;
    private final AppUserRepository appUserRepository;
    private final PlaidGuard guard;

    public PlaidEmailVerificationController(VerificationStore verificationStore,
                                           EmailService emailService,
                                           AppUserRepository appUserRepository,
                                           PlaidGuard guard) {
        this.verificationStore = verificationStore;
        this.emailService = emailService;
        this.appUserRepository = appUserRepository;
        this.guard = guard;
    }

    /** Whether the current user's email is already verified. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "success");
        m.put("verified", guard.isEmailVerified(req));
        return ResponseEntity.ok(m);
    }

    /** Send a one-time code to the current user's email. */
    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(HttpServletRequest req) {
        Integer appUserId = appUserId(req);
        String clientId = SessionUtil.getAppClientId(req);
        if (appUserId == null) {
            // Church-owner sessions are already treated as verified.
            return ResponseEntity.ok(resp("success", "Already verified.", true));
        }
        AppUser user = appUserRepository.findById(appUserId).orElse(null);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            return ResponseEntity.status(400).body(resp("error", "No email on file for your account.", false));
        }
        if (user.isEmailVerified()) {
            return ResponseEntity.ok(resp("success", "Your email is already verified.", true));
        }
        try {
            String code = verificationStore.generateAndStore(clientId, TYPE_PREFIX + appUserId, user.getEmail());
            String html = "<p>Your ChurchGeniusPro verification code for connecting a bank account is:</p>"
                    + "<p style=\"font-size:22px;font-weight:700;letter-spacing:3px;\">" + code + "</p>"
                    + "<p>This code expires in 10 minutes. If you did not request it, you can ignore this email.</p>";
            emailService.sendAccountEmail(user.getEmail(), "Your bank-connect verification code", html, clientId);
            return ResponseEntity.ok(resp("success", "A verification code was sent to your email.", false));
        } catch (Exception e) {
            log.warn("Plaid email verification send failed: {}", e.getMessage());
            return ResponseEntity.status(500).body(resp("error", "Could not send the verification code.", false));
        }
    }

    /** Confirm the code and mark the user's email verified. */
    @PostMapping("/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@RequestBody Map<String, Object> body,
                                                       HttpServletRequest req) {
        Integer appUserId = appUserId(req);
        String clientId = SessionUtil.getAppClientId(req);
        if (appUserId == null) {
            return ResponseEntity.ok(resp("success", "Already verified.", true));
        }
        String code = body.get("code") != null ? body.get("code").toString().trim() : "";
        if (code.isEmpty()) return ResponseEntity.status(400).body(resp("error", "Enter the code.", false));

        if (!verificationStore.validate(clientId, TYPE_PREFIX + appUserId, code)) {
            return ResponseEntity.status(400).body(resp("error", "Invalid or expired code.", false));
        }
        AppUser user = appUserRepository.findById(appUserId).orElse(null);
        if (user == null) return ResponseEntity.status(400).body(resp("error", "Account not found.", false));
        user.setEmailVerified(true);
        appUserRepository.save(user);
        verificationStore.remove(clientId, TYPE_PREFIX + appUserId);
        return ResponseEntity.ok(resp("success", "Email verified.", true));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Integer appUserId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        Object v = s != null ? s.getAttribute("appUserId") : null;
        return v instanceof Integer i ? i : null;
    }

    private Map<String, Object> resp(String status, String message, boolean verified) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("message", message);
        m.put("verified", verified);
        return m;
    }
}
