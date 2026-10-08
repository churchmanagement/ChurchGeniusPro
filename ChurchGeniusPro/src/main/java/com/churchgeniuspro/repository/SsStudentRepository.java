package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SsStudent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface SsStudentRepository extends JpaRepository<SsStudent, Long> {
    List<SsStudent> findByClassIdAndDeleteFlagFalse(Long classId);
    List<SsStudent> findByTeacherIdAndDeleteFlagFalse(Long teacherId);
    List<SsStudent> findByClientIdAndDeleteFlagFalse(String clientId);
    Optional<SsStudent> findByMemberRefAndClientIdAndDeleteFlagFalse(String memberRef, String clientId);
    Optional<SsStudent> findByFamilyMemberIdAndClientIdAndDeleteFlagFalse(Integer familyMemberId, String clientId);
    List<SsStudent> findByMemberRefAndDeleteFlagFalse(String memberRef);
    List<SsStudent> findByFamilyMemberIdAndDeleteFlagFalse(Integer familyMemberId);
    // Strategy 5: match by contact email within the same org
    List<SsStudent> findByContactEmailAndClientIdAndDeleteFlagFalse(String contactEmail, String clientId);
    List<SsStudent> findByContactEmailAndDeleteFlagFalse(String contactEmail);
    // Tenant-scoped lookups (security audit, week 1)
    Optional<SsStudent> findByIdAndClientIdAndDeleteFlagFalse(Long id, String clientId);
    List<SsStudent> findByFamilyMemberIdInAndClientIdAndDeleteFlagFalse(java.util.Collection<Integer> familyMemberIds, String clientId);
    List<SsStudent> findByContactEmailIgnoreCaseAndClientIdAndDeleteFlagFalse(String contactEmail, String clientId);
}
