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

    /** Display name of the teacher or staff member who uploaded it. */
    @Column(name = "uploaded_by_name", length = 200)
    private String uploadedByName;

    /**
     * Whether students enrolled in this class may see, download and print it.
     *
     * <p>Defaults to {@code false}, and the column default matters: rows that
     * existed before this feature were uploaded when the Files section was
     * staff-only, so they may be lesson plans or answer keys. They stay private
     * until someone deliberately shares them. New uploads opt in at upload time.
     */
    @org.hibernate.annotations.ColumnDefault("false")
    @Column(name = "shared_with_students", nullable = false)
    private boolean sharedWithStudents = false;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
