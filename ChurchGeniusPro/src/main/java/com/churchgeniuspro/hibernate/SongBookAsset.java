package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.Instant;

/**
 * A user-uploaded pre-designed page image for a church's Song Book — either the
 * custom <em>cover</em> page (replaces the generated cover) or the custom
 * <em>last</em> page (back/end page). Stored as an image (PDF uploads are
 * rasterized to PNG at upload time so they render and print reliably).
 *
 * <p>Tenant-scoped by {@code client_id}; one row per {@code kind}. The image is
 * stored as {@code bytea} (no {@code @Lob} — a large-object/BLOB would fail in
 * auto-commit, the same trap fixed for the text columns).
 */
@Data
@Entity
@Table(name = "song_book_asset",
       uniqueConstraints = @UniqueConstraint(columnNames = {"client_id", "kind"}))
public class SongBookAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** "cover" | "last" */
    @Column(name = "kind", nullable = false, length = 20)
    private String kind;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "file_name", length = 300)
    private String fileName;

    @Column(name = "data", columnDefinition = "bytea")
    private byte[] data;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
