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
 * Persisted reminder rule for a specific church event.
 * Supports "same day", "N days before", and "N days after" triggers.
 */
@Data
@Entity
@Table(name = "event_reminder")
public class EventReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "event_reminder_seq")
    @SequenceGenerator(
            name           = "event_reminder_seq",
            sequenceName   = "event_reminder_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    /** FK to {@code church_event.id}. */
    @Column(name = "event_id")
    private Integer eventId;

    /** Send reminder on the event date itself. */
    @Column(name = "same_day")
    private Boolean sameDay;

    /** Send reminder N days before the event date (null = not used). */
    @Column(name = "before_days")
    private Integer beforeDays;

    /** Send reminder N days after the event date (null = not used). */
    @Column(name = "after_days")
    private Integer afterDays;

    /**
     * Custom email template for the same-day reminder. When blank/null the
     * scheduler falls back to the built-in default body. Supports the dynamic
     * placeholders {@code {eventName}}, {@code {date}}, {@code {time}},
     * {@code {location}}, {@code {map}}, {@code {calendar}}, {@code {beforeDays}}.
     */
    @Column(name = "same_day_template", columnDefinition = "TEXT")
    private String sameDayTemplate;

    /** Custom email template for the before-days reminder (blank = default). */
    @Column(name = "before_days_template", columnDefinition = "TEXT")
    private String beforeDaysTemplate;

    /** Custom email template for the after-days reminder (blank = default). */
    @Column(name = "after_days_template", columnDefinition = "TEXT")
    private String afterDaysTemplate;

    /**
     * Comma-separated recipient groups: e.g. {@code "Members,Guests"}.
     * Possible tokens: {@code Members}, {@code Guests}.
     */
    @Column(name = "recipients")
    private String recipients;

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
