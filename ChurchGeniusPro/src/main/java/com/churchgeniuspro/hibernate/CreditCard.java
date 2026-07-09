package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Data;

import java.util.Date;

/**
 * Hibernate entity for the {@code credit_card} table.
 *
 * <p>{@code createdDate} is automatically set to the current timestamp
 * on first insert via the {@link #onCreate()} lifecycle callback.
 *
 * <p>{@code type} is stored as a readable string using
 * {@link CardType#CREDIT_CARD} or {@link CardType#DEBIT_CARD}.
 *
 * <p><strong>Note:</strong> Card numbers must be encrypted or tokenised
 * before persistence to comply with PCI-DSS requirements.
 */
@Data
@Entity
@Table(name = "credit_card")
public class CreditCard {

    // ── Primary Key ───────────────────────────────────────────────────────

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "credit_card_seq")
    @SequenceGenerator(name = "credit_card_seq", sequenceName = "credit_card_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    // ── Card Details ──────────────────────────────────────────────────────

    @Column(name = "name")
    private String name;

    /**
     * Full card number stored as a String to preserve leading zeros
     * and avoid numeric overflow on 16-digit card numbers.
     */
    @Column(name = "credit_card_no")
    private String creditCardNo;

    @Column(name = "exp_month")
    private Integer expMonth;

    @Column(name = "exp_year")
    private Integer expYear;

    @Column(name = "cvv")
    private Integer cvv;

    /**
     * Indicates this is the primary / default card.
     * Column named {@code is_primary} to avoid the SQL reserved word PRIMARY.
     */
    @Column(name = "is_primary")
    private Boolean primary;

    /**
     * Card type: Credit Card or Debit Card.
     * Stored as a string in the database for readability.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 20)
    private CardType type;

    // ── Status Flags ──────────────────────────────────────────────────────

    @Column(name = "deleted")
    private Boolean deleted;

    @Column(name = "active")
    private Boolean active;

    @Column(name = "declined")
    private Boolean declined;

    // ── Dates ─────────────────────────────────────────────────────────────

    /**
     * Automatically set to the current timestamp on first insert.
     * Never updated after the initial persist.
     */
    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    @Column(name = "updated_date")
    private Date updatedDate;

    // ── Card Type Enum ────────────────────────────────────────────────────

    public enum CardType {
        CREDIT_CARD,
        DEBIT_CARD
    }

    // ── Lifecycle Callback ────────────────────────────────────────────────

    /**
     * Populates {@code createdDate} with the current timestamp
     * immediately before the entity is first inserted into the database.
     */
    @PrePersist
    protected void onCreate() {
        this.createdDate = new Date();
    }
}
