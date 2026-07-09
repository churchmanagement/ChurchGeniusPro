package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.MidRegMeetRsvp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MidRegMeetRsvpRepository extends JpaRepository<MidRegMeetRsvp, Long> {
    List<MidRegMeetRsvp> findByClientIdOrderByCreatedAtDesc(String clientId);
    Optional<MidRegMeetRsvp> findByEditToken(String editToken);
}
