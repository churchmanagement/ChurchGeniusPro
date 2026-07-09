package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SsExam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SsExamRepository extends JpaRepository<SsExam, Long> {
    List<SsExam> findByClassIdAndDeleteFlagFalseOrderByIdDesc(Long classId);
    List<SsExam> findByClientIdAndDeleteFlagFalse(String clientId);
}
