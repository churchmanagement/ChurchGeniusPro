package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

/** A Kids Ministry classroom / age group. */
@Data
@Entity
@Table(name = "km_classroom")
public class KmClassroom {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "km_classroom_seq")
    @SequenceGenerator(name = "km_classroom_seq", sequenceName = "km_classroom_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "class_name", nullable = false, length = 200)
    private String className;

    /** Inclusive minimum age in years (null = no lower bound) */
    @Column(name = "min_age")
    private Integer minAge;

    /** Inclusive maximum age in years (null = no upper bound) */
    @Column(name = "max_age")
    private Integer maxAge;

    @Column(name = "capacity")
    private Integer capacity;

    @Column(name = "room_number", length = 50)
    private String roomNumber;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
