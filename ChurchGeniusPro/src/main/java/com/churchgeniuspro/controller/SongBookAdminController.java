package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.SongAuditLog;
import com.churchgeniuspro.model.PageSlice;
import com.churchgeniuspro.service.SongBookAccessService;
import com.churchgeniuspro.service.SongBookService;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.format.DateTimeParseException;
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

    public SongBookAdminController(SongBookAccessService access, SongBookService service) {
        this.access = access;
        this.service = service;
    }

    private boolean isAdmin(HttpServletRequest req) {
        return SessionUtil.isAdminLike(req) || SessionUtil.isChurchAccount(req);
    }

    /**
     * GET /api/songbook/admin/members?offset=0&limit=10&q= — one page of portal members
     * with their Song Book access level (server-side paging and search; limit 1..100).
     * The body is a plain JSON array; {@code X-Has-More} says whether another page
     * exists and, on the first page only, {@code X-Total-Count} gives the match count.
     */
    @GetMapping("/members")
    public ResponseEntity<?> members(@RequestParam(defaultValue = "0") int offset,
                                     @RequestParam(defaultValue = "10") int limit,
                                     @RequestParam(defaultValue = "") String q,
                                     HttpServletRequest req) {
        if (!isAdmin(req)) return ResponseEntity.status(403).body(Map.of("error", "Admin only"));
        String clientId = SessionUtil.getAppClientId(req);
        if (clientId == null || clientId.isBlank()) return ResponseEntity.status(401).body(Map.of("error", "No church selected"));
        PageSlice<Map<String, Object>> page = access.memberPage(clientId, q, offset, limit);
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok()
                .header("X-Has-More", String.valueOf(page.hasMore()));
        if (offset <= 0) {
            long total = page.hasMore() ? access.memberCount(clientId, q) : page.rows().size();
            ok.header("X-Total-Count", String.valueOf(total));
        }
        return ok.body(page.rows());
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

    /**
     * GET /api/songbook/admin/audit?limit=10[&beforeTs=…&beforeId=…] — the activity log,
     * newest first, one page at a time. Without a cursor it returns the newest entries;
     * with the createdAt + id of the last row already shown it returns the next older
     * ones. {@code X-Has-More} says whether older entries remain.
     */
    @GetMapping("/audit")
    public ResponseEntity<?> audit(@RequestParam(defaultValue = "100") int limit,
                                   @RequestParam(required = false) String beforeTs,
                                   @RequestParam(required = false) Long beforeId,
                                   HttpServletRequest req) {
        if (!isAdmin(req)) return ResponseEntity.status(403).body(Map.of("error", "Admin only"));
        String clientId = SessionUtil.getAppClientId(req);
        if (clientId == null || clientId.isBlank()) return ResponseEntity.status(401).body(Map.of("error", "No church selected"));
        Instant cursorTs = null;
        if (beforeTs != null && !beforeTs.isBlank()) {
            try { cursorTs = Instant.parse(beforeTs.trim()); }
            catch (DateTimeParseException e) { return ResponseEntity.badRequest().body(Map.of("error", "Invalid beforeTs")); }
        }
        PageSlice<SongAuditLog> page = service.auditPage(clientId, cursorTs, beforeId, limit);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SongAuditLog l : page.rows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("action", l.getAction());
            m.put("actor", l.getActor());
            m.put("role", l.getRole());
            m.put("songTitle", l.getSongTitle());
            m.put("detail", l.getDetail());
            m.put("createdAt", l.getCreatedAt() == null ? null : l.getCreatedAt().toString());
            out.add(m);
        }
        return ResponseEntity.ok()
                .header("X-Has-More", String.valueOf(page.hasMore()))
                .body(out);
    }
}
