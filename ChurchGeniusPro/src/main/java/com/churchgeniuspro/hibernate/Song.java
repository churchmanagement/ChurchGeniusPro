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
 * A song in a church's single working Song Book.
 *
 * <p>A song starts in the working list ({@code finalized = false}). When moved
 * to the Finalized list ({@code finalized = true}) it can carry lyrics
 * ({@code lyricsHtml}) and is ordered by {@code finalizedOrder}. The working
 * list is ordered by {@code workingOrder}. One list per church ({@code client_id}).
 */
@Data
@Entity
@Table(name = "song")
public class Song {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    /** Owning section/category (nullable for legacy rows → migrated to a default). */
    @Column(name = "section_id")
    private Long sectionId;

    /** Song language, e.g. English, Malayalam, Hindi, Tamil, Telugu, Kannada. */
    @Column(name = "language", length = 60)
    private String language;

    /** Order within its section's working list. */
    @Column(name = "working_order", nullable = false)
    private int workingOrder;

    /** True once moved to the Finalized book. */
    @Column(name = "finalized", nullable = false)
    private boolean finalized = false;

    /** Owning section within the Finalized book (null until assigned). */
    @Column(name = "finalized_section_id")
    private Long finalizedSectionId;

    /** Order within its finalized section. */
    @Column(name = "finalized_order", nullable = false)
    private int finalizedOrder;

    /** Lyrics as sanitized HTML (preserves line breaks / basic formatting).
     *  NOTE: no @Lob — on PostgreSQL @Lob maps a String to a large-object/CLOB
     *  which can't be read in auto-commit (repository finders). A plain TEXT
     *  column is read inline as a String. */
    @Column(name = "lyrics_html", columnDefinition = "TEXT")
    private String lyricsHtml;

    /** Original lyrics source filename (for reference). */
    @Column(name = "lyrics_filename", length = 300)
    private String lyricsFilename;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
