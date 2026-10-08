package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.MeetingMessageTemplate;
import com.churchgeniuspro.service.MeetingMessageTemplateService;
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
 * REST endpoints for managing meeting message templates (Meetings screen).
 * Reads are tenant-scoped; mutations require the meetings edit permission.
 */
@Controller
public class MeetingTemplateController {

    private final MeetingMessageTemplateService service;

    public MeetingTemplateController(MeetingMessageTemplateService service) {
        this.service = service;
    }

    @ResponseBody
    @GetMapping("/api/meeting-templates")
    public ResponseEntity<List<Map<String, Object>>> list(HttpServletRequest request) {
        String appClientId = SessionUtil.getAppClientId(request);
        return ResponseEntity.ok(service.getAll(appClientId));
    }

    @ResponseBody
    @PostMapping("/api/meeting-templates")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body,
                                                       HttpServletRequest request) {
        String deny = writeGuard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return unauthorized();
        try {
            MeetingMessageTemplate saved = service.create(
                    str(body.get("name")), str(body.get("body")),
                    str(body.get("dateFormat")), str(body.get("timeFormat")),
                    Boolean.TRUE.equals(body.get("makeDefault")), appClientId);
            return ResponseEntity.ok(Map.of("id", saved.getId(), "success", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @ResponseBody
    @PutMapping("/api/meeting-templates/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Integer id,
                                                      @RequestBody Map<String, Object> body,
                                                      HttpServletRequest request) {
        String deny = writeGuard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        if (id == null || id == MeetingMessageTemplateService.BUILT_IN_ID) {
            return bad("The built-in default template can't be edited. Save it as a new template instead.");
        }
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return unauthorized();
        try {
            service.update(id, str(body.get("name")), str(body.get("body")),
                    str(body.get("dateFormat")), str(body.get("timeFormat")), appClientId);
            return ResponseEntity.ok(Map.of("id", id, "success", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @ResponseBody
    @DeleteMapping("/api/meeting-templates/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Integer id, HttpServletRequest request) {
        String deny = writeGuard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        if (id == null || id == MeetingMessageTemplateService.BUILT_IN_ID) {
            return bad("The built-in default template can't be deleted.");
        }
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return unauthorized();
        try {
            service.delete(id, appClientId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @ResponseBody
    @PostMapping("/api/meeting-templates/{id}/default")
    public ResponseEntity<Map<String, Object>> setDefault(@PathVariable Integer id, HttpServletRequest request) {
        String deny = writeGuard(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return unauthorized();
        try {
            service.setDefault(id, appClientId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
    }

    /** Same guard pair as the /meetings page: role check, then the granular permission. */
    private static String writeGuard(HttpServletRequest request) {
        String deny = RoleGuard.requireStaffOrMember(request);
        if (deny != null) return deny;
        return RoleGuard.requirePermission(request, "general.meetings.edit");
    }
}
