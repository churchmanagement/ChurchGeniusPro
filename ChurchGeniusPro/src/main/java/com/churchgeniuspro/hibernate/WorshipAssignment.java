package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;

/**
 * A worship assignment for a specific date and group.
 * Each combination of (client_id, group_id, assignment_date) represents
 * one Sunday (or any day) service plan for that group.
 */
@Data
@Entity
@Table(name = "worship_assignment",
       uniqueConstraints = @UniqueConstraint(
               name = "uq_worship_assignment_client_group_date",
               columnNames = {"client_id", "group_id", "assignment_date"}),
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

    @Column(name = "assignment_type", length = 20)
    private String assignmentType = "manual";

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
