package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A visitor (non-member) captured at check-in. {@code visitCount} and
 * {@code firstVisitDate}/{@code lastVisitDate} drive first-time vs returning logic.
 */
@Data
@Entity
@Table(name = "attendance_visitor")
public class AttendanceVisitor {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "attendance_visitor_seq")
    @SequenceGenerator(name = "attendance_visitor_seq", sequenceName = "attendance_visitor_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "name", length = 200)
    private String name;

    @Column(name = "phone", length = 60)
    private String phone;

    @Column(name = "email", length = 200)
    private String email;

    @Column(name = "address", columnDefinition = "TEXT")
    private String address;

    @Column(name = "invited_by", length = 200)
    private String invitedBy;

    @Column(name = "first_visit_date")
    private LocalDate firstVisitDate;

    @Column(name = "last_visit_date")
    private LocalDate lastVisitDate;

    @Column(name = "visit_count", nullable = false)
    private int visitCount = 0;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;

    @PrePersist
    protected void onCreate() {
        if (this.createdDate == null) this.createdDate = LocalDateTime.now();
    }
}
