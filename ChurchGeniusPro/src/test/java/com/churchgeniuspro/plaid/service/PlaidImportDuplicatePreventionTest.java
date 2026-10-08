package com.churchgeniuspro.plaid.service;

import com.churchgeniuspro.hibernate.Expense;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.plaid.controller.PlaidReviewController;
import com.churchgeniuspro.plaid.entity.PlaidTransactionStaging;
import com.churchgeniuspro.plaid.repository.PlaidTransactionStagingRepository;
import com.churchgeniuspro.plaid.util.PlaidGuard;
import com.churchgeniuspro.repository.TransactionTypeRepository;
import com.churchgeniuspro.service.DuplicateImportException;
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
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plaid approval promotes a staged transaction to the income/expense ledger — the
 * other half of the two import channels that, before this, could not see each
 * other's postings (Financial audit H8). {@code PlaidTransactionStaging} already
 * carries a bank-guaranteed-unique {@code plaidTransactionId}; the ledger row it
 * promotes to now carries that as its {@code importRef}, so a Bank Import of the
 * same period recognizes it via the shared date+amount soft check.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Plaid review — promotion carries importRef, force plumbing (H8)")
class PlaidImportDuplicatePreventionTest {

    private static final String CLIENT = "CHR-ours";

    @Mock PlaidTransactionStagingRepository stagingRepo;
    @Mock IncomeService          incomeService;
    @Mock ExpenseService         expenseService;
    @Mock TransactionTypeRepository transactionTypeRepo;
    @Mock PlaidAuditService      audit;
    @Mock PlaidGuard             guard;

    PlaidReviewService    review;
    PlaidReviewController controller;

    @BeforeEach
    void setUp() {
        review = new PlaidReviewService(stagingRepo, incomeService, expenseService, transactionTypeRepo, audit);
        controller = new PlaidReviewController(guard, review);
        when(guard.requireBankAccess(any())).thenReturn(null);
        // Financial audit M6: approve/reject now claim the row atomically before
        // acting on it. These tests are about promotion/duplicate behavior, not
        // the claim itself (covered separately in PlaidReviewConcurrencyTest), so
        // the claim always "wins" here.
        when(stagingRepo.claimPending(anyInt(), anyString(), anyString(), anyString(), any()))
                .thenReturn(1);
    }

    private PlaidTransactionStaging staged(String direction) {
        PlaidTransactionStaging r = new PlaidTransactionStaging();
        r.setId(42);
        r.setClientId(CLIENT);
        r.setPlaidTransactionId("plaid-txn-abc123");
        r.setDirection(direction);
        r.setAmount(new BigDecimal("75.00"));
        r.setTxnDate(LocalDate.of(2026, 1, 15));
        r.setDescription("Grocery Co");
        r.setStatus("PENDING");
        r.setPending(false);
        r.setRemoved(false);
        if ("EXPENSE".equals(direction)) {
            r.setMappedPurposeId(1); r.setMappedMainSourceId(1); r.setMappedTransactionTypeId(1);
        } else {
            r.setMappedSubSourceId(1); r.setMappedTransactionTypeId(1);
        }
        return r;
    }

    @Nested
    @DisplayName("promote()")
    class Promote {

        @Test
        @DisplayName("an income approval passes \"plaid:<plaidTransactionId>\" as the importRef")
        void incomePromotionCarriesImportRef() {
            when(stagingRepo.findByIdAndClientId(42, CLIENT)).thenReturn(Optional.of(staged("INCOME")));
            Income posted = new Income(); posted.setId(500);
            when(incomeService.createIncome(any(), eq(1), any(), eq(1), isNull(), any(BigDecimal.class), anyString(),
                    any(), eq(false), eq(CLIENT), anyString(), anyString(), anyBoolean()))
                    .thenReturn(posted);

            review.approve(CLIENT, 42, "reviewer");

            ArgumentCaptor<String> refCaptor = ArgumentCaptor.forClass(String.class);
            verify(incomeService).createIncome(any(), eq(1), any(), eq(1), isNull(), any(BigDecimal.class), anyString(),
                    any(), eq(false), eq(CLIENT), anyString(), refCaptor.capture(), eq(false));
            assertThat(refCaptor.getValue()).isEqualTo("plaid:plaid-txn-abc123");
        }

        @Test
        @DisplayName("an expense approval passes the same importRef convention")
        void expensePromotionCarriesImportRef() {
            when(stagingRepo.findByIdAndClientId(42, CLIENT)).thenReturn(Optional.of(staged("EXPENSE")));
            Expense posted = new Expense(); posted.setId(600);
            when(expenseService.createExpense(eq(1), eq(1), any(), eq(1), isNull(), any(BigDecimal.class), anyString(),
                    eq(false), eq(CLIENT), anyString(), anyString(), anyBoolean()))
                    .thenReturn(posted);

            review.approve(CLIENT, 42, "reviewer");

            ArgumentCaptor<String> refCaptor = ArgumentCaptor.forClass(String.class);
            verify(expenseService).createExpense(eq(1), eq(1), any(), eq(1), isNull(), any(BigDecimal.class), anyString(),
                    eq(false), eq(CLIENT), anyString(), refCaptor.capture(), eq(false));
            assertThat(refCaptor.getValue()).isEqualTo("plaid:plaid-txn-abc123");
        }

