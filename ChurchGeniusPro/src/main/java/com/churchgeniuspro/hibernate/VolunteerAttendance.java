package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** Tracks actual check-in/check-out time for a volunteer on event day. */
@Data
@Entity
@Table(name = "volunteer_attendance")
public class VolunteerAttendance {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "volunteer_attendance_seq")
    @SequenceGenerator(name = "volunteer_attendance_seq", sequenceName = "volunteer_attendance_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "app_client_id", nullable = false, length = 100)
    private String appClientId;

    /** Links to VolunteerAssignment.id */
    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "checkin_time")
    private LocalDateTime checkinTime;

    @Column(name = "checkout_time")
    private LocalDateTime checkoutTime;

    @Column(name = "marked_by", length = 200)
    private String markedBy;
}
