package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.StripeSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StripeSettingsRepository extends JpaRepository<StripeSettings, Long> {

    /** Retrieve the single Stripe-settings record for an organization (absent if never saved). */
    Optional<StripeSettings> findByClientId(String clientId);
}
