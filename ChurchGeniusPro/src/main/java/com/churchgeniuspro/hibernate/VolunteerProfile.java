package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;

/**
 * A church member's volunteer profile — their skills, interests, and availability.
 * Linked to FamilyMember.id via familyMemberId.
 */
@Data
@Entity
@Table(name = "volunteer_profile")
public class VolunteerProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "volunteer_profile_seq")
    @SequenceGenerator(name = "volunteer_profile_seq", sequenceName = "volunteer_profile_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "app_client_id", nullable = false, length = 100)
    private String appClientId;

    /** Links to FamilyMember.id */
    @Column(name = "family_member_id", nullable = false)
    private Integer familyMemberId;

    /** Comma-separated skill/interest tags, e.g. "Sound System,Keyboard,Event Registration" */
    @Column(name = "skills", columnDefinition = "TEXT")
    private String skills;

    /** General availability notes, e.g. "Weekends only", "Morning shifts" */
    @Column(name = "availability", columnDefinition = "TEXT")
    private String availability;

    /** Active/inactive in volunteer pool */
    @Column(name = "status", length = 20)
    private String status = "Active"; // Active | Inactive

    @Column(name = "joined_date")
    private LocalDate joinedDate;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
