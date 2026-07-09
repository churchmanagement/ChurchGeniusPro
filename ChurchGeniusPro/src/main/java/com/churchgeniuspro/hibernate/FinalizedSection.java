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
 * A section within the curated <em>Finalized</em> Song Book (e.g. "English",
 * "Malayalam"). Independent of the working-library {@link SongSection}s: the
 * finalized book has its own sections, order and grouping.
 *
 * <p>Songs reference their finalized section via {@code Song.finalizedSectionId};
 * within a section they are ordered by {@code Song.finalizedOrder}. Section
 * display order is {@code sort_order}. Tenant-scoped by {@code client_id}.
 */
@Data
@Entity
@Table(name = "finalized_section")
public class FinalizedSection {

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
