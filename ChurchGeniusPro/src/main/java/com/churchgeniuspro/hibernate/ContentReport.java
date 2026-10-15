package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * A member's report of objectionable content (mobile Phase 2, M5). Reviewed by church
 * staff in Follow-Ups → Reported Content. Today the only reportable content is a
 * Members Chat message ({@code MEMBER_MESSAGE}); the type column leaves room for more.
 *
 * <p>Status: {@code OPEN} → {@code REVIEWED} (staff acted) or {@code DISMISSED}.
 * The reported text is copied at report time so the review still shows it after the
 * message is deleted.
 */
@Data
@Entity
@Table(name = "content_report",
       indexes = {
           @Index(name = "idx_content_report_client_status", columnList = "app_client_id, status"),
           @Index(name = "idx_content_report_content",       columnList = "content_type, content_id")
       })
public class ContentReport {

    public static final String TYPE_MEMBER_MESSAGE = "MEMBER_MESSAGE";
    public static final String STATUS_OPEN      = "OPEN";
    public static final String STATUS_REVIEWED  = "REVIEWED";
    public static final String STATUS_DISMISSED = "DISMISSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "app_client_id", nullable = false, length = 64)
    private String appClientId;

    @Column(name = "content_type", nullable = false, length = 32)
    private String contentType;

    @Column(name = "content_id", nullable = false)
    private Long contentId;

    /** Copy of the reported text at report time (clipped), for the staff review. */
    @Column(name = "content_excerpt", length = 2000)
    private String contentExcerpt;

    @Column(name = "reporter_member_id", nullable = false)
    private Integer reporterMemberId;

    @Column(name = "reporter_name", length = 160)
    private String reporterName;

    @Column(name = "reported_member_id")
    private Integer reportedMemberId;

    @Column(name = "reported_name", length = 160)
    private String reportedName;

    /** HARASSMENT | SPAM | INAPPROPRIATE | OTHER */
    @Column(name = "reason", nullable = false, length = 32)
    private String reason;

    @Column(name = "details", length = 1000)
    private String details;

    @Column(name = "status", nullable = false, length = 16)
    @ColumnDefault("'OPEN'")
    private String status = STATUS_OPEN;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by", length = 150)
    private String reviewedBy;

    /** What staff did: e.g. "Message deleted", "Dismissed", free text ≤ 500. */
    @Column(name = "action_note", length = 500)
    private String actionNote;
}
