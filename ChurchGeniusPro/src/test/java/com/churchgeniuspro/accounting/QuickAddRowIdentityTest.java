package com.churchgeniuspro.accounting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The accountant dashboard's Quick-Add rows must be addressed by template id,
 * never by array position.
 *
 * <p>Financial audit H7. Rows were keyed by index: element ids were
 * {@code qai-amt-0}, handlers captured {@code i}, and removing a row filtered the
 * template array by index without re-rendering the DOM. Every row after the
 * removed one then pointed at the wrong template — the row on screen said
 * "Ben Carter", the handler looked up the next template, and a real contribution
 * was posted against a different member and fund with the amount the user had
 * typed. Reproduced in a browser before the fix: remove row 1, press Add on row 2,
 * and the POST carried row 3's memberId.
 *
 * <p>There is no JavaScript test runner in this build, so this reads the page and
 * pins the shape that makes the bug impossible: nothing in either panel is keyed
 * by {@code i}, every handler is given {@code t.id}, and removal filters by id.
 */
@DisplayName("Accountant dashboard — Quick-Add row identity (H7)")
class QuickAddRowIdentityTest {

    private static String page() throws IOException {
        return Files.readString(Paths.get("src/main/resources/static/home.html"));
    }

    /** The source of one panel, from its template array to the end of its remove function. */
    private static String panel(String prefix) throws IOException {
        String src   = page();
        String start = prefix.equals("qai") ? "let quickAddIncomeTemplates" : "let quickAddExpenseTemplates";
        String fn    = prefix.equals("qai") ? "async function removeQuickAddIncome" : "async function removeQuickAddExpense";
        int a = src.indexOf(start);
        int b = src.indexOf(fn);
        assertThat(a).as("panel start").isPositive();
        assertThat(b).as("remove function").isGreaterThan(a);
        // Include the remove function body: up to the next blank-line-separated block.
        int end = src.indexOf("\n}\n", b);
        return src.substring(a, end + 3);
    }

    @ParameterizedTest(name = "{0} panel")
    @ValueSource(strings = { "qai", "qae" })
    @DisplayName("no element id is built from the array index")
    void elementIdsAreNotIndexed(String prefix) throws IOException {
        String p = panel(prefix);
        Matcher m = Pattern.compile("id=\"" + prefix + "-[a-z]+-\\$\\{([^}]+)\\}\"").matcher(p);
        int count = 0;
        while (m.find()) {
            count++;
            assertThat(m.group(1))
                    .as("element id key in %s", m.group())
                    .isEqualTo("t.id");
        }
        assertThat(count).as("element ids found").isGreaterThanOrEqualTo(6);
    }

    @ParameterizedTest(name = "{0} panel")
    @ValueSource(strings = { "qai", "qae" })
    @DisplayName("every handler is wired with the template id, and lookups are by id")
    void handlersAndLookupsUseTheId(String prefix) throws IOException {
        String p = panel(prefix);
        String submit = prefix.equals("qai") ? "submitQuickAddIncome" : "submitQuickAddExpense";
        String remove = prefix.equals("qai") ? "removeQuickAddIncome" : "removeQuickAddExpense";
        String array  = prefix.equals("qai") ? "quickAddIncomeTemplates" : "quickAddExpenseTemplates";

        assertThat(p).contains("() => " + submit + "(t.id)");
        assertThat(p).contains("() => " + remove + "(t.id)");
        assertThat(p).contains(array + ".find(t => t.id === templateId)");
        assertThat(p).doesNotContain(array + "[rowIdx]");
    }

    @ParameterizedTest(name = "{0} panel")
    @ValueSource(strings = { "qai", "qae" })
    @DisplayName("removing a row filters the templates by id, never by position")
    void removalFiltersById(String prefix) throws IOException {
        String p = panel(prefix);
        assertThat(p).doesNotContain("filter((_, i) => i !== rowIdx)");
        assertThat(p).containsPattern("filter\\(t => t\\.id !== (incomeId|expenseId)\\)");
    }

    @Test
    @DisplayName("the templates the page renders carry an id to key on")
    void templatesCarryAnId() throws IOException {
        // incomeToMap / expenseToMap put "id" first; the panels rely on it.
        String inc = Files.readString(Paths.get("src/main/java/com/churchgeniuspro/service/IncomeService.java"));
        String exp = Files.readString(Paths.get("src/main/java/com/churchgeniuspro/service/ExpenseService.java"));
        assertThat(inc).contains("map.put(\"id\",");
        assertThat(exp).contains("map.put(\"id\",");
    }
}
