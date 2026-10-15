package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

/**
 * Stores family membership requests submitted via the public {@code /membershipForm} page.
 * Mirrors the {@link Family} entity — name and address details are stored only on the
 * associated {@link MembershipFamilyMember} rows (primarily on the Head member).
 * Lifecycle (V15): {@link #STATUS_PENDING_REVIEW} → {@link #STATUS_APPROVED} (accepted, family
 * record untouched) or {@link #STATUS_UPDATED} ("Approve and Update": the submitted changes were
 * applied to {@code family} / {@code family_member}). {@code deleteFlag} now means a staff user
 * deleted the request; it never touches the family record.
 */
@Data
@Entity
@Table(name = "membership_family")
public class MembershipFamily {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "membership_family_seq")
    @SequenceGenerator(name = "membership_family_seq", sequenceName = "membership_family_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    /** Always stored as {@code false} — inactive flag is hidden from the public form. */
    @Column(name = "inactive", nullable = false)
    private boolean inactive = false;

    public static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_UPDATED = "UPDATED";

    /**
     * When set, this request is an update for an existing family rather than a new
     * member registration. "Approve and Update" applies the changes to that family and
     * stamps {@code family.membership_renewed_at}; plain Approve leaves it untouched.
     */
    @Column(name = "existing_family_id")
    private Integer existingFamilyId;

    /** Whether the submitter checked the mandatory declaration checkbox. */
    @Column(name = "declaration_accepted")
    private Boolean declarationAccepted;

    @Column(name = "app_client_id")
    private String appClientId;

    /**
     * Optional reference to the {@code signup.id} created alongside this form submission.
     * Set when the member creates login credentials as part of submitting this form.
     * Used in membershipRequests to offer an "Approve Signup" action (sets signup.active=true).
     */
    @Column(name = "signup_id")
    private Integer signupId;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;

    // ── V15: status + audit ──────────────────────────────────────────────

    /** PENDING_REVIEW (default) / APPROVED / UPDATED. Older rows were backfilled by V15. */
    @Column(name = "status", nullable = false, length = 24)
    @org.hibernate.annotations.ColumnDefault("'PENDING_REVIEW'")
    private String status = STATUS_PENDING_REVIEW;

    @Column(name = "status_changed_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date statusChangedAt;

    @Column(name = "status_changed_by", length = 320)
    private String statusChangedBy;

    /** The successful Approve and Update: when, by whom, and which family it went to. */
    @Column(name = "applied_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date appliedAt;

    @Column(name = "applied_by", length = 320)
    private String appliedBy;

    @Column(name = "applied_family_id")
    private Integer appliedFamilyId;

    /** Soft delete by staff. */
    @Column(name = "deleted_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date deletedAt;

    @Column(name = "deleted_by", length = 320)
    private String deletedBy;

    public boolean isPendingReview() { return STATUS_PENDING_REVIEW.equals(status); }
    public boolean isUpdated() { return STATUS_UPDATED.equals(status); }

    @Column(name = "created_date", nullable = false, updatable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date createdDate;

    @PrePersist
    protected void onCreate() {
        this.createdDate = new Date();
        this.deleteFlag  = false;
        this.inactive    = false;
        if (this.status == null) this.status = STATUS_PENDING_REVIEW;
    }
}
