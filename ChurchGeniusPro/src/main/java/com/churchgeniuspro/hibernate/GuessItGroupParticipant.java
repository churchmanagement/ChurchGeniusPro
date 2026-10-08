package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

/**
 * One person's membership of a {@link GuessItGroup}, carrying their cumulative
 * score across every game in that group.
 *
 * <p>A person registers once per group and keeps the same row for all of its
 * games — that is what lets the leaderboard accumulate.
 *
 * <p>Two kinds of participant share this table:
 * <ul>
 *   <li><b>Logged-in members</b> — {@link #memberId} is set from the session and
 *       {@link #displayName} is their first name. They are matched back by
 *       member id, so they resume their row on any device.</li>
 *   <li><b>Public participants</b> — {@link #memberId} is {@code null}; they are
 *       identified by the opaque {@link #token} handed out at join time and kept
 *       in the browser. No login, no personal data beyond the name they type.</li>
 * </ul>
 *
 * <p>{@link #nameKey} is the case-folded display name and is unique within a
 * group, which is what enforces "that name is already taken in this game".
 * The same name is free to be used again in a different group.
 */
@Data
@Entity
@Table(name = "guess_it_group_participant",
       uniqueConstraints = {
           @UniqueConstraint(name = "uq_guess_it_group_participant_name",
                             columnNames = {"group_id", "name_key"})
       },
       indexes = {
           @Index(name = "idx_guess_it_group_participant_group",
                  columnList = "group_id"),
           // Public participants are resolved by token on every poll.
           @Index(name = "idx_guess_it_group_participant_token",
                  columnList = "token"),
           // Logged-in members are resolved by (group, member).
           @Index(name = "idx_guess_it_group_participant_member",
                  columnList = "group_id, member_id")
       })
public class GuessItGroupParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "guess_it_group_participant_seq")
    @SequenceGenerator(name = "guess_it_group_participant_seq",
                       sequenceName = "guess_it_group_participant_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** The group this person belongs to. */
    @Column(name = "group_id", nullable = false)
    private Long groupId;

    /** Name shown on clue cards and the leaderboard, as typed or as first name. */
    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    /**
     * Case-folded, whitespace-collapsed display name. Uniqueness within the
     * group is enforced on this column so "Sam", "sam" and " Sam " collide.
     */
    @Column(name = "name_key", nullable = false, length = 200)
    private String nameKey;

    /**
     * family_member.id when a logged-in member joined; {@code null} for public
     * participants.
     */
    @Column(name = "member_id")
    private Integer memberId;

    /**
     * Opaque identifier handed to public participants so they can resume the
     * group on later polls without logging in. Also populated for members so a
     * single lookup path works for both.
     */
    @Column(name = "token", nullable = false, length = 64)
    private String token;

    /**
     * Cumulative points across the group — one point per game won.
     * columnDefinition supplies the DEFAULT so PostgreSQL can add the column to
     * existing rows without rejecting the NOT NULL constraint.
     */
    @Column(name = "score", nullable = false, columnDefinition = "integer default 0")
    private int score = 0;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt = Instant.now();

    /** Tenant column (H2): backfilled by W4 from the parent; set on create by the owning
     *  service/controller. Nullable for now — flipped to NOT NULL once every create-path
     *  is deployed (see db/window/W4_tenant_columns.sql). */
    @Column(name = "client_id")
    private String clientId;
}
