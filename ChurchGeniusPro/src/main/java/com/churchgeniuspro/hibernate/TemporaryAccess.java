package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A time-limited "Temporary Access" pass that lets someone log in with a printed
 * barcode badge plus a 6-digit code, without provisioning a real username/password.
 *
 * <p>Security notes:
 * <ul>
 *   <li>The 6-digit code is stored <b>hashed</b> ({@code accessCodeHash}) — never plaintext.</li>
 *   <li>{@code barcodeValue} is a unique, non-guessable token (secure random).</li>
 *   <li>Access is only valid within [{@code startDateTime}, {@code endDateTime}]; the
 *       {@code TempAccessFilter} expires live sessions the moment the end time passes.</li>
 * </ul>
 *
 * <p>{@code permissions} is a comma-separated list of page permission keys
 * (e.g. {@code general.meetings,general.kidsministry}) chosen by the admin; these
 * are turned into the session's privileges JSON at login so the existing
 * {@code RoleGuard.requirePermission} gates govern which pages are reachable.
 */
@Data
@Entity
@Table(name = "temporary_access")
public class TemporaryAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Organization (tenant) this pass belongs to — the creating admin's clientId. */
    @Column(name = "client_id", nullable = false)
    private String clientId;

    /** Optional link to an existing app_user.user_id (USR…) — informational only. */
    @Column(name = "user_id")
    private String userId;

    /** Human label for the badge / audit (e.g. "Guest Volunteer — Jane"). */
    @Column(name = "holder_name")
    private String holderName;

    /** Where the 6-digit code is emailed (optional). */
    @Column(name = "email")
    private String email;

    /** Holder's phone number (optional) — shown on the badge when present. */
    @Column(name = "phone")
    private String phone;

    /** Holder's role / title for this pass (e.g. Volunteer, Usher, Registration). */
    @Column(name = "role")
    private String role;

    /** Holder photo as a data-URI (optional) — copied from the selected member/volunteer. */
    @Column(name = "holder_photo", columnDefinition = "TEXT")
    private String holderPhoto;

    /** CSV of badge field keys the admin chose to display (empty/null = show all). */
    @Column(name = "badge_fields", columnDefinition = "TEXT")
    private String badgeFields;

    @Column(name = "start_date_time", nullable = false)
    private LocalDateTime startDateTime;

    @Column(name = "end_date_time", nullable = false)
    private LocalDateTime endDateTime;

    /** Unique, non-guessable badge token (Code128). */
    @Column(name = "barcode_value", nullable = false, unique = true)
    private String barcodeValue;

    /** BCrypt hash of the 6-digit access code — never the plaintext. */
    @Column(name = "access_code_hash", nullable = false)
    private String accessCodeHash;

    /** ACTIVE or REVOKED (EXPIRED is derived from the time window, not stored). */
    @Column(name = "status", nullable = false)
    private String status;

    /** CSV of permitted page permission keys. */
    @Column(name = "permissions", columnDefinition = "TEXT")
    private String permissions;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_date")
    private LocalDateTime createdDate;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    @PrePersist
    protected void onCreate() {
        if (this.createdDate == null) this.createdDate = LocalDateTime.now();
        if (this.status == null) this.status = "ACTIVE";
    }
}
