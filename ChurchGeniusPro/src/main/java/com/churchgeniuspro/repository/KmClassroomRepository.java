package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.KmClassroom;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface KmClassroomRepository extends JpaRepository<KmClassroom, Long> {

    /** Tenant-scoped lookup for upsert-by-id handlers. */
    Optional<KmClassroom> findByIdAndClientId(Long id, String clientId);

    List<KmClassroom> findByClientIdAndDeleteFlagFalseOrderByMinAgeAscClassNameAsc(String clientId);
}
