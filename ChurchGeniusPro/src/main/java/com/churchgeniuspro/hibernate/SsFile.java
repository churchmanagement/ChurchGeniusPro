package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** A file uploaded for a Sunday School class (lesson materials, notes, etc.). */
@Data
@Entity
@Table(name = "ss_file")
public class SsFile {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ss_file_seq")
    @SequenceGenerator(name = "ss_file_seq", sequenceName = "ss_file_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "original_name", nullable = false, length = 300)
    private String originalName;

    /** MIME type. */
    @Column(name = "content_type", length = 200)
    private String contentType;

    /** Base64-encoded file content (stored in DB for simplicity). */
    @Column(name = "file_data", columnDefinition = "TEXT")
    private String fileData;

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
