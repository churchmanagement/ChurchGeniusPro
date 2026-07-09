package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.MidRegMeet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MidRegMeetRepository extends JpaRepository<MidRegMeet, Long> {
    Optional<MidRegMeet> findByClientId(String clientId);
}
