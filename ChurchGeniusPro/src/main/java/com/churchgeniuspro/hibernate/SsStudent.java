package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

/**
 * A Sunday School student (child member) enrolled in a class under a teacher.
 */
@Data
@Entity
@Table(name = "ss_student")
public class SsStudent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ss_student_seq")
    @SequenceGenerator(name = "ss_student_seq", sequenceName = "ss_student_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "teacher_id", nullable = false)
    private Long teacherId;

    /** Linked family member id (must have role=Child). */
    @Column(name = "family_member_id")
    private Integer familyMemberId;

    /** memberRef of the child (MBR<uuid>) — used for member login identification. */
    @Column(name = "member_ref", length = 100)
    private String memberRef;

    @Column(name = "student_name", nullable = false, length = 200)
    private String studentName;

    /** Email used for notifications (own or HoH). */
    @Column(name = "contact_email", length = 200)
    private String contactEmail;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
