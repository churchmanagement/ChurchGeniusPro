package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Per-tenant Kids Ministry "Children Setup" options. Each flag toggles whether a
 * section appears throughout the module — the online Register Child form, the
 * child detail screen, and the downloadable PDF form. All sections are ON by
 * default (a missing row = everything enabled), so existing tenants are unaffected
 * until they explicitly disable something.
 */
@Data
@Entity
@Table(name = "km_child_setup")
public class KmChildSetup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, unique = true, length = 100)
    private String clientId;

    @Column(name = "classroom_enabled", nullable = false)
    private boolean classroomEnabled = true;

    @Column(name = "emergency_contact_enabled", nullable = false)
    private boolean emergencyContactEnabled = true;

    @Column(name = "medical_info_enabled", nullable = false)
    private boolean medicalInfoEnabled = true;

    @Column(name = "allergies_enabled", nullable = false)
    private boolean allergiesEnabled = true;

    @Column(name = "medical_notes_enabled", nullable = false)
    private boolean medicalNotesEnabled = true;

    @Column(name = "authorized_pickup_enabled", nullable = false)
    private boolean authorizedPickupEnabled = true;

    // ── Pickup-expiration / overdue-alert configuration ──

    /** Default scheduled pickup time-of-day for children checked in today (HH:mm). */
    @Column(name = "default_pickup_time", length = 5)
    private String defaultPickupTime;

    /** Time-of-day after which a still-checked-in child is overdue (HH:mm). Falls back to defaultPickupTime. */
    @Column(name = "pickup_expiration_time", length = 5)
    private String pickupExpirationTime;

    @Column(name = "email_alerts_enabled", nullable = false)
    private boolean emailAlertsEnabled = false;

    @Column(name = "sms_alerts_enabled", nullable = false)
    private boolean smsAlertsEnabled = false;

    /** JSON array of alert recipients: [{name,email,phone}, ...]. */
    @Column(name = "alert_recipients", columnDefinition = "TEXT")
    private String alertRecipients;

    // ── Duplicate-detection matching criteria (used on import) ──
    // columnDefinition carries a DB-level "default true" so ddl-auto=update can ADD these
    // NOT NULL columns to an ALREADY-POPULATED km_child_setup table. Without a default,
    // Postgres rejects "ADD COLUMN ... not null" on a table that has rows
    // ("column ... contains null values"), which aborts schema migration on startup.
    @Column(name = "dedupe_name", columnDefinition = "boolean not null default true")      private boolean dedupeName = true;     // child name (+ DOB)
    @Column(name = "dedupe_phone", columnDefinition = "boolean not null default true")     private boolean dedupePhone = true;    // parent/guardian phone
    @Column(name = "dedupe_email", columnDefinition = "boolean not null default true")     private boolean dedupeEmail = true;    // parent/guardian email
    @Column(name = "dedupe_member_id", columnDefinition = "boolean not null default true") private boolean dedupeMemberId = true; // linked member id

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** A fresh, all-enabled config for a tenant that has never saved Setup. */
    public static KmChildSetup defaults(String clientId) {
        KmChildSetup s = new KmChildSetup();
        s.setClientId(clientId);
        return s;   // all booleans already default true
    }
}
