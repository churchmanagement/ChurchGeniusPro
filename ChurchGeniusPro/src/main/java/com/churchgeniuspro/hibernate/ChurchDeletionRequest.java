package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * App Store readiness D7 (2026-11-06): a church's request to delete its whole account.
 *
 * <p>Flow: REQUESTED (SuperAdmin or the church login, password + church name) → VERIFIED (the
 * link e-mailed to the church's registered address was confirmed) → DELETED (a Service Admin ran
 * the deletion after reviewing the dry-run counts), or CANCELLED / EXPIRED. Nothing is deleted
 * automatically.
 *
 * <p>The church is stored in {@code church_client_id} — deliberately NOT {@code client_id} /
 * {@code app_client_id}, so the tenant purge never deletes the record of its own deletion
 * (same reason as {@code demo_deletion_audit}). The verification token is stored as a SHA-256
 * hash only.
 */
@Data
@Entity
@Table(name = "church_deletion_request", indexes = {
        @Index(name = "idx_cdr_church", columnList = "church_client_id, status"),
        @Index(name = "idx_cdr_token", columnList = "token_hash")
})
public class ChurchDeletionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "church_client_id", nullable = false, length = 100)
    private String churchClientId;

    @Column(name = "church_name", length = 255)
    private String churchName;

    /** REQUESTED · VERIFIED · DELETED · CANCELLED · EXPIRED */
    @Column(name = "status", nullable = false, length = 12)
    private String status;

    @Column(name = "requested_by", length = 255)
    private String requestedBy;

    @Column(name = "requested_role", length = 40)
    private String requestedRole;

    /** The church's registered e-mail at request time — where every step is confirmed. */
    @Column(name = "notify_email", length = 255)
    private String notifyEmail;

    @Column(name = "token_hash", length = 64)
    private String tokenHash;

    @Column(name = "token_expires_at")
    private LocalDateTime tokenExpiresAt;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "completed_by", length = 255)
    private String completedBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancelled_by", length = 255)
    private String cancelledBy;

    /** Total rows removed by the purge (counts per table go to the AUDIT log line). */
    @Column(name = "deleted_rows")
    private Integer deletedRows;
}
