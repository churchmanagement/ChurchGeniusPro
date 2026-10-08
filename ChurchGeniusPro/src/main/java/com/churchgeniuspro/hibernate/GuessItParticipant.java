package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

/**
 * Tracks a single member's participation in one Guess It game.
 *
 * <p>One row per (gameId, memberId) pair — enforced by a unique constraint.
 *
 * <p>{@code actionsJson} stores the per-clue actions as a JSON object:
 * <pre>
 *   { "0": "wrong:Eve", "1": "wrong:Sarah", "2": "correct:Ruth" }
 * </pre>
 * Keys are 0-based clue indices (as strings). Values are
 * {@code "correct:<option>"} or {@code "wrong:<option>"} or
 * {@code "auto:<option>"} (auto-eliminated when admin advanced clue).
 */
@Data
@Entity
@Table(name = "guess_it_participant",
       uniqueConstraints = {
           @UniqueConstraint(
               name = "uq_guess_it_participant_game_member",
               columnNames = {"game_id", "member_id"}),
           // One play row per person per grouped game.
           @UniqueConstraint(
               name = "uq_guess_it_participant_game_group_participant",
               columnNames = {"game_id", "group_participant_id"})
       },
       indexes = {
           @Index(name = "idx_guess_it_participant_game",
                  columnList = "game_id"),
           @Index(name = "idx_guess_it_participant_group_participant",
                  columnList = "group_participant_id")
       })
public class GuessItParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "guess_it_participant_seq")
    @SequenceGenerator(name = "guess_it_participant_seq", sequenceName = "guess_it_participant_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** The game this participant is playing. */
    @Column(name = "game_id", nullable = false)
    private Long gameId;

    /**
     * The family_member.id of the participant, when a logged-in member is
     * playing. Stored as Integer to match FamilyMember's Integer PK.
     *
     * <p>{@code null} for public participants, who have no member record — they
     * are identified by {@link #groupParticipantId} instead. PostgreSQL treats
     * NULLs as distinct in the (game_id, member_id) unique constraint, so any
     * number of public participants can share a game without colliding.
     */
    @Column(name = "member_id")
    private Integer memberId;

    /**
     * The {@link GuessItGroupParticipant} row this play belongs to, when the
     * game is part of a group. {@code null} for standalone games, which
     * continue to identify participants by {@link #memberId} alone.
     *
     * <p>This is what links a single game's play back to the person's
     * cumulative group score, and it is how public (not-logged-in) participants
     * are identified at all — for them {@link #memberId} is {@code null}.
     */
    @Column(name = "group_participant_id")
    private Long groupParticipantId;

    /** Display name at the time of joining (firstName + lastName). */
    @Column(name = "member_name", nullable = false, length = 300)
    private String memberName;

    /** Role at the time of joining (Member, Child, Son, Daughter, etc.). */
    @Column(name = "member_role", length = 50)
    private String memberRole;

    /**
     * JSON object recording the action taken for each clue index.
     * Example: {@code {"0":"wrong:Eve","1":"correct:Ruth"}}
     */
    @Column(name = "actions_json", columnDefinition = "TEXT")
    private String actionsJson = "{}";

    /**
     * {@code true} when the participant submitted the wrong "correct" answer
     * or was auto-eliminated.  Eliminated participants cannot take further
     * actions in this game.
     */
    @Column(name = "eliminated", nullable = false)
    private boolean eliminated = false;

    /**
     * {@code true} when the participant submitted the correct answer and won.
     */
    @Column(name = "winner", nullable = false)
    private boolean winner = false;

    /** Clue index at which this participant was eliminated (-1 = not eliminated). */
    @Column(name = "eliminated_at_clue")
    private Integer eliminatedAtClue;

    /** When this participant record was first created (joined the game). */
    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt = Instant.now();

    /** Tenant column (H2): backfilled by W4 from the parent; set on create by the owning
     *  service/controller. Nullable for now — flipped to NOT NULL once every create-path
     *  is deployed (see db/window/W4_tenant_columns.sql). */
    @Column(name = "client_id")
    private String clientId;
}
