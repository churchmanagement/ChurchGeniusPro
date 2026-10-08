package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.GuessItGame;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GuessItGameRepository extends JpaRepository<GuessItGame, Long> {

    /** All non-deleted games for an org, newest first (legacy — kept for reference). */
    List<GuessItGame> findByClientIdAndDeleteFlagFalseOrderByCreatedAtDesc(String clientId);

    /** The currently active game for an org (there should be at most one). */
    Optional<GuessItGame> findFirstByClientIdAndStatusAndDeleteFlagFalse(
            String clientId, String status);

    /**
     * All non-deleted games for an org ordered by their admin-defined sequence.
     * sequence_order preserves creation/intended play order across restarts.
     */
    @Query("SELECT g FROM GuessItGame g WHERE g.clientId = :clientId AND g.deleteFlag = false " +
           "ORDER BY g.sequenceOrder ASC, g.createdAt ASC")
    List<GuessItGame> findAllByClientIdOrderBySequenceOrder(@Param("clientId") String clientId);

    /**
     * Returns the highest sequence_order value currently in use for the org,
     * or null when no games exist yet.  Used to assign the next sequence number.
     */
    @Query("SELECT MAX(g.sequenceOrder) FROM GuessItGame g " +
           "WHERE g.clientId = :clientId AND g.deleteFlag = false")
    Integer findMaxSequenceOrderByClientId(@Param("clientId") String clientId);

    // ------------------------------------------------------------------
    // Group-aware queries
    // ------------------------------------------------------------------

    /**
     * The active game inside one group. Each group runs its own game, so two
     * groups in the same org can be live at once without seeing each other.
     */
    Optional<GuessItGame> findFirstByGroupIdAndStatusAndDeleteFlagFalse(
            Long groupId, String status);

    /** Every game in a group, in the admin's intended play order. */
    @Query("SELECT g FROM GuessItGame g WHERE g.groupId = :groupId AND g.deleteFlag = false " +
           "ORDER BY g.sequenceOrder ASC, g.createdAt ASC")
    List<GuessItGame> findAllByGroupIdOrderBySequenceOrder(@Param("groupId") Long groupId);

    /** Published games in a group, in play order — what participants may see. */
    @Query("SELECT g FROM GuessItGame g WHERE g.groupId = :groupId AND g.published = true " +
           "AND g.deleteFlag = false ORDER BY g.sequenceOrder ASC, g.createdAt ASC")
    List<GuessItGame> findPublishedByGroupIdOrderBySequenceOrder(@Param("groupId") Long groupId);

    /**
     * Games in a group that are still playable (published but not finished).
     * When this reaches zero and at least one game exists, the group has run its
     * course and can be closed automatically.
     */
    @Query("SELECT COUNT(g) FROM GuessItGame g WHERE g.groupId = :groupId " +
           "AND g.deleteFlag = false AND g.published = true AND g.status <> 'completed'")
    long countUnfinishedPublishedInGroup(@Param("groupId") Long groupId);

    /** Highest sequence number inside a group, for assigning the next one. */
    @Query("SELECT MAX(g.sequenceOrder) FROM GuessItGame g " +
           "WHERE g.groupId = :groupId AND g.deleteFlag = false")
    Integer findMaxSequenceOrderByGroupId(@Param("groupId") Long groupId);

    // ------------------------------------------------------------------
    // Legacy (ungrouped) queries
    // ------------------------------------------------------------------

    /**
     * The active standalone game for an org — grouped games are excluded so that
     * starting a group game never hijacks the pre-existing member portal or
     * big-screen views, which knew nothing about groups.
     */
    Optional<GuessItGame> findFirstByClientIdAndGroupIdIsNullAndStatusAndDeleteFlagFalse(
            String clientId, String status);
}
