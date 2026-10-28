package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;

/**
 * Maps to the pre-existing {@code serviceadmin} table.
 * The table is seeded manually via an INSERT script — Hibernate
 * will only add missing columns (DDL = update).
 */
@Entity
@Table(name = "serviceadmin")
@Data
@ToString(exclude = {"password"})   // never in a log line, even by accident (2026-10-28 logging audit)
public class ServiceAdmin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String username;

    private String password;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private Boolean deleted = false;
}
