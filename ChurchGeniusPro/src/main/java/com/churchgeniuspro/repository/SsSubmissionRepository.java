package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SsSubmission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SsSubmissionRepository extends JpaRepository<SsSubmission, Long> {
    Optional<SsSubmission> findByExamIdAndStudentIdAndDeleteFlagFalse(Long examId, Long studentId);
    List<SsSubmission> findByExamIdAndDeleteFlagFalse(Long examId);
    List<SsSubmission> findByStudentIdAndDeleteFlagFalse(Long studentId);

    /** Immediately hard-deletes any submission for this (examId, studentId) pair regardless of deleteFlag.
     *  Used before re-inserting after a republish, to avoid unique constraint violations from soft-deleted rows. */
    @Modifying
    @Query("DELETE FROM SsSubmission s WHERE s.examId = :examId AND s.studentId = :studentId")
    void deleteByExamIdAndStudentId(@Param("examId") Long examId, @Param("studentId") Long studentId);
}
