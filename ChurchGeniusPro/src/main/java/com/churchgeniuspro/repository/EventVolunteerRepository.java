package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.EventVolunteer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventVolunteerRepository extends JpaRepository<EventVolunteer, Long> {

    List<EventVolunteer> findByAppClientIdAndEventIdAndDeleteFlagFalseOrderByCreatedAtAsc(
            String appClientId, Integer eventId);

    List<EventVolunteer> findByAppClientIdAndDeleteFlagFalseOrderByEventIdAscCreatedAtAsc(
            String appClientId);

    List<EventVolunteer> findByAppClientIdAndFamilyMemberIdAndDeleteFlagFalseOrderByCreatedAtDesc(
            String appClientId, Integer familyMemberId);
}
