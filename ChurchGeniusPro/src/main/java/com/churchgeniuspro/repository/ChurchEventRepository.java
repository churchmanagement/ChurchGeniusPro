package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.ChurchEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link ChurchEvent} entities.
 */
@Repository
public interface ChurchEventRepository extends JpaRepository<ChurchEvent, Integer> {

    /** All non-deleted events for a given organization, newest first. */
    List<ChurchEvent> findByAppClientIdAndDeleteFlagFalseOrderByCreatedDateDesc(String appClientId);

    /** Single non-deleted event by id. */
    Optional<ChurchEvent> findByIdAndDeleteFlagFalse(Integer id);

    /** Look up a non-deleted event by its unique event code (e.g. EVT-A3B7C2). */
    Optional<ChurchEvent> findByEventCodeAndDeleteFlagFalse(String eventCode);

    /**
     * Uniqueness check for a manually entered Event ID / Code.
     *
     * <p>Case-insensitive on purpose: {@code evt-a3b7c2} and {@code EVT-A3B7C2}
     * are the same code to the person typing it, and letting both exist makes
     * the code useless as a search key.
     */
    Optional<ChurchEvent> findFirstByEventCodeIgnoreCaseAndDeleteFlagFalse(String eventCode);

    /** All non-deleted events across all orgs (used by EventCalendarController in unauthenticated public context). */
    List<ChurchEvent> findAllByDeleteFlagFalseOrderByCreatedDateDesc();

    /** Events with the Registration Reminder feature enabled (scanned by the scheduled reminder job). */
    List<ChurchEvent> findByRegistrationReminderEnabledTrueAndDeleteFlagFalse();

    // ── Tenant-scoped lookups (security audit, week 1) ─────────────────────

    java.util.Optional<ChurchEvent> findByIdAndAppClientIdAndDeleteFlagFalse(Integer id, String appClientId);
    java.util.Optional<ChurchEvent> findByPublicTokenAndDeleteFlagFalse(String publicToken);

    /**
     * AI Search: events with a day inside the range — one-day events by event_date,
     * multi-day events by any of their church_event_day rows. Rows of
     * [id, event_name, event_type, first_date_in_range, start_time], soonest first, bounded.
     */
    @org.springframework.data.jpa.repository.Query(value =
           "SELECT e.id, e.event_name, e.event_type, " +
           "       COALESCE(MIN(d.event_date), e.event_date) AS first_date, e.start_time " +
           "FROM   church_event e " +
           "LEFT JOIN church_event_day d ON d.event_id = e.id AND d.event_date BETWEEN :fromDate AND :toDate " +
           "WHERE  e.app_client_id = :appClientId AND e.delete_flag = false " +
           "  AND  ((e.event_date BETWEEN :fromDate AND :toDate) OR d.event_date IS NOT NULL) " +
           "GROUP  BY e.id, e.event_name, e.event_type, e.event_date, e.start_time " +
           "ORDER  BY first_date ASC, e.id ASC LIMIT :limit",
           nativeQuery = true)
    List<Object[]> aiUpcomingEvents(@org.springframework.data.repository.query.Param("appClientId") String appClientId,
                                    @org.springframework.data.repository.query.Param("fromDate") java.sql.Date fromDate,
                                    @org.springframework.data.repository.query.Param("toDate") java.sql.Date toDate,
                                    @org.springframework.data.repository.query.Param("limit") int limit);

}
