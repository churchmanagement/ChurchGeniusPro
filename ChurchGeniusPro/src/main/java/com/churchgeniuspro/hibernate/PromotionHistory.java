package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Append-only audit trail for promotions: who did what, and when, with a full copy of
 * the values before and after. Rows are only ever inserted — never updated or deleted.
 *
 * <p>Phase 1 writes the deal events ({@link #DEAL_CREATED}, {@link #DEAL_EDITED},
 * {@link #DEAL_DEACTIVATED}, {@link #DEAL_REACTIVATED}); {@code clientId} is empty for
 * those. Phase 2 adds the customer events (assigned, terms changed, applied or not
 * applied at conversion, activated, kept, cancelled) with the client filled in.
 */
@Data
@Entity
@Table(name = "promotion_history",
       indexes = {
           @Index(name = "ix_promotion_history_promotion", columnList = "promotion_id, occurred_at"),
           @Index(name = "ix_promotion_history_client",    columnList = "client_id, occurred_at")
       })
public class PromotionHistory {

    public static final String DEAL_CREATED     = "DEAL_CREATED";
    public static final String DEAL_EDITED      = "DEAL_EDITED";
    public static final String DEAL_DEACTIVATED = "DEAL_DEACTIVATED";
    public static final String DEAL_REACTIVATED = "DEAL_REACTIVATED";

    // Customer events (phase 2) — clientId and clientPromotionId are filled in.
    public static final String ASSIGNED          = "ASSIGNED";
    public static final String TERMS_CHANGED     = "TERMS_CHANGED";
    public static final String APPLIED           = "APPLIED";            // applied at trial-to-paid conversion
    public static final String NOT_APPLIED       = "NOT_APPLIED";        // Service Admin chose not to apply it at conversion
    public static final String ACTIVATED         = "ACTIVATED";          // a paying client's start date arrived (system)
    public static final String KEPT_AFTER_CHANGE = "KEPT_AFTER_CHANGE";  // kept through a plan / frequency change
    public static final String CANCELLED         = "CANCELLED";
    public static final String EXPIRED           = "EXPIRED";            // end date passed (system) — regular pricing restored

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "promotion_id", updatable = false)
    private Long promotionId;

    /** Copied so an entry still reads on its own. */
    @Column(name = "promotion_code", length = 30, updatable = false)
    private String promotionCode;

    /** The customer discount the event concerns; null for deal-level events. */
    @Column(name = "client_promotion_id", updatable = false)
    private Long clientPromotionId;

    /** The church the event concerns; null for deal-level events. */
    @Column(name = "client_id", length = 100, updatable = false)
    private String clientId;

    @Column(name = "event", nullable = false, length = 40, updatable = false)
    private String event;

    /** The Service Admin who did it (or "system"). */
    @Column(name = "actor", nullable = false, length = 100, updatable = false)
    private String actor;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @Column(name = "notes", columnDefinition = "TEXT", updatable = false)
    private String notes;

    /** JSON copies of the record before and after the change (before is null for a creation). */
    @Column(name = "before_json", columnDefinition = "TEXT", updatable = false) private String beforeJson;
    @Column(name = "after_json",  columnDefinition = "TEXT", updatable = false) private String afterJson;
}
