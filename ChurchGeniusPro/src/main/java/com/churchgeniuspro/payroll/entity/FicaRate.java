package com.churchgeniuspro.payroll.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Effective-dated FICA rates and limits for a tax year (global). Update or add a
 * row to change rates for a future year — no code change required.
 */
@Data
@Entity
@Table(name = "payroll_fica_rate")
public class FicaRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "effective_year", nullable = false, unique = true)
    private Integer effectiveYear;

    private BigDecimal socialSecurityRate;          // employee, e.g. 0.062
    private BigDecimal socialSecurityWageBase;      // e.g. 184500
    private BigDecimal medicareRate;                // e.g. 0.0145
    private BigDecimal additionalMedicareRate;      // e.g. 0.009
    private BigDecimal additionalMedicareThreshold; // e.g. 200000
}
