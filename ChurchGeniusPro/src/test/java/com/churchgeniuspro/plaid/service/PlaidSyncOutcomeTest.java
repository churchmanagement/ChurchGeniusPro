package com.churchgeniuspro.plaid.service;

import com.churchgeniuspro.plaid.entity.PlaidItem;
import com.churchgeniuspro.plaid.repository.PlaidAccountRepository;
import com.churchgeniuspro.plaid.repository.PlaidItemRepository;
import com.churchgeniuspro.plaid.repository.PlaidTransactionStagingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Error-handling audit 2026-10-28, M8: a transient Plaid condition never marks the
 * connection broken, the two documented retry-now codes are retried inside the run,
 * and a real connection fault is reported with an outcome the caller can act on.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PlaidSyncService — outcomes, transient codes, retries")
class PlaidSyncOutcomeTest {

    static final String CHURCH = "CHR-1", ENV = "sandbox";

    @Mock PlaidClient client;
    @Mock PlaidEnvironmentService plaidEnv;
    @Mock PlaidTokenCipher cipher;
    @Mock PlaidItemRepository itemRepo;
    @Mock PlaidAccountRepository accountRepo;
    @Mock PlaidTransactionStagingRepository stagingRepo;
    @Mock PlaidAuditService audit;

    PlaidSyncService sync;
    PlaidItem item;
    List<Long> pauses = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sync = new PlaidSyncService(client, plaidEnv, cipher, itemRepo, accountRepo, stagingRepo, audit);
        sync.setSleeper(pauses::add);
        item = new PlaidItem();
        item.setId(7); item.setClientId(CHURCH); item.setItemId("item-7");
        item.setAccessTokenEnc("enc"); item.setStatus("ACTIVE"); item.setSyncCursor("cur-0"); item.setDeleteFlag(false);
        when(cipher.decrypt("enc")).thenReturn("tok");
        when(plaidEnv.envForItem(any())).thenReturn(ENV);
        when(itemRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(stagingRepo.findByPlaidTransactionIdAndClientId(anyString(), eq(CHURCH))).thenReturn(Optional.empty());
    }

