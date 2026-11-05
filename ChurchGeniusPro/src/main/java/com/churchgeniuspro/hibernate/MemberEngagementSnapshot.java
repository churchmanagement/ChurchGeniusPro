package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AI Phase 2 (2026-11-04): one row per live member per snapshot day — the facts behind the
 * engagement signal, computed nightly by {@code MemberIntelligenceService} from attendance,
 * giving presence, groups and serving. Holds counts and dates only: no names, no amounts.
 * Tenant-scoped by {@code app_client_id}; purged after 24 months.
 */
@Data
@Entity
@Table(name = "member_engagement_snapshot", indexes = {
        @Index(name = "idx_mes_client_day_member", columnList = "app_client_id, snapshot_date, member_id"),
        @Index(name = "idx_mes_client_member_day", columnList = "app_client_id, member_id, snapshot_date")
})
public class MemberEngagementSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_client_id", nullable = false, length = 100)
    private String appClientId;

    @Column(name = "member_id", nullable = false)
    private Integer memberId;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    /** Distinct Present/Late days in the last 30 / 90 / 365 days. */
    @Column(name = "attendance_30")  private int attendance30;
    @Column(name = "attendance_90")  private int attendance90;
    @Column(name = "attendance_365") private int attendance365;

    @Column(name = "first_attended") private LocalDate firstAttended;
    @Column(name = "last_attended")  private LocalDate lastAttended;

    /** Income rows recorded for the member in the last 90 / 365 days and in the 365 days before that (presence, never amounts). */
    @Column(name = "gifts_90")        private int gifts90;
    @Column(name = "gifts_365")       private int gifts365;
    @Column(name = "gifts_prior_365") private int giftsPrior365;
    @Column(name = "last_gift")       private LocalDate lastGift;

    /** Confirmed live groups (member link or an e-mail only they use). */
    @Column(name = "groups_count") private int groupsCount;

    /** A non-declined volunteer assignment dated in the serving window (or undated). */
    @Column(name = "serving") private boolean serving;

    /** ENGAGED · WATCH · AT_RISK · NEW · QUIET. */
    @Column(name = "signal", nullable = false, length = 12)
    private String signal;

    /** JSON array of plain sentences explaining the signal (numbers and dates only). */
    @Column(name = "reasons_json", columnDefinition = "TEXT")
    private String reasonsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
