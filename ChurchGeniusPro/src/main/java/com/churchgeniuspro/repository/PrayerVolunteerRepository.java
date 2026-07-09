package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.PrayerVolunteer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PrayerVolunteerRepository extends JpaRepository<PrayerVolunteer, Integer> {

    @Query("SELECT v FROM PrayerVolunteer v " +
           "WHERE v.clientId = :clientId AND v.deleteFlag = false " +
           "ORDER BY v.active DESC, v.id ASC")
    List<PrayerVolunteer> findByClient(@Param("clientId") String clientId);

    Optional<PrayerVolunteer> findByIdAndClientId(Integer id, String clientId);

    /**
     * Returns an existing (active or inactive) volunteer row for the given
     * family member, regardless of deleteFlag. Lets callers re-activate a
     * previously removed volunteer instead of creating a duplicate.
     */
    @Query("SELECT v FROM PrayerVolunteer v " +
           "WHERE v.clientId = :clientId AND v.familyMemberId = :familyMemberId")
    Optional<PrayerVolunteer> findByMember(@Param("clientId") String clientId,
                                           @Param("familyMemberId") Integer familyMemberId);
}
