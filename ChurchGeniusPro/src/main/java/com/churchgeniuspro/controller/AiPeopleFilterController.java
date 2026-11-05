package com.churchgeniuspro.controller;

import com.churchgeniuspro.ai.audit.AiAuditService;
import com.churchgeniuspro.ai.filter.PeopleFilter;
import com.churchgeniuspro.ai.filter.PeopleFilterService;
import com.churchgeniuspro.hibernate.AiSavedPeopleList;
import com.churchgeniuspro.repository.AiSavedPeopleListRepository;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.util.AppClock;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Phase 1 (2026-11-04): the people-filter API behind the chips and Saved Lists.
 * <ul>
 *   <li>{@code POST /api/ai-search/filter {predicates:[…]}} — re-run an edited filter (a chip removed).</li>
 *   <li>{@code GET|POST /api/ai-search/saved-lists}, {@code PUT|DELETE /…/{id}}, {@code POST /…/{id}/run},
 *       {@code GET /…/{id}/export} — named filters of the church; the list stores the filter, never the people.</li>
 * </ul>
 * Gates, in order: a signed-in staff session with a church ({@code SessionUtil}), the AI Assistant permission
 * ({@code more.aiassistant}, like every AI endpoint), the Saved Lists page permission ({@code more.savedlists})
 * for the saved-list calls, and then {@link PeopleFilterService}'s module rules for the data itself. All
 * paths sit under {@code /api/ai-search}, so the plan feature {@code aiSearch} and {@code AiGuardFilter}
 * (temp-access refusal, rate limit, audit) apply unchanged.
 */
@RestController
public class AiPeopleFilterController {

    public static final String PERM_SAVED_LISTS = "more.savedlists";
    static final int MAX_LISTS = 200;

    private final PeopleFilterService filters;
    private final AiAccessPolicy access;
    private final AiSavedPeopleListRepository lists;
    private final ObjectMapper mapper = new ObjectMapper();

    public AiPeopleFilterController(PeopleFilterService filters, AiAccessPolicy access, AiSavedPeopleListRepository lists) {
        this.filters = filters;
        this.access = access;
        this.lists = lists;
    }

    /* ─────────────── gates ─────────────── */

    /** The church id when the session may use the AI, else a ready error response. */
    private Object gate(HttpServletRequest req, boolean savedLists) {
        String clientId = SessionUtil.getAppClientId(req);
        if (clientId == null || clientId.isBlank() || req.getSession(false) == null || req.getSession(false).getAttribute("username") == null)
            return ResponseEntity.status(401).body(Map.of("error", "Not signed in."));
        if (!VoiceCommandController.isStaffSession(req) || RoleGuard.isKidsPortalSession(req.getSession(false)))
            return ResponseEntity.status(403).body(Map.of("error", "People filters are available to staff accounts."));
        if (RoleGuard.requireFeature(req, RoleGuard.PERM_AI_ASSISTANT) != null)
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to the AI Assistant."));
        if (savedLists) {
            // People filters list the church's people, so Saved Lists follow the Families page roles.
            String role = String.valueOf(req.getSession(false).getAttribute("role"));
            if (!("SuperAdmin".equals(role) || "Admin".equals(role) || "Accountant".equals(role)))
                return ResponseEntity.status(403).body(Map.of("error", "Saved Lists are available to SuperAdmin, Admin and Accountant accounts."));
            if (RoleGuard.requirePagePermission(req, PERM_SAVED_LISTS) != null)
                return ResponseEntity.status(403).body(Map.of("error", "You do not have access to Saved Lists."));
        }
        return clientId;
    }

    private PeopleFilter parse(Object predicates) {
        if (!(predicates instanceof List<?> l)) throw new IllegalArgumentException("predicates must be a list.");
        return PeopleFilter.fromJson(l);
    }

    private Map<String, Object> run(HttpServletRequest req, String clientId, PeopleFilter f) {
        LocalDate today = AppClock.today();
        Map<String, Object> m = filters.run(f, access.resolve(req, clientId), access.crossAccess(req, clientId), clientId, today);
        AiDataSearchController.audited(req, m);
        return m;
    }

    /* ─────────────── chips ─────────────── */

    @PostMapping("/api/ai-search/filter")
    public ResponseEntity<?> filter(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        Object g = gate(req, false);
        if (g instanceof ResponseEntity<?> r) return r;
        PeopleFilter f;
        try { f = parse(body == null ? null : body.get("predicates")); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
        return ResponseEntity.ok(run(req, (String) g, f));
    }

    /* ─────────────── saved lists ─────────────── */

    @GetMapping("/api/ai-search/saved-lists")
    public ResponseEntity<?> list(HttpServletRequest req) {
        Object g = gate(req, true);
        if (g instanceof ResponseEntity<?> r) return r;
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiSavedPeopleList l : lists.findByAppClientIdAndDeleteFlagFalseOrderByNameAsc((String) g)) out.add(view(l));
        return ResponseEntity.ok(Map.of("lists", out, "max", MAX_LISTS));
    }

