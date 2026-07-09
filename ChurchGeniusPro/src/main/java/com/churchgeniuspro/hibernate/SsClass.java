package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

/** A Sunday School class (group). */
@Data
@Entity
@Table(name = "ss_class")
public class SsClass {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ss_class_seq")
    @SequenceGenerator(name = "ss_class_seq", sequenceName = "ss_class_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "class_name", nullable = false, length = 200)
    private String className;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
