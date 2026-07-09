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
 * Persisted auto-reminder rule for recurring calendar events.
 *
 * <p>The {@link #name} field is one of:
 * Birthday, Wedding Anniversary, Birthdays &amp; Anniversary,
 * Monthly Statement, New Year, Christmas, US Independence,
 * Thanksgiving, Veterans Day, Presidents' Day, Memorial Day, Labor Day.
 */
@Data
@Entity
@Table(name = "auto_reminder")
public class AutoReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "auto_reminder_seq")
    @SequenceGenerator(
            name           = "auto_reminder_seq",
            sequenceName   = "auto_reminder_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    /**
     * FK to {@code auto_reminder_types.id}.
     * Determines which scheduler logic runs for this record.
     */
    @Column(name = "reminder_type_id")
    private Integer reminderTypeId;

    /** Human-readable reminder name — kept for display; mirrors the type name. */
    @Column(name = "name")
    private String name;

    /**
     * Comma-separated recipient groups.
     * Possible tokens: {@code Members}, {@code Guests}, {@code Visitors},
     * {@code Celebrant}, and {@code Group:<id>} (one per selected group, where
     * {@code <id>} is an {@code app_group} primary key).
     * {@code Celebrant} applies only for birthday/anniversary reminders.
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

    /**
     * Custom email template body for holiday reminders. When blank/null the
     * scheduler falls back to the built-in default greeting. Supports the
     * placeholders {@code {date}} (the occasion's date) and {@code {occasion}}.
     */
    @Column(name = "email_template", columnDefinition = "TEXT")
    private String emailTemplate;

    /**
     * Custom SMS/text message for holiday reminders (blank = default message).
     * Kept short for single-segment SMS delivery; supports {@code {date}} and
     * {@code {occasion}} placeholders.
     */
    @Column(name = "sms_template", length = 480)
    private String smsTemplate;

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
