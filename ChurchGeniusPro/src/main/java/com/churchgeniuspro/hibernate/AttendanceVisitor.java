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

    // ── Visitor registration (2026-11-04): split name/address, family/group, interest ──
    // All nullable: older visitors keep only {@code name}/{@code address} and still show.

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    /** Optional: {@code M} or {@code F}. */
    @Column(name = "gender", length = 1)
    private String gender;

    @Column(name = "address1", length = 200)
    private String address1;

    @Column(name = "address2", length = 200)
    private String address2;

    @Column(name = "city", length = 100)
    private String city;

    /** State abbreviation (e.g. {@code TX}), from the same list the Family page shows. */
    @Column(name = "state", length = 40)
    private String state;

    @Column(name = "zip_code", length = 12)
    private String zipCode;

    /** The family or group this visitor came with ({@link VisitorParty}); null for one person. */
    @Column(name = "party_id")
    private Long partyId;

    /** Relationship to the party's primary person: PRIMARY, SPOUSE, ADULT, CHILD, OTHER (optional). */
    @Column(name = "relationship", length = 20)
    private String relationship;

    /** Latest interest level: INTERESTED, NOT_SURE, NOT_INTERESTED (copied from the last visit). */
    @Column(name = "interest_level", length = 20)
    private String interestLevel;

    /** Latest "follow-up required" answer (copied from the last visit). */
    @Column(name = "follow_up_required")
    private Boolean followUpRequired;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdDate == null) this.createdDate = LocalDateTime.now();
    }
}
