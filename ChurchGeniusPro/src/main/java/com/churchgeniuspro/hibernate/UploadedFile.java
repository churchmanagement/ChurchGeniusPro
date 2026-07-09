package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

import java.util.Date;

/**
 * Hibernate entity for the {@code uploaded_file} table.
 *
 * <p>Stores uploaded document files with their binary content and metadata.
 * Records are soft-deleted via {@code delete_flag}.
 */
@Data
@Entity
@Table(name = "uploaded_file")
public class UploadedFile {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "uploaded_file_seq")
    @SequenceGenerator(
            name           = "uploaded_file_seq",
            sequenceName   = "uploaded_file_id_seq",
            allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    /** MIME type / content type (e.g. "application/pdf"). */
    @Column(name = "file_type", length = 100)
    private String fileType;

    /** File size in bytes. */
    @Column(name = "file_size")
    private Long fileSize;

    /** Base64-encoded file content (data URI). */
    @Column(name = "file_data", columnDefinition = "TEXT")
    private String fileData;

    @Column(name = "app_client_id")
    private String appClientId;

    /** User ID of the uploader. */
    @Column(name = "uploaded_by_id")
    private Integer uploadedById;

    /** Display name of the uploader. */
    @Column(name = "uploaded_by_name")
    private String uploadedByName;

    @Column(name = "upload_date", nullable = false, updatable = false)
    private Date uploadDate;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    @PrePersist
    protected void onCreate() {
        this.uploadDate  = new Date();
        this.deleteFlag  = false;
    }
}
