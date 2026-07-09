package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

/** A student's answer to a specific question in a submission. */
@Data
@Entity
@Table(name = "ss_answer",
       uniqueConstraints = @UniqueConstraint(name = "uq_ss_answer_submission_question",
               columnNames = {"submission_id", "question_id"}))
public class SsAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ss_answer_seq")
    @SequenceGenerator(name = "ss_answer_seq", sequenceName = "ss_answer_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "submission_id", nullable = false)
    private Long submissionId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(name = "answer_text", columnDefinition = "TEXT")
    private String answerText;

    /** Whether the answer was auto-graded as correct. Null if not auto-gradable. */
    @Column(name = "is_correct")
    private Boolean isCorrect;

    /** Teacher-assigned marks for manual questions. */
    @Column(name = "manual_marks")
    private Integer manualMarks;

    /**
     * System-suggested marks computed at submission time by the AnswerGrader.
     * For fuzzy short answers: 0 or full marks.
     * For comprehensive (LongAnswer): partial marks proportional to keyword coverage.
     * Null = not yet computed (e.g. auto-gradable MCQ).
     */
    @Column(name = "suggested_marks")
    private Integer suggestedMarks;

    /**
     * JSON array of matched keyword/phrase strings for comprehensive questions,
     * stored so the teacher UI can highlight them without recomputing.
     * Example: ["Holy Spirit","baptized","repent"]
     */
    @Column(name = "match_highlights", columnDefinition = "TEXT")
    private String matchHighlights;
}
