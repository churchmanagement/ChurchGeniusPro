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
 * Publication state of a church's Song Book. One row per church.
 *
 * <p>Publishing captures an immutable JSON snapshot of the finalized songs +
 * lyrics and mints a secure {@code token}. The public viewer reads the snapshot
 * by token, so further edits in the portal do not change the public book until
 * it is published again. Unpublishing flips {@code published = false} so the
 * public URL stops serving content.
 */
@Data
@Entity
@Table(name = "song_book_publish")
public class SongBookPublish {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, unique = true, length = 100)
    private String clientId;

    @Column(name = "book_title", length = 300)
    private String bookTitle;

    /** Secure, URL-safe public token. */
    @Column(name = "token", unique = true, length = 120)
    private String token;

    @Column(name = "published", nullable = false)
    private boolean published = false;

    /** Immutable JSON snapshot of finalized songs + lyrics at publish time.
     *  No @Lob (see Song.lyricsHtml) — plain TEXT, read inline as a String. */
    @Column(name = "snapshot", columnDefinition = "TEXT")
    private String snapshot;

    /** Cover-page designer settings (JSON) — persists across publishes. */
    @Column(name = "cover_json", columnDefinition = "TEXT")
    private String coverJson;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "published_by", length = 100)
    private String publishedBy;

    /* ── "Now Singing" / "Next Song" live selections (per book, per tenant) ──
     * These live on the publish row, which is uniquely keyed by the book-scoped
     * client_id, so they are automatically tenant- and book-safe. Both reference
     * a {@link Song} id that must exist in the current published {@link #snapshot}.
     * All three columns are nullable (added by ddl-auto=update on existing rows). */

    /** The song currently being sung, or null when none is set. */
    @Column(name = "current_song_id")
    private Long currentSongId;

    /** The song coming up next, or null when none is set. */
    @Column(name = "next_song_id")
    private Long nextSongId;

    /** Monotonic counter bumped on every change to {@link #currentSongId}
     *  (set / change / unset). The public viewer uses it to re-show a dismissed
     *  banner only when the Current Song setting actually changes. null == 0. */
    @Column(name = "current_song_version")
    private Long currentSongVersion;
}
