package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One attendance entry — a member or visitor checked in for a service/event on a date.
 */
@Data
@Entity
@Table(name = "attendance_record",
       indexes = {
           @Index(name = "idx_attendance_client_date", columnList = "client_id, attendance_date"),
           @Index(name = "idx_attendance_client_flag", columnList = "client_id, delete_flag")
       })
public class AttendanceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "attendance_record_seq")
    @SequenceGenerator(name = "attendance_record_seq", sequenceName = "attendance_record_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    @Column(name = "check_in_time")
    private LocalDateTime checkInTime;

    @Column(name = "check_out_time")
    private LocalDateTime checkOutTime;

    /** MEMBER or VISITOR. */
    @Column(name = "person_type", length = 20)
    private String personType;

    /** FamilyMember.id when personType = MEMBER. */
    @Column(name = "family_member_id")
    private Integer familyMemberId;

    /** AttendanceVisitor.id when personType = VISITOR. */
    @Column(name = "visitor_id")
    private Long visitorId;

    /** Denormalized name for grids/exports. */
    @Column(name = "person_name", length = 200)
    private String personName;

    @Column(name = "service_type", length = 120)
    private String serviceType;

    @Column(name = "ministry", length = 120)
    private String ministry;

    @Column(name = "campus", length = 120)
    private String campus;

    /** PRESENT, LATE, ABSENT, EXCUSED. */
    @Column(name = "status", length = 20)
    private String status;

    /** manual, qr, barcode, family. */
    @Column(name = "check_in_method", length = 20)
    private String checkInMethod;

    @Column(name = "created_by", length = 120)
    private String createdBy;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;

    @PrePersist
    protected void onCreate() {
        if (this.createdDate == null) this.createdDate = LocalDateTime.now();
        if (this.status == null)      this.status = "PRESENT";
    }
}
