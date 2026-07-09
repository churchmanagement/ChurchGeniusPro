package com.churchgeniuspro.model;

import lombok.Data;

/**
 * Business Object representing the Save Meeting form submission from
 * {@code meeting.html}.
 *
 * <p>Received as the JSON request body of {@code POST /api/meetings} and
 * {@code PUT /api/meetings/{id}}, then forwarded to
 * {@link com.churchgeniuspro.service.MeetingService}.
 */
@Data
public class MeetingBO {

    // ── Type ──────────────────────────────────────────────────────────────

    /** ID of the selected {@link com.churchgeniuspro.hibernate.MeetingType}. */
    private Integer meetingTypeId;

    // ── Location ──────────────────────────────────────────────────────────

    /**
     * ID of the {@link com.churchgeniuspro.hibernate.FamilyMember} used as the
     * location.  {@code null} when address was entered manually or a family was selected.
     */
    private Integer locationMemberId;

    /**
     * ID of the {@link com.churchgeniuspro.hibernate.Family} used as the location.
     * {@code null} when address was entered manually or a member was selected.
     */
    private Integer locationFamilyId;

    // ── Schedule ──────────────────────────────────────────────────────────

    /** ISO date string: {@code "YYYY-MM-DD"}. */
    private String meetingDate;

    /** 24-hour time: {@code "HH:MM"}. */
    private String startTime;

    /** 24-hour time: {@code "HH:MM"}. */
    private String endTime;

    // ── Address ───────────────────────────────────────────────────────────

    private String address1;
    private String address2;
    private String city;

    /** Numeric state code string (e.g. {@code "10"} = Georgia). */
    private String state;

    /** Country text code (e.g. {@code "USA"}, {@code "CAN"}). */
    private String country;

    private String pinCode;

    // ── Notes ─────────────────────────────────────────────────────────────

    private String note;

    // ── Recurrence ────────────────────────────────────────────────────────

    /** One-time | Daily | Weekly | Monthly */
    private String occurrence;

    /** ISO date string: {@code "YYYY-MM-DD"}. Used for Daily / Weekly / Monthly meetings. */
    private String endDate;

    /** When true, this meeting is exempt from the automatic 90-day purge. */
    private boolean doNotAutoDelete;

    // ── Weekly extra ──────────────────────────────────────────────────────

    /**
     * Days of the week this meeting repeats on when {@link #occurrence} is
     * {@code Weekly}.  Values: 0=Sunday … 6=Saturday.
     */
    private int[] weekDays;

    // ── Monthly extras ────────────────────────────────────────────────────

    /**
     * Months of the year this meeting repeats in when {@link #occurrence} is
     * {@code Monthly}.  Values: 1=Jan … 12=Dec.
     */
    private int[] monthMonths;

    /**
     * Specific day of the month (1–31).  Used when {@link #occurrence} is
     * {@code Monthly} and the user selected a fixed date pattern.
     */
    private Integer monthDayOfMonth;

    /**
     * Week ordinal for a week-based monthly pattern (1=1st, 2=2nd, 3=3rd,
     * 4=4th, 5=Last).  Paired with {@link #monthWeekDay}.
     */
    private Integer monthWeekOrdinal;

    /**
     * Day of the week (0=Sun … 6=Sat) for a week-based monthly pattern.
     * Paired with {@link #monthWeekOrdinal}.
     */
    private Integer monthWeekDay;
}
