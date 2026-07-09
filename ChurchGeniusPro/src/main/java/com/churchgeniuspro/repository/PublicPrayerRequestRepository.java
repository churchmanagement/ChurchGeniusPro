package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PublicPrayerRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PublicPrayerRequestRepository extends JpaRepository<PublicPrayerRequest, Long> {
    List<PublicPrayerRequest> findByClientIdAndDeleteFlagFalseOrderByCreatedAtDesc(String clientId);
    List<PublicPrayerRequest> findByClientIdAndSourceAndDeleteFlagFalseOrderByCreatedAtDesc(String clientId, String source);
    Optional<PublicPrayerRequest> findByIdAndClientIdAndDeleteFlagFalse(Long id, String clientId);
}
