package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.Group;
import com.churchgeniuspro.service.GroupService;
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
 * Handles requests for the Groups admin page and REST API.
 *
 * <h3>Page routes</h3>
 * <ul>
 *   <li>{@code GET /groups} → {@code group.html}</li>
 * </ul>
 *
 * <h3>API routes</h3>
 * <ul>
 *   <li>{@code GET    /api/groups}       → list all active groups</li>
 *   <li>{@code POST   /api/groups}       → create a group</li>
 *   <li>{@code PUT    /api/groups/{id}}  → update a group</li>
 *   <li>{@code DELETE /api/groups/{id}}  → soft-delete a group</li>
 * </ul>
 */
@Controller
public class GroupController {

    private final GroupService groupService;

    public GroupController(GroupService groupService) {
        this.groupService = groupService;
    }

    // ── Page route ────────────────────────────────────────────────────────

    @GetMapping("/groups")
    public String groupPage(HttpServletRequest request) {
        // Members with admin.groups permission are allowed through
        jakarta.servlet.http.HttpSession session = request.getSession(false);
        boolean isMember = session != null
                && "Member".equals(session.getAttribute("role"))
                && session.getAttribute("memberId") != null;
        if (isMember) {
            String deny = RoleGuard.requireMemberPermission(request, "admin.groups");
            return deny != null ? deny : "forward:/group.html";
        }
        String deny = RoleGuard.requireAdmin(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePermission(request, "admin.groups");
        if (deny != null) return deny;
        return "forward:/group.html";
    }

    // ── List ──────────────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/groups")
    public ResponseEntity<List<Map<String, Object>>> getAll(HttpServletRequest request) {
        String appClientId = SessionUtil.getAppClientId(request);
        return ResponseEntity.ok(groupService.getAll(appClientId));
    }

    // ── Create ────────────────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/groups")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        String deny = RoleGuard.requirePermission(request, "admin.groups.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String groupName = body.get("groupName");
        if (groupName == null || groupName.isBlank()) {
            return bad("Group name is required.");
        }
        String appClientId = SessionUtil.getAppClientId(request);
        try {
            Group saved = groupService.create(groupName, appClientId);
            return ResponseEntity.ok(Map.of("id", saved.getId(), "success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    // ── Update ────────────────────────────────────────────────────────────

    @ResponseBody
    @PutMapping("/api/groups/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Integer id,
                                                      @RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        String deny = RoleGuard.requirePermission(request, "admin.groups.edit");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String groupName = body.get("groupName");
        if (groupName == null || groupName.isBlank()) {
            return bad("Group name is required.");
        }
        try {
            Group saved = groupService.update(id, groupName);
            return ResponseEntity.ok(Map.of("id", saved.getId(), "success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    // ── Soft-Delete ───────────────────────────────────────────────────────

    @ResponseBody
    @DeleteMapping("/api/groups/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Integer id,
                                                      HttpServletRequest request) {
        String deny = RoleGuard.requirePermission(request, "admin.groups.delete");
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        try {
            groupService.delete(id);
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
