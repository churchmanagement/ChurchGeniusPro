package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Audit log for "Forgot Username" recovery attempts.
 * Used for rate-limiting (max 5 per identifier per hour, block after 5).
 */
@Data
@Entity
@Table(name = "username_recovery_log")
public class UsernameRecoveryLog {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "username_recovery_log_seq")
    @SequenceGenerator(name = "username_recovery_log_seq", sequenceName = "username_recovery_log_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** The email or phone number submitted (lowercased/normalised). */
    @Column(name = "identifier", nullable = false, length = 300)
    private String identifier;

    /** "email" or "phone" */
    @Column(name = "identifier_type", nullable = false, length = 10)
    private String identifierType;

    /** IP address of the requesting client. */
    @Column(name = "ip_address", length = 100)
    private String ipAddress;

    /** Whether at least one match was found (false = no account, still logged). */
    @Column(name = "match_found", nullable = false)
    private boolean matchFound;

    @Column(name = "attempted_at", nullable = false)
    private LocalDateTime attemptedAt;

    @PrePersist
    protected void onCreate() {
        this.attemptedAt = LocalDateTime.now();
    }
}
