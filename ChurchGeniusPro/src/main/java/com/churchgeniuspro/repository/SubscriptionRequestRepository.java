package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SubscriptionRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface SubscriptionRequestRepository extends JpaRepository<SubscriptionRequest, Long> {

    List<SubscriptionRequest> findAllByOrderByCreatedAtDesc();

    /** The church's open (NEW / IN_PROGRESS) request, newest first. */
    List<SubscriptionRequest> findByClientIdAndStatusInOrderByCreatedAtDesc(String clientId, Collection<String> statuses);

    /**
     * Moves a request from one status to another only if it is still in {@code from}
     * — the atomic claim that makes two admins / a double click safe. 1 = this caller won.
     */
    @Modifying
    @Transactional
    @Query("UPDATE SubscriptionRequest r SET r.status = :to, r.decidedBy = :actor, r.decidedAt = :at "
         + "WHERE r.id = :id AND r.status IN :from")
    int transition(@Param("id") Long id, @Param("from") Collection<String> from, @Param("to") String to,
                   @Param("actor") String actor, @Param("at") LocalDateTime at);
}
