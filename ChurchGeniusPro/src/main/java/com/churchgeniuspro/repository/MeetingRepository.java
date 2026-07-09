package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.Meeting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link Meeting} entities.
 */
@Repository
public interface MeetingRepository extends JpaRepository<Meeting, Integer> {

    /**
     * All non-deleted meetings with their meeting type eager-fetched,
     * ordered newest first.
     */
    @Query("SELECT m FROM Meeting m JOIN FETCH m.meetingType " +
           "WHERE m.deleteFlag = false " +
           "ORDER BY m.meetingDate DESC, m.startTime ASC")
    List<Meeting> findAllActiveOrderByDateDesc();

    /**
     * Single non-deleted meeting by ID with type eager-fetched.
     */
    @Query("SELECT m FROM Meeting m LEFT JOIN FETCH m.meetingType " +
           "WHERE m.id = :id AND m.deleteFlag = false")
    Optional<Meeting> findActiveById(@Param("id") Integer id);

    /**
     * Returns non-deleted meetings for the given meeting type, ordered most-recent
     * first.  Used to auto-fill Start Time / End Time when a type is selected.
     */
    @Query("SELECT m FROM Meeting m " +
           "WHERE m.meetingType.id = :meetingTypeId " +
           "AND m.deleteFlag = false " +
           "ORDER BY m.meetingDate DESC, m.startTime ASC")
    List<Meeting> findByMeetingTypeIdOrderByDateDesc(@Param("meetingTypeId") Integer meetingTypeId);

    /**
     * Upcoming meetings from a given date onwards, ordered soonest first.
     * Used by the Admin dashboard to populate the Upcoming Events panel.
     */
    @Query("SELECT m FROM Meeting m JOIN FETCH m.meetingType " +
           "WHERE m.deleteFlag = false AND m.meetingDate >= :today " +
           "ORDER BY m.meetingDate ASC, m.startTime ASC")
    List<Meeting> findUpcomingFromDate(@Param("today") LocalDate today);

    /**
     * All non-deleted meetings filtered by appClientId, ordered newest first.
     * Used by auto-fill and legacy callers that need most-recent first.
     */
    @Query("SELECT m FROM Meeting m JOIN FETCH m.meetingType " +
           "WHERE m.deleteFlag = false " +
           "AND (:appClientId IS NULL OR m.appClientId = :appClientId) " +
           "ORDER BY m.meetingDate DESC, m.startTime ASC")
    List<Meeting> findAllActiveByAppUserOrderByDateDesc(@Param("appClientId") String appClientId);

    /**
     * All non-deleted meetings filtered by appClientId, ordered soonest first.
     * Used by the Scheduled Meetings table, calendars, and member upcoming views.
     */
    @Query("SELECT m FROM Meeting m JOIN FETCH m.meetingType " +
           "WHERE m.deleteFlag = false " +
           "AND (:appClientId IS NULL OR m.appClientId = :appClientId) " +
           "ORDER BY m.meetingDate ASC, m.startTime ASC")
    List<Meeting> findAllActiveByAppUserOrderByDateAsc(@Param("appClientId") String appClientId);

    /**
     * Non-deleted meetings for a meeting type, filtered by appClientId, newest first.
     */
    @Query("SELECT m FROM Meeting m " +
           "WHERE m.meetingType.id = :meetingTypeId " +
           "AND m.deleteFlag = false " +
           "AND (:appClientId IS NULL OR m.appClientId = :appClientId) " +
           "ORDER BY m.meetingDate DESC, m.startTime ASC")
    List<Meeting> findByMeetingTypeIdOrderByDateDescByAppUser(
            @Param("meetingTypeId") Integer meetingTypeId,
            @Param("appClientId")   String appClientId);

    /**
     * Upcoming meetings from a given date, filtered by appClientId, soonest first.
     */
    @Query("SELECT m FROM Meeting m JOIN FETCH m.meetingType " +
           "WHERE m.deleteFlag = false AND m.meetingDate >= :today " +
           "AND (:appClientId IS NULL OR m.appClientId = :appClientId) " +
           "ORDER BY m.meetingDate ASC, m.startTime ASC")
    List<Meeting> findUpcomingFromDateByAppUser(
            @Param("today")       LocalDate today,
            @Param("appClientId") String appClientId);

    /**
     * All non-deleted meetings on a specific date with meeting type eager-fetched.
     * Used by the scheduler to find meetings happening today.
     */
    @Query("SELECT m FROM Meeting m JOIN FETCH m.meetingType " +
           "WHERE m.deleteFlag = false AND m.meetingDate = :date " +
           "ORDER BY m.startTime ASC")
    List<Meeting> findByMeetingDateAndDeleteFlagFalse(@Param("date") LocalDate date);

    /**
     * All non-deleted meetings for a specific organization client, with meeting type
     * eager-fetched.  Used by the weekly meeting reminder scheduler to find all
     * recurring and one-time meetings and filter them in Java.
     */
    @Query("SELECT m FROM Meeting m JOIN FETCH m.meetingType " +
           "WHERE m.deleteFlag = false AND m.appClientId = :appClientId " +
           "ORDER BY m.meetingDate ASC, m.startTime ASC")
    List<Meeting> findByAppClientIdAndDeleteFlagFalse(@Param("appClientId") String appClientId);

    /**
     * Finds all non-deleted meetings that are older than the given cutoff date
     * and not marked as exempt from auto-deletion.
     */
    @Query("SELECT m FROM Meeting m JOIN FETCH m.meetingType " +
           "WHERE m.deleteFlag = false AND m.doNotAutoDelete = false " +
           "AND m.meetingDate < :cutoff AND m.appClientId = :appClientId")
    List<Meeting> findAutoPurgeCandidates(@Param("cutoff") LocalDate cutoff,
                                          @Param("appClientId") String appClientId);
}
