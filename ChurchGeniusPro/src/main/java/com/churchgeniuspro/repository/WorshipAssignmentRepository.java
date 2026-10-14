package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.WorshipAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorshipAssignmentRepository extends JpaRepository<WorshipAssignment, Long> {
    List<WorshipAssignment> findByClientIdAndDeleteFlagFalseOrderByAssignmentDateAsc(String clientId);
    List<WorshipAssignment> findByClientIdAndAssignmentDateBetweenAndDeleteFlagFalse(
            String clientId, LocalDate from, LocalDate to);

    /**
     * Finds the first active assignment for a (client, group, date) tuple regardless
     * of workflow. Kept for callers that pre-date the per-workflow key; the upsert in
     * {@code WorshipPlanningController} now uses the type-aware variant below.
     */
    Optional<WorshipAssignment> findFirstByClientIdAndGroupIdAndAssignmentDateAndDeleteFlagFalse(
            String clientId, Long groupId, LocalDate date);

    /**
     * The one active row of a (client, group, date, workflow) key — Manual and
     * Automatic Assignment each own their own row for the same group and date.
     * Uniqueness is enforced by the partial index {@code ux_worship_assignment_active}.
     */
    Optional<WorshipAssignment> findFirstByClientIdAndGroupIdAndAssignmentDateAndAssignmentTypeAndDeleteFlagFalse(
            String clientId, Long groupId, LocalDate date, String assignmentType);

    /** Active rows of ONE workflow in a date range (the Manual tab reads only its own rows). */
    List<WorshipAssignment> findByClientIdAndAssignmentTypeAndAssignmentDateBetweenAndDeleteFlagFalse(
            String clientId, String assignmentType, LocalDate from, LocalDate to);
}
