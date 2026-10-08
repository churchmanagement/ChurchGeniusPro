package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.GuessItGroupParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GuessItGroupParticipantRepository
        extends JpaRepository<GuessItGroupParticipant, Long> {

    /**
     * Name collision check within one group. {@code nameKey} is the case-folded
     * name, so "Sam" and "sam" are the same person for this purpose.
     */
    Optional<GuessItGroupParticipant> findByGroupIdAndNameKey(Long groupId, String nameKey);

    /** Resume a public participant from the token held in their browser. */
    Optional<GuessItGroupParticipant> findByToken(String token);

    /** Resume a logged-in member, who is matched by member id rather than token. */
    Optional<GuessItGroupParticipant> findByGroupIdAndMemberId(Long groupId, Integer memberId);

    /** Leaderboard order: most points first, then alphabetical for a stable tie order. */
    List<GuessItGroupParticipant> findByGroupIdOrderByScoreDescDisplayNameAsc(Long groupId);

    /** Everyone in a group, unordered — used for counts. */
    List<GuessItGroupParticipant> findByGroupId(Long groupId);
}
