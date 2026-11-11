package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;

/**
 * Maps to the pre-existing {@code serviceadmin} table.
 * The table is seeded manually via an INSERT script — Hibernate
 * will only add missing columns (DDL = update).
 */
@Entity
@Table(name = "serviceadmin")
@Data
@ToString(exclude = {"password"})   // never in a log line, even by accident (2026-10-28 logging audit)
public class ServiceAdmin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    private String username;

    private String password;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private Boolean deleted = false;

    /**
     * Credential version (2026-11-11, App Store security review): raised by one every time this service admin's password is
     * set. A session records the version it signed in with ({@code util.CredentialSessions}); ServiceAdminAuthFilter refuse a session whose version no longer matches, so a password change or reset ends every
     * other open session on every browser, device and app instance. Existing rows start at 0.
     */
    @org.hibernate.annotations.ColumnDefault("0")
    @Column(name = "credential_version", nullable = false)
    private Integer credentialVersion = 0;

    /** Call whenever the password is set to a new value (reset, claim, admin reset). */
    public void markCredentialChanged() {
        credentialVersion = (credentialVersion == null ? 0 : credentialVersion) + 1;
    }
}
