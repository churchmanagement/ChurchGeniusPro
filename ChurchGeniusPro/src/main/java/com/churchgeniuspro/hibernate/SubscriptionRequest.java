package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A church's request to move to a subscription plan, submitted from
 * {@code /subscriptionReq} (the link in trial reminder emails, or the app).
 *
 * <p>Lifecycle: {@link #NEW} → {@link #IN_PROGRESS} (Service Admin is handling it)
 * → {@link #COMPLETED} (converted / registered) or {@link #DECLINED}. At most one
 * request per church may be NEW or IN_PROGRESS (service check + V9 partial index).
 *
 * <p>The church name is copied from the client account, never typed by the
 * requester. {@code registerAsNewClient} marks a sample-data trial ({@code TRIAL-}),
 * which cannot be converted: the Service Admin registers a new client instead.
 */
@Data
@Entity
@Table(name = "subscription_request",
       indexes = @Index(name = "ix_subscription_request_status", columnList = "status, created_at"))
public class SubscriptionRequest {

    public static final String NEW         = "NEW";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String COMPLETED   = "COMPLETED";
    public static final String DECLINED    = "DECLINED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "church_name", length = 200)        private String churchName;
    @Column(name = "first_name", nullable = false, length = 100) private String firstName;
    @Column(name = "last_name", nullable = false, length = 100)  private String lastName;
    @Column(name = "registered_email", nullable = false, length = 320) private String registeredEmail;
    @Column(name = "phone", length = 40)               private String phone;
    @Column(name = "plan_code", nullable = false, length = 40)  private String planCode;
    @Column(name = "plan_name", length = 100)          private String planName;
    /** MONTHLY | YEARLY */
    @Column(name = "billing_frequency", length = 10)   private String billingFrequency;
    @Column(name = "note", columnDefinition = "TEXT")  private String note;

    /** The client's plan when the request was made (e.g. TRIAL). */
    @Column(name = "current_plan", length = 40)        private String currentPlan;

    /** Sample-data trial: handled by registering a new client, never by conversion. */
    @Column(name = "register_as_new_client")
    private Boolean registerAsNewClient;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "request_ip", length = 64)          private String requestIp;
    @Column(name = "created_at", nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(name = "support_email_sent")               private Boolean supportEmailSent;
    @Column(name = "decided_at")                       private LocalDateTime decidedAt;
    @Column(name = "decided_by", length = 100)         private String decidedBy;
    @Column(name = "decline_reason", length = 500)     private String declineReason;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null || status.isBlank()) status = NEW;
    }
}
