package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Represents an SMS opt-in record for a church congregation member.
 *
 * <p>Consent lifecycle:
 * <ol>
 *   <li>User submits the opt-in form → {@code consent=true, confirmed=false}</li>
 *   <li>Confirmation SMS sent: "Reply YES to confirm"</li>
 *   <li>User replies YES → {@code confirmed=true}</li>
 *   <li>User replies STOP → {@code consent=false}</li>
 *   <li>User replies START → {@code consent=true, confirmed=true}</li>
 * </ol>
 *
 * <p>Only records with {@code consent=true AND confirmed=true} receive outbound SMS.
 */
@Entity
@Table(name = "sms_opt_in",
       uniqueConstraints = @UniqueConstraint(name = "uq_sms_opt_in_phone_client",
                                             columnNames = {"phone_number", "app_client_id"}))
public class SmsOptIn {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sms_opt_in_seq")
    @SequenceGenerator(name = "sms_opt_in_seq", sequenceName = "sms_opt_in_id_seq", allocationSize = 1)
    private Integer id;

    /** Church's appClientId — ties the record to the correct church. */
    @Column(name = "app_client_id", nullable = false)
    private String appClientId;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    /** E.164 format, e.g. +12345678901 */
    @Column(name = "phone_number", nullable = false)
    private String phoneNumber;

    /**
     * True when the user has agreed to receive SMS.
     * Set to false when user replies STOP.
     */
    @Column(name = "consent", nullable = false)
    private boolean consent = false;

    /**
     * True after the user replies YES to the confirmation SMS.
     */
    @Column(name = "confirmed", nullable = false)
    private boolean confirmed = false;

    @Column(name = "opted_in_at")
    private LocalDateTime optedInAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "opted_out_at")
    private LocalDateTime optedOutAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public Integer getId() { return id; }

    public String getAppClientId() { return appClientId; }
    public void setAppClientId(String appClientId) { this.appClientId = appClientId; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public boolean isConsent() { return consent; }
    public void setConsent(boolean consent) { this.consent = consent; }

    public boolean isConfirmed() { return confirmed; }
    public void setConfirmed(boolean confirmed) { this.confirmed = confirmed; }

    public LocalDateTime getOptedInAt() { return optedInAt; }
    public void setOptedInAt(LocalDateTime optedInAt) { this.optedInAt = optedInAt; }

    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }

    public LocalDateTime getOptedOutAt() { return optedOutAt; }
    public void setOptedOutAt(LocalDateTime optedOutAt) { this.optedOutAt = optedOutAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
