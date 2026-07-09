package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.EventRegistrationReminderContact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link EventRegistrationReminderContact} entities.
 */
@Repository
public interface EventRegistrationReminderContactRepository
        extends JpaRepository<EventRegistrationReminderContact, Integer> {

    /** All reminder contacts configured for an event, oldest first. */
    List<EventRegistrationReminderContact> findByEventIdOrderByIdAsc(Integer eventId);

    /** Removes all reminder contacts for an event (used before re-saving the updated list). */
    void deleteByEventId(Integer eventId);
}
