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
import lombok.ToString;

import java.util.Date;

/**
 * A connected institution login ("Item" in Plaid terminology). Holds the
 * encrypted Plaid access token and the incremental sync cursor.
 *
 * <p>The raw access token is never stored, logged, or returned to a client —
 * only its AES-256-GCM ciphertext is persisted in {@code access_token_enc}.
 *
 * <p>Uniqueness on ({@code client_id}, {@code item_id}) is scoped per tenant,
 * not globally: a Plaid {@code item_id} is not guaranteed unique across
 * tenants, and a global constraint paired with a global re-link lookup used
 * to let one tenant's Link flow silently overwrite another tenant's item
 * row — access token included (financial audit M7).
 */
@Data
@ToString(exclude = {"accessTokenEnc", "syncCursor"})
@Entity
@Table(name = "plaid_item",
        uniqueConstraints = @UniqueConstraint(name = "uq_plaid_item_client_item_id",
                columnNames = {"client_id", "item_id"}))
public class PlaidItem {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "plaid_item_seq")
    @SequenceGenerator(name = "plaid_item_seq", sequenceName = "plaid_item_id_seq", allocationSize = 1)
    @Column(name = "id", nullable = false, updatable = false)
    private Integer id;

    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** app_user.id of the person who linked this item. */
    @Column(name = "created_by_user_id")
    private Integer createdByUserId;

    /** Plaid item_id. */
    @Column(name = "item_id", nullable = false, length = 200)
    private String itemId;

    /** AES-256-GCM encrypted Plaid access_token (base64 of IV + ciphertext). */
    @Column(name = "access_token_enc", nullable = false, columnDefinition = "TEXT")
    private String accessTokenEnc;

    /**
     * The Plaid environment that issued this item's access token — {@code sandbox}
     * or {@code production}.
     *
     * <p>Load-bearing, not descriptive: a Plaid access token is only valid in the
     * environment that created it, so every later call for this item must use the
     * stamp rather than the tenant's current plan. It is what keeps a Trial
     * tenant's sandbox connections harmless if they later upgrade, and what lets
     * the background sync keep running when the subscription cannot be read.
     *
     * <p>Nullable so ddl-auto can add it to a populated table; a null row is read
     * as this deployment's configured environment, which is what it was created
     * against. {@code migrate_production.sql} backfills it explicitly.
     */
    @Column(name = "plaid_env", length = 20)
    private String plaidEnv;

    @Column(name = "institution_id", length = 100)
    private String institutionId;

    @Column(name = "institution_name", length = 200)
    private String institutionName;

    /** ACTIVE, LOGIN_REQUIRED, ERROR, DEGRADED, DISCONNECTED. */
    @Column(name = "status", length = 30)
    private String status;

    /** Last Plaid error code, e.g. ITEM_LOGIN_REQUIRED. */
    @Column(name = "error_code", length = 100)
    private String errorCode;

    /** Cursor for the /transactions/sync incremental endpoint. */
    @Column(name = "sync_cursor", columnDefinition = "TEXT")
    private String syncCursor;

    @Column(name = "last_synced_date")
    private Date lastSyncedDate;

    @Column(name = "delete_flag", nullable = false)
    private boolean deleteFlag;

    @Column(name = "created_date", nullable = false, updatable = false)
    private Date createdDate;

    @PrePersist
    void onCreate() {
        this.createdDate = new Date();
        if (this.status == null) this.status = "ACTIVE";
    }
}
