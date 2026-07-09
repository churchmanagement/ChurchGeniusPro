package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * A volunteer activity entry on a public prayer request — a general note, a
 * recorded contact attempt, prayer activity, or a follow-up outcome. Forms the
 * communication history / activity timeline for the request.
 */
@Data
@Entity
@Table(name = "public_prayer_note")
public class PublicPrayerNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "public_prayer_id", nullable = false)
    private Long publicPrayerId;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** NOTE | CONTACT_ATTEMPT | PRAYER_ACTIVITY | OUTCOME */
    @Column(name = "note_type", nullable = false, length = 30)
    private String noteType = "NOTE";

    @Column(name = "note_text", columnDefinition = "TEXT")
    private String noteText;

    @Column(name = "author", length = 150)
    private String author;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
