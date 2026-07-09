package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * An assignment of a volunteer (family member) to a role for a specific event.
 */
@Data
@Entity
@Table(name = "volunteer_assignment")
public class VolunteerAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "volunteer_assignment_seq")
    @SequenceGenerator(name = "volunteer_assignment_seq", sequenceName = "volunteer_assignment_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "app_client_id", nullable = false, length = 100)
    private String appClientId;

    /** Links to ChurchEvent.id (nullable — allows non-event assignments e.g. weekly service) */
    @Column(name = "event_id")
    private Integer eventId;

    /** Free-text event/service label when not linked to a ChurchEvent */
    @Column(name = "event_label", length = 300)
    private String eventLabel;

    /** Event date (denormalised for quick display) */
    @Column(name = "event_date")
    private java.time.LocalDate eventDate;

    /** Shift time label, e.g. "8:00 AM – 10:00 AM" */
    @Column(name = "shift_time", length = 100)
    private String shiftTime;

    /** Links to VolunteerRole.id */
    @Column(name = "role_id")
    private Long roleId;

    /** Links to FamilyMember.id */
    @Column(name = "family_member_id", nullable = false)
    private Integer familyMemberId;

    /**
     * pending | confirmed | declined | maybe
     * Set by admin initially to "pending"; volunteer responds via member portal.
     */
    @Column(name = "assignment_status", length = 30)
    private String assignmentStatus = "pending";

    /** When the volunteer responded */
    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    /** Internal admin notes */
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "notification_sent", nullable = false)
    private boolean notificationSent = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
