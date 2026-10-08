package com.churchgeniuspro.accounting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The accountant dashboard's month-pie navigation must fetch the year it
 * pages into, not just relabel a cached year's data.
 *
 * <p>Financial audit M10. {@code _cachedDashData} only ever holds ONE
 * year's worth of monthly series — whatever year was last fetched.
 * {@code shiftStatMonth} used to bump {@code _statYear} on a Dec/Jan
 * rollover and immediately re-render that same cached year: the label read
 * the new year, the pies kept showing the old one. {@code shiftBarYear}'s
 * own year navigation already refetches correctly and is the reference
 * shape this pins {@code shiftStatMonth} to as well.
 *
 * <p>There is no JavaScript test runner in this build (see {@link
 * QuickAddRowIdentityTest}), so this reads the page and pins the shape
 * that makes the bug impossible: a year change is never followed straight
 * to render without an intervening fetch scoped to the NEW year, and a
 * stale, out-of-order response can never overwrite a newer one. (The
 * dynamic behavior — that paging Dec→Jan actually shows the new year's
 * figures, that same-year paging makes no network call, and that a
 * rapid double-click's stale response loses the race — was additionally
 * verified by executing the real, extracted function in a sandboxed JS
 * context; this class is the permanent, `mvn test`-integrated guard.)
 */
@DisplayName("Accountant dashboard — stat-pie month navigation refetches its year (M10)")
class DashboardStatPieYearTest {

    private static String page() throws IOException {
        return Files.readString(Paths.get("src/main/resources/static/home.html"));
    }

    /** shiftStatMonth's own source, from its declaration to its closing brace. */
    private static String shiftStatMonth() throws IOException {
        String src   = page();
        int    start = src.indexOf("function shiftStatMonth(delta) {");
        assertThat(start).as("shiftStatMonth found").isPositive();
        int end = src.indexOf("\n}\n", start);
        assertThat(end).as("shiftStatMonth's closing brace found").isGreaterThan(start);
        return src.substring(start, end + 2);
    }

    @Test
    @DisplayName("is declared async, so it can await a refetch before rendering")
    void isAsync() throws IOException {
        String src   = page();
        int    start = src.indexOf("function shiftStatMonth(delta) {");
        String before = src.substring(0, start);
        assertThat(before.endsWith("async ")).as("must be declared 'async function shiftStatMonth'").isTrue();
    }

    @Test
    @DisplayName("captures the year it started at, to tell whether this move actually crossed a year")
    void capturesPreviousYear() throws IOException {
        assertThat(shiftStatMonth()).contains("const prevYear = _statYear;");
    }

    @Test
    @DisplayName("when the year changes, awaits a fetch for the NEW _statYear before its final render — never falls straight through to rendering the old cached year")
    void refetchesTheNewYearBeforeRendering() throws IOException {
        String fn = shiftStatMonth();
        assertThat(fn).as("must branch on whether the year actually changed").contains("_statYear === prevYear");
        assertThat(fn).as("must await something scoped to the new _statYear")
                .containsPattern("await\\s+fetchDashDataForYear\\(\\s*_statYear\\s*\\)");

        // The historic bug was falling straight through to
        // renderStatPiesForMonth(_cachedDashData) with nothing awaited
        // in between whenever the year changed. Pin the ORDER: the
        // year-scoped fetch is awaited after the year-wrap ifs, and the
        // function's final render comes after that await, not before it.
        int wrapEnd   = fn.indexOf("_statYear--; }") + "_statYear--; }".length();
        int awaitIdx  = fn.indexOf("await fetchDashDataForYear");
        int renderIdx = fn.lastIndexOf("renderStatPiesForMonth(_cachedDashData)");
        assertThat(wrapEnd).as("year-wrap logic found").isGreaterThan(0);
        assertThat(awaitIdx).as("the year-scoped fetch must be awaited after the year-wrap logic")
                .isGreaterThan(wrapEnd);
        assertThat(renderIdx).as("the final render must come after the awaited fetch, not before it")
                .isGreaterThan(awaitIdx);
    }

    @Test
    @DisplayName("fetchDashDataForYear asks the API for the specific requested year, not the default (current) year")
    void fetchIsScopedToTheRequestedYear() throws IOException {
        String src   = page();
        int    start = src.indexOf("async function fetchDashDataForYear(year) {");
        assertThat(start).as("fetchDashDataForYear found").isPositive();
        int end = src.indexOf("\n}\n", start);
        String fn = src.substring(start, end);
        assertThat(fn).containsPattern("/api/accountant/dashboard\\?clientId=[^&]*&year=\\$\\{year\\}");
    }

    @Test
    @DisplayName("a sequence guard discards a stale cross-year response instead of letting it overwrite a newer one")
    void guardsAgainstOutOfOrderResponses() throws IOException {
        String src = page();
        assertThat(src).contains("_statFetchSeq");
        String fn = shiftStatMonth();
        assertThat(fn).containsPattern("seq\\s*!==\\s*_statFetchSeq");
    }

    @Test
    @DisplayName("paging within the same year still renders straight from cache — no needless refetch")
    void sameYearStillRendersFromCacheDirectly() throws IOException {
        String fn          = shiftStatMonth();
        int    sameYearIdx = fn.indexOf("_statYear === prevYear");
        int    renderIdx   = fn.indexOf("renderStatPiesForMonth(_cachedDashData)", sameYearIdx);
        int    returnIdx   = fn.indexOf("return;", sameYearIdx);
        assertThat(sameYearIdx).isPositive();
        assertThat(renderIdx).as("same-year branch must render immediately, before its own return")
                .isBetween(sameYearIdx, returnIdx);
    }
}
