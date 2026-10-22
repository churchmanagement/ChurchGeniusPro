package com.churchgeniuspro.service;

import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

/**
 * What the AI Search may answer for the current session — decided once per request,
 * server-side, from the session alone (never from the question or the request body).
 *
 * <p>The AI Search is an alternative front door to pages the user could open anyway, so
 * each module is gated by <b>the same rule as that module's page</b>: the page's role
 * guard plus its page-permission key (alias-aware, exactly like
 * {@link RoleGuard#requirePagePermission}), plus the church's subscription feature for
 * the module — the subscription filter only gates URLs, and the AI endpoint's URL is
 * not the module's. When the rule is unclear the answer is "no": Kids Portal sessions
 * get nothing; Member-portal sessions get a module only when its {@code member.*} key
 * is explicitly {@code true} (the member nav shows a tab on the same condition);
 * church-owner sessions are denied the modules whose pages deny them.
 *
 * <p>This is the one place AI permission rules live, so {@code AiSearchService} and
 * {@code AiDataSearchService} cannot drift apart again (the previous copies checked
 * keys that are no longer in the permission tree and so never denied anyone).
 */
@Service
public class AiAccessPolicy {

    /**
     * Resolved access for one request. All data flags are false for a Kids Portal session.
     * Each flag is one module, named after the page whose rule it mirrors (Phase B, 2026-10-21):
     * <ul>
     *   <li>members — /viewfamily: SuperAdmin/Admin/Accountant + admin.family; Member portal: member.directory explicitly true</li>
     *   <li>incomeRecords / expenseRecords / reports — /income, /expense, the report pages: SuperAdmin/Admin/Accountant +
     *       accounting.income / accounting.expense / accounting.reports + plan "accounting"</li>
     *   <li>volunteers — /volunteers: SuperAdmin/Admin/User + general.volunteers + plan "volunteers"</li>
     *   <li>meetings — /meetings: staff roles + general.meetings; Member portal DENIED (no member key exists for meetings)</li>
     *   <li>prayer — /prayerRequest: SuperAdmin/Admin/User + general.ministry.prayer (alias general.prayer honoured) + plan "prayer"</li>
     *   <li>followUps — /followups: SuperAdmin/Admin/User + more.followups + plan "followUps"; only SuperAdmin/Admin see
     *       everyone's items ({@code followUpsAll}), others their own (the page's rule)</li>
     *   <li>events — /events: staff roles + general.events (the page carries no plan feature)</li>
     *   <li>reminders — /eventReminders: SuperAdmin/Admin/User + general.reminders + plan "reminders"</li>
     *   <li>pledges — /pledges: SuperAdmin/Admin/Accountant + accounting.pledges + plan "pledges"</li>
     *   <li>meetingsEdit — POST /api/meetings: meetings + general.meetings.edit (Phase E meeting creation)</li>
     *   <li>kidsMinistry — /kidsMinistry: SuperAdmin/Admin/User + general.ministry.kids + plan "kidsMinistry"</li>
     *   <li>publicLinks — /publicScreens: SuperAdmin/Admin/User + more.publicscreens + plan "publicScreens"</li>
     * </ul>
     */
    public record AiAccess(String role, String username, boolean church, boolean member, boolean kids,
                           boolean members, boolean incomeRecords, boolean expenseRecords, boolean reports,
                           boolean volunteers, boolean meetings, boolean meetingsEdit, boolean prayer, boolean followUps, boolean followUpsAll,
                           boolean events, boolean reminders, boolean pledges, boolean kidsMinistry, boolean publicLinks,
                           Set<String> pages) {
        /** Any finance data at all (used for the generic denial wording). */
        public boolean anyFinance() { return incomeRecords || expenseRecords || reports; }
        /** True when the session may open this catalogue page (Phase D navigation). */
        public boolean canOpen(String url) { return pages != null && pages.contains(url); }
        /** Nothing may be answered except navigation/how-to. */
        public static AiAccess none(String role) {
            return new AiAccess(role, "", false, false, true, false, false, false, false, false,
                                false, false, false, false, false, false, false, false, false, false, Set.of());
        }
        /** Phase A shape (members / finance / volunteers only) — used by tests and the legacy intents. */
        public static AiAccess of(String role, boolean church, boolean member, boolean kids, boolean members,
                                  boolean incomeRecords, boolean expenseRecords, boolean reports, boolean volunteers) {
            return new AiAccess(role, "", church, member, kids, members, incomeRecords, expenseRecords, reports, volunteers,
                                false, false, false, false, false, false, false, false, false, false, Set.of());
        }
    }

