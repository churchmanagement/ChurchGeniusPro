package com.churchgeniuspro.hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/**
 * A certificate that was generated/issued from the Certificates section
 * (baptism, appreciation, dedication, completion, Sunday-school completion,
 * membership, marriage, or a custom "other" certificate).
 *
 * <p>Tenant-scoped by {@code client_id} (the church's session {@code appClientId}).
 * Key fields are stored as columns for listing/search; the full editable field
 * set is preserved verbatim as JSON in {@code details} so a record can be
 * re-opened and re-printed exactly as it was generated.
 */
@Data
@Entity
@Table(name = "issued_certificate")
public class IssuedCertificate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    /** Owning church (service_client.client_id / session appClientId). */
    @Column(name = "client_id", nullable = false, length = 100)
    private String clientId;

    /** Certificate type key, e.g. "appreciation", "dedication", "marriage". */
    @Column(name = "cert_type", nullable = false, length = 60)
    private String certType;

    /** Rendered title, e.g. "Certificate of Appreciation". */
    @Column(name = "title", length = 200)
    private String title;

    /** Primary recipient (recipient/child/participant/member/groom). */
    @Column(name = "recipient_name", length = 200)
    private String recipientName;

    /** Optional secondary name (e.g. the bride on a marriage certificate). */
    @Column(name = "secondary_name", length = 200)
    private String secondaryName;

    /** Church name as printed on the certificate. */
    @Column(name = "church_name", length = 200)
    private String churchName;

    /** Primary date as entered (ISO yyyy-MM-dd when available). */
    @Column(name = "issued_date", length = 40)
    private String issuedDate;

    /** Human-readable record number (auto-generated, also printable). */
    @Column(name = "cert_number", length = 60)
    private String certNumber;

    /** All editable fields, serialized as JSON for faithful re-rendering.
     *  No @Lob (PostgreSQL large-object/auto-commit trap) — plain TEXT. */
    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    /** Username that generated the record. */
    @Column(name = "created_by", length = 100)
    private String createdBy;

    /** When the record was generated. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
