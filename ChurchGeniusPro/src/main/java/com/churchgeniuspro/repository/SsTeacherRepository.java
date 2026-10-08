package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SsTeacher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SsTeacherRepository extends JpaRepository<SsTeacher, Long> {
    List<SsTeacher> findByClassIdAndDeleteFlagFalse(Long classId);
    List<SsTeacher> findByClientIdAndDeleteFlagFalse(String clientId);
    List<SsTeacher> findByMemberRefAndClientId(String memberRef, String clientId);
    // Tenant-scoped lookups (security audit, week 1)
    Optional<SsTeacher> findByIdAndClientIdAndDeleteFlagFalse(Long id, String clientId);
    List<SsTeacher> findByMemberRefAndClientIdAndDeleteFlagFalse(String memberRef, String clientId);
    List<SsTeacher> findByEmailAndClientIdAndDeleteFlagFalse(String email, String clientId);
}
