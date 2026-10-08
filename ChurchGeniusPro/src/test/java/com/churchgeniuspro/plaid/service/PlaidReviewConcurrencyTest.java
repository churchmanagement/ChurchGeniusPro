package com.churchgeniuspro.plaid.service;

import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.plaid.entity.PlaidTransactionStaging;
import com.churchgeniuspro.plaid.repository.PlaidTransactionStagingRepository;
import com.churchgeniuspro.repository.TransactionTypeRepository;
import com.churchgeniuspro.service.ExpenseService;
import com.churchgeniuspro.service.IncomeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Financial audit M6: approving (or rejecting) a staged Plaid transaction used
 * to read its status, then act on it, then write the new status back — three
 * separate steps with nothing stopping two concurrent requests for the same
 * row (a double-click, or two reviewers) from both reading PENDING and both
 * proceeding. For approve, that meant two ledger rows for one bank
 * transaction; for a reject racing an approve, it meant the staging row could
 * end up REJECTED while the ledger row the approve had already posted stayed
 * in place. {@link PlaidReviewService#approve} and {@link
 * PlaidReviewService#reject} now claim the row with a single atomic
 * {@code UPDATE ... WHERE status = 'PENDING'} before doing anything else —
 * of two concurrent requests, at most one can ever win that claim.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Plaid review — approve/reject claim the row atomically (M6)")
class PlaidReviewConcurrencyTest {

    private static final String CLIENT = "CHR-ours";

    @Mock PlaidTransactionStagingRepository stagingRepo;
    @Mock IncomeService             incomeService;
    @Mock ExpenseService            expenseService;
    @Mock TransactionTypeRepository transactionTypeRepo;
    @Mock PlaidAuditService         audit;

    private PlaidReviewService review;

    @BeforeEach
    void setUp() {
        review = new PlaidReviewService(stagingRepo, incomeService, expenseService, transactionTypeRepo, audit);
    }

    private static PlaidTransactionStaging staged(int id) {
        PlaidTransactionStaging r = new PlaidTransactionStaging();
        r.setId(id);
        r.setClientId(CLIENT);
        r.setPlaidTransactionId("plaid-txn-" + id);
        r.setDirection("INCOME");
        r.setAmount(new BigDecimal("75.00"));
        r.setTxnDate(LocalDate.of(2026, 1, 15));
        r.setDescription("Grocery Co");
        r.setStatus("PENDING");
        r.setPending(false);
        r.setRemoved(false);
        r.setMappedSubSourceId(1);
        r.setMappedTransactionTypeId(1);
        return r;
    }

    /** Generic stub matching any call shape of {@code IncomeService.createIncome}. */
    private void stubIncomeCreateSucceeds() {
        when(incomeService.createIncome(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(),
                anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new Income());
    }

    @Nested
    @DisplayName("approve()")
    class Approve {

        @Test
        @DisplayName("claims the row (id, clientId, APPROVED, actor) before promoting")
        void claimsBeforePromoting() {
            when(stagingRepo.findByIdAndClientId(42, CLIENT)).thenReturn(Optional.of(staged(42)));
            when(stagingRepo.claimPending(eq(42), eq(CLIENT), eq("APPROVED"), eq("reviewer"), any())).thenReturn(1);
            stubIncomeCreateSucceeds();

            Map<String, Object> result = review.approve(CLIENT, 42, "reviewer");

            assertThat(result.get("status")).isEqualTo("APPROVED");
            verify(stagingRepo).claimPending(eq(42), eq(CLIENT), eq("APPROVED"), eq("reviewer"), any());
        }

        @Test
        @DisplayName("a lost claim (0 rows) refuses without ever calling the ledger — no second ledger row")
        void lostClaimNeverPromotes() {
            when(stagingRepo.findByIdAndClientId(42, CLIENT)).thenReturn(Optional.of(staged(42)));
            when(stagingRepo.claimPending(eq(42), eq(CLIENT), eq("APPROVED"), anyString(), any())).thenReturn(0);

            IllegalArgumentException ex = catchIllegalArgument(() -> review.approve(CLIENT, 42, "reviewer"));

            assertThat(ex).isNotNull();
            verifyNoInteractions(incomeService);
            verifyNoInteractions(expenseService);
        }

        @Test
        @DisplayName("a lost claim's error names the row's true current status, re-read after losing")
        void lostClaimReportsCurrentStatus() {
            PlaidTransactionStaging approvedCopy = staged(42);
            approvedCopy.setStatus("APPROVED");
            when(stagingRepo.findByIdAndClientId(42, CLIENT))
                    .thenReturn(Optional.of(staged(42)))       // first read, by requirePending
                    .thenReturn(Optional.of(approvedCopy));    // second read, after losing the claim
            when(stagingRepo.claimPending(eq(42), eq(CLIENT), eq("APPROVED"), anyString(), any())).thenReturn(0);

            IllegalArgumentException ex = catchIllegalArgument(() -> review.approve(CLIENT, 42, "reviewer"));

            assertThat(ex).isNotNull();
            assertThat(ex.getMessage()).contains("already been approved");
        }

        @Test
        @DisplayName("never writes the row through save() — the atomic claim is the only status write")
        void successNeverCallsSave() {
            when(stagingRepo.findByIdAndClientId(42, CLIENT)).thenReturn(Optional.of(staged(42)));
            when(stagingRepo.claimPending(any(), any(), any(), any(), any())).thenReturn(1);
            stubIncomeCreateSucceeds();

            review.approve(CLIENT, 42, "reviewer");

            verify(stagingRepo, never()).save(any());
        }
    }

    @Nested
    @DisplayName("reject()")
    class Reject {

        @Test
        @DisplayName("claims the row (id, clientId, REJECTED, actor) and never touches the ledger")
        void claimsAndNeverTouchesLedger() {
            when(stagingRepo.findByIdAndClientId(42, CLIENT)).thenReturn(Optional.of(staged(42)));
            when(stagingRepo.claimPending(eq(42), eq(CLIENT), eq("REJECTED"), eq("reviewer"), any())).thenReturn(1);

            Map<String, Object> result = review.reject(CLIENT, 42, "reviewer");

            assertThat(result.get("status")).isEqualTo("REJECTED");
            verifyNoInteractions(incomeService);
            verifyNoInteractions(expenseService);
        }

        @Test
        @DisplayName("a lost claim refuses and never writes — a reject can't silently overwrite an approval that already posted")
        void lostClaimNeverWrites() {
            when(stagingRepo.findByIdAndClientId(42, CLIENT)).thenReturn(Optional.of(staged(42)));
            when(stagingRepo.claimPending(eq(42), eq(CLIENT), eq("REJECTED"), anyString(), any())).thenReturn(0);

            IllegalArgumentException ex = catchIllegalArgument(() -> review.reject(CLIENT, 42, "reviewer"));

            assertThat(ex).isNotNull();
            verify(stagingRepo, never()).save(any());
        }
    }

    @Nested
    @DisplayName("bulk()")
    class Bulk {

        @Test
        @DisplayName("when one of two rows loses its claim (already grabbed), it's skipped with a reason and the other still posts")
        void oneWinnerOneLoserInSameBatch() {
            when(stagingRepo.findByIdAndClientId(42, CLIENT)).thenReturn(Optional.of(staged(42)));
            when(stagingRepo.findByIdAndClientId(43, CLIENT)).thenReturn(Optional.of(staged(43)));
            when(stagingRepo.claimPending(eq(42), eq(CLIENT), eq("APPROVED"), anyString(), any())).thenReturn(1);
            when(stagingRepo.claimPending(eq(43), eq(CLIENT), eq("APPROVED"), anyString(), any())).thenReturn(0);
            stubIncomeCreateSucceeds();

            Map<String, Object> result = review.bulk(CLIENT, List.of(42, 43), "approve", "reviewer");

            assertThat(result.get("processed")).isEqualTo(1);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> skipped = (List<Map<String, Object>>) result.get("skipped");
            assertThat(skipped).hasSize(1);
            assertThat(skipped.get(0).get("id")).isEqualTo(43);
            assertThat(String.valueOf(skipped.get(0).get("reason"))).contains("already been");
            verify(incomeService, times(1)).createIncome(any(), any(), any(), any(), any(), any(), any(), any(),
                    anyBoolean(), anyString(), anyString(), anyString(), anyBoolean());
        }
    }

    // ── helper ──────────────────────────────────────────────────────────────

    private interface ThrowingRunnable { void run(); }

    private static IllegalArgumentException catchIllegalArgument(ThrowingRunnable r) {
        try {
            r.run();
            return null;
        } catch (IllegalArgumentException e) {
            return e;
        }
    }
}
