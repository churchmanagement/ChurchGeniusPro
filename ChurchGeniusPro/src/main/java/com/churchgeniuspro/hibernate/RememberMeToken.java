package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * One "Remember me" credential per browser / device (Phase 1, M2 of the mobile
 * feasibility report).
 *
 * <p>Replaces the single {@code signup.remember} column, which allowed exactly one
 * remembered device per account and stored the token in clear. Here the cookie
 * holds an opaque random value and only its SHA-256 hash is stored, so a database
 * read can never reconstruct a usable cookie. Each row is independent: signing in
 * on a phone no longer signs the laptop out, logout revokes only the device that
 * logged out, and "sign out everywhere" (password reset today, a user action in a
 * later phase) revokes every row of the account.
 *
 * <p>The token is rotated every time it is used; the previous hash stays valid for
 * a short grace period so two tabs opening the login page at once do not race each
 * other out of auto-login.
 *
 * <p>{@code platform} is {@code web} for every row written today; the Capacitor
 * shells (Phase 6) will write {@code android} / {@code ios} so the device list can
 * label them. No foreign key to {@code signup}: Flyway creates this table before
 * Hibernate creates {@code signup} on a fresh database.
 */
@Data
@ToString(exclude = {"tokenHash", "prevTokenHash"})
@Entity
@Table(name = "remember_me_token",
       indexes = {
           @Index(name = "idx_remember_me_token_signup",  columnList = "signup_id"),
           @Index(name = "idx_remember_me_token_expires", columnList = "expires_at")
       })
public class RememberMeToken {

    public static final String PLATFORM_WEB = "web";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** {@code signup.id} of the login this device belongs to. */
    @Column(name = "signup_id", nullable = false, updatable = false)
    private Integer signupId;

    /** SHA-256 (hex) of the current cookie value. Unique: the lookup key. */
    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    /** SHA-256 (hex) of the value this row carried before its last rotation. */
    @Column(name = "prev_token_hash", length = 64)
    private String prevTokenHash;

    /** When {@code prevTokenHash} was superseded; it stays valid for a short grace period. */
    @Column(name = "rotated_at")
    private Instant rotatedAt;

    /** Human-readable origin, e.g. "Chrome on Windows". Never used for authorisation. */
    @Column(name = "device_label", length = 200)
    private String deviceLabel;

    @Column(name = "platform", nullable = false, length = 16)
    @ColumnDefault("'web'")
    private String platform = PLATFORM_WEB;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Set by logout (this device) or "sign out everywhere"; a revoked row never authenticates. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    public boolean isUsable(Instant now) {
        return revokedAt == null && expiresAt != null && expiresAt.isAfter(now);
    }
}
