package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.ChurchEventDay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link ChurchEventDay} entities.
 */
@Repository
public interface ChurchEventDayRepository extends JpaRepository<ChurchEventDay, Integer> {

    /** All day records for an event, ordered by day number. */
    List<ChurchEventDay> findByEventIdOrderByDayOrderAsc(Integer eventId);

    /** Removes all day records for an event (used before re-saving updated days). */
    void deleteByEventId(Integer eventId);
}
