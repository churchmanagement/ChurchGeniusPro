package com.churchgeniuspro.webfilter;

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
 * Gates every {@code /api/serviceadmin/**} request on a service-admin session.
 *
 * <p>{@link AuthFilter} deliberately whitelists this prefix because service admins
 * are not tenant users and have no {@code clientId}. That made each controller
 * responsible for its own check — and {@code ServiceAdminController} had none, so
 * its tenant list, subscription edits, owner re-approval and database restore were
 * reachable anonymously. This filter is the single authority instead: a request
 * passes only when the session carries {@code serviceAdminId}, which is set solely
 * by {@code POST /api/serviceadmin/login}.
 *
 * <p>The check is on {@code serviceAdminId}, never on {@code role == "ServiceAdmin"}.
 * The session {@code role} is copied verbatim from {@code app_user.role}, which a
 * church owner can set to any string when creating a staff user.
 *
 * <p>Exempt: the login endpoint itself. {@code change-password} is not exempt — it
 * re-checks the current password, but there is no reason to expose it to the
 * anonymous internet.
 */
public class ServiceAdminAuthFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(ServiceAdminAuthFilter.class);

    static final String SESSION_ATTR = "serviceAdminId";
    private static final String LOGIN_PATH = "/api/serviceadmin/login";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest  req = (HttpServletRequest)  request;
        HttpServletResponse res = (HttpServletResponse) response;

        if (LOGIN_PATH.equals(req.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute(SESSION_ATTR) == null) {
            log.warn("[ServiceAdminAuth] refused {} {} — no service-admin session", req.getMethod(), req.getRequestURI());
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"status\":\"error\",\"message\":\"Service admin login required.\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
