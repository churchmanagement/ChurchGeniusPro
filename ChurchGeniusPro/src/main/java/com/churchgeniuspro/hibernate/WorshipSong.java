package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;

/**
 * A song entry for a specific service date and worship group.
 * Songs may be organised under headings (heading flag) or as individual titles.
 * Sort order controls display sequence within a date+group.
 */
@Data
@Entity
@Table(name = "worship_song",
       indexes = {
           @Index(name = "idx_worship_song_client_group_date",
                  columnList = "client_id, group_id, service_date, delete_flag")
       })
public class WorshipSong {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "worship_song_seq")
    @SequenceGenerator(name = "worship_song_seq", sequenceName = "worship_song_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Organization identifier. */
    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** The worship group this song list belongs to. */
    @Column(name = "group_id", nullable = false)
    private Long groupId;

    /**
     * The service date. {@code null} means this is a group-level library song
     * (shared across all weeks). A real date means it belongs to that week only.
     */
    @Column(name = "service_date", nullable = true)
    private LocalDate serviceDate;

    /**
     * When {@code true} this row is a section heading (e.g. "Praise Songs"),
     * not an actual song title.
     */
    @Column(name = "is_heading", nullable = false)
    private boolean isHeading = false;

    /** The song title or heading text. */
    @Column(name = "song_title", nullable = false, length = 500)
    private String songTitle;

    /** Display order within the date+group list. */
    @Column(name = "sort_order")
    private Integer sortOrder;

    /** Soft-delete flag. */
    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
