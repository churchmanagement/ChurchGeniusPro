package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.DemoClientSettings;
import com.churchgeniuspro.hibernate.DemoRoleAccess;
import com.churchgeniuspro.repository.DemoClientSettingsRepository;
import com.churchgeniuspro.repository.DemoRoleAccessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Access windows and delivery switches for demo/test tenants.
 *
 * <p>Two jobs:
 * <ul>
 *   <li>Give every demo login its own end date, enforced at sign-in, so one
 *       expired role cannot keep working and cannot take its siblings down.</li>
 *   <li>Keep real SMS/email delivery off for demo tenants unless a service admin
 *       deliberately turns it on for that client.</li>
 * </ul>
 *
 * <p>Existing demo tenants are handled by {@link #backfill()}, which gives every
 * pre-existing login a window derived from its tenant's subscription end date.
 * Nothing here rewrites demo data.
 */
@Service
public class DemoAccessService {

    private static final Logger log = LoggerFactory.getLogger(DemoAccessService.class);

    /** Demo tenants are the ones whose client_id carries this prefix. */
    public static final String DEMO_PREFIX = TestDataService.DEMO_CLIENT_PREFIX;

    /** Self-service trial tenants, managed on the same screen. */
    public static final String TRIAL_PREFIX = TestDataService.TRIAL_CLIENT_PREFIX;

    /**
     * A reset issues a fresh window of the configured trial length
     * ({@link TrialPolicy#trialDays()}); {@link TrialPolicy#FALLBACK_DAYS} without a policy.
     */

    private final DemoRoleAccessRepository accessRepo;
    private final DemoClientSettingsRepository settingsRepo;
    private final JdbcTemplate jdbc;

    private TrialPolicy trialPolicy;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTrialPolicy(TrialPolicy p) { this.trialPolicy = p; }
    private int defaultTrialDays() { return trialPolicy != null ? trialPolicy.trialDays() : TrialPolicy.FALLBACK_DAYS; }

    public DemoAccessService(DemoRoleAccessRepository accessRepo,
                             DemoClientSettingsRepository settingsRepo,
                             JdbcTemplate jdbc) {
        this.accessRepo   = accessRepo;
        this.settingsRepo = settingsRepo;
        this.jdbc         = jdbc;
    }

    /* ── is this even a demo tenant? ────────────────────────────────────── */

    /**
     * True for a tenant this service governs — demo or self-service trial.
     *
     * <p>Widened to cover trial tenants when self-service trial registration was
     * added. That is deliberate on both counts it affects: trial logins get access
     * windows, reminders and the Service Admin controls that demo logins already
     * had, and trial tenants start with real SMS/email delivery switched OFF, so a
     * church that signed itself up minutes ago cannot message its congregation
     * until a Service Admin turns it on.
     */
    public boolean isDemoClient(String clientId) {
        return clientId != null
                && (clientId.startsWith(DEMO_PREFIX) || clientId.startsWith(TRIAL_PREFIX));
    }

    /* ── settings ───────────────────────────────────────────────────────── */

    /** Settings for a demo tenant, created blocked-by-default on first touch. */
    @Transactional
    public DemoClientSettings settingsFor(String clientId) {
        return settingsRepo.findById(clientId).orElseGet(() -> {
            DemoClientSettings s = new DemoClientSettings();
            s.setClientId(clientId);
            s.setAllowSms(false);          // demo tenants must not text real people
            s.setAllowEmail(false);        // ...or email them
            s.setReminderDays("10,5,1");
            s.setUpdatedAt(LocalDateTime.now());
            return settingsRepo.save(s);
        });
    }

    /**
     * Whether real delivery is permitted for a client.
     *
     * <p>Non-demo clients are unaffected: they always send. Only demo tenants are
     * gated, and only on an explicit opt-in.
     */
    public boolean sendingAllowed(String clientId, boolean sms) {
        if (!isDemoClient(clientId)) return true;
        DemoClientSettings s = settingsRepo.findById(clientId).orElse(null);
        if (s == null) return false;       // unknown demo tenant → blocked, not open
        return sms ? Boolean.TRUE.equals(s.getAllowSms())
                   : Boolean.TRUE.equals(s.getAllowEmail());
    }

    @Transactional
    public DemoClientSettings setSending(String clientId, Boolean allowSms, Boolean allowEmail) {
        DemoClientSettings s = settingsFor(clientId);
        if (allowSms   != null) s.setAllowSms(allowSms);
        if (allowEmail != null) s.setAllowEmail(allowEmail);
        s.setUpdatedAt(LocalDateTime.now());
        return settingsRepo.save(s);
    }

    @Transactional
    public DemoClientSettings setReminderDays(String clientId, String csv) {
        DemoClientSettings s = settingsFor(clientId);
        s.setReminderDays(normaliseDays(csv));
        s.setUpdatedAt(LocalDateTime.now());
        return settingsRepo.save(s);
    }

    /** "10, 5, 1, 5, x" → "10,5,1" — deduped, descending, digits only. */
    public static String normaliseDays(String csv) {
        if (csv == null || csv.isBlank()) return "";
        TreeSet<Integer> days = new TreeSet<>(Comparator.reverseOrder());
        for (String p : csv.split("[,;\\s]+")) {
            try {
                int d = Integer.parseInt(p.trim());
                if (d > 0 && d <= 365) days.add(d);
            } catch (NumberFormatException ignored) { /* skip junk silently */ }
        }
        StringJoiner j = new StringJoiner(",");
        days.forEach(d -> j.add(String.valueOf(d)));
        return j.toString();
    }

    /**
     * Records the choices made on the Load Test Data screen, so a role added to
     * this tenant months later still defaults to the expiry the admin picked.
     */
    @Transactional
    public DemoClientSettings configureNewTenant(String clientId, LocalDate expiresOn,
                                                 Integer expiryDays, String reminderCsv) {
        DemoClientSettings c = settingsFor(clientId);
        c.setDefaultEndDate(expiresOn);
        c.setDefaultExpiryDays(expiryDays);
        if (reminderCsv != null && !reminderCsv.isBlank()) c.setReminderDays(normaliseDays(reminderCsv));
        c.setUpdatedAt(LocalDateTime.now());
        return settingsRepo.save(c);
    }

    /* ── access windows ─────────────────────────────────────────────────── */

    public Optional<DemoRoleAccess> forSignup(Integer signupId) {
        return signupId == null ? Optional.empty() : accessRepo.findBySignupId(signupId);
    }

    /**
     * The window for a login by its current username.
     *
     * <p>Used by the request filter, which has a session but not always the signup
     * id: only the sign-in path records that. Reset keeps this row's username in
     * step, so lookups by name stay correct after a reissue.
     */
    public Optional<DemoRoleAccess> forUsername(String username) {
        return username == null || username.isBlank()
                ? Optional.empty()
                : accessRepo.findByUsername(username);
    }

    public List<DemoRoleAccess> forClient(String clientId) {
        return accessRepo.findByClientId(clientId);
    }

    @Transactional
    public DemoRoleAccess setEndDate(Long id, LocalDate endDate) {
        DemoRoleAccess a = accessRepo.findById(id).orElseThrow(
                () -> new IllegalArgumentException("No such demo role: " + id));
        a.setEndDate(endDate);
        a.setUpdatedAt(LocalDateTime.now());
        DemoRoleAccess saved = accessRepo.save(a);
        syncPortalWindows(saved);
        return saved;
    }

    @Transactional
    public DemoRoleAccess setBlocked(Long id, boolean blocked) {
        DemoRoleAccess a = accessRepo.findById(id).orElseThrow(
                () -> new IllegalArgumentException("No such demo role: " + id));
        a.setBlocked(blocked);
        a.setUpdatedAt(LocalDateTime.now());
        DemoRoleAccess saved = accessRepo.save(a);
        syncPortalWindows(saved);
        return saved;
    }

    /* ── the three portals move together ────────────────────────────────── */

    /**
     * The roles that OWN a demo/trial tenant, and whose window the portals follow.
     *
     * <p>Deliberately not every staff role: a demo tenant has four of them, and
     * blocking the sample "User" login is about that login, not about the account.
     * The Church login and the SuperAdmin are the account itself.
     */
    private static final Set<String> GOVERNING_ROLES =
            Set.of(TestDataService.ROLE_CHURCH, "SuperAdmin");

    /** The portal roles that follow — a Member Portal and a Kids (Child) Portal. */
    private static final Set<String> PORTAL_ROLES =
            Set.of(TestDataService.ROLE_MEMBER_PORTAL, TestDataService.ROLE_CHILD_PORTAL);

    /**
     * Carries a governing login's end date and block through to the tenant's
     * Member and Kids portals.
     *
     * <p>A trial or demo account is one account with three doors into it. Blocking
     * the staff login while the Member and Kids portals stayed open left two of
     * those doors unlocked — the evaluation carried on, in the same tenant, with
     * the same data, through a login the Service Admin had already shut off. The
     * three windows are kept in step here, in the service, so every caller gets it
     * and no screen has to remember.
     *
     * <p>The direction is one-way and deliberate: the portals follow the account,
     * never the other way round. Blocking a portal on its own is still allowed and
     * still means only that portal.
     *
     * @return how many portal windows were changed
     */
    @Transactional
    public int syncPortalWindows(DemoRoleAccess source) {
        if (source == null || source.getClientId() == null) return 0;
        if (!GOVERNING_ROLES.contains(canonicalRole(source.getRoleLabel()))) return 0;

        int changed = 0;
        for (DemoRoleAccess a : accessRepo.findByClientId(source.getClientId())) {
            if (Objects.equals(a.getId(), source.getId())) continue;
            if (!PORTAL_ROLES.contains(canonicalRole(a.getRoleLabel()))) continue;

            boolean dirty = false;
            if (!Objects.equals(a.getEndDate(), source.getEndDate())) {
                a.setEndDate(source.getEndDate());
                dirty = true;
            }
            if (!Objects.equals(Boolean.TRUE.equals(a.getBlocked()),
                                Boolean.TRUE.equals(source.getBlocked()))) {
                a.setBlocked(Boolean.TRUE.equals(source.getBlocked()));
                dirty = true;
            }
            if (dirty) {
                a.setUpdatedAt(LocalDateTime.now());
                accessRepo.save(a);
                changed++;
            }
        }
        if (changed > 0) {
            log.info("DemoAccessService: {} portal window(s) followed {} for {} — endDate={} blocked={}",
                     changed, source.getRoleLabel(), source.getClientId(),
                     source.getEndDate(), Boolean.TRUE.equals(source.getBlocked()));
        }
        return changed;
    }

    /** A role label as {@link TestDataService} spells it, so comparisons are stable. */
    private static String canonicalRole(String roleLabel) {
        return roleLabel == null ? "" : TestDataService.canonicalDemoRole(roleLabel);
    }

    /**
     * Records that the trial-account agreement was accepted for one demo login.
     *
     * <p>Idempotent: the first acceptance wins, so a double-clicked OK button or a
     * replayed request never moves the timestamp. Keyed by signup id — the same key
     * the window itself uses — so it survives a username change.
     *
     * @return the saved window, or empty when this signup has no demo window
     */
    @Transactional
    public Optional<DemoRoleAccess> acceptAgreement(Integer signupId) {
        if (signupId == null) return Optional.empty();
        Optional<DemoRoleAccess> found = accessRepo.findBySignupId(signupId);
        if (found.isEmpty()) return found;
        DemoRoleAccess a = found.get();
        if (a.getAgreementAcceptedAt() == null) {
            a.setAgreementAcceptedAt(LocalDateTime.now());
            a.setUpdatedAt(LocalDateTime.now());
            a = accessRepo.save(a);
        }
        return Optional.of(a);
    }

    /**
     * Records that the product tour is done for this login — finished or skipped,
     * which are the same thing as far as "do not show it again" is concerned.
     *
     * <p>Written once: a second call on an already-marked row leaves the original
     * timestamp alone, so the record says when the person actually saw it.
     */
    public Optional<DemoRoleAccess> completeProductTour(Integer signupId) {
        if (signupId == null) return Optional.empty();
        Optional<DemoRoleAccess> found = accessRepo.findBySignupId(signupId);
        if (found.isEmpty()) return found;
        DemoRoleAccess a = found.get();
        if (a.getProductTourAt() == null) {
            a.setProductTourAt(LocalDateTime.now());
            a.setUpdatedAt(LocalDateTime.now());
            a = accessRepo.save(a);
        }
        return Optional.of(a);
    }

    public DemoRoleAccess forAccessId(Long id) {
        return accessRepo.findById(id).orElseThrow(
                () -> new IllegalArgumentException("No such demo role: " + id));
    }

    /**
     * Applies a reissued login to its window: new username, a fresh window of the
     * configured trial length from today, and the block cleared. Role, member name and Client ID are kept —
     * they identify the role, and Reset is not meant to change who it is.
     */
    @Transactional
    public DemoRoleAccess reissue(Long id, String newUsername) {
        DemoRoleAccess a = forAccessId(id);
        a.setUsername(newUsername);
        a.setStartDate(LocalDate.now());
        a.setEndDate(LocalDate.now().plusDays(defaultTrialDays()));
        a.setBlocked(false);
        a.setLastResetAt(LocalDateTime.now());
        // A reset issues a new username and a new trial window, so whoever holds
        // the credential now has not agreed to THIS trial period. Clearing the
        // acceptance makes the popup appear once more on the next sign-in.
        a.setAgreementAcceptedAt(null);
        // …and the tour goes with it: a reissued credential is a new person, who
        // should be shown round the product just as the first one was.
        a.setProductTourAt(null);
        a.setUpdatedAt(LocalDateTime.now());
        DemoRoleAccess saved = accessRepo.save(a);
        // A reset reopens the account, so the portals reopen with it — otherwise a
        // reissued staff login works and its Member and Kids portals stay expired.
        syncPortalWindows(saved);
        return saved;
    }

    /**
     * Moves every window of a tenant out to a new end date.
     *
     * <p>A demo or trial tenant is governed by two dates: its subscription's
     * {@code end_date}, and each login's own window. Extending only the first left
     * every login still refused at sign-in with "your demo/trial access has
     * expired" — the paid extension appeared to do nothing, and the only way out
     * was to edit each role's date by hand on the Service Admin screen.
     *
     * <p>Only moves dates FORWARD, and only for windows that would otherwise expire
     * first: a window an admin has deliberately set further out, or shortened for
     * one role, is left as they set it. A blocked login stays blocked — blocking is
     * about that login, not about the subscription.
     *
     * @return how many windows were moved
     */
    @Transactional
    public int extendWindows(String clientId, LocalDate endDate) {
        if (clientId == null || endDate == null) return 0;
        int moved = 0;
        for (DemoRoleAccess a : accessRepo.findByClientId(clientId)) {
            if (a.getEndDate() != null && a.getEndDate().isAfter(endDate)) continue;  // already later
            a.setEndDate(endDate);
            a.setUpdatedAt(LocalDateTime.now());
            accessRepo.save(a);
            moved++;
        }
        if (moved > 0) {
            log.info("DemoAccessService: extended {} access window(s) for {} to {}", moved, clientId, endDate);
        }
        return moved;
    }

    /** Records a new login's window. Used when a role is added to a tenant. */
    @Transactional
    public DemoRoleAccess record(Integer signupId, String clientId, String username,
                                 String roleLabel, String memberName, LocalDate endDate) {
        DemoRoleAccess a = accessRepo.findBySignupId(signupId).orElseGet(DemoRoleAccess::new);
        a.setSignupId(signupId);
        a.setClientId(clientId);
        a.setUsername(username);
        a.setRoleLabel(roleLabel);
        a.setMemberName(memberName);
        if (a.getStartDate() == null) a.setStartDate(LocalDate.now());
        a.setEndDate(endDate);
        if (a.getBlocked() == null) a.setBlocked(false);
        if (a.getCreatedAt() == null) a.setCreatedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        return accessRepo.save(a);
    }

    /**
     * The window for one login, created on the spot from the tenant's expiry if
     * this demo login has never had one.
     *
     * <p>Called from the login path so enforcement cannot be bypassed by a demo
     * account that pre-dates the feature and has not been backfilled yet. Without
     * this, "no row" would silently mean "no limit" — exactly the bypass the
     * requirement is meant to close.
     */
    @Transactional
    public DemoRoleAccess ensureWindow(Integer signupId, String clientId, String username) {
        Optional<DemoRoleAccess> existing = accessRepo.findBySignupId(signupId);
        if (existing.isPresent()) return existing.get();
        settingsFor(clientId);
        return record(signupId, clientId, username, null, null, tenantEndDate(clientId));
    }

    /**
     * Every demo login merged with its access window and its tenant's delivery
     * switches — one row per line of the Service Admin table.
     *
     * <p>Reads the credentials live rather than from {@code demo_role_access},
     * so a username reissued by Reset shows immediately, and windows are matched
     * by signup id, which Reset does not change.
     */
    public List<Map<String, Object>> listForAdmin() {
        Map<Integer, DemoRoleAccess> bySignup = new HashMap<>();
        for (DemoRoleAccess a : accessRepo.findAll()) bySignup.put(a.getSignupId(), a);

        Map<String, DemoClientSettings> cfg = new HashMap<>();
        for (DemoClientSettings c : settingsRepo.findAll()) cfg.put(c.getClientId(), c);

        // Soft-deleted tenants, so the screen can badge them and offer Restore
        // rather than silently listing an account that can no longer be used.
        java.util.Set<String> deletedTenants = new java.util.HashSet<>();
        try {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT client_id FROM service_client WHERE delete_flag = true "
                  + "  AND (client_id LIKE ? OR client_id LIKE ?)",
                    DEMO_PREFIX + "%", TRIAL_PREFIX + "%")) {
                deletedTenants.add(String.valueOf(r.get("client_id")));
            }
        } catch (Exception e) {
            log.warn("DemoAccessService: could not read soft-deleted tenants — {}", e.getMessage());
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : allDemoLogins()) {
            Integer signupId = r.get("signup_id") == null ? null
                             : ((Number) r.get("signup_id")).intValue();
            String tenant = (String) r.get("tenant");
            if (signupId == null || tenant == null) continue;

            DemoRoleAccess w = bySignup.get(signupId);
            DemoClientSettings c = cfg.get(tenant);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("signupId",   signupId);
            row.put("accessId",   w == null ? null : w.getId());
            row.put("clientId",   tenant);
            row.put("role",       r.get("role_label"));
            row.put("memberName", ((String) Objects.toString(r.get("member_name"), "")).trim());
            row.put("username",   r.get("username"));
            row.put("password",   Objects.toString(r.get("demo_password"), ""));
            row.put("startDate",  w == null ? null : String.valueOf(w.getStartDate()));
            row.put("endDate",    w == null || w.getEndDate() == null ? null : String.valueOf(w.getEndDate()));
            // No window yet means the login has not been seen since the feature
            // shipped; it is created on first sign-in, so report it as such rather
            // than implying it is unrestricted.
            row.put("status",     w == null ? "PENDING" : w.getStatus());
            row.put("agreementAccepted", w != null && w.isAgreementAccepted());
            row.put("agreementAcceptedAt",
                    w == null || w.getAgreementAcceptedAt() == null ? null
                            : String.valueOf(w.getAgreementAcceptedAt()));
            // What the delete controls on this row may offer. The Church login is
            // the account itself, not a role within it, and the server refuses to
            // delete one on its own — said here too so the button is never drawn.
            row.put("trial",      TestDataService.isTrialTenant(tenant));
            row.put("deletable",  com.churchgeniuspro.service.TrialDeletionService
                                      .isRoleDeletable(String.valueOf(r.get("role_label"))));
            row.put("tenantDeleted", deletedTenants.contains(tenant));
            row.put("allowSms",   c != null && Boolean.TRUE.equals(c.getAllowSms()));
            row.put("allowEmail", c != null && Boolean.TRUE.equals(c.getAllowEmail()));
            row.put("reminderDays", c == null ? "10,5,1" : c.getReminderDays());
            out.add(row);
        }
        // Within a tenant, order by the role hierarchy the Service Admin screen
        // offers rather than alphabetically, so the table reads Church → SuperAdmin
        // → … → Child Portal. Roles outside that list (older free-text ones) sort
        // after it, by name, instead of being dropped or jumbled in.
        out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("clientId")))
                           .thenComparingInt(m -> roleRank(String.valueOf(m.get("role"))))
                           .thenComparing(m -> String.valueOf(m.get("role")))
                           .thenComparing(m -> String.valueOf(m.get("username"))));
        return out;
    }

    /**
     * Where a role sits in the hierarchy the Service Admin screen offers.
     * Unknown roles rank last so nothing is ever hidden by being unrecognised.
     */
    private static int roleRank(String role) {
        int i = TestDataService.DEMO_ROLES.indexOf(TestDataService.canonicalDemoRole(role));
        return i < 0 ? TestDataService.DEMO_ROLES.size() : i;
    }

    /* ── backfill for demo tenants that pre-date this feature ───────────── */

    /**
     * Gives every existing demo login an access window, so expiry, reset and
     * reminders behave identically on old and new demo data.
     *
     * <p>End date comes from the tenant's own subscription row, which is the
     * expiry the admin chose when the data was loaded. Rows that already exist
     * are left alone — backfill never overwrites a date someone has since edited.
     *
     * @return how many windows were created
     */
    /**
     * SQL for "every demo login and the tenant it really belongs to".
     *
     * <p>Three shapes, because {@code signup.client_id} means three different
     * things: the tenant for a church login, the {@code app_user.user_id} for a
     * staff login, and the {@code family_member.member_ref} for a portal login.
     * Matching on the signup's own client_id alone would find only church rows —
     * which is exactly the bug this query exists to avoid.
     */
    private static final String DEMO_LOGINS_SQL =
            "SELECT s.id AS signup_id, s.client_id AS tenant, s.username, s.demo_password, " +
            "       'Church' AS role_label, '' AS member_name " +
            "  FROM signup s " +
            " WHERE (s.client_id LIKE ? OR s.client_id LIKE ?) " +
            "   AND (s.deleted IS NULL OR s.deleted = false) " +
            "UNION ALL " +
            "SELECT s.id, u.client_id, s.username, s.demo_password, " +
            "       COALESCE(u.role, 'Staff'), " +
            "       COALESCE(u.first_name, '') || ' ' || COALESCE(u.last_name, '') " +
            "  FROM signup s JOIN app_user u ON u.user_id = s.client_id " +
            " WHERE (u.client_id LIKE ? OR u.client_id LIKE ?) " +
            "   AND (s.deleted IS NULL OR s.deleted = false) " +
            "UNION ALL " +
            "SELECT s.id, fm.app_client_id, s.username, s.demo_password, " +
            "       CASE WHEN LOWER(fm.role) = 'child' THEN 'Child Portal' ELSE 'Member Portal' END, " +
            "       COALESCE(fm.first_name, '') || ' ' || COALESCE(fm.last_name, '') " +
            "  FROM signup s JOIN family_member fm ON fm.member_ref = s.client_id " +
            " WHERE (fm.app_client_id LIKE ? OR fm.app_client_id LIKE ?) " +
            "   AND (s.deleted IS NULL OR s.deleted = false) ";

    /** Every demo login with its resolved tenant, role label and member name. */
    public List<Map<String, Object>> allDemoLogins() {
        String demo  = DEMO_PREFIX  + "%";
        String trial = TRIAL_PREFIX + "%";
        // Six binds: each of the three login shapes matches either prefix.
        return jdbc.queryForList(DEMO_LOGINS_SQL, demo, trial, demo, trial, demo, trial);
    }

    /**
     * Gives every existing demo login an access window, so expiry, reset and
     * reminders behave identically on old and new demo data.
     *
     * <p>End date comes from the tenant's own subscription row — the expiry the
     * admin chose when the data was loaded. Existing rows are left alone, so a
     * backfill never overwrites a date someone has since edited.
     *
     * @return how many windows were created
     */
    @Transactional
    public int backfill() {
        Map<String, LocalDate> tenantEnd = new HashMap<>();
        int created = 0;
        for (Map<String, Object> r : allDemoLogins()) {
            Integer signupId = r.get("signup_id") == null ? null
                             : ((Number) r.get("signup_id")).intValue();
            String tenant   = (String) r.get("tenant");
            String username = (String) r.get("username");
            if (signupId == null || tenant == null) continue;
            if (accessRepo.findBySignupId(signupId).isPresent()) continue;   // never clobber

            LocalDate end = tenantEnd.computeIfAbsent(tenant, this::tenantEndDate);
            record(signupId, tenant, username,
                   (String) r.get("role_label"),
                   ((String) r.getOrDefault("member_name", "")).trim(), end);
            settingsFor(tenant);            // ensure the tenant is blocked-by-default too
            created++;
        }
        if (created > 0) log.info("DemoAccessService: backfilled {} demo access windows", created);
        return created;
    }

    /** The tenant's subscription end date, or null when it has none. */
    private LocalDate tenantEndDate(String clientId) {
        try {
            List<Map<String, Object>> r = jdbc.queryForList(
                    "SELECT end_date FROM service_client WHERE client_id = ? ORDER BY id DESC LIMIT 1",
                    clientId);
            if (r.isEmpty() || r.get(0).get("end_date") == null) return null;
            Object v = r.get(0).get("end_date");
            if (v instanceof java.sql.Date d)  return d.toLocalDate();
            if (v instanceof LocalDate ld)     return ld;
            return LocalDate.parse(v.toString().substring(0, 10));
        } catch (Exception e) {
            log.warn("DemoAccessService: could not read expiry for {} — {}", clientId, e.getMessage());
            return null;
        }
    }
}
