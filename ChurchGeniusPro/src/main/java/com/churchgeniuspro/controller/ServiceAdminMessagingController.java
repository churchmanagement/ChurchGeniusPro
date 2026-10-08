package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.SmsService;
import com.churchgeniuspro.util.PhoneNumbers;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Ad-hoc SMS and email from the Service Admin home page.
 *
 * <p>Uses the EXISTING Twilio account ({@code twilio.*}) and the existing mail
 * configuration — nothing new is provisioned here. The From values are simply
 * defaults the admin may override for a single send.
 *
 * <p>Every endpoint is gated on the {@code role=ServiceAdmin} session attribute
 * set by {@link ServiceAdminController#login}, matching the guard used by the
 * other service-admin APIs.
 */
@RestController
public class ServiceAdminMessagingController {

    private static final Logger log = LoggerFactory.getLogger(ServiceAdminMessagingController.class);

    /** Deliberately permissive: real deliverability is decided by the mail server. */
    private static final Pattern EMAIL =
            Pattern.compile("^[^\\s@,;]+@[^\\s@,;]+\\.[A-Za-z]{2,}$");

    /** Twilio's hard limit for a single message body. */
    private static final int SMS_MAX_CHARS = 1600;

    private final SmsService smsService;
    private final EmailService emailService;

    @Value("${spring.mail.username:info@churchgeniuspro.com}")
    private String configuredFromEmail;

    public ServiceAdminMessagingController(SmsService smsService, EmailService emailService) {
        this.smsService = smsService;
        this.emailService = emailService;
    }

    /* ── guard ──────────────────────────────────────────────────────────── */

    private boolean isServiceAdmin(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null && "ServiceAdmin".equals(s.getAttribute("role"));
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", "Not signed in as Service Admin"));
    }

    private String actor(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        Object u = s != null ? s.getAttribute("serviceAdminUsername") : null;
        return u != null ? u.toString() : "ServiceAdmin";
    }

    /* ── defaults for the composer ──────────────────────────────────────── */

    @GetMapping("/api/serviceadmin/messaging/defaults")
    public ResponseEntity<Map<String, Object>> defaults(HttpServletRequest req) {
        if (!isServiceAdmin(req)) return unauthorized();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("smsFrom",       smsService.getFromNumber());
        out.put("smsConfigured", smsService.isConfigured());
        out.put("emailFrom",     configuredFromEmail);
        out.put("smsMaxChars",   SMS_MAX_CHARS);
        return ResponseEntity.ok(out);
    }

    /* ── recipient parsing / validation ─────────────────────────────────── */

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : raw.split("[,;\\n\\r]+")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Validates numbers without sending — backs the Preview button. */
    @PostMapping("/api/serviceadmin/messaging/sms/check")
    public ResponseEntity<Map<String, Object>> checkSms(@RequestBody Map<String, String> body,
                                                        HttpServletRequest req) {
        if (!isServiceAdmin(req)) return unauthorized();

        List<Map<String, String>> valid = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : split(body.get("to"))) {
            String e164 = PhoneNumbers.toE164(raw);
            if (e164 == null)          invalid.add(raw);
            else if (seen.add(e164))   valid.add(Map.of("input", raw, "e164", e164));
        }

        String from = body.get("from");
        String fromE164 = (from == null || from.isBlank())
                ? smsService.getFromNumber() : PhoneNumbers.toE164(from);

        String text = body.getOrDefault("body", "");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", valid);
        out.put("invalid", invalid);
        out.put("from", fromE164);
        out.put("fromValid", fromE164 != null);
        out.put("chars", text.length());
        out.put("segments", segments(text));
        out.put("unicode", !text.chars().allMatch(c -> c < 128));
        return ResponseEntity.ok(out);
    }

    /** GSM-7 vs UCS-2 segmenting, so the admin sees the real message count. */
    private static int segments(String text) {
        if (text.isEmpty()) return 0;
        boolean unicode = !text.chars().allMatch(c -> c < 128);
        int single = unicode ? 70 : 160, multi = unicode ? 67 : 153;
        return text.length() <= single ? 1 : (int) Math.ceil(text.length() / (double) multi);
    }

    /* ── send SMS ───────────────────────────────────────────────────────── */

    @PostMapping("/api/serviceadmin/messaging/sms/send")
    public ResponseEntity<Map<String, Object>> sendSms(@RequestBody Map<String, String> body,
                                                        HttpServletRequest req) {
        if (!isServiceAdmin(req)) return unauthorized();

        if (!smsService.isConfigured()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "SMS is not configured on this server (Twilio settings are missing)."));
        }
        String text = body.getOrDefault("body", "").trim();
        if (text.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message content is required."));
        }
        if (text.length() > SMS_MAX_CHARS) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Message is " + text.length() + " characters; the limit is " + SMS_MAX_CHARS + "."));
        }

        String from = body.get("from");
        if (from != null && !from.isBlank() && PhoneNumbers.toE164(from) == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "'From' is not a usable phone number: " + from));
        }

        List<String> raws = split(body.get("to"));
        if (raws.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "At least one recipient is required."));
        }
        List<String> unusable = new ArrayList<>();
        Set<String> targets = new LinkedHashSet<>();
        for (String raw : raws) {
            String e164 = PhoneNumbers.toE164(raw);
            if (e164 == null) unusable.add(raw); else targets.add(e164);
        }
        // Refuse the whole batch rather than half-sending: a partial blast the
        // admin did not intend is harder to undo than a rejected form.
        if (!unusable.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Fix these phone numbers before sending: " + String.join(", ", unusable)));
        }

        // Per recipient, so one bad number cannot hide the rest of the outcome.
        List<Map<String, Object>> results = new ArrayList<>();
        int sent = 0, failed = 0;
        for (String to : targets) {
            SmsService.SendOutcome o;
            try {
                o = smsService.sendWithOutcome(to, text, from);
            } catch (Exception e) {
                o = SmsService.SendOutcome.fail(e.getMessage());
            }
            if (o.sent()) sent++; else failed++;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("to", to);
            r.put("sent", o.sent());
            if (o.reason() != null) r.put("reason", o.reason());
            results.add(r);
        }
        log.info("ServiceAdmin SMS by {} — {} sent, {} failed", actor(req), sent, failed);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", failed == 0);
        out.put("sent", sent);
        out.put("failed", failed);
        out.put("results", results);
        out.put("message", failed == 0
                ? ("SMS sent to " + sent + " recipient" + (sent == 1 ? "" : "s") + ".")
                : (sent + " sent, " + failed + " failed — see the details below."));
        return ResponseEntity.ok(out);
    }

    /* ── send email ─────────────────────────────────────────────────────── */

    @PostMapping("/api/serviceadmin/messaging/email/send")
    public ResponseEntity<Map<String, Object>> sendEmail(@RequestBody Map<String, String> body,
                                                          HttpServletRequest req) {
        if (!isServiceAdmin(req)) return unauthorized();

        String subject = body.getOrDefault("subject", "").trim();
        String html    = body.getOrDefault("html", "").trim();
        if (subject.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Subject is required."));
        }
        if (html.isEmpty() || html.replaceAll("<[^>]*>", "").trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message content is required."));
        }

        String from = body.get("from");
        if (from != null && !from.isBlank() && !EMAIL.matcher(from.trim()).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "'From' is not a valid email address."));
        }

        List<String> to  = split(body.get("to"));
        List<String> bcc = split(body.get("bcc"));
        List<String> bad = new ArrayList<>();
        for (String a : to)  if (!EMAIL.matcher(a).matches()) bad.add(a);
        for (String a : bcc) if (!EMAIL.matcher(a).matches()) bad.add(a);
        if (!bad.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Fix these email addresses before sending: " + String.join(", ", bad)));
        }
        if (to.isEmpty() && bcc.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "At least one To or BCC recipient is required."));
        }

        try {
            emailService.sendComposed(to, bcc, subject, html, from, body.get("fromName"));
        } catch (Exception e) {
            log.error("ServiceAdmin email by {} failed — {}", actor(req), e.getMessage());
            return ResponseEntity.status(502).body(Map.of(
                    "error", "The mail server rejected the message: " + e.getMessage()));
        }
        int total = to.size() + bcc.size();
        log.info("ServiceAdmin email by {} — {} to, {} bcc, subject '{}'",
                 actor(req), to.size(), bcc.size(), subject);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("to", to.size());
        out.put("bcc", bcc.size());
        out.put("message", "Email sent to " + total + " recipient" + (total == 1 ? "" : "s")
                + (bcc.isEmpty() ? "." : " (" + bcc.size() + " blind copied)."));
        return ResponseEntity.ok(out);
    }
}
