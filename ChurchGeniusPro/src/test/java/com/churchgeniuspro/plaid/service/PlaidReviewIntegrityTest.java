package com.churchgeniuspro.plaid.service;

import com.churchgeniuspro.plaid.entity.PlaidItem;
import com.churchgeniuspro.plaid.entity.PlaidTransactionStaging;
import com.churchgeniuspro.plaid.repository.PlaidAccountRepository;
import com.churchgeniuspro.plaid.repository.PlaidItemRepository;
import com.churchgeniuspro.plaid.repository.PlaidTransactionStagingRepository;
import com.churchgeniuspro.repository.TransactionTypeRepository;
import com.churchgeniuspro.service.ExpenseService;
import com.churchgeniuspro.service.IncomeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Financial audit M8: an amount Plaid sent that we couldn't parse used to be
 * silently treated as a real $0 transaction — staged as an ordinary,
 * approvable row. A {@code direction} set through the edit API was stored as
 * free text with no whitelist, so an unrecognized value fell through to the
 * INCOME branch at approval time. And every dollar that came into the bank
 * account was staged as INCOME with no way to say otherwise, so a card
 * refund, once approved, inflated giving the same as a real gift.
 *
 * <p>This proves: an unparseable amount stages as ERROR (never a guessed
 * $0), ERROR self-heals back to PENDING once a valid amount is supplied
 * (by sync repairing it, or by a reviewer's edit), {@code direction} is
 * whitelisted to INCOME/EXPENSE/REFUND everywhere it can be set, approval
 * refuses an unreadable amount or an unrecognized direction before it can
 * ever reach the ledger, and REFUND never creates an Income row.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Plaid review — amount/direction integrity (M8)")
class PlaidReviewIntegrityTest {

    private static final String CLIENT = "CHR-ours";
    private static final String ENV    = "sandbox";

    @Mock PlaidClient                          client;
    @Mock PlaidEnvironmentService              plaidEnv;
    @Mock PlaidTokenCipher                     cipher;
    @Mock PlaidItemRepository                  itemRepo;
    @Mock PlaidAccountRepository               accountRepo;
    @Mock PlaidTransactionStagingRepository    stagingRepo;
    @Mock PlaidAuditService                    audit;
    @Mock IncomeService                        incomeService;
    @Mock ExpenseService                       expenseService;
    @Mock TransactionTypeRepository            transactionTypeRepo;

    private static PlaidItem item() {
        PlaidItem it = new PlaidItem();
        it.setId(1);
        it.setClientId(CLIENT);
        it.setItemId("plaid-item-1");
        it.setAccessTokenEnc("enc-token");
        it.setStatus("ACTIVE");
        it.setDeleteFlag(false);
        return it;
    }

    private static Map<String, Object> txnMap(String txnId, Object amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transaction_id", txnId);
        m.put("account_id", "acct-1");
        m.put("amount", amount);
        m.put("date", "2026-02-01");
        m.put("name", "Office Supply Co");
        m.put("pending", false);
        return m;
    }

    private static Map<String, Object> syncResponse(List<Map<String, Object>> added) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accounts", List.of());
        m.put("added", added);
        m.put("modified", List.of());
        m.put("removed", List.of());
        m.put("next_cursor", "cursor-next");
        m.put("has_more", false);
        return m;
    }

    private static PlaidTransactionStaging staged(String direction, BigDecimal amount, String status) {
        PlaidTransactionStaging r = new PlaidTransactionStaging();
        r.setId(42);
        r.setClientId(CLIENT);
        r.setPlaidTransactionId("plaid-txn-42");
        r.setDirection(direction);
        r.setAmount(amount);
        r.setTxnDate(LocalDate.of(2026, 2, 1));
        r.setDescription("Office Supply Co");
        r.setStatus(status);
        r.setPending(false);
        r.setRemoved(false);
        if ("EXPENSE".equals(direction)) {
            r.setMappedPurposeId(1); r.setMappedMainSourceId(1); r.setMappedTransactionTypeId(1);
        } else if ("INCOME".equals(direction)) {
            r.setMappedSubSourceId(1); r.setMappedTransactionTypeId(1);
        }
        return r;
    }

    // ── PlaidSyncService: unparseable amount → ERROR, never a guessed $0 ───────

    @Nested
    @DisplayName("PlaidSyncService")
    class Sync {

        private PlaidSyncService sync;

        @BeforeEach
        void setUp() {
            sync = new PlaidSyncService(client, plaidEnv, cipher, itemRepo, accountRepo, stagingRepo, audit);
            when(cipher.decrypt("enc-token")).thenReturn("plain-token");
            when(plaidEnv.envForItem(any())).thenReturn(ENV);
        }

        @Test
        @DisplayName("an unparseable amount stages the row as ERROR, not an approvable $0")
        void unparseableAmountStagesAsError() {
            when(client.transactionsSync(eq(ENV), eq("plain-token"), any()))
                    .thenReturn(syncResponse(List.of(txnMap("txn-bad", "not-a-number"))));
            when(stagingRepo.findByPlaidTransactionIdAndClientId("txn-bad", CLIENT)).thenReturn(Optional.empty());

            sync.sync(item(), "actor");

            ArgumentCaptor<PlaidTransactionStaging> saved = ArgumentCaptor.forClass(PlaidTransactionStaging.class);
            verify(stagingRepo).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo("ERROR");
            assertThat(saved.getValue().getAmount()).isNull();
            assertThat(saved.getValue().getDirection()).isNull();
        }

        @Test
        @DisplayName("a parseable amount still stages normally — ERROR is not the default")
        void parseableAmountStagesNormally() {
            when(client.transactionsSync(eq(ENV), eq("plain-token"), any()))
                    .thenReturn(syncResponse(List.of(txnMap("txn-good", "-40.00"))));
            when(stagingRepo.findByPlaidTransactionIdAndClientId("txn-good", CLIENT)).thenReturn(Optional.empty());

            sync.sync(item(), "actor");

            ArgumentCaptor<PlaidTransactionStaging> saved = ArgumentCaptor.forClass(PlaidTransactionStaging.class);
            verify(stagingRepo).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo("PENDING");
            assertThat(saved.getValue().getAmount()).isEqualByComparingTo("40.00");
            assertThat(saved.getValue().getDirection()).isEqualTo("INCOME");
        }

        @Test
        @DisplayName("a later sync with a now-valid amount repairs an ERROR row back to PENDING")
        void laterValidSyncRepairsAnErrorRow() {
            PlaidTransactionStaging existing = staged(null, null, "ERROR");
            existing.setPlaidTransactionId("txn-fixed");
            when(client.transactionsSync(eq(ENV), eq("plain-token"), any()))
                    .thenReturn(syncResponse(List.of(txnMap("txn-fixed", "25.00"))));
            when(stagingRepo.findByPlaidTransactionIdAndClientId("txn-fixed", CLIENT)).thenReturn(Optional.of(existing));

            sync.sync(item(), "actor");

            assertThat(existing.getStatus()).isEqualTo("PENDING");
            assertThat(existing.getAmount()).isEqualByComparingTo("25.00");
            assertThat(existing.getDirection()).isEqualTo("EXPENSE");
        }

        @Test
        @DisplayName("an already-reviewed row is still never touched, even if resynced with an unparseable amount")
        void approvedRowNeverDowngradedToError() {
            PlaidTransactionStaging existing = staged("INCOME", new BigDecimal("25.00"), "APPROVED");
            existing.setPlaidTransactionId("txn-locked");
            when(client.transactionsSync(eq(ENV), eq("plain-token"), any()))
                    .thenReturn(syncResponse(List.of(txnMap("txn-locked", "garbage"))));
            when(stagingRepo.findByPlaidTransactionIdAndClientId("txn-locked", CLIENT)).thenReturn(Optional.of(existing));

            sync.sync(item(), "actor");

            verify(stagingRepo, never()).save(any());
            assertThat(existing.getStatus()).isEqualTo("APPROVED");
        }
    }

    // ── PlaidReviewService.edit(): direction whitelist + ERROR self-heal ──────

    @Nested
    @DisplayName("edit()")
    class Edit {

        private PlaidReviewService review;

        @BeforeEach
        void setUp() {
            review = new PlaidReviewService(stagingRepo, incomeService, expenseService, transactionTypeRepo, audit);
        }

        @Test
        @DisplayName("an unrecognized direction is refused, never stored")
        void rejectsAnUnrecognizedDirection() {
            when(stagingRepo.findByIdAndClientId(42, CLIENT))
                    .thenReturn(Optional.of(staged("INCOME", new BigDecimal("25.00"), "PENDING")));

            assertThrows(IllegalArgumentException.class,
                    () -> review.edit(CLIENT, 42, Map.of("direction", "BANANA"), "reviewer"));
            verify(stagingRepo, never()).save(any());
        }

        @Test
        @DisplayName("\"refund\" (any case) is accepted and normalized to REFUND")
        void acceptsRefundDirection() {
            when(stagingRepo.findByIdAndClientId(42, CLIENT))
                    .thenReturn(Optional.of(staged("INCOME", new BigDecimal("25.00"), "PENDING")));

            review.edit(CLIENT, 42, Map.of("direction", "refund"), "reviewer");

            ArgumentCaptor<PlaidTransactionStaging> saved = ArgumentCaptor.forClass(PlaidTransactionStaging.class);
            verify(stagingRepo).save(saved.capture());
            assertThat(saved.getValue().getDirection()).isEqualTo("REFUND");
        }

        @Test
        @DisplayName("supplying a valid amount on an ERROR row revives it to PENDING")
        void validAmountRevivesErrorRow() {
            when(stagingRepo.findByIdAndClientId(42, CLIENT))
                    .thenReturn(Optional.of(staged(null, null, "ERROR")));

            review.edit(CLIENT, 42, Map.of("amount", "18.50"), "reviewer");

            ArgumentCaptor<PlaidTransactionStaging> saved = ArgumentCaptor.forClass(PlaidTransactionStaging.class);
            verify(stagingRepo).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("an invalid amount on an ERROR row leaves it in ERROR")
        void invalidAmountLeavesErrorRowInError() {
            when(stagingRepo.findByIdAndClientId(42, CLIENT))
                    .thenReturn(Optional.of(staged(null, null, "ERROR")));

            review.edit(CLIENT, 42, Map.of("amount", "-5.00"), "reviewer");

            ArgumentCaptor<PlaidTransactionStaging> saved = ArgumentCaptor.forClass(PlaidTransactionStaging.class);
            verify(stagingRepo).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo("ERROR");
        }

        @Test
        @DisplayName("an ERROR row can be edited at all — it is not locked out like an approved/rejected row")
        void errorRowIsEditable() {
            when(stagingRepo.findByIdAndClientId(42, CLIENT))
                    .thenReturn(Optional.of(staged(null, null, "ERROR")));

            Map<String, Object> result = review.edit(CLIENT, 42, Map.of("description", "Corrected"), "reviewer");

            assertThat(result.get("description")).isEqualTo("Corrected");
        }
    }

    // ── PlaidReviewService.approve(): pre-claim guards + REFUND promotion ─────

    @Nested
    @DisplayName("approve()")
    class Approve {

        private PlaidReviewService review;

        @BeforeEach
        void setUp() {
            review = new PlaidReviewService(stagingRepo, incomeService, expenseService, transactionTypeRepo, audit);
        }

        @Test
        @DisplayName("refuses a null amount before claiming or touching the ledger")
        void refusesNullAmount() {
            when(stagingRepo.findByIdAndClientId(42, CLIENT))
                    .thenReturn(Optional.of(staged(null, null, "ERROR")));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> review.approve(CLIENT, 42, "reviewer"));

            assertThat(ex.getMessage()).contains("could not be read");
            verify(stagingRepo, never()).claimPending(anyInt(), anyString(), anyString(), anyString(), any());
            verifyNoInteractions(incomeService);
            verifyNoInteractions(expenseService);
        }

        @Test
        @DisplayName("refuses a zero amount before claiming or touching the ledger")
        void refusesZeroAmount() {
            when(stagingRepo.findByIdAndClientId(42, CLIENT))
                    .thenReturn(Optional.of(staged("INCOME", BigDecimal.ZERO, "PENDING")));

            assertThrows(IllegalArgumentException.class, () -> review.approve(CLIENT, 42, "reviewer"));
            verify(stagingRepo, never()).claimPending(anyInt(), anyString(), anyString(), anyString(), any());
            verifyNoInteractions(incomeService);
        }

        @Test
        @DisplayName("refuses an unrecognized direction before claiming or touching the ledger")
        void refusesUnrecognizedDirection() {
            // A row in an unexpected state (e.g. pre-fix legacy data) — never
            // producible through edit() anymore, but promote() must not trust
            // that on faith.
            when(stagingRepo.findByIdAndClientId(42, CLIENT))
                    .thenReturn(Optional.of(staged("WIRE", new BigDecimal("25.00"), "PENDING")));

            assertThrows(IllegalArgumentException.class, () -> review.approve(CLIENT, 42, "reviewer"));
            verify(stagingRepo, never()).claimPending(anyInt(), anyString(), anyString(), anyString(), any());
            verifyNoInteractions(incomeService);
            verifyNoInteractions(expenseService);
        }

        @Test
        @DisplayName("approving a REFUND claims and audits the row but never creates an Income row")
        void refundNeverCreatesIncome() {
            when(stagingRepo.findByIdAndClientId(42, CLIENT))
                    .thenReturn(Optional.of(staged("REFUND", new BigDecimal("30.00"), "PENDING")));
            when(stagingRepo.claimPending(eq(42), eq(CLIENT), eq("APPROVED"), eq("reviewer"), any())).thenReturn(1);

            Map<String, Object> result = review.approve(CLIENT, 42, "reviewer");

            assertThat(result.get("status")).isEqualTo("APPROVED");
            assertThat(result.get("promotedIncomeId")).isNull();
            assertThat(result.get("promotedExpenseId")).isNull();
            verify(stagingRepo).claimPending(eq(42), eq(CLIENT), eq("APPROVED"), eq("reviewer"), any());
            verifyNoInteractions(incomeService);
            verifyNoInteractions(expenseService);
        }

        @Test
        @DisplayName("a REFUND approves with no fund/category mapping at all — none is needed")
        void refundNeedsNoMapping() {
            PlaidTransactionStaging r = staged("REFUND", new BigDecimal("30.00"), "PENDING");
            r.setMappedSubSourceId(null);
            r.setMappedPurposeId(null);
            r.setMappedMainSourceId(null);
            r.setMappedTransactionTypeId(null);
            when(stagingRepo.findByIdAndClientId(42, CLIENT)).thenReturn(Optional.of(r));
            when(stagingRepo.claimPending(eq(42), eq(CLIENT), eq("APPROVED"), eq("reviewer"), any())).thenReturn(1);

            Map<String, Object> result = review.approve(CLIENT, 42, "reviewer");

            assertThat(result.get("status")).isEqualTo("APPROVED");
        }
    }
}
