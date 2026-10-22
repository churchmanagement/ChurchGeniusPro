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

    /** Resolved access for one request. All flags are false for a Kids Portal session. */
    public record AiAccess(String role, boolean church, boolean member, boolean kids,
                           boolean members, boolean incomeRecords, boolean expenseRecords,
                           boolean reports, boolean volunteers) {
        /** Any finance data at all (used for the generic denial wording). */
        public boolean anyFinance() { return incomeRecords || expenseRecords || reports; }
        /** Nothing may be answered except navigation/how-to. */
        public static AiAccess none(String role) {
            return new AiAccess(role, false, false, true, false, false, false, false, false);
        }
    }

    private static final Set<String> FINANCE_ROLES = Set.of("SuperAdmin", "Admin", "Accountant");
    private static final Set<String> MEMBER_PAGE_ROLES = Set.of("SuperAdmin", "Admin", "Accountant");   // /viewfamily
    private static final Set<String> VOLUNTEER_ROLES = Set.of("SuperAdmin", "Admin", "User");          // /volunteers

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

        boolean members, incomeRecords, expenseRecords, reports, volunteers;
        if (member) {
            // Member portal: only the Directory tab shows other members; no finance,
            // no volunteer administration. Explicit true, like the member nav.
            members = memberKeyTrue(s, "member.directory");
            incomeRecords = expenseRecords = reports = volunteers = false;
        } else if (church) {
            // The church-owner login cannot open /income, /viewfamily or /volunteers
            // (their role guards deny it) — the AI mirrors those pages.
            members = incomeRecords = expenseRecords = reports = volunteers = false;
        } else {
            boolean fin = FINANCE_ROLES.contains(role) && feature(clientId, "accounting");
            members        = MEMBER_PAGE_ROLES.contains(role) && page(req, "admin.family");
            incomeRecords  = fin && page(req, "accounting.income");
            expenseRecords = fin && page(req, "accounting.expense");
            reports        = fin && page(req, "accounting.reports");
            volunteers     = VOLUNTEER_ROLES.contains(role) && page(req, "general.volunteers") && feature(clientId, "volunteers");
        }
        return new AiAccess(role, church, member, false, members, incomeRecords, expenseRecords, reports, volunteers);
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
