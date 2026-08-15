package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.Meeting;
import com.churchgeniuspro.model.MeetingBO;
import com.churchgeniuspro.service.MeetingService;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles the Meetings page and all meeting REST API endpoints.
 *
 * <h3>Page routes</h3>
 * <ul>
 *   <li>{@code GET /meetings} → {@code meeting.html}</li>
 * </ul>
 *
 * <h3>API routes</h3>
 * <ul>
 *   <li>{@code GET    /api/meetings/locations}                    → location picker data</li>
 *   <li>{@code GET    /api/meetings/type/{typeId}/last-times}     → last start/end time for type</li>
 *   <li>{@code GET    /api/meetings}                              → list all active meetings</li>
 *   <li>{@code GET    /api/meetings/{id}}                         → single meeting detail</li>
 *   <li>{@code POST   /api/meetings}                              → create meeting</li>
 *   <li>{@code PUT    /api/meetings/{id}}                         → update meeting</li>
 *   <li>{@code DELETE /api/meetings/{id}}                         → soft-delete meeting</li>
 * </ul>
 */
@Controller
public class MeetingController {

    private final MeetingService meetingService;
    private final com.churchgeniuspro.service.MeetingOccurrenceService occurrenceService;

    public MeetingController(MeetingService meetingService,
                             com.churchgeniuspro.service.MeetingOccurrenceService occurrenceService) {
        this.meetingService = meetingService;
        this.occurrenceService = occurrenceService;
    }

    // ── Page route ────────────────────────────────────────────────────────

