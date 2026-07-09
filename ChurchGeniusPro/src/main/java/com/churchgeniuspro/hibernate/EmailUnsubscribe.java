package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Tracks email addresses that have opted out of church communications.
 * Keyed by (email, client_id) so each organization manages its own list.
 */
@Data
@Entity
@Table(name = "email_unsubscribe",
       uniqueConstraints = @UniqueConstraint(columnNames = {"email", "client_id"}))
public class EmailUnsubscribe {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "email_unsub_seq")
    @SequenceGenerator(name = "email_unsub_seq", sequenceName = "email_unsubscribe_id_seq", allocationSize = 1)
    @Column(name = "id")
    private Integer id;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "unsubscribed_at")
    private LocalDateTime unsubscribedAt;

    @PrePersist
    public void onCreate() {
        if (unsubscribedAt == null) unsubscribedAt = LocalDateTime.now();
    }
}
