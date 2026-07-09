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

    /** All non-deleted events across all orgs (used by EventCalendarController in unauthenticated public context). */
    List<ChurchEvent> findAllByDeleteFlagFalseOrderByCreatedDateDesc();

    /** Events with the Registration Reminder feature enabled (scanned by the scheduled reminder job). */
    List<ChurchEvent> findByRegistrationReminderEnabledTrueAndDeleteFlagFalse();
}
