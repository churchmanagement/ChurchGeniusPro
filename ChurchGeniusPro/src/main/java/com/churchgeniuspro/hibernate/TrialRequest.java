package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A prospective church asking for a free trial through the public, token-gated
 * Trial Request page (Service Admin → Trial Requests).
 *
 * <p>Lifecycle: {@link #PENDING_VERIFICATION} on submit → {@link #VERIFIED} once the
 * requester enters the code emailed to them (only then is the Support Email notified
 * and the request treated as real) → {@link #APPROVED} or {@link #REJECTED} by a
 * Service Admin. Approval does NOT create an account: it issues an ordinary Trial
 * Registration Link ({@code trialLinkId}) and emails it to the verified address, and
 * the existing registration flow creates the trial from there.
 *
 * <p>Platform-level, not tenant data — there is no church yet. Hibernate
 * {@code ddl-auto=update} creates the table from this entity.
 */
@Data
@Entity
@Table(name = "trial_request",
       uniqueConstraints = @UniqueConstraint(name = "uq_trial_request_ref", columnNames = {"reference"}),
       indexes = @Index(name = "ix_trial_request_status", columnList = "status, created_at"))
public class TrialRequest {

    public static final String PENDING_VERIFICATION = "PENDING_VERIFICATION";
    public static final String VERIFIED             = "VERIFIED";
    public static final String APPROVED             = "APPROVED";
    public static final String REJECTED             = "REJECTED";
    /**
     * The emailed code was never entered within {@link #VERIFY_WINDOW_HOURS} hours.
     * Such a request no longer blocks the email address, so nobody can lock an
     * address out by submitting a request in someone else's name.
     */
    public static final String VERIFICATION_EXPIRED = "VERIFICATION_EXPIRED";
    /** Phase C: the account is switched off by a Service Admin; every login of its tenant is refused. */
    public static final String DISABLED = "DISABLED";
    /** Phase C: removed from the list (soft); the tenant is refused exactly like DISABLED. Reversible. */
    public static final String DELETED  = "DELETED";

    /** Tenant statuses mirrored onto service_client when a request leaves/returns to APPROVED. */
    public static final String TENANT_PENDING  = "Pending";
    public static final String TENANT_DISABLED = "Disabled";
    public static final String TENANT_DELETED  = "Deleted";

    /** How long an unverified request stays open (and blocks a new request for its email). */
    public static final int VERIFY_WINDOW_HOURS = 24;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Public reference, e.g. {@code TRQ-4K7Q2M8P}; also the verification key. */
    @Column(name = "reference", nullable = false, updatable = false, length = 20)
    private String reference;

    @Column(name = "first_name",  nullable = false, length = 100) private String firstName;
    @Column(name = "last_name",   nullable = false, length = 100) private String lastName;
    @Column(name = "church_name", nullable = false, length = 200) private String churchName;
    @Column(name = "email",       nullable = false, length = 320) private String email;
    @Column(name = "phone",       length = 40)                    private String phone;
    @Column(name = "designation", length = 100)                   private String designation;
    @Column(name = "note",        columnDefinition = "TEXT")      private String note;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "request_ip", length = 64)
    private String requestIp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** When the last verification code was emailed (resend cooldown). */
    @Column(name = "code_sent_at")
    private LocalDateTime codeSentAt;

    /** Verification codes emailed for this request (resend cap). */
    @Column(name = "codes_sent")
    private Integer codesSent;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    /** Whether the verified request reached the Support Email. */
    @Column(name = "support_email_sent")
    private Boolean supportEmailSent;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decided_by", length = 150)
    private String decidedBy;

    @Column(name = "reject_reason", length = 500)
    private String rejectReason;

    /** The Trial Registration Link issued on approval. */
    @Column(name = "trial_link_id")
    private Integer trialLinkId;

    /** Whether the approval email carrying that link was delivered. */
    @Column(name = "approval_email_sent")
    private Boolean approvalEmailSent;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null || status.isBlank()) status = PENDING_VERIFICATION;
    }

    public String fullName() {
        return ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
    }
}
