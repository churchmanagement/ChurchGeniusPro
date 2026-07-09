package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SsTeacher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface SsTeacherRepository extends JpaRepository<SsTeacher, Long> {
    List<SsTeacher> findByClassIdAndDeleteFlagFalse(Long classId);
    List<SsTeacher> findByClientIdAndDeleteFlagFalse(String clientId);
    List<SsTeacher> findByMemberRefAndClientId(String memberRef, String clientId);
    List<SsTeacher> findByMemberRefAndDeleteFlagFalse(String memberRef);
    List<SsTeacher> findByEmailAndDeleteFlagFalse(String email);
}
