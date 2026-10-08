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
import java.util.Date;

/**
 * A bank account exposed by a connected {@link PlaidItem}. Balances are latest
 * display snapshots only — they are not used as ledger figures. Uniqueness on
 * ({@code client_id}, {@code account_id}) is scoped per tenant, not globally,
 * since a Plaid {@code account_id} is not guaranteed unique across tenants
 * (financial audit M7 — see {@link PlaidTransactionStaging}).
 */
@Data
@Entity
@Table(name = "plaid_account",
        uniqueConstraints = @UniqueConstraint(name = "uq_plaid_account_client_account",
                columnNames = {"client_id", "account_id"}))
public class PlaidAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "plaid_account_seq")
    @SequenceGenerator(name = "plaid_account_seq", sequenceName = "plaid_account_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** FK (by value) to {@link PlaidItem#getId()}. */
    @Column(name = "plaid_item_id", nullable = false)
    private Integer plaidItemId;

    /** Plaid account_id. */
    @Column(name = "account_id", nullable = false, length = 200)
    private String accountId;

    @Column(name = "name", length = 200)
    private String name;

    @Column(name = "official_name", length = 200)
    private String officialName;

    /** Last four digits. */
    @Column(name = "mask", length = 10)
    private String mask;

    @Column(name = "type", length = 50)
    private String type;

    @Column(name = "subtype", length = 50)
    private String subtype;

    @Column(name = "current_balance", precision = 15, scale = 2)
    private BigDecimal currentBalance;

    @Column(name = "available_balance", precision = 15, scale = 2)
    private BigDecimal availableBalance;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    @Column(name = "updated_date")
    private Date updatedDate;

    @PrePersist
    void onCreate() {
        this.createdDate = new Date();
        this.active = true;
    }
}
