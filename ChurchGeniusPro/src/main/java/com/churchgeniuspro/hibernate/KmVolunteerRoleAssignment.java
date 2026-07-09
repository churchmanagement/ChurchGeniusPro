package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

/** Assigns a role (by name) to a KM volunteer. */
@Data
@Entity
@Table(name = "km_volunteer_role_assignment")
public class KmVolunteerRoleAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "km_volunteer_role_assignment_seq")
    @SequenceGenerator(name = "km_volunteer_role_assignment_seq", sequenceName = "km_volunteer_role_assignment_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "volunteer_id", nullable = false)
    private Long volunteerId;

    /** Role name stored directly (not FK) so custom/ad-hoc roles work. */
    @Column(name = "role_name", nullable = false, length = 100)
    private String roleName;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
