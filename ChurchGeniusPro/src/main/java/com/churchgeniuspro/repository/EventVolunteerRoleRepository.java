package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.EventVolunteerRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventVolunteerRoleRepository extends JpaRepository<EventVolunteerRole, Long> {

    List<EventVolunteerRole> findByAppClientIdAndEventVolunteerIdAndDeleteFlagFalseOrderByIdAsc(
            String appClientId, Long eventVolunteerId);

    List<EventVolunteerRole> findByAppClientIdAndEventVolunteerIdInAndDeleteFlagFalseOrderByIdAsc(
            String appClientId, List<Long> eventVolunteerIds);

    void deleteByEventVolunteerId(Long eventVolunteerId);
}
