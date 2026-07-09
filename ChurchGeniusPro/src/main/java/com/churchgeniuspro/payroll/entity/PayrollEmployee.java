package com.churchgeniuspro.payroll.entity;

import com.churchgeniuspro.payroll.model.PayFrequency;
import com.churchgeniuspro.payroll.model.PayType;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;

/**
 * A W-2 employee on a tenant's payroll. Holds personal information, pay setup,
 * and (masked) direct-deposit details. W-4 tax information lives in a linked
 * {@link PayrollW4} record so it can change over time without losing history.
 *
 * <p>Multi-tenant: scoped by {@code app_client_id} like the rest of the app.
 */
@Data
@Entity
@Table(name = "payroll_employee")
public class PayrollEmployee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_client_id", nullable = false)
    private String appClientId;

    // ── Personal information ───────────────────────────────────────────────
    private String employeeNumber;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;

    /** Stored encrypted/last-4 in practice; never expose full SSN. */
    @Column(name = "ssn_last4")
    private String ssnLast4;

    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;       // employee residence/work state code, e.g. "TX"
    private String postalCode;
    private LocalDate hireDate;
    private LocalDate terminationDate;

    // ── Pay setup ──────────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    private PayType payType;            // HOURLY or SALARY

    /** Hourly rate (when payType=HOURLY). */
    private BigDecimal hourlyRate;

    /** Annual salary (when payType=SALARY). */
    private BigDecimal annualSalary;

    @Enumerated(EnumType.STRING)
    private PayFrequency payFrequency;

    /** Optional override of periods/year for annualization (hourly/daily cadences). */
    private Integer payPeriodsPerYearOverride;

    /** State tax: code used to look up the configurable StateTaxConfig (may be null = none). */
    private String stateTaxCode;

    // ── Direct deposit (store masked / tokenized; never the full number) ───
    private String bankName;
    @Column(name = "dd_account_last4")
    private String directDepositAccountLast4;
    @Column(name = "dd_routing_last4")
    private String directDepositRoutingLast4;
    private String directDepositAccountType; // CHECKING / SAVINGS

    private boolean active = true;

    @Column(updatable = false)
    private Date created;
    private Date updated;

    @PrePersist void onCreate() { created = new Date(); updated = created; }
    @PreUpdate  void onUpdate() { updated = new Date(); }

    public String fullName() {
        return ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
    }
}
