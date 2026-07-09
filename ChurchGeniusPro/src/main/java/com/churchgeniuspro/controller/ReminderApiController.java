package com.churchgeniuspro.controller;

import com.churchgeniuspro.model.AutoReminderBO;
import com.churchgeniuspro.model.EventReminderBO;
import com.churchgeniuspro.model.MeetingReminderBO;
import com.churchgeniuspro.model.OneTimeReminderBO;
import com.churchgeniuspro.service.ReminderService;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST API controller for all four reminder types.
 *
 * <h3>Event Reminders</h3>
 * <ul>
 *   <li>{@code GET    /api/reminders/event}       — list all for org</li>
 *   <li>{@code POST   /api/reminders/event}       — create</li>
 *   <li>{@code PUT    /api/reminders/event/{id}}  — update</li>
 *   <li>{@code DELETE /api/reminders/event/{id}}  — delete</li>
 * </ul>
 *
 * <h3>Auto Reminders</h3>
 * <ul>
 *   <li>{@code GET    /api/reminders/auto}        — list all for org</li>
 *   <li>{@code POST   /api/reminders/auto}        — create</li>
 *   <li>{@code PUT    /api/reminders/auto/{id}}   — update</li>
 *   <li>{@code DELETE /api/reminders/auto/{id}}   — delete</li>
 * </ul>
 *
 * <h3>One-time Reminders</h3>
 * <ul>
 *   <li>{@code GET    /api/reminders/onetime}        — list all for org</li>
 *   <li>{@code POST   /api/reminders/onetime}        — create</li>
 *   <li>{@code PUT    /api/reminders/onetime/{id}}   — update</li>
 *   <li>{@code DELETE /api/reminders/onetime/{id}}   — delete</li>
 * </ul>
 *
 * <h3>Meeting Reminders</h3>
 * <ul>
 *   <li>{@code GET    /api/reminders/meeting}        — list all for org</li>
 *   <li>{@code POST   /api/reminders/meeting}        — create</li>
 *   <li>{@code PUT    /api/reminders/meeting/{id}}   — update</li>
 *   <li>{@code DELETE /api/reminders/meeting/{id}}   — delete</li>
 * </ul>
 */
@RestController
public class ReminderApiController {

    private final ReminderService service;

    public ReminderApiController(ReminderService service) {
        this.service = service;
    }

    // ── Event Reminders ───────────────────────────────────────────────────

    @GetMapping("/api/reminders/event")
    public ResponseEntity<List<Map<String, Object>>> getEventReminders(HttpServletRequest req) {
        return ResponseEntity.ok(service.getAllEventReminders(SessionUtil.getAppClientId(req)));
    }

    @PostMapping("/api/reminders/event")
    public ResponseEntity<Map<String, Object>> createEventReminder(
            @RequestBody EventReminderBO bo, HttpServletRequest req) {
        try {
            return ResponseEntity.ok(service.createEventReminder(bo, SessionUtil.getAppClientId(req)));
        } catch (IllegalArgumentException e) { return bad(e.getMessage()); }
    }

    @PutMapping("/api/reminders/event/{id}")
    public ResponseEntity<Map<String, Object>> updateEventReminder(
            @PathVariable Integer id, @RequestBody EventReminderBO bo) {
        try {
            return ResponseEntity.ok(service.updateEventReminder(id, bo));
        } catch (IllegalArgumentException e) { return bad(e.getMessage()); }
    }

