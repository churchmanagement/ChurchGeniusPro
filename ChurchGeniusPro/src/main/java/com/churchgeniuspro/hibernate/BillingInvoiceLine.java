package com.churchgeniuspro.hibernate;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;

/** One line of a {@link BillingInvoice}. Lines are editable while the invoice is a DRAFT and frozen once sent. */
@Data
@Entity
@Table(name = "billing_invoice_line",
       indexes = @Index(name = "ix_billing_invoice_line_invoice", columnList = "invoice_id, sort_order"))
public class BillingInvoiceLine {

    public static final String SUBSCRIPTION = "SUBSCRIPTION";
    public static final String CHARGE       = "CHARGE";
    public static final String CUSTOM       = "CUSTOM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "invoice_id", nullable = false) private Long invoiceId;
    @Column(name = "client_id", nullable = false, length = 100) private String clientId;
    @Column(name = "kind", nullable = false, length = 14) private String kind;
    @Column(name = "description", nullable = false, length = 200) private String description;
    @Column(name = "amount", nullable = false, precision = 12, scale = 2) private BigDecimal amount;
    /** The additional charge this line bills (kind CHARGE). */
    @Column(name = "charge_id") private Long chargeId;
    @Column(name = "sort_order") private Integer sortOrder;
}
