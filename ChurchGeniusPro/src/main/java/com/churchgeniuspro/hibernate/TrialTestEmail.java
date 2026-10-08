package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * The one verified test address a Trial/Demo tenant's congregation mail is
 * redirected to (Phase B). One row per tenant.
 *
 * <p>{@code verifiedEmail} is the only address test mail ever goes to; it is set
 * solely by a successful code verification. A change request parks the new
 * address in {@code pendingEmail} with a hashed, expiring code, and the verified
 * address is left alone until that code is confirmed. Codes are stored as a
 * SHA-256 hash and never logged.
 */
@Data
@Entity
@Table(name = "trial_test_email",
       uniqueConstraints = @UniqueConstraint(name = "uq_trial_test_email_client", columnNames = {"client_id"}))
public class TrialTestEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "verified_email", length = 320)
    private String verifiedEmail;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "verified_by", length = 320)
    private String verifiedBy;

    @Column(name = "pending_email", length = 320)
    private String pendingEmail;

    /** SHA-256 of {@code clientId + ":" + code}, hex. Null when nothing is pending. */
    @Column(name = "pending_code_hash", length = 64)
    private String pendingCodeHash;

    @Column(name = "pending_expires_at")
    private LocalDateTime pendingExpiresAt;

    @Column(name = "pending_requested_at")
    private LocalDateTime pendingRequestedAt;

    @Column(name = "pending_attempts", nullable = false)
    @org.hibernate.annotations.ColumnDefault("0")
    private int pendingAttempts;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 320)
    private String updatedBy;
}
