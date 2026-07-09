package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.SongAuditLog;
import com.churchgeniuspro.service.FamilyService;
import com.churchgeniuspro.service.SongBookAccessService;
import com.churchgeniuspro.service.SongBookService;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin / Super Admin management for Song Book member access + the audit trail.
 * Lists portal members and lets an admin grant NONE / VIEW / FULL access.
 */
@RestController
@RequestMapping("/api/songbook/admin")
public class SongBookAdminController {

    private final SongBookAccessService access;
    private final SongBookService service;
    private final FamilyService familyService;

    public SongBookAdminController(SongBookAccessService access, SongBookService service,
                                   FamilyService familyService) {
        this.access = access;
        this.service = service;
        this.familyService = familyService;
    }

    private boolean isAdmin(HttpServletRequest req) {
        return SessionUtil.isAdminLike(req) || SessionUtil.isChurchAccount(req);
    }

    @GetMapping("/members")
    public ResponseEntity<?> members(HttpServletRequest req) {
        if (!isAdmin(req)) return ResponseEntity.status(403).body(Map.of("error", "Admin only"));
        String clientId = SessionUtil.getAppClientId(req);
        Map<Long, String> levels = access.levelsForChurch(clientId);

        List<Map<String, Object>> members = familyService.getAllMembers(
                "", "", "", "", "", false, false, false, clientId);

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : members) {
            Object idObj = m.get("id");
            if (idObj == null) continue;
            Long id;
            try { id = Long.valueOf(String.valueOf(idObj)); } catch (Exception e) { continue; }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("memberId", id);
            row.put("firstName", m.get("firstName"));
            row.put("lastName", m.get("lastName"));
            row.put("email", m.get("email"));
            row.put("role", m.get("role"));
            row.put("level", levels.getOrDefault(id, "NONE"));
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping("/access")
    public ResponseEntity<?> setAccess(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        if (!isAdmin(req)) return ResponseEntity.status(403).body(Map.of("error", "Admin only"));
        String clientId = SessionUtil.getAppClientId(req);
        String actor = SessionUtil.getUsername(req);
        Long memberId;
        try { memberId = Long.valueOf(String.valueOf(b.get("memberId"))); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "memberId required")); }
        String level = String.valueOf(b.getOrDefault("level", "NONE"));
        access.setLevel(clientId, memberId, level, actor);
        service.audit(clientId, actor, SessionUtil.getRole(req), "ACCESS_GRANT", null,
                null, "member " + memberId + " → " + level.toUpperCase());
        return ResponseEntity.ok(Map.of("memberId", memberId, "level", level.toUpperCase()));
    }

    @GetMapping("/audit")
    public ResponseEntity<?> audit(@RequestParam(defaultValue = "100") int limit, HttpServletRequest req) {
        if (!isAdmin(req)) return ResponseEntity.status(403).body(Map.of("error", "Admin only"));
        String clientId = SessionUtil.getAppClientId(req);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SongAuditLog l : service.recentAudit(clientId, limit)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", l.getAction());
            m.put("actor", l.getActor());
            m.put("role", l.getRole());
            m.put("songTitle", l.getSongTitle());
            m.put("detail", l.getDetail());
            m.put("createdAt", l.getCreatedAt() == null ? null : l.getCreatedAt().toString());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }
}
