package com.churchgeniuspro.accounting;

import com.churchgeniuspro.service.LedgerSummaryReconcileScheduler;
import com.churchgeniuspro.service.LedgerSummaryService;
import com.churchgeniuspro.service.LedgerSummaryService.Mismatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The nightly ledger_month_summary safety net (ledger scalability, part B): every
 * church that drifted from its ledger rows is rebuilt separately, so one failure
 * never hides another, and a clean run touches nothing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Ledger summary reconcile — per-church repair")
class LedgerSummaryReconcileSchedulerTest {

    @Mock LedgerSummaryService summary;

    private static Mismatch drift(String tenant, String kind) {
        return new Mismatch(tenant, LocalDate.of(2024, 3, 1), kind, -1, 7, -1,
                            new BigDecimal("100.00"), new BigDecimal("101.00"), 3, 3);
    }

    @Test void cleanRunRebuildsNothing() {
        when(summary.reconcile()).thenReturn(List.of());

        var o = new LedgerSummaryReconcileScheduler(summary).reconcileAndRepair();

        assertThat(o.clean()).isTrue();
        assertThat(o.perTenant()).isEmpty();
        verify(summary, never()).rebuild(anyString());
    }

    @Test void eachDriftedChurchIsRebuiltOnce() {
        when(summary.reconcile()).thenReturn(List.of(drift("A", "I"), drift("A", "E"), drift("B", "I")));
        when(summary.rebuild("A")).thenReturn(10);
        when(summary.rebuild("B")).thenReturn(4);

        var o = new LedgerSummaryReconcileScheduler(summary).reconcileAndRepair();

        assertThat(o.mismatchedRows()).isEqualTo(3);
        assertThat(o.perTenant()).containsEntry("A", "rebuilt 10 rows").containsEntry("B", "rebuilt 4 rows");
        verify(summary, times(1)).rebuild("A");
        verify(summary, times(1)).rebuild("B");
    }

    @Test void oneChurchFailingDoesNotStopTheOthers() {
        when(summary.reconcile()).thenReturn(List.of(drift("A", "I"), drift("B", "E")));
        when(summary.rebuild("A")).thenThrow(new IllegalStateException("boom"));
        when(summary.rebuild("B")).thenReturn(4);

        var o = new LedgerSummaryReconcileScheduler(summary).reconcileAndRepair();

        assertThat(o.perTenant().get("A")).startsWith("FAILED: boom");
        assertThat(o.perTenant().get("B")).isEqualTo("rebuilt 4 rows");
        verify(summary).rebuild("B");
    }

    @Test void nightlyNeverThrows() {
        when(summary.reconcile()).thenThrow(new IllegalStateException("relation ledger_month_summary does not exist"));

        new LedgerSummaryReconcileScheduler(summary).nightly();   // must not propagate
    }
}
