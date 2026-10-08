package com.churchgeniuspro.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nightly safety net for {@code ledger_month_summary} (ledger scalability, part B).
 *
 * <p>The summary is kept exact by database triggers, so a mismatch here means
 * something bypassed or broke them — a trigger dropped by hand, a restore from a
 * backup taken mid-transaction, a future bug. This job compares every summary
 * row with a fresh aggregate of the ledger rows ({@code ledger_summary_reconcile()},
 * ≈1.4 s for 1.9M ledger rows), logs each difference with enough detail to
 * investigate (church, month, kind, fund, both figures), and rebuilds the summary
 * of every church that drifted from its ledger rows — the rows always win.
 *
 * <p>Each church is rebuilt in its own try/catch and counted in its own outcome,
 * so one failure never hides the others (same rule as the reminder schedulers).
 * Runs on America/Chicago like every other scheduler.
 */
@Service
public class LedgerSummaryReconcileScheduler {

    private static final Logger log = LoggerFactory.getLogger(LedgerSummaryReconcileScheduler.class);

    /** One run's result: churches found inconsistent, and what happened to each. */
    public record Outcome(int mismatchedRows, Map<String, String> perTenant) {
        public boolean clean() { return mismatchedRows == 0; }
    }

    private final LedgerSummaryService summary;

    public LedgerSummaryReconcileScheduler(LedgerSummaryService summary) {
        this.summary = summary;
    }

    @Scheduled(cron = "${scheduler.job.ledger-summary-reconcile:0 20 2 * * *}", zone = "America/Chicago")
    public void nightly() {
        try {
            Outcome o = reconcileAndRepair();
            if (o.clean()) {
                log.info("Ledger summary reconcile: clean — every summary row matches the ledger.");
            } else {
                log.warn("Ledger summary reconcile: {} mismatched row(s) across {} church(es); repair outcomes: {}",
                         o.mismatchedRows(), o.perTenant().size(), o.perTenant());
            }
        } catch (Exception e) {
            // e.g. the summary schema is missing on this database — never let the scheduler die
            log.error("Ledger summary reconcile failed before any repair: {}", e.getMessage());
        }
    }

    /**
     * Compares summary and ledger, repairs each drifted church separately, and
     * returns what happened. Public so an operator endpoint or test can run it.
     */
    public Outcome reconcileAndRepair() {
        List<LedgerSummaryService.Mismatch> diffs = summary.reconcile();
        Map<String, String> perTenant = new LinkedHashMap<>();
        for (LedgerSummaryService.Mismatch m : diffs) {
            log.warn("Ledger summary drift: church={} month={} kind={} main={} sub={} purpose={} "
                     + "summary={} ({} rows) ledger={} ({} rows)",
                     m.appClientId(), m.periodMonth(), m.kind(), m.mainSourceId(), m.subSourceId(),
                     m.purposeId(), m.summaryAmount(), m.summaryCount(), m.actualAmount(), m.actualCount());
            perTenant.putIfAbsent(m.appClientId(), "pending");
        }
        for (String tenant : perTenant.keySet()) {
            try {
                int rows = summary.rebuild(tenant);
                perTenant.put(tenant, "rebuilt " + rows + " rows");
                log.info("Ledger summary rebuilt for church={} ({} rows)", tenant, rows);
            } catch (Exception e) {
                perTenant.put(tenant, "FAILED: " + e.getMessage());
                log.error("Ledger summary rebuild FAILED for church={} — {}", tenant, e.getMessage());
            }
        }
        return new Outcome(diffs.size(), perTenant);
    }
}
