package com.churchgeniuspro.util;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Server-side role guard for page-serving controller methods.
 *
 * <p>Usage in a controller:
 * <pre>
 *   \@GetMapping("/viewusers")
 *   public String page(HttpServletRequest req) {
 *       String deny = RoleGuard.requireAdmin(req);
 *       if (deny != null) return deny;
 *       return "forward:/viewusers.html";
 *   }
 * </pre>
 *
 * <p>Returns {@code null} when access is allowed.
 * Returns {@code "redirect:/login"} when the user is not authenticated.
 * Returns {@code "forward:/access-denied.html"} when the user is authenticated
 * but does not have the required role — the browser URL stays on the attempted
 * page and a clear permission-denied message is displayed.
 *
 * <p>Role hierarchy enforced here (matches session.js ALLOWED map):
 * <ul>
 *   <li>SuperAdmin  — all pages</li>
 *   <li>Admin       — admin, admin-settings, general, reminders sections</li>
 *   <li>Accountant  — accounting, account-settings, general sections</li>
 *   <li>User        — general section only</li>
 *   <li>Church      — /viewusers only (via requireAdminOrChurch)</li>
 * </ul>
 */
public final class RoleGuard {

    private RoleGuard() {}

    // ── Return values ─────────────────────────────────────────────────────

    /** Redirect target when the user is not logged in. */
    public static final String REDIRECT_LOGIN = "redirect:/login";

    /**
     * Forward target when the user is logged in but lacks the required role.
     * Using a forward (not a redirect) keeps the attempted URL in the browser
     * address bar while rendering the access-denied error page.
     */
    public static final String FORWARD_ACCESS_DENIED = "forward:/access-denied.html";

    // ── Guards ────────────────────────────────────────────────────────────

