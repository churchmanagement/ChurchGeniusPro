package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * Configurable service / event type used when checking in (e.g. Sunday Worship,
 * Bible Study, Prayer Meeting, …). Seeded with defaults per organization; admins
 * can add more via Attendance Settings.
 */
@Data
@Entity
@Table(name = "attendance_service_type")
public class AttendanceServiceType {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "attendance_service_type_seq")
    @SequenceGenerator(name = "attendance_service_type_seq", sequenceName = "attendance_service_type_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    /** "service" or "event". */
    @Column(name = "category", length = 20)
    private String category = "service";

    @Column(name = "sort_order")
    private int sortOrder = 0;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
