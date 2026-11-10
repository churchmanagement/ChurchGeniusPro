package com.churchgeniuspro.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-09: after adding an income or expense, the new row is highlighted in its normal
 * date position (fetching further server pages when it is backdated) for a few seconds.
 * The list is never re-sorted and edits are not highlighted.
 */
@DisplayName("Income / Expense — the record just added is highlighted where its date puts it")
class NewEntryHighlightTest {

    private static String read(String f) throws IOException {
        return Files.readString(Paths.get("src/main/resources/static/" + f));
    }

    @Test
    @DisplayName("income: highlight after an add only, found through the existing pages, timed, no sorting")
    void income() throws IOException {
        check(read("income.html"), "allTransactions", "fetchIncomePage", "renderTransactions", "visibleIncomeCount",
              "if (isNew && data && data.id) await revealNewRow(data.id);", ".txn-table tr.row-new td");
    }

    @Test
    @DisplayName("expense: highlight after an add only, found through the existing pages, timed, no sorting")
    void expense() throws IOException {
        check(read("expense.html"), "allExpenses", "fetchExpensePage", "renderTable", "visibleExpenseCount",
              "if (!isEdit && data && data.id) await revealNewRow(data.id);", ".expense-table tr.row-new td");
    }

    private static void check(String s, String rows, String fetch, String render, String visible, String afterSave, String css) {
        assertThat(s).contains(afterSave)
                .contains("const NEW_ROW_MS        = 8000;", "const NEW_ROW_MAX_PAGES = 20;")
                .contains(rows + ".findIndex(r => r.id === id)", "if (!(await " + fetch + "())) break;")
                .contains(visible + " = Math.max(" + visible + ", Math.ceil((idx + 1) / 10) * 10);")
                .contains("newRowTimer = setTimeout(", "}, NEW_ROW_MS);", "clearNewRow();")
                .contains("<tr${newRowAttrs(", "${newRowTag(", css)
                .contains("prefers-reduced-motion: reduce");
        // The list order stays the server's (date DESC): the page never sorts its rows.
        assertThat(s).doesNotContain(rows + ".sort(").doesNotContain(rows + ".unshift(");
        // A fresh load (edit, delete, reload) drops any highlight before rendering.
        int load = s.indexOf("clearNewRow();\n    " + rows + " ");
        assertThat(load).isPositive();
        assertThat(s.indexOf("function " + render + "()")).isPositive();
    }
}
