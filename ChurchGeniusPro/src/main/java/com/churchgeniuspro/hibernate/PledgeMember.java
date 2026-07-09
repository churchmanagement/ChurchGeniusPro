package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A member's pledge against a {@link PledgeCampaign}.
 *
 * Two contributor shapes are supported:
 *  - Existing member: set {@link #familyMemberId}; {@link #guestName} stays null.
 *  - Walk-up / non-member: leave {@link #familyMemberId} null and populate
 *    {@link #guestName}.
 *
 * {@link #amountCollected} is incremented by the Income auto-allocate hook
 * whenever a matching income row (member + fund) is saved.
 */
@Data
@Entity
@Table(name = "pledge_member")
public class PledgeMember {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "pledge_member_seq")
    @SequenceGenerator(name = "pledge_member_seq", sequenceName = "pledge_member_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** FK → pledge_campaign.id (no JPA relationship, kept simple for queries). */
    @Column(name = "campaign_id", nullable = false)
    private Integer campaignId;

    /** FK → family_member.id; null for walk-ups. */
    @Column(name = "family_member_id")
    private Integer familyMemberId;

    /** Optional FK → family.id for the family this pledge is attributed to. */
    @Column(name = "family_id")
    private Integer familyId;

    /** Free-text name for non-member pledgers (used when familyMemberId is null). */
    @Column(name = "guest_name", length = 300)
    private String guestName;

    /** Total pledged. */
    @Column(name = "pledge_amount", precision = 15, scale = 2)
    private BigDecimal pledgeAmount;

    /** Optional monthly cadence amount. */
    @Column(name = "monthly_amount", precision = 15, scale = 2)
    private BigDecimal monthlyAmount;

    /** Free-text non-cash commitments (e.g. "Sound system setup"). */
    @Column(name = "gifts", length = 500)
    private String gifts;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** Running total of income applied to this pledge. */
    @Column(name = "amount_collected", precision = 15, scale = 2)
    private BigDecimal amountCollected;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @PrePersist
    void onCreate() {
        if (createdDate == null) createdDate = LocalDateTime.now();
        if (amountCollected == null) amountCollected = BigDecimal.ZERO;
    }
}
