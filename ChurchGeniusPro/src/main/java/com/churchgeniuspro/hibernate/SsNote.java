package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** A group-level note for a Sunday School class. */
@Data
@Entity
@Table(name = "ss_note")
public class SsNote {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ss_note_seq")
    @SequenceGenerator(name = "ss_note_seq", sequenceName = "ss_note_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "class_id", nullable = false)
    private Long classId;

    @Column(name = "note_text", nullable = false, columnDefinition = "TEXT")
    private String noteText;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag = false;
}
