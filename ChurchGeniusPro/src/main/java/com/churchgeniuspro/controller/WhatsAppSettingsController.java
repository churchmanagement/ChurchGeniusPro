package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.WhatsAppSettings;
import com.churchgeniuspro.repository.WhatsAppSettingsRepository;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serves the WhatsApp Integration settings page and its REST API.
 *
 * <p>One record per organization (unique constraint on {@code client_id}).
 * The save endpoint is always an upsert.</p>
 *
 * <ul>
 *   <li>{@code GET  /whatsappIntegration}        → page (Admin / SuperAdmin / Church)</li>
 *   <li>{@code GET  /api/whatsapp-settings}       → load current settings</li>
 *   <li>{@code POST /api/whatsapp-settings}       → create or update settings</li>
 * </ul>
 */
@Controller
public class WhatsAppSettingsController {

    private final WhatsAppSettingsRepository repo;

    public WhatsAppSettingsController(WhatsAppSettingsRepository repo) {
        this.repo = repo;
    }

    // ── Page ─────────────────────────────────────────────────────────────────

    @GetMapping("/whatsappIntegration")
    public String page(HttpServletRequest request) {
        // Owner-only: ONLY church-type logins may access. All other roles denied.
        String deny = RoleGuard.requireChurch(request);
        return deny != null ? deny : "forward:/whatsappIntegration.html";
    }

    // ── REST: load ────────────────────────────────────────────────────────────

    /**
     * Returns the current WhatsApp settings for the session's organization.
     * The Auth Token is masked — only a confirmation flag is returned so the
     * UI can show "token is set" without exposing the value.
     */
    @ResponseBody
    @GetMapping("/api/whatsapp-settings")
    public ResponseEntity<?> get(HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        // Owner-only, like the /whatsappIntegration page: this holds the Twilio auth token.
        if (RoleGuard.requireChurch(request) != null) return ResponseEntity.status(403).build();

        WhatsAppSettings s = repo.findByClientId(clientId).orElse(new WhatsAppSettings());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accountSid",    s.getAccountSid()    != null ? s.getAccountSid()    : "");
        out.put("senderPhone",   s.getSenderPhone()   != null ? s.getSenderPhone()   : "");
        out.put("messageTemplate", s.getMessageTemplate() != null ? s.getMessageTemplate() : "");
        out.put("meetingReminderContentSid", s.getMeetingReminderContentSid() != null ? s.getMeetingReminderContentSid() : "");
        // Auth token: only signal whether it is set — never return the raw value
        String token = s.getAuthToken();
        out.put("authTokenSet", token != null && !token.isBlank());
        return ResponseEntity.ok(out);
    }

    // ── REST: save (upsert) ───────────────────────────────────────────────────

    /**
     * Creates or updates the single WhatsApp-settings record for this organization.
     * If {@code authToken} is blank the existing token is preserved.
     */
    @ResponseBody
    @PostMapping("/api/whatsapp-settings")
    public ResponseEntity<?> save(@RequestBody Map<String, Object> body,
                                   HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        // Owner-only, like the /whatsappIntegration page: this holds the Twilio auth token.
        if (RoleGuard.requireChurch(request) != null) return ResponseEntity.status(403).build();

        String accountSid                = str(body.get("accountSid"));
        String authToken                 = str(body.get("authToken"));
        String senderPhone               = str(body.get("senderPhone"));
        String messageTemplate           = str(body.get("messageTemplate"));
        String meetingReminderContentSid = str(body.get("meetingReminderContentSid"));

        if (accountSid == null || accountSid.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Account SID is required."));
        if (senderPhone == null || senderPhone.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Sender Phone is required."));

        if (authToken == null || authToken.isBlank()) {
            // Blank auth token is only allowed when one is already stored
            boolean hasExisting = repo.findByClientId(clientId)
                    .map(s -> s.getAuthToken() != null && !s.getAuthToken().isBlank())
                    .orElse(false);
            if (!hasExisting)
                return ResponseEntity.badRequest().body(Map.of("error", "Auth Token is required."));
        }

        WhatsAppSettings s = repo.findByClientId(clientId).orElse(new WhatsAppSettings());
        s.setClientId(clientId);
        s.setAccountSid(accountSid);
        s.setSenderPhone(senderPhone);
        s.setMessageTemplate(messageTemplate != null ? messageTemplate : "");
        s.setMeetingReminderContentSid(meetingReminderContentSid != null ? meetingReminderContentSid : "");
        if (authToken != null && !authToken.isBlank()) {
            s.setAuthToken(authToken);
        }

        repo.save(s);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static String str(Object o) {
        return o == null ? null : o.toString().trim();
    }
}
