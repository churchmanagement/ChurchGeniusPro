package com.churchgeniuspro.controller;

import com.churchgeniuspro.ai.AiRateLimiter;
import com.churchgeniuspro.ai.llm.LlmGateway;
import com.churchgeniuspro.hibernate.AiAuditLog;
import com.churchgeniuspro.repository.AiAuditLogRepository;
import com.churchgeniuspro.util.PermissionCatalog;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only views of the AI audit log (AI Phase 0, 2026-11-04).
 * <ul>
 *   <li>{@code GET /api/ai/activity?limit=10[&beforeTs=&beforeId=]} — a church's own rows, newest
 *       first, keyset paging (same contract as the Access Audit): SuperAdmin / Admin or the church login.</li>
 *   <li>{@code GET /api/ai/activity/summary?days=30} — per-channel counts and tokens for the church,
 *       plus the caller's current rate-limit usage.</li>
 *   <li>{@code GET /api/serviceadmin/ai/activity?days=30} — every church's counts and tokens
 *       (Service Admin only; gated by {@code ServiceAdminAuthFilter}).</li>
 *   <li>{@code GET /api/permissions/catalog} — the server-side permission key list.</li>
 * </ul>
 * No row carries question text, so nothing here can leak what a person asked.
 */
@RestController
public class AiActivityController {

    private final AiAuditLogRepository repo;
    private final AiRateLimiter limiter;
    private final LlmGateway gateway;

    public AiActivityController(AiAuditLogRepository repo, AiRateLimiter limiter, LlmGateway gateway) {
        this.repo = repo;
        this.limiter = limiter;
        this.gateway = gateway;
    }

    @GetMapping("/api/ai/activity")
    public ResponseEntity<?> activity(@RequestParam(defaultValue = "10") int limit,
                                      @RequestParam(required = false) String beforeTs,
                                      @RequestParam(required = false) Long beforeId,
                                      HttpServletRequest req) {
        String clientId = guard(req);
        if (clientId == null) return deny(req);
        int size = Math.max(1, Math.min(100, limit));
        List<AiAuditLog> rows;
        if (beforeTs != null && !beforeTs.isBlank()) {
            LocalDateTime ts;
            try { ts = LocalDateTime.parse(beforeTs.trim()); }
            catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "beforeTs must be an ISO local date-time.")); }
            rows = repo.findPageBefore(clientId, ts, beforeId == null ? Long.MAX_VALUE : beforeId, PageRequest.of(0, size + 1));
        } else {
            rows = repo.findNewestPage(clientId, PageRequest.of(0, size + 1));
        }
        boolean hasMore = rows.size() > size;
        if (hasMore) rows = rows.subList(0, size);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiAuditLog r : rows) out.add(row(r));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", out);
        body.put("hasMore", hasMore);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/ai/activity/summary")
    public ResponseEntity<?> summary(@RequestParam(defaultValue = "30") int days, HttpServletRequest req) {
        String clientId = guard(req);
        if (clientId == null) return deny(req);
        int d = Math.max(1, Math.min(366, days));
        LocalDateTime since = LocalDateTime.now().minusDays(d);
        List<Map<String, Object>> channels = new ArrayList<>();
        long calls = 0, tokens = 0;
        for (Object[] r : repo.summaryByChannel(clientId, since)) {
            Map<String, Object> m = new LinkedHashMap<>();
            long n = ((Number) r[1]).longValue(), p = ((Number) r[2]).longValue(), c = ((Number) r[3]).longValue();
            m.put("channel", r[0]); m.put("calls", n); m.put("promptTokens", p); m.put("completionTokens", c);
            m.put("refused", ((Number) r[4]).longValue());
            channels.add(m); calls += n; tokens += p + c;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("days", d); body.put("calls", calls); body.put("tokens", tokens); body.put("channels", channels);
        Object actor = req.getSession(false) == null ? null : req.getSession(false).getAttribute("username");
        body.put("rateLimit", limiter.usage(clientId, actor == null ? "" : String.valueOf(actor)));
        return ResponseEntity.ok(body);
    }

    /** Service Admin: cross-tenant totals. {@code ServiceAdminAuthFilter} already requires the admin session. */
    @GetMapping("/api/serviceadmin/ai/activity")
    public ResponseEntity<?> serviceAdminSummary(@RequestParam(defaultValue = "30") int days, HttpServletRequest req) {
        if (req.getSession(false) == null || req.getSession(false).getAttribute("serviceAdminId") == null)
            return ResponseEntity.status(401).body(Map.of("error", "Service Admin session required."));
        int d = Math.max(1, Math.min(366, days));
        List<Map<String, Object>> tenants = new ArrayList<>();
        for (Object[] r : repo.summaryByTenant(LocalDateTime.now().minusDays(d))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("clientId", r[0]); m.put("calls", ((Number) r[1]).longValue());
            m.put("promptTokens", ((Number) r[2]).longValue()); m.put("completionTokens", ((Number) r[3]).longValue());
            m.put("rateLimited", ((Number) r[4]).longValue()); m.put("lastAt", r[5] == null ? null : r[5].toString());
            tenants.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("days", d); body.put("tenants", tenants); body.put("gateway", gateway.stats());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/permissions/catalog")
    public ResponseEntity<?> permissionCatalog(HttpServletRequest req) {
        if (RoleGuard.requireAdminOrChurch(req) != null) return deny(req);
        return ResponseEntity.ok(Map.of("keys", PermissionCatalog.KEYS));
    }

    /** SuperAdmin / Admin staff or the church login; returns the church id or null. */
    private static String guard(HttpServletRequest req) {
        if (RoleGuard.requireAdminOrChurch(req) != null) return null;
        String clientId = RoleGuard.clientId(req);
        return clientId == null || clientId.isBlank() ? null : clientId;
    }

    private static ResponseEntity<?> deny(HttpServletRequest req) {
        boolean signedIn = req.getSession(false) != null && req.getSession(false).getAttribute("username") != null;
        return ResponseEntity.status(signedIn ? 403 : 401).body(Map.of("error", signedIn ? "Administrators only." : "Not signed in."));
    }

    static Map<String, Object> row(AiAuditLog r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId()); m.put("createdAt", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
        m.put("actor", r.getActor()); m.put("role", r.getRole()); m.put("channel", r.getChannel());
        m.put("path", r.getPath()); m.put("intent", r.getIntent()); m.put("model", r.getModel());
        m.put("promptTokens", r.getPromptTokens()); m.put("completionTokens", r.getCompletionTokens());
        m.put("httpStatus", r.getHttpStatus()); m.put("outcome", r.getOutcome()); m.put("durationMs", r.getDurationMs());
        return m;
    }
}
