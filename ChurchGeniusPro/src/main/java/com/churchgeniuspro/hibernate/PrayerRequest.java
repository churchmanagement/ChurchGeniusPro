package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

/**
 * Hibernate entity for the {@code prayer_request} table.
 *
 * <p>Represents a prayer subsection / individual prayer item
 * (e.g. "Anson's Visa Process") nested under a {@link PrayerSection}.
 */
@Data
@Entity
@Table(name = "prayer_request")
public class PrayerRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "prayer_request_seq")
    @SequenceGenerator(
            name           = "prayer_request_seq",
            sequenceName   = "prayer_request_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** FK to {@code prayer_section.id}. */
    @Column(name = "section_id", nullable = false)
    private Long sectionId;

    /** Short title / subsection name (e.g. "Anson's Visa Process"). */
    @Column(name = "title", nullable = false)
    private String title;

    /** Full prayer request description. */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Name of the person making the request. */
    @Column(name = "requester_name")
    private String requesterName;

    /**
     * Workflow status. Legacy values "Active" / "Answered" are still accepted
     * for backwards compat; the new vocabulary is one of:
     * New, Assigned, In Progress, Following Up, Answered, Closed.
     *
     * <p>"Closed" is the archive state: closed requests are hidden from the
     * default Active list but stay in the DB for history.
     */
    @Column(name = "status")
    private String status;

    /**
     * Optional FK to {@code family_member.id} of the volunteer the request is
     * currently assigned to. Null = unassigned. We don't enforce the FK here
     * (no relationship mapping) so a deleted FamilyMember doesn't cascade
     * into the prayer module.
     */
    @Column(name = "assigned_volunteer_id")
    private Integer assignedVolunteerId;

    /**
     * Timestamp when the request was moved to status='Closed'. Lets the UI
     * group archived requests by close date without parsing notes.
     */
    @Column(name = "closed_at")
    private Date closedAt;

    /** Organization that owns this request. */
    @Column(name = "client_id")
    private String clientId;

    // ── Schedule ──────────────────────────────────────────────────────────

    /**
     * Recurrence pattern for scheduled prayer email reminders.
     * Values: {@code One-time}, {@code Daily}, {@code Weekly}, {@code Monthly}.
     * Null / blank = no scheduled reminder.
     */
    @Column(name = "occurrence", length = 20)
    private String occurrence;

    /**
     * Days of the week for a Weekly schedule (0=Sun … 6=Sat).
     * Stored as comma-separated string, e.g. {@code "0,3,5"}.
     */
    @Column(name = "week_days", length = 20)
    private String weekDays;

    /**
     * Months for a Monthly schedule (1=Jan … 12=Dec).
     * Stored as comma-separated string, e.g. {@code "1,6,12"}.
     */
    @Column(name = "month_months", length = 30)
    private String monthMonths;

    /** Specific day of month (1–31) for a fixed-date monthly pattern. */
    @Column(name = "month_day_of_month")
    private Integer monthDayOfMonth;

    /** Week ordinal for a week-based monthly pattern (1=1st … 4=4th, 5=Last). */
    @Column(name = "month_week_ordinal")
    private Integer monthWeekOrdinal;

    /** Day of week (0=Sun … 6=Sat) for a week-based monthly pattern. */
    @Column(name = "month_week_day")
    private Integer monthWeekDay;

    // ── Soft-delete / audit ───────────────────────────────────────────────

    /** Soft-delete flag — records are never physically removed. */
    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Date createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt  = new Date();
        this.deleteFlag = false;
        if (this.status == null || this.status.isBlank()) {
            // New default per the v1 spec. Legacy "Active" rows still
            // load correctly — the controller maps them to "New" on read.
            this.status = "New";
        }
    }
}
