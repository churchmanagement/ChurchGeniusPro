package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.KmVolunteer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface KmVolunteerRepository extends JpaRepository<KmVolunteer, Long> {

    /** Tenant-scoped lookup for upsert-by-id handlers. */
    Optional<KmVolunteer> findByIdAndClientId(Long id, String clientId);

    List<KmVolunteer> findByClientIdAndDeleteFlagFalseOrderByNameAsc(String clientId);

    List<KmVolunteer> findByClientIdAndClassroomIdAndDeleteFlagFalseAndInactiveFalse(
            String clientId, Long classroomId);
}
