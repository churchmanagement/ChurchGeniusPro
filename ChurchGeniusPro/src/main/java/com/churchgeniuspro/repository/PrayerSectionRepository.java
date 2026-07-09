package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PrayerSection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link PrayerSection} entities.
 */
@Repository
public interface PrayerSectionRepository extends JpaRepository<PrayerSection, Long> {

    /** All non-deleted sections for a given organization, newest first. */
    List<PrayerSection> findByClientIdAndDeleteFlagFalseOrderByCreatedAtAsc(String clientId);
}
