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
}
