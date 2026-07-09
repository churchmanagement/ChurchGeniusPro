package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PrayerNote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PrayerNoteRepository extends JpaRepository<PrayerNote, Integer> {

    /** Notes on one prayer request, oldest first so the UI renders a chronological thread. */
    @Query("SELECT n FROM PrayerNote n " +
           "WHERE n.prayerRequestId = :requestId AND n.clientId = :clientId AND n.deleteFlag = false " +
           "ORDER BY n.createdDate ASC, n.id ASC")
    List<PrayerNote> findByRequest(@Param("clientId") String clientId,
                                   @Param("requestId") Long requestId);

    Optional<PrayerNote> findByIdAndClientId(Integer id, String clientId);
}
