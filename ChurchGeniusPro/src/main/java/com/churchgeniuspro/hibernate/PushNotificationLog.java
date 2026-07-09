package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * Persists every push notification sent to a user so the app can:
 * <ul>
 *   <li>Show a notification badge count (unread count per userKey)</li>
 *   <li>Display a notification history dropdown in the topbar</li>
 *   <li>Clear the badge when the user views their notifications</li>
 * </ul>
 *
 * <p>One row is written per user that a push is sent to (not per device).
 * Multiple devices owned by the same user share the same log row — the
 * "read" state is per-user, not per-device.
 *
 * <p>Rows are retained indefinitely for history; the badge query only counts
 * rows where {@code readAt} is {@code null}.
 */
@Data
@Entity
@Table(name = "push_notification_log",
       indexes = {
           @Index(name = "idx_pnl_user_key",    columnList = "user_key"),
           @Index(name = "idx_pnl_app_client",  columnList = "app_client_id"),
           @Index(name = "idx_pnl_sent_at",     columnList = "sent_at")
       })
public class PushNotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "pnl_seq")
    @SequenceGenerator(name = "pnl_seq", sequenceName = "push_notification_log_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** "USR-xxx" for staff, "MBR-xxx" for members — matches push_subscription.user_key. */
    @Column(name = "user_key", nullable = false, length = 128)
    private String userKey;

    /** Org-level scoping — same as app_client_id on all other tables. */
    @Column(name = "app_client_id", length = 64)
    private String appClientId;

    /** "member" or "staff" — mirrors push_subscription.user_type. */
    @Column(name = "user_type", length = 16)
    private String userType;

    /** Notification title shown in the OS notification centre. */
    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /** Short body text of the notification. */
    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    /** Relative URL opened when the user taps the notification (e.g. "/home"). */
    @Column(name = "url", length = 512)
    private String url;

    /**
     * Notification tag — same as the tag sent in the Web Push payload.
     * Used to categorise notifications (e.g. "cgp-birthday", "cgp-event").
     */
    @Column(name = "tag", length = 64)
    private String tag;

    /** When the push was sent. */
    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    /**
     * When the user acknowledged/viewed this notification.
     * {@code null} means unread — this drives the badge count.
     */
    @Column(name = "read_at")
    private Instant readAt;

    @PrePersist
    protected void onCreate() {
        if (this.sentAt == null) {
            this.sentAt = Instant.now();
        }
    }
}
