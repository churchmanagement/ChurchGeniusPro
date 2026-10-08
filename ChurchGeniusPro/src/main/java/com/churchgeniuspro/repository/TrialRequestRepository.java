package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.TrialRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TrialRequestRepository extends JpaRepository<TrialRequest, Long> {

    Optional<TrialRequest> findByReference(String reference);

    boolean existsByReference(String reference);

    List<TrialRequest> findAllByOrderByCreatedAtDesc();

    /** The request a registration link was issued for (Phase C: tenant ↔ request). */
    Optional<TrialRequest> findFirstByTrialLinkId(Integer trialLinkId);

    /**
     * The open requests (awaiting verification or approval) for an email address.
     * Emails are stored trimmed and lower-cased, so callers pass the same form.
     */
    List<TrialRequest> findByEmailAndStatusInOrderByCreatedAtDesc(String email, java.util.Collection<String> statuses);

    /**
     * Marks unverified requests created before {@code cutoff} as VERIFICATION_EXPIRED.
     * Run before every submit and every admin listing, so the 24-hour rule needs no
     * scheduler. Returns the number of requests expired.
     */
    @Modifying
    @Transactional
    @Query("UPDATE TrialRequest t SET t.status = 'VERIFICATION_EXPIRED' "
         + "WHERE t.status = 'PENDING_VERIFICATION' AND t.createdAt < :cutoff")
    int expireUnverified(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Moves a request from one status to another only if it is still in {@code from}
     * — the atomic claim that makes Approve/Reject safe against a double click or two
     * admins at once. Returns 1 when this caller won, 0 otherwise.
     */
    @Modifying
    @Transactional
    @Query("UPDATE TrialRequest t SET t.status = :to, t.decidedBy = :actor, t.decidedAt = :at "
         + "WHERE t.id = :id AND t.status = :from")
    int transition(@Param("id") Long id, @Param("from") String from, @Param("to") String to,
                   @Param("actor") String actor, @Param("at") LocalDateTime at);
}
