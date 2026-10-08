package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.LoginAttemptLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * Counting queries behind the login rate limiter, plus the retention purge.
 *
 * <p>All counters are derived from rows in {@code login_attempt_log}, so they are
 * shared by every application instance — nothing lives in JVM memory and no
 * instance affinity is required.
 */
@Repository
public interface LoginAttemptLogRepository extends JpaRepository<LoginAttemptLog, Long> {

    /**
     * Failures for one {@code username + IP} pair since {@code since}.
     * This is the primary rate-limit signal: it throttles the attacker's own
     * machine without touching the real owner's ability to sign in elsewhere.
     */
    @Query("""
           SELECT COUNT(l) FROM LoginAttemptLog l
            WHERE l.username = :username
              AND l.ipAddress = :ip
              AND l.outcome = 'FAILURE'
              AND l.attemptedAt >= :since
           """)
    long countUserIpFailuresSince(@Param("username") String username,
                                  @Param("ip") String ip,
                                  @Param("since") LocalDateTime since);

    /** Failures from one IP address, across all usernames, since {@code since}. */
    @Query("""
           SELECT COUNT(l) FROM LoginAttemptLog l
            WHERE l.ipAddress = :ip
              AND l.outcome = 'FAILURE'
              AND l.attemptedAt >= :since
           """)
    long countIpFailuresSince(@Param("ip") String ip, @Param("since") LocalDateTime since);

    /** Failures against one username, from any IP, since {@code since}. */
    @Query("""
           SELECT COUNT(l) FROM LoginAttemptLog l
            WHERE l.username = :username
              AND l.outcome = 'FAILURE'
              AND l.attemptedAt >= :since
           """)
    long countUsernameFailuresSince(@Param("username") String username, @Param("since") LocalDateTime since);

    /**
     * Timestamp of the most recent successful authentication (or explicit counter
     * reset) for this username, from any IP.
     *
     * <p>Used as the lower bound when counting account-scoped failures, which is how
     * "a successful login resets the failure counters" is implemented without
     * deleting audit rows. It is safe against abuse because producing a {@code SUCCESS}
     * row requires the correct password for that same account — an attacker who can do
     * that has no need of the rate limiter's help. Note that this cutoff is applied to
     * the account-scoped counters only; the IP-scoped counter deliberately ignores it,
     * so someone holding one valid account cannot clear the IP budget and keep spraying.
     *
     * @return the timestamp, or {@code null} when there is no success in the retention window
     */
    @Query("""
           SELECT MAX(l.attemptedAt) FROM LoginAttemptLog l
            WHERE l.username = :username
              AND l.outcome IN ('SUCCESS', 'RESET')
           """)
    LocalDateTime lastSuccessOrResetAt(@Param("username") String username);

    /** Retention purge — removes attempt rows older than {@code cutoff}. */
    @Modifying
    @Query("DELETE FROM LoginAttemptLog l WHERE l.attemptedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
