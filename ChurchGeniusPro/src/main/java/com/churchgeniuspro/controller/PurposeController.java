package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.Purpose;
import com.churchgeniuspro.service.PurposeService;
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
 * Handles requests for the Purpose admin page and REST API.
 *
 * <h3>Page route</h3>
 * <ul>
 *   <li>{@code GET /purpose} → {@code purpose.html}</li>
 * </ul>
 *
 * <h3>REST API</h3>
 * <ul>
 *   <li>{@code GET    /api/purposes}      → list all purposes</li>
 *   <li>{@code POST   /api/purposes}      → create a purpose</li>
 *   <li>{@code PUT    /api/purposes/{id}} → update a purpose</li>
 *   <li>{@code DELETE /api/purposes/{id}} → soft-delete a purpose</li>
 * </ul>
 */
@Controller
public class PurposeController {

    private final PurposeService purposeService;

    public PurposeController(PurposeService purposeService) {
        this.purposeService = purposeService;
    }

    // ── Page route ────────────────────────────────────────────────────────

    @GetMapping("/purpose")
    public String purposePage(HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "accounting.settings");
        if (deny != null) return deny;
        return "forward:/purpose.html";
    }

    // ── REST ──────────────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/purposes")
    public ResponseEntity<List<Map<String, Object>>> getAll(HttpServletRequest request) {
        if (RoleGuard.requireAccountantOrAdmin(request) != null) return ResponseEntity.status(403).build();
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(purposeService.getAll(appClientId));
    }

    @ResponseBody
    @PostMapping("/api/purposes")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        String name = body.get("purposeName");
        if (name == null || name.isBlank()) {
            return bad("Purpose name is required.");
        }
        try {
            Purpose saved = purposeService.create(name, appClientId);
            return ResponseEntity.ok(Map.of("id", saved.getId(), "success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    @ResponseBody
    @PutMapping("/api/purposes/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable Integer id,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        String name = body.get("purposeName");
        if (name == null || name.isBlank()) {
            return bad("Purpose name is required.");
        }
        try {
            Purpose saved = purposeService.update(id, name, appClientId);
            return ResponseEntity.ok(Map.of("id", saved.getId(), "success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    @ResponseBody
    @DeleteMapping("/api/purposes/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Integer id,
                                                      HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        try {
            purposeService.delete(id, appClientId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
