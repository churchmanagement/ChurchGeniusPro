package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.MemberType;
import com.churchgeniuspro.service.MemberTypeService;
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
 * Handles requests for the Member Type admin page and REST API.
 *
 * <h3>Page routes</h3>
 * <ul>
 *   <li>{@code GET /membertype} → {@code memberType.html}</li>
 * </ul>
 *
 * <h3>API routes</h3>
 * <ul>
 *   <li>{@code GET    /api/member-types}       → list all active member types</li>
 *   <li>{@code POST   /api/member-types}       → create a member type</li>
 *   <li>{@code PUT    /api/member-types/{id}}  → update a member type</li>
 *   <li>{@code DELETE /api/member-types/{id}}  → soft-delete a member type</li>
 * </ul>
 */
@Controller
public class MemberTypeController {

    private final MemberTypeService memberTypeService;

    public MemberTypeController(MemberTypeService memberTypeService) {
        this.memberTypeService = memberTypeService;
    }

    // ── Page route ────────────────────────────────────────────────────────

    @GetMapping("/membertype")
    public String memberTypePage(HttpServletRequest request) {
        String deny = RoleGuard.requireAdmin(request);
        if (deny != null) return deny;
        return "forward:/memberType.html";
    }

    // ── List ──────────────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/member-types")
    public ResponseEntity<List<Map<String, Object>>> getAll(HttpServletRequest request) {
        String appClientId = SessionUtil.getAppClientId(request);
        return ResponseEntity.ok(memberTypeService.getAll(appClientId));
    }

    // ── Create ────────────────────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/member-types")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        String deny = RoleGuard.requireAdmin(request);   // same gate as the /membertype page
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String typeName = body.get("typeName");
        if (typeName == null || typeName.isBlank()) {
            return bad("Member type name is required.");
        }
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        try {
            MemberType saved = memberTypeService.create(typeName, appClientId);
            return ResponseEntity.ok(Map.of("id", saved.getId(), "success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    // ── Update ────────────────────────────────────────────────────────────

    @ResponseBody
    @PutMapping("/api/member-types/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Integer id,
                                                      @RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        String deny = RoleGuard.requireAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String typeName = body.get("typeName");
        if (typeName == null || typeName.isBlank()) {
            return bad("Member type name is required.");
        }
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        try {
            MemberType saved = memberTypeService.update(id, typeName, appClientId);
            return ResponseEntity.ok(Map.of("id", saved.getId(), "success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    // ── Soft-Delete ───────────────────────────────────────────────────────

    @ResponseBody
    @DeleteMapping("/api/member-types/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Integer id,
                                                      HttpServletRequest request) {
        String deny = RoleGuard.requireAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        try {
            memberTypeService.delete(id, appClientId);
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
