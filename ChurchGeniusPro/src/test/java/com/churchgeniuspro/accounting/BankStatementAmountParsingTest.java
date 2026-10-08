package com.churchgeniuspro.accounting;

import com.churchgeniuspro.service.BankStatementExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The amount read off a bank statement line.
 *
 * <p>Financial audit H6. The money pattern matched "one to three digits, then
 * optional comma groups", and was unanchored, so on a statement printed without
 * thousands separators it matched wherever it could: {@code ACH DEPOSIT 12345.67}
 * produced the token {@code 345.67}, the leading {@code 12} was left in the
 * description, and the row was staged at a hundredth of its value. And because
 * cents had to be exactly two digits, a CSV-style {@code 12.5} matched nothing and
 * the row vanished. Neither failure was visible: a shorter amount and a missing
 * row both look like an ordinary statement.
 *
 * <p>These go through {@link BankStatementExtractor#extractFromText}, the entry
 * point the browser OCR path uses.
 */
@DisplayName("Bank statement import — amount parsing (H6)")
class BankStatementAmountParsingTest {

    private final BankStatementExtractor extractor = new BankStatementExtractor();

    private List<Map<String, Object>> rows(String text) {
        return extractor.extractFromText(text, 90).rows;
    }

    private BigDecimal amount(String line) {
        List<Map<String, Object>> r = rows(line);
        assertThat(r).as("a row for: %s", line).hasSize(1);
        return (BigDecimal) r.get(0).get("amount");
    }

    private String description(String line) {
        return String.valueOf(rows(line).get(0).get("description"));
    }

    @Nested
    @DisplayName("single-line layout")
    class SingleLine {

        @ParameterizedTest(name = "\"{0}\" → {1}")
        @CsvSource({
            // The audit's executed case: previously 345.67.
            "'01/15/2026 ACH DEPOSIT 12345.67',        12345.67",
            "'01/15/2026 ACH DEPOSIT 1,234.56',        1234.56",
            // Every width, with and without commas.
            "'01/15/2026 DEPOSIT 1234.56',             1234.56",
            "'01/15/2026 DEPOSIT 123456.78',           123456.78",
            "'01/15/2026 DEPOSIT 1,234,567.89',        1234567.89",
            "'01/15/2026 DEPOSIT $12345.67',           12345.67",
            "'01/15/2026 DEPOSIT 9.10',                9.10",
        })
        @DisplayName("a whole credit amount is read, not its last three digits")
        void readsTheWholeAmount(String line, String expected) {
            assertThat(amount(line)).isEqualByComparingTo(expected);
        }

        @ParameterizedTest(name = "\"{0}\" → {1}")
        @CsvSource({
            "'01/15/2026 ACH DEBIT 12345.67',          -12345.67",
            "'01/15/2026 CHECK 1042 12345.67',         -12345.67",
            "'01/15/2026 FEE (12345.67)',              -12345.67",
            "'01/15/2026 WITHDRAWAL 12345.67-',        -12345.67",
            "'01/15/2026 POS PURCHASE 12345.67 DR',    -12345.67",
        })
        @DisplayName("…and a whole debit amount, whichever way the bank marks it")
        void readsTheWholeDebit(String line, String expected) {
            assertThat(amount(line)).isEqualByComparingTo(expected);
        }

        @Test
        @DisplayName("a one-decimal amount is read as cents, not dropped")
        void oneDecimalIsRead() {
            // Previously matched nothing, so the row silently disappeared.
            assertThat(amount("01/15/2026 SERVICE FEE 12.5")).isEqualByComparingTo("-12.50");
            assertThat(amount("01/15/2026 INTEREST DEPOSIT 1234.5")).isEqualByComparingTo("1234.50");
        }

        @Test
        @DisplayName("the leading digits no longer leak into the description")
        void descriptionIsClean() {
            // Previously "12 ACH DEPOSIT": the unmatched prefix of the amount.
            assertThat(description("01/15/2026 ACH DEPOSIT 12345.67")).isEqualTo("ACH DEPOSIT");
        }

        @Test
        @DisplayName("a trailing running balance is still ignored in favour of the transaction amount")
        void runningBalanceStillIgnored() {
            assertThat(amount("01/15/2026 DEPOSIT 12345.67 98765.43")).isEqualByComparingTo("12345.67");
        }

        @Test
        @DisplayName("an amount is never read out of the middle of a longer number")
        void neverASubstringOfALongerNumber() {
            // "…5.67" inside an account number must not become $5.67.
            assertThat(rows("01/15/2026 XFER ACCT 000123455.67890")).isEmpty();
        }

        @Test
        @DisplayName("a reference number without a decimal point is not an amount")
        void integersAreNotAmounts() {
            assertThat(rows("01/15/2026 REF 4471928 TRACE 8812")).isEmpty();
        }
    }

    @Nested
    @DisplayName("stacked layout (amount on its own line above the date row)")
    class Stacked {

        @Test
        @DisplayName("a five-figure amount on its own line is read whole")
        void stackedAmountIsReadWhole() {
            List<Map<String, Object>> r = rows("+12345.67\n01/15/2026 DIRECT DEP PAYROLL");
            assertThat(r).hasSize(1);
            assertThat((BigDecimal) r.get(0).get("amount")).isEqualByComparingTo("12345.67");
            assertThat(r.get(0).get("txnType")).isEqualTo("credit");
        }

        @Test
        @DisplayName("…and a one-decimal one is not dropped")
        void stackedOneDecimalIsRead() {
            List<Map<String, Object>> r = rows("12.5\n01/15/2026 MONTHLY FEE");
            assertThat(r).hasSize(1);
            assertThat((BigDecimal) r.get(0).get("amount")).isEqualByComparingTo("-12.50");
        }

        @Test
        @DisplayName("comma-grouped amounts still work as before")
        void stackedCommaGrouped() {
            List<Map<String, Object>> r = rows("+1,234.56\n01/15/2026 DIRECT DEP PAYROLL");
            assertThat((BigDecimal) r.get(0).get("amount")).isEqualByComparingTo("1234.56");
        }
    }

    @Nested
    @DisplayName("what must not change")
    class Unchanged {

        @Test
        @DisplayName("statement totals foot to the rows")
        void totalsFoot() {
            BankStatementExtractor.Result r = extractor.extractFromText(
                    "01/15/2026 ACH DEPOSIT 12345.67\n"
                  + "01/16/2026 SERVICE FEE 12.5\n"
                  + "01/17/2026 DEPOSIT 1,000.00\n", 90);
            assertThat(r.rows).hasSize(3);
            assertThat((BigDecimal) r.summary.get("totalIn")).isEqualByComparingTo("13345.67");
            assertThat((BigDecimal) r.summary.get("totalOut")).isEqualByComparingTo("12.50");
        }

        @Test
        @DisplayName("every amount is stored at two places")
        void twoPlaces() {
            assertThat(amount("01/15/2026 SERVICE FEE 12.5").scale()).isEqualTo(2);
            assertThat(amount("01/15/2026 DEPOSIT 12345.67").scale()).isEqualTo(2);
        }
    }
}
