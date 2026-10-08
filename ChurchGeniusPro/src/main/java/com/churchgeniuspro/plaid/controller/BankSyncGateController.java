package com.churchgeniuspro.plaid.controller;

import com.churchgeniuspro.plaid.service.BankSyncGateService;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API for the Bank Sync email-verification gate: send/resend a code, verify it,
 * check status, and (for Church-role admins) generate a temporary code for a user.
 * Session-authenticated via AuthFilter; these endpoints do NOT themselves require
 * the gate to be passed (they are how the user passes it).
 */
@RestController
@RequestMapping("/api/plaid/gate")
public class BankSyncGateController {

    private static final Logger log = LoggerFactory.getLogger(BankSyncGateController.class);

    private final BankSyncGateService gate;

    public BankSyncGateController(BankSyncGateService gate) {
        this.gate = gate;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status",   "success");
        m.put("verified", gate.isVerified(req));
        // Lets the verify page tell "already verified" apart from "exempt", so it
        // can send an exempt user straight on instead of offering a Resend button
        // for a code that will never arrive.
        m.put("verificationRequired", gate.verificationRequired(req));
        return ResponseEntity.ok(m);
    }

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(HttpServletRequest req) {
        if (!gate.verificationRequired(req)) return ResponseEntity.ok(notRequired());
        boolean ok = gate.sendCode(req, false);
        return ok ? ResponseEntity.ok(msg("success", "A verification code was sent to your email."))
                  : ResponseEntity.status(400).body(msg("error", "Could not send a verification code. Check that your account has an email on file."));
    }

    @PostMapping("/resend")
    public ResponseEntity<Map<String, Object>> resend(HttpServletRequest req) {
        if (!gate.verificationRequired(req)) return ResponseEntity.ok(notRequired());
        boolean ok = gate.sendCode(req, true);
        return ok ? ResponseEntity.ok(msg("success", "A new verification code was sent."))
                  : ResponseEntity.status(400).body(msg("error", "Could not resend the code."));
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody Map<String, Object> body,
                                                      HttpServletRequest req, HttpServletResponse res) {
        String code = body.get("code") != null ? body.get("code").toString() : null;
        boolean remember = Boolean.TRUE.equals(body.get("remember"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("remember")));
        BankSyncGateService.VerifyOutcome out = gate.verify(req, res, code, remember);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", out.ok() ? "success" : "error");
        m.put("verified", out.ok());
        m.put("message", out.message());
        return ResponseEntity.status(out.ok() ? 200 : 400).body(m);
    }

    /** Church-role admin generates a temporary one-time code for another user. */
    @PostMapping("/temp-code")
    public ResponseEntity<Map<String, Object>> tempCode(@RequestBody Map<String, Object> body,
                                                        HttpServletRequest req) {
        if (!canGenerateTempCode(req)) {
            return ResponseEntity.status(403).body(msg("error", "Only a Church administrator can generate a code."));
        }
        Integer userId;
        try { userId = Integer.valueOf(String.valueOf(body.get("userId"))); }
        catch (Exception e) { return ResponseEntity.status(400).body(msg("error", "A valid userId is required.")); }
        try {
            String code = gate.generateTempCode(req, userId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "success");
            m.put("code", code);
            m.put("message", "Temporary code generated. It expires in 15 minutes and can be used once.");
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(msg("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("temp-code generation failed: {}", e.getMessage());
            return ResponseEntity.status(500).body(msg("error", "Could not generate a code."));
        }
    }

    private boolean canGenerateTempCode(HttpServletRequest req) {
        if (SessionUtil.isChurchAccount(req)) return true;
        String role = SessionUtil.getRole(req);
        return "Church".equalsIgnoreCase(role) || "SuperAdmin".equals(role) || "Admin".equals(role);
    }

    private Map<String, Object> map(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "success");
        m.put(k, v);
        return m;
    }

    /** Trial tenants skip the gate: report it as passed, not as a failed send. */
    private Map<String, Object> notRequired() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status",   "success");
        m.put("verified", true);
        m.put("verificationRequired", false);
        m.put("message",  "Verification is not required for Trial subscriptions.");
        return m;
    }

    private Map<String, Object> msg(String status, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("message", message);
        return m;
    }
}
