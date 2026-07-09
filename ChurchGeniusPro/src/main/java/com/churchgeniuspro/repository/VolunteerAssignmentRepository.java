package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.VolunteerAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;

public interface VolunteerAssignmentRepository extends JpaRepository<VolunteerAssignment, Long> {

    /** All assignments for an event. */
    List<VolunteerAssignment> findByAppClientIdAndEventIdAndDeleteFlagFalseOrderByRoleIdAscFamilyMemberIdAsc(
            String appClientId, Integer eventId);

    /** All non-deleted assignments for a church (for roster/report views). */
    List<VolunteerAssignment> findByAppClientIdAndDeleteFlagFalseOrderByEventDateDescCreatedAtDesc(
            String appClientId);

    /** All assignments for a specific family member (used in member portal). */
    List<VolunteerAssignment> findByAppClientIdAndFamilyMemberIdAndDeleteFlagFalseOrderByEventDateDesc(
            String appClientId, Integer familyMemberId);

    /** Upcoming assignments for a member (event date >= today). */
    @Query("SELECT a FROM VolunteerAssignment a WHERE a.appClientId = :cid " +
           "AND a.familyMemberId = :mid AND a.deleteFlag = false " +
           "AND (a.eventDate IS NULL OR a.eventDate >= :today) " +
           "ORDER BY a.eventDate ASC")
    List<VolunteerAssignment> findUpcomingForMember(@Param("cid") String appClientId,
                                                    @Param("mid") Integer familyMemberId,
                                                    @Param("today") LocalDate today);

    /** Assignments for a role + event (used to enforce maxCapacity). */
    @Query("SELECT COUNT(a) FROM VolunteerAssignment a WHERE a.appClientId = :cid " +
           "AND a.eventId = :eid AND a.roleId = :rid AND a.deleteFlag = false " +
           "AND a.assignmentStatus <> 'declined'")
    long countActiveForEventRole(@Param("cid") String appClientId,
                                 @Param("eid") Integer eventId,
                                 @Param("rid") Long roleId);

    /** All assignments for a given event label (e.g. "Kids Ministry"). */
    List<VolunteerAssignment> findByAppClientIdAndEventLabelAndDeleteFlagFalseOrderByFamilyMemberIdAsc(
            String appClientId, String eventLabel);

    /** Find assignments that haven't had a notification sent yet. */
    List<VolunteerAssignment> findByAppClientIdAndNotificationSentFalseAndDeleteFlagFalse(String appClientId);
}
