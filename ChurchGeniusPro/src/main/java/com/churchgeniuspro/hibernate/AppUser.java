package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.util.Date;
import java.util.UUID;

/**
 * Hibernate entity for the {@code app_user} table.
 *
 * <p>The table is named {@code app_user} (not {@code user}) because
 * {@code user} is a reserved keyword in PostgreSQL.
 *
 * <p>Roles: SuperAdmin, Admin, Accountant, User.
 * The {@code enabled} flag controls login access without physical deletion.
 * Physical records are never removed — a {@code delete_flag} is used instead.
 */
@Data
@Entity
@Table(name = "app_user", uniqueConstraints = {
        @UniqueConstraint(name = "uq_app_user_email_role_client",
                          columnNames = {"email", "role", "client_id"})
})
public class AppUser {

    // ── Primary Key ───────────────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "app_user_seq")
    @SequenceGenerator(
            name           = "app_user_seq",
            sequenceName   = "app_user_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    // ── Signup token ──────────────────────────────────────────────────────

    /**
     * Unique UUID token generated at creation time.
     * Used to build the signup link sent to the user.
     */
    @Column(name = "user_id", nullable = false, unique = true, updatable = false)
    private String userId;

    /**
     * Random token in the staff invitation link ({@code /signup?clientId=…}).
     * Minted each time the invite is (re)sent and cleared when the signup completes,
     * so a link is single-use and a re-sent invite invalidates the earlier one.
     */
    @Column(name = "invite_token", unique = true, length = 64)
    private String inviteToken;

    // ── Organization link ─────────────────────────────────────────────────

    /**
     * Service-client identifier (e.g. {@code CGP-00001}) inherited from the
     * logged-in administrator who created this user.  Links this user record
     * to the correct organization in the {@code service_client} table.
     * Hibernate DDL ({@code ddl-auto=update}) will add the column automatically.
     */
    @Column(name = "client_id")
    private String clientId;

    // ── Personal Information ──────────────────────────────────────────────

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "phone")
    private String phone;

    // ── Address ───────────────────────────────────────────────────────────

    @Column(name = "address1") private String address1;
    @Column(name = "address2") private String address2;
    @Column(name = "city")     private String city;
    @Column(name = "state")    private String state;
    @Column(name = "country")  private String country;
    @Column(name = "pin_code") private String pinCode;

    // ── Role & Status ─────────────────────────────────────────────────────

    /** Values: SuperAdmin, Admin, Accountant, User. */
    @Column(name = "role", nullable = false)
    private String role;

    /** {@code true} = account is active; {@code false} = account is disabled. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /**
     * {@code true} once the user has verified control of their email address
     * (via the OTP flow). Required before connecting a bank account for Plaid
     * sync. columnDefinition supplies a default so the column can be added to
     * tables with existing rows under {@code ddl-auto=update}.
     */
    @Column(name = "email_verified", nullable = false, columnDefinition = "boolean default false")
    private boolean emailVerified;

    /** Soft-delete flag — record is never physically removed. */
    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    /**
     * Optional link-group identifier.  Users that share the same non-null
     * {@code link_group} value are "linked accounts" and can switch between
     * their roles without re-entering credentials.  A UUID is generated when
     * accounts are first linked; set to {@code null} to unlink.
     */
    @Column(name = "link_group")
    private String linkGroup;

    /**
     * Granular privilege map stored as a JSON string.
     * Keys are dot-separated paths (e.g. "admin.family.edit").
     * A missing key or {@code false} value means the privilege is revoked.
     * {@code null} means all privileges are granted (default full access).
     */
    @Column(name = "privileges", columnDefinition = "TEXT")
    private String privileges;

    // ── Audit ─────────────────────────────────────────────────────────────

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @PrePersist
    protected void onCreate() {
        this.createdDate = new Date();
        this.enabled     = true;
        this.deleteFlag  = false;
        if (this.userId == null) {
        	
            this.userId = "USR" + UUID.randomUUID().toString();
        }
        if (this.country == null || this.country.isBlank()) {
            this.country = "USA";
        }
    }
}
