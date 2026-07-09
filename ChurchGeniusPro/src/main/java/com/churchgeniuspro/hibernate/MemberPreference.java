package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.util.Date;

/**
 * Stores per-member portal preferences.
 *
 * <p>Keyed on {@code member_id} (FK to {@code family_member.id}).
 * One row per member. Hibernate {@code ddl-auto=update} creates the table automatically.
 */
@Data
@Entity
@Table(name = "member_preference")
public class MemberPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "member_preference_seq")
    @SequenceGenerator(
            name           = "member_preference_seq",
            sequenceName   = "member_preference_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    /** FK to {@code family_member.id}. One preference row per member. */
    @Column(name = "member_id", unique = true, nullable = false)
    private Integer memberId;

    /**
     * When {@code true} the member's portal session will not expire due to
     * inactivity.  The Logout button still works normally.
     * Defaults to {@code true} (no auto-logout) for all new members.
     */
    @Column(name = "no_auto_logout", nullable = false)
    private boolean noAutoLogout = true;

    @Column(name = "created_date", updatable = false)
    private Date createdDate;

    @Column(name = "updated_date")
    private Date updatedDate;

    @PrePersist
    protected void onCreate() {
        this.createdDate = new Date();
        this.updatedDate = new Date();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedDate = new Date();
    }
}
