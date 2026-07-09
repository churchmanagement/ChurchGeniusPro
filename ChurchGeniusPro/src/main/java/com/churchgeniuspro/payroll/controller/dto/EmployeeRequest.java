package com.churchgeniuspro.payroll.controller.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Create/update payload for a payroll employee. PII is intentionally limited to
 * masked values — only the last 4 digits of SSN and bank account/routing are
 * accepted and stored (full PII capture/encryption is out of scope here).
 */
@Data
public class EmployeeRequest {
    private String employeeNumber;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String ssnLast4;

    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String postalCode;
    private LocalDate hireDate;
    private LocalDate terminationDate;

    private String payType;            // HOURLY or SALARY
    private BigDecimal hourlyRate;
    private BigDecimal annualSalary;
    private String payFrequency;       // WEEKLY, BIWEEKLY, SEMIMONTHLY, MONTHLY, DAILY, HOURLY
    private Integer payPeriodsPerYearOverride;
    private String stateTaxCode;       // e.g. "TX"; null = no state tax

    private String bankName;
    private String directDepositAccountLast4;
    private String directDepositRoutingLast4;
    private String directDepositAccountType;   // CHECKING or SAVINGS

    private Boolean active;
}
