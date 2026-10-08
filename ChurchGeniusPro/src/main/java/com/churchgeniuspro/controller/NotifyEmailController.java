package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.NotifyEmailService;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Handles the Notify Email page and its REST API.
 *
 * <h3>Page routes</h3>
 * <ul>
 *   <li>{@code GET /notifyEmail} → {@code notifyEmail.html}</li>
 * </ul>
 *
 * <h3>API routes</h3>
 * <ul>
 *   <li>{@code GET  /api/notify/events}       → list events for the event-guests selector</li>
 *   <li>{@code GET  /api/notify/recipients}    → resolve recipient list for preview</li>
 *   <li>{@code POST /api/notify/send}          → send the notification email</li>
 * </ul>
 */
@Controller
public class NotifyEmailController {

    private final NotifyEmailService notifyEmailService;

    public NotifyEmailController(NotifyEmailService notifyEmailService) {
        this.notifyEmailService = notifyEmailService;
    }

    // ── Page route ────────────────────────────────────────────────────────

    @GetMapping("/notifyEmail")
    public String notifyEmailPage(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "general.email");
        if (deny != null) return deny;
        return "forward:/notifyEmail.html";
    }

    // ── Events (for Event Guests selector) ───────────────────────────────

    @ResponseBody
    @GetMapping("/api/notify/events")
    public ResponseEntity<List<Map<String, Object>>> getEvents(HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        return ResponseEntity.ok(notifyEmailService.getEvents(clientId));
    }

    // ── Recipients preview ────────────────────────────────────────────────

    /**
     * Returns the resolved recipient list for a given type so the frontend can
     * display a preview before sending.
     *
     * @param type    members | event-guests | church-guests | group-email
     * @param eventId optional — relevant for event-guests
     */
    @ResponseBody
    @GetMapping("/api/notify/recipients")
    public ResponseEntity<List<Map<String, Object>>> getRecipients(
            @RequestParam String type,
            @RequestParam(required = false) Integer eventId,
            HttpServletRequest request) {
        // Same gate as the page route and send(): the preview lists real member emails.
        if (RoleGuard.requireAdminOrUser(request) != null
                || RoleGuard.requirePermission(request, "general.email") != null) {
            return ResponseEntity.status(403).build();
        }
        String clientId = com.churchgeniuspro.util.SessionUtil.getAppClientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(notifyEmailService.getRecipients(type, eventId, clientId));
    }

    // ── Send ──────────────────────────────────────────────────────────────

    /**
     * Sends the notification email.
     *
     * @param recipientType  members | event-guests | church-guests | group-email
     * @param eventId        optional event ID for event-guests
     * @param manualEmails   newline/comma-separated addresses for group-email
     * @param subject        email subject
     * @param content        HTML email body
     * @param files          optional file attachments
     */
    @ResponseBody
    @PostMapping("/api/notify/send")
    public ResponseEntity<Map<String, Object>> send(
            @RequestParam String recipientType,
            @RequestParam(required = false) Integer eventId,
            @RequestParam(required = false) String manualEmails,
            @RequestParam String subject,
            @RequestParam String content,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            HttpServletRequest request) {
        // Enforce Compose Email permission on the API too (not just the page route).
        if (RoleGuard.requireAdminOrUser(request) != null
                || RoleGuard.requirePermission(request, "general.email") != null) {
            return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        }
        String clientId = com.churchgeniuspro.util.SessionUtil.getAppClientId(request);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        try {
            int count = notifyEmailService.sendEmail(
                    recipientType, eventId, manualEmails, subject, content, files, clientId);
            String test = notifyEmailService.lastTestAddress();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", test != null
                        ? "Trial/Demo test: 1 test email sent to " + test + " — " + count + " recipient(s) simulated, none emailed."
                        : "Email sent to " + count + " recipient(s)."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Unexpected error: " + e.getMessage()));
        }
    }
}
