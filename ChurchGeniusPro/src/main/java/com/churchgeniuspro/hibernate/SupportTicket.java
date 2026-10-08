package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A support request a church raised with the ChurchGeniusPro support team
 * (Ticketing, 2026-10-01).
 *
 * <p>Tenant-scoped by {@code client_id}. Once submitted a ticket is read-only for
 * the church — there is no update or delete path for them; only a Service Admin
 * changes {@link #status} (Open ⇄ Closed). The reference number
 * ({@code TKT-XXXXXX}) is what both sides quote.
 */
@Data
@Entity
@Table(name = "support_ticket",
       uniqueConstraints = @UniqueConstraint(name = "uq_support_ticket_ref", columnNames = {"reference"}),
       indexes = {
           @Index(name = "ix_support_ticket_client", columnList = "client_id, created_at"),
           @Index(name = "ix_support_ticket_status", columnList = "status, created_at")
       })
public class SupportTicket {

    public static final String STATUS_OPEN   = "Open";
    public static final String STATUS_CLOSED = "Closed";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Public reference, e.g. {@code TKT-4K7Q2M}. */
    @Column(name = "reference", nullable = false, updatable = false, length = 20)
    private String reference;

    @Column(name = "client_id", nullable = false, updatable = false, length = 100)
    private String clientId;

    /** Church name at submission time (denormalised for the Service Admin list). */
    @Column(name = "church_name", length = 255)
    private String churchName;

    @Column(name = "subject", nullable = false, length = 200)
    private String subject;

    @Column(name = "submitter_name", nullable = false, length = 200)
    private String submitterName;

    @Column(name = "submitter_email", nullable = false, length = 320)
    private String submitterEmail;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    /** Low | Medium | High. */
    @Column(name = "urgency", nullable = false, length = 10)
    private String urgency;

    /** Open | Closed. */
    @Column(name = "status", nullable = false, length = 10)
    private String status = STATUS_OPEN;

    /** Plan at submission time: Trial / Free / Standard / Pro (or the plan's own name). */
    @Column(name = "client_package", length = 60)
    private String clientPackage;

    /** Signed-in username (or member portal login) that submitted it. */
    @Column(name = "submitted_by", length = 200)
    private String submittedBy;

    /** SuperAdmin / Admin / Accountant / User / Member / church. */
    @Column(name = "submitted_role", length = 30)
    private String submittedRole;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** When a Service Admin last changed the status. */
    @Column(name = "status_changed_at")
    private LocalDateTime statusChangedAt;

    @Column(name = "status_changed_by", length = 200)
    private String statusChangedBy;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (status == null || status.isBlank()) status = STATUS_OPEN;
    }

    @PreUpdate
    public void onUpdate() { updatedAt = LocalDateTime.now(); }
}
