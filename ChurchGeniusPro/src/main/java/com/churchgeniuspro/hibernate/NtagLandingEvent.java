package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/** One analytics event on a public NTAG landing page — a page view or a button click. */
@Data
@Entity
@Table(name = "ntag_landing_event")
public class NtagLandingEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** view | click */
    @Column(name = "type", nullable = false, length = 16)
    private String type;

    @Column(name = "button_key", length = 60)
    private String buttonKey;

    @Column(name = "button_label", length = 120)
    private String buttonLabel;

    @Column(name = "ip_address", length = 60)
    private String ipAddress;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
