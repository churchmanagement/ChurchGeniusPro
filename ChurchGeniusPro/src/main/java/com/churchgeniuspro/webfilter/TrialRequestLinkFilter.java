package com.churchgeniuspro.webfilter;

import com.churchgeniuspro.service.TrialRequestService;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Gates the public Trial Request page behind the current request-link token.
 *
 * <p>Same shape and reasoning as {@link TrialRegistrationLinkFilter}: the page is a
 * static resource served with no controller running, so the guard must sit in front
 * of both spellings ({@code /trialRequest.html} and {@code /trialRequest}). The
 * submit endpoint re-checks the token itself, so this is the door, not the only lock.
 * Fails CLOSED: if the token cannot be read the page is refused.
 */
public class TrialRequestLinkFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(TrialRequestLinkFilter.class);
    private static final String INVALID_PAGE = "/trialRequestInvalid.html";

    private final TrialRequestService requests;

    public TrialRequestLinkFilter(TrialRequestService requests) {
        this.requests = requests;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest req) || !(response instanceof HttpServletResponse res)) {
            chain.doFilter(request, response);
            return;
        }
        boolean ok;
        try {
            ok = requests.isLinkToken(req.getParameter("k"));
        } catch (Exception e) {
            log.error("Trial Request link check failed — refusing access. {}", e.getMessage());
            ok = false;
        }
        if (ok) {
            chain.doFilter(request, response);
            return;
        }
        log.info("Trial Request page refused — invalid or revoked link, ip={}", req.getRemoteAddr());
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        req.getRequestDispatcher(INVALID_PAGE).forward(req, res);
    }
}
