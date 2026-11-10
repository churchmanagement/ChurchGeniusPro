package com.churchgeniuspro.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-10: an income / expense saved through the Genius Assistant updates the Dashboard's finance figures
 * without a reload — the assistant announces the save once (only for a saved income / expense receipt), the
 * Dashboard re-reads the same API it loads from, coalescing signals and ignoring stale responses.
 */
@DisplayName("Dashboard refreshes its finance figures after a Genius Assistant save")
class DashboardLiveRefreshTest {

    static String read(String f) throws IOException { return Files.readString(Paths.get("src/main/resources/static/" + f)); }

    @Test @DisplayName("assistant: one announcement per saved income / expense, fired when the answer arrives (not on restore)")
    void assistantAnnounces() throws IOException {
        String js = read("ai-assistant.js");
        assertThat(js).contains("function announceLedgerChange(d){",
                "if (!d || d.intent!=='action.done' || !d.receipt || typeof d.receipt!=='object') return;",
                "if (kind!=='income' && kind!=='expense') return;",
                "window.dispatchEvent(new CustomEvent('cgp:ledger-changed', { detail:detail }))",
                "new BroadcastChannel('cgp-ledger')");
        int render = js.indexOf("function renderCard(a){");
        assertThat(js.indexOf("announceLedgerChange(d0);", render)).isBetween(render, render + 300);
        assertThat(js.split("announceLedgerChange\\(").length - 1).as("defined once, called once").isEqualTo(2);
    }

    @Test @DisplayName("dashboard: re-reads /api/accountant/dashboard, renders every finance card, coalesces, ignores stale answers, only when shown")
    void dashboardRefreshes() throws IOException {
        String h = read("home.html");
        assertThat(h).contains("window.addEventListener('cgp:ledger-changed', onLedgerChanged);",
                "new BroadcastChannel('cgp-ledger').onmessage = onLedgerChanged;",
                "if (!_acctDashLoaded) return;", "_acctDashLoaded = true;",
                "_ledgerRefreshTimer = setTimeout(refreshFinanceDashboard, 250);",
                "if (seq !== _ledgerRefreshSeq) return;",
                "fetch(`/api/accountant/dashboard?clientId=${encodeURIComponent(clientId)}`, { cache: 'no-store' })",
                "renderAcctSummary(data);", "renderAcctCatCards(data);", "renderRecentTxns(data.recentActivity || []);",
                "renderStatPiesForMonth(data);", "renderAcctBarChart(data.incomeByMonth || [], data.expenseByMonth || []);",
                "fetchAndRenderBarForYear(_barYear);");
        // no page reload as the update mechanism
        int at = h.indexOf("async function refreshFinanceDashboard()");
        assertThat(h.substring(at, h.indexOf("window.addEventListener('cgp:ledger-changed'", at))).doesNotContain("location.reload");
    }
}
