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
}
