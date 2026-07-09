package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

/** A person authorized to pick up a child from Kids Ministry. */
@Data
@Entity
@Table(name = "km_authorized_pickup")
public class KmAuthorizedPickup {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "km_pickup_seq")
    @SequenceGenerator(name = "km_pickup_seq", sequenceName = "km_authorized_pickup_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "child_id", nullable = false)
    private Long childId;

    @Column(name = "person_name", nullable = false, length = 200)
    private String personName;

    @Column(name = "relationship", length = 100)
    private String relationship;

    @Column(name = "phone", length = 30)
    private String phone;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
