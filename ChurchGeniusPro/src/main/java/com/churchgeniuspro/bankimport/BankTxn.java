package com.churchgeniuspro.bankimport;

import java.math.BigDecimal;

/**
 * One parsed-and-categorized bank/credit-card transaction.
 *
 * <p>{@code amount} is signed: positive = money in (income/credit),
 * negative = money out (expense/debit). {@code confidence} is the categorizer's
 * certainty (0–100) that the assigned {@link #category} is correct.
 */
public class BankTxn {
    public String date;          // normalized ISO yyyy-MM-dd (or raw if unparseable)
    public String description;   // payee / memo
    public BigDecimal amount;    // signed: +in / -out
    public String category;      // e.g. Supplies, Tithes, Utilities, Payroll …
    public String fund;          // General / Missions / Youth / Building
    public int confidence;       // AI/heuristic certainty, 0–100
}
