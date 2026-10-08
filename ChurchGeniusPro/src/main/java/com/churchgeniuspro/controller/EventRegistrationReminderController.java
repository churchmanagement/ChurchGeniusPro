package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.ChurchEvent;
import com.churchgeniuspro.hibernate.EventRegistrationReminderContact;
import com.churchgeniuspro.hibernate.EventRegistrationReminderLog;
import com.churchgeniuspro.repository.ChurchEventRepository;
import com.churchgeniuspro.repository.EventRegistrationReminderContactRepository;
import com.churchgeniuspro.repository.EventRegistrationReminderLogRepository;
import com.churchgeniuspro.service.EventRegistrationReminderService;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for the per-event <em>Registration Reminder</em> configuration
 * (see {@code EventRegistrationReminderService} for the scheduled send logic).
 *
 * <h3>Routes (authenticated, events-edit permission)</h3>
 * <ul>
 *   <li>{@code GET /api/events/{id}/registration-reminder}      → current config + contacts</li>
 *   <li>{@code PUT /api/events/{id}/registration-reminder}      → save config, replace contact list</li>
 *   <li>{@code GET /api/events/{id}/registration-reminder/logs} → attempt history (audit)</li>
 * </ul>
 *
 * <p>PUT body: {@code {"enabled":true,"daysBefore":5,
 * "contacts":[{"email":"a@b.com","phone":"(913) 333-0704"}, …]}}.
 * Each contact needs at least one of email/phone; blank rows are dropped.
 */
@RestController
public class EventRegistrationReminderController {

    private final ChurchEventRepository                      eventRepo;
    private final EventRegistrationReminderContactRepository contactRepo;
    private final EventRegistrationReminderLogRepository     logRepo;
    private final EventRegistrationReminderService           reminderService;

    public EventRegistrationReminderController(
            ChurchEventRepository                      eventRepo,
            EventRegistrationReminderContactRepository contactRepo,
            EventRegistrationReminderLogRepository     logRepo,
            EventRegistrationReminderService           reminderService) {
        this.eventRepo       = eventRepo;
        this.contactRepo     = contactRepo;
        this.logRepo         = logRepo;
        this.reminderService = reminderService;
    }

    // ── GET config ────────────────────────────────────────────────────────

