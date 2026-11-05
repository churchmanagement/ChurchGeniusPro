package com.churchgeniuspro.webfilter;

import com.churchgeniuspro.ai.AiRateLimiter;
import com.churchgeniuspro.ai.audit.AiAuditService;
import com.churchgeniuspro.mcp.PersonalAccessTokenService;
import com.churchgeniuspro.mcp.TokenSession;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bearer-token authentication for the MCP endpoint (AI Phase 5, 2026-11-05). Registered on
 * {@code /mcp} and {@code /mcp/*} only — the rest of the application keeps its cookie session.
 *
 * <ol>
 *   <li>No {@code Authorization: Bearer …} → 401 with {@code WWW-Authenticate: Bearer} (the MCP
 *       client knows to ask for a token). A cookie session is deliberately NOT accepted here:
 *       the endpoint is for external tools with their own token, never for a browser tab.</li>
 *   <li>{@link PersonalAccessTokenService#authenticate} → 401 / 403 / 503 with a JSON body
 *       {@code {error, code}} when the token, the person, the plan or the church refuses.</li>
 *   <li>Rate limits: the per-token minute window ({@code mcp.rate.per-token-per-minute}) and the
 *       shared {@link AiRateLimiter} (per user per minute, per church per day) → 429 + Retry-After.</li>
 *   <li>Otherwise the request goes on wrapped so {@code getSession()} is a {@link TokenSession}
 *       with the owner's staff sign-in attributes — every existing permission check applies.</li>
 * </ol>
 * Refusals write one audit row (channel MCP, outcome DENIED / RATE_LIMITED); the controller
 * writes the row for calls that get through. Any failure inside the filter fails CLOSED (401):
 * an external caller never gets data because a check broke.
 */
public class ApiTokenFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenFilter.class);
    public static final String CHANNEL = "MCP";

    private final PersonalAccessTokenService tokens;
    private final AiRateLimiter limiter;
    private final AiAuditService audit;
    private final int perTokenPerMinute;
    private final Clock clock;
    private final Map<Long, long[]> windows = new ConcurrentHashMap<>();   // tokenId → {minuteKey, count}

    public ApiTokenFilter(PersonalAccessTokenService tokens, AiRateLimiter limiter, AiAuditService audit, int perTokenPerMinute) {
        this(tokens, limiter, audit, perTokenPerMinute, Clock.systemUTC());
    }

    public ApiTokenFilter(PersonalAccessTokenService tokens, AiRateLimiter limiter, AiAuditService audit, int perTokenPerMinute, Clock clock) {
        this.tokens = tokens; this.limiter = limiter; this.audit = audit; this.perTokenPerMinute = perTokenPerMinute; this.clock = clock;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) { chain.doFilter(req, res); return; }
        long started = System.currentTimeMillis();
        String raw = bearer(request.getHeader("Authorization"));
        if (raw == null) { refuse(response, 401, "NO_TOKEN", "Send the personal access token as 'Authorization: Bearer <token>'."); return; }
        PersonalAccessTokenService.Outcome out;
        try { out = tokens.authenticate(raw); }
        catch (RuntimeException e) {
            log.warn("MCP token check failed: {}", e.getClass().getSimpleName());
            refuse(response, 401, "TOKEN_CHECK_FAILED", "The token could not be checked. Please try again."); return;
        }
        if (out.refusal() != null) {
            refuse(response, out.refusal().status(), out.refusal().code(), out.refusal().message());
            return;
        }
        PersonalAccessTokenService.Principal p = out.principal();
        String cid = p.user().getClientId(), actor = p.token().getUsername(), role = p.user().getRole();
        // Per-token minute window, then the shared user/day limits.
        if (perTokenPerMinute > 0 && !tokenWindowAllows(p.token().getId())) {
            response.setHeader("Retry-After", "60");
            refuse(response, 429, "TOKEN_RATE_LIMITED", "This token has made too many requests this minute.");
            audit.record(cid, actor, role, CHANNEL, "/mcp", "rate.token", null, 429, "RATE_LIMITED", System.currentTimeMillis() - started);
            return;
        }
        if (limiter != null) {
            AiRateLimiter.Decision d = limiter.tryAcquire(cid, actor);
            if (!d.allowed()) {
                response.setHeader("Retry-After", String.valueOf(Math.max(1, d.retryAfterSeconds())));
                refuse(response, 429, "AI_RATE_LIMITED", "Too many AI requests (" + d.scope() + "). Please wait and try again.");
                audit.record(cid, actor, role, CHANNEL, "/mcp", "rate." + d.scope(), null, 429, "RATE_LIMITED", System.currentTimeMillis() - started);
                return;
            }
        }
        TokenSession session = TokenSession.forPrincipal(request.getServletContext(), p);
        chain.doFilter(TokenSession.wrap(request, session), res);
    }

    static String bearer(String header) {
        if (header == null) return null;
        String h = header.trim();
        if (h.length() < 8 || !h.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String v = h.substring(7).trim();
        return v.isEmpty() ? null : v;
    }

    private boolean tokenWindowAllows(Long tokenId) {
        long minute = clock.millis() / 60_000L;
        long[] w = windows.computeIfAbsent(tokenId, k -> new long[]{minute, 0});
        synchronized (w) {
            if (w[0] != minute) { w[0] = minute; w[1] = 0; }
            w[1]++;
            if (windows.size() > 10_000) windows.clear();   // bounded memory on a long-running instance
            return w[1] <= perTokenPerMinute;
        }
    }

    private static void refuse(HttpServletResponse response, int status, String code, String message) throws IOException {
        if (response.isCommitted()) return;
        response.setStatus(status);
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer realm=\"ChurchGeniusPro MCP\"" + (code.equals("NO_TOKEN") ? "" : ", error=\"invalid_token\""));
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"code\":\"" + code + "\"}");
    }
}
