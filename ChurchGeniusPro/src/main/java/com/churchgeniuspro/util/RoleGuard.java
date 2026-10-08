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
        // The session copy is re-read from the database at most every 15 s, so a
        // change made in viewUsers applies without a new sign-in.
        com.churchgeniuspro.service.PermissionRefresher.refreshIfStale(session);
        Object privAttr = session.getAttribute(isMember ? "memberPrivileges" : "privileges");
        if (privAttr == null) return null;
        return permissionAllows(String.valueOf(privAttr), permKey) ? null : FORWARD_ACCESS_DENIED;
    }

    /**
     * {@link #requirePermission} for PAGE routes (the ones that {@code forward:} to an
     * HTML page). Identical semantics, plus the legacy-key aliases in
     * {@link #PERMISSION_ALIASES}: a record saved by an earlier version of the
     * Permissions screen under an old key name keeps the restriction the admin set.
     *
     * <p>Deliberately NOT used on API endpoints. Permissions control pages, menus and
     * buttons; data endpoints keep the role checks they have today, and the aliases must
     * not change what any API refuses. That is why this is a separate method rather than
     * a change to {@link #requirePermission}.
     */
    public static String requirePagePermission(HttpServletRequest request, String permKey) {
        HttpSession session = request.getSession(false);
        if (session == null) return REDIRECT_LOGIN;
        boolean isMember = "Member".equals(role(session)) && session.getAttribute("memberId") != null;
        if (!isMember && session.getAttribute("username") == null) return REDIRECT_LOGIN;
        if (isChurchSession(session)) return null;
        com.churchgeniuspro.service.PermissionRefresher.refreshIfStale(session);
        Object privAttr = session.getAttribute(isMember ? "memberPrivileges" : "privileges");
        if (privAttr == null) return null;
        return pagePermissionAllows(String.valueOf(privAttr), permKey) ? null : FORWARD_ACCESS_DENIED;
    }

    /**
     * Legacy key names the Permissions screen used in earlier versions, per current key.
     *
     * <p>Three generations of names have been saved into {@code user_permissions} over
     * time. Nothing is renamed in the database; instead a page check on the current
     * key also honours an explicit {@code false} stored under any of its old names.
     * Read-side only, page routes only. An unused alias costs nothing.
     */
    public static final java.util.Map<String, java.util.List<String>> PERMISSION_ALIASES = java.util.Map.ofEntries(
        java.util.Map.entry("accounting.reports", java.util.List.of(
                "reports.income", "reports.expense", "reports.daterange", "reports.taxreport", "reports.financial",
                "accountingReports", "accountingReports.income", "accountingReports.expense",
                "accountingReports.dateRange", "accountingReports.taxReport", "accountingReports.financial")),
        java.util.Map.entry("general.reminders", java.util.List.of(
                "reminders", "reminders.event", "reminders.auto", "reminders.onetime",
                "reminders.eventReminders", "reminders.autoReminders", "reminders.oneTimeReminders")),
        java.util.Map.entry("general.ministry.kids",    java.util.List.of("general.kidsministry", "general.sundayschool")),
        java.util.Map.entry("general.ministry.worship", java.util.List.of("general.worshipplanning")),
        java.util.Map.entry("general.ministry.prayer",  java.util.List.of("general.prayer", "general.prayerRequests")),
        java.util.Map.entry("general.emailsettings",    java.util.List.of("general.email.settings")),
        java.util.Map.entry("general.events",           java.util.List.of("general.event")),
        java.util.Map.entry("admin.membership",         java.util.List.of("admin.membershipRequests")),
        java.util.Map.entry("admin.unsubscribed",       java.util.List.of("admin.unsubscribedList")),
        java.util.Map.entry("admin.email",              java.util.List.of("admin.email.delete", "admin.groups.email")),
        java.util.Map.entry("accounting.donation",      java.util.List.of("accounting.donationReview")),
        java.util.Map.entry("accounting.settings",      java.util.List.of("accountSettings")),
        java.util.Map.entry("more.certificates",        java.util.List.of("general.certificates")),
        java.util.Map.entry("more.publicscreens",       java.util.List.of("general.publicScreens")),
        java.util.Map.entry("member.classes",           java.util.List.of("member.sundayschool"))
    );

    /**
     * {@link #permissionAllows} plus aliases: denied when the key itself, or any of its
     * legacy names, is explicitly {@code false}. A record with none of them is allowed,
     * exactly as before.
     */
    public static boolean pagePermissionAllows(String privilegesJson, String permKey) {
        if (!permissionAllows(privilegesJson, permKey)) return false;
        java.util.List<String> legacy = PERMISSION_ALIASES.get(permKey);
        if (legacy == null) return true;
        for (String old : legacy) {
            if (!permissionAllows(privilegesJson, old)) return false;
        }
        return true;
    }

    /**
     * The granular-permission rule on its own, for callers that hold a privileges
     * JSON rather than a request — e.g. one freshly read from {@code user_permissions}
     * so a change made in viewUsers applies without waiting for the user to sign in
     * again. Identical semantics to {@link #requirePermission}: no JSON → allowed,
     * missing key → allowed, explicit {@code false} → denied, unparseable → allowed.
     */
    @SuppressWarnings("unchecked")
    public static boolean permissionAllows(String privilegesJson, String permKey) {
        if (privilegesJson == null || privilegesJson.isBlank() || "null".equals(privilegesJson)) return true;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, Object> map = mapper.readValue(privilegesJson, java.util.Map.class);
            // Missing key = full access (opt-in denial); explicit false = denied
            return !Boolean.FALSE.equals(map.get(permKey));
        } catch (Exception e) {
            // Unparseable JSON → treat as full access
            return true;
        }
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
        com.churchgeniuspro.service.PermissionRefresher.refreshIfStale(session);
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

    /**
     * {@link #requireMemberPermission} for PAGE routes: the same rule, and additionally
     * denied when a legacy alias of {@code memberPermKey} is explicitly {@code false}
     * (e.g. {@code member.classes} honours an old {@code member.sundayschool:false}).
     */
    public static String requireMemberPagePermission(HttpServletRequest request, String memberPermKey) {
        String deny = requireMemberPermission(request, memberPermKey);
        if (deny != null) return deny;
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        if (!("Member".equals(role(session)) && session.getAttribute("memberId") != null)) return null;
        Object privAttr = session.getAttribute("memberPrivileges");
        if (privAttr == null) return null;
        java.util.List<String> legacy = PERMISSION_ALIASES.get(memberPermKey);
        if (legacy == null) return null;
        for (String old : legacy) {
            if (!permissionAllows(String.valueOf(privAttr), old)) return FORWARD_ACCESS_DENIED;
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

    // ── Opt-in features: Ticketing and AI Assistant (2026-10-01) ───────────────

    /** Permission key for the Ticketing page and its APIs. */
    public static final String PERM_TICKETING    = "more.ticketing";
    /** Permission key for the AI Assistant page and the AI search/assist APIs. */
    public static final String PERM_AI_ASSISTANT = "more.aiassistant";

    /**
     * True for a Kids Portal session: a member-portal login whose family-member
     * role is a child. Ticketing and the AI Assistant are never available there,
     * whatever the stored permissions say.
     */
    public static boolean isKidsPortalSession(HttpSession session) {
        if (session == null) return false;
        if (!("Member".equals(role(session)) && session.getAttribute("memberId") != null)) return false;
        Object mr = session.getAttribute("memberRole");
        String r = mr == null ? "" : mr.toString().trim().toLowerCase();
        return r.equals("child") || r.equals("son") || r.equals("daughter");
    }

    /**
     * Access rule shared by the Ticketing and AI Assistant pages AND their APIs
     * (the permission is enforced on both, not just by hiding the menu item):
     * <ul>
     *   <li>no session → login;</li>
     *   <li>Kids Portal → denied, always;</li>
     *   <li>Church login → allowed (the Church role manages the account);</li>
     *   <li>Member portal → allowed ONLY when the key is explicitly {@code true} in the
     *       member's saved permissions — off by default, granted from /viewusers;</li>
     *   <li>staff (SuperAdmin / Admin / Accountant / User / Limited) → allowed unless the
     *       key is explicitly {@code false} — on by default, like every staff checkbox.</li>
     * </ul>
     * Member and staff permissions are re-read within ~15 s of a change, as for
     * {@link #requirePermission}.
     *
     * @return {@code null} when allowed, otherwise the redirect / forward to return
     */
    public static String requireFeature(HttpServletRequest request, String permKey) {
        HttpSession session = request.getSession(false);
        if (session == null) return REDIRECT_LOGIN;
        if (isKidsPortalSession(session)) return FORWARD_ACCESS_DENIED;
        boolean isMember = "Member".equals(role(session)) && session.getAttribute("memberId") != null;
        if (!isMember && session.getAttribute("username") == null) return REDIRECT_LOGIN;
        if (isChurchSession(session)) return null;
        if (isMember) {
            com.churchgeniuspro.service.PermissionRefresher.refreshIfStale(session);
            Object privAttr = session.getAttribute("memberPrivileges");
            return permissionGrantedExplicitly(privAttr == null ? null : String.valueOf(privAttr), permKey)
                    ? null : FORWARD_ACCESS_DENIED;
        }
        return requirePermission(request, permKey);
    }

    /** True for a feature a session may use ({@link #requireFeature} returns null). */
    public static boolean featureAllowed(HttpServletRequest request, String permKey) {
        return requireFeature(request, permKey) == null;
    }

    /** Opt-in rule: the key must be present and {@code true}; anything else is denied. */
    @SuppressWarnings("unchecked")
    static boolean permissionGrantedExplicitly(String privilegesJson, String permKey) {
        if (privilegesJson == null || privilegesJson.isBlank() || "null".equals(privilegesJson)) return false;
        try {
            java.util.Map<String, Object> map = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(privilegesJson, java.util.Map.class);
            return Boolean.TRUE.equals(map.get(permKey));
        } catch (Exception e) {
            return false;
        }
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