    private static final Set<String> FINANCE_ROLES = Set.of("SuperAdmin", "Admin", "Accountant");
    private static final Set<String> MEMBER_PAGE_ROLES = Set.of("SuperAdmin", "Admin", "Accountant");   // /viewfamily
    private static final Set<String> VOLUNTEER_ROLES = Set.of("SuperAdmin", "Admin", "User");          // /volunteers
    private static final Set<String> ADMIN_OR_USER = Set.of("SuperAdmin", "Admin", "User");            // requireAdminOrUser pages
    private static final Set<String> STAFF_ROLES = Set.of("SuperAdmin", "Admin", "Accountant", "User"); // requireAuth / requireStaffOrMember pages
    private static final Set<String> ADMINS = Set.of("SuperAdmin", "Admin");

    private final SubscriptionService subscriptions;   // null in unit tests → features assumed enabled

    @Autowired
    public AiAccessPolicy(SubscriptionService subscriptions) { this.subscriptions = subscriptions; }

    public AiAccessPolicy() { this(null); }

    public AiAccess resolve(HttpServletRequest req, String clientId) {
        HttpSession s = req == null ? null : req.getSession(false);
        if (s == null) return AiAccess.none("");
        String role = s.getAttribute("role") == null ? "" : String.valueOf(s.getAttribute("role"));
        boolean member = "Member".equals(role) && s.getAttribute("memberId") != null;
        if (RoleGuard.isKidsPortalSession(s)) return AiAccess.none(role);
        Object ch = s.getAttribute("church");
        boolean church = Boolean.TRUE.equals(ch) || "true".equalsIgnoreCase(String.valueOf(ch));
        if (!member && s.getAttribute("username") == null) return AiAccess.none(role);

        String username = s.getAttribute("username") == null ? "" : String.valueOf(s.getAttribute("username"));
        if (member) {
            // Member portal: only the Directory tab shows other members; no finance, no
            // volunteer administration, and (decision 1, 2026-10-21) no meetings — the
            // page admits members by role but no member.* key governs it. Explicit true,
            // like the member nav.
            boolean dir = memberKeyTrue(s, "member.directory");
            return new AiAccess(role, username, false, true, false, dir, false, false, false, false,
                                false, false, false, false, false, false, false, false, false, false, openable(req, s, clientId, role, true, false));
        }
        if (church) {
            // The church-owner login cannot open /income, /viewfamily, /volunteers, /prayerRequest,
            // /followups … (their role guards deny it) — the AI mirrors those pages.
            return new AiAccess(role, username, true, false, false, false, false, false, false, false,
                                false, false, false, false, false, false, false, false, false, false, openable(req, s, clientId, role, false, true));
        }
        boolean fin = FINANCE_ROLES.contains(role) && feature(clientId, "accounting");
        boolean members        = MEMBER_PAGE_ROLES.contains(role) && page(req, "admin.family");
        boolean incomeRecords  = fin && page(req, "accounting.income");
        boolean expenseRecords = fin && page(req, "accounting.expense");
        boolean reports        = fin && page(req, "accounting.reports");
        boolean volunteers     = VOLUNTEER_ROLES.contains(role) && page(req, "general.volunteers") && feature(clientId, "volunteers");
        boolean meetings       = STAFF_ROLES.contains(role) && page(req, "general.meetings");
        // Creating a meeting = the POST /api/meetings guard: the page rule above plus the
        // granular general.meetings.edit permission (Phase E).
        boolean meetingsEdit   = meetings && RoleGuard.requirePermission(req, "general.meetings.edit") == null;
        boolean prayer         = ADMIN_OR_USER.contains(role) && page(req, "general.ministry.prayer") && feature(clientId, "prayer");
        boolean followUps      = ADMIN_OR_USER.contains(role) && page(req, "more.followups") && feature(clientId, "followUps");
        boolean events         = STAFF_ROLES.contains(role) && page(req, "general.events");
        boolean reminders      = ADMIN_OR_USER.contains(role) && page(req, "general.reminders") && feature(clientId, "reminders");
        boolean pledges        = FINANCE_ROLES.contains(role) && page(req, "accounting.pledges") && feature(clientId, "pledges");
        boolean kidsMinistry   = ADMIN_OR_USER.contains(role) && page(req, "general.ministry.kids") && feature(clientId, "kidsMinistry");
        boolean publicLinks    = ADMIN_OR_USER.contains(role) && page(req, "more.publicscreens") && feature(clientId, "publicScreens");
        return new AiAccess(role, username, false, false, false, members, incomeRecords, expenseRecords, reports, volunteers,
                            meetings, meetingsEdit, prayer, followUps, followUps && ADMINS.contains(role), events, reminders, pledges, kidsMinistry, publicLinks,
                            openable(req, s, clientId, role, false, false));
    }

