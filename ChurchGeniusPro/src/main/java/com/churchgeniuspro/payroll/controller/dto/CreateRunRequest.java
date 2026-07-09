package com.churchgeniuspro.payroll.controller.dto;

import lombok.Data;

import java.time.LocalDate;

/** Request body to create a payroll run. Dates bind from ISO {@code yyyy-MM-dd} strings. */
@Data
public class CreateRunRequest {
    private String payFrequency;        // WEEKLY, BIWEEKLY, SEMIMONTHLY, MONTHLY, DAILY, HOURLY
    private LocalDate payPeriodStart;
    private LocalDate payPeriodEnd;
    private LocalDate payDate;
}
