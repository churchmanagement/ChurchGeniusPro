package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Append-only history of a client's subscription: one row each time its plan,
 * price, billing frequency, dates or status change. Written only by
 * {@code SubscriptionLifecycleService}; never updated or deleted, so it is the
 * record of what a client was on and paying at any point.
 */
@Data
@Entity
@Table(name = "subscription_change",
       indexes = @Index(name = "ix_subscription_change_client", columnList = "client_id, changed_at"))
public class SubscriptionChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    @Column(name = "changed_by", length = 100)
    private String changedBy;

    /** CREATED | EDIT | DEMO_SUBSCRIPTION (the shared trial/demo update) */
    @Column(name = "reason", length = 40)
    private String reason;

    @Column(name = "from_plan", length = 40)      private String fromPlan;
    @Column(name = "to_plan", length = 40)        private String toPlan;
    @Column(name = "from_price", precision = 10, scale = 2) private BigDecimal fromPrice;
    @Column(name = "to_price", precision = 10, scale = 2)   private BigDecimal toPrice;
    @Column(name = "from_frequency", length = 10) private String fromFrequency;
    @Column(name = "to_frequency", length = 10)   private String toFrequency;
    @Column(name = "from_start_date")             private LocalDate fromStartDate;
    @Column(name = "to_start_date")               private LocalDate toStartDate;
    @Column(name = "from_end_date")               private LocalDate fromEndDate;
    @Column(name = "to_end_date")                 private LocalDate toEndDate;
    @Column(name = "from_status", length = 20)    private String fromStatus;
    @Column(name = "to_status", length = 20)      private String toStatus;
}
