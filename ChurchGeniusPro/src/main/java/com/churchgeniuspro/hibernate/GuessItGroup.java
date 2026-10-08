package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

/**
 * A Guess It <em>group</em>: a container for several games played by the same
 * set of participants, with a cumulative score carried across those games.
 *
 * <p>Participants join a group once — by entering its short {@link #code} —
 * and then play every published game inside it without registering again.
 *
 * <p>Groups are optional. Games created before groups existed (and any game
 * created without one) have a {@code null} group_id and continue to behave
 * exactly as they did before: single game, no cumulative scoring.
 */
@Data
@Entity
@Table(name = "guess_it_group",
       indexes = {
           // Hot path: public participants resolve a group by its join code.
           @Index(name = "idx_guess_it_group_code_status",
                  columnList = "code, status, delete_flag"),
           // Admin listing for an org.
           @Index(name = "idx_guess_it_group_client",
                  columnList = "client_id, status, delete_flag")
       })
public class GuessItGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "guess_it_group_seq")
    @SequenceGenerator(name = "guess_it_group_seq", sequenceName = "guess_it_group_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Multi-tenant organisation identifier (matches GuessItGame.clientId). */
    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /**
     * Six-character uppercase alphanumeric join code, e.g. {@code A1768G}.
     * Unique across all groups that are still {@code active} — an ended group's
     * code may be reissued later.
     */
    @Column(name = "code", nullable = false, length = 6)
    private String code;

    /** Optional admin-facing label, e.g. "Sunday Youth Night". */
    @Column(name = "name", length = 200)
    private String name;

    /**
     * Lifecycle state.
     * {@code active} — accepting joins and game play.
     * {@code ended}  — closed; final results remain viewable but nothing is playable.
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "active";

    /**
     * Per-clue countdown duration, in seconds, shared by every game in this
     * group. {@code 0} means no timer.
     *
     * <p>This is the group's own setting rather than a copy of any one game's:
     * it is what newly added games inherit, and changing it re-applies to every
     * game in the group that has not finished yet, so a host can lengthen or
     * shorten the rounds part-way through an evening. Completed games keep the
     * duration they were actually played with.
     *
     * <p>Capped at {@code 180} (three minutes) by the controller — long enough
     * for a hard clue, short enough that a room does not lose interest.
     *
     * <p>columnDefinition supplies the DEFAULT so PostgreSQL can add this NOT
     * NULL column to the existing rows of a live table (see CLAUDE.md — a
     * nullable=false column without one is silently skipped by ddl-auto).
     */
    @Column(name = "timer_secs", nullable = false, columnDefinition = "integer default 60")
    private int timerSecs = 60;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Set when the group is ended, manually or by finishing every game. */
    @Column(name = "ended_at")
    private Instant endedAt;

    /** Soft-delete flag. */
    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
