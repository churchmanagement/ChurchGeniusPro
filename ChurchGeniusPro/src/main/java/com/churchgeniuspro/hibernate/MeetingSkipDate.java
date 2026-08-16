package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A single deleted (skipped) occurrence of a recurring {@link Meeting}.
 *
 * <p>Recurring meetings are stored as ONE {@code meeting} row and their
 * occurrences are computed on the fly (calendar, ICS feed, reminder
 * scheduler). Deleting an individual occurrence therefore cannot remove a
 * row — instead a {@code MeetingSkipDate} exception is recorded and every
 * expansion point (Event Calendar, ICS EXDATE, meeting reminders, weekly
 * digest) excludes that date. All other occurrences continue unchanged.</p>
 */
@Entity
@Table(name = "meeting_skip_date",
       uniqueConstraints = @UniqueConstraint(name = "uq_meeting_skip",
               columnNames = {"meeting_id", "skip_date"}))
@Getter
@Setter
@NoArgsConstructor
public class MeetingSkipDate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to {@code meeting.id} (kept as a plain column — no navigation needed). */
    @Column(name = "meeting_id", nullable = false)
    private Integer meetingId;

    /** The occurrence date that was deleted. */
    @Column(name = "skip_date", nullable = false)
    private LocalDate skipDate;

    /** Tenant scoping — mirrors {@code meeting.app_client_id}. */
    @Column(name = "app_client_id", length = 64)
    private String appClientId;

    @Column(name = "created_date")
    private LocalDateTime createdDate = LocalDateTime.now();

    public MeetingSkipDate(Integer meetingId, LocalDate skipDate, String appClientId) {
        this.meetingId   = meetingId;
        this.skipDate    = skipDate;
        this.appClientId = appClientId;
    }
}
