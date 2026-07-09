package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.SmsOptIn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SmsOptInRepository extends JpaRepository<SmsOptIn, Integer> {

    /** Find an opt-in record by phone + church. */
    Optional<SmsOptIn> findByPhoneNumberAndAppClientId(String phoneNumber, String appClientId);

    /** All fully opted-in records for a church (safe to send SMS to). */
    List<SmsOptIn> findByAppClientIdAndConsentTrueAndConfirmedTrue(String appClientId);

    /** All records for a church (for admin view). */
    List<SmsOptIn> findByAppClientIdOrderByCreatedAtDesc(String appClientId);

    /**
     * Webhook lookup — finds any opt-in record matching this phone number
     * regardless of church (Twilio sends raw phone; we find the record globally).
     * If a phone can opt into multiple churches this returns all of them.
     */
    List<SmsOptIn> findByPhoneNumber(String phoneNumber);

    /** Check consent status before sending — use this before any outbound SMS. */
    @Query("SELECT COUNT(s) > 0 FROM SmsOptIn s " +
           "WHERE s.phoneNumber = :phone AND s.appClientId = :clientId " +
           "AND s.consent = true AND s.confirmed = true")
    boolean isOptedIn(@Param("phone") String phoneNumber, @Param("clientId") String appClientId);
}
