package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

/**
 * Stores a Web Push subscription for a single browser on a single device.
 *
 * <p>One row is created every time a browser grants push-notification
 * permission and posts its subscription object to
 * {@code POST /api/push/subscribe}.  The same user can have multiple rows
 * (one per device / browser).
 *
 * <p>The {@code endpoint}, {@code p256dh}, and {@code auth} fields come
 * directly from the browser's {@code PushSubscription} JSON object and are
 * all that is needed to deliver a push message via the VAPID protocol.
 *
 * <p>Soft-deletion via {@code active = false} lets us keep records for
 * audit / debugging without delivering to dead endpoints.
 */
@Data
@Entity
@Table(name = "push_subscription",
       indexes = {
           @Index(name = "idx_push_sub_user",   columnList = "user_key"),
           @Index(name = "idx_push_sub_client",  columnList = "app_client_id")
       })
public class PushSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "push_sub_seq")
    @SequenceGenerator(name = "push_sub_seq", sequenceName = "push_subscription_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /**
     * Logical owner key — for staff users this is the {@code app_user.user_id}
     * (e.g. {@code USR-abc123}); for member-portal users this is
     * {@code MBR<uuid>} (the member_ref stored as clientId in the member session).
     */
    @Column(name = "user_key", nullable = false, length = 128)
    private String userKey;

    /**
     * Multi-tenant org scoping — the {@code app_client_id} for staff users or
     * the FamilyMember's {@code app_client_id} for members.
     */
    @Column(name = "app_client_id", length = 64)
    private String appClientId;

    /** "member" or "staff" — drives which notification queries to run. */
    @Column(name = "user_type", nullable = false, length = 16)
    private String userType;

    /** Full push endpoint URL (unique per browser subscription). */
    @Column(name = "endpoint", nullable = false, columnDefinition = "TEXT", unique = true)
    private String endpoint;

    /** Client public key (p256dh), Base64url-encoded. */
    @Column(name = "p256dh", nullable = false, columnDefinition = "TEXT")
    private String p256dh;

    /** Authentication secret, Base64url-encoded. */
    @Column(name = "auth", nullable = false, columnDefinition = "TEXT")
    private String auth;

    /** When false this subscription is no longer delivered to (e.g. 410 Gone). */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