    static RestClientResponseException plaidError(int http, String code) {
        String body = "{\"error_type\":\"X\",\"error_code\":\"" + code + "\",\"request_id\":\"r1\"}";
        return new RestClientResponseException("Plaid /transactions/sync HTTP " + http + " error_code=" + code,
                HttpStatus.valueOf(http), "x", HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    static Map<String, Object> page(String next, boolean more, String... txnIds) {
        List<Map<String, Object>> added = new ArrayList<>();
        for (String id : txnIds) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("transaction_id", id); t.put("amount", "12.00"); t.put("date", "2026-10-01"); t.put("name", "x");
            added.add(t);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accounts", List.of()); m.put("added", added); m.put("modified", List.of()); m.put("removed", List.of());
        m.put("next_cursor", next); m.put("has_more", more);
        return m;
    }

    @Test @DisplayName("RATE_LIMIT_EXCEEDED: paused once and retried; status untouched when it persists")
    void rateLimitRetriedThenDeferred() {
        when(client.transactionsSync(ENV, "tok", "cur-0")).thenThrow(plaidError(429, "RATE_LIMIT_EXCEEDED"));
        PlaidSyncService.SyncResult r = sync.syncWithResult(item, "actor");
        assertThat(r.outcome()).isEqualTo(PlaidSyncService.Outcome.RETRY_LATER);
        assertThat(r.errorCode()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(r.message()).contains("busy");
        assertThat(pauses).containsExactly(PlaidSyncService.RATE_LIMIT_PAUSE_MS);
        verify(client, times(2)).transactionsSync(ENV, "tok", "cur-0");
        assertThat(item.getStatus()).isEqualTo("ACTIVE");
        assertThat(item.getSyncCursor()).isEqualTo("cur-0");
        assertThat(item.getErrorCode()).isEqualTo("RATE_LIMIT_EXCEEDED");
        verify(audit).record(eq(CHURCH), eq("actor"), eq("SYNC_DEFERRED"), eq("item-7"), anyString());
    }

    @Test @DisplayName("RATE_LIMIT_EXCEEDED once, then success: cursor advances, code cleared")
    void rateLimitThenOk() {
        when(client.transactionsSync(ENV, "tok", "cur-0"))
                .thenThrow(plaidError(429, "RATE_LIMIT_EXCEEDED"))
                .thenReturn(page("cur-1", false, "t1", "t2"));
        PlaidSyncService.SyncResult r = sync.syncWithResult(item, "actor");
        assertThat(r.ok()).isTrue();
        assertThat(r.staged()).isEqualTo(2);
        assertThat(item.getSyncCursor()).isEqualTo("cur-1");
        assertThat(item.getErrorCode()).isNull();
        assertThat(item.getStatus()).isEqualTo("ACTIVE");
    }

    @Test @DisplayName("MUTATION_DURING_PAGINATION: pagination restarts from the saved cursor once, no pause")
    void mutationRestartsFromSavedCursor() {
        when(client.transactionsSync(ENV, "tok", "cur-0")).thenReturn(page("cur-a", true, "t1"));
        when(client.transactionsSync(ENV, "tok", "cur-a"))
                .thenThrow(plaidError(400, "TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION"))
                .thenReturn(page("cur-b", false, "t2"));
        PlaidSyncService.SyncResult r = sync.syncWithResult(item, "actor");
        assertThat(r.ok()).isTrue();
        verify(client, times(2)).transactionsSync(ENV, "tok", "cur-0");   // restarted from the SAVED cursor
        assertThat(item.getSyncCursor()).isEqualTo("cur-b");
        assertThat(pauses).isEmpty();
        assertThat(item.getStatus()).isEqualTo("ACTIVE");
    }

    @Test @DisplayName("MUTATION twice in a row: deferred, cursor and status untouched")
    void mutationTwiceDeferred() {
        when(client.transactionsSync(ENV, "tok", "cur-0")).thenThrow(plaidError(400, "TRANSACTIONS_SYNC_MUTATION_DURING_PAGINATION"));
        PlaidSyncService.SyncResult r = sync.syncWithResult(item, "actor");
        assertThat(r.retryLater()).isTrue();
        verify(client, times(2)).transactionsSync(ENV, "tok", "cur-0");
        assertThat(item.getStatus()).isEqualTo("ACTIVE");
        assertThat(item.getSyncCursor()).isEqualTo("cur-0");
    }

    @Test @DisplayName("institution down, Plaid 5xx and timeouts are transient: status stays ACTIVE")
    void transientConditions() {
        for (RuntimeException ex : List.of(plaidError(400, "INSTITUTION_DOWN"), plaidError(500, "INTERNAL_SERVER_ERROR"),
                plaidError(502, "WHATEVER"), new ResourceAccessException("Read timed out"))) {
            item.setStatus("ACTIVE"); item.setErrorCode(null);
            doThrow(ex).when(client).transactionsSync(ENV, "tok", "cur-0");
            PlaidSyncService.SyncResult r = sync.syncWithResult(item, "actor");
            assertThat(r.outcome()).as(ex.toString()).isEqualTo(PlaidSyncService.Outcome.RETRY_LATER);
            assertThat(item.getStatus()).as(ex.toString()).isEqualTo("ACTIVE");
            assertThat(r.message()).doesNotContain("Plaid /").doesNotContain("HTTP");
        }
        assertThat(pauses).isEmpty();
    }

    @Test @DisplayName("ITEM_LOGIN_REQUIRED → LOGIN_REQUIRED outcome + status; other codes → ERROR; message is a sentence")
    void connectionFaults() {
        when(client.transactionsSync(ENV, "tok", "cur-0")).thenThrow(plaidError(400, "ITEM_LOGIN_REQUIRED"));
        PlaidSyncService.SyncResult r = sync.syncWithResult(item, "actor");
        assertThat(r.outcome()).isEqualTo(PlaidSyncService.Outcome.LOGIN_REQUIRED);
        assertThat(item.getStatus()).isEqualTo("LOGIN_REQUIRED");
        assertThat(item.getErrorCode()).isEqualTo("ITEM_LOGIN_REQUIRED");
        assertThat(r.message()).contains("sign in again");

        item.setStatus("ACTIVE");
        doThrow(plaidError(400, "INVALID_ACCESS_TOKEN")).when(client).transactionsSync(ENV, "tok", "cur-0");
        r = sync.syncWithResult(item, "actor");
        assertThat(r.outcome()).isEqualTo(PlaidSyncService.Outcome.ERROR);
        assertThat(item.getStatus()).isEqualTo("ERROR");
        assertThat(r.message()).contains("no longer valid");
        // the int-returning entry point still works for the schedulers
        assertThat(sync.sync(item, "actor")).isZero();
    }

    @Test @DisplayName("pages staged before a transient failure are kept and counted")
    void partialProgressKept() {
        when(client.transactionsSync(ENV, "tok", "cur-0")).thenReturn(page("cur-a", true, "t1", "t2"));
        when(client.transactionsSync(ENV, "tok", "cur-a")).thenThrow(plaidError(400, "INSTITUTION_NOT_RESPONDING"));
        PlaidSyncService.SyncResult r = sync.syncWithResult(item, "actor");
        assertThat(r.retryLater()).isTrue();
        assertThat(r.staged()).isEqualTo(2);
        assertThat(item.getSyncCursor()).as("cursor only moves on a complete run").isEqualTo("cur-0");
        verify(stagingRepo, times(2)).save(any());
    }

    @Test @DisplayName("describe(): every transient code and the common faults have a sentence without the raw code")
    void describeLabels() {
        for (String c : PlaidSyncService.TRANSIENT_CODES)
            assertThat(PlaidSyncService.describe(c)).as(c).doesNotContain(c).endsWith(".");
        assertThat(PlaidSyncService.describe("ITEM_LOGIN_REQUIRED")).contains("sign in again");
        assertThat(PlaidSyncService.describe("SOMETHING_NEW")).contains("SOMETHING_NEW").contains("contact support");
        assertThat(PlaidSyncService.describe(null)).isNotBlank();
    }
}
