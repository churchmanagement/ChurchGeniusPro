package com.churchgeniuspro.hibernate;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

import java.util.Date;

/**
 * Hibernate entity for the {@code church_registration} table.
 *
 * <p>Persists the data submitted via {@code POST /api/churchregistration}
 * from {@code churchregistration.html}.  Fields are mapped 1-to-1 from
 * {@link com.churchgeniuspro.model.ChurchRegistrationBO}.
 *
 * <p>{@code createdDate} is automatically set to the current timestamp on
 * first insert via the {@link #onCreate()} lifecycle callback and is never
 * updated after that point.
 */
@Data
@Entity
@Table(name = "church_registration")
public class ChurchRegistration {

    // ── Primary Key ───────────────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "church_registration_seq")
    @SequenceGenerator(
            name            = "church_registration_seq",
            sequenceName    = "church_registration_id_seq",
            allocationSize  = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    // ── Client Reference ──────────────────────────────────────────────────

    /** Client identifier assigned by the backend at sign-up time. */
    @Column(name = "client_id")
    private String clientId;

    // ── Personal Information ──────────────────────────────────────────────

    /** First name of the registrant. Mandatory. */
    @Column(name = "first_name", nullable = false)
    private String firstName;

    /** Last name of the registrant. Mandatory. */
    @Column(name = "last_name", nullable = false)
    private String lastName;

    /** Name of the church being registered. */
    @Column(name = "church_name")
    private String churchName;

    /** Contact e-mail address. Mandatory. */
    @Column(name = "email", nullable = false)
    private String email;

    /** Contact phone number. */
    @Column(name = "phone")
    private String phone;

    // ── Address (One-to-One) ──────────────────────────────────────────────

    /**
     * The church's address, persisted in the {@code address} table.
     * {@code CascadeType.ALL} ensures the {@link Address} row is inserted
     * together with this registration in a single save call.
     * The FK column {@code address_id} is stored on this table.
     */
    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "address_id", referencedColumnName = "id")
    private Address address;

    // ── Church Details ────────────────────────────────────────────────────

    /** Employer Identification Number. */
    @Column(name = "ein")
    private String ein;

    /** Indicates whether the church holds non-profit status. */
    @Column(name = "non_profit")
    private Boolean nonProfit;

    /** Free-text notes attached to this registration. */
    @Column(name = "note")
    private String note;

    // ── Website & Social Media (optional) ─────────────────────────────────
    @Column(name = "website_url")   private String websiteUrl;
    @Column(name = "facebook_url")  private String facebookUrl;
    @Column(name = "instagram_url") private String instagramUrl;
    @Column(name = "youtube_url")   private String youtubeUrl;

    // ── Declaration ───────────────────────────────────────────────────────

    /**
     * true when the registrant has accepted the Terms of Use checkbox.
     * Form submission is blocked on the frontend when this is false.
     */
    @Column(name = "declaration_check")
    private Boolean declarationCheck;

   
    // ── Soft-Delete ───────────────────────────────────────────────────────

    /**
     * Soft-delete flag. Defaults to {@code false} on insert.
     * Set to {@code true} instead of physically deleting the row.
     */
    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    /**
     * Timestamp of when this record was soft-deleted.
     * Remains {@code null} while {@link #deleteFlag} is {@code false}.
     */
    @Column(name = "deleted_date")
    private Date deletedDate;

    // ── Audit Dates ───────────────────────────────────────────────────────

    /**
     * Automatically set to the current timestamp on first insert.
     * Never updated after the initial persist.
     */
    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    // ── Lifecycle Callback ────────────────────────────────────────────────

    /**
     * Populates {@code createdDate} with the current timestamp and ensures
     * {@code deleteFlag} is always {@code false} on every new insert.
     */
    @PrePersist
    protected void onCreate() {
        this.createdDate = new Date();
        this.deleteFlag  = false;
    }
}
