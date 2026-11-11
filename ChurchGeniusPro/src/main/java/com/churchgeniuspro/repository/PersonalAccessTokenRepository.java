package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PersonalAccessToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Personal access tokens for the MCP endpoint (AI Phase 5). Every finder but the hash lookup is tenant-scoped. */
public interface PersonalAccessTokenRepository extends JpaRepository<PersonalAccessToken, Long> {

    /** The hash is unique and unguessable (SHA-256 of 256 random bits) — the row carries its own tenant. */
    Optional<PersonalAccessToken> findByTokenHash(String tokenHash);

    List<PersonalAccessToken> findByAppClientIdOrderByCreatedAtDesc(String appClientId);

    List<PersonalAccessToken> findByAppClientIdAndAppUserIdOrderByCreatedAtDesc(String appClientId, Integer appUserId);

    Optional<PersonalAccessToken> findByIdAndAppClientId(Long id, String appClientId);

    long countByAppClientIdAndAppUserIdAndRevokedAtIsNullAndExpiresAtAfter(String appClientId, Integer appUserId, LocalDateTime now);

    @Modifying
    @Query("UPDATE PersonalAccessToken t SET t.revokedAt = :now, t.revokedBy = :by WHERE t.appClientId = :cid AND t.revokedAt IS NULL")
    int revokeAll(@Param("cid") String appClientId, @Param("by") String by, @Param("now") LocalDateTime now);

    /** Every live token of one login (by its username), whatever church — used when that login's password changes. */
    @Modifying
    @Query("UPDATE PersonalAccessToken t SET t.revokedAt = :now, t.revokedBy = :by WHERE LOWER(t.username) = LOWER(:username) AND t.revokedAt IS NULL")
    int revokeAllForUsername(@Param("username") String username, @Param("by") String by, @Param("now") LocalDateTime now);
}
