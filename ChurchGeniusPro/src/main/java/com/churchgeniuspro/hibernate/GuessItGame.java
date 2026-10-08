package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

/**
 * A single Guess It game round.
 *
 * <p>Clues and answer options are stored as JSON arrays (plain text) so we
 * avoid extra join tables while keeping the model simple.
 *
 * <ul>
 *   <li>{@code clues}   – JSON array of clue strings, e.g. {@code ["Female","Humility","Naomi"]}</li>
 *   <li>{@code options} – JSON array of answer option strings</li>
 *   <li>{@code correctAnswer} – the exact option string that is correct</li>
 *   <li>{@code currentClueIndex} – 0-based index of the clue currently revealed (starts at 0)</li>
 *   <li>{@code status} – one of: {@code pending}, {@code active}, {@code completed}</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "guess_it_game",
       indexes = {
           // Hot-path: find the active game for a given org (used by every poll)
           @Index(name = "idx_guess_it_game_client_status_del",
                  columnList = "client_id, status, delete_flag"),
           // Admin game list ordered by sequence (secondary fallback on created_at)
           @Index(name = "idx_guess_it_game_client_seq",
                  columnList = "client_id, sequence_order, created_at")
       })
public class GuessItGame {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "guess_it_game_seq")
    @SequenceGenerator(name = "guess_it_game_seq", sequenceName = "guess_it_game_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Multi-tenant organisation identifier. */
    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /**
     * Owning {@link GuessItGroup}, or {@code null} for a standalone game.
     *
     * <p>Games created before groups existed have {@code null} here and keep
     * their original single-game behaviour: no cumulative scoring, visible
     * without a join code, driven purely by the admin's start/next/end controls.
     */
    @Column(name = "group_id")
    private Long groupId;

    /**
     * Whether this game has been released to participants.
     *
     * <p>Only meaningful for grouped games: an unpublished game is invisible to
     * participants so an admin can build a whole set in advance and release the
     * rounds one at a time. Standalone (ungrouped) games ignore this flag
     * entirely, which is why the column defaults to {@code false} without
     * changing how legacy games behave.
     */
    @Column(name = "published", nullable = false, columnDefinition = "boolean default false")
    private boolean published = false;

    /** Display title shown to participants, e.g. "Guess the Bible Character". */
    @Column(name = "title", nullable = false, length = 500)
    private String title;

    /** JSON array of clue strings. */
    @Column(name = "clues", nullable = false, columnDefinition = "TEXT")
    private String clues;

    /** JSON array of answer option strings. */
    @Column(name = "options", nullable = false, columnDefinition = "TEXT")
    private String options;

    /** The exact option string that is the correct answer. */
    @Column(name = "correct_answer", nullable = false, length = 500)
    private String correctAnswer;

    /**
     * 0-based index of the currently revealed clue.
     * When the game is {@code pending} this is -1 (no clue shown yet).
     * Admin advances this manually.
     */
    @Column(name = "current_clue_index", nullable = false)
    private int currentClueIndex = -1;

    /**
     * Game lifecycle state.
     * {@code pending}   – created but not yet started.
     * {@code active}    – in progress; participants can interact.
     * {@code completed} – ended; no more actions accepted.
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "pending";

    /**
     * Admin-defined play order within this org's game list.
     * Assigned as MAX(sequenceOrder)+1 at creation time and never changed
     * by restart — so the original sequence is always preserved.
     * Existing rows default to 0 when the column is first added by Hibernate DDL=update.
     * columnDefinition supplies the DEFAULT so PostgreSQL can add it to existing rows
     * without rejecting the NOT NULL constraint.
     */
    @Column(name = "sequence_order", nullable = false, columnDefinition = "integer default 0")
    private int sequenceOrder = 0;

    /** When this game record was created. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** When the game was started (status → active). */
    @Column(name = "started_at")
    private Instant startedAt;

    /** When the game was completed (status → completed). */
    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * Per-clue countdown duration in seconds chosen by the admin.
     * {@code 0} means no timer (unlimited time).
     * New games default to 60 seconds (one minute); the admin can still change
     * it. The column DEFAULT stays 0 so games stored before this change keep
     * whatever duration they were created with.
     * Stored once per game; every clue uses the same duration.
     * columnDefinition supplies the DEFAULT so PostgreSQL can add this column
     * to existing rows without rejecting the NOT NULL constraint.
     */
    @Column(name = "timer_secs", nullable = false, columnDefinition = "integer default 0")
    private int timerSecs = 60;

    /**
     * Server-side UTC timestamp of when the current clue was revealed.
     * Set on game start, each next-clue advance, and restart.
     * Clients compute remaining time as {@code timerSecs - (nowEpochMs - clueStartedAt) / 1000}
     * so they are anchored to server time rather than their own local clock.
     * Null when game is pending or timer is disabled.
     */
    @Column(name = "clue_started_at")
    private Instant clueStartedAt;

    /** Soft-delete flag. */
    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
