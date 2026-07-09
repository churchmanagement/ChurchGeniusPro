package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

import java.util.Date;

/**
 * Hibernate entity for the {@code note} table.
 *
 * <p>Stores user notes with title and rich-text body.
 * Only the creator can edit or delete their note.
 * Notes can be starred for quick access.
 * Records are soft-deleted via {@code delete_flag}.
 */
@Data
@Entity
@Table(name = "note")
public class Note {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "note_seq")
    @SequenceGenerator(
            name           = "note_seq",
            sequenceName   = "note_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    /** User ID of the creator. */
    @Column(name = "created_by_id")
    private Integer createdById;

    /** Display name of the creator. */
    @Column(name = "created_by_name")
    private String createdByName;

    /** Whether this note is starred (favourited) by its creator. */
    @Column(name = "starred", nullable = false)
    private boolean starred;

    @Column(name = "app_client_id")
    private String appClientId;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    @Column(name = "updated_date")
    private Date updatedDate;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    @PrePersist
    protected void onCreate() {
        this.createdDate = new Date();
        this.deleteFlag  = false;
        this.starred     = false;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedDate = new Date();
    }
}
