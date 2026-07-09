package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.AttendanceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, Long> {

    List<AttendanceRecord> findByClientIdAndAttendanceDateAndDeleteFlagFalse(String clientId, LocalDate date);

    List<AttendanceRecord> findByClientIdAndAttendanceDateBetweenAndDeleteFlagFalse(
            String clientId, LocalDate from, LocalDate to);

    Optional<AttendanceRecord> findByIdAndClientIdAndDeleteFlagFalse(Long id, String clientId);

    /** Prevent duplicate check-ins: same person + date + service. */
    @Query("select a from AttendanceRecord a where a.clientId = :clientId and a.attendanceDate = :date "
         + "and a.serviceType = :service and a.personType = :ptype "
         + "and ((:fmId is not null and a.familyMemberId = :fmId) or (:visId is not null and a.visitorId = :visId)) "
         + "and a.deleteFlag = false")
    Optional<AttendanceRecord> findDuplicate(@Param("clientId") String clientId,
                                             @Param("date") LocalDate date,
                                             @Param("service") String service,
                                             @Param("ptype") String ptype,
                                             @Param("fmId") Integer familyMemberId,
                                             @Param("visId") Long visitorId);

    /** Distinct dates a member has attended (for last-attendance / consecutive-absence logic). */
    @Query("select distinct a.attendanceDate from AttendanceRecord a where a.clientId = :clientId "
         + "and a.familyMemberId = :fmId and a.deleteFlag = false order by a.attendanceDate desc")
    List<LocalDate> distinctDatesForMember(@Param("clientId") String clientId, @Param("fmId") Integer familyMemberId);
}
