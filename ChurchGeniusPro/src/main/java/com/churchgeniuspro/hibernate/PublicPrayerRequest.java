package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A prayer request submitted by the public (NTAG landing page, public website).
 * Kept separate from the internal {@code PrayerRequest} list so staff can manage
 * public submissions, assign prayer volunteers, track status, and follow up.
 */
@Data
@Entity
@Table(name = "public_prayer_request")
public class PublicPrayerRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "first_name", length = 120)
    private String firstName;

    @Column(name = "last_name", length = 120)
    private String lastName;

    @Column(name = "email", length = 200)
    private String email;

    @Column(name = "phone", length = 40)
    private String phone;

    @Column(name = "request_text", columnDefinition = "TEXT")
    private String requestText;

    /** May we share this with the prayer team? */
    @Column(name = "share_with_team", nullable = false, columnDefinition = "boolean not null default false")
    private boolean shareWithTeam;

    /** NTAG | PUBLIC | WEBSITE */
    @Column(name = "source", nullable = false, length = 20)
    private String source = "PUBLIC";

    /** New | Assigned | InProgress | PrayedFor | FollowedUp | Closed */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "New";

    @Column(name = "assigned_volunteer_id")
    private Integer assignedVolunteerId;

    @Column(name = "assigned_to", length = 150)
    private String assignedTo;

    /** Linked Follow-Up record id (auto-created on submission). */
    @Column(name = "follow_up_id")
    private Long followUpId;

    @Column(name = "delete_flag", nullable = false, columnDefinition = "boolean not null default false")
    private boolean deleteFlag;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() { this.createdAt = LocalDateTime.now(); this.updatedAt = this.createdAt; }
    @PreUpdate
    void onUpdate() { this.updatedAt = LocalDateTime.now(); }
}
