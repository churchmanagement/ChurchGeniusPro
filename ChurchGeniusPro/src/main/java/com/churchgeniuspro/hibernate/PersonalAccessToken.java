package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * A personal access token for the external MCP endpoint (AI Phase 5, 2026-11-05).
 *
 * <p>One row per token. The secret itself is never stored — only its SHA-256 hex
 * ({@link #tokenHash}) and the first characters ({@link #tokenPrefix}) so the owner
 * can recognise it in the list. A token belongs to ONE staff login of ONE church
 * ({@link #appUserId} / {@link #appClientId}); a request made with it gets exactly
 * that person's permissions, narrowed by {@link #scopes}.
 */
@Entity
@Table(name = "personal_access_token",
       indexes = {
           @Index(name = "idx_pat_hash", columnList = "token_hash", unique = true),
           @Index(name = "idx_pat_client_user", columnList = "app_client_id, app_user_id")
       })
@Getter @Setter
@ToString(exclude = "tokenHash")
public class PersonalAccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_client_id", nullable = false, length = 100)
    private String appClientId;

    @Column(name = "app_user_id", nullable = false)
    private Integer appUserId;

    @Column(name = "username", nullable = false, length = 150)
    private String username;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "token_prefix", nullable = false, length = 16)
    private String tokenPrefix;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    /** Comma-separated scope names (see {@code PersonalAccessTokenService.SCOPES}). */
    @Column(name = "scopes", nullable = false, length = 200)
    private String scopes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_by", length = 150)
    private String revokedBy;

    public boolean isRevoked() { return revokedAt != null; }
    public boolean isExpired(LocalDateTime now) { return expiresAt == null || !expiresAt.isAfter(now); }
    public boolean isLive(LocalDateTime now) { return !isRevoked() && !isExpired(now); }
}
