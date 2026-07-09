package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SsAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SsAnswerRepository extends JpaRepository<SsAnswer, Long> {
    List<SsAnswer> findBySubmissionId(Long submissionId);
    Optional<SsAnswer> findBySubmissionIdAndQuestionId(Long submissionId, Long questionId);

    /** Immediately deletes all answers for a submission — bypasses Hibernate action queue. */
    @Modifying
    @Query("DELETE FROM SsAnswer a WHERE a.submissionId = :submissionId")
    void deleteBySubmissionId(@Param("submissionId") Long submissionId);

    /** Deletes all answers whose submission belongs to the given (examId, studentId) pair.
     *  Covers both active and soft-deleted submissions. Used during republish cleanup. */
    @Modifying
    @Query("DELETE FROM SsAnswer a WHERE a.submissionId IN " +
           "(SELECT s.id FROM SsSubmission s WHERE s.examId = :examId AND s.studentId = :studentId)")
    void deleteByExamIdAndStudentId(@Param("examId") Long examId, @Param("studentId") Long studentId);
}
