package com.churchgeniuspro.accounting;

import com.churchgeniuspro.service.CheckExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The amount a scanned check is pre-filled with.
 *
 * <p>Financial audit H5. The courtesy-box pattern matched "one to three digits,
 * then optional comma groups", so on a check written without a thousands comma
 * it took the first three digits and stopped: {@code $12345.67} was read as
 * {@code 123} and the income form was pre-filled with $123.00. A plausible wrong
 * number is the worst kind — it survives a glance. Three of the four amount
 * patterns shared the token, and the fourth simply never matched a plain digit
 * run, so a five-figure check had no correct path through the parser at all.
 *
 * <p>These go through {@link CheckExtractor#extractFromText}, the entry point
 * the browser OCR path uses, so they exercise the parser exactly as production
 * reaches it.
 */
@DisplayName("Check scan — amount parsing (H5)")
class CheckAmountParsingTest {

    private final CheckExtractor extractor = new CheckExtractor();

    private String amount(String ocrText) {
        return extractor.extractFromText(ocrText, 80).fields.get("amount");
    }

    @Nested
    @DisplayName("the courtesy box")
    class CourtesyBox {

        @ParameterizedTest(name = "\"{0}\" → {1}")
        @CsvSource({
            // The audit's executed cases: previously 123, 456 and 1,234.56.
            "'PAY TO THE ORDER OF Grace Chapel  $12345.67',   12345.67",
            "'$45678.00',                                      45678.00",
            "'$1,234.56',                                      1234.56",
            // Every width of plain digit run, with and without cents.
            "'$1234.56',                                       1234.56",
            "'$1234',                                          1234.00",
            "'$99.10',                                         99.10",
            "'$5',                                             5.00",
            "'$ 250.00',                                       250.00",
            // Comma-grouped larger amounts.
            "'$12,345.67',                                     12345.67",
            "'$1,234,567.89',                                  1234567.89",
        })
        @DisplayName("a whole amount is read, not its first three digits")
        void readsTheWholeAmount(String text, String expected) {
            assertThat(amount(text)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "\"{0}\" → {1}")
        @CsvSource({
            // Dash/dot-separated cents, with and without commas — same token, same bug.
            "'$ 100-00',      100.00",
            "'S 100.00',      100.00",
            "'$12345-67',     12345.67",
            "'$1,500-00',     1500.00",
        })
        @DisplayName("the dash-separated courtesy box reads whole amounts too")
        void dashFormReadsTheWholeAmount(String text, String expected) {
            assertThat(amount(text)).isEqualTo(expected);
        }

        @Test
        @DisplayName("the first $-amount on the check still wins over later ones")
        void firstDollarAmountWins() {
            assertThat(amount("$1250.00 ... memo: reimburse $40.00")).isEqualTo("1250.00");
        }
    }

    @Nested
    @DisplayName("without a $ sign")
    class NoDollarSign {

        @Test
        @DisplayName("a plain five-figure decimal is found — it used to be invisible to the parser")
        void plainDecimalWithoutCommasIsFound() {
            assertThat(amount("Amount 12345.67 Memo tithe")).isEqualTo("12345.67");
        }

        @Test
        @DisplayName("the largest decimal token is taken, as before")
        void largestDecimalWins() {
            assertThat(amount("No. 1042  09/12/2026  250.00  memo 15.50")).isEqualTo("250.00");
        }
    }

    @Nested
    @DisplayName("what must still be refused or unchanged")
    class Guards {

        @Test
        @DisplayName("an amount that is a prefix of a longer digit run is not an amount")
        void prefixOfLongerRunIsNotAnAmount() {
            // "$123" here is the first three characters of an account number.
            assertThat(amount("$1234567890123 ref")).isNull();
        }

        @Test
        @DisplayName("nothing readable gives no amount, not a guess")
        void nothingReadable() {
            assertThat(amount("PAY TO THE ORDER OF")).isNull();
            assertThat(amount("")).isNull();
        }

        @Test
        @DisplayName("an implausible amount is refused")
        void implausibleAmountRefused() {
            assertThat(amount("$99999999.00")).isNull();
        }

        @Test
        @DisplayName("the value is normalised to two places, with no locale in the way")
        void normalisedToTwoPlaces() {
            // BigDecimal.toPlainString: never "1234,56", whatever the JVM's default locale.
            // (Cents are exactly two digits on a check; a one-digit reading is an OCR
            // fault and is not accepted by any pattern — unchanged from before.)
            assertThat(amount("$1234")).isEqualTo("1234.00");
            assertThat(amount("$1234.56")).doesNotContain(",");
        }
    }
}
