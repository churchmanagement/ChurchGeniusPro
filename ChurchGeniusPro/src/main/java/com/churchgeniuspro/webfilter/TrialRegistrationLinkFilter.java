package com.churchgeniuspro.webfilter;

import com.churchgeniuspro.service.TrialRegistrationLinkService;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Gates the Trial Registration page behind a valid invitation token.
 *
 * <p>A servlet filter rather than a controller check, because
 * {@code /trialRegistration.html} is a static resource: Spring's resource handler
 * serves it without any controller running, so a guard anywhere else would be
 * trivially skipped by asking for the {@code .html} URL directly. This filter sits
 * in front of both spellings — the static file and the {@code /trialRegistration}
 * route — and is the reason the page cannot be reached by URL alone.
 *
 * <p>The token is only the key to the DOOR. The registration POST re-validates and
 * consumes it independently, so knowing the form's field names is not a way in
 * either.
 *
 * <p>A refused visitor is forwarded to a page explaining which of expired, used,
 * revoked or unknown applied. Unknown and missing are answered identically, so the
 * page cannot be used to probe for valid tokens.
 */
public class TrialRegistrationLinkFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(TrialRegistrationLinkFilter.class);

    /** Where a refused visitor lands. */
    private static final String INVALID_PAGE = "/trialRegistrationInvalid.html";

    /** Accepted spellings of the token parameter. */
    private static final String[] TOKEN_PARAMS = { "id", "token" };

    private final TrialRegistrationLinkService links;

    public TrialRegistrationLinkFilter(TrialRegistrationLinkService links) {
        this.links = links;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest req) || !(response instanceof HttpServletResponse res)) {
            chain.doFilter(request, response);
            return;
        }

        String token = null;
        for (String p : TOKEN_PARAMS) {
            String v = req.getParameter(p);
            if (v != null && !v.isBlank()) { token = v; break; }
        }

        TrialRegistrationLinkService.Validation v;
        try {
            v = links.validate(token);
        } catch (Exception e) {
            // Fails CLOSED, unlike most guards in this codebase. The page provisions
            // a tenant, so an unreadable token table must not open it to everyone —
            // the cost of refusing a genuine prospect for a minute is far lower.
            log.error("Trial link validation failed — refusing access. {}", e.getMessage());
            forwardToNotice(req, res, "error");
            return;
        }

        if (v.valid()) {
            chain.doFilter(request, response);
            return;
        }

        log.info("Trial registration page refused — outcome={} ip={}",
                 v.outcome(), req.getRemoteAddr());
        forwardToNotice(req, res, v.outcome().name().toLowerCase());
    }

    /**
     * Forwards to the notice page with the reason.
     *
     * <p>A forward, not a redirect: the URL the visitor followed stays in the
     * address bar, so a link sent by an admin does not silently rewrite itself into
     * something that looks like a different page.
     */
    private void forwardToNotice(HttpServletRequest req, HttpServletResponse res, String reason)
            throws IOException, ServletException {
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        req.getRequestDispatcher(INVALID_PAGE + "?reason="
                + URLEncoder.encode(reason, StandardCharsets.UTF_8)).forward(req, res);
    }
}
