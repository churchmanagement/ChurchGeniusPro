package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PrayerRequestVolunteer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface PrayerRequestVolunteerRepository extends JpaRepository<PrayerRequestVolunteer, Integer> {

    /** Every volunteer currently assigned to a request. */
    @Query("SELECT prv FROM PrayerRequestVolunteer prv " +
           "WHERE prv.clientId = :clientId AND prv.prayerRequestId = :requestId " +
           "ORDER BY prv.id ASC")
    List<PrayerRequestVolunteer> findByRequest(@Param("clientId") String clientId,
                                               @Param("requestId") Long requestId);

    /** Lookup used to enforce dedupe on add. */
    Optional<PrayerRequestVolunteer> findByPrayerRequestIdAndPrayerVolunteerId(
            Long prayerRequestId, Integer prayerVolunteerId);

    /** Hard-delete the join row when a volunteer is removed from a request. */
    @Modifying
    @Transactional
    @Query("DELETE FROM PrayerRequestVolunteer prv " +
           "WHERE prv.clientId = :clientId AND prv.prayerRequestId = :requestId " +
           "AND prv.prayerVolunteerId = :volunteerId")
    int deleteAssignment(@Param("clientId") String clientId,
                         @Param("requestId") Long requestId,
                         @Param("volunteerId") Integer volunteerId);

    /** Used by deleteVolunteer to scrub any assignments before soft-deleting the volunteer. */
    @Modifying
    @Transactional
    @Query("DELETE FROM PrayerRequestVolunteer prv " +
           "WHERE prv.clientId = :clientId AND prv.prayerVolunteerId = :volunteerId")
    int deleteAllForVolunteer(@Param("clientId") String clientId,
                              @Param("volunteerId") Integer volunteerId);
}
