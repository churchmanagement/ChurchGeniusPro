package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.Donation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DonationRepository extends JpaRepository<Donation, Long> {

    /** All donations for an organization, newest first. */
    List<Donation> findByClientIdOrderByDonatedAtDesc(String clientId);

    /** Idempotency check — prevents double-saving the same PaymentIntent. */
    Optional<Donation> findByStripePaymentIntentId(String stripePaymentIntentId);
}
