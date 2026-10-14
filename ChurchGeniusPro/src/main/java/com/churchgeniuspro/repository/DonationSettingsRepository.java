package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.DonationSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DonationSettingsRepository extends JpaRepository<DonationSettings, Long> {

    /** The church's Donation Settings — at most one row per church (V14 unique index). */
    Optional<DonationSettings> findByClientId(String clientId);
}
