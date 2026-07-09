package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SsLesson;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SsLessonRepository extends JpaRepository<SsLesson, Long> {
    List<SsLesson> findByStudentIdAndDeleteFlagFalseOrderBySortOrderAsc(Long studentId);
    List<SsLesson> findByClassIdAndDeleteFlagFalse(Long classId);
}
