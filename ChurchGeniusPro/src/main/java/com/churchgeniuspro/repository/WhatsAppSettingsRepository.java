package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.WhatsAppSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WhatsAppSettingsRepository extends JpaRepository<WhatsAppSettings, Long> {

    /** Retrieve the single WhatsApp-settings record for an organization. */
    Optional<WhatsAppSettings> findByClientId(String clientId);
}
