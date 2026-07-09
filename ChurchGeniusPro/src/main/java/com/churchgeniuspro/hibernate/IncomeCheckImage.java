package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** Scanned check image stored alongside an Income record. */
@Data
@Entity
@Table(name = "income_check_image")
public class IncomeCheckImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** FK to income.id (Income uses an Integer id). */
    @Column(name = "income_id", nullable = false)
    private Integer incomeId;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "filename", length = 255)
    private String filename;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Lob
    @Column(name = "data")
    private byte[] data;

    @Column(name = "created_date")
    private LocalDateTime createdDate;
}
