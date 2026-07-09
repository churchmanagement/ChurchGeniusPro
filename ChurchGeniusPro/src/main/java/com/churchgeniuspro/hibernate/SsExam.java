package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;

/** A Sunday School exam for a class. */
@Data
@Entity
@Table(name = "ss_exam")
public class SsExam {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ss_exam_seq")
    @SequenceGenerator(name = "ss_exam_seq", sequenceName = "ss_exam_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "exam_title", nullable = false, length = 300)
    private String examTitle;

    /** Total questions in the paper. */
    @Column(name = "total_questions")
    private Integer totalQuestions;

    /** How many questions the student must answer. */
    @Column(name = "required_questions")
    private Integer requiredQuestions;

    /** Maximum duration in minutes (null = no limit). */
    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "exam_date")
    private LocalDate examDate;

    /** One of: Draft, Published, Closed. */
    @Column(name = "status", nullable = false, length = 50)
    private String status = "Draft";

    /** When true, a copy of each submission email is also sent to the child's Head of Household. */
    @Column(name = "copy_to_hoh", nullable = false, columnDefinition = "boolean not null default false")
    private boolean copyToHoh = false;

    @Column(name = "delete_flag", nullable = false, columnDefinition = "boolean not null default false")
    private boolean deleteFlag = false;
}
