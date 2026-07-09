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
       uniqueConstraints = @UniqueConstraint(
               name = "uq_guess_it_participant_game_member",
               columnNames = {"game_id", "member_id"}),
       indexes = {
           @Index(name = "idx_guess_it_participant_game",
                  columnList = "game_id")
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
     * The family_member.id of the participant.
     * Stored as Integer to match FamilyMember's Integer PK.
     */
    @Column(name = "member_id", nullable = false)
    private Integer memberId;

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
}
