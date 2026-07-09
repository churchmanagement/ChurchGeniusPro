package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Stores the uploaded church logo image (binary data) per organization.
 * One record per {@code client_id} — upserted on every upload.
 */
@Data
@Entity
@Table(name = "church_logo",
       uniqueConstraints = @UniqueConstraint(name = "uq_church_logo_client", columnNames = {"client_id"}))
public class ChurchLogo {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "church_logo_seq")
    @SequenceGenerator(name = "church_logo_seq", sequenceName = "church_logo_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Organization identifier — one row per org. */
    @Column(name = "client_id", nullable = false, unique = true)
    private String clientId;

    /** Raw image bytes (PNG / JPG / GIF / WebP). */
    @Column(name = "logo_data", columnDefinition = "bytea")
    private byte[] logoData;

    /** MIME type stored at upload time (e.g. "image/png"). */
    @Column(name = "content_type", length = 100)
    private String contentType;

    /** Original file name as supplied by the browser. */
    @Column(name = "original_file_name", length = 255)
    private String originalFileName;
}
