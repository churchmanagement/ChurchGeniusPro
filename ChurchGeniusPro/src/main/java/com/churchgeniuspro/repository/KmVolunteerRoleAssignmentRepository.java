package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.KmVolunteerRoleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

public interface KmVolunteerRoleAssignmentRepository extends JpaRepository<KmVolunteerRoleAssignment, Long> {
    List<KmVolunteerRoleAssignment> findByClientIdAndVolunteerIdAndDeleteFlagFalse(String clientId, Long volunteerId);
    List<KmVolunteerRoleAssignment> findByClientIdAndDeleteFlagFalse(String clientId);

    @Transactional
    void deleteByClientIdAndVolunteerId(String clientId, Long volunteerId);
}
