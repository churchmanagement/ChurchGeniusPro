package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Per-church rules for posting online donations to accounting from the Donation
 * Review page (one row per {@code clientId}; schema from Flyway V14).
 *
 * <p>Only one accounting mode exists in this phase — {@link #MODE_GROSS_WITH_FEE_EXPENSE}:
 * the full charge is posted as Income and Stripe's processing fee as a separate
 * Expense, so the ledger's net equals the bank deposit while the donor's statement
 * keeps the gross gift. The column is kept so a net-only mode can be added later
 * without a schema change, but the application refuses to save any other value.
 *
 * <p>Every id here is validated against the church on save AND again on every
 * post, because a fund or method can be soft-deleted after the settings were saved.
 */
@Data
@Entity
@Table(name = "donation_settings")
public class DonationSettings {

    public static final String MODE_GROSS_WITH_FEE_EXPENSE = "GROSS_WITH_FEE_EXPENSE";

    public static final String DATE_DONATION = "DONATION_DATE";
    public static final String DATE_TODAY    = "TODAY";

    /** Default note templates — placeholders are filled by {@code DonationPostingService}. */
    public static final String DEFAULT_INCOME_NOTE  = "{card} — {donorNote}";
    public static final String DEFAULT_EXPENSE_NOTE = "Stripe fees for donation of {gross} on {date} — {donorNote}";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "donation_settings_seq")
    @SequenceGenerator(name = "donation_settings_seq", sequenceName = "donation_settings_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private String clientId;

    @Column(name = "accounting_mode", length = 32, nullable = false)
    private String accountingMode = MODE_GROSS_WITH_FEE_EXPENSE;

    // ── Income side (mandatory) ───────────────────────────────────────────
    @Column(name = "income_sub_source_id")
    private Integer incomeSubSourceId;

    @Column(name = "income_transaction_type_id")
    private Integer incomeTransactionTypeId;

    @Column(name = "income_note_template", columnDefinition = "TEXT")
    private String incomeNoteTemplate;

    // ── Expense side (mandatory in the gross-with-fee mode) ───────────────
    @Column(name = "expense_purpose_id")
    private Integer expensePurposeId;

    @Column(name = "expense_main_source_id")
    private Integer expenseMainSourceId;

    @Column(name = "expense_transaction_type_id")
    private Integer expenseTransactionTypeId;

    @Column(name = "expense_note_template", columnDefinition = "TEXT")
    private String expenseNoteTemplate;

    // ── Dates and fee estimate ────────────────────────────────────────────
    @Column(name = "date_mode", length = 16, nullable = false)
    private String dateMode = DATE_DONATION;

    /** Percentage used ONLY when Stripe's actual fee is unavailable, e.g. 2.900. */
    @Column(name = "fee_percent", precision = 6, scale = 3)
    private BigDecimal feePercent;

    /** Fixed amount used ONLY when Stripe's actual fee is unavailable, e.g. 0.30. */
    @Column(name = "fee_fixed", precision = 8, scale = 2)
    private BigDecimal feeFixed;

    @Column(name = "updated_by", length = 320)
    private String updatedBy;

    @Column(name = "updated_date")
    private LocalDateTime updatedDate;

    /** True when a fee can be estimated from these settings (both parts present and non-negative). */
    public boolean hasFeeRate() {
        return feePercent != null && feeFixed != null
                && feePercent.signum() >= 0 && feeFixed.signum() >= 0;
    }
}
