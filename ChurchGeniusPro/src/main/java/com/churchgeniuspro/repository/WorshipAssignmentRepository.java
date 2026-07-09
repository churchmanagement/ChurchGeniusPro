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
     * Finds the first active assignment for a (client, group, date) tuple.
     * Uses findFirst to tolerate any pre-existing duplicate rows.
     * A UNIQUE constraint on the entity prevents new duplicates.
     */
    Optional<WorshipAssignment> findFirstByClientIdAndGroupIdAndAssignmentDateAndDeleteFlagFalse(
            String clientId, Long groupId, LocalDate date);
}
