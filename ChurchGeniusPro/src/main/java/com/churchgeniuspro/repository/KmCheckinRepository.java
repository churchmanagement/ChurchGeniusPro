package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.KmCheckin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface KmCheckinRepository extends JpaRepository<KmCheckin, Long> {

    /** All check-ins for a client (for attendance reports) */
    @Query("SELECT c FROM KmCheckin c WHERE c.clientId = :clientId " +
           "AND c.checkinTime >= :from ORDER BY c.checkinTime DESC")
    List<KmCheckin> findByClientIdSince(@Param("clientId") String clientId,
                                        @Param("from") LocalDateTime from);

    /** Active check-ins (not yet checked out) for today */
    @Query("SELECT c FROM KmCheckin c WHERE c.clientId = :clientId " +
           "AND c.checkoutTime IS NULL AND c.checkinTime >= :from " +
           "ORDER BY c.checkinTime ASC")
    List<KmCheckin> findActiveCheckins(@Param("clientId") String clientId,
                                       @Param("from") LocalDateTime from);

    /** Active check-in for a specific child */
    @Query("SELECT c FROM KmCheckin c WHERE c.clientId = :clientId " +
           "AND c.childId = :childId AND c.checkoutTime IS NULL " +
           "ORDER BY c.checkinTime DESC")
    List<KmCheckin> findActiveForChild(@Param("clientId") String clientId,
                                       @Param("childId") Long childId);

    /** Check-ins within an explicit date range (history reporting). */
    @Query("SELECT c FROM KmCheckin c WHERE c.clientId = :clientId " +
           "AND c.checkinTime >= :from AND c.checkinTime < :to ORDER BY c.checkinTime DESC")
    List<KmCheckin> findByClientIdInRange(@Param("clientId") String clientId,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);

    /** Most recent check-in for a child regardless of status (child detail panel). */
    Optional<KmCheckin> findFirstByClientIdAndChildIdOrderByCheckinTimeDesc(String clientId, Long childId);

    /** All currently checked-in rows for a tenant (no checkout), newest first. Pickup dashboard + child status. */
    List<KmCheckin> findByClientIdAndCheckoutTimeIsNullOrderByCheckinTimeDesc(String clientId);

    /** Look up by security code for checkout verification */
    Optional<KmCheckin> findByClientIdAndSecurityCodeAndCheckoutTimeIsNull(
            String clientId, String securityCode);

    long countByClientIdAndClassroomIdAndCheckoutTimeIsNull(String clientId, Long classroomId);

    /**
     * All rows that share the same familyCheckinCode — i.e. the kid +
     * guardians submitted together via the public /kidsCheckin page. Used
     * by the Children-page expander to render a unified family group.
     */
    @Query("SELECT c FROM KmCheckin c WHERE c.clientId = :clientId " +
           "AND c.familyCheckinCode = :code ORDER BY c.checkinTime ASC")
    List<KmCheckin> findByClientIdAndFamilyCheckinCode(
            @Param("clientId") String clientId,
            @Param("code") String code);

    /**
     * Rows whose printed/scanned code matches EITHER the shared family check-in
     * code or the per-row security code. Used by the barcode-scan lookup so it
     * works regardless of which check-in path created the row (the staff-side
     * check-in sets only securityCode, the public page sets both).
     */
    @Query("SELECT c FROM KmCheckin c WHERE c.clientId = :clientId " +
           "AND (c.familyCheckinCode = :code OR c.securityCode = :code) " +
           "ORDER BY c.checkinTime ASC")
    List<KmCheckin> findByClientIdAndAnyCode(
            @Param("clientId") String clientId,
            @Param("code") String code);

    /** The open check-in behind a pickup-link token (checkout clears the token). */
    java.util.Optional<KmCheckin> findByPickupTokenAndCheckoutTimeIsNull(String pickupToken);
    java.util.Optional<KmCheckin> findByIdAndClientId(Long id, String clientId);

    /**
     * Claims this check-in for an overdue-pickup alert (data-integrity audit 2026-10-28, H1/H5).
     * Atomic conditional UPDATE: returns 1 for exactly one caller per re-alert window, 0 for anyone
     * else (a second instance, an overlapping run, a row alerted since it was listed). Touches only
     * {@code last_alert_sent_at} — the previous code merged a detached entity, which rewrote every
     * column and nulled the pickup token that had just been e-mailed.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE KmCheckin c SET c.lastAlertSentAt = :now " +
           "WHERE c.id = :id AND c.clientId = :clientId AND c.checkoutTime IS NULL " +
           "AND (c.lastAlertSentAt IS NULL OR c.lastAlertSentAt < :cutoff)")
    int claimOverdueAlert(@Param("id") Long id, @Param("clientId") String clientId,
                          @Param("now") LocalDateTime now, @Param("cutoff") LocalDateTime cutoff);

    /** Mints the pickup-link token once: only the first caller writes it (audit 2026-10-28, H1). */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("UPDATE KmCheckin c SET c.pickupToken = :token " +
           "WHERE c.id = :id AND c.clientId = :clientId AND (c.pickupToken IS NULL OR c.pickupToken = '')")
    int setPickupTokenIfAbsent(@Param("id") Long id, @Param("clientId") String clientId, @Param("token") String token);
}
