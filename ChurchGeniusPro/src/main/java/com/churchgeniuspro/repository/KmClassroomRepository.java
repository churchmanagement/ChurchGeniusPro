package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.KmClassroom;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KmClassroomRepository extends JpaRepository<KmClassroom, Long> {

    List<KmClassroom> findByClientIdAndDeleteFlagFalseOrderByMinAgeAscClassNameAsc(String clientId);
}