    @GetMapping("/meetings")
    public String meetingsPage(HttpServletRequest request) {
        // Meetings now lives under General, so any staff role plus Member portal
        // are allowed through the role check — granular access is decided by
        // requirePermission("general.meetings").
        String deny = RoleGuard.requireStaffOrMember(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePermission(request, "general.meetings");
        if (deny != null) return deny;
        return "forward:/meeting.html";
    }

    // ── Locations API ─────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/meetings/locations")
    public ResponseEntity<List<Map<String, Object>>> getLocations(HttpServletRequest request) {
        String appClientId = SessionUtil.getAppClientId(request);
        return ResponseEntity.ok(meetingService.getLocations(appClientId));
    }

    // ── Last times by type (for Start/End Time auto-fill) ─────────────────

    /**
     * Returns the {@code startTime} and {@code endTime} from the most recent
     * meeting of the given type.  Returns an empty JSON object {@code {}} when
     * no previous meeting of that type exists.
     */
    @ResponseBody
    @GetMapping("/api/meetings/type/{meetingTypeId}/last-times")
    public ResponseEntity<Map<String, Object>> getLastTimes(
            @PathVariable Integer meetingTypeId,
            HttpServletRequest request) {
        String appClientId = SessionUtil.getAppClientId(request);
        return ResponseEntity.ok(meetingService.getLastTimesByMeetingType(meetingTypeId, appClientId));
    }

    // ── List ──────────────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/meetings")
    public ResponseEntity<List<Map<String, Object>>> getAll(HttpServletRequest request) {
        String appClientId = SessionUtil.getAppClientId(request);
        return ResponseEntity.ok(meetingService.getAll(appClientId));
    }

    // ── Single ────────────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/meetings/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable Integer id) {
        try {
            return ResponseEntity.ok(meetingService.getById(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    // ── Create ────────────────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/meetings")
    public ResponseEntity<Map<String, Object>> create(@RequestBody MeetingBO bo,
                                                      HttpServletRequest request) {
        String deny = RoleGuard.requirePermission(request, "general.meetings.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        try {
            Meeting m = meetingService.save(bo, appClientId);
            return ResponseEntity.ok(Map.of("id", m.getId(), "success", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    // ── Update ────────────────────────────────────────────────────────────

    @ResponseBody
    @PutMapping("/api/meetings/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Integer id,
                                                      @RequestBody MeetingBO bo,
                                                      HttpServletRequest request) {
        String deny = RoleGuard.requirePermission(request, "general.meetings.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        try {
            Meeting m = meetingService.update(id, bo);
            return ResponseEntity.ok(Map.of("id", m.getId(), "success", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    // ── Soft-Delete ───────────────────────────────────────────────────────

    @ResponseBody
    @DeleteMapping("/api/meetings/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Integer id,
                                                      HttpServletRequest request) {
        String deny = RoleGuard.requirePermission(request, "general.meetings.delete");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        try {
            meetingService.delete(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    // ── Occurrences of a recurring meeting ────────────────────────────────

    /**
     * Lists upcoming occurrences of a (recurring) meeting from today, each
     * flagged {@code skipped} when that single occurrence has been deleted.
     */
    @ResponseBody
    @GetMapping("/api/meetings/{id}/occurrences")
    public ResponseEntity<Map<String, Object>> occurrences(
            @PathVariable Integer id,
            @RequestParam(name = "limit", defaultValue = "26") int limit,
            HttpServletRequest request) {
        String deny = RoleGuard.requirePermission(request, "general.meetings");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        try {
            return ResponseEntity.ok(occurrenceService.listOccurrences(id, appClientId, limit));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Deletes one or more single occurrences of a recurring meeting.
     * Body: {@code {"dates": ["2026-08-04", "2026-08-11"]}}. The rest of the
     * series (and its reminders) continues unchanged; the skipped dates no
     * longer appear on the Meetings page or Event Calendar and generate no
     * reminders of any kind.
     */
    @ResponseBody
    @PostMapping("/api/meetings/{id}/occurrences/delete")
    public ResponseEntity<Map<String, Object>> deleteOccurrences(
            @PathVariable Integer id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        String deny = RoleGuard.requirePermission(request, "general.meetings.delete");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        try {
            List<java.time.LocalDate> dates = parseDates(body.get("dates"));
            int n = occurrenceService.deleteOccurrences(id, appClientId, dates);
            return ResponseEntity.ok(Map.of("success", true, "deleted", n));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    /** Restores previously deleted occurrences. Body: {@code {"dates": [...]}}. */
    @ResponseBody
    @PostMapping("/api/meetings/{id}/occurrences/restore")
    public ResponseEntity<Map<String, Object>> restoreOccurrences(
            @PathVariable Integer id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        String deny = RoleGuard.requirePermission(request, "general.meetings.delete");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        try {
            List<java.time.LocalDate> dates = parseDates(body.get("dates"));
            int n = occurrenceService.restoreOccurrences(id, appClientId, dates);
            return ResponseEntity.ok(Map.of("success", true, "restored", n));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    /**
     * Deletes an occurrence and ALL future occurrences by ending the series
     * the day before. Body: {@code {"fromDate": "2026-08-04"}}. If nothing
     * would remain, the whole series is soft-deleted.
     */
    @ResponseBody
    @PostMapping("/api/meetings/{id}/occurrences/delete-future")
    public ResponseEntity<Map<String, Object>> deleteFutureOccurrences(
            @PathVariable Integer id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        String deny = RoleGuard.requirePermission(request, "general.meetings.delete");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        try {
            Object raw = body.get("fromDate");
            java.time.LocalDate fromDate = raw instanceof String s && !s.isBlank()
                    ? java.time.LocalDate.parse(s.trim()) : null;
            Map<String, Object> result =
                    occurrenceService.deleteFromDate(id, appClientId, fromDate);
            Map<String, Object> out = new java.util.LinkedHashMap<>(result);
            out.put("success", true);
            return ResponseEntity.ok(out);
        } catch (java.time.format.DateTimeParseException e) {
            return bad("Invalid date format — expected yyyy-MM-dd.");
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    /** Parses a JSON array of ISO dates into LocalDates (invalid entries rejected). */
    private static List<java.time.LocalDate> parseDates(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("No dates provided.");
        }
        List<java.time.LocalDate> out = new ArrayList<>();
        for (Object o : list) {
            if (o == null) continue;
            try {
                out.add(java.time.LocalDate.parse(String.valueOf(o).trim()));
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid date: " + o);
            }
        }
        if (out.isEmpty()) throw new IllegalArgumentException("No valid dates provided.");
        return out;
    }

    // ── Bulk Delete ───────────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/meetings/delete-bulk")
    public ResponseEntity<Map<String, Object>> deleteBulk(
            @RequestBody List<Integer> ids,
            HttpServletRequest request) {
        String deny = RoleGuard.requireAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        if (RoleGuard.requirePermission(request, "general.meetings.delete") != null)
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        if (ids == null || ids.isEmpty())
            return bad("No meeting IDs provided.");
        int count = meetingService.deleteBulk(ids);
        return ResponseEntity.ok(Map.of("success", true, "deleted", count));
    }

    // ── Auto-Purge (>90 days, not flagged) ───────────────────────────────

    @ResponseBody
    @PostMapping("/api/meetings/auto-purge")
    public ResponseEntity<Map<String, Object>> autoPurge(HttpServletRequest request) {
        String deny = RoleGuard.requireAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        if (RoleGuard.requirePermission(request, "general.meetings.delete") != null)
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        String appClientId = SessionUtil.getAppClientId(request);
        int count = meetingService.autoPurgeOldMeetings(appClientId);
        return ResponseEntity.ok(Map.of("success", true, "purged", count));
    }

    // ── Manual Notify ─────────────────────────────────────────────────────────

    /**
     * Sends an on-demand Email and/or SMS for a single meeting.
     *
     * <p>Expected body:
     * <pre>
     * {
     *   "channels":   ["Email", "SMS"],
     *   "recipients": ["Members", "Guests"]
     * }
     * </pre>
     */
    @ResponseBody
    @PostMapping("/api/meetings/{id}/notify")
    public ResponseEntity<Map<String, Object>> notify(@PathVariable Integer id,
                                                      @RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        String deny = RoleGuard.requirePermission(request, "general.meetings.notify");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);

        @SuppressWarnings("unchecked")
        List<String> channels   = body.get("channels")   instanceof List ? (List<String>) body.get("channels")   : List.of();
        @SuppressWarnings("unchecked")
        List<String> recipients = body.get("recipients") instanceof List ? (List<String>) body.get("recipients") : List.of();

        if (channels.isEmpty())   return bad("Please select at least one channel (Email or SMS).");
        if (recipients.isEmpty()) return bad("Please select at least one recipient group.");

        try {
            Map<String, Object> result = meetingService.sendManualNotification(id, channels, recipients, appClientId);
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Failed to send notifications: " + e.getMessage()));
        }
    }

    // ── Image Upload ──────────────────────────────────────────────────────

    /**
     * Uploads a banner/flyer image for a single meeting.
     * Accepts: multipart/form-data with field "file" (image/jpeg, image/png, …).
     */
    @ResponseBody
    @PostMapping(value = "/api/meetings/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadImage(
            @PathVariable Integer id,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        String deny = RoleGuard.requireAuth(request);
        if (deny != null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        if (RoleGuard.requirePermission(request, "general.meetings.edit") != null)
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        if (file == null || file.isEmpty())
            return bad("No file selected.");
        String ct = file.getContentType();
        if (ct == null || !ct.startsWith("image/"))
            return bad("Only image files are allowed.");
        try {
            meetingService.saveImage(id, file.getBytes(), ct);
            return ResponseEntity.ok(Map.of("success", true, "imageUrl", "/api/meetings/" + id + "/image"));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Image Serve ───────────────────────────────────────────────────────

    /**
     * Serves the stored image for a meeting.
     * Returns 404 if no image has been uploaded.
     */
    @GetMapping("/api/meetings/{id}/image")
    public ResponseEntity<byte[]> getImage(@PathVariable Integer id) {
        return meetingService.getImage(id);
    }

    // ── Image Delete ──────────────────────────────────────────────────────

    @ResponseBody
    @DeleteMapping("/api/meetings/{id}/image")
    public ResponseEntity<Map<String, Object>> deleteImage(
            @PathVariable Integer id,
            HttpServletRequest request) {
        String deny = RoleGuard.requireAuth(request);
        if (deny != null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        if (RoleGuard.requirePermission(request, "general.meetings.edit") != null)
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        try {
            meetingService.deleteImage(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
