package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** A single check-in event for a child in Kids Ministry. */
@Data
@Entity
@Table(name = "km_checkin")
public class KmCheckin {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "km_checkin_seq")
    @SequenceGenerator(name = "km_checkin_seq", sequenceName = "km_checkin_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /**
     * The Kids-Ministry child being checked in. NULL for guardian-only rows
     * created by the public /kidsCheckin flow when a parent checks in only
     * themselves. ({@link #guardianMemberId} carries the family-member id
     * in that case.)
     */
    @Column(name = "child_id")
    private Long childId;

    @Column(name = "classroom_id")
    private Long classroomId;

    @Column(name = "checkin_time")
    private LocalDateTime checkinTime;

    @Column(name = "checkout_time")
    private LocalDateTime checkoutTime;

    /** Random alphanumeric code, e.g. "A-4821", matched on parent pickup tag */
    @Column(name = "security_code", length = 20)
    private String securityCode;

    /** Name of the guardian/authorized person who picked the child up. */
    @Column(name = "checked_out_by", length = 200)
    private String checkedOutBy;

    /** User id of the staff/volunteer who performed the check-out. */
    @Column(name = "checked_out_user", length = 100)
    private String checkedOutUser;

    /** How the check-out was performed: Code | Barcode | Verified | Manual | List. */
    @Column(name = "checkout_method", length = 30)
    private String checkoutMethod;

    /** Per-child override of the default pickup deadline for this check-in. */
    @Column(name = "pickup_deadline")
    private LocalDateTime pickupDeadline;

    /** Optional note explaining a pickup-deadline extension. */
    @Column(name = "pickup_extension_notes", columnDefinition = "TEXT")
    private String pickupExtensionNotes;

    /** Global snooze: overdue alerts for this child are paused until this time. */
    @Column(name = "snooze_until")
    private LocalDateTime snoozeUntil;

    /** When the last overdue alert was sent (drives the re-alert interval). */
    @Column(name = "last_alert_sent_at")
    private LocalDateTime lastAlertSentAt;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /**
     * FamilyMember.id when this row represents a Head/Spouse/Adult
     * checking themselves in via the public kids-checkin page. NULL for
     * regular per-child check-ins (where {@link #childId} carries the
     * KmChild reference instead).
     */
    @Column(name = "guardian_member_id")
    private Integer guardianMemberId;

    /**
     * Shared code stamped on every row produced by the same public
     * /kidsCheckin submission so kid + parent show the same value on
     * their printed label and can be looked up as a group later.
     */
    @Column(name = "family_checkin_code", length = 20)
    private String familyCheckinCode;
}
