package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.RememberMeToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RememberMeTokenRepository extends JpaRepository<RememberMeToken, Long> {

    Optional<RememberMeToken> findByTokenHash(String tokenHash);

    Optional<RememberMeToken> findByPrevTokenHash(String prevTokenHash);

    /** Live rows of one login, oldest first (for the per-account cap). */
    @Query("SELECT t FROM RememberMeToken t WHERE t.signupId = :signupId AND t.revokedAt IS NULL "
         + "AND t.expiresAt > :now ORDER BY t.createdAt ASC")
    List<RememberMeToken> findActiveBySignupId(@Param("signupId") Integer signupId, @Param("now") Instant now);

    @Transactional
    @Modifying
    @Query("UPDATE RememberMeToken t SET t.revokedAt = :now WHERE t.signupId = :signupId AND t.revokedAt IS NULL")
    int revokeAllBySignupId(@Param("signupId") Integer signupId, @Param("now") Instant now);

    /** Housekeeping: rows that can never authenticate again and are old enough to forget. */
    @Transactional
    @Modifying
    @Query("DELETE FROM RememberMeToken t WHERE (t.revokedAt IS NOT NULL AND t.revokedAt < :before) "
         + "OR t.expiresAt < :before")
    int deleteDeadBefore(@Param("before") Instant before);
}
