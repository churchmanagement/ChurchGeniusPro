package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;

/**
 * Append-only note / comment thread on a {@link PrayerRequest}.
 *
 * <p>Used for follow-up notes, prayer updates, contact notes, and internal
 * ministry notes. Notes are never edited after save — adding a correction
 * means adding a new note — which keeps the audit story simple and matches
 * the v1 scope decision.
 *
 * <p>{@link #createdByUserId} and {@link #createdByName} are captured at
 * write time so renaming a user / removing a volunteer does not mutate
 * historic note attribution.
 */
@Data
@Entity
@Table(name = "prayer_note")
public class PrayerNote {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "prayer_note_seq")
    @SequenceGenerator(name = "prayer_note_seq", sequenceName = "prayer_note_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** FK → prayer_request.id. */
    @Column(name = "prayer_request_id", nullable = false)
    private Long prayerRequestId;

    @Column(name = "body", columnDefinition = "TEXT", nullable = false)
    private String body;

    /** USR<uuid> token of the AppUser who wrote the note, or "system". */
    @Column(name = "created_by_user_id", length = 100)
    private String createdByUserId;

    /** Frozen display name at write time — survives later renames. */
    @Column(name = "created_by_name", length = 200)
    private String createdByName;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    @PrePersist
    void onCreate() {
        if (createdDate == null) createdDate = new Date();
    }
}
