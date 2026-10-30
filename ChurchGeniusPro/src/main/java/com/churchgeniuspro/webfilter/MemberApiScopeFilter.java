package com.churchgeniuspro.webfilter;

import com.churchgeniuspro.util.RoleGuard;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Default-deny scope for Member Portal (and Kids portal) sessions on {@code /api/*}.
 *
 * <p>Why (security audit 2026-10-29): many staff endpoints are guarded by
 * {@code RoleGuard.requireAdminOrUser / requireStaffOrMember / requireAuth}, which admit a
 * Member Portal session on purpose (so a PAGE can show Access Denied), and some check no
 * permission at all. A member could therefore read staff data (e.g. the Kids Ministry
 * children list) by calling the API directly. Fixing every controller one by one leaves the
 * next new endpoint open; this filter closes the whole class.
 *
 * <p>Rule, for a session with role {@code Member} and a member id:
 * <ol>
 *   <li>public endpoints (the {@link AuthFilter} whitelist) pass — a signed-in member can
 *       still register for an event or open a donation page;</li>
 *   <li>the Member Portal's own APIs ({@link #MEMBER_AREAS}) pass — those are exactly the
 *       calls the portal pages make, and their controllers scope data to the member;</li>
 *   <li>a staff API area passes only when the church explicitly ticked the matching
 *       permission for this member ({@link #GRANT_AREAS}, derived from the API calls each
 *       staff page makes) — the per-endpoint role/permission checks then apply as usual;</li>
 *   <li>anything else: 403 {@code MEMBER_SCOPE}.</li>
 * </ol>
 * Staff, church, temporary-access and NTAG sessions are not affected.
 */
public class MemberApiScopeFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(MemberApiScopeFilter.class);

    /** API areas the Member Portal pages call (memberHome, memberWorship, songbook, guessIt, helpCenter, tickets, ai-assistant, shared scripts). */
    static final List<String> MEMBER_AREAS = List.of(
            "/api/session", "/api/app-version", "/api/subscription/features",
            "/api/member", "/api/members/my", "/api/volunteers/my",
            "/api/families", "/api/family/avatar",              // FamilyController: own family only (2026-10-29)
            "/api/guess-it",                                    // playing; host controls are staff-only in the controller
            "/api/songbook", "/api/worship", "/api/ss",         // controller-checked (access list / write guard / teacher of class)
            "/api/favorites", "/api/push", "/api/help", "/api/tickets",
            "/api/ai-search", "/api/ai-assist", "/api/voice/command", "/api/voice/status", "/api/voice/features",
            "/api/openai-usage/status", "/api/demo/product-tour", "/api/demo/trial-agreement",
            "/api/trial-tips", "/api/logo");

    /** Exact paths (no sub-paths) the portal calls. */
    static final List<String> MEMBER_EXACT = List.of(
            "/api/members",    // names only for members (FamilyController)
            "/api/family");    // POST — refused for members in FamilyController; listed so the portal gets that message

    static final List<Pattern> MEMBER_PATTERNS = List.of(
            Pattern.compile("^/api/events/\\d+/volunteers/\\d+/member-respond$"));

    /**
     * Staff permission key → API areas its pages use. A member the church explicitly granted
     * the key may reach these areas; the endpoints' own checks still apply.
     */
    static final Map<String, List<String>> GRANT_AREAS = Map.ofEntries(
            Map.entry("admin.family",            List.of("/api/families", "/api/family", "/api/members", "/api/family-report")),
            Map.entry("admin.groups",            List.of("/api/groups", "/api/group-members", "/api/members")),
            Map.entry("admin.membership",        List.of("/api/membership-requests")),
            Map.entry("admin.unsubscribed",      List.of("/api/unsubscribe")),
            Map.entry("general.meetings",        List.of("/api/meetings", "/api/meeting-types", "/api/meeting-templates")),
            Map.entry("general.attendance",      List.of("/api/attendance")),
            Map.entry("general.events",          List.of("/api/events", "/api/event-calendar", "/api/event-email-templates",
                                                         "/api/event-register", "/api/volunteers", "/api/meetings",
                                                         "/api/meeting-types", "/api/members")),
            Map.entry("general.ministry.kids",   List.of("/api/kids-ministry", "/api/ss", "/api/volunteers", "/api/families", "/api/members")),
            Map.entry("general.ministry.prayer", List.of("/api/prayer", "/api/families")),
            Map.entry("general.ministry.worship",List.of("/api/worship", "/api/members")),
            Map.entry("general.email",           List.of("/api/notify", "/api/email-settings")),
            Map.entry("general.emailsettings",   List.of("/api/email-settings")),
            Map.entry("general.reminders",       List.of("/api/reminders", "/api/events", "/api/groups")),
            Map.entry("more.certificates",       List.of("/api/certificates", "/api/members", "/api/email-settings")),
            Map.entry("more.publicscreens",      List.of("/api/public-screens", "/api/membership-form")),
            Map.entry("more.followups",          List.of("/api/followups", "/api/connect-admin", "/api/ppr", "/api/content-reports")));

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpSession session = req.getSession(false);
        if (!RoleGuard.isMemberPortalSession(session)) { chain.doFilter(request, response); return; }

        String path = RequestPaths.path(req);
        if (!path.startsWith("/api/") || AuthFilter.isPublicPath(path) || memberArea(path) || granted(session, path)) {
            chain.doFilter(request, response);
            return;
        }
        log.info("[MemberScope] refused {} {} for member {}", req.getMethod(), path, session.getAttribute("memberId"));
        HttpServletResponse res = (HttpServletResponse) response;
        res.setStatus(HttpServletResponse.SC_FORBIDDEN);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"error\":\"This is not available in the Member Portal.\",\"code\":\"MEMBER_SCOPE\"}");
    }

    /** Exact path or a path segment under the area — never a bare string prefix ("/api/member" ≠ "/api/members-x"). */
    static boolean under(String path, String area) {
        return path.equals(area) || path.startsWith(area + "/");
    }

    static boolean memberArea(String path) {
        for (String a : MEMBER_EXACT) if (path.equals(a)) return true;
        for (String a : MEMBER_AREAS) if (under(path, a)) return true;
        for (Pattern p : MEMBER_PATTERNS) if (p.matcher(path).matches()) return true;
        return false;
    }

    static boolean granted(HttpSession session, String path) {
        for (Map.Entry<String, List<String>> e : GRANT_AREAS.entrySet()) {
            for (String area : e.getValue()) {
                if (under(path, area) && RoleGuard.memberHasExplicitGrant(session, e.getKey())) return true;
            }
        }
        return false;
    }
}
