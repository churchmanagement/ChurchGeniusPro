package com.churchgeniuspro.webfilter;

import com.churchgeniuspro.hibernate.PrivateAccessSetting;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.service.PrivateAccessService;
import com.churchgeniuspro.util.EncryptionUtil;
import com.churchgeniuspro.util.NetworkMatcher;
import com.churchgeniuspro.util.PrivatePageCatalog;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.*;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Enforces Private Page Access — strictly per church.
 *
 * <p>For in-app pages the owning church comes from the session. For pre-login pages
 * (Login, Temp Login) there is no session, so the church is resolved from the request
 * itself: a church-scoped login link ({@code ?c=<encrypted clientId>} or {@code ?cid=}),
 * or the device's remember-me cookie. If no church can be identified the page is NOT
 * gated — so one church's login restriction can never affect another church's login.
 *
 * <p>Pre-login safeguards (no admin session exists yet): loopback is always allowed and an
 * optional break-glass token bypasses the gate.
 */
public class PrivatePageFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(PrivatePageFilter.class);

    private final PrivateAccessService service;
    private final LoginRepository loginRepository;
    private final String bypassToken;

    public PrivatePageFilter(PrivateAccessService service, LoginRepository loginRepository, String bypassToken) {
        this.service = service;
        this.loginRepository = loginRepository;
        this.bypassToken = bypassToken == null ? "" : bypassToken.trim();
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request  = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        try {
            String path = request.getRequestURI();
            String pageKey = PrivatePageCatalog.keyForPath(path);
            if (pageKey == null) { chain.doFilter(req, res); return; }

            PrivatePageCatalog.Page page = PrivatePageCatalog.byKey(pageKey);
            boolean preAuth = page != null && page.preAuth();

            // Resolve the OWNING church. In-app pages: from the session. Pre-login pages:
            // from the request (church login link or remember-me cookie). If unknown, do
            // not gate — never affect another church's shared login.
            String clientId = preAuth ? resolveChurch(request) : SessionUtil.getAppClientId(request);
            if (clientId == null) { chain.doFilter(req, res); return; }

            PrivateAccessSetting setting = service.getSetting(clientId);
            if (!setting.isEnabled()) { chain.doFilter(req, res); return; }

            String ip = NetworkMatcher.clientIp(request, setting.isTrustProxy());
            String user = SessionUtil.getUsername(request);

            // ── Pre-login anti-lockout safeguards ──
            if (preAuth) {
                if (NetworkMatcher.isLoopback(ip)) { chain.doFilter(req, res); return; }
                if (hasBypassToken(request)) {
                    logSafe(clientId, user, override(pageKey, "break-glass token"), ip, path);
                    chain.doFilter(req, res); return;
                }
            }

            boolean isAdmin = !preAuth && SessionUtil.isAdminLike(request); // no session pre-login
            PrivateAccessService.Decision d = service.evaluate(clientId, pageKey, ip, isAdmin);
            if ("OFF".equals(d.status)) { chain.doFilter(req, res); return; }

            // Log gated attempts. Pre-login pages can be hit often, so only record blocks
            // and overrides there; in-app pages log every attempt.
            if (!preAuth || !"ALLOWED".equals(d.status)) logSafe(clientId, user, d, ip, path);

            if (d.allowed) { chain.doFilter(req, res); return; }
            writeBlocked(request, response, ip);
        } catch (Exception e) {
            LOG.error("[PrivateAccess] filter error (allowing request): {}", e.toString(), e);
            chain.doFilter(req, res);
        }
    }

    /** Identify the church for a pre-login request: ?c=<encrypted>, ?cid=<raw>, or remember-me cookie. */
    private String resolveChurch(HttpServletRequest request) {
        String c = request.getParameter("c");
        if (c != null && !c.isBlank()) {
            try {
                String dec = EncryptionUtil.decrypt(c.trim());
                if (dec != null && !dec.isBlank()) return dec.trim();
            } catch (Exception ignored) {}
        }
        String cid = request.getParameter("cid");
        if (cid != null && !cid.isBlank()) return cid.trim();

        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie ck : cookies) {
                if ("rememberToken".equals(ck.getName()) && ck.getValue() != null && !ck.getValue().isBlank()) {
                    try {
                        return loginRepository.findByRememberToken(ck.getValue())
                                .map(SignUp::getClientId).filter(s -> s != null && !s.isBlank())
                                .orElse(null);
                    } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    private boolean hasBypassToken(HttpServletRequest request) {
        if (bypassToken.isEmpty()) return false;
        if (bypassToken.equals(request.getParameter("bypass"))) return true;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) for (Cookie c : cookies) {
            if ("cgp_net_bypass".equals(c.getName()) && bypassToken.equals(c.getValue())) return true;
        }
        return false;
    }

    private PrivateAccessService.Decision override(String pageKey, String reason) {
        PrivateAccessService.Decision d = new PrivateAccessService.Decision();
        d.pageKey = pageKey; d.status = "OVERRIDE"; d.allowed = true; d.reason = reason;
        return d;
    }

    private void logSafe(String clientId, String user, PrivateAccessService.Decision d, String ip, String path) {
        try { service.log(clientId, user, d, ip, path); }
        catch (Exception e) { LOG.warn("[PrivateAccess] audit log failed: {}", e.toString()); }
    }

    private void writeBlocked(HttpServletRequest request, HttpServletResponse response, String ip)
            throws IOException {
        String path = request.getRequestURI();
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        if (path.startsWith("/api/")) {
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                "{\"error\":\"This page is only available while connected to an authorized church network.\"," +
                "\"code\":\"NETWORK_RESTRICTED\"}");
            return;
        }
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write(blockedHtml(ip));
    }

    private String blockedHtml(String ip) {
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"/>" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>" +
            "<title>Restricted — Church Network Required</title>" +
            "<style>body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#f5f6fa;color:#333;" +
            "display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0;padding:24px;}" +
            ".card{background:#fff;max-width:460px;width:100%;border-radius:16px;box-shadow:0 8px 32px rgba(0,0,0,.1);" +
            "padding:36px 32px;text-align:center;}" +
            ".lock{font-size:54px;margin-bottom:8px;}h1{font-size:20px;color:#673147;margin:6px 0 12px;}" +
            "p{font-size:14px;line-height:1.55;color:#555;margin:0 0 14px;}" +
            ".ip{font-size:12px;color:#9aa;font-family:monospace;margin-top:10px;}" +
            ".btn{display:inline-block;margin-top:14px;background:#673147;color:#fff;text-decoration:none;" +
            "padding:10px 22px;border-radius:9px;font-size:14px;font-weight:600;}</style></head><body>" +
            "<div class=\"card\"><div class=\"lock\">🔒</div>" +
            "<h1>Authorized Church Network Required</h1>" +
            "<p>This page is only available while connected to an authorized church network.</p>" +
            "<p>Please connect to an approved church Wi-Fi network and try again, or contact an administrator.</p>" +
            "<a class=\"btn\" href=\"/home\">Return Home</a>" +
            "<div class=\"ip\">Your network address: " + escape(ip) + "</div></div></body></html>";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
