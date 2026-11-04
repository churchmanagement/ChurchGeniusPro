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

import java.time.LocalDateTime;

/**
 * Visitors who came together (2026-11-04): a FAMILY (one primary person + relatives) or a
 * GROUP (friends, not related). A single visitor has no party row. Each person stays an
 * {@link AttendanceVisitor} with {@code party_id} pointing here.
 */
@Data
@Entity
@Table(name = "visitor_party",
       indexes = @Index(name = "idx_visitor_party_client", columnList = "client_id, delete_flag"))
public class VisitorParty {

    public static final String FAMILY = "FAMILY";
    public static final String GROUP  = "GROUP";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "visitor_party_seq")
    @SequenceGenerator(name = "visitor_party_seq", sequenceName = "visitor_party_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** FAMILY or GROUP. */
    @Column(name = "kind", nullable = false, length = 10)
    private String kind;

    /** Optional name for a group ("Smith family", "College friends"). */
    @Column(name = "group_name", length = 150)
    private String groupName;

    /** The primary contact / household member. */
    @Column(name = "primary_visitor_id")
    private Long primaryVisitorId;

    @Column(name = "created_by", length = 120)
    private String createdBy;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;

    @PrePersist
    protected void onCreate() {
        if (createdDate == null) createdDate = LocalDateTime.now();
    }
}
