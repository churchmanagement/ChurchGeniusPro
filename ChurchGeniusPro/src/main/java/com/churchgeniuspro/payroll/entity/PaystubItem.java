package com.churchgeniuspro.payroll.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * One itemized line on a {@link Paystub}: an earning, a pre-tax deduction, a tax
 * withholding, or a post-tax deduction, with current-period and (where known)
 * year-to-date amounts.
 */
@Data
@Entity
@Table(name = "payroll_paystub_item")
public class PaystubItem {

    /** Section of the stub this line belongs to. */
    public enum Category { EARNING, PRE_TAX_DEDUCTION, TAX, POST_TAX_DEDUCTION }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_client_id", nullable = false)
    private String appClientId;

    @Column(name = "paystub_id", nullable = false)
    private Long paystubId;

    @Enumerated(EnumType.STRING)
    private Category category;

    private String label;
    private BigDecimal currentAmount = BigDecimal.ZERO;
    private BigDecimal ytdAmount;       // nullable when per-line YTD isn't tracked
    private Integer sortOrder;
}
