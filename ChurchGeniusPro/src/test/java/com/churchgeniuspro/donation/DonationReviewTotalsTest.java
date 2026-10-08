package com.churchgeniuspro.donation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The donation review page's headline total must be the server's exact figure
 * when one is available, and never plain floating-point addition.
 *
 * <p>Financial audit M11. {@code renderStats} used to always recompute the total
 * itself with {@code sameCur.reduce((s,d)=>s+parseFloat(d.amount||0),0)} — raw
 * floating-point addition that can drift by a cent or more over a large list, with
 * nothing to check it against. {@code /api/donations} now also returns {@code
 * totals}: an exact, BigDecimal-summed figure per currency, computed from the same
 * rows it sends (see {@code DonationController#getDonations}). {@code renderStats}
 * uses that figure directly for the full (unfiltered) view; only a client-side date
 * filter — a subset the server was never asked about — still sums client-side, and
 * does so in rounded integer cents rather than raw floats.
 *
 * <p>There is no JavaScript test runner in this build (see {@code
 * QuickAddRowIdentityTest} in the accounting package), so this reads the page and
 * pins the shape that makes the bug impossible. (The dynamic behavior — that a
 * supplied server total is shown as-is rather than re-derived, and that the integer-
 * cent fallback produces the exact sum on a list that would visibly drift under raw
 * float addition — was additionally verified by executing the real, extracted
 * function in a sandboxed JS context; this class is the permanent, `mvn
 * test`-integrated guard.)
 */
@DisplayName("Donation review — headline total reconciles against the server (M11)")
class DonationReviewTotalsTest {

    private static String page() throws IOException {
        return Files.readString(Paths.get("src/main/resources/static/donationReview.html"));
    }

    private static String functionSource(String declaration) throws IOException {
        String src   = page();
        int    start = src.indexOf(declaration);
        assertThat(start).as(declaration + " found").isPositive();
        int end = src.indexOf("\n}\n", start);
        assertThat(end).as(declaration + "'s closing brace found").isGreaterThan(start);
        return src.substring(start, end + 2);
    }

    private static String renderStats() throws IOException {
        return functionSource("function renderStats(rows, totals) {");
    }

    @Test
    @DisplayName("loadDonations reads rows/totals from the response envelope, not a bare array")
    void loadDonationsReadsNewEnvelope() throws IOException {
        String fn = functionSource("async function loadDonations() {");
        assertThat(fn).contains("const data = await res.json();");
        assertThat(fn).contains("allDonations = data.rows");
        assertThat(fn).contains("serverTotals = data.totals");
        assertThat(fn).contains("renderStats(allDonations, serverTotals)");
    }

    @Test
    @DisplayName("uses the server-computed total for the current currency when one is available")
    void prefersServerTotalWhenAvailable() throws IOException {
        String fn = renderStats();
        assertThat(fn).as("must look up a totals entry matching the displayed currency")
                .containsPattern("totals\\s*&&\\s*totals\\.find\\(");
        assertThat(fn).as("must derive the shown figure from the server total, not re-sum rows for it")
                .containsPattern("serverTotal\\.total\\s*\\*\\s*100");
    }

    @Test
    @DisplayName("without a server total, sums in rounded integer cents — never raw floating-point addition")
    void fallsBackToIntegerCentSummation() throws IOException {
        String fn = renderStats();
        // The historic bug: `sameCur.reduce((s, d) => s + parseFloat(d.amount || 0), 0)`
        // — raw float addition, no per-row rounding. Require the "sameCur.reduce(" call
        // prefix (not just the arrow-function shape) so this only matches real leftover
        // code, not this class's own doc comment describing the old bug for context.
        assertThat(fn).doesNotContainPattern("sameCur\\.reduce\\(\\(s,\\s*d\\)\\s*=>\\s*s\\s*\\+\\s*parseFloat");
        assertThat(fn).containsPattern("Math\\.round\\(parseFloat\\(d\\.amount \\|\\| 0\\)\\s*\\*\\s*100\\)");
    }

    @Test
    @DisplayName("both the server-total and fallback paths converge on one cents value, divided by 100 exactly once")
    void bothPathsConvergeOnTotalCentsOverOneHundred() throws IOException {
        String fn = renderStats();
        assertThat(fn).contains("let totalCents;");
        assertThat(fn).contains("const total = totalCents / 100;");
    }

    @Test
    @DisplayName("a date filter's narrowed subset has no matching server total, so it is passed null")
    void applyFilterPassesNullTotals() throws IOException {
        String fn = functionSource("function applyFilter() {");
        assertThat(fn).contains("renderStats(filtered, null);");
    }

    @Test
    @DisplayName("clearing the filter goes back to the server-reconciled total for the full set")
    void clearFilterUsesServerTotals() throws IOException {
        String fn = functionSource("function clearFilter() {");
        assertThat(fn).contains("renderStats(allDonations, serverTotals);");
    }
}
