package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PrayerRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link PrayerRequest} entities.
 */
@Repository
public interface PrayerRequestRepository extends JpaRepository<PrayerRequest, Long> {

    /** All non-deleted requests for a given organization, oldest first. */
    List<PrayerRequest> findByClientIdAndDeleteFlagFalseOrderByCreatedAtAsc(String clientId);

    /** All non-deleted requests under a specific section, oldest first. */
    List<PrayerRequest> findBySectionIdAndDeleteFlagFalseOrderByCreatedAtAsc(Long sectionId);

    /** Count non-deleted requests under a section (used before deleting a section). */
    long countBySectionIdAndDeleteFlagFalse(Long sectionId);

    /**
     * All non-deleted, scheduled prayer requests for a given org that have a non-null occurrence.
     * Used by the prayer reminder scheduler (type 15).
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT r FROM PrayerRequest r WHERE r.clientId = :clientId " +
        "AND r.deleteFlag = false AND r.occurrence IS NOT NULL AND r.status = 'Active'")
    List<PrayerRequest> findScheduledByClientId(@org.springframework.data.repository.query.Param("clientId") String clientId);
}
