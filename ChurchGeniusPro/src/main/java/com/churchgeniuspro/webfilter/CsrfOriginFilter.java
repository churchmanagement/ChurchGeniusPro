package com.churchgeniuspro.webfilter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Cross-site request forgery guard for state-changing requests.
 *
 * <p>The app has no CSRF token; the session cookie's {@code SameSite=Lax} is what
 * keeps a foreign page's POST from carrying it. That protects modern browsers only,
 * and only while nobody relaxes the cookie. This filter is the second, independent
 * check: for POST/PUT/PATCH/DELETE on {@code /api/*} and {@code /login}, the
 * {@code Origin} header (or, failing that, {@code Referer}) must name this site.
 *
 * <p>Requests with neither header are allowed through — browsers always send
 * {@code Origin} on cross-site state-changing requests, so a header-less request is
 * a non-browser client (Twilio, Plaid, curl), which cannot be forging a user's
 * cookie. Signed webhooks are exempted explicitly anyway.
 *
 * <p>Allowed hosts: the {@code app.base-url} host and the request's own {@code Host}
 * (Azure App Service forwards the client's Host header unchanged, so under the
 * custom domain {@code getServerName()} is the site itself). {@code localhost} /
 * {@code 127.0.0.1} are accepted only when {@code app.base-url} is itself local.
 * {@code X-Forwarded-Host} is deliberately NOT consulted: it is a client-supplied
 * header, and matching the Origin against it let a cross-site page pass this check
 * by sending {@code X-Forwarded-Host} equal to its own host (production-readiness
 * audit 2026-10-07, Phase 4.5).
 */
public class CsrfOriginFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(CsrfOriginFilter.class);

    private static final Set<String> STATE_CHANGING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> EXEMPT = Set.of(
            "/api/plaid/webhook",   // ES256-signed by Plaid
            "/api/billing/stripe-webhook",   // HMAC-signed by Stripe (platform billing account)
            "/webhook/sms"          // HMAC-signed by Twilio
    );

    private final String baseHost;

    public CsrfOriginFilter(String appBaseUrl) {
        this.baseHost = hostOf(appBaseUrl);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest  req = (HttpServletRequest)  request;
        HttpServletResponse res = (HttpServletResponse) response;

        if (!STATE_CHANGING.contains(req.getMethod()) || EXEMPT.contains(req.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String source = req.getHeader("Origin");
        if (source == null || source.isBlank() || "null".equals(source)) source = req.getHeader("Referer");
        if (source == null || source.isBlank()) {          // non-browser client
            chain.doFilter(request, response);
            return;
        }

        String sourceHost = hostOf(source);
        if (sourceHost != null && isOurs(sourceHost, req)) {
            chain.doFilter(request, response);
            return;
        }

        log.warn("[CSRF] refused {} {} from origin host '{}'", req.getMethod(), req.getRequestURI(), sourceHost);
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"status\":\"error\",\"message\":\"Cross-site request refused.\"}");
    }

    private boolean isOurs(String host, HttpServletRequest req) {
        if (baseHost != null && baseHost.equals(host)) return true;
        String reqHost = req.getServerName();
        if (reqHost != null && reqHost.toLowerCase(Locale.ROOT).equals(host)) return true;
        boolean localBase = baseHost == null || isLocal(baseHost);
        return localBase && isLocal(host);
    }

    private static boolean isLocal(String host) {
        return "localhost".equals(host) || "127.0.0.1".equals(host);
    }

    /** Lower-cased host (no port) of a URL, or null when unparsable. */
    static String hostOf(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            String h = URI.create(url.trim()).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
