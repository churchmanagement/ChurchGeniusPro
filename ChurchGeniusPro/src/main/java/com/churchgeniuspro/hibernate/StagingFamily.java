package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Target-shaped staging row for the {@code family} import. Mirrors the columns we
 * are about to insert into {@code family_member} (so "preview = what loads"),
 * plus the raw source payload and pipeline bookkeeping (see the ETL design doc,
 * §4.2). Tenant-scoped; nothing here is live until a batch loads it.
 *
 * <p>Phase 1 scope: schema only. Transform/validate (Phase 4) populate it; the
 * loader (Phase 6) reads it. {@code source_payload}/{@code source_row_id} give
 * full traceability back to the origin row.
 */
@Data
@Entity
@Table(name = "staging_family",
       indexes = {
           @Index(name = "ix_stg_family_run",    columnList = "run_id, row_status"),
           @Index(name = "ix_stg_family_tenant", columnList = "client_id")
       })
public class StagingFamily {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "run_id", nullable = false)        private Long runId;
    @Column(name = "client_id", nullable = false, length = 64) private String clientId;

    /** Locator in the source; child rows reference families by this key. */
    @Column(name = "source_row_id", length = 128)     private String sourceRowId;
    /** Natural family grouping key from the source (links income/expense). */
    @Column(name = "family_key", length = 128)        private String familyKey;
    /** Exact raw source row as JSON. */
    @Column(name = "source_payload", columnDefinition = "TEXT", nullable = false) private String sourcePayload;

    // ── Target-shaped, post-transform columns (mirror FamilyMember) ──
    @Column(name = "first_name",  length = 255) private String firstName;
    @Column(name = "last_name",   length = 255) private String lastName;
    @Column(name = "other_name",  length = 255) private String otherName;   // nickname
    @Column(name = "email",       length = 255) private String email;
    @Column(name = "phone",       length = 64)  private String phone;
    @Column(name = "gender",      length = 16)  private String gender;
    @Column(name = "member_type", length = 32)  private String memberType;
    @Column(name = "role",        length = 64)  private String role;
    @Column(name = "address1",    length = 255) private String address1;
    @Column(name = "address2",    length = 255) private String address2;
    @Column(name = "city",        length = 128) private String city;
    @Column(name = "state",       length = 64)  private String state;
    @Column(name = "country",     length = 64)  private String country;
    @Column(name = "pin_code",    length = 32)  private String pinCode;
    @Column(name = "birthday_month") private Integer birthdayMonth;
    @Column(name = "birthday_day")   private Integer birthdayDay;
    @Column(name = "birthday_year")  private Integer birthdayYear;
    @Column(name = "phone_private")   private Boolean phonePrivate;
    @Column(name = "email_private")   private Boolean emailPrivate;
    @Column(name = "address_private") private Boolean addressPrivate;

    // ── Pipeline bookkeeping ──
    /** PENDING | VALID | WARN | ERROR | APPROVED | REJECTED | LOADED | SKIPPED | FAILED */
    @Column(name = "row_status", nullable = false, length = 16) private String rowStatus = "PENDING";
    /** JSON array of {field, level, message}. */
    @Column(name = "validation_msgs", columnDefinition = "TEXT") private String validationMsgs;
    /** Live family_member.id when a tenant-scoped duplicate was detected. */
    @Column(name = "dedupe_match_id") private Long dedupeMatchId;
    /** INSERT | UPDATE | SKIP  (default SKIP unless the operator confirms). */
    @Column(name = "dedupe_action", length = 16) private String dedupeAction;
    /** Live row id after load (for rollback + traceability). */
    @Column(name = "target_id") private Long targetId;
    /** Live parent Family id created/used at load (for rollback). */
    @Column(name = "parent_target_id") private Long parentTargetId;
    /** JSON snapshot of the live row BEFORE an UPDATE load (for rollback). Null for INSERTs. */
    @Column(name = "before_image", columnDefinition = "TEXT") private String beforeImage;
    @Column(name = "batch_id")  private Long batchId;

    @Column(name = "created_date", nullable = false, updatable = false) private LocalDateTime createdDate;

    @PrePersist
    void onCreate() { if (createdDate == null) createdDate = LocalDateTime.now(); }
}
