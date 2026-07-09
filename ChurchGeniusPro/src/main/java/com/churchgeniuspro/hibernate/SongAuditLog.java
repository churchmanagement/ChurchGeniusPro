package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/**
 * Append-only audit trail for Song Book actions (upload, add, edit, delete,
 * reorder, finalize, lyrics changes, publish, access grants, public views).
 * Tenant-scoped by {@code client_id}.
 */
@Data
@Entity
@Table(name = "song_audit_log")
public class SongAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** Username or member ref of the actor (or "public"). */
    @Column(name = "actor", length = 120)
    private String actor;

    @Column(name = "role", length = 40)
    private String role;

    /** UPLOAD, ADD, EDIT, DELETE, REORDER, FINALIZE, UNFINALIZE,
     *  LYRICS_SET, LYRICS_DELETE, PUBLISH, UNPUBLISH, ACCESS_GRANT, PUBLIC_VIEW */
    @Column(name = "action", nullable = false, length = 40)
    private String action;

    @Column(name = "song_id")
    private Long songId;

    @Column(name = "song_title", length = 500)
    private String songTitle;

    @Column(name = "detail", length = 1000)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
