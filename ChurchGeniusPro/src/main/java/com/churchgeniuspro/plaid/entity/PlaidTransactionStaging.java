package com.churchgeniuspro.plaid.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;

/**
 * The mandatory review queue. Every imported Plaid transaction lands here first
 * and is only promoted to the {@code income}/{@code expense} ledger on human
 * approval. Uniqueness on {@code plaid_transaction_id} makes webhook re-delivery
 * and re-sync idempotent.
 */
@Data
@Entity
@Table(name = "plaid_transaction_staging",
        uniqueConstraints = @UniqueConstraint(name = "uq_plaid_txn_txn_id",
                columnNames = {"plaid_transaction_id"}))
public class PlaidTransactionStaging {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "plaid_txn_staging_seq")
    @SequenceGenerator(name = "plaid_txn_staging_seq",
            sequenceName = "plaid_txn_staging_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    @Column(name = "plaid_item_id", nullable = false)
    private Integer plaidItemId;

    @Column(name = "plaid_account_id")
    private Integer plaidAccountId;

    /** Plaid transaction_id — idempotency key. */
    @Column(name = "plaid_transaction_id", nullable = false, length = 200)
    private String plaidTransactionId;

    /** INCOME or EXPENSE — derived from amount sign, user-editable before approval. */
    @Column(name = "direction", length = 10)
    private String direction;

    /** Absolute value of the transaction amount. */
    @Column(name = "amount", precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "txn_date")
    private LocalDate txnDate;

    @Column(name = "name", length = 500)
    private String name;

    @Column(name = "merchant_name", length = 300)
    private String merchantName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** Raw Plaid personal-finance category. */
    @Column(name = "plaid_category", length = 200)
    private String plaidCategory;

    // ── Mapping to ledger dimensions (set during review) ───────────────────
    @Column(name = "mapped_sub_source_id")
    private Integer mappedSubSourceId;        // income fund

    @Column(name = "mapped_purpose_id")
    private Integer mappedPurposeId;          // expense category

    @Column(name = "mapped_main_source_id")
    private Integer mappedMainSourceId;       // expense main fund

    @Column(name = "mapped_transaction_type_id")
    private Integer mappedTransactionTypeId;  // payment method

    /** PENDING, APPROVED, REJECTED. */
    @Column(name = "status", nullable = false, length = 15)
    private String status;

    /** Ledger row created on approval (one of these is set). */
    @Column(name = "promoted_income_id")
    private Integer promotedIncomeId;

    @Column(name = "promoted_expense_id")
    private Integer promotedExpenseId;

    /** Plaid pending flag — pending holds cannot be approved until they post. */
    @Column(name = "pending", nullable = false)
    private boolean pending;

    /** Set when Plaid reports the transaction removed. */
    @Column(name = "removed", nullable = false)
    private boolean removed;

    @Column(name = "reviewed_by", length = 150)
    private String reviewedBy;

    @Column(name = "reviewed_date")
    private Date reviewedDate;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    @Column(name = "updated_date")
    private Date updatedDate;

    @PrePersist
    void onCreate() {
        this.createdDate = new Date();
        if (this.status == null) this.status = "PENDING";
    }
}
