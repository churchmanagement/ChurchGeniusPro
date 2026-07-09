package com.churchgeniuspro.repository;

import com.churchgeniuspro.hibernate.VolunteerAttendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface VolunteerAttendanceRepository extends JpaRepository<VolunteerAttendance, Long> {

    Optional<VolunteerAttendance> findByAssignmentId(Long assignmentId);

    List<VolunteerAttendance> findByAppClientId(String appClientId);

    @Query("SELECT a FROM VolunteerAttendance a WHERE a.appClientId = :cid " +
           "AND a.assignmentId IN :ids")
    List<VolunteerAttendance> findByAssignmentIds(@Param("cid") String appClientId,
                                                  @Param("ids") List<Long> assignmentIds);
}
