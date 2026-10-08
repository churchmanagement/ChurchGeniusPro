package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.VolunteerRole;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface VolunteerRoleRepository extends JpaRepository<VolunteerRole, Long> {

    /** Tenant-scoped lookup for upsert-by-id and FK validation. */
    Optional<VolunteerRole> findByIdAndAppClientId(Long id, String appClientId);

    List<VolunteerRole> findByAppClientIdAndDeleteFlagFalseOrderByMinistryAscRoleNameAsc(String appClientId);
}
