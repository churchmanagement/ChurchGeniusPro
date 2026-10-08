package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.EventRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link EventRegistration} entities.
 */
@Repository
public interface EventRegistrationRepository extends JpaRepository<EventRegistration, Integer> {

    /** All registrations for an event, oldest first. */
    List<EventRegistration> findByEventIdOrderByCreatedDateAsc(Integer eventId);

    /** Only registrations where the person confirmed attendance (attending = true). */
    List<EventRegistration> findByEventIdAndAttendingTrue(Integer eventId);

    /**
     * Registrations to notify for event reminders: those who RSVP'd "Yes"
     * ({@code attending = true}) or "Maybe" ({@code attending IS NULL}), but NOT
     * "No" ({@code attending = false}). A derived "AttendingNotFalse" query cannot
     * be used because SQL three-valued logic would drop the NULL (Maybe) rows.
     */
    @Query("SELECT r FROM EventRegistration r " +
           "WHERE r.eventId = :eventId AND (r.attending IS NULL OR r.attending = true)")
    List<EventRegistration> findRemindableByEventId(@Param("eventId") Integer eventId);

    /** Returns true if an email is already registered for a given event (case-insensitive). */
    boolean existsByEventIdAndEmailIgnoreCase(Integer eventId, String email);

    /** Find the registration for a given event + email (for RSVP update). */
    Optional<EventRegistration> findByEventIdAndEmailIgnoreCase(Integer eventId, String email);

    /** Find a registration for a given event by phone (for upsert / dedup by phone). */
    Optional<EventRegistration> findFirstByEventIdAndPhone(Integer eventId, String phone);

    /** Most-recent registration for a tenant matched by email (for auto-fill across the client's events). */
    Optional<EventRegistration> findFirstByClientIdAndEmailIgnoreCaseOrderByCreatedDateDesc(String clientId, String email);

    /** Most-recent registration for a tenant matched by phone (for auto-fill across the client's events). */
    Optional<EventRegistration> findFirstByClientIdAndPhoneOrderByCreatedDateDesc(String clientId, String phone);

    /** Find a registration by its unique registration code (for QR / self check-in). */
    Optional<EventRegistration> findByRegistrationCode(String registrationCode);

    /** Sum of all adult counts registered for an event. */
    @Query("SELECT COALESCE(SUM(r.adults), 0) FROM EventRegistration r WHERE r.eventId = :eventId")
    long sumAdultsByEventId(@Param("eventId") Integer eventId);

    /** Sum of all kids counts registered for an event. */
    @Query("SELECT COALESCE(SUM(r.kids), 0) FROM EventRegistration r WHERE r.eventId = :eventId")
    long sumKidsByEventId(@Param("eventId") Integer eventId);

    /** Count of checked-in registrations for an event. */
    long countByEventIdAndCheckedInTrue(Integer eventId);

    /** Count of all registrations for an event. */
    long countByEventId(Integer eventId);

    // ── Tenant-scoped lookups (security audit, week 1) ─────────────────────

    java.util.Optional<EventRegistration> findByIdAndClientId(Integer id, String clientId);
}
