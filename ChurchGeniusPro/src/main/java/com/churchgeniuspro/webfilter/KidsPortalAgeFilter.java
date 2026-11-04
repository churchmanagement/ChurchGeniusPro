package com.churchgeniuspro.webfilter;

import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.util.KidsPortalEligibility;
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
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;

/**
 * Kids Portal 13+ rule for sessions that are ALREADY open (2026-11-04).
 *
 * <p>The sign-in paths refuse an under-13 or undated child before a session exists.
 * This filter covers the rest: a session that was open when the family record changed
 * (role switched to Child, date of birth corrected or cleared) and "Don't log out
 * automatically" sessions that never time out. For member portal sessions only, the
 * church's family record is re-read at most every {@link #RECHECK_MS} (the result is
 * cached on the session). When the login is no longer allowed, the session ends:
 * an API call answers 401 with code {@code KIDS_PORTAL_AGE}, a page goes to the login
 * page with the reason.
 *
 * <p>Fails OPEN on a lookup error (the session was already allowed at sign-in and the
 * next check runs on the next request after the cache window).
 */
public class KidsPortalAgeFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(KidsPortalAgeFilter.class);

    static final long RECHECK_MS = 5 * 60 * 1000L;
    static final String CHECKED_UNTIL = "kidsPortalAgeCheckedUntil";

    private final FamilyMemberRepository members;
    private final LongSupplier clock;

    public KidsPortalAgeFilter(FamilyMemberRepository members) {
        this(members, System::currentTimeMillis);
    }

    KidsPortalAgeFilter(FamilyMemberRepository members, LongSupplier clock) {
        this.members = members;
        this.clock = clock;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;
        HttpSession session = request.getSession(false);

        if (session == null || isStaticAsset(request) || !isMemberSession(session)) {
            chain.doFilter(req, res);
            return;
        }
        long now = clock.getAsLong();
        Object until = session.getAttribute(CHECKED_UNTIL);
        if (until instanceof Long u && now < u) {
            chain.doFilter(req, res);
            return;
        }

        FamilyMember fm;
        try {
            Object ref = session.getAttribute("clientId");
            fm = ref == null ? null : members.findByMemberRef(ref.toString()).orElse(null);
        } catch (Exception e) {
            log.warn("[KidsPortalAge] re-check failed for an open member session; allowing: {}",
                    com.churchgeniuspro.util.LogSafe.exception(e));
            chain.doFilter(req, res);
            return;
        }

        String reason = fm == null ? null : KidsPortalEligibility.reason(fm);
        if (reason == null) {
            session.setAttribute(CHECKED_UNTIL, now + RECHECK_MS);
            chain.doFilter(req, res);
            return;
        }

        String message = KidsPortalEligibility.message(fm);
        log.info("[KidsPortalAge] ended an open member session (member {}, reason {})", fm.getId(), reason);
        try { session.invalidate(); } catch (IllegalStateException ignored) { /* already gone */ }

        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"status\":\"error\",\"code\":\"" + KidsPortalEligibility.CODE
                    + "\",\"reason\":\"" + reason + "\",\"message\":\"" + json(message) + "\"}");
        } else {
            response.sendRedirect(request.getContextPath() + "/?reason=" + reason);
        }
    }

    static boolean isMemberSession(HttpSession session) {
        return "Member".equals(session.getAttribute("role")) && session.getAttribute("memberId") != null;
    }

    private static boolean isStaticAsset(HttpServletRequest request) {
        String p = request.getRequestURI().toLowerCase();
        return p.endsWith(".js") || p.endsWith(".css") || p.endsWith(".png") || p.endsWith(".jpg")
                || p.endsWith(".jpeg") || p.endsWith(".gif") || p.endsWith(".svg") || p.endsWith(".ico")
                || p.endsWith(".webp") || p.endsWith(".woff") || p.endsWith(".woff2") || p.endsWith(".map")
                || p.endsWith(".json") || p.endsWith(".webmanifest");
    }

    private static String json(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
