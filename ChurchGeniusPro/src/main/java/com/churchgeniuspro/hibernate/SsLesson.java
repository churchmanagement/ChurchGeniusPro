package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;

/** A lesson for a student in a Sunday School class. */
@Data
@Entity
@Table(name = "ss_lesson")
public class SsLesson {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ss_lesson_seq")
    @SequenceGenerator(name = "ss_lesson_seq", sequenceName = "ss_lesson_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "lesson_title", nullable = false, length = 300)
    private String lessonTitle;

    /** One of: Pending, In Progress, Done, Skipped. */
    @Column(name = "status", nullable = false, length = 50)
    private String status = "Pending";

    @Column(name = "remarks", length = 2000)
    private String remarks;

    @Column(name = "lesson_date")
    private LocalDate lessonDate;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
