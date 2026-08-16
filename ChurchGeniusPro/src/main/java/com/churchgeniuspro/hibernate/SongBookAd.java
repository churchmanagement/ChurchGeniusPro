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
 * An advertisement / announcement page for a church's Song Book — sponsor ads,
 * event promotions, ministry announcements, etc. Uploaded as an image or PDF
 * (PDF pages are rasterized to PNG at upload time, one row per page) and
 * insertable anywhere in the book via {@link #position}:
 *
 * <ul>
 *   <li>{@code cover} — right after the cover page</li>
 *   <li>{@code toc} — after the table of contents</li>
 *   <li>{@code fsec:<finalizedSectionId>} — after that section's songs</li>
 *   <li>{@code end} — after all sections (before the custom last page)</li>
 * </ul>
 *
 * <p>Multiple ads may share a position; {@link #sortOrder} orders them. Stored
 * as {@code bytea} (no {@code @Lob} — same auto-commit trap as the other song
 * tables). Tenant-scoped by {@code client_id} (book-scoped ids supported).</p>
 */
@Data
@Entity
@Table(name = "song_book_ad")
public class SongBookAd {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** Optional admin label, e.g. "Sponsor — Smith's Bakery". */
    @Column(name = "title", length = 200)
    private String title;

    /** cover | toc | fsec:&lt;id&gt; | end */
    @Column(name = "position", nullable = false, length = 40)
    private String position = "end";

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "file_name", length = 300)
    private String fileName;

    @Column(name = "data", columnDefinition = "bytea")
    private byte[] data;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
}
