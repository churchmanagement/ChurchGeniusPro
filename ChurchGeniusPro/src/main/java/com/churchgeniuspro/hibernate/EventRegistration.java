package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

import java.util.Date;

/**
 * Hibernate entity for the {@code event_registration} table.
 *
 * <p>Stores one registration record per attendee for a {@link ChurchEvent}.
 * Registrations are hard-deleted (no soft-delete flag).
 */
@Data
@Entity
@Table(name = "event_registration")
public class EventRegistration {

    // ── Primary Key ───────────────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "event_registration_seq")
    @SequenceGenerator(
            name           = "event_registration_seq",
            sequenceName   = "event_registration_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    // ── Parent reference ──────────────────────────────────────────────────

    @Column(name = "event_id", nullable = false)
    private Integer eventId;

    // Tenant of the parent event, copied from the event at registration time so
    // registrations can be filtered, reported on, and isolated per client in a
    // multi-tenant setup. Sourced from ChurchEvent.appClientId.
    @Column(name = "client_id")
    private String clientId;

    // ── Registrant details ────────────────────────────────────────────────

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "email")
    private String email;

    @Column(name = "phone")
    private String phone;

    @Column(name = "adults")
    private Integer adults;

    @Column(name = "kids")
    private Integer kids;

    /** {@code true} = attending, {@code false} = not attending. */
    @Column(name = "attending")
    private Boolean attending;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    /** {@code true} = vegetarian, {@code false/null} = non-vegetarian (when food is available). */
    @Column(name = "vegetarian")
    private Boolean vegetarian;

    /**
     * JSON array of selected food item names from the event's food menu
     * (e.g. "[\"Pasta\",\"Chicken\"]"). Replaces the single vegetarian flag
     * when the event defines specific food options.
     */
    @Column(name = "selected_food_items", columnDefinition = "TEXT")
    private String selectedFoodItems;

    /** JSON array of day-order numbers the registrant plans to attend (Multiple Days events). */
    @Column(name = "attending_days", columnDefinition = "TEXT")
    private String attendingDays;

    // ── Walk-in address fields ────────────────────────────────────────────

    @Column(name = "address1")
    private String address1;

    @Column(name = "address2")
    private String address2;

    @Column(name = "city")
    private String city;

    @Column(name = "state")
    private String state;

    @Column(name = "zip_code")
    private String zipCode;

    /** True when this registration was created at the door (walk-in), not pre-registered. */
    @Column(name = "walk_in", nullable = false, columnDefinition = "boolean not null default false")
    private boolean walkIn;

    // ── QR / Check-in ─────────────────────────────────────────────────────

    /**
     * Unique token used to identify this registration in QR codes and
     * self check-in links.  Format: {@code REG<uuid>}.
     * Auto-generated on insert; never changed.
     */
    @Column(name = "registration_code", unique = true)
    private String registrationCode;

    /** {@code true} once the registrant has checked in at the event. */
    @Column(name = "checked_in", nullable = false, columnDefinition = "boolean not null default false")
    private boolean checkedIn;

    /** Timestamp when the registrant checked in, or {@code null} if not yet. */
    @Column(name = "checked_in_at")
    private Date checkedInAt;

    // ── Audit ─────────────────────────────────────────────────────────────

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @PrePersist
    protected void onCreate() {
        this.createdDate = new Date();
        this.checkedIn   = false;
        if (this.registrationCode == null) {
            this.registrationCode = "REG" + java.util.UUID.randomUUID().toString();
        }
    }
}
