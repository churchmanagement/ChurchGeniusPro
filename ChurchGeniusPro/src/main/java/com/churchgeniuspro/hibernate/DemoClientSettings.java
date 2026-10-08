package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Per-tenant switches for a demo/test client.
 *
 * <p>Exists so a demo tenant cannot text or email real people by accident: both
 * channels default to BLOCKED and a service admin has to turn them on
 * deliberately, per client, without regenerating the demo data.
 */
@Data
@Entity
@Table(name = "demo_client_settings")
public class DemoClientSettings {

    /** The demo tenant's client_id — one settings row per tenant. */
    @Id
    @Column(name = "client_id", length = 100, nullable = false)
    private String clientId;

    /** Real SMS delivery for this demo tenant. Blocked unless switched on. */
    @Column(name = "allow_sms", nullable = false)
    @ColumnDefault("false")
    private Boolean allowSms = Boolean.FALSE;

    /** Real email delivery for this demo tenant. Blocked unless switched on. */
    @Column(name = "allow_email", nullable = false)
    @ColumnDefault("false")
    private Boolean allowEmail = Boolean.FALSE;

    /**
     * The expiry chosen when the demo data was loaded. Used as the default end
     * date when a new role is added later, so roles line up with their tenant.
     */
    @Column(name = "default_end_date")
    private LocalDate defaultEndDate;

    /** 30 / 60 / null for a custom date — remembered only to prefill the UI. */
    @Column(name = "default_expiry_days")
    private Integer defaultExpiryDays;

    /**
     * Days-before-expiry reminder points, comma separated, e.g. {@code "10,5,1"}.
     * Reminder dates are never stored: they are computed from each role's CURRENT
     * end date every time the scheduler runs, so moving an expiry moves its
     * reminders automatically.
     */
    @Column(name = "reminder_days", length = 100)
    @ColumnDefault("'10,5,1'")
    private String reminderDays = "10,5,1";

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
