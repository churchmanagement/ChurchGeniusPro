package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

import java.util.Date;

/**
 * Hibernate entity for the {@code subscription} table.
 *
 * <p>{@code initialSetUpId} is a Many-to-One foreign key to {@link InitialSetUp},
 * meaning one setup record can have many subscription entries.
 *
 * <p>{@code createDate} is automatically set to the current timestamp
 * on first insert via the {@link #onCreate()} lifecycle callback.
 */
@Data
@Entity
@Table(name = "subscription")
public class Subscription {

    // ── Primary Key ───────────────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "subscription_seq")
    @SequenceGenerator(name = "subscription_seq", sequenceName = "subscription_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    // ── Foreign Key → InitialSetUp (Many-to-One, Lazy) ───────────────────

    /**
     * Many subscription records can belong to one {@link InitialSetUp}.
     * Stored as the {@code initial_set_up_id} column in the {@code subscription} table.
     */
	/*
	 * @ManyToOne(fetch = FetchType.LAZY)
	 * 
	 * @JoinColumn(name = "church_set_up_id", referencedColumnName = "id") private
	 * ChurchRegistration initialSetUpId;
	 */

    // ── Subscription Details ──────────────────────────────────────────────

    @Column(name = "name")
    private String name;

    // ── Status Flags ──────────────────────────────────────────────────────

    @Column(name = "active")
    private Boolean active;

    @Column(name = "deleted")
    private Boolean deleted;

    // ── Dates ─────────────────────────────────────────────────────────────

    /**
     * Automatically set to the current timestamp on first insert.
     * Never updated after the initial persist.
     */
    @Column(name = "create_date", nullable = false, updatable = false)
    private Date createDate;

    @Column(name = "update_date")
    private Date updateDate;

    // ── Lifecycle Callback ────────────────────────────────────────────────

    /**
     * Populates {@code createDate} with the current timestamp
     * immediately before the entity is first inserted into the database.
     */
    @PrePersist
    protected void onCreate() {
        this.createDate = new Date();
    }
}
