package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.ColumnDefault;
import java.time.LocalDate;

/**
 * A worship assignment for a specific date and group.
 * Each combination of (client_id, group_id, assignment_date, assignment_type)
 * represents one Sunday (or any day) service plan for that group in ONE
 * workflow: {@code manual} (user-built schedule) or {@code auto} (generated
 * rotation). The two workflows never share a row, so editing one can never
 * overwrite the other.
 *
 * <p>Uniqueness is enforced by the partial index {@code ux_worship_assignment_active}
 * (… WHERE delete_flag = false), created by Flyway V13 /
 * {@code config/WorshipSchemaInitializer} — NOT by a JPA constraint, because the
 * key must ignore soft-deleted rows (regenerating a schedule soft-deletes the old
 * dates and re-creates them).
 */
@Data
@Entity
@Table(name = "worship_assignment",
       indexes = {
           @Index(name = "idx_worship_assignment_client_date",
                  columnList = "client_id, assignment_date"),
           @Index(name = "idx_worship_assignment_client_flag",
                  columnList = "client_id, delete_flag")
       })
public class WorshipAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "worship_assignment_seq")
    @SequenceGenerator(name = "worship_assignment_seq", sequenceName = "worship_assignment_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "assignment_date", nullable = false)
    private LocalDate assignmentDate;

    /** Workflow that owns this row: {@code manual} or {@code auto}. Part of the key. */
    @Column(name = "assignment_type", nullable = false, length = 20)
    @ColumnDefault("'manual'")
    private String assignmentType = "manual";

    /**
     * Manual workflow only: the date is marked "skipped" (no service / not
     * scheduled) but its members are KEPT, so un-skipping restores them exactly.
     * Automatic Assignment never sets this (its skip handling is client-side).
     * Skipped rows are hidden from the read-only schedule views.
     */
    @Column(name = "skipped", nullable = false)
    @ColumnDefault("false")
    private boolean skipped = false;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