    /**
     * The catalogue pages this session may open — each page's own route rule (role guard,
     * alias-aware page key, member tab key or opt-in key) plus the plan feature the
     * subscription filter would apply to its path. Evaluated once per request.
     */
    private Set<String> openable(HttpServletRequest req, HttpSession s, String clientId, String role, boolean member, boolean church) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (AiNavigationCatalog.Page p : AiNavigationCatalog.PAGES) {
            AiNavigationCatalog.Rule r = p.rule();
            boolean ok;
            if (r.optInKey() != null) {
                ok = RoleGuard.featureAllowed(req, r.optInKey());
            } else if (member) {
                ok = r.memberKey() != null && memberKeyTrue(s, r.memberKey());
            } else if (church) {
                ok = r.church();
            } else {
                ok = r.roles().contains(role) && (r.pageKey() == null || page(req, r.pageKey()));
            }
            if (!ok) continue;
            String path = p.url(); int cut = path.indexOf('?'); if (cut > 0) path = path.substring(0, cut); cut = path.indexOf('#'); if (cut > 0) path = path.substring(0, cut);
            String featureKey = com.churchgeniuspro.util.SubscriptionFeatureCatalog.keyForPath(path);
            if (featureKey != null && !feature(clientId, featureKey)) continue;
            out.add(p.url());
        }
        return out;
    }

    private static boolean page(HttpServletRequest req, String key) {
        return RoleGuard.requirePagePermission(req, key) == null;
    }

    @SuppressWarnings("unchecked")
    private static boolean memberKeyTrue(HttpSession s, String key) {
        com.churchgeniuspro.service.PermissionRefresher.refreshIfStale(s);
        Object priv = s.getAttribute("memberPrivileges");
        if (priv == null) return false;
        try {
            java.util.Map<String, Object> map = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(String.valueOf(priv), java.util.Map.class);
            if (Boolean.TRUE.equals(map.get(key))) return true;
            String prefix = key + ".";
            return map.entrySet().stream().anyMatch(e -> e.getKey().startsWith(prefix) && Boolean.TRUE.equals(e.getValue()));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean feature(String clientId, String featureKey) {
        if (subscriptions == null || clientId == null) return true;
        try { return subscriptions.isFeatureEnabled(clientId, featureKey); }
        catch (RuntimeException e) { return false; }   // unclear → deny
    }

    /** Lower-cased role, for callers that want to branch on it without null checks. */
    public static String roleLc(AiAccess a) { return a == null || a.role() == null ? "" : a.role().toLowerCase(Locale.ROOT); }
}
