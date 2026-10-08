package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.GuessItParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GuessItParticipantRepository extends JpaRepository<GuessItParticipant, Long> {

    /** All participants for a given game. */
    List<GuessItParticipant> findByGameId(Long gameId);

    /** Look up a specific participant in a game. */
    Optional<GuessItParticipant> findByGameIdAndMemberId(Long gameId, Integer memberId);

    /** Winners for a given game. */
    List<GuessItParticipant> findByGameIdAndWinnerTrue(Long gameId);

    /** Eliminated (non-winner) participants for a given game. */
    List<GuessItParticipant> findByGameIdAndEliminatedTrueAndWinnerFalse(Long gameId);

    /** Active (not eliminated, not winner) participants for a given game. */
    List<GuessItParticipant> findByGameIdAndEliminatedFalseAndWinnerFalse(Long gameId);

    /** A grouped game's play row for one group participant. */
    Optional<GuessItParticipant> findByGameIdAndGroupParticipantId(Long gameId, Long groupParticipantId);

    /** Every play row belonging to a group participant, across all its games. */
    List<GuessItParticipant> findByGroupParticipantId(Long groupParticipantId);
}
