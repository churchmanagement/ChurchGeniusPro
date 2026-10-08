package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.LoginBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Lookup of active temporary login blocks.
 *
 * <p>The hot path — "is this request currently blocked?" — is a single indexed
 * query over at most three scope keys, so an ordinary successful login pays for
 * one extra SELECT and nothing else.
 */
@Repository
public interface LoginBlockRepository extends JpaRepository<LoginBlock, Long> {

    /**
     * Active (not yet expired) blocks for any of the supplied scope keys,
     * longest-lasting first.
     */
    @Query("""
           SELECT b FROM LoginBlock b
            WHERE b.scopeKey IN :keys
              AND b.blockedUntil > :now
            ORDER BY b.blockedUntil DESC
           """)
    List<LoginBlock> findActive(@Param("keys") Collection<String> keys, @Param("now") LocalDateTime now);

    /**
     * How many blocks this key has already collected inside the escalation window.
     * Drives the progressive lengthening of repeat blocks.
     */
    @Query("SELECT COUNT(b) FROM LoginBlock b WHERE b.scopeKey = :key AND b.blockedAt >= :since")
    long countBlocksSince(@Param("key") String key, @Param("since") LocalDateTime since);

    /**
     * Ends every active block attached to a username, across all scopes.
     *
     * <p>Called after the account holder proves control of the account by completing
     * a password reset. Deliberately does <em>not</em> touch IP-scope blocks, which
     * carry no username.
     */
    @Modifying
    @Query("DELETE FROM LoginBlock b WHERE b.username = :username AND b.blockedUntil > :now")
    int releaseActiveBlocksForUsername(@Param("username") String username, @Param("now") LocalDateTime now);

    /** Retention purge — removes expired block rows older than {@code cutoff}. */
    @Modifying
    @Query("DELETE FROM LoginBlock b WHERE b.blockedUntil < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
