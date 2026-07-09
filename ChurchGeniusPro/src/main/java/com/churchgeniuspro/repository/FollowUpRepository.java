package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.FollowUp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link FollowUp} entities.
 */
@Repository
public interface FollowUpRepository extends JpaRepository<FollowUp, Long> {

    /** All active (non-deleted) follow-ups for an org, newest first. */
    List<FollowUp> findByClientIdAndDeleteFlagFalseOrderByCreatedAtDesc(String clientId);

    /** Active follow-ups for an org filtered by status. */
    List<FollowUp> findByClientIdAndStatusAndDeleteFlagFalseOrderByCreatedAtDesc(
            String clientId, String status);

    /** Active follow-ups assigned to a specific user. */
    List<FollowUp> findByClientIdAndAssignedToAndDeleteFlagFalseOrderByCreatedAtDesc(
            String clientId, String assignedTo);

    /** Active follow-ups for a specific user, optionally filtered by status. */
    List<FollowUp> findByClientIdAndAssignedToAndStatusAndDeleteFlagFalseOrderByCreatedAtDesc(
            String clientId, String assignedTo, String status);

    /** Single active follow-up by id and org. */
    Optional<FollowUp> findByIdAndClientIdAndDeleteFlagFalse(Long id, String clientId);

    /**
     * Marks all PENDING follow-ups whose due date has passed (before today) as MISSED,
     * across all orgs. Called daily by the scheduler.
     */
    @Modifying
    @Transactional
    @Query("UPDATE FollowUp f SET f.status = 'MISSED', f.updatedAt = :now " +
           "WHERE f.status = 'PENDING' AND f.deleteFlag = false AND f.dueDate < :today")
    int markOverdueMissed(@Param("today") Date today, @Param("now") Date now);

    /** Follow-ups linked to a specific record (e.g. prayer request id). */
    List<FollowUp> findByClientIdAndLinkedTypeAndLinkedIdAndDeleteFlagFalseOrderByCreatedAtDesc(
            String clientId, String linkedType, Long linkedId);

    /** Count of PENDING follow-ups for an org (for dashboard widget). */
    long countByClientIdAndStatusAndDeleteFlagFalse(String clientId, String status);
}
