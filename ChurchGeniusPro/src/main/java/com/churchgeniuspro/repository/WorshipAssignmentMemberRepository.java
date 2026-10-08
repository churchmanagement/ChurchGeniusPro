package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.WorshipAssignmentMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

@Repository
public interface WorshipAssignmentMemberRepository extends JpaRepository<WorshipAssignmentMember, Long> {
    List<WorshipAssignmentMember> findByAssignmentIdOrderBySortOrderAsc(Long assignmentId);
    List<WorshipAssignmentMember> findByAssignmentIdAndInstrumentId(Long assignmentId, Long instrumentId);

    /**
     * Bulk-fetch all assignment members for a set of assignment IDs in a single query.
     * Eliminates the N+1 pattern in buildAssignmentDetail when loading multiple assignments.
     */
    @Query("SELECT m FROM WorshipAssignmentMember m WHERE m.assignmentId IN :assignmentIds ORDER BY m.sortOrder ASC")
    List<WorshipAssignmentMember> findByAssignmentIdInOrderBySortOrderAsc(
            @Param("assignmentIds") Collection<Long> assignmentIds);

    @Modifying
    @Transactional
    @Query("DELETE FROM WorshipAssignmentMember m WHERE m.assignmentId = :assignmentId")
    void deleteByAssignmentId(@Param("assignmentId") Long assignmentId);

}
