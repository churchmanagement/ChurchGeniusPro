package com.churchgeniuspro.plaid.service;

import com.churchgeniuspro.repository.TransactionTypeRepository;
import com.churchgeniuspro.service.ExpenseService;
import com.churchgeniuspro.service.IncomeService;
import com.churchgeniuspro.plaid.repository.PlaidTransactionStagingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Error-handling audit 2026-10-28, M11: bulk approve/reject commits each row on its
 * own (through the transactional proxy), so one failing row is reported and the
 * others stay done — the old self-invoked approve shared one transaction, and a
 * failure after a repository call silently undid every "processed" row at commit.
 */
@DisplayName("Plaid review — bulk approve commits per row")
class PlaidBulkPerRowTest {

    static final String CID = "CHR-TEST";

    PlaidReviewService self = mock(PlaidReviewService.class);
    PlaidAuditService audit = mock(PlaidAuditService.class);

    PlaidReviewService service() {
        return new PlaidReviewService(mock(PlaidTransactionStagingRepository.class), mock(IncomeService.class),
                mock(ExpenseService.class), mock(TransactionTypeRepository.class), audit, self);
    }

    @Test
    @DisplayName("each row goes through the proxy; a failing row is skipped with its reason, the rest are processed")
    void perRowThroughProxy() {
        doThrow(new IllegalArgumentException("This transaction is still pending at the bank and cannot be approved yet."))
                .when(self).bulkOne(CID, 2, true, "rev");
        doThrow(new RuntimeException("could not execute statement [insert into income ...]"))
                .when(self).bulkOne(CID, 3, true, "rev");

        Map<String, Object> out = service().bulk(CID, List.of(1, 2, 3, 4), "approve", "rev");

        assertThat(out.get("processed")).isEqualTo(2);
        @SuppressWarnings("unchecked") List<Map<String, Object>> skipped = (List<Map<String, Object>>) out.get("skipped");
        assertThat(skipped).extracting(m -> m.get("id")).containsExactly(2, 3);
        assertThat(skipped.get(0).get("reason")).isEqualTo("This transaction is still pending at the bank and cannot be approved yet.");
        assertThat(String.valueOf(skipped.get(1).get("reason")))
                .doesNotContain("insert").doesNotContain("statement")
                .startsWith("Could not be processed");
        for (int id : new int[]{1, 2, 3, 4}) verify(self).bulkOne(CID, id, true, "rev");
        verify(audit).record(eq(CID), eq("rev"), eq("BULK_APPROVED"), eq(null), anyString());
    }

    @Test
    @DisplayName("reject goes through the same per-row path")
    void rejectPerRow() {
        Map<String, Object> out = service().bulk(CID, List.of(7), "reject", "rev");
        assertThat(out.get("processed")).isEqualTo(1);
        verify(self).bulkOne(CID, 7, false, "rev");
    }

    @Test
    @DisplayName("bulk itself is not transactional; bulkOne is REQUIRES_NEW and public (so the proxy intercepts it)")
    void annotations() throws Exception {
        Method bulk = PlaidReviewService.class.getMethod("bulk", String.class, List.class, String.class, String.class);
        assertThat(bulk.getAnnotation(Transactional.class)).isNull();
        Method one = PlaidReviewService.class.getMethod("bulkOne", String.class, Integer.class, boolean.class, String.class);
        Transactional t = one.getAnnotation(Transactional.class);
        assertThat(t).isNotNull();
        assertThat(t.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    @DisplayName("without a proxy (unit-test construction) the old in-place path still works")
    void noProxyFallback() {
        PlaidReviewService plain = new PlaidReviewService(mock(PlaidTransactionStagingRepository.class),
                mock(IncomeService.class), mock(ExpenseService.class), mock(TransactionTypeRepository.class), audit);
        Map<String, Object> out = plain.bulk(CID, List.of(5), "reject", "rev");
        // the staging row does not exist in the mock repository → reported as skipped, not thrown
        assertThat(out.get("processed")).isEqualTo(0);
        assertThat((List<?>) out.get("skipped")).hasSize(1);
    }
}
