package com.churchgeniuspro.bankimport;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Fingerprints one bank-statement line so the same transaction is recognized
 * whether it arrives through a CSV/OFX/QFX upload or a photo/PDF (OCR/vision)
 * scan, and whichever of those two a church re-imports next month.
 *
 * <p>Financial audit H8: before this, the "possible duplicate" check run before
 * a Bank Import save compared only date + amount, and it was purely advisory —
 * declining it, or the check call itself failing, did not stop the save. This
 * fingerprint is threaded through as {@code importRef} and enforced in
 * {@code IncomeService}/{@code ExpenseService} at the point of creation instead,
 * so the same statement line can never be posted to the ledger twice.
 *
 * <p>The fingerprint is deliberately coarse: date + absolute amount, plus either
 * the check number (when the statement line names one) or a normalized
 * description. It identifies "this statement line", not "this exact row" — two
 * genuinely different transactions that happen to share date, amount and
 * description text are rare enough on a bank statement that treating them as
 * the same import is the right default, and the separate soft (date + amount
 * only, source-agnostic) check downstream still lets a real second transaction
 * through with a one-click confirmation.
 */
public final class BankImportFingerprint {

    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    private static final int MAX_TAIL = 48;

    private BankImportFingerprint() { }

    /**
     * @return a fingerprint string, or {@code null} when the line doesn't carry
     *         enough to fingerprint safely (unparsed date, missing amount, and no
     *         check number or description to disambiguate on).
     */
    public static String of(String isoDate, BigDecimal amount, String checkNo, String description) {
        if (isoDate == null || !ISO_DATE.matcher(isoDate).matches()) return null;
        if (amount == null) return null;
        String tail = normalize(checkNo != null && !checkNo.isBlank() ? "CK" + checkNo : description);
        if (tail.isEmpty()) return null;
        String amt = amount.abs().setScale(2, RoundingMode.HALF_UP).toPlainString();
        return "stmt:" + isoDate + "|" + amt + "|" + tail;
    }

    private static String normalize(String s) {
        if (s == null) return "";
        String n = s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return n.length() > MAX_TAIL ? n.substring(0, MAX_TAIL) : n;
    }
}