    @DeleteMapping("/api/reminders/event/{id}")
    public ResponseEntity<Map<String, Object>> deleteEventReminder(@PathVariable Integer id) {
        try {
            service.deleteEventReminder(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) { return bad(e.getMessage()); }
    }

    // ── Auto Reminder Types ───────────────────────────────────────────────

    /** Returns all reminder types for the UI dropdown — no auth scoping needed. */
    @GetMapping("/api/reminders/auto-types")
    public ResponseEntity<List<Map<String, Object>>> getAutoReminderTypes() {
        return ResponseEntity.ok(service.getAllAutoReminderTypes());
    }

    // ── Auto Reminders ────────────────────────────────────────────────────

    @GetMapping("/api/reminders/auto")
    public ResponseEntity<List<Map<String, Object>>> getAutoReminders(HttpServletRequest req) {
        return ResponseEntity.ok(service.getAllAutoReminders(SessionUtil.getAppClientId(req)));
    }

    @PostMapping("/api/reminders/auto")
    public ResponseEntity<Map<String, Object>> createAutoReminder(
            @RequestBody AutoReminderBO bo, HttpServletRequest req) {
        try {
            return ResponseEntity.ok(service.createAutoReminder(bo, SessionUtil.getAppClientId(req)));
        } catch (IllegalArgumentException e) { return bad(e.getMessage()); }
    }

    @PutMapping("/api/reminders/auto/{id}")
    public ResponseEntity<Map<String, Object>> updateAutoReminder(
            @PathVariable Integer id, @RequestBody AutoReminderBO bo) {
        try {
            return ResponseEntity.ok(service.updateAutoReminder(id, bo));
        } catch (IllegalArgumentException e) { return bad(e.getMessage()); }
    }

    @DeleteMapping("/api/reminders/auto/{id}")
    public ResponseEntity<Map<String, Object>> deleteAutoReminder(@PathVariable Integer id) {
        try {
            service.deleteAutoReminder(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) { return bad(e.getMessage()); }
    }

    // ── One-Time Reminders ────────────────────────────────────────────────

    @GetMapping("/api/reminders/onetime")
    public ResponseEntity<List<Map<String, Object>>> getOneTimeReminders(HttpServletRequest req) {
        return ResponseEntity.ok(service.getAllOneTimeReminders(SessionUtil.getAppClientId(req)));
    }

    @PostMapping("/api/reminders/onetime")
    public ResponseEntity<Map<String, Object>> createOneTimeReminder(
            @RequestBody OneTimeReminderBO bo, HttpServletRequest req) {
        try {
            return ResponseEntity.ok(service.createOneTimeReminder(bo, SessionUtil.getAppClientId(req)));
        } catch (IllegalArgumentException e) { return bad(e.getMessage()); }
    }

    @PutMapping("/api/reminders/onetime/{id}")
    public ResponseEntity<Map<String, Object>> updateOneTimeReminder(
            @PathVariable Integer id, @RequestBody OneTimeReminderBO bo) {
        try {
            return ResponseEntity.ok(service.updateOneTimeReminder(id, bo));
        } catch (IllegalArgumentException e) { return bad(e.getMessage()); }
    }

    @DeleteMapping("/api/reminders/onetime/{id}")
    public ResponseEntity<Map<String, Object>> deleteOneTimeReminder(@PathVariable Integer id) {
        try {
            service.deleteOneTimeReminder(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) { return bad(e.getMessage()); }
    }

    // ── Meeting Reminders ─────────────────────────────────────────────────

    @GetMapping("/api/reminders/meeting")
    public ResponseEntity<List<Map<String, Object>>> getMeetingReminders(HttpServletRequest req) {
        return ResponseEntity.ok(service.getAllMeetingReminders(SessionUtil.getAppClientId(req)));
    }

    @PostMapping("/api/reminders/meeting")
    public ResponseEntity<Map<String, Object>> createMeetingReminder(
            @RequestBody MeetingReminderBO bo, HttpServletRequest req) {
        try {
            return ResponseEntity.ok(service.createMeetingReminder(bo, SessionUtil.getAppClientId(req)));
        } catch (IllegalArgumentException e) { return bad(e.getMessage()); }
    }

    @PutMapping("/api/reminders/meeting/{id}")
    public ResponseEntity<Map<String, Object>> updateMeetingReminder(
            @PathVariable Integer id, @RequestBody MeetingReminderBO bo) {
        try {
            return ResponseEntity.ok(service.updateMeetingReminder(id, bo));
        } catch (IllegalArgumentException e) { return bad(e.getMessage()); }
    }

    @DeleteMapping("/api/reminders/meeting/{id}")
    public ResponseEntity<Map<String, Object>> deleteMeetingReminder(@PathVariable Integer id) {
        try {
            service.deleteMeetingReminder(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) { return bad(e.getMessage()); }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
