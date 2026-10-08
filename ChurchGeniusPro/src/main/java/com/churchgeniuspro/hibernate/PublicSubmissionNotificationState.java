package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/** Per-user read / dismissed state for a {@link PublicSubmissionNotification}. */
@Data
@Entity
@Table(name = "public_submission_notification_state",
       uniqueConstraints = @UniqueConstraint(name = "uq_psn_state_user",
                                             columnNames = {"notification_id", "user_key"}),
       indexes = @Index(name = "ix_psn_state_user", columnList = "user_key"))
public class PublicSubmissionNotificationState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "notification_id", nullable = false)
    private Long notificationId;

    /** The login's session clientId — the same key the existing notification log uses. */
    @Column(name = "user_key", nullable = false, length = 128)
    private String userKey;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "dismissed_at")
    private Instant dismissedAt;
}
