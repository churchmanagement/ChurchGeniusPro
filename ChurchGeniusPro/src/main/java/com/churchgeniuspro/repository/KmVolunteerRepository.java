package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.KmVolunteer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KmVolunteerRepository extends JpaRepository<KmVolunteer, Long> {

    List<KmVolunteer> findByClientIdAndDeleteFlagFalseOrderByNameAsc(String clientId);

    List<KmVolunteer> findByClientIdAndClassroomIdAndDeleteFlagFalseAndInactiveFalse(
            String clientId, Long classroomId);
}
