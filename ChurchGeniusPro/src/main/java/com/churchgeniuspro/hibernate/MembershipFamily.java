package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

/**
 * Stores family membership requests submitted via the public {@code /membershipForm} page.
 * Mirrors the {@link Family} entity — name and address details are stored only on the
 * associated {@link MembershipFamilyMember} rows (primarily on the Head member).
 * Approved records are transferred to the {@code family} / {@code family_member} tables
 * and soft-deleted here.
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

    /**
     * When set, this request is an update for an existing family rather than a new
     * member registration.  On approval the existing family's members are updated
     * in-place and the renewal date is refreshed instead of creating a new family.
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

    @Column(name = "created_date", nullable = false, updatable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date createdDate;

    @PrePersist
    protected void onCreate() {
        this.createdDate = new Date();
        this.deleteFlag  = false;
        this.inactive    = false;
    }
}
