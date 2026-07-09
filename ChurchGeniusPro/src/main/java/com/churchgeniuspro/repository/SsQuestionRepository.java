package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SsQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SsQuestionRepository extends JpaRepository<SsQuestion, Long> {
    List<SsQuestion> findByExamIdAndDeleteFlagFalseOrderBySortOrderAsc(Long examId);
    /** Includes soft-deleted — used for matching historical student answers after re-upload. */
    List<SsQuestion> findByExamIdOrderBySortOrderAsc(Long examId);
    void deleteByExamId(Long examId);
}
