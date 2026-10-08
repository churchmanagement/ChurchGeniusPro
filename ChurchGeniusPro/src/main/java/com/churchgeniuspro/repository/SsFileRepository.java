package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SsFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SsFileRepository extends JpaRepository<SsFile, Long> {
    List<SsFile> findByClassIdAndDeleteFlagFalseOrderByUploadedAtDesc(Long classId);

    /**
     * The documents a student of this class may see.
     *
     * <p>Written as explicit JPQL rather than a derived name: Spring Data resolves
     * derived finders at context startup, and a name it mis-parses takes the whole
     * application down on boot rather than failing a test.
     */
    @org.springframework.data.jpa.repository.Query("""
           SELECT f FROM SsFile f
            WHERE f.classId = :classId
              AND f.deleteFlag = false
              AND f.sharedWithStudents = true
            ORDER BY f.uploadedAt DESC
           """)
    List<SsFile> findSharedForClass(@org.springframework.data.repository.query.Param("classId") Long classId);
}