    @PostMapping("/api/ai-search/saved-lists")
    public ResponseEntity<?> create(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        Object g = gate(req, true);
        if (g instanceof ResponseEntity<?> r) return r;
        String clientId = (String) g;
        String name = body == null || body.get("name") == null ? "" : String.valueOf(body.get("name")).trim();
        if (name.isEmpty() || name.length() > 120) return ResponseEntity.badRequest().body(Map.of("error", "Give the list a name (up to 120 characters)."));
        PeopleFilter f;
        try { f = parse(body.get("predicates")); } catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
        if (f.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "A saved list needs at least one condition."));
        if (!PeopleFilterService.missing(f, access.resolve(req, clientId), access.crossAccess(req, clientId)).isEmpty())
            return ResponseEntity.status(403).body(Map.of("error", "You can only save a list you are allowed to run."));
        if (lists.countByAppClientIdAndDeleteFlagFalse(clientId) >= MAX_LISTS) return ResponseEntity.status(409).body(Map.of("error", "This church already has " + MAX_LISTS + " saved lists."));
        if (lists.existsByAppClientIdAndDeleteFlagFalseAndNameIgnoreCase(clientId, name)) return ResponseEntity.status(409).body(Map.of("error", "A list with that name already exists."));
        AiSavedPeopleList l = new AiSavedPeopleList();
        l.setAppClientId(clientId); l.setName(name); l.setFilterJson(json(f));
        l.setCreatedBy(String.valueOf(req.getSession(false).getAttribute("username")));
        l = lists.save(l);
        AiAuditService.intent(req, "savedList.create");
        return ResponseEntity.ok(view(l));
    }

    @PutMapping("/api/ai-search/saved-lists/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        Object g = gate(req, true);
        if (g instanceof ResponseEntity<?> r) return r;
        String clientId = (String) g;
        AiSavedPeopleList l = lists.findByIdAndAppClientIdAndDeleteFlagFalse(id, clientId).orElse(null);
        if (l == null) return ResponseEntity.status(404).body(Map.of("error", "Saved list not found."));
        if (body != null && body.get("name") != null) {
            String name = String.valueOf(body.get("name")).trim();
            if (name.isEmpty() || name.length() > 120) return ResponseEntity.badRequest().body(Map.of("error", "Give the list a name (up to 120 characters)."));
            if (!name.equalsIgnoreCase(l.getName()) && lists.existsByAppClientIdAndDeleteFlagFalseAndNameIgnoreCase(clientId, name))
                return ResponseEntity.status(409).body(Map.of("error", "A list with that name already exists."));
            l.setName(name);
        }
        if (body != null && body.get("predicates") != null) {
            PeopleFilter f;
            try { f = parse(body.get("predicates")); } catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
            if (f.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "A saved list needs at least one condition."));
            if (!PeopleFilterService.missing(f, access.resolve(req, clientId), access.crossAccess(req, clientId)).isEmpty())
                return ResponseEntity.status(403).body(Map.of("error", "You can only save a list you are allowed to run."));
            l.setFilterJson(json(f)); l.setLastCount(null); l.setLastRunAt(null);
        }
        l.setUpdatedAt(LocalDateTime.now());
        AiAuditService.intent(req, "savedList.update");
        return ResponseEntity.ok(view(lists.save(l)));
    }

    @DeleteMapping("/api/ai-search/saved-lists/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, HttpServletRequest req) {
        Object g = gate(req, true);
        if (g instanceof ResponseEntity<?> r) return r;
        AiSavedPeopleList l = lists.findByIdAndAppClientIdAndDeleteFlagFalse(id, (String) g).orElse(null);
        if (l == null) return ResponseEntity.status(404).body(Map.of("error", "Saved list not found."));
        l.setDeleteFlag(true); l.setUpdatedAt(LocalDateTime.now()); lists.save(l);
        AiAuditService.intent(req, "savedList.delete");
        return ResponseEntity.ok(Map.of("status", "success"));
    }

    @PostMapping("/api/ai-search/saved-lists/{id}/run")
    public ResponseEntity<?> runList(@PathVariable Long id, HttpServletRequest req) {
        Object g = gate(req, true);
        if (g instanceof ResponseEntity<?> r) return r;
        AiSavedPeopleList l = lists.findByIdAndAppClientIdAndDeleteFlagFalse(id, (String) g).orElse(null);
        if (l == null) return ResponseEntity.status(404).body(Map.of("error", "Saved list not found."));
        PeopleFilter f;
        try { f = PeopleFilter.fromJson(mapper.readValue(l.getFilterJson(), List.class)); }
        catch (Exception e) { return ResponseEntity.status(409).body(Map.of("error", "This saved list can no longer be read. Please edit or delete it.")); }
        Map<String, Object> m = run(req, (String) g, f);
        if (Boolean.FALSE.equals(m.get("denied")) && m.get("total") != null) {
            l.setLastRunAt(LocalDateTime.now()); l.setLastCount((int) Math.min(Integer.MAX_VALUE, ((Number) m.get("total")).longValue())); lists.save(l);
        }
        Map<String, Object> out = new LinkedHashMap<>(m);
        out.put("list", view(l));
        return ResponseEntity.ok(out);
    }

    /** The matching people's e-mail addresses (up to 2,000) for the "Email this list" hand-off to the composer. */
    @GetMapping("/api/ai-search/saved-lists/{id}/emails")
    public ResponseEntity<?> emails(@PathVariable Long id, HttpServletRequest req) {
        Object g = gate(req, true);
        if (g instanceof ResponseEntity<?> r) return r;
        String clientId = (String) g;
        AiSavedPeopleList l = lists.findByIdAndAppClientIdAndDeleteFlagFalse(id, clientId).orElse(null);
        if (l == null) return ResponseEntity.status(404).body(Map.of("error", "Saved list not found."));
        PeopleFilter f;
        try { f = PeopleFilter.fromJson(mapper.readValue(l.getFilterJson(), List.class)); }
        catch (Exception e) { return ResponseEntity.status(409).body(Map.of("error", "This saved list can no longer be read.")); }
        Map<String, Object> m = filters.emails(f, access.resolve(req, clientId), access.crossAccess(req, clientId), clientId, AppClock.today(), 2000);
        AiAuditService.intent(req, "savedList.emails");
        if (Boolean.TRUE.equals(m.get("denied"))) return ResponseEntity.status(403).body(Map.of("error", m.get("answer")));
        return ResponseEntity.ok(m);
    }

    /** CSV of the current result (the rows the search would show, up to the cap); cells are formula-guarded. */
    @GetMapping("/api/ai-search/saved-lists/{id}/export")
    public ResponseEntity<?> export(@PathVariable Long id, HttpServletRequest req) {
        Object g = gate(req, true);
        if (g instanceof ResponseEntity<?> r) return r;
        String clientId = (String) g;
        AiSavedPeopleList l = lists.findByIdAndAppClientIdAndDeleteFlagFalse(id, clientId).orElse(null);
        if (l == null) return ResponseEntity.status(404).body(Map.of("error", "Saved list not found."));
        PeopleFilter f;
        try { f = PeopleFilter.fromJson(mapper.readValue(l.getFilterJson(), List.class)); }
        catch (Exception e) { return ResponseEntity.status(409).body(Map.of("error", "This saved list can no longer be read.")); }
        Map<String, Object> m = filters.run(f, access.resolve(req, clientId), access.crossAccess(req, clientId), clientId, AppClock.today(), 2000);
        AiAuditService.intent(req, "savedList.export");
        if (Boolean.TRUE.equals(m.get("denied"))) return ResponseEntity.status(403).body(Map.of("error", m.get("answer")));
        if (!PeopleFilterService.INTENT.equals(m.get("intent"))) return ResponseEntity.status(409).body(Map.of("error", m.get("answer")));
        @SuppressWarnings("unchecked") List<String> cols = (List<String>) m.get("columns");
        @SuppressWarnings("unchecked") List<List<String>> rows = (List<List<String>>) m.get("rows");
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(csv(l.getName())).append("\n").append(csv(String.valueOf(m.get("answer")))).append("\n\n");
        sb.append(String.join(",", cols.stream().map(AiPeopleFilterController::csv).toList())).append("\n");
        for (List<String> r : rows) sb.append(String.join(",", r.stream().map(AiPeopleFilterController::csv).toList())).append("\n");
        String fname = l.getName().replaceAll("[^A-Za-z0-9._-]+", "_") + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fname + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /* ─────────────── helpers ─────────────── */

    private Map<String, Object> view(AiSavedPeopleList l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId()); m.put("name", l.getName());
        List<?> preds = List.of();
        List<String> labels = new ArrayList<>();
        try {
            PeopleFilter f = PeopleFilter.fromJson(mapper.readValue(l.getFilterJson(), List.class));
            preds = f.toJson();
            for (PeopleFilter.Predicate p : f.predicates()) labels.add(p.label());
        } catch (Exception ignored) { m.put("unreadable", true); }
        m.put("predicates", preds); m.put("labels", labels);
        m.put("createdBy", l.getCreatedBy()); m.put("createdAt", l.getCreatedAt() == null ? null : l.getCreatedAt().toString());
        m.put("lastRunAt", l.getLastRunAt() == null ? null : l.getLastRunAt().toString()); m.put("lastCount", l.getLastCount());
        return m;
    }

    private String json(PeopleFilter f) {
        try { return mapper.writeValueAsString(f.toJson()); } catch (Exception e) { throw new IllegalArgumentException("Could not store the filter."); }
    }

    static String csv(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o);
        if (s.matches("^[=+\\-@\\t\\r].*")) s = "'" + s;          // spreadsheet formula injection guard
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) s = "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}
