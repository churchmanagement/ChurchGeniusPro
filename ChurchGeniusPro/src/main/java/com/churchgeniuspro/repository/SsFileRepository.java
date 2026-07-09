package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SsFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SsFileRepository extends JpaRepository<SsFile, Long> {
    List<SsFile> findByClassIdAndDeleteFlagFalseOrderByUploadedAtDesc(Long classId);
}
