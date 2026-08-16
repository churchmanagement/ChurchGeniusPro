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
 * A named Song Book belonging to a church. A church may have any number of
 * books (e.g. "Musical Night", "Christmas Carols"), each with its own
 * sections, songs, finalized list, cover design and publish state.
 *
 * <p><b>Data scoping.</b> The song tables (song, song_section,
 * finalized_section, song_book_publish, song_book_asset, song_audit_log) are
 * keyed by {@code client_id}. Rather than adding a book column to every table,
 * non-default books store their rows under a <i>scoped</i> client id of the
 * form {@code <clientId>#B<bookId>} (see SongBookController#scopeFor). The
 * DEFAULT book uses the plain {@code clientId}, so all pre-existing data
 * automatically belongs to the default book ("Musical Night") and existing
 * behavior is unchanged.
 */
@Data
@Entity
@Table(name = "song_book")
public class SongBook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** The default book owns all legacy (plain client_id) song data. */
    @Column(name = "default_book", nullable = false)
    private boolean defaultBook = false;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at")
    private Instant createdAt;
}
