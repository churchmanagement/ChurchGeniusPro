package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.util.Date;

/**
 * Stores the granular permission set for a single {@link AppUser}.
 *
 * <p>Permissions are saved as a JSON text blob (a map of permission-key → boolean)
 * so that the schema never needs to change when new permissions are added.
 *
 * <p>One row per {@code app_user.id} (unique constraint on {@code app_user_id}).
 */
@Data
@Entity
@Table(name = "user_permissions")
public class UserPermissions {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "user_permissions_seq")
    @SequenceGenerator(
            name           = "user_permissions_seq",
            sequenceName   = "user_permissions_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    /** FK to {@code app_user.id}. One permissions row per user. */
    @Column(name = "app_user_id", unique = true, nullable = false)
    private Integer appUserId;

    /**
     * JSON map of permission-key → boolean, e.g.
     * {@code {"admin.family.edit":true,"admin.family.delete":false,...}}
     */
    @Column(name = "permissions", columnDefinition = "TEXT")
    private String permissions;

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
