package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * The flyer image for a {@link ChurchEvent}, stored in its own table so the (large,
 * base64 data-URI) payload is never loaded by the ordinary event queries.
 *
 * <p>Database audit P7 (Sept 2026): {@code church_event.image_data} was a {@code TEXT}
 * column holding each flyer as an inline {@code data:image/...;base64,…} URI — roughly
 * 2.7 MB per event — and Hibernate eager-loads a basic column, so every event list,
 * calendar and reminder query pulled every flyer (production: {@code church_event} was
 * 51 MB for 19 rows, 31,987 sequential scans). The bytes live here now, one row per
 * event, fetched only when a caller actually needs the image (the single-event view and
 * the reminder emails). The event row keeps a cheap {@code image_present} flag so a list
 * can say whether an image exists without loading it. Mirrors {@code church_logo}, which
 * already serves its image from a separate table.
 */
@Data
@Entity
@Table(name = "church_event_image",
       uniqueConstraints = @UniqueConstraint(name = "uq_church_event_image_event", columnNames = {"event_id"}))
public class ChurchEventImage {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "church_event_image_seq")
    @SequenceGenerator(name = "church_event_image_seq", sequenceName = "church_event_image_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    /** The owning event — one image row per event (uniqueness via uq_church_event_image_event). */
    @Column(name = "event_id", nullable = false)
    private Integer eventId;

    /** Tenant, carried for defence in depth (the caller already scopes by event). */
    @Column(name = "app_client_id", length = 100)
    private String appClientId;

    /** The flyer as a {@code data:image/...;base64,…} URI, exactly as it was stored before. */
    @Column(name = "image_data", columnDefinition = "TEXT")
    private String imageData;

    @Column(name = "created_date")
    private LocalDateTime createdDate = LocalDateTime.now();
}
