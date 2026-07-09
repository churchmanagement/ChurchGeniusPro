package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/**
 * A user-defined section / category within a church's Song Book
 * (e.g. "Malayalam Songs", "Youth Songs", "Christmas Songs").
 *
 * <p>Sections are freely created, renamed, deleted and reordered — the system
 * enforces no fixed language or order. Songs reference their section via
 * {@code Song.sectionId}. Tenant-scoped by {@code client_id}; display order is
 * {@code sort_order}.
 */
@Data
@Entity
@Table(name = "song_section")
public class SongSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at")
    private Instant createdAt;
}
