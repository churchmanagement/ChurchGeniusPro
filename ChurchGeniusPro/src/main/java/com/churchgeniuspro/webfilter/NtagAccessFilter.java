package com.churchgeniuspro.webfilter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.util.List;

/**
 * Strict "Allowed Access Pages" enforcement for NTAG-login sessions.
 *
 * <p>An NTAG user may reach <em>only</em> the pages selected in their NTAG
 * credential's Allowed Access Pages (stored on the session as {@code ntagRoutes}).
 * For any other page navigation — including a directly typed URL — this filter
 * forwards to {@code /access-denied.html} so no unselected module is reachable.
 *
 * <p>For a non-NTAG session (no {@code ntagCredId}) this filter is a no-op.
 *
 * <p>Always allowed through (so the shell, login flow and the denied page work):
 * static assets, {@code /api/*} (those carry their own role/permission guards),
 * the access-denied page, the login/logout/tempLogin routes, and the site root.
 * Page-data APIs are intentionally left to the existing {@code RoleGuard}
 * checks; this filter governs visible <em>page</em> access.
 */
public class NtagAccessFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  req = (HttpServletRequest)  request;
        HttpServletResponse res = (HttpServletResponse) response;

        HttpSession session = req.getSession(false);
        Object cred = session == null ? null : session.getAttribute("ntagCredId");
        if (cred == null) {                       // not an NTAG-restricted session → ignore
            chain.doFilter(request, response);
            return;
        }

        // Canonical, decoded, normalised path (see RequestPaths) — never the raw
        // getRequestURI(), so an encoded-traversal URL off a permitted route (e.g.
        // "/kidsCheckin/%2e%2e/giving") cannot reach a page outside the allowed set.
        String uri = RequestPaths.path(req);
        if (isAlwaysAllowed(uri)) { chain.doFilter(request, response); return; }

        @SuppressWarnings("unchecked")
        List<String> routes = (List<String>) session.getAttribute("ntagRoutes");
        if (isPermittedPage(uri, routes)) { chain.doFilter(request, response); return; }

        // Blocked: a page the NTAG user is not allowed to see.
        if (uri.startsWith("/api/")) {            // (kept for safety; APIs are normally allowed above)
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"status\":\"error\",\"message\":\"Access denied for this page.\"}");
            return;
        }
        // Forward (not redirect) so the access-denied message shows at the attempted URL.
        req.getRequestDispatcher("/access-denied.html").forward(req, res);
    }

    /** Infra/asset paths that must always pass so the page chrome can load. */
    private boolean isAlwaysAllowed(String uri) {
        if (uri.equals("/") )                                         return true;
        if (uri.startsWith("/api/"))                                  return true;  // guarded by RoleGuard
        if (uri.equals("/access-denied") || uri.equals("/access-denied.html")) return true;
        if (uri.equals("/login") || uri.equals("/login.html"))        return true;
        if (uri.equals("/logout"))                                    return true;
        if (uri.equals("/tempLogin") || uri.startsWith("/tempLogin")) return true;
        if (uri.equals("/favicon.ico"))                               return true;
        int dot = uri.lastIndexOf('.');
        if (dot > -1) {
            String ext = uri.substring(dot + 1).toLowerCase();
            switch (ext) {
                // NOTE: ".html" is deliberately NOT allowed here — page html is gated below.
                case "js": case "css": case "png": case "jpg": case "jpeg": case "gif":
                case "svg": case "ico": case "woff": case "woff2": case "ttf": case "map":
                case "webp": case "json": case "mjs": case "wasm":
                    return true;
                default: break;
            }
        }
        return false;
    }

    /**
     * True when {@code uri} is one of the permitted catalog routes, that route's
     * static html file, or a sub-path beneath a permitted route.
     */
    private boolean isPermittedPage(String uri, List<String> routes) {
        if (routes == null || routes.isEmpty()) return false;
        String path = uri;
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String base = path.endsWith(".html") ? path.substring(0, path.length() - 5) : path;
        for (String r : routes) {
            if (r == null || r.isBlank()) continue;
            if (base.equals(r) || path.equals(r) || path.equals(r + ".html")) return true;
            if (base.startsWith(r + "/") || path.startsWith(r + "/"))         return true;
        }
        return false;
    }
}
