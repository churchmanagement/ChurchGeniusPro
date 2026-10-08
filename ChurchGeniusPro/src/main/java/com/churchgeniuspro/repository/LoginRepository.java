package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SignUp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface LoginRepository extends JpaRepository<SignUp, Integer> {

    /**
     * Returns {@code true} if a signup record already exists for the given username,
     * matched CASE-INSENSITIVELY. This blocks creating usernames that differ only by
     * letter case (e.g. both "Anson" and "anson").
     */
    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END " +
           "FROM SignUp s WHERE UPPER(s.username) = UPPER(:username)")
    boolean existsByUsername(@Param("username") String username);

    /**
     * Number of member portals (member logins) created under a church —
     * used to enforce the subscription plan's max-member-portals limit.
     */
    long countByChurchIdAndChurchFalseAndDeletedFalse(Integer churchId);

    /**
     * Find an active, non-deleted signup record by username — used for login validation.
     * Matched CASE-INSENSITIVELY so "Anson"/"anson"/"ANSON" all resolve to the same account.
     */
    @Query("SELECT s FROM SignUp s WHERE UPPER(s.username) = UPPER(:username) AND s.deleted = false")
    java.util.Optional<SignUp> findByUsernameAndDeletedFalse(@Param("username") String username);

    /**
     * Same intent as {@link #findByUsernameAndDeletedFalse} but also matches rows
     * where the {@code deleted} column is {@code NULL} (treats NULL as "not deleted").
     * Use this whenever the account may have been created before the {@code deleted}
     * column was back-filled to {@code false}.
     */
    @Query("SELECT s FROM SignUp s WHERE UPPER(s.username) = UPPER(:username) AND (s.deleted = false OR s.deleted IS NULL)")
    java.util.Optional<SignUp> findActiveByUsername(@Param("username") String username);

    /** Find a signup record by the organization client-ID. */
    java.util.Optional<SignUp> findByClientId(String clientId);

    /**
     * Every signup row sharing a client-ID, including deleted ones.
     *
     * <p>The {@code Optional} finder above assumes at most one row per client-ID.
     * Callers that need to TEST that assumption before creating another row must
     * use this one, or the check itself would throw the exception it is meant to
     * prevent. Written as explicit JPQL rather than a derived name so a parsing
     * mistake fails the build instead of the application context at startup.
     */
    @Query("SELECT s FROM SignUp s WHERE s.clientId = :clientId")
    java.util.List<SignUp> findAllByClientId(@Param("clientId") String clientId);

    /** Look up a signup record by its remember-me token (stored in {@code signup.remember}). */
    @Query("SELECT s FROM SignUp s WHERE s.remember = :token AND (s.deleted = false OR s.deleted IS NULL)")
    java.util.Optional<SignUp> findByRememberToken(@Param("token") String token);

    /**
     * Find an active, non-deleted signup for a non-church user by their {@code user_id}.
     * For non-church users, {@code signup.client_id} equals {@code app_user.user_id}.
     * Used to verify that an app_user has created login credentials.
     */
    @Query("SELECT s FROM SignUp s WHERE s.clientId = :userId " +
           "AND s.active = true AND (s.deleted = false OR s.deleted IS NULL) AND (s.church = false OR s.church IS NULL)")
    java.util.Optional<SignUp> findActiveSignupByUserId(@Param("userId") String userId);

    /**
     * Batch version of {@link #findActiveSignupByUserId} — fetches active non-church
     * signups for multiple user IDs in a single query.
     * Returns only the matching rows; callers should build a {@code Set} of
     * {@code clientId} values for O(1) membership tests.
     */
    @Query("SELECT s FROM SignUp s WHERE s.clientId IN :userIds " +
           "AND s.active = true AND (s.deleted = false OR s.deleted IS NULL) AND (s.church = false OR s.church IS NULL)")
    java.util.List<SignUp> findActiveSignupsByUserIds(@Param("userIds") java.util.List<String> userIds);

    /**
     * Validates that a church-type login is fully authorized:
     * signup → service_client (via client_id, direct join).
     *
     * <p>For church accounts, signup.client_id IS the organization's client_id,
     * so we join directly to service_client without going through app_user.
     * The app_user row is created on first successful login (chicken-and-egg),
     * so including it in this pre-login check would permanently block new churches.
     *
     * <p>Checks: active subscription, non-expired end date, account not locked/deleted/inactive.
     */
    @Query(value = """
            SELECT COUNT(*) FROM signup sign
            INNER JOIN service_client sc ON sign.client_id = sc.client_id
            WHERE sign.church      = true
              AND sign.deleted     = false
              AND sign.locked      = false
              AND sign.active      = true
              AND LOWER(sign.username) = LOWER(:username)
              AND sc.delete_flag   = false
              AND sc.end_date      > :today
              AND sc.status        = 'Active'
            """, nativeQuery = true)
    int countValidChurchLoginOn(@Param("username") String username, @Param("today") java.time.LocalDate today);

    /**
     * {@link #countValidChurchLoginOn} for today's America/Chicago date. Passing the
     * date (rather than the database's {@code current_date}) keeps sign-in in step with
     * the trial dates and the per-request status check whatever zone the DB runs in.
     */
    default int countValidChurchLogin(String username) {
        return countValidChurchLoginOn(username, com.churchgeniuspro.util.AppClock.today());
    }

    /** Find all active, non-deleted signups sharing the given link_group. */
    @Query("SELECT s FROM SignUp s WHERE s.linkGroup = :linkGroup AND (s.deleted = false OR s.deleted IS NULL)")
    java.util.List<SignUp> findByLinkGroup(@Param("linkGroup") String linkGroup);

    /**
     * Find an active non-deleted member signup by their memberRef (client_id starting with MBR).
     * Used to look up the signup record for a member during cross-type account linking.
     */
    @Query("SELECT s FROM SignUp s WHERE s.clientId = :memberRef " +
           "AND s.active = true AND (s.deleted = false OR s.deleted IS NULL)")
    java.util.Optional<SignUp> findActiveSignupByMemberRef(@Param("memberRef") String memberRef);

    /**
     * Validates that a non-church (member/staff) login is fully authorized:
     * signup → app_user (via user_id) → church_registration (via client_id)
     * → service_client (via client_id).
     * Checks active subscription, non-expired end date, account not locked/deleted/inactive.
     */
    @Query(value = """
            SELECT COUNT(*) FROM signup sign
            INNER JOIN app_user usr           ON sign.client_id    = usr.user_id
            INNER JOIN church_registration cr ON usr.client_id     = cr.client_id
            INNER JOIN service_client sc      ON usr.client_id     = sc.client_id
            WHERE sign.church      = false
              AND sign.deleted     = false
              AND sign.locked      = false
              AND sign.active      = true
              AND LOWER(sign.username) = LOWER(:username)
              AND usr.delete_flag  = false
              AND usr.enabled      = true
              AND cr.delete_flag   = false
              AND sc.delete_flag   = false
              AND sc.end_date      > :today
              AND sc.status        = 'Active'
            """, nativeQuery = true)
    int countValidNonChurchLoginOn(@Param("username") String username, @Param("today") java.time.LocalDate today);

    /** {@link #countValidNonChurchLoginOn} for today's America/Chicago date. */
    default int countValidNonChurchLogin(String username) {
        return countValidNonChurchLoginOn(username, com.churchgeniuspro.util.AppClock.today());
    }
}
