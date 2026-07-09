package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.Instant;

/**
 * Per-member Song Book access level, granted by an Admin / Super Admin on the
 * dedicated "Song Book Access" page.
 *
 * <p>Levels:
 * <ul>
 *   <li>{@code NONE} — no access (default for members with no row).</li>
 *   <li>{@code VIEW} — may view the song list and the finalized/published book.</li>
 *   <li>{@code FULL} — may upload, edit, delete, reorder, finalize, manage lyrics
 *       and publish the song book.</li>
 * </ul>
 *
 * Tenant-scoped by {@code client_id}; {@code member_id} is the member's
 * {@code FamilyMember.id} (matches the member-portal session {@code memberId}).
 */
@Data
@Entity
@Table(name = "song_book_access",
       uniqueConstraints = @UniqueConstraint(columnNames = {"client_id", "member_id"}))
public class SongBookAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    /** NONE | VIEW | FULL */
    @Column(name = "access_level", nullable = false, length = 10)
    private String accessLevel = "NONE";

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
