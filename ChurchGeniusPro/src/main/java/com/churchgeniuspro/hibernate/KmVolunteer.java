package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

/** A volunteer / teacher assigned to the Kids Ministry. */
@Data
@Entity
@Table(name = "km_volunteer")
public class KmVolunteer {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "km_volunteer_seq")
    @SequenceGenerator(name = "km_volunteer_seq", sequenceName = "km_volunteer_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "classroom_id")
    private Long classroomId;

    /** Optional link to FamilyMember.id */
    @Column(name = "family_member_id")
    private Integer familyMemberId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "email", length = 200)
    private String email;

    @Column(name = "role", length = 100)
    private String role; // e.g. "Teacher", "Helper", "Check-in"

    /** pending | confirmed | declined | maybe */
    @Column(name = "status", length = 30)
    private String status;

    @Column(name = "is_manual", nullable = false)
    private boolean manual = false;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "inactive", nullable = false)
    private boolean inactive = false;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