    @GetMapping("/api/events/{id}/registration-reminder")
    public ResponseEntity<?> getConfig(@PathVariable Integer id, HttpServletRequest request) {
        ChurchEvent event = findOwnedEvent(id, request);
        if (event == null) return ResponseEntity.status(404).body(Map.of("error", "Event not found"));

        List<Map<String, Object>> contacts = new ArrayList<>();
        for (EventRegistrationReminderContact c : contactRepo.findByEventIdOrderByIdAsc(id)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id",    c.getId());
            row.put("email", c.getEmail());
            row.put("phone", c.getPhone());
            contacts.add(row);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled",    event.isRegistrationReminderEnabled());
        body.put("daysBefore", event.getRegistrationReminderDays());
        body.put("contacts",   contacts);
        return ResponseEntity.ok(body);
    }

    // ── PUT config (save + replace contact list) ──────────────────────────

    @PutMapping("/api/events/{id}/registration-reminder")
    @Transactional
    public ResponseEntity<?> saveConfig(@PathVariable Integer id,
                                        @RequestBody Map<String, Object> body,
                                        HttpServletRequest request) {
        String deny = RoleGuard.requirePermission(request, "general.events.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));

        ChurchEvent event = findOwnedEvent(id, request);
        if (event == null) return ResponseEntity.status(404).body(Map.of("error", "Event not found"));

        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));

        Integer daysBefore = null;
        Object rawDays = body.get("daysBefore");
        if (rawDays instanceof Number n) daysBefore = n.intValue();
        else if (rawDays instanceof String s && !s.isBlank()) {
            try { daysBefore = Integer.parseInt(s.trim()); }
            catch (NumberFormatException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "daysBefore must be a number"));
            }
        }
        if (enabled && (daysBefore == null || daysBefore < 0)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Days before event must be 0 or greater when the reminder is enabled"));
        }

        // ── Parse + validate contacts ──────────────────────────────────────
        List<EventRegistrationReminderContact> newContacts = new ArrayList<>();
        Object rawContacts = body.get("contacts");
        if (rawContacts instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) continue;
                String email = str(m.get("email"));
                String phone = str(m.get("phone"));
                if (email == null && phone == null) continue; // silently drop blank rows
                EventRegistrationReminderContact c = new EventRegistrationReminderContact();
                c.setEventId(id);
                c.setEmail(email);
                c.setPhone(phone);
                c.setAppClientId(event.getAppClientId());
                newContacts.add(c);
            }
        }
        if (enabled && newContacts.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Add at least one email or phone contact, or disable the reminder"));
        }

        // ── Persist: update event flags, replace contact list ─────────────
        event.setRegistrationReminderEnabled(enabled);
        event.setRegistrationReminderDays(daysBefore);
        eventRepo.save(event);

        contactRepo.deleteByEventId(id);
        contactRepo.saveAll(newContacts);

        return ResponseEntity.ok(Map.of("status", "saved", "contactCount", newContacts.size()));
    }

    // ── POST manual run ("Send Now") ──────────────────────────────────────

    /**
     * Runs the reminder for one event immediately. {@code force=true} (default)
     * bypasses the date match and the once-per-day gate so admins can test;
     * the already-registered check and per-recipient duplicate protection are
     * never bypassed, so repeated clicks cannot double-message anyone.
     */
    @org.springframework.web.bind.annotation.PostMapping("/api/events/{id}/registration-reminder/run")
    public ResponseEntity<?> runNow(@PathVariable Integer id,
                                    @org.springframework.web.bind.annotation.RequestParam(defaultValue = "true") boolean force,
                                    HttpServletRequest request) {
        String deny = RoleGuard.requirePermission(request, "general.events.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));

        ChurchEvent event = findOwnedEvent(id, request);
        if (event == null) return ResponseEntity.status(404).body(Map.of("error", "Event not found"));

        EventRegistrationReminderService.RunResult r = reminderService.runNowForEvent(event, force);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("processed",  r.processed());
        body.put("skipReason", r.skipReason);
        body.put("emailsSent", r.emailsSent);
        body.put("smsSent",    r.smsSent);
        body.put("skipped",    r.skipped);
        body.put("failed",     r.failed);
        if (r.testEmail != null) {                 // Phase B: Trial/Demo test copies
            body.put("testEmailsSent", r.testEmailsSent);
            body.put("testEmail",      r.testEmail);
        }
        return ResponseEntity.ok(body);
    }

    // ── POST send invitations ─────────────────────────────────────────────

    /**
     * Sends the event invitation (email and/or SMS with the RSVP link) to
     * every valid contact on the event's saved contact list. Each recipient
     * receives at most one invitation per event/channel — re-sending only
     * reaches newly added contacts. Returns a sent/skipped/failed summary.
     */
    @org.springframework.web.bind.annotation.PostMapping("/api/events/{id}/registration-reminder/invite")
    public ResponseEntity<?> sendInvite(@PathVariable Integer id, HttpServletRequest request) {
        String deny = RoleGuard.requirePermission(request, "general.events.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));

        ChurchEvent event = findOwnedEvent(id, request);
        if (event == null) return ResponseEntity.status(404).body(Map.of("error", "Event not found"));

        EventRegistrationReminderService.RunResult r = reminderService.sendInvitations(event);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("processed",  r.processed());
        body.put("skipReason", r.skipReason);
        body.put("emailsSent", r.emailsSent);
        body.put("smsSent",    r.smsSent);
        body.put("skipped",    r.skipped);
        body.put("failed",     r.failed);
        if (r.testEmail != null) {                 // Phase B: Trial/Demo test copies
            body.put("testEmailsSent", r.testEmailsSent);
            body.put("testEmail",      r.testEmail);
        }
        return ResponseEntity.ok(body);
    }

    // ── GET attempt logs (audit) ──────────────────────────────────────────

    @GetMapping("/api/events/{id}/registration-reminder/logs")
    public ResponseEntity<?> getLogs(@PathVariable Integer id, HttpServletRequest request) {
        ChurchEvent event = findOwnedEvent(id, request);
        if (event == null) return ResponseEntity.status(404).body(Map.of("error", "Event not found"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (EventRegistrationReminderLog l : logRepo.findByEventIdOrderByCreatedDateDesc(id)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("messageType", l.getMessageType());
            row.put("channel",    l.getChannel());
            row.put("recipient",  l.getRecipient());
            row.put("status",     l.getStatus());
            row.put("reason",     l.getReason());
            row.put("daysBefore", l.getDaysBefore());
            row.put("createdDate", l.getCreatedDate());
            rows.add(row);
        }
        return ResponseEntity.ok(rows);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** The event, but only when it exists, isn't deleted, and belongs to the caller's org. */
    private ChurchEvent findOwnedEvent(Integer id, HttpServletRequest request) {
        String appClientId = SessionUtil.getAppClientId(request);
        return eventRepo.findByIdAndDeleteFlagFalse(id)
                .filter(e -> e.getAppClientId() != null && e.getAppClientId().equals(appClientId))
                .orElse(null);
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
