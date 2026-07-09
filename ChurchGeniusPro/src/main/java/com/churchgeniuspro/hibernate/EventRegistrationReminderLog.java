package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

import java.util.Date;

/**
 * Audit + deduplication log for event Registration Reminder attempts.
 *
 * <p>One row is written for every (contact, channel) evaluated by the
 * scheduled reminder job, whether the message was sent or skipped:
 * <ul>
 *   <li>{@code status = SENT}    — message dispatched to the provider.</li>
 *   <li>{@code status = SKIPPED} — nothing sent; {@code reason} explains why
 *       (already registered, duplicate reminder, invalid email/phone, …).</li>
 *   <li>{@code status = FAILED}  — provider not configured / send error.</li>
 * </ul>
 *
 * <p>SENT rows double as the duplicate-prevention record: before sending, the
 * scheduler checks for an existing SENT row with the same event, channel, and
 * normalized recipient so the same person is never reminded twice for the same
 * event.
 */
@Data
@Entity
@Table(name = "event_registration_reminder_log",
       indexes = {
           @Index(name = "idx_errl_event",       columnList = "event_id"),
           @Index(name = "idx_errl_event_recip", columnList = "event_id,channel,recipient_norm")
       })
public class EventRegistrationReminderLog {

    /** Delivery channel of this attempt. */
    public static final String CHANNEL_EMAIL = "EMAIL";
    public static final String CHANNEL_SMS   = "SMS";

    /** Kind of message: register-before-deadline reminder, or event invitation. */
    public static final String TYPE_REMINDER = "REMINDER";
    public static final String TYPE_INVITE   = "INVITE";

    /** Outcome of this attempt. */
    public static final String STATUS_SENT    = "SENT";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_FAILED  = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "err_log_seq")
    @SequenceGenerator(
            name           = "err_log_seq",
            sequenceName   = "event_registration_reminder_log_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** FK to {@code church_event.id}. */
    @Column(name = "event_id", nullable = false)
    private Integer eventId;

    /** FK to {@code event_registration_reminder_contact.id} (null if the contact was since deleted). */
    @Column(name = "contact_id")
    private Integer contactId;

    /** {@link #CHANNEL_EMAIL} or {@link #CHANNEL_SMS}. */
    @Column(name = "channel", nullable = false, length = 10)
    private String channel;

    /**
     * {@link #TYPE_REMINDER} (scheduled register-reminder) or
     * {@link #TYPE_INVITE} (admin-triggered invitation). Dedupe is scoped per
     * type so a sent invite never blocks the later reminder, and vice versa.
     * columnDefinition default lets ddl-auto backfill existing rows.
     */
    @Column(name = "message_type", length = 10,
            columnDefinition = "varchar(10) default 'REMINDER'")
    private String messageType;

    /** Recipient exactly as configured on the contact (for display). */
    @Column(name = "recipient")
    private String recipient;

    /**
     * Normalized form used for dedupe comparisons: lower-cased email, or the
     * 10-digit canonical phone (all formatting stripped, leading US "1" dropped).
     */
    @Column(name = "recipient_norm")
    private String recipientNorm;

    /** {@link #STATUS_SENT}, {@link #STATUS_SKIPPED}, or {@link #STATUS_FAILED}. */
    @Column(name = "status", nullable = false, length = 10)
    private String status;

    /** Human-readable explanation for SKIPPED / FAILED rows (null for clean sends). */
    @Column(name = "reason")
    private String reason;

    /** Days-before-event value of the schedule that produced this attempt. */
    @Column(name = "days_before")
    private Integer daysBefore;

    /** Org scoping — matches {@code church_event.app_client_id}. */
    @Column(name = "app_client_id")
    private String appClientId;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    @PrePersist
    protected void onCreate() {
        this.createdDate = new Date();
        if (this.messageType == null) this.messageType = TYPE_REMINDER;
    }
}
