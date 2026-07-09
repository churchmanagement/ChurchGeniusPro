package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

/**
 * Hibernate entity for the {@code follow_up} table.
 *
 * <p>Tracks follow-up tasks for members, visitors, prayer requests, or donations.
 * One row per follow-up item, scoped to an organization via {@code client_id}.</p>
 *
 * <p>Status lifecycle: {@code PENDING} → {@code COMPLETED} (or auto-set to
 * {@code MISSED} by the scheduler when {@code due_date} passes without completion).</p>
 *
 * <p>Priority levels: {@code LOW}, {@code MEDIUM}, {@code HIGH}, {@code URGENT}.</p>
 *
 * <p>Linked types: {@code MEMBER}, {@code VISITOR}, {@code PRAYER_REQUEST}, {@code DONATION}.</p>
 */
@Data
@Entity
@Table(name = "follow_up")
public class FollowUp {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "follow_up_seq")
    @SequenceGenerator(
            name           = "follow_up_seq",
            sequenceName   = "follow_up_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Organization that owns this follow-up — matches {@code app_user.client_id}. */
    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** Short title / subject of the follow-up. */
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /** Detailed description or notes for the follow-up. */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Date by which the follow-up should be completed.
     * The scheduler marks overdue items as {@code MISSED}.
     */
    @Column(name = "due_date")
    @Temporal(TemporalType.DATE)
    private Date dueDate;

    /**
     * Priority level.  One of: {@code LOW}, {@code MEDIUM}, {@code HIGH}, {@code URGENT}.
     */
    @Column(name = "priority", length = 10)
    private String priority;

    /**
     * Current status.  One of: {@code PENDING}, {@code COMPLETED}, {@code MISSED}.
     * Defaults to {@code PENDING} on creation.
     */
    @Column(name = "status", length = 15)
    private String status;

    /**
     * Username or display name of the staff member assigned to this follow-up.
     * Used for role-based filtering (Users only see follow-ups assigned to them).
     */
    @Column(name = "assigned_to", length = 150)
    private String assignedTo;

    /**
     * Type of the linked record.  One of:
     * {@code MEMBER}, {@code VISITOR}, {@code PRAYER_REQUEST}, {@code DONATION}.
     * {@code null} means the follow-up is standalone (not linked to a specific record).
     */
    @Column(name = "linked_type", length = 20)
    private String linkedType;

    /**
     * ID of the linked record (family member ID, prayer request ID, etc.).
     * Only meaningful when {@code linkedType} is not null.
     */
    @Column(name = "linked_id")
    private Long linkedId;

    /**
     * Display name or label for the linked record (e.g. member name, request title).
     * Stored as a denormalized label so the list page doesn't need extra joins.
     */
    @Column(name = "linked_label", length = 255)
    private String linkedLabel;

    /** Username of the staff member who created this follow-up. */
    @Column(name = "created_by", length = 150)
    private String createdBy;

    /** Soft-delete flag — {@code true} means the record is logically deleted. */
    @Column(name = "delete_flag", nullable = false)
    private Boolean deleteFlag;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Date createdAt;

    @Column(name = "updated_at")
    private Date updatedAt;

    @PrePersist
    protected void onCreate() {
        this.status     = (this.status     != null) ? this.status     : "PENDING";
        this.priority   = (this.priority   != null) ? this.priority   : "MEDIUM";
        this.deleteFlag = false;
        this.createdAt  = new Date();
        this.updatedAt  = new Date();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = new Date();
    }
}
