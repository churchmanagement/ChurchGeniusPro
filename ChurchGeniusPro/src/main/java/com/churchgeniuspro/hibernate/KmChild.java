package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;

/** A child registered in the Kids Ministry program. */
@Data
@Entity
@Table(name = "km_child")
public class KmChild {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "km_child_seq")
    @SequenceGenerator(name = "km_child_seq", sequenceName = "km_child_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "dob")
    private LocalDate dob;

    @Column(name = "gender", length = 20)
    private String gender;

    /** Grade level, e.g. "Nursery", "Pre-K", "1st", "2nd" … */
    @Column(name = "grade", length = 50)
    private String grade;

    @Column(name = "allergies", columnDefinition = "TEXT")
    private String allergies;

    @Column(name = "medical_notes", columnDefinition = "TEXT")
    private String medicalNotes;

    /** Optional link to an existing FamilyMember.id */
    @Column(name = "family_member_id")
    private Integer familyMemberId;

    /** Parent / guardian name (free text, or linked member name) */
    @Column(name = "parent_name", length = 200)
    private String parentName;

    /** Parent / guardian phone for check-in lookup */
    @Column(name = "parent_phone", length = 30)
    private String parentPhone;

    @Column(name = "parent_email", length = 200)
    private String parentEmail;

    @Column(name = "emergency_contact_name", length = 200)
    private String emergencyContactName;

    @Column(name = "emergency_contact_phone", length = 30)
    private String emergencyContactPhone;

    /** Auto-assigned classroom id based on age/grade */
    @Column(name = "classroom_id")
    private Long classroomId;

    @Column(name = "photo_url", columnDefinition = "TEXT")
    private String photoUrl;

    /** Photo of the completed paper registration form (base64 data-URI). */
    @Column(name = "form_image_data", columnDefinition = "TEXT")
    private String formImageData;

    @Column(name = "inactive", nullable = false)
    private boolean inactive = false;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;

    @Column(name = "created_date")
    private LocalDate createdDate;

    /** Whether overdue-pickup alerts fire for this child (per-child override). */
    @Column(name = "pickup_alerts_enabled")
    private Boolean pickupAlertsEnabled = Boolean.TRUE;

    /** User id of the staff/volunteer who registered the child. */
    @Column(name = "registered_by", length = 100)
    private String registeredBy;

    /** Exact timestamp the registration was saved. */
    @Column(name = "registered_at")
    private java.time.LocalDateTime registeredAt;
}
