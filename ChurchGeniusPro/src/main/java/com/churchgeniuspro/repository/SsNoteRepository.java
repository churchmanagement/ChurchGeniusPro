package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SsNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SsNoteRepository extends JpaRepository<SsNote, Long> {
    List<SsNote> findByClassIdAndDeleteFlagFalseOrderByCreatedAtDesc(Long classId);
}
