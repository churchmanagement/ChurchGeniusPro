package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.KmCheckin;
import com.churchgeniuspro.hibernate.KmChild;
import com.churchgeniuspro.hibernate.KmChildSetup;
import com.churchgeniuspro.hibernate.KmClassroom;
import com.churchgeniuspro.repository.KmCheckinRepository;
import com.churchgeniuspro.repository.KmChildRepository;
import com.churchgeniuspro.repository.KmChildSetupRepository;
import com.churchgeniuspro.repository.KmClassroomRepository;
import com.churchgeniuspro.util.EncryptionUtil;
import com.churchgeniuspro.util.KmPickupUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Builds and sends overdue-pickup alerts (email + SMS) for the Kids Ministry
 * module, and broadcasts the "picked up" confirmation. Invoked on a schedule by
 * {@code KmPickupAlertScheduler}; the public pickup page calls
 * {@link #broadcastPickedUp} when a recipient confirms a pickup.
 */
@Service
public class KmPickupAlertService {

    private static final Logger log = LoggerFactory.getLogger(KmPickupAlertService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a");

    private final KmChildSetupRepository setupRepo;
    private final KmCheckinRepository    checkinRepo;
    private final KmChildRepository      childRepo;
    private final KmClassroomRepository  classroomRepo;
    private final EmailService           emailService;
    private final SmsService             smsService;

    @Value("${app.base-url:}")
    private String baseUrl;

    /** Don't re-alert the same overdue child more often than this. */
    @Value("${km.pickup.realert-minutes:15}")
    private int reAlertMinutes;

    public KmPickupAlertService(KmChildSetupRepository setupRepo,
                                KmCheckinRepository checkinRepo,
                                KmChildRepository childRepo,
                                KmClassroomRepository classroomRepo,
                                EmailService emailService,
                                SmsService smsService) {
        this.setupRepo     = setupRepo;
        this.checkinRepo   = checkinRepo;
        this.childRepo     = childRepo;
        this.classroomRepo = classroomRepo;
        this.emailService  = emailService;
        this.smsService    = smsService;
    }

    public record Recipient(String name, String email, String phone) {}

    // ───────────────────────── scheduled scan ─────────────────────────

    /** Scan every tenant with alerts configured and fire overdue alerts. */
    public void sendOverdueAlerts() {
        for (KmChildSetup setup : setupRepo.findAll()) {
            if (setup == null) continue;
            if (!setup.isEmailAlertsEnabled() && !setup.isSmsAlertsEnabled()) continue;
            List<Recipient> recipients = parseRecipients(setup.getAlertRecipients());
            if (recipients.isEmpty()) continue;
            try {
                processTenant(setup, recipients);
            } catch (Exception e) {
                log.warn("KM pickup alert scan failed for tenant {}: {}", setup.getClientId(), e.getMessage());
            }
        }
    }

    private void processTenant(KmChildSetup setup, List<Recipient> recipients) {
        String clientId = setup.getClientId();
        LocalDateTime now = LocalDateTime.now();

        Map<Long, String> classNames = new HashMap<>();
        for (KmClassroom r : classroomRepo.findByClientIdAndDeleteFlagFalseOrderByMinAgeAscClassNameAsc(clientId))
            classNames.put(r.getId(), r.getClassName());

        for (KmCheckin ci : checkinRepo.findByClientIdAndCheckoutTimeIsNullOrderByCheckinTimeDesc(clientId)) {
            if (ci.getChildId() == null) continue;
            LocalDateTime deadline = KmPickupUtil.effectiveDeadline(ci, setup);
            if (deadline == null || !now.isAfter(deadline)) continue;                  // not overdue
            if (ci.getSnoozeUntil() != null && ci.getSnoozeUntil().isAfter(now)) continue;  // snoozed
            if (ci.getLastAlertSentAt() != null
                    && ci.getLastAlertSentAt().isAfter(now.minusMinutes(reAlertMinutes))) continue;  // recently alerted

            KmChild child = childRepo.findById(ci.getChildId()).orElse(null);
            if (child == null) continue;
            if (Boolean.FALSE.equals(child.getPickupAlertsEnabled())) continue;   // per-child opt-out

            long overdueMins = ChronoUnit.MINUTES.between(deadline, now);
            String classroom = ci.getClassroomId() != null ? classNames.getOrDefault(ci.getClassroomId(), "") : "";
            String url = pickupUrl(clientId, ci.getId());

            if (setup.isEmailAlertsEnabled()) {
                String subject = "Overdue pickup: " + safe(child.getFirstName()) + " " + safe(child.getLastName());
                String html = buildEmailHtml(child, ci, deadline, overdueMins, classroom, url);
                for (Recipient r : recipients)
                    if (notBlank(r.email())) {
                        try { emailService.sendOrgEmail(r.email().trim(), subject, html, clientId); }
                        catch (Exception e) { log.warn("pickup email to {} failed: {}", r.email(), e.getMessage()); }
                    }
            }
            if (setup.isSmsAlertsEnabled() && smsService.isConfigured()) {
                String sms = buildSms(child, overdueMins, url);
                for (Recipient r : recipients)
                    if (notBlank(r.phone())) {
                        try { smsService.send(r.phone().trim(), sms); }
                        catch (Exception e) { log.warn("pickup SMS to {} failed: {}", r.phone(), e.getMessage()); }
                    }
            }
            ci.setLastAlertSentAt(now);
            checkinRepo.save(ci);
            log.info("KM overdue pickup alert sent for child {} (checkin {}), {} min overdue",
                ci.getChildId(), ci.getId(), overdueMins);
        }
    }

    // ───────────────────────── picked-up broadcast ─────────────────────────

    /** Notify all configured recipients that {@code byName} confirmed the pickup. */
    public void broadcastPickedUp(String clientId, String childName, String byName) {
        KmChildSetup setup = setupRepo.findByClientId(clientId).orElse(null);
        if (setup == null) return;
        List<Recipient> recipients = parseRecipients(setup.getAlertRecipients());
        if (recipients.isEmpty()) return;
        String msg = "\"" + (notBlank(byName) ? byName : "A recipient")
                + "\" has confirmed that " + (notBlank(childName) ? childName : "the child")
                + " has been picked up. Alert notifications have been stopped.";
        String subject = "Pickup confirmed: " + (notBlank(childName) ? childName : "child");
        for (Recipient r : recipients) {
            if (setup.isEmailAlertsEnabled() && notBlank(r.email())) {
                try { emailService.sendOrgEmail(r.email().trim(), subject,
                        "<p style=\"font-family:Arial,sans-serif;font-size:15px;\">" + esc(msg) + "</p>", clientId); }
                catch (Exception e) { log.warn("pickup-confirm email failed: {}", e.getMessage()); }
            }
            if (setup.isSmsAlertsEnabled() && smsService.isConfigured() && notBlank(r.phone())) {
                try { smsService.send(r.phone().trim(), msg); }
                catch (Exception e) { log.warn("pickup-confirm SMS failed: {}", e.getMessage()); }
            }
        }
    }

    // ───────────────────────── helpers ─────────────────────────

    public String pickupUrl(String clientId, Long checkinId) {
        try {
            String token = EncryptionUtil.encrypt(clientId + "|" + checkinId);
            return baseUrl + "/kidsPickup?t=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return baseUrl + "/kidsPickup";
        }
    }

    public List<Recipient> parseRecipients(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> raw = MAPPER.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            List<Recipient> out = new ArrayList<>();
            for (Map<String, Object> m : raw) {
                out.add(new Recipient(str(m.get("name")), str(m.get("email")), str(m.get("phone"))));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String buildEmailHtml(KmChild c, KmCheckin ci, LocalDateTime deadline,
                                  long overdueMins, String classroom, String url) {
        StringBuilder b = new StringBuilder();
        b.append("<div style=\"font-family:Arial,sans-serif;font-size:14px;color:#333;\">");
        b.append("<h2 style=\"color:#b23b3b;\">⏰ Overdue Pickup Alert</h2>");
        b.append("<p>The following child has not been picked up by the scheduled time:</p>");
        b.append("<table style=\"border-collapse:collapse;font-size:14px;\">");
        row(b, "Child Name", safe(c.getFirstName()) + " " + safe(c.getLastName()));
        row(b, "Child ID", String.valueOf(c.getId()));
        row(b, "Parent / Guardian", safe(c.getParentName()));
        row(b, "Parent Phone", safe(c.getParentPhone()));
        row(b, "Parent Email", safe(c.getParentEmail()));
        row(b, "Emergency Contact", safe(c.getEmergencyContactName()));
        row(b, "Emergency Phone", safe(c.getEmergencyContactPhone()));
        row(b, "Classroom", classroom);
        row(b, "Check-In Time", ci.getCheckinTime() != null ? ci.getCheckinTime().format(TIME) : "");
        row(b, "Scheduled Pickup", deadline != null ? deadline.format(TIME) : "");
        row(b, "Overdue By", fmtMins(overdueMins));
        b.append("</table>");
        b.append("<p style=\"margin-top:16px;\"><a href=\"").append(url)
         .append("\" style=\"background:#673147;color:#fff;padding:10px 18px;border-radius:8px;text-decoration:none;\">")
         .append("View status, snooze, or mark picked up</a></p>");
        b.append("</div>");
        return b.toString();
    }

    private String buildSms(KmChild c, long overdueMins, String url) {
        return "Overdue pickup: " + safe(c.getFirstName()) + " " + safe(c.getLastName())
                + " is " + fmtMins(overdueMins) + " overdue. View / snooze / confirm pickup: " + url;
    }

    private void row(StringBuilder b, String k, String v) {
        b.append("<tr><td style=\"padding:3px 12px 3px 0;color:#888;\">").append(esc(k))
         .append("</td><td style=\"padding:3px 0;font-weight:600;\">").append(esc(v == null ? "" : v))
         .append("</td></tr>");
    }

    private static String fmtMins(long mins) {
        long h = mins / 60, m = mins % 60;
        return (h > 0 ? h + "h " : "") + m + "m";
    }

    private static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
    private static String safe(String s) { return s == null ? "" : s; }
    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
