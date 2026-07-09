package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A volunteer assigned to a specific event.
 * Can be an existing church member (familyMemberId set) or a manually-added external volunteer.
 */
@Data
@Entity
@Table(name = "event_volunteer")
public class EventVolunteer {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "event_volunteer_seq")
    @SequenceGenerator(name = "event_volunteer_seq", sequenceName = "event_volunteer_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "app_client_id", nullable = false, length = 100)
    private String appClientId;

    /** Links to ChurchEvent.id */
    @Column(name = "event_id", nullable = false)
    private Integer eventId;

    /** Links to FamilyMember.id — null if manually added */
    @Column(name = "family_member_id")
    private Integer familyMemberId;

    /** Volunteer's first name (denormalized for manual volunteers; also cached for members) */
    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "phone", length = 50)
    private String phone;

    /** true = manually entered (not a member lookup) */
    @Column(name = "is_manual", nullable = false)
    private boolean isManual = false;

    /**
     * pending | confirmed | declined | maybe
     */
    @Column(name = "status", length = 30)
    private String status = "pending";

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
