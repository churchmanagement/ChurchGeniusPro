package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A question belonging to a Sunday School exam.
 * Types: MCQ, ShortAnswer, LongAnswer, TrueFalse, FillBlank.
 */
@Data
@Entity
@Table(name = "ss_question")
public class SsQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ss_question_seq")
    @SequenceGenerator(name = "ss_question_seq", sequenceName = "ss_question_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "exam_id", nullable = false)
    private Long examId;

    /** MCQ | ShortAnswer | LongAnswer | TrueFalse | FillBlank */
    @Column(name = "question_type", nullable = false, length = 50)
    private String questionType;

    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT")
    private String questionText;

    /** JSON array of choices for MCQ, e.g. ["A","B","C","D"]. */
    @Column(name = "options_json", columnDefinition = "TEXT")
    private String optionsJson;

    /** Correct answer (for auto-gradable types). */
    @Column(name = "correct_answer", columnDefinition = "TEXT")
    private String correctAnswer;

    @Column(name = "marks")
    private Integer marks = 1;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
