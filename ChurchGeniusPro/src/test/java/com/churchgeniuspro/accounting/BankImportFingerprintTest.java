package com.churchgeniuspro.accounting;

import com.churchgeniuspro.bankimport.BankImportFingerprint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fingerprint that lets Bank Import (and Plaid) recognize "this exact
 * statement line" across re-imports and across the two import channels.
 *
 * <p>Financial audit H8.
 */
@DisplayName("Bank import — statement-line fingerprint (H8)")
class BankImportFingerprintTest {

    @Test
    @DisplayName("date + amount + description produce a stable, readable fingerprint")
    void basicShape() {
        String fp = BankImportFingerprint.of("2026-01-15", new BigDecimal("12345.67"), null, "ACH DEPOSIT");
        assertThat(fp).isEqualTo("stmt:2026-01-15|12345.67|ACHDEPOSIT");
    }

    @Test
    @DisplayName("the same line fingerprints identically every time — this is what makes re-import detection work")
    void deterministic() {
        String a = BankImportFingerprint.of("2026-01-15", new BigDecimal("12345.67"), null, "ACH DEPOSIT");
        String b = BankImportFingerprint.of("2026-01-15", new BigDecimal("12345.67"), null, "ACH DEPOSIT");
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("a debit's negative amount fingerprints the same as the matching credit magnitude")
    void amountIsAbsolute() {
        String debit  = BankImportFingerprint.of("2026-01-15", new BigDecimal("-50.00"), null, "CHECK 1042");
        String credit = BankImportFingerprint.of("2026-01-15", new BigDecimal("50.00"),  null, "CHECK 1042");
        assertThat(debit).isEqualTo(credit);
    }

    @Test
    @DisplayName("a check number takes priority over the description, and is tagged so it can't collide with one")
    void checkNumberWins() {
        String fp = BankImportFingerprint.of("2026-01-15", new BigDecimal("250.00"), "1042", "irrelevant memo text");
        assertThat(fp).isEqualTo("stmt:2026-01-15|250.00|CK1042");
    }

    @Test
    @DisplayName("case, punctuation and spacing differences normalize to the same fingerprint")
    void normalizationCollapsesFormatting() {
        String a = BankImportFingerprint.of("2026-01-15", new BigDecimal("12.50"), null, "Ach Deposit");
        String b = BankImportFingerprint.of("2026-01-15", new BigDecimal("12.50"), null, "ach-deposit!!");
        String c = BankImportFingerprint.of("2026-01-15", new BigDecimal("12.50"), null, "  ACH   DEPOSIT  ");
        assertThat(a).isEqualTo(b).isEqualTo(c);
    }

    @Test
    @DisplayName("a very long description is truncated, not left to grow the column without bound")
    void longDescriptionIsTruncated() {
        String longDesc = "PAYROLL DIRECT DEPOSIT FROM SOME VERY LONG EMPLOYER NAME THAT KEEPS ON GOING";
        String fp = BankImportFingerprint.of("2026-01-15", new BigDecimal("1000.00"), null, longDesc);
        // "stmt:2026-01-15|1000.00|" prefix + at most 48 normalized characters.
        String tail = fp.substring(fp.lastIndexOf('|') + 1);
        assertThat(tail).hasSizeLessThanOrEqualTo(48);
        assertThat(longDesc.replaceAll("[^A-Za-z0-9]", "")).startsWith(tail);
    }

    @Test
    @DisplayName("a different date, amount, or description produces a different fingerprint")
    void distinctInputsDontCollide() {
        String base = BankImportFingerprint.of("2026-01-15", new BigDecimal("100.00"), null, "OFFERING");
        assertThat(BankImportFingerprint.of("2026-01-16", new BigDecimal("100.00"), null, "OFFERING")).isNotEqualTo(base);
        assertThat(BankImportFingerprint.of("2026-01-15", new BigDecimal("100.01"), null, "OFFERING")).isNotEqualTo(base);
        assertThat(BankImportFingerprint.of("2026-01-15", new BigDecimal("100.00"), null, "TITHE")).isNotEqualTo(base);
    }

    @Test
    @DisplayName("an unparsed or missing date yields no fingerprint, never a guess")
    void unparsedDateYieldsNull() {
        assertThat(BankImportFingerprint.of(null, new BigDecimal("10.00"), null, "FEE")).isNull();
        assertThat(BankImportFingerprint.of("01/15/2026", new BigDecimal("10.00"), null, "FEE")).isNull();
        assertThat(BankImportFingerprint.of("not a date", new BigDecimal("10.00"), null, "FEE")).isNull();
    }

    @Test
    @DisplayName("a missing amount yields no fingerprint")
    void missingAmountYieldsNull() {
        assertThat(BankImportFingerprint.of("2026-01-15", null, null, "FEE")).isNull();
    }

    @Test
    @DisplayName("nothing to disambiguate on (no check number, blank description) yields no fingerprint")
    void nothingToDisambiguateYieldsNull() {
        assertThat(BankImportFingerprint.of("2026-01-15", new BigDecimal("10.00"), null, null)).isNull();
        assertThat(BankImportFingerprint.of("2026-01-15", new BigDecimal("10.00"), "", "   ")).isNull();
    }

    @Test
    @DisplayName("the amount is always rendered to two places, whatever scale it arrived with")
    void amountAlwaysTwoPlaces() {
        String fp = BankImportFingerprint.of("2026-01-15", new BigDecimal("9"), null, "FEE");
        assertThat(fp).isEqualTo("stmt:2026-01-15|9.00|FEE");
    }
}
