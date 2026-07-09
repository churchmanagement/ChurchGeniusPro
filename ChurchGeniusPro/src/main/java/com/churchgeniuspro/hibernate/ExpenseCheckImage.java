package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** Scanned check image stored alongside an Expense record. */
@Data
@Entity
@Table(name = "expense_check_image")
public class ExpenseCheckImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** FK to expense.id (Expense uses an Integer id). */
    @Column(name = "expense_id", nullable = false)
    private Integer expenseId;

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