    /**
     * Requires any authenticated user (church or staff).
     * Redirects to login if there is no active session.
     */
    public static String requireAuth(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("username") == null) {
            return REDIRECT_LOGIN;
        }
        return null;
    }

    /**
     * Requires SuperAdmin or Admin role (admin / admin-settings sections).
     * Church logins are denied — use {@link #requireAdminOrChurch} for pages
     * that church users are also allowed to access (e.g. /viewusers).
     */
    public static String requireAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("username") == null) return REDIRECT_LOGIN;
        if (isChurchSession(session)) return FORWARD_ACCESS_DENIED;
        String role = role(session);
        if ("SuperAdmin".equals(role) || "Admin".equals(role)) return null;
        // All other roles (including Member portal) fall through to the access-denied
        // page so unauthorized navigation never silently lands on a different page.
        return FORWARD_ACCESS_DENIED;
    }

    /**
     * Requires SuperAdmin, Admin, OR a church-type login.
     * Used for pages that church admins may also access (e.g. /viewusers).
     */
    public static String requireAdminOrChurch(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("username") == null) return REDIRECT_LOGIN;
        if (isChurchSession(session)) return null;          // church users allowed
        String role = role(session);
        if ("SuperAdmin".equals(role) || "Admin".equals(role)) return null;
        return FORWARD_ACCESS_DENIED;
    }

    /**
     * Requires a CHURCH-type login and denies everyone else.
     *
     * <p>Used for owner-only pages (User management, Temporary Access, Stripe /
     * WhatsApp integration). Every other role — SuperAdmin, Admin, Accountant,
     * User, Limited, Member, Child — receives an Access Denied. Unauthenticated
     * requests are sent to the login screen.
     */
    public static String requireChurch(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return REDIRECT_LOGIN;
        if (isChurchSession(session)) return null;          // church account only
        boolean authed = session.getAttribute("username") != null
                || session.getAttribute("memberId") != null;
        return authed ? FORWARD_ACCESS_DENIED : REDIRECT_LOGIN;
    }

    /**
     * Requires SuperAdmin or Accountant role (accounting / accounting-reports sections).
     * Church logins are always denied.
     */
    public static String requireAccountant(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("username") == null) return REDIRECT_LOGIN;
        if (isChurchSession(session)) return FORWARD_ACCESS_DENIED;
        String role = role(session);
        if ("SuperAdmin".equals(role) || "Accountant".equals(role)) return null;
        // All other roles (including Member portal) get the access-denied page —
        // a silent redirect to memberHome masks the real cause and confuses users.
        return FORWARD_ACCESS_DENIED;
    }

    /**
     * Requires SuperAdmin, Admin, or Accountant role.
     *
     * <p>Used for the Income and Expense pages so that SuperAdmin — who sits
     * above all other roles in the hierarchy — can always access financial data
     * regardless of whether their session was fully populated by the login flow.
     * Church logins are always denied.
     */
    public static String requireAccountantOrAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("username") == null) return REDIRECT_LOGIN;
        if (isChurchSession(session)) return FORWARD_ACCESS_DENIED;
        String role = role(session);
        if ("SuperAdmin".equals(role) || "Admin".equals(role) || "Accountant".equals(role)) return null;
        return FORWARD_ACCESS_DENIED;
    }

    /**
     * Requires payroll access: SuperAdmin, Admin, or Accountant staff roles, OR a
     * church-type login. User and Member/Child sessions are denied.
     *
     * <p>Payroll is sensitive (pay, masked PII, tax data); this is the single
     * place that decides who may use the payroll module. Adjust the allowed set
     * here to change the policy for every payroll page and API at once.
     */
    public static String requirePayroll(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("username") == null) return REDIRECT_LOGIN;
        if (isChurchSession(session)) return null;          // church admin allowed
        String role = role(session);
        if ("SuperAdmin".equals(role) || "Admin".equals(role) || "Accountant".equals(role)) return null;
        return FORWARD_ACCESS_DENIED;
    }

    /**
     * Requires SuperAdmin, Admin, or Accountant role.
     *
     * <p>Used for pages that Accountant users are allowed to view (e.g. /viewfamily),
     * while still denying access to User and Church roles.
     * Church logins are always denied.
     */
    public static String requireAdminOrAccountant(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("username") == null) return REDIRECT_LOGIN;
        if (isChurchSession(session)) return FORWARD_ACCESS_DENIED;
        String role = role(session);
        if ("SuperAdmin".equals(role) || "Admin".equals(role) || "Accountant".equals(role)) return null;
        return FORWARD_ACCESS_DENIED;
    }

    /**
     * Requires SuperAdmin, Admin, or User role.
     *
     * <p>Used for pages in the {@code general.*} permission section that are
     * accessible to the User role when the matching permission is enabled.
     * Examples: Worship Planning, Sunday School, Kids Ministry, Volunteers.
     *
     * <p>Accountant and Church logins are always denied here — they use separate
     * role guards ({@link #requireAccountant}, {@link #requireAdminOrChurch}).
     */
    public static String requireAdminOrUser(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("username") == null) return REDIRECT_LOGIN;
        if (isChurchSession(session)) return FORWARD_ACCESS_DENIED;
        String role = role(session);
        if ("SuperAdmin".equals(role) || "Admin".equals(role) || "User".equals(role)) return null;
        // Member portal sessions are allowed past the role check so that downstream
        // requirePermission() decides access; that way unauthorized navigation lands
        // on /access-denied (forward, URL preserved) instead of silently bouncing the
        // user back to /memberHome — which looks like the buttons "don't work".
        if ("Member".equals(role) && session.getAttribute("memberId") != null) return null;
        return FORWARD_ACCESS_DENIED;
    }

    /**
     * Requires SuperAdmin role only.
     */
    public static String requireSuperAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("username") == null) return REDIRECT_LOGIN;
        if ("SuperAdmin".equals(role(session))) return null;
        return FORWARD_ACCESS_DENIED;
    }

    /**
     * Requires ANY authenticated staff role (Admin, Accountant, User, SuperAdmin)
     * OR a Member portal session. Denies church-type logins.
     *
     * <p>Use this on pages that belong to the General section — every staff role
     * has access to General, and the per-page granular check is left to
     * {@link #requirePermission}. Examples: /meetings, /events, /ministry,
     * /notifyEmail, /reminders.
     */
    public static String requireStaffOrMember(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return REDIRECT_LOGIN;
        // Member portal sessions use memberId rather than username — accept them.
        boolean isMember = "Member".equals(role(session)) && session.getAttribute("memberId") != null;
        if (isMember) return null;
        if (session.getAttribute("username") == null) return REDIRECT_LOGIN;
        if (isChurchSession(session)) return FORWARD_ACCESS_DENIED;
        // Any staff role (Admin, Accountant, User, SuperAdmin, Limited) is allowed
        // through; the actual page-level permission gate is requirePermission().
        return null;
    }

    /**
     * Requires SuperAdmin, Admin, or Member role.
     *
     * <p>Used for pages (e.g. Worship Planning) that both staff admins and
     * logged-in church members are permitted to access.
     * Member sessions are identified by {@code role = "Member"} in the session.
     * Church-type logins are denied.
     */
    public static String requireAdminOrMember(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return REDIRECT_LOGIN;
        // Member portal sessions use "memberId" instead of "username"
        boolean isMember = "Member".equals(role(session)) && session.getAttribute("memberId") != null;
        if (isMember) return null;
        if (session.getAttribute("username") == null) return REDIRECT_LOGIN;
        if (isChurchSession(session)) return FORWARD_ACCESS_DENIED;
        String role = role(session);
        if ("SuperAdmin".equals(role) || "Admin".equals(role)) return null;
        return FORWARD_ACCESS_DENIED;
    }

    /**
     * Requires a granular permission key to be present and {@code true} in the
     * stored privileges JSON.  SuperAdmin always passes.  If the privileges JSON
     * is absent or unparseable the user is treated as having full access.
     *
     * <p>Uses opt-in denial semantics: a missing key means access is allowed;
     * only an explicit {@code false} value blocks access.
     *
     * <p>Example usage in a controller:
     * <pre>
     *   String deny = RoleGuard.requirePermission(request, "general.eventcheckin");
     *   if (deny != null) return deny;
     * </pre>
     *
     * @param permKey  dot-separated permission key, e.g. {@code "admin.family"}
     */
    @SuppressWarnings("unchecked")
    public static String requirePermission(HttpServletRequest request, String permKey) {
        HttpSession session = request.getSession(false);
        if (session == null) return REDIRECT_LOGIN;
        // Member portal sessions are identified by memberId rather than username —
        // allow them past the auth check and look up permissions in memberPrivileges
        // (the staff "privileges" attribute is not set for members).
        boolean isMember = "Member".equals(role(session)) && session.getAttribute("memberId") != null;
        if (!isMember && session.getAttribute("username") == null) return REDIRECT_LOGIN;
        // Church sessions bypass granular permission checks (their nav is governed
        // by applyChurchNavFilter on the frontend).
        if (isChurchSession(session)) return null;
        // SuperAdmin used to always bypass — but per product spec, saved perms now
        // apply to SuperAdmin too. Fall through to the JSON check below; only the
        // absence of any saved perms grants implicit full access.
        // For staff: read "privileges"; for member portal: read "memberPrivileges".
        // Either attribute may legitimately be null — that means "no restrictions"
        // (opt-in denial) — so callers should pair this guard with a role check.
        Object privAttr = session.getAttribute(isMember ? "memberPrivileges" : "privileges");
        if (privAttr == null) return null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, Object> map =
                    mapper.readValue(String.valueOf(privAttr), java.util.Map.class);
            // Missing key = full access (opt-in denial); explicit false = denied
            Object val = map.get(permKey);
            if (Boolean.FALSE.equals(val)) {
                return FORWARD_ACCESS_DENIED;
            }
        } catch (Exception e) {
            // Unparseable JSON → treat as full access
        }
        return null;
    }

    /**
     * Checks a {@code member.*} privilege key for member portal sessions.
     * For non-member sessions this check is skipped (returns {@code null}).
     *
     * <p>Semantics (same opt-in denial as {@link #requirePermission}):
     * <ul>
     *   <li>No {@code memberPrivileges} stored in session → allow (null = no restrictions)</li>
     *   <li>Key present and {@code true} → allow</li>
     *   <li>Key explicitly {@code false} → deny</li>
     *   <li>Key absent from map → allow</li>
     * </ul>
     *
     * <p>Example usage alongside {@code requireAdminOrMember}:
     * <pre>
     *   String deny = RoleGuard.requireAdminOrMember(request);
     *   if (deny != null) return deny;
     *   deny = RoleGuard.requireMemberPermission(request, "member.worship");
     *   if (deny != null) return deny;
     * </pre>
     *
     * @param memberPermKey  a {@code member.*} permission key, e.g. {@code "member.worship"}
     */
    @SuppressWarnings("unchecked")
    public static String requireMemberPermission(HttpServletRequest request, String memberPermKey) {
        HttpSession session = request.getSession(false);
        if (session == null) return REDIRECT_LOGIN;
        // Only applies to member portal sessions
        if (!("Member".equals(role(session)) && session.getAttribute("memberId") != null)) return null;
        Object privAttr = session.getAttribute("memberPrivileges");
        // No stored privileges → all tabs allowed by default
        if (privAttr == null) return null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, Object> map =
                    mapper.readValue(String.valueOf(privAttr), java.util.Map.class);

            // If any child key (memberPermKey + '.') is explicitly true, access is granted.
            // This mirrors the frontend perm() helper in applyMemberNavFilter:
            // a subsection key may be saved as false by the permissions modal when the
            // section header was off, but individual action keys may still be enabled.
            String childPrefix = memberPermKey + ".";
            boolean anyChildTrue = map.entrySet().stream()
                    .anyMatch(e -> e.getKey().startsWith(childPrefix)
                                   && Boolean.TRUE.equals(e.getValue()));
            if (anyChildTrue) return null;

            // Fall back to the exact key: missing = allowed, explicit false = denied.
            Object val = map.get(memberPermKey);
            if (Boolean.FALSE.equals(val)) return FORWARD_ACCESS_DENIED;
        } catch (Exception e) {
            // Unparseable JSON → treat as full access
        }
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the current session belongs to a church-type login
     * ({@code church = true} in the session).  Safe to call even when there is no
     * active session (returns {@code false} in that case).
     */
    public static boolean isChurch(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return false;
        return isChurchSession(session);
    }

    /**
     * Returns the effective {@code clientId} for API calls.
     * For church sessions: {@code clientId} from session.
     * For staff sessions: {@code appClientId} from session (the organization's client ID).
     * For member sessions (role=Member): {@code appClientId} from session.
     * Returns {@code null} if there is no active session.
     */
    public static String clientId(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        // Member portal sessions — identified by role=Member + memberId
        if ("Member".equals(role(session)) && session.getAttribute("memberId") != null) {
            Object v = session.getAttribute("appClientId");
            if (v instanceof String s && !s.isBlank()) return s;
            // fallback: clientId stored directly
            Object v2 = session.getAttribute("clientId");
            return v2 instanceof String s2 ? s2 : null;
        }
        if (session.getAttribute("username") == null) return null;
        if (isChurchSession(session)) {
            Object v = session.getAttribute("clientId");
            return v instanceof String s ? s : null;
        }
        Object v = session.getAttribute("appClientId");
        if (v instanceof String s && !s.isBlank()) return s;
        // fallback for SuperAdmin whose appClientId may equal clientId
        Object v2 = session.getAttribute("clientId");
        return v2 instanceof String s2 ? s2 : null;
    }

    private static boolean isChurchSession(HttpSession session) {
        Object val = session.getAttribute("church");
        return Boolean.TRUE.equals(val) || "true".equalsIgnoreCase(String.valueOf(val));
    }

    private static String role(HttpSession session) {
        Object val = session.getAttribute("role");
        return val instanceof String s ? s : null;
    }
}
