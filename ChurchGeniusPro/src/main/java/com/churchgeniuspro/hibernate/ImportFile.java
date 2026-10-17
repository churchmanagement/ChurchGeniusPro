package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Migration Phase 1 — one uploaded source file of an import run: its name, size,
 * SHA-256, format and row count. Gives the import history a file identity and lets
 * the extract step refuse the same file for the same church twice (the hash is
 * compared across every non-discarded run of that tenant) unless the admin asks
 * for a deliberate re-import. Tenant-scoped like every other ETL table.
 */
@Data
@Entity
@Table(name = "import_file", indexes = {
    @Index(name = "idx_import_file_run", columnList = "run_id"),
    @Index(name = "idx_import_file_hash", columnList = "client_id, sha256")
})
public class ImportFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    /** Source table the file was extracted into. */
    @Column(name = "source_table", length = 128)
    private String sourceTable;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** Lower-case hex SHA-256 of the uploaded bytes. */
    @Column(name = "sha256", length = 64, nullable = false)
    private String sha256;

    /** csv | json | xlsx | xls */
    @Column(name = "format", length = 8)
    private String format;

    @Column(name = "row_count")
    private Integer rowCount;

    /** The source's own id column found in the header (preset), or null. */
    @Column(name = "id_column", length = 128)
    private String idColumn;

    /** Migration Phase 5: the data type this file holds (chosen in the wizard, or detected from its headers). */
    @Column(name = "target_table", length = 32)
    private String targetTable;

    /** Migration Phase 5: what header detection suggested, with its score — kept even when the admin picks another type. */
    @Column(name = "suggested_table", length = 32)
    private String suggestedTable;

    @Column(name = "uploaded_by", length = 128)
    private String uploadedBy;

    /** True when the admin chose to re-import a file already imported for this church. */
    @Column(name = "forced")
    private Boolean forced;

    @Column(name = "created_date", nullable = false, updatable = false)
    private LocalDateTime createdDate;

    @PrePersist
    void onCreate() {
        if (createdDate == null) createdDate = LocalDateTime.now();
    }
}
