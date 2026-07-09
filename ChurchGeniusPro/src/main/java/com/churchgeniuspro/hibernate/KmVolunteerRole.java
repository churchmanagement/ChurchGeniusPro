package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

/** A named role defined for the Kids Ministry volunteer system. */
@Data
@Entity
@Table(name = "km_volunteer_role")
public class KmVolunteerRole {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "km_volunteer_role_seq")
    @SequenceGenerator(name = "km_volunteer_role_seq", sequenceName = "km_volunteer_role_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "role_name", nullable = false, length = 100)
    private String roleName;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
