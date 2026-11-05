package com.churchgeniuspro.webfilter;

import com.churchgeniuspro.ai.AiRateLimiter;
import com.churchgeniuspro.ai.audit.AiAuditService;
import com.churchgeniuspro.ai.llm.LlmGateway;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * The common gate in front of every AI endpoint (AI Phase 0, 2026-11-04) — the paths in
 * {@link AiAuditService#CHANNELS}. Order 8 on {@code /api/*}, after {@code AuthFilter} (1),
 * {@code MemberApiScopeFilter} (2), {@code AccountStatusFilter} (6) and
 * {@code SubscriptionFeatureFilter} (7), so a request that reaches it belongs to a signed-in,
 * plan-permitted session. It then:
 * <ol>
 *   <li>refuses <b>temporary-access (badge/code) sessions</b> — 403 {@code AI_TEMP_ACCESS}
 *       (finding S1: those sessions are role {@code User} with a privileges JSON built from a
 *       7-page catalogue, and the AI policy read the missing AI keys as <em>allowed</em>);</li>
 *   <li>applies the {@link AiRateLimiter} — 429 {@code AI_RATE_LIMITED} with
 *       {@code Retry-After} (finding S4); in WARN-only mode it logs and lets the call through;</li>
 *   <li>runs the request, then writes <b>one</b> {@code ai_audit_log} row: who, which church,
 *       channel, path, the intent the service annotated, model/tokens the
 *       {@link LlmGateway} published, status, outcome and duration (finding S3).</li>
 * </ol>
 * Refused calls are audited too ({@code DENIED} / {@code RATE_LIMITED}). Every failure inside
 * the filter itself fails <em>open</em> for the request and is logged — the filter must never
 * take an AI feature down on its own.
 */
public class AiGuardFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(AiGuardFilter.class);

    public static final String CODE_TEMP_ACCESS = "AI_TEMP_ACCESS";
    public static final String CODE_RATE_LIMITED = "AI_RATE_LIMITED";

    private final AiAuditService audit;
    private final AiRateLimiter limiter;
    private final boolean enforceTempAccess;

    public AiGuardFilter(AiAuditService audit, AiRateLimiter limiter, boolean enforceTempAccess) {
        this.audit = audit;
        this.limiter = limiter;
        this.enforceTempAccess = enforceTempAccess;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        String path = RequestPaths.path(request);
        String channel = AiAuditService.channelOf(path);
        if (channel == null) { chain.doFilter(req, res); return; }

        HttpSession s = request.getSession(false);
        String clientId = SessionUtil.getAppClientId(request);
        if (s == null || clientId == null) { chain.doFilter(req, res); return; }   // AuthFilter already answered 401
        String actor = s.getAttribute("username") == null ? null : String.valueOf(s.getAttribute("username"));
        String role  = s.getAttribute("role") == null ? null : String.valueOf(s.getAttribute("role"));
        long started = System.nanoTime();

        try {
            if (s.getAttribute("tempAccessId") != null) {
                if (enforceTempAccess) {
                    writeJson(response, 403, "{\"error\":\"AI features are not available to temporary-access sessions.\","
                            + "\"code\":\"" + CODE_TEMP_ACCESS + "\"}");
                    record(clientId, actor, role, channel, path, request, 403, "DENIED", started);
                    return;
                }
                log.warn("[AiGuard] temporary-access session reached {} (enforcement off)", path);
            }
            AiRateLimiter.Decision d = limiter.tryAcquire(clientId, actor);
            if (!d.allowed()) {
                if (limiter.enforcing()) {
                    response.setHeader("Retry-After", String.valueOf(d.retryAfterSeconds()));
                    writeJson(response, 429, "{\"error\":\"Too many AI requests. Please wait a moment and try again.\","
                            + "\"code\":\"" + CODE_RATE_LIMITED + "\",\"scope\":\"" + d.scope() + "\","
                            + "\"retryAfterSeconds\":" + d.retryAfterSeconds() + "}");
                    record(clientId, actor, role, channel, path, request, 429, "RATE_LIMITED", started);
                    return;
                }
                log.warn("[AiGuard] rate limit {} exceeded for clientId={} on {} (enforcement off)", d.scope(), clientId, path);
            }
        } catch (Exception e) {
            log.warn("[AiGuard] gate failed open on {}: {}", path, com.churchgeniuspro.util.LogSafe.exception(e));
        }

        try {
            chain.doFilter(req, res);
        } finally {
            int status = response.getStatus();
            String outcome = status / 100 == 2 ? "OK" : (status == 403 || status == 401 ? "DENIED" : status == 429 ? "RATE_LIMITED" : "ERROR");
            Object denied = request.getAttribute("ai.denied");          // a 200 that carried a refusal (crossref.denied …)
            if (Boolean.TRUE.equals(denied)) outcome = "DENIED";
            record(clientId, actor, role, channel, path, request, status, outcome, started);
        }
    }

    private void record(String clientId, String actor, String role, String channel, String path,
                        HttpServletRequest request, int status, String outcome, long startedNanos) {
        try {
            Object intent = request.getAttribute(AiAuditService.INTENT_ATTR);
            Object usage = request.getAttribute(LlmGateway.USAGE_ATTR);
            audit.record(clientId, actor, role, channel, path,
                    intent == null ? null : String.valueOf(intent),
                    usage instanceof LlmGateway.Usage u ? u : null,
                    status, outcome, (System.nanoTime() - startedNanos) / 1_000_000);
        } catch (Exception e) {
            log.warn("[AiGuard] audit failed on {}: {}", path, com.churchgeniuspro.util.LogSafe.exception(e));
        }
    }

    private static void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(body);
    }
}
