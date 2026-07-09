package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDate;

/**
 * Hibernate entity for the {@code church_event_day} table.
 *
 * <p>Stores individual day records for "Multiple Days" {@link ChurchEvent} instances.
 * One Day events store their schedule directly on {@link ChurchEvent}.
 */
@Data
@Entity
@Table(name = "church_event_day")
public class ChurchEventDay {

    // ── Primary Key ───────────────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "church_event_day_seq")
    @SequenceGenerator(
            name           = "church_event_day_seq",
            sequenceName   = "church_event_day_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    // ── Parent reference ──────────────────────────────────────────────────

    @Column(name = "event_id", nullable = false)
    private Integer eventId;

    // ── Schedule ──────────────────────────────────────────────────────────

    @Column(name = "event_date")
    private LocalDate eventDate;

    @Column(name = "start_time", length = 5)
    private String startTime;

    @Column(name = "end_time", length = 5)
    private String endTime;

    /** 1-based ordering within the event (Day 1, Day 2, …). */
    @Column(name = "day_order")
    private Integer dayOrder;

    /** Whether food is available on this specific day. */
    @Column(name = "food_available", nullable = false)
    private boolean foodAvailable;
}
