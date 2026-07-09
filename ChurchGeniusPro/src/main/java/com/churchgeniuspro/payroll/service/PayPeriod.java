package com.churchgeniuspro.payroll.service;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDate;

/** A generated pay period: the work window and the date employees are paid. */
@Data
@AllArgsConstructor
public class PayPeriod {
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private LocalDate payDate;
}
