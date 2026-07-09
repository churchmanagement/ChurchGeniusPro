package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A Sunday School teacher assigned to a class.
 * May be an existing family member (memberRef set) or an externally added teacher.
 */
@Data
@Entity
@Table(name = "ss_teacher")
public class SsTeacher {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ss_teacher_seq")
    @SequenceGenerator(name = "ss_teacher_seq", sequenceName = "ss_teacher_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    /** Display name (first + last). */
    @Column(name = "teacher_name", nullable = false, length = 200)
    private String teacherName;

    @Column(name = "email", length = 200)
    private String email;

    /** If linked to an existing family member, their memberRef (MBR<uuid>). */
    @Column(name = "member_ref", length = 100)
    private String memberRef;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
