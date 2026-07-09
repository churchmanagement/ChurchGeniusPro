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

import java.time.LocalDate;
import java.util.Date;

/**
 * Hibernate entity for the {@code church_event} table.
 *
 * <p>Stores church events that can span one day or multiple days.
 * For One Day events, {@code eventDate}, {@code startTime}, and {@code endTime}
 * are stored directly.  For Multiple Days events, day details are stored in
 * child {@link ChurchEventDay} records.
 *
 * <p>Records are never physically removed — {@code deleteFlag} is used instead.
 */
@Data
@Entity
@Table(name = "church_event")
public class ChurchEvent {

    // ── Primary Key ───────────────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "church_event_seq")
    @SequenceGenerator(
            name           = "church_event_seq",
            sequenceName   = "church_event_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    // ── Core fields ───────────────────────────────────────────────────────

    @Column(name = "event_name", nullable = false)
    private String eventName;

    /** Unique short code for the event (e.g. "EVT-A3B7C2"). */
    @Column(name = "event_code", unique = true)
    private String eventCode;

    /** "One Day" or "Multiple Days" */
    @Column(name = "event_type", length = 20)
    private String eventType;

    // ── One-Day schedule (null for Multiple Days) ─────────────────────────

    @Column(name = "event_date")
    private LocalDate eventDate;

    @Column(name = "start_time", length = 5)
    private String startTime;

    @Column(name = "end_time", length = 5)
    private String endTime;

    // ── Registration ──────────────────────────────────────────────────────

    @Column(name = "registration_end_date")
    private LocalDate registrationEndDate;

    @Column(name = "registration_link")
    private String registrationLink;

    /**
     * When {@code true}, the "Registration Reminder" feature is active for this
     * event: the scheduled reminder job sends a register-before-the-deadline
     * email/SMS to each configured {@link EventRegistrationReminderContact}
     * that has not yet registered. When {@code false} the feature is ignored
     * entirely. columnDefinition carries a DB default so ddl-auto=update can
     * ADD this NOT NULL column to an already-populated church_event table.
     */
    @Column(name = "registration_reminder_enabled", nullable = false,
            columnDefinition = "boolean not null default false")
    private boolean registrationReminderEnabled;

    /**
     * Number of days before the event date on which the registration reminder
     * fires (e.g. 5 = five days before). Only relevant when
     * {@link #registrationReminderEnabled} is {@code true}.
     */
    @Column(name = "registration_reminder_days")
    private Integer registrationReminderDays;

    @Column(name = "fee", length = 50)
    private String fee;

    @Column(name = "max_capacity")
    private Integer maxCapacity;

    /**
     * Whether to show the "Who else is attending?" registrants list
     * on the public event registration page.  Defaults to {@code true}.
     */
    @Column(name = "show_registrants", nullable = false)
    private boolean showRegistrants;

    /**
     * When {@code true}, the "Maybe" RSVP option is available on the public
     * event registration page in addition to "Yes" and "No".
     */
    @Column(name = "allow_maybe_rsvp", nullable = false)
    private boolean allowMaybeRsvp;

    /**
     * When {@code true}, a unique registration code and QR code are generated
     * for each registrant after they submit their RSVP.
     */
    @Column(name = "generate_qr_code", nullable = false, columnDefinition = "boolean not null default false")
    private boolean generateQrCode;

    /**
     * When {@code true}, registrants can check themselves in on mobile by
     * visiting their event link and clicking the "I'm Here" button.
     */
    @Column(name = "self_checkin_enabled", nullable = false, columnDefinition = "boolean not null default false")
    private boolean selfCheckinEnabled;

    /**
     * When {@code true}, a short human-readable Registration ID (derived from each
     * registration's token) is shown and printable on the check-in label/ticket.
     * Combined with {@link #generateQrCode} to drive what the mini-printer label
     * includes. columnDefinition carries a DB default so ddl-auto=update can ADD this
     * NOT NULL column to an already-populated church_event table.
     */
    @Column(name = "generate_registration_id", nullable = false, columnDefinition = "boolean not null default false")
    private boolean generateRegistrationId;

    // ── Location ──────────────────────────────────────────────────────────

    @Column(name = "address1") private String address1;
    @Column(name = "address2") private String address2;
    @Column(name = "city")     private String city;
    @Column(name = "state")    private String state;
    @Column(name = "country")  private String country;
    @Column(name = "pin_code") private String pinCode;

    // ── Host contact ──────────────────────────────────────────────────────

    @Column(name = "host_name")  private String hostName;
    @Column(name = "host_phone") private String hostPhone;
    @Column(name = "host_email") private String hostEmail;

    @Column(name = "host_note", columnDefinition = "TEXT")
    private String hostNote;

    // ── Details ───────────────────────────────────────────────────────────

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    /** Whether food is available at this event. */
    @Column(name = "food_available", nullable = false)
    private boolean foodAvailable;

    /**
     * JSON array of food item names available at this event (e.g. ["Pasta","Chicken"]).
     * Only relevant when {@code foodAvailable} is {@code true}.
     */
    @Column(name = "food_items", columnDefinition = "TEXT")
    private String foodItems;

    /** Whether accommodation is available for this event. */
    @Column(name = "accommodation_available", nullable = false)
    private boolean accommodationAvailable;

    @Column(name = "accommodation_address")
    private String accommodationAddress;

    @Column(name = "accommodation_comments", columnDefinition = "TEXT")
    private String accommodationComments;

    /** Base64-encoded image data (data URI), nullable. */
    @Column(name = "image_data", columnDefinition = "TEXT")
    private String imageData;

    // ── Org scoping ───────────────────────────────────────────────────────

    @Column(name = "app_client_id")
    private String appClientId;

    // ── Soft-Delete ───────────────────────────────────────────────────────

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    // ── Audit ─────────────────────────────────────────────────────────────

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    /** Username (email) of the staff member who created or last saved this event. */
    @Column(name = "created_by", length = 255)
    private String createdBy;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @PrePersist
    protected void onCreate() {
        this.deleteFlag      = false;
        this.showRegistrants = true;
        this.createdDate     = new Date();
    }
}
