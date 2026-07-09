package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.VolunteerRole;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface VolunteerRoleRepository extends JpaRepository<VolunteerRole, Long> {

    List<VolunteerRole> findByAppClientIdAndDeleteFlagFalseOrderByMinistryAscRoleNameAsc(String appClientId);
}
