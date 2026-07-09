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
 * Persisted reminder rule linked to a specific scheduled meeting.
 * The scheduler sends the reminder within 3 hours before the meeting start.
 */
@Data
@Entity
@Table(name = "meeting_reminder")
public class MeetingReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "meeting_reminder_seq")
    @SequenceGenerator(
            name           = "meeting_reminder_seq",
            sequenceName   = "meeting_reminder_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    /** FK to {@code meeting.id}. */
    @Column(name = "meeting_id")
    private Integer meetingId;

    /**
     * Comma-separated recipient groups.
     * Possible tokens: {@code Members}, {@code Guests}.
     */
    @Column(name = "recipients")
    private String recipients;

    /** When {@code true} this rule is ignored by the scheduler. */
    @Column(name = "disabled", nullable = false)
    private Boolean disabled;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    /** Org scoping — matches {@code app_user.client_id}. */
    @Column(name = "app_client_id")
    private String appClientId;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    @PrePersist
    protected void onCreate() {
        this.disabled    = false;
        this.createdDate = new Date();
    }
}
