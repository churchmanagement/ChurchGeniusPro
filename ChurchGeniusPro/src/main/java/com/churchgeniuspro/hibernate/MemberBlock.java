package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.Instant;

/**
 * One member has blocked another in Members Chat (mobile Phase 2, M5 — the
 * "block abusive users" control both app stores require for user-generated content).
 *
 * <p>Silent to the blocked member: their messages simply stop reaching the blocker's
 * inbox and new ones are refused with a neutral message. Tenant-scoped: both ids are
 * {@code family_member.id} rows of {@code app_client_id}.
 */
@Data
@Entity
@Table(name = "member_block",
       uniqueConstraints = @UniqueConstraint(name = "uq_member_block",
               columnNames = {"app_client_id", "blocker_member_id", "blocked_member_id"}),
       indexes = @Index(name = "idx_member_block_blocker", columnList = "app_client_id, blocker_member_id"))
public class MemberBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "app_client_id", nullable = false, length = 64)
    private String appClientId;

    @Column(name = "blocker_member_id", nullable = false)
    private Integer blockerMemberId;

    @Column(name = "blocked_member_id", nullable = false)
    private Integer blockedMemberId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
