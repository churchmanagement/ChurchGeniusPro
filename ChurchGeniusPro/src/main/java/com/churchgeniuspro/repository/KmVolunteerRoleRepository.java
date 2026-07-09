package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.KmVolunteerRole;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KmVolunteerRoleRepository extends JpaRepository<KmVolunteerRole, Long> {
    List<KmVolunteerRole> findByClientIdAndDeleteFlagFalseOrderByRoleNameAsc(String clientId);
}
