package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

/** A named volunteer role within a ministry (e.g. "Sound System", "Food Service"). */
@Data
@Entity
@Table(name = "volunteer_role")
public class VolunteerRole {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "volunteer_role_seq")
    @SequenceGenerator(name = "volunteer_role_seq", sequenceName = "volunteer_role_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "app_client_id", nullable = false, length = 100)
    private String appClientId;

    /** Role name, e.g. "Registration", "Parking", "Sound System" */
    @Column(name = "role_name", nullable = false, length = 200)
    private String roleName;

    /** Ministry area, e.g. "Media", "Hospitality", "Security" */
    @Column(name = "ministry", length = 100)
    private String ministry;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Max volunteers allowed per assignment using this role (null = unlimited) */
    @Column(name = "max_capacity")
    private Integer maxCapacity;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
