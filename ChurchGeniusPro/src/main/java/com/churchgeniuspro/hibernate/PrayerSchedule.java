package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

/**
 * Hibernate entity for the {@code prayer_schedule} table.
 *
 * <p>Stores the single global reminder schedule for all Prayer Requests
 * belonging to an organization.  One row per {@code client_id}.</p>
 *
 * <p>Schedule semantics:</p>
 * <ul>
 *   <li><b>One-time</b> — fires once (admin clears occurrence to stop).</li>
 *   <li><b>Daily</b>    — fires every day.</li>
 *   <li><b>Weekly</b>   — fires on {@code weekDays} (0=Sun…6=Sat, comma-sep).</li>
 *   <li><b>Monthly</b>  — fires on selected months ({@code monthMonths}) and either
 *       a fixed day ({@code monthDayOfMonth}) or a week-ordinal pattern
 *       ({@code monthWeekOrdinal} + {@code monthWeekDay}).</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "prayer_schedule")
public class PrayerSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "prayer_schedule_seq")
    @SequenceGenerator(
            name           = "prayer_schedule_seq",
            sequenceName   = "prayer_schedule_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Organization that owns this schedule — unique per org. */
    @Column(name = "client_id", nullable = false, unique = true, length = 100)
    private String clientId;

    /**
     * Recurrence type.  One of: {@code One-time}, {@code Daily}, {@code Weekly},
     * {@code Monthly}.  {@code null} / blank = no reminder.
     */
    @Column(name = "occurrence", length = 20)
    private String occurrence;

    /**
     * Comma-separated days of the week for Weekly schedule (0=Sun…6=Sat).
     * Example: {@code "0,3,5"} = Sun, Wed, Fri.
     */
    @Column(name = "week_days", length = 20)
    private String weekDays;

    /**
     * Comma-separated months for Monthly schedule (1=Jan…12=Dec).
     * Example: {@code "1,6,12"} = Jan, Jun, Dec.
     */
    @Column(name = "month_months", length = 30)
    private String monthMonths;

    /** Fixed day of month (1–31) for Monthly schedule. */
    @Column(name = "month_day_of_month")
    private Integer monthDayOfMonth;

    /** Week ordinal for week-based monthly pattern (1=1st…4=4th, 5=Last). */
    @Column(name = "month_week_ordinal")
    private Integer monthWeekOrdinal;

    /** Day of week (0=Sun…6=Sat) for week-based monthly pattern. */
    @Column(name = "month_week_day")
    private Integer monthWeekDay;

    /**
     * Hour of day (0–23, America/Chicago) at which the prayer reminder should be sent.
     * {@code 6} = 6 AM, {@code 18} = 6 PM.
     * {@code null} = send at any time (legacy / no preference set).
     */
    @Column(name = "send_hour")
    private Integer sendHour;

    /**
     * The calendar date (stored as a DATE) on which the prayer reminder was
     * last successfully sent for this org.  The scheduler compares this to
     * {@code LocalDate.now()} before sending — if they match, the digest has
     * already gone out today and the run is skipped.
     */
    @Column(name = "last_sent_date")
    @Temporal(TemporalType.DATE)
    private Date lastSentDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Date createdAt;

    @Column(name = "updated_at")
    private Date updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = new Date();
        this.updatedAt = new Date();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = new Date();
    }
}
