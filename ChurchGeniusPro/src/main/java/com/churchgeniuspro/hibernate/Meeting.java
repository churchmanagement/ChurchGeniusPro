package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDate;
import java.util.Date;

/**
 * Hibernate entity for the {@code meeting} table.
 *
 * <p>Records a scheduled meeting with its type, location (a Primary family
 * member), date/time, address, notes, and recurrence settings.
 *
 * <p>{@code deleteFlag} is set to {@code false} and {@code createdDate} is
 * stamped automatically on first persist via {@link #onCreate()}.
 */
@Data
@Entity
@Table(name = "meeting")
public class Meeting {

    // ── Primary Key ───────────────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "meeting_seq")
    @SequenceGenerator(
            name           = "meeting_seq",
            sequenceName   = "meeting_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    // ── Type ──────────────────────────────────────────────────────────────

    /** The category of this meeting (e.g. Sunday Service, Bible Study). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_type_id")
    private MeetingType meetingType;

    // ── Location ──────────────────────────────────────────────────────────

    /**
     * Optional FK to the {@code family_member.id} of the member whose address
     * was selected as the meeting location.  {@code null} when the address was
     * entered manually or a family was selected instead.
     */
    @Column(name = "location_member_id")
    private Integer locationMemberId;

    /**
     * Optional FK to the {@code family.id} of the family whose address was
     * selected as the meeting location.  {@code null} when the address was
     * entered manually or a member was selected instead.
     */
    @Column(name = "location_family_id")
    private Integer locationFamilyId;

    // ── Schedule ──────────────────────────────────────────────────────────

    @Column(name = "meeting_date")
    private LocalDate meetingDate;

    /** 24-hour time string, e.g. {@code "09:00"}. */
    @Column(name = "start_time", length = 5)
    private String startTime;

    /** 24-hour time string, e.g. {@code "11:00"}. */
    @Column(name = "end_time", length = 5)
    private String endTime;

    // ── Address ───────────────────────────────────────────────────────────

    @Column(name = "address1") private String address1;
    @Column(name = "address2") private String address2;
    @Column(name = "city")     private String city;

    /** Numeric state code as a string, matching the States.java lookup (e.g. "10" = GA). */
    @Column(name = "state")    private String state;

    /** Country text code (e.g. "USA", "CAN"). */
    @Column(name = "country")  private String country;

    @Column(name = "pin_code") private String pinCode;

    // ── Notes ─────────────────────────────────────────────────────────────

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    // ── Recurrence ────────────────────────────────────────────────────────

    /**
     * Recurrence pattern. Values: {@code One-time}, {@code Daily},
     * {@code Weekly}, {@code Monthly}.
     */
    @Column(name = "occurrence", length = 20)
    private String occurrence;

    /**
     * Last date of the recurrence series.  Meaningful only when
     * {@link #occurrence} is {@code Daily}, {@code Weekly}, or {@code Monthly}.
     */
    @Column(name = "end_date")
    private LocalDate endDate;

    // ── Weekly extras ─────────────────────────────────────────────────────

    /**
     * Days of the week this meeting repeats on (Weekly recurrence).
     * Stored as a comma-separated string of day numbers, e.g. {@code "0,3,5"}
     * for Sun/Wed/Fri (0=Sun … 6=Sat).
     */
    @Column(name = "week_days", length = 20)
    private String weekDays;

    // ── Monthly extras ────────────────────────────────────────────────────

    /**
     * Months of the year this meeting repeats in (Monthly recurrence).
     * Stored as a comma-separated string of month numbers, e.g. {@code "1,4,7,10"}
     * for Jan/Apr/Jul/Oct (1=Jan … 12=Dec).
     */
    @Column(name = "month_months", length = 30)
    private String monthMonths;

    /** Day of the month (1–31) for a fixed-date monthly pattern. */
    @Column(name = "month_day_of_month")
    private Integer monthDayOfMonth;

    /** Week ordinal for a week-based monthly pattern (1=1st, 2=2nd, 3=3rd, 4=4th, 5=Last). */
    @Column(name = "month_week_ordinal")
    private Integer monthWeekOrdinal;

    /** Day of week (0=Sun … 6=Sat) for a week-based monthly pattern. */
    @Column(name = "month_week_day")
    private Integer monthWeekDay;

    // ── Image ─────────────────────────────────────────────────────────────

    /** Optional banner/flyer image stored as raw bytes. */
    @Column(name = "image_data", columnDefinition = "BYTEA")
    private byte[] imageData;

    /** MIME type of the stored image (e.g. {@code "image/jpeg"}). */
    @Column(name = "image_content_type", length = 64)
    private String imageContentType;

    // ── Soft-Delete ───────────────────────────────────────────────────────

    /** Optional org identifier from the app_user who created this record. Null for church-level accounts. */
    @Column(name = "app_client_id")
    private String appClientId;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    /** When true, this meeting is exempt from the automatic 90-day purge. */
    @Column(name = "do_not_auto_delete", nullable = false, columnDefinition = "boolean not null default false")
    private boolean doNotAutoDelete;

    // ── Audit ─────────────────────────────────────────────────────────────

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @PrePersist
    protected void onCreate() {
        this.createdDate = new Date();
        this.deleteFlag  = false;
    }
}
