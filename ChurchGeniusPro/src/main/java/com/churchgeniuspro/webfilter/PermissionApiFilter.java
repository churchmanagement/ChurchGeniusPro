package com.churchgeniuspro.webfilter;

import com.churchgeniuspro.util.PermissionApiCatalog;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Enforces {@link PermissionApiCatalog} on {@code /api/*} for staff sessions (2026-11-06): the same
 * permission that opens a page is required for the APIs that page reads, so typing an API URL is
 * refused exactly like typing the page URL. 403 {@code {"code":"PERMISSION_DENIED","permission":…}}.
 *
 * <p>Uses {@link RoleGuard#requirePagePermission} for each key (legacy aliases honoured, a session
 * with nothing saved gets the role's defaults, church logins exempt). Member Portal sessions are
 * left to {@link MemberApiScopeFilter}; requests without a staff session are left to the auth
 * filter and the controllers. A failure inside the check refuses the request (fails closed).
 */
public class PermissionApiFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PermissionApiFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        PermissionApiCatalog.Rule rule = PermissionApiCatalog.ruleFor(RequestPaths.path(req), req.getMethod());
        if (rule == null || !isStaffSession(req)) {
            chain.doFilter(req, res);
            return;
        }
        boolean allowed;
        try {
            allowed = rule.anyOf().stream().anyMatch(k -> RoleGuard.requirePagePermission(req, k) == null);
        } catch (RuntimeException e) {
            log.warn("Permission check failed for {} — refused: {}", rule.prefix(), e.getClass().getSimpleName());
            allowed = false;
        }
        if (allowed) {
            chain.doFilter(req, res);
            return;
        }
        res.setStatus(403);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"error\":\"You don't have permission for this. Ask your church administrator.\","
                + "\"code\":\"PERMISSION_DENIED\",\"permission\":\"" + rule.anyOf().get(0) + "\"}");
    }

    /** A signed-in staff session (not church, not Member Portal). */
    static boolean isStaffSession(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null || s.getAttribute("username") == null) return false;
        if (RoleGuard.isChurch(req)) return false;
        return !"Member".equals(s.getAttribute("role"));
    }
}
