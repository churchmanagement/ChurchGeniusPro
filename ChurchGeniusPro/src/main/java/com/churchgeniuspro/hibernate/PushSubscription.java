package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;

/**
 * Stores a push subscription for a single browser or app install on a single device.
 *
 * <p>Mobile Phase 5: rows with {@code platform} = {@code android} / {@code ios} are
 * native (FCM) registrations made by the ChurchGeniusPro app — {@code endpoint} holds
 * the FCM registration token and {@code p256dh}/{@code auth} hold the placeholder
 * {@value #NATIVE_KEY_PLACEHOLDER}. Everything else (owner key, org, type, active
 * flag, the logout deactivation and the schedulers' queries) is shared with Web Push.
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
           @Index(name = "idx_push_sub_client",  columnList = "app_client_id"),
           @Index(name = "idx_push_sub_platform", columnList = "platform")
       })
public class PushSubscription {

    public static final String PLATFORM_WEB     = "web";
    public static final String PLATFORM_ANDROID = "android";
    public static final String PLATFORM_IOS     = "ios";
    /** Stored in p256dh/auth for native rows (the columns are NOT NULL, the values unused). */
    public static final String NATIVE_KEY_PLACEHOLDER = "-";

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

    /**
     * {@link #PLATFORM_WEB} for VAPID browser subscriptions (all rows before Phase 5),
     * {@link #PLATFORM_ANDROID} / {@link #PLATFORM_IOS} for the app (delivered via FCM).
     */
    @Column(name = "platform", nullable = false, length = 16)
    @org.hibernate.annotations.ColumnDefault("'web'")
    private String platform = PLATFORM_WEB;

    /** Optional human label for the device ("Pixel 8", "iPhone 15") — native rows only. */
    @Column(name = "device_name", length = 120)
    private String deviceName;

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

    /** True for app (FCM) registrations, false for Web Push rows. */
    public boolean isNative() {
        return platform != null && !PLATFORM_WEB.equals(platform);
    }
}
