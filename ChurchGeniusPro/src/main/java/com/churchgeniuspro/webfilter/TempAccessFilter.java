package com.churchgeniuspro.webfilter;

import com.churchgeniuspro.hibernate.TemporaryAccess;
import com.churchgeniuspro.service.TemporaryAccessService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.Optional;

/**
 * Enforces the time window of a Temporary Access session on every request.
 *
 * <p>For a normal (non-temporary) session this filter is a no-op. For a temporary
 * session it re-checks the pass against the database each request, so the moment
 * the end time passes — or an admin revokes the pass — the live session is ended:
 * API calls get 401 and page navigations are redirected to the temp login screen.
 * (Admin "extend" simply moves the end time, so the session keeps working with no
 * forced logout.)
 *
 * <p>The {@code /api/temp-access/session} and {@code /api/temp-access/logout}
 * endpoints, the {@code /tempLogin} screen, and static assets are always allowed
 * through so the client can wind down cleanly.
 */
public class TempAccessFilter implements Filter {

    private final TemporaryAccessService svc;

    public TempAccessFilter(TemporaryAccessService svc) {
        this.svc = svc;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  req = (HttpServletRequest)  request;
        HttpServletResponse res = (HttpServletResponse) response;

        HttpSession session = req.getSession(false);
        Object idAttr = session == null ? null : session.getAttribute("tempAccessId");
        if (!(idAttr instanceof Long id)) {            // not a temporary session → ignore
            chain.doFilter(request, response);
            return;
        }

        String uri = req.getRequestURI();
        if (isAlwaysAllowed(uri)) { chain.doFilter(request, response); return; }

        String clientId = String.valueOf(session.getAttribute("clientId"));
        Optional<TemporaryAccess> opt = svc.find(id, clientId);
        boolean live = opt.isPresent() && svc.isLiveNow(opt.get());

        if (!live) {
            Object auditId = session.getAttribute("tempAuditId");
            if (auditId instanceof Long aid) {
                try { svc.recordLogout(aid); } catch (Exception ignore) {}
            }
            session.invalidate();
            if (uri.startsWith("/api/")) {
                res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"status\":\"error\",\"message\":\"Temporary access has ended. Please log in again.\"}");
            } else {
                res.sendRedirect("/tempLogin?reason=expired");
            }
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isAlwaysAllowed(String uri) {
        if (uri.equals("/api/temp-access/session") || uri.equals("/api/temp-access/logout")) return true;
        if (uri.equals("/tempLogin") || uri.startsWith("/tempLogin")) return true;
        if (uri.equals("/favicon.ico")) return true;
        int dot = uri.lastIndexOf('.');
        if (dot > -1) {
            String ext = uri.substring(dot + 1).toLowerCase();
            switch (ext) {
                case "js": case "css": case "png": case "jpg": case "jpeg": case "gif":
                case "svg": case "ico": case "woff": case "woff2": case "ttf": case "map":
                case "webp": case "json":
                    return true;
                default: break;
            }
        }
        return false;
    }
}
