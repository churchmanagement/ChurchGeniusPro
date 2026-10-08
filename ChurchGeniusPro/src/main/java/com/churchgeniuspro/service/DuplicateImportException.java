package com.churchgeniuspro.service;

import java.math.BigDecimal;

/**
 * Thrown by {@link IncomeService}/{@link ExpenseService} when a caller supplies an
 * {@code importRef} (Bank Import or Plaid) that collides with an existing active
 * ledger row for the same church.
 *
 * <p>Financial audit H8: bank statement import and Plaid each had their own
 * duplicate check, neither could see the other's postings, and the check itself
 * was purely advisory — the browser asked once before saving, but nothing stopped
 * the actual POST if the warning was ignored or the pre-check call failed. This
 * exception makes the block real by moving it into the create path itself, and
 * {@link #hard} tells the caller whether the match can be overridden.
 */
public class DuplicateImportException extends RuntimeException {

    /** "income" or "expense" — which ledger the existing row is in. */
    public final String type;

    /** Primary key of the existing row, for a link/lookup in the UI. */
    public final Object existingId;

    /** ISO {@code yyyy-MM-dd} date of the existing row. */
    public final String existingDate;

    public final BigDecimal existingAmount;

    public final String existingRefNo;

    /**
     * True when this exact source transaction (same church + same {@code importRef})
     * was already posted — re-saving it can never be intended, so the caller must
     * not offer "save anyway" for this case. False when it is only a same-date,
     * same-amount match: a second, genuinely different transaction is possible, so
     * the caller may proceed by retrying with {@code force = true}.
     */
    public final boolean hard;

    public DuplicateImportException(String message, String type, Object existingId, String existingDate,
                                    BigDecimal existingAmount, String existingRefNo, boolean hard) {
        super(message);
        this.type           = type;
        this.existingId     = existingId;
        this.existingDate   = existingDate;
        this.existingAmount = existingAmount;
        this.existingRefNo  = existingRefNo;
        this.hard           = hard;
    }
}
