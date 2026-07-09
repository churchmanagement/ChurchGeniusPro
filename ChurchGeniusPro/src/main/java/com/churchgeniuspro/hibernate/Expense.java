package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;

/**
 * Represents a single expense transaction.
 * Mapped to the {@code expense} table.
 */
@Data
@ToString(exclude = {"purpose", "mainSource", "transactionType"})
@Entity
@Table(name = "expense")
public class Expense {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "expense_seq")
    @SequenceGenerator(name = "expense_seq", sequenceName = "expense_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    /** The purpose (expense category) this expense is attributed to. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purpose_id", nullable = false)
    private Purpose purpose;

    /** The main fund (main source category) this expense is drawn from. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "main_source_id", nullable = false)
    private MainSource mainSource;

    /** Date of the expense transaction. */
    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    /**
     * Payment method — references the transaction_type table.
     * Nullable at the column level to preserve compatibility with legacy rows that
     * pre-date this FK; the application layer enforces a non-null value on save.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_type_id", nullable = true)
    private TransactionType transactionType;

    /** Reference / cheque number (optional). */
    @Column(name = "ref_no", length = 100)
    private String refNo;

    /** Dollar amount of this expense entry. */
    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    /** Optional free-text note. */
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    /**
     * When true, this record appears in the Quick Add panel on the Accountant
     * dashboard so users can rapidly re-enter recurring transactions.
     */
    @Column(name = "quick_add", nullable = false)
    private boolean quickAdd;

    // ── Soft-Delete ───────────────────────────────────────────────────────

    /** Optional org identifier from the app_user who created this record. Null for church-level accounts. */
    @Column(name = "app_client_id")
    private String appClientId;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    // ── Audit ─────────────────────────────────────────────────────────────

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    /**
     * Username of the logged-in user who entered this transaction.
     * Set once on creation and never updated.
     */
    @Column(name = "created_by", updatable = false, length = 150)
    private String createdBy;

    /**
     * Username of the logged-in user who last saved (created or edited) this
     * transaction.  Updated on every write.
     */
    @Column(name = "updated_by", length = 150)
    private String updatedBy;

    /** Timestamp of the most recent create or edit. Updated on every write. */
    @Column(name = "updated_date")
    private Date updatedDate;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @PrePersist
    protected void onCreate() {
        this.createdDate = new Date();
        this.deleteFlag  = false;
    }
}
