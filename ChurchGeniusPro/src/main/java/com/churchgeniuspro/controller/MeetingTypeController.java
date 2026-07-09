package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.MeetingType;
import com.churchgeniuspro.service.MeetingTypeService;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;

/**
 * Handles requests for the Meeting Type admin page and REST API.
 *
 * <h3>Page routes</h3>
 * <ul>
 *   <li>{@code GET /meetingtype} → {@code meetingType.html}</li>
 * </ul>
 *
 * <h3>API routes</h3>
 * <ul>
 *   <li>{@code GET    /api/meeting-types}       → list all active meeting types</li>
 *   <li>{@code POST   /api/meeting-types}       → create a meeting type</li>
 *   <li>{@code PUT    /api/meeting-types/{id}}  → update a meeting type</li>
 *   <li>{@code DELETE /api/meeting-types/{id}}  → soft-delete a meeting type</li>
 * </ul>
 */
@Controller
public class MeetingTypeController {

    private final MeetingTypeService meetingTypeService;

    public MeetingTypeController(MeetingTypeService meetingTypeService) {
        this.meetingTypeService = meetingTypeService;
    }

    // ── Page route ────────────────────────────────────────────────────────

    @GetMapping("/meetingtype")
    public String meetingTypePage(HttpServletRequest request) {
        String deny = RoleGuard.requireAdmin(request);
        if (deny != null) return deny;
        return "forward:/meetingType.html";
    }

    // ── List ──────────────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/meeting-types")
    public ResponseEntity<List<Map<String, Object>>> getAll(HttpServletRequest request) {
        String appClientId = SessionUtil.getAppClientId(request);
        return ResponseEntity.ok(meetingTypeService.getAll(appClientId));
    }

    // ── Create ────────────────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/meeting-types")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        String typeName = body.get("typeName");
        if (typeName == null || typeName.isBlank()) {
            return bad("Meeting type name is required.");
        }
        String appClientId = SessionUtil.getAppClientId(request);
        try {
            MeetingType saved = meetingTypeService.create(typeName, appClientId);
            return ResponseEntity.ok(Map.of("id", saved.getId(), "success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    // ── Update ────────────────────────────────────────────────────────────

    @ResponseBody
    @PutMapping("/api/meeting-types/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Integer id,
                                                      @RequestBody Map<String, String> body) {
        String typeName = body.get("typeName");
        if (typeName == null || typeName.isBlank()) {
            return bad("Meeting type name is required.");
        }
        try {
            MeetingType saved = meetingTypeService.update(id, typeName);
            return ResponseEntity.ok(Map.of("id", saved.getId(), "success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    // ── Soft-Delete ───────────────────────────────────────────────────────

    @ResponseBody
    @DeleteMapping("/api/meeting-types/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Integer id) {
        try {
            meetingTypeService.delete(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