        @Test
        @DisplayName("the 3-arg approve() (existing callers, e.g. bulk) defaults force to false")
        void threeArgApproveDefaultsForceFalse() {
            when(stagingRepo.findByIdAndClientId(42, CLIENT)).thenReturn(Optional.of(staged("INCOME")));
            when(incomeService.createIncome(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(),
                    anyString(), anyString(), anyString(), anyBoolean()))
                    .thenReturn(new Income());

            review.approve(CLIENT, 42, "reviewer");   // 3-arg overload

            verify(incomeService).createIncome(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(),
                    anyString(), anyString(), anyString(), eq(false));
        }

        @Test
        @DisplayName("approve(force=true) is threaded through to the ledger create call")
        void forceIsThreaded() {
            when(stagingRepo.findByIdAndClientId(42, CLIENT)).thenReturn(Optional.of(staged("INCOME")));
            when(incomeService.createIncome(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(),
                    anyString(), anyString(), anyString(), anyBoolean()))
                    .thenReturn(new Income());

            review.approve(CLIENT, 42, "reviewer", true);

            verify(incomeService).createIncome(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(),
                    anyString(), anyString(), anyString(), eq(true));
        }
    }

    @Nested
    @DisplayName("bulk()")
    class Bulk {

        @Test
        @DisplayName("a soft duplicate is skipped with a reason, not silently approved, and never forces past it")
        void softDuplicateSkippedWithReason() {
            when(stagingRepo.findByIdAndClientId(42, CLIENT)).thenReturn(Optional.of(staged("INCOME")));
            when(incomeService.createIncome(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(),
                    anyString(), anyString(), anyString(), eq(false)))
                    .thenThrow(new DuplicateImportException("A possible duplicate already exists for this date and amount.",
                            "income", 9, "2026-01-15", new BigDecimal("75.00"), null, false));

            Map<String, Object> result = review.bulk(CLIENT, List.of(42), "approve", "reviewer");

            assertThat(result.get("processed")).isEqualTo(0);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> skipped = (List<Map<String, Object>>) result.get("skipped");
            assertThat(skipped).hasSize(1);
            assertThat(skipped.get(0).get("reason")).isEqualTo("A possible duplicate already exists for this date and amount.");
            // bulk() never retries with force — a human must look at a flagged row.
            verify(incomeService, org.mockito.Mockito.never()).createIncome(any(), any(), any(), any(), any(), any(),
                    any(), any(), anyBoolean(), anyString(), anyString(), anyString(), eq(true));
        }
    }

    @Nested
    @DisplayName("PlaidReviewController")
    class ControllerMapping {

        private MockHttpServletRequest req() {
            MockHttpServletRequest r = new MockHttpServletRequest();
            MockHttpSession s = new MockHttpSession();
            s.setAttribute("appClientId", CLIENT);
            s.setAttribute("username", "reviewer");
            r.setSession(s);
            return r;
        }

        @Test
        @DisplayName("a plain POST /approve (no body) reaches the service as force=false")
        void noBodyMeansNotForced() {
            when(stagingRepo.findByIdAndClientId(42, CLIENT)).thenReturn(Optional.of(staged("INCOME")));
            when(incomeService.createIncome(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(),
                    anyString(), anyString(), anyString(), anyBoolean()))
                    .thenReturn(new Income());

            ResponseEntity<Map<String, Object>> res = controller.approve(42, null, req());

            assertThat(res.getStatusCode().value()).isEqualTo(200);
            verify(incomeService).createIncome(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(),
                    anyString(), anyString(), anyString(), eq(false));
        }

        @Test
        @DisplayName("a duplicate raised during approval maps to 409 with the duplicate payload, not a bare 400")
        void duplicateMapsTo409() {
            when(stagingRepo.findByIdAndClientId(42, CLIENT)).thenReturn(Optional.of(staged("INCOME")));
            when(incomeService.createIncome(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(),
                    anyString(), anyString(), anyString(), anyBoolean()))
                    .thenThrow(new DuplicateImportException("A possible duplicate already exists for this date and amount.",
                            "income", 9, "2026-01-15", new BigDecimal("75.00"), null, false));

            ResponseEntity<Map<String, Object>> res = controller.approve(42, null, req());

            assertThat(res.getStatusCode().value()).isEqualTo(409);
            @SuppressWarnings("unchecked")
            Map<String, Object> dup = (Map<String, Object>) res.getBody().get("duplicate");
            assertThat(dup.get("id")).isEqualTo(9);
            assertThat(dup.get("hard")).isEqualTo(false);
        }

        @Test
        @DisplayName("{force:true} in the body reaches the service as force=true")
        void bodyForceTrueIsThreaded() {
            when(stagingRepo.findByIdAndClientId(42, CLIENT)).thenReturn(Optional.of(staged("INCOME")));
            when(incomeService.createIncome(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(),
                    anyString(), anyString(), anyString(), anyBoolean()))
                    .thenReturn(new Income());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("force", true);
            ResponseEntity<Map<String, Object>> res = controller.approve(42, body, req());

            assertThat(res.getStatusCode().value()).isEqualTo(200);
            verify(incomeService).createIncome(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(),
                    anyString(), anyString(), anyString(), eq(true));
        }
    }
}
