package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.DemoRoleAccess;
import com.churchgeniuspro.repository.DemoRoleAccessRepository;
import com.churchgeniuspro.repository.TrialRegistrationLinkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Removing trial data from the Service Admin screen, in two strengths.
 *
 * <h2>Soft delete</h2>
 * Sets the flags the application already reads, so the row leaves the screen and
 * stops working, and nothing is destroyed:
 * <ul>
 *   <li><b>an account</b> → {@code service_client.delete_flag} and
 *       {@code church_registration.delete_flag}. Both are already required to be
 *       false by the login queries and by every Service Admin listing, so a
 *       soft-deleted trial cannot be signed into and does not appear anywhere —
 *       with no new rule to keep in step.</li>
 *   <li><b>a role</b> → {@code signup.deleted} (the login queries require
 *       {@code deleted = false}), plus {@code app_user.delete_flag} for a staff
 *       role, and the login's access window is blocked so the two agree.</li>
 *   <li><b>a registration link</b> → {@code deleted_at}, a column added for this;
 *       the table had {@code revoked}, but revoking is a message to the PROSPECT
 *       and deleting is about the admin's own list.</li>
 * </ul>
 *
 * <h2>Permanent delete</h2>
 * For an account, {@link TestDataService#clearManagedTenant} — the cascade that
 * already existed for demo tenants, statement by statement, children before
 * parents, every one scoped to the single client id. For a role, the login rows
 * only. For a link, the row.
 *
 * <h2>What a role deletion does NOT remove</h2>
 * A role is a LOGIN, and deleting one deletes the login: the {@code signup} row,
 * its access window, and — for a staff role — the {@code app_user}. The person it
 * signs in as stays. A Member Portal login belongs to a family member who is part
 * of the church's sample data; removing them would quietly edit the tenant's data
 * rather than its access. Deleting the whole account removes both.
 *
 * <p>Everything here refuses anything that is not a {@code TRIAL-} tenant. Demo
 * tenants keep the Clear button they have always had, and a real church is
 * refused outright — this class never widens beyond trials.
 */
@Service
public class TrialDeletionService {

    private static final Logger log = LoggerFactory.getLogger(TrialDeletionService.class);

    /** The one role a trial account cannot lose on its own. */
    public static final String CHURCH_ROLE = TestDataService.ROLE_CHURCH;

    private final TrialRegistrationLinkRepository linkRepo;
    private final DemoRoleAccessRepository accessRepo;
    private final DemoAccessService demoAccess;
    private final TestDataService testData;
    private final JdbcTemplate jdbc;

    public TrialDeletionService(TrialRegistrationLinkRepository linkRepo,
                                DemoRoleAccessRepository accessRepo,
                                DemoAccessService demoAccess,
                                TestDataService testData,
                                JdbcTemplate jdbc) {
        this.linkRepo   = linkRepo;
        this.accessRepo = accessRepo;
        this.demoAccess = demoAccess;
        this.testData   = testData;
        this.jdbc       = jdbc;
    }

    /** What one operation did, for the response and the audit row. */
    public record Outcome(String operation, String scope, int affected, Map<String, Integer> counts) {
        static Outcome of(String op, String scope, int n) {
            return new Outcome(op, scope, n, Map.of());
        }
    }

    /* ══ Registration links ══════════════════════════════════════════════ */

    @Transactional
    public Outcome softDeleteLinks(Collection<Integer> ids, String by) {
        if (ids == null || ids.isEmpty()) return Outcome.of("soft-delete", "links", 0);
        int n = linkRepo.softDelete(ids, LocalDateTime.now(), actor(by));
        audit("soft-delete", "trial registration link(s) " + ids, n, by);
        return Outcome.of("soft-delete", "links", n);
    }

    @Transactional
    public Outcome softDeleteAllLinks(String by) {
        int n = linkRepo.softDeleteAll(LocalDateTime.now(), actor(by));
        audit("soft-delete", "every trial registration link", n, by);
        return Outcome.of("soft-delete", "links", n);
    }

    @Transactional
    public Outcome restoreLinks(Collection<Integer> ids, String by) {
        if (ids == null || ids.isEmpty()) return Outcome.of("restore", "links", 0);
        int n = linkRepo.restore(ids);
        audit("restore", "trial registration link(s) " + ids, n, by);
        return Outcome.of("restore", "links", n);
    }

    @Transactional
    public Outcome permanentDeleteLinks(Collection<Integer> ids, String by) {
        if (ids == null || ids.isEmpty()) return Outcome.of("permanent-delete", "links", 0);
        int n = linkRepo.permanentDelete(ids);
        audit("permanent-delete", "trial registration link(s) " + ids, n, by);
        return Outcome.of("permanent-delete", "links", n);
    }

    @Transactional
    public Outcome permanentDeleteAllLinks(String by) {
        int n = linkRepo.permanentDeleteAll();
        audit("permanent-delete", "every trial registration link", n, by);
        return Outcome.of("permanent-delete", "links", n);
    }

    /* ══ Trial accounts ══════════════════════════════════════════════════ */

    /**
     * Hides a whole trial account and stops every login into it.
     *
     * <p>Two flags, and no third mechanism: the login SQL already refuses a client
     * whose {@code service_client.delete_flag} is true, and every Service Admin
     * listing already filters on {@code church_registration.delete_flag}.
     */
    @Transactional
    public Outcome softDeleteAccount(String clientId, String by) {
        requireTrial(clientId);
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("service_client",
            jdbc.update("UPDATE service_client SET delete_flag = true WHERE client_id = ?", clientId));
        counts.put("church_registration",
            jdbc.update("UPDATE church_registration SET delete_flag = true WHERE client_id = ?", clientId));
        int n = counts.values().stream().mapToInt(Integer::intValue).sum();
        audit("soft-delete", "trial account " + clientId, n, by);
        return new Outcome("soft-delete", clientId, n, counts);
    }

    /** Brings a soft-deleted trial account back. */
    @Transactional
    public Outcome restoreAccount(String clientId, String by) {
        requireTrial(clientId);
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("service_client",
            jdbc.update("UPDATE service_client SET delete_flag = false WHERE client_id = ?", clientId));
        counts.put("church_registration",
            jdbc.update("UPDATE church_registration SET delete_flag = false WHERE client_id = ?", clientId));
        int n = counts.values().stream().mapToInt(Integer::intValue).sum();
        audit("restore", "trial account " + clientId, n, by);
        return new Outcome("restore", clientId, n, counts);
    }

    /**
     * Removes a trial account and everything belonging to it.
     *
     * <p>Delegates to the cascade demo deletion has always used, so there is one
     * ordered list of tables to keep correct rather than two that can drift.
     */
    @Transactional
    public Map<String, Object> permanentDeleteAccount(String clientId, String by) {
        requireTrial(clientId);
        Map<String, Object> result = testData.clearManagedTenant(clientId, by);
        Object total = result.get("total");
        audit("permanent-delete", "trial account " + clientId,
              total instanceof Number n ? n.intValue() : 0, by);
        return result;
    }

    /* ══ Individual roles ════════════════════════════════════════════════ */

    /**
     * Soft-deletes one login inside a trial account.
     *
     * @param accessId the {@code demo_role_access} row shown on the screen
     */
    @Transactional
    public Outcome softDeleteRole(Long accessId, String by) {
        DemoRoleAccess w = requireDeletableRole(accessId);
        Map<String, Integer> counts = new LinkedHashMap<>();

        counts.put("signup", jdbc.update(
            "UPDATE signup SET deleted = true, active = false WHERE id = ?", w.getSignupId()));
        // A staff login also has an app_user row; disabling it is what the rest of
        // the application reads when deciding whether that person still works here.
        counts.put("app_user", jdbc.update(
            "UPDATE app_user SET delete_flag = true, enabled = false "
          + " WHERE client_id = ? AND user_id IN (SELECT client_id FROM signup WHERE id = ?)",
            w.getClientId(), w.getSignupId()));
        // ...and block the window so the screen and the login agree about this role.
        w.setBlocked(true);
        w.setUpdatedAt(LocalDateTime.now());
        accessRepo.save(w);
        counts.put("demo_role_access", 1);

        int n = counts.values().stream().mapToInt(Integer::intValue).sum();
        audit("soft-delete", "role " + w.getRoleLabel() + " in " + w.getClientId(), n, by);
        return new Outcome("soft-delete", w.getClientId() + " / " + w.getRoleLabel(), n, counts);
    }

    /**
     * Removes one login inside a trial account outright.
     *
     * <p>The login only — see the class note on what a role deletion deliberately
     * leaves behind.
     */
    @Transactional
    public Outcome permanentDeleteRole(Long accessId, String by) {
        DemoRoleAccess w = requireDeletableRole(accessId);
        String clientId = w.getClientId();
        String role     = w.getRoleLabel();
        Map<String, Integer> counts = new LinkedHashMap<>();

        // The app_user goes first: its user_id is what the signup row is keyed by,
        // so the sub-select has to run while that signup still exists.
        counts.put("app_user", jdbc.update(
            "DELETE FROM app_user WHERE client_id = ? "
          + " AND user_id IN (SELECT client_id FROM signup WHERE id = ?)",
            clientId, w.getSignupId()));
        counts.put("signup", jdbc.update("DELETE FROM signup WHERE id = ?", w.getSignupId()));
        accessRepo.delete(w);
        counts.put("demo_role_access", 1);

        int n = counts.values().stream().mapToInt(Integer::intValue).sum();
        audit("permanent-delete", "role " + role + " in " + clientId, n, by);
        return new Outcome("permanent-delete", clientId + " / " + role, n, counts);
    }

    /** Soft-deletes several roles, reporting the total. */
    @Transactional
    public Outcome softDeleteRoles(Collection<Long> accessIds, String by) {
        return eachRole(accessIds, by, true);
    }

    /** Permanently deletes several roles, reporting the total. */
    @Transactional
    public Outcome permanentDeleteRoles(Collection<Long> accessIds, String by) {
        return eachRole(accessIds, by, false);
    }

    private Outcome eachRole(Collection<Long> accessIds, String by, boolean soft) {
        String op = soft ? "soft-delete" : "permanent-delete";
        if (accessIds == null || accessIds.isEmpty()) return Outcome.of(op, "roles", 0);
        int n = 0;
        for (Long id : accessIds) {
            // One refusal fails the whole batch, deliberately: a caller that
            // included the Church row has asked for something it may not have, and
            // half-doing it would leave them guessing which half.
            Outcome one = soft ? softDeleteRole(id, by) : permanentDeleteRole(id, by);
            n += one.affected();
        }
        return Outcome.of(op, accessIds.size() + " role(s)", n);
    }

    /* ══ Guards ══════════════════════════════════════════════════════════ */

    /** Refuses anything that is not a self-service trial tenant. */
    private void requireTrial(String clientId) {
        if (!TestDataService.isTrialTenant(clientId)) {
            throw new IllegalArgumentException(
                "This action applies to trial accounts only. '" + clientId + "' is not one.");
        }
    }

    /**
     * The window for a role that may be deleted on its own.
     *
     * <p>The Church login is refused: it IS the account, not a role within it, and
     * removing it would leave a tenant nobody can administer while every other row
     * it owns carries on existing. Deleting the whole account removes it.
     */
    private DemoRoleAccess requireDeletableRole(Long accessId) {
        DemoRoleAccess w = accessRepo.findById(accessId).orElseThrow(
            () -> new IllegalArgumentException("No such role: " + accessId));
        requireTrial(w.getClientId());
        if (CHURCH_ROLE.equalsIgnoreCase(TestDataService.canonicalDemoRole(w.getRoleLabel()))) {
            throw new IllegalArgumentException(
                "The Church login cannot be deleted on its own — it is the trial account itself. "
              + "Delete the whole account to remove it.");
        }
        if (w.getSignupId() == null) {
            throw new IllegalArgumentException("This role has no login row to delete.");
        }
        return w;
    }

    /** True when this role may be deleted individually — what the screen asks. */
    public static boolean isRoleDeletable(String roleLabel) {
        return !CHURCH_ROLE.equalsIgnoreCase(TestDataService.canonicalDemoRole(roleLabel));
    }

    private static String actor(String by) {
        return (by == null || by.isBlank()) ? "(unknown service admin)" : by;
    }

    /**
     * One clearly-labelled line per operation.
     *
     * <p>Matches the AUDIT line {@code clearManagedTenant} already writes, so a
     * search for "AUDIT trial-" finds every deletion a Service Admin performed
     * whichever strength they chose.
     */
    private void audit(String operation, String what, int affected, String by) {
        log.warn("AUDIT trial-{} | target='{}' affected={} performedBy='{}' at='{}'",
                 operation, what, affected, actor(by), LocalDateTime.now());
    }

    /** Roles in a trial account, newest tenant first — what the delete UI lists. */
    public List<DemoRoleAccess> rolesFor(String clientId) {
        requireTrial(clientId);
        return accessRepo.findByClientId(clientId);
    }
}
