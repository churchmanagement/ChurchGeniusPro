package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Stable, scannable attendance code for a member. The {@code code} is encoded as a
 * QR code and a Code 128 barcode; scanning it at check-in resolves the member.
 */
@Data
@Entity
@Table(name = "member_attendance_code",
       uniqueConstraints = @UniqueConstraint(name = "uq_member_attendance_code", columnNames = {"code"}))
public class MemberAttendanceCode {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "member_attendance_code_seq")
    @SequenceGenerator(name = "member_attendance_code_seq", sequenceName = "member_attendance_code_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "family_member_id", nullable = false)
    private Integer familyMemberId;

    @Column(name = "code", nullable = false, length = 60)
    private String code;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @PrePersist
    protected void onCreate() {
        if (this.createdDate == null) this.createdDate = LocalDateTime.now();
        if (this.code == null) {
            this.code = "ATM-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        }
    }
}
