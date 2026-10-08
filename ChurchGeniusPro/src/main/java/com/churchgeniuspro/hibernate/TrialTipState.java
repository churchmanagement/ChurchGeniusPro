package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Which feature-discovery tips a Trial login has seen or dismissed (trial-tips.js).
 * One row per (tenant, username, tip key). Holds nothing but tip keys and timestamps —
 * no personal, member or financial data. Rows become inert once the tenant leaves the
 * Trial plan (eligibility is re-checked on every request).
 */
@Data
@Entity
@Table(name = "trial_tip_state",
       uniqueConstraints = @UniqueConstraint(name = "uq_trial_tip_state", columnNames = { "client_id", "username", "tip_key" }))
public class TrialTipState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100) private String clientId;
    @Column(name = "username",  nullable = false, length = 150) private String username;
    @Column(name = "tip_key",   nullable = false, length = 40)  private String tipKey;
    /** How many times the card was shown (ignored cards count; a dismissed card stops). */
    @Column(name = "shown_count", nullable = false) private int shownCount;
    @Column(name = "last_shown_at") private LocalDateTime lastShownAt;
    @Column(name = "dismissed_at")  private LocalDateTime dismissedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;

    @PrePersist
    void prePersist() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
