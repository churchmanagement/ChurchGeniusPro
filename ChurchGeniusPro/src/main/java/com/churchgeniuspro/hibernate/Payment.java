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

import java.time.LocalDate;
import java.util.Date;

/**
 * Hibernate entity for the {@code payment} table.
 *
 * <p>Stores payment / card details for an organization's setup record.
 * {@code paymentId} is a Many-to-One foreign key back to {@link InitialSetUp}.
 *
 * <p><strong>Note:</strong> Credit card numbers are stored here for mapping
 * purposes. In production, card data must be encrypted or tokenised to comply
 * with PCI-DSS requirements.
 */
@Data
@Entity
@Table(name = "payment")
public class Payment {

    // ── Primary Key ───────────────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "payment_seq")
    @SequenceGenerator(name = "payment_seq", sequenceName = "payment_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    // ── Foreign Key → InitialSetUp (Many-to-One, Lazy) ───────────────────

    /**
     * Many payment records can belong to one {@link InitialSetUp}.
     * Stored as the {@code payment_id} column in the {@code payment} table.
     */
	/*
	 * @ManyToOne(fetch = FetchType.LAZY)
	 * 
	 * @JoinColumn(name = "payment_id", referencedColumnName = "id") private
	 * ChurchRegistration paymentId;
	 */

    // ── Payment Details ───────────────────────────────────────────────────

    @Column(name = "name")
    private String name;

    /** Payment method type code (e.g. 1 = Credit, 2 = Debit, 3 = ACH). */
    @Column(name = "type")
    private Integer type;

    /**
     * Credit card number.
     * Stored as {@code Long} — credit card numbers are up to 16 digits,
     * which exceeds the Integer range (max ~2.1 billion).
     */
    @Column(name = "credit_card_no")
    private Long creditCardNo;

    @Column(name = "exp_date")
    private LocalDate expDate;

    @Column(name = "cvv")
    private Integer cvv;

    // ── Status Flags ──────────────────────────────────────────────────────

    /**
     * Indicates this is the primary / default payment method.
     * Column named {@code is_primary} to avoid the SQL reserved word PRIMARY.
     */
    @Column(name = "is_primary")
    private Boolean primary;

    @Column(name = "deleted")
    private Boolean deleted;

    @Column(name = "expired")
    private Boolean expired;

    @Column(name = "active")
    private Boolean active;

    // ── Dates ─────────────────────────────────────────────────────────────

    /**
     * Automatically set to the current timestamp on first insert.
     * Never updated after the initial persist.
     */
    @Column(name = "payment_date", nullable = false, updatable = false)
    private Date paymentDate;

    // ── Lifecycle Callback ────────────────────────────────────────────────

    /**
     * Populates {@code paymentDate} with the current timestamp
     * immediately before the entity is first inserted into the database.
     */
    @PrePersist
    protected void onCreate() {
        this.paymentDate = new Date();
    }
}
