package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** A student's exam submission record. */
@Data
@Entity
@Table(name = "ss_submission",
       uniqueConstraints = @UniqueConstraint(name = "uq_ss_submission_exam_student",
               columnNames = {"exam_id", "student_id"}))
public class SsSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ss_submission_seq")
    @SequenceGenerator(name = "ss_submission_seq", sequenceName = "ss_submission_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "exam_id", nullable = false)
    private Long examId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    /** One of: InProgress, Submitted, TimedOut. */
    @Column(name = "status", nullable = false, length = 50)
    private String status = "InProgress";

    /** Auto-calculated marks (MCQ + TrueFalse + FillBlank). */
    @Column(name = "auto_marks")
    private Integer autoMarks;

    /** Teacher-assigned marks for short/long answers. */
    @Column(name = "manual_marks")
    private Integer manualMarks;

    /** Total marks (auto + manual). */
    @Column(name = "total_marks")
    private Integer totalMarks;

    /**
     * Extra minutes granted by a teacher/admin after the exam started.
     * Added to the original durationMinutes to extend the student's timer.
     */
    @Column(name = "extra_time_minutes", nullable = false, columnDefinition = "integer not null default 0")
    private int extraTimeMinutes = 0;

    /** Set to true when a teacher has finalized and submitted the review; status becomes "Graded". */
    @Column(name = "reviewed", nullable = false, columnDefinition = "boolean not null default false")
    private boolean reviewed = false;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
