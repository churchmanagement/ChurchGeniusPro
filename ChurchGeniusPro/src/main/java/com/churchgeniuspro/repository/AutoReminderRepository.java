package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.AutoReminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AutoReminderRepository extends JpaRepository<AutoReminder, Integer> {

    /** List all reminders for an org, newest first. */
    List<AutoReminder> findByAppClientIdOrderByCreatedDateDesc(String appClientId);

    /** All active (non-disabled) reminders for an org — used by the CRUD API list. */
    List<AutoReminder> findByAppClientIdAndDisabledFalse(String appClientId);

    /** All enabled reminders with the given name across all orgs (legacy). */
    List<AutoReminder> findByNameAndDisabledFalse(String name);

    /**
     * All enabled reminders of a specific type across ALL organizations.
     * Used by the scheduler to fan out across all orgs.
     */
    List<AutoReminder> findByReminderTypeIdAndDisabledFalse(Integer reminderTypeId);

    /**
     * Returns the set of organization client-IDs that have a valid, active
     * subscription — i.e. clients whose emails should actually be sent.
     *
     * <p>A client is considered valid when every layer of its account is healthy:
     * <ul>
     *   <li>The signup record is active, not deleted, and not locked.</li>
     *   <li>The corresponding {@code app_user} row is enabled and not deleted.</li>
     *   <li>The {@code church_registration} row is not deleted.</li>
     *   <li>The {@code service_client} subscription is Active, not expired, and not deleted.</li>
     * </ul>
     *
     * <p>Returns {@code usr.client_id} — this is the organization-level client ID
     * that is stored in {@code auto_reminder.app_client_id}.
     */
    @Query(value = """
            SELECT DISTINCT usr.client_id
            FROM   signup            sign
            JOIN   app_user          usr      ON sign.client_id        = usr.user_id
            JOIN   church_registration churchreg ON usr.client_id      = churchreg.client_id
            JOIN   service_client    sc       ON usr.client_id         = sc.client_id
            WHERE  sign.church      = false
              AND  sign.deleted     = false
              AND  sign.locked      = false
              AND  sign.active      = true
              AND  usr.delete_flag  = false
              AND  usr.enabled      = true
              AND  churchreg.delete_flag = false
              AND  sc.delete_flag   = false
              AND  sc.end_date      > CURRENT_DATE
              AND  sc.status        = 'Active'
            """, nativeQuery = true)
    List<String> findActiveClientIds();

    // ── Tenant-scoped lookups (security audit, week 1) ─────────────────────

    java.util.Optional<AutoReminder> findByIdAndAppClientId(Integer id, String appClientId);
}
