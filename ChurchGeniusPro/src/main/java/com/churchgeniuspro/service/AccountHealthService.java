package com.churchgeniuspro.service;

import com.churchgeniuspro.config.LoginProtectionProperties;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.plaid.entity.PlaidItem;
import com.churchgeniuspro.plaid.repository.PlaidItemRepository;
import com.churchgeniuspro.plaid.util.PlaidGuard;
import com.churchgeniuspro.repository.ServiceClientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only report for the Service Admin "Account Health" card: churches that are
 * expired, inactive or unused, and bank connections that are paused, broken or idle.
 *
 * <p>"Active" here is {@link SubscriptionService#isAccountActive} — the same rule that
 * refuses sign-in and that the schedulers use — so the list shows exactly the churches
 * whose reminders, pushes and Bank Sync are currently being skipped.
 *
 * <p>Nothing here writes. It lists; the admin decides.
 */
@Service
public class AccountHealthService {

    private static final Logger log = LoggerFactory.getLogger(AccountHealthService.class);

    /** An active account with no sign-in for this long is "unused". Capped by login-log retention. */
    static final int UNUSED_DAYS = 30;
    /** An ACTIVE bank connection not synced for this long is "stale". */
    static final int STALE_SYNC_DAYS = 30;

    private final ServiceClientRepository clientRepo;
    private final PlaidItemRepository     itemRepo;
    private final PlaidGuard              plaidGuard;
    private final JdbcTemplate            jdbc;

    @Autowired(required = false)
    private LoginProtectionProperties loginProps;

    public AccountHealthService(ServiceClientRepository clientRepo,
                                PlaidItemRepository itemRepo,
                                PlaidGuard plaidGuard,
                                JdbcTemplate jdbc) {
        this.clientRepo = clientRepo;
        this.itemRepo   = itemRepo;
        this.plaidGuard = plaidGuard;
        this.jdbc       = jdbc;
    }

    /** Window used for "unused": 30 days, or less when login history is kept for less. */
    int unusedWindowDays() {
        if (loginProps == null || loginProps.getRetention() == null) return UNUSED_DAYS;
        long kept = loginProps.getRetention().toDays();
        return (int) Math.max(1, Math.min(UNUSED_DAYS, kept));
    }

    /** Client ids that still hold a live bank connection although the church itself has been removed. */
    public java.util.Set<String> removedChurchesWithBankSync() {
        java.util.Set<String> live = new java.util.HashSet<>();
        for (ServiceClient sc : clientRepo.findAllByDeleteFlagFalseOrderByIdDesc()) {
            if (sc.getClientId() != null) live.add(sc.getClientId());
        }
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (PlaidItem item : itemRepo.findByDeleteFlagFalse()) {
            if (item.getClientId() != null && !live.contains(item.getClientId())) out.add(item.getClientId());
        }
        return out;
    }

    public Map<String, Object> report() {
        return report(LocalDate.now());
    }

    Map<String, Object> report(LocalDate today) {
        int unusedDays = unusedWindowDays();
        LocalDateTime unusedCutoff = today.minusDays(unusedDays).atStartOfDay();
        Map<String, LastLogin> lastLogin = lastSuccessfulLoginByChurch();
        Map<String, LocalDateTime> lastSignIn = new HashMap<>();
        lastLogin.forEach((k, v) -> lastSignIn.put(k, v.at()));
        LocalDateTime recentCutoff = today.minusDays(UNUSED_DAYS).atStartOfDay();
        List<Map<String, Object>> logins = new ArrayList<>();
        int noRecentLogin = 0;

        List<ServiceClient> clients = clientRepo.findAllByDeleteFlagFalseOrderByIdDesc();
        Map<String, ServiceClient> byClientId = new HashMap<>();
        List<Map<String, Object>> accounts = new ArrayList<>();
        int expired = 0, inactive = 0, unused = 0;

        for (ServiceClient sc : clients) {
            if (sc.getClientId() == null) continue;
            byClientId.put(sc.getClientId(), sc);
            boolean active = SubscriptionService.isAccountActive(sc, today);
            LocalDateTime last = lastSignIn.get(sc.getClientId());

            // 2026-11-16 — login overview: every registered church, successful sign-ins only.
            LastLogin ll = lastLogin.get(sc.getClientId());
            boolean recent = ll != null && !ll.at().isBefore(recentCutoff);
            if (!recent) noRecentLogin++;
            Map<String, Object> lo = new LinkedHashMap<>();
            lo.put("id",            sc.getId());
            lo.put("clientId",      sc.getClientId());
            lo.put("kind",          kindOf(sc.getClientId()));
            lo.put("churchName",    sc.getChurchName());
            lo.put("status",        sc.getStatus());
            lo.put("accountActive", active);
            lo.put("lastLogin",     ll == null ? null : ll.at().toString());
            lo.put("lastLoginUser", ll == null ? null : ll.username());
            lo.put("loggedInRecently", recent);
            logins.add(lo);

            String state;
            if (!active) {
                if (!"Active".equals(sc.getStatus())) { state = "INACTIVE"; inactive++; }
                else                                  { state = "EXPIRED";  expired++;  }
            } else if (last == null || last.isBefore(unusedCutoff)) {
                state = "UNUSED"; unused++;
            } else {
                continue;   // active and in use — nothing to report
            }

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id",          sc.getId());
            r.put("clientId",    sc.getClientId());
            r.put("kind",        kindOf(sc.getClientId()));
            r.put("churchName",  sc.getChurchName());
            r.put("contactName", sc.getName());
            r.put("email",       sc.getEmail());
            r.put("plan",        sc.getSubscriptionType());
            r.put("status",      sc.getStatus());
            r.put("endDate",     sc.getEndDate() == null ? null : sc.getEndDate().toString());
            r.put("daysSinceExpiry", sc.getEndDate() != null && "EXPIRED".equals(state)
                    ? ChronoUnit.DAYS.between(sc.getEndDate(), today) : null);
            r.put("lastSignIn",  last == null ? null : last.toString());
            r.put("state",       state);
            accounts.add(r);
        }

        List<Map<String, Object>> banks = new ArrayList<>();
        int paused = 0;
        LocalDate staleCutoff = today.minusDays(STALE_SYNC_DAYS);
        // 2026-11-16 — a connection whose church has been removed is not an active issue: nobody can act on it.
        // It is listed apart (removedChurchBankSyncs) so it can be closed, and stays out of the attention list.
        List<Map<String, Object>> orphaned = new ArrayList<>();
        for (PlaidItem item : itemRepo.findByDeleteFlagFalse()) {
            ServiceClient sc = byClientId.get(item.getClientId());
            if (sc == null) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("itemDbId",    item.getId());
                o.put("clientId",    item.getClientId());
                o.put("institution", item.getInstitutionName());
                o.put("status",      item.getStatus());
                orphaned.add(o);
                continue;
            }
            boolean churchActive = SubscriptionService.isAccountActive(sc, today);
            String st = item.getStatus() == null ? "" : item.getStatus();
            LocalDate lastSync = toLocalDate(item.getLastSyncedDate());
            boolean syncOn = safeSyncEnabled(item.getClientId());

            List<String> reasons = new ArrayList<>();
            if (!churchActive) { reasons.add("PAUSED"); paused++; }
            if ("DISCONNECTED".equals(st)) reasons.add("DISCONNECTED");
            else if ("LOGIN_REQUIRED".equals(st) || "ERROR".equals(st)
                  || "DEGRADED".equals(st) || "PENDING".equals(st)) reasons.add("NEEDS_ATTENTION");
            if ("ACTIVE".equals(st) && (lastSync == null || lastSync.isBefore(staleCutoff))) reasons.add("STALE");
            if (!syncOn && !"DISCONNECTED".equals(st)) reasons.add("SYNC_DISABLED");
            if (reasons.isEmpty()) continue;

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("itemDbId",        item.getId());
            r.put("serviceClientId", sc == null ? null : sc.getId());
            r.put("clientId",        item.getClientId());
            r.put("churchName",      sc == null ? null : sc.getChurchName());
            r.put("institution",     item.getInstitutionName());
            r.put("status",          st);
            r.put("errorCode",       item.getErrorCode());
            r.put("lastSynced",      lastSync == null ? null : lastSync.toString());
            r.put("syncEnabled",     syncOn);
            r.put("churchActive",    churchActive);
            r.put("reasons",         reasons);
            banks.add(r);
        }

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("expired",  expired);
        counts.put("inactive", inactive);
        counts.put("unused",   unused);
        counts.put("bankIssues", banks.size());
        counts.put("bankPaused", paused);
        counts.put("removedChurchBankSyncs", orphaned.size());
        counts.put("noRecentLogin", noRecentLogin);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedOn",   today.toString());
        out.put("unusedDays",    unusedDays);
        out.put("staleSyncDays", STALE_SYNC_DAYS);
        out.put("counts",        counts);
        out.put("accounts",      accounts);
        // Churches with no recent sign-in first, then the most recent sign-in first.
        logins.sort((a, b) -> {
            boolean ra = Boolean.TRUE.equals(a.get("loggedInRecently")), rb = Boolean.TRUE.equals(b.get("loggedInRecently"));
            if (ra != rb) return ra ? 1 : -1;
            String la = (String) a.get("lastLogin"), lb = (String) b.get("lastLogin");
            if (la == null && lb == null) return 0;
            if (la == null) return -1;
            if (lb == null) return 1;
            return lb.compareTo(la);
        });
        out.put("logins",        logins);
        out.put("recentLoginDays", UNUSED_DAYS);
        out.put("loginHistoryDays", loginProps == null || loginProps.getRetention() == null
                ? null : loginProps.getRetention().toDays());
        out.put("bankSyncs",     banks);
        out.put("removedChurchBankSyncs", orphaned);
        return out;
    }

    /**
     * Last successful sign-in per CHURCH. {@code login_attempt_log.client_id} holds the
     * login's own id — the church id for Church logins, the {@code app_user.user_id} for
     * staff, the {@code member_ref} for portal logins — so each shape is mapped back to
     * its church. Kept only for the login-log retention window.
     */
    Map<String, LocalDateTime> lastSuccessfulSignInByChurch() {
        Map<String, LocalDateTime> out = new HashMap<>();
        lastSuccessfulLoginByChurch().forEach((k, v) -> out.put(k, v.at()));
        return out;
    }

    /** The most recent successful sign-in of a church: when, and which login it was. */
    record LastLogin(LocalDateTime at, String username) {}

    /**
     * 2026-11-16 — same mapping as above, and also WHO signed in last. Successful sign-ins only:
     * failed, blocked and refused attempts are never read.
     */
    Map<String, LastLogin> lastSuccessfulLoginByChurch() {
        Map<String, LastLogin> out = new HashMap<>();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT COALESCE(sc.client_id, au.client_id, fm.app_client_id) AS church, "
                  + "       l.username AS username, MAX(l.attempted_at) AS last_at "
                  + "FROM login_attempt_log l "
                  + "LEFT JOIN service_client sc ON sc.client_id = l.client_id "
                  + "LEFT JOIN app_user      au ON au.user_id   = l.client_id "
                  + "LEFT JOIN family_member fm ON fm.member_ref = l.client_id "
                  + "WHERE l.outcome = 'SUCCESS' AND l.client_id IS NOT NULL "
                  + "GROUP BY COALESCE(sc.client_id, au.client_id, fm.app_client_id), l.username");
            for (Map<String, Object> r : rows) {
                Object church = r.get("church");
                Object at = r.get("last_at");
                if (church == null || at == null) continue;
                LocalDateTime t = at instanceof java.sql.Timestamp ts ? ts.toLocalDateTime()
                                : at instanceof LocalDateTime ldt ? ldt
                                : LocalDateTime.parse(at.toString().replace(' ', 'T'));
                Object user = r.get("username");
                LastLogin mine = new LastLogin(t, user == null ? null : user.toString());
                out.merge(church.toString(), mine, (a, b) -> a.at().isAfter(b.at()) ? a : b);
            }
        } catch (Exception e) {
            log.warn("Account health: could not read sign-in history — {}", e.getMessage());
        }
        return out;
    }

    private boolean safeSyncEnabled(String clientId) {
        try { return plaidGuard.isSyncEnabled(clientId); }
        catch (Exception e) { return false; }
    }

    private static String kindOf(String clientId) {
        if (TestDataService.isTrialTenant(clientId)) return "TRIAL";
        if (clientId != null && clientId.startsWith(TestDataService.DEMO_CLIENT_PREFIX)) return "DEMO";
        return "CLIENT";
    }

    private static LocalDate toLocalDate(java.util.Date d) {
        if (d == null) return null;
        if (d instanceof java.sql.Date sd) return sd.toLocalDate();
        return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
