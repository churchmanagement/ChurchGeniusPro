package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PrayerSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link PrayerSchedule} entities.
 *
 * <p>Each organization has at most one row in {@code prayer_schedule}.</p>
 */
@Repository
public interface PrayerScheduleRepository extends JpaRepository<PrayerSchedule, Long> {

    /** Finds the global prayer schedule for a given organization. */
    Optional<PrayerSchedule> findByClientId(String clientId);
}
