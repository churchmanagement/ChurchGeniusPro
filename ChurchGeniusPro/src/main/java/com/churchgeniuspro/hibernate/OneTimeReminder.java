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
 * Persisted one-time reminder that fires on a specific calendar date.
 */
@Data
@Entity
@Table(name = "one_time_reminder")
public class OneTimeReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "one_time_reminder_seq")
    @SequenceGenerator(
            name           = "one_time_reminder_seq",
            sequenceName   = "one_time_reminder_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    /** Short descriptive name for this reminder. */
    @Column(name = "name")
    private String name;

    /** The calendar date on which this reminder is triggered. */
    @Column(name = "event_date")
    private LocalDate eventDate;

    /**
     * Comma-separated recipient groups.
     * Possible tokens: {@code Members}, {@code Guests}, {@code Visitors},
     * and {@code Group:<id>} (one per selected group, where {@code <id>} is an
     * {@code app_group} primary key).
     */
    @Column(name = "recipients")
    private String recipients;

    /** Optional image attached to the reminder email (base64 data URI). */
    @Column(name = "image_data", columnDefinition = "TEXT")
    private String imageData;

    /** When {@code true} this rule is ignored by the scheduler. */
    @Column(name = "disabled", nullable = false)
    private Boolean disabled;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    /** Send reminder via email (default on). */
    @Column(name = "send_email", columnDefinition = "boolean not null default true")
    private Boolean sendEmail;

    /** Send reminder via SMS (default off). */
    @Column(name = "send_sms", columnDefinition = "boolean not null default false")
    private Boolean sendSms;

    /** Send reminder via WhatsApp (default on). */
    @Column(name = "send_whatsapp", columnDefinition = "boolean not null default true")
    private Boolean sendWhatsApp;

    /** Org scoping — matches {@code app_user.client_id}. */
    @Column(name = "app_client_id")
    private String appClientId;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    @PrePersist
    protected void onCreate() {
        this.disabled     = false;
        this.sendEmail    = true;
        this.sendSms      = false;
        this.sendWhatsApp = true;
        this.createdDate  = new Date();
    }
}
