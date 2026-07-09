package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.Instant;

/**
 * Deduplication log for all scheduled reminder sends.
 *
 * <p>One row is written <em>before</em> a reminder batch is dispatched.
 * The unique constraint on {@code (app_client_id, reminder_type, reference_key, sent_date)}
 * guarantees that even if two scheduler threads race, only one will succeed with the INSERT
 * (the other gets a constraint violation that is caught and treated as "already sent").
 *
 * <h3>Key fields</h3>
 * <ul>
 *   <li>{@code reminderType} — short string identifying the reminder category:
 *       {@code "MEETING"}, {@code "WEEKLY_MEETING"}, {@code "BIRTHDAY"},
 *       {@code "ANNIVERSARY"}, {@code "HOLIDAY_<N>"}, {@code "ONE_TIME_<id>"},
 *       {@code "EVENT_<eventId>_<trigger>"}, {@code "PRAYER"}.</li>
 *   <li>{@code referenceKey} — further scopes the row within a reminder type.
 *       For meetings it is the meeting ID; for birthdays it is the member ID;
 *       for holidays it is the year; for weekly-meeting it is the ISO week string.
 *       Use {@code "ALL"} when a single row covers the whole batch.</li>
 *   <li>{@code sentDate} — CST calendar date on which the send occurred.
 *       Ensures birthday reminders reset each year.</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "reminder_sent_log",
       uniqueConstraints = @UniqueConstraint(
               name = "uq_reminder_sent_log",
               columnNames = {"app_client_id", "reminder_type", "reference_key", "sent_date"}),
       indexes = {
           @Index(name = "idx_rsl_client_type_date",
                  columnList = "app_client_id,reminder_type,sent_date")
       })
public class ReminderSentLog {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "rsl_seq")
    @SequenceGenerator(name = "rsl_seq", sequenceName = "reminder_sent_log_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Organisation client ID — same as {@code app_client_id} on all other tables. */
    @Column(name = "app_client_id", nullable = false, length = 64)
    private String appClientId;

    /**
     * Category of the reminder, e.g. {@code "MEETING"}, {@code "BIRTHDAY"},
     * {@code "ANNIVERSARY"}, {@code "HOLIDAY_7"}, {@code "ONE_TIME_42"},
     * {@code "EVENT_5_SAME_DAY"}, {@code "WEEKLY_MEETING"}.
     */
    @Column(name = "reminder_type", nullable = false, length = 64)
    private String reminderType;

    /**
     * Scopes the log row within a reminder type.
     * Examples: meeting ID as string, member ID, year string, ISO-week string, "ALL".
     */
    @Column(name = "reference_key", nullable = false, length = 128)
    private String referenceKey;

    /** CST calendar date on which this reminder was (first) sent. */
    @Column(name = "sent_date", nullable = false)
    private LocalDate sentDate;

    /** Wall-clock instant of the send for auditing purposes. */
    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    @PrePersist
    protected void onCreate() {
        if (this.sentAt == null) this.sentAt = Instant.now();
    }
}
