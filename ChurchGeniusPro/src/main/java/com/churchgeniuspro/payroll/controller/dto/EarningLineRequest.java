package com.churchgeniuspro.payroll.controller.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * One earnings line in a process-employee request. Supply {@code hours} + {@code rate}
 * (+ optional {@code multiplier}) for an hourly line, or {@code amount} for a fixed line.
 */
@Data
public class EarningLineRequest {
    private String type;            // REGULAR, OVERTIME, HOLIDAY, BONUS, OTHER
    private String description;
    private BigDecimal hours;
    private BigDecimal rate;
    private BigDecimal multiplier;  // e.g. 1.5 for overtime
    private BigDecimal amount;      // for fixed-amount lines
}
