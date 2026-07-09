package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.EmailSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EmailSettingsRepository extends JpaRepository<EmailSettings, Long> {

    /** Retrieve the single settings record for an organization (may be absent if never saved). */
    Optional<EmailSettings> findByClientId(String clientId);
}
