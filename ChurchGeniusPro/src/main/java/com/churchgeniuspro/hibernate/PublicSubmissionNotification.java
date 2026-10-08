package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * One in-app notification for a submission made on a church's public page
 * (Prayer Request, Connect With Us, Membership Form, Donation).
 *
 * <p>Stored once per CHURCH, not once per user. Who may see it is decided when the
 * notification panel is read — by the destination page's role guard, the user's
 * current permissions and the church's active-account status — so a permission
 * removed in viewUsers takes effect on the very next poll, and an expired church's
 * notifications are hidden, not deleted.
 */
@Data
@Entity
@Table(name = "public_submission_notification",
       indexes = @Index(name = "ix_psn_client_created", columnList = "client_id, created_at"))
public class PublicSubmissionNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** The church the submission belongs to (service_client.client_id). */
    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** PRAYER, CONNECT, MEMBERSHIP or DONATION. */
    @Column(name = "type", nullable = false, length = 20)
    private String type;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    /** Id of the submission row (prayer request, connect submission, membership family, donation). */
    @Column(name = "source_id")
    private Long sourceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
