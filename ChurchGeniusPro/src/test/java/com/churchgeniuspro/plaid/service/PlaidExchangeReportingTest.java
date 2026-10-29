package com.churchgeniuspro.plaid.service;

import com.churchgeniuspro.plaid.controller.PlaidLinkController;
import com.churchgeniuspro.plaid.entity.PlaidItem;
import com.churchgeniuspro.plaid.repository.PlaidAccountRepository;
import com.churchgeniuspro.plaid.repository.PlaidItemRepository;
import com.churchgeniuspro.plaid.repository.PlaidTransactionStagingRepository;
import com.churchgeniuspro.plaid.util.PlaidGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Error-handling audit 2026-10-28, M8: once the item is saved, the exchange is a
 * success — a first-sync failure is reported on it, never turned into "could not
 * connect" (which made people link the same bank twice). "Sync now" answers with
 * the outcome: 502 for a transient provider condition, 409 for a connection fault.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Plaid exchange + sync-now report the real outcome")
class PlaidExchangeReportingTest {

    static final String CHURCH = "CHR-1", ENV = "sandbox";

    @Mock PlaidClient client;
    @Mock PlaidEnvironmentService plaidEnv;
    @Mock PlaidTokenCipher cipher;
    @Mock PlaidItemRepository itemRepo;
    @Mock PlaidAccountRepository accountRepo;
    @Mock PlaidTransactionStagingRepository stagingRepo;
    @Mock PlaidSyncService syncService;
    @Mock PlaidAuditService audit;
    @Mock PlaidGuard guard;

    PlaidLinkService link;

    @BeforeEach
    void setUp() {
        link = new PlaidLinkService(client, plaidEnv, cipher, itemRepo, accountRepo, stagingRepo, syncService, audit);
        when(plaidEnv.envForClient(CHURCH)).thenReturn(ENV);
        when(client.exchangePublicToken(eq(ENV), anyString())).thenReturn(Map.of("access_token", "raw", "item_id", "item-1"));
        when(cipher.encrypt("raw")).thenReturn("enc");
        when(itemRepo.findByItemIdAndClientId("item-1", CHURCH)).thenReturn(Optional.empty());
        when(itemRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Nested @DisplayName("PlaidLinkService.exchangePublicToken")
    class Service {

        @Test @DisplayName("first sync OK → firstSync ok, count, no message")
        void firstSyncOk() {
            when(syncService.syncWithResult(any(), eq("actor"))).thenReturn(PlaidSyncService.SyncResult.ok(5));
            Map<String, Object> r = link.exchangePublicToken(CHURCH, 42, "pub", "actor");
            assertThat(r).containsEntry("connected", true).containsEntry("firstSync", "ok")
                         .containsEntry("stagedTransactions", 5).doesNotContainKey("message");
        }

        @Test @DisplayName("first sync deferred (transient) → item saved, firstSync failed, honest message, retried note")
        void firstSyncTransient() {
            when(syncService.syncWithResult(any(), eq("actor"))).thenReturn(
                    new PlaidSyncService.SyncResult(PlaidSyncService.Outcome.RETRY_LATER, 0, "RATE_LIMIT_EXCEEDED",
                            PlaidSyncService.describe("RATE_LIMIT_EXCEEDED")));
            Map<String, Object> r = link.exchangePublicToken(CHURCH, 42, "pub", "actor");
            verify(itemRepo).save(any(PlaidItem.class));
            assertThat(r).containsEntry("connected", true).containsEntry("firstSync", "failed")
                         .containsEntry("errorCode", "RATE_LIMIT_EXCEEDED").containsEntry("syncOutcome", "RETRY_LATER");
            assertThat((String) r.get("message")).startsWith("Bank connected.").contains("busy").contains("retried automatically");
        }

        @Test @DisplayName("first sync throws unexpectedly → still connected, reported as failed, nothing propagates")
        void firstSyncThrows() {
            when(syncService.syncWithResult(any(), eq("actor"))).thenThrow(new IllegalStateException("boom jdbc"));
            Map<String, Object> r = link.exchangePublicToken(CHURCH, 42, "pub", "actor");
            assertThat(r).containsEntry("connected", true).containsEntry("firstSync", "failed").containsEntry("errorCode", "SYNC_FAILED");
            assertThat(r.get("message").toString()).doesNotContain("jdbc").doesNotContain("boom");
        }

        @Test @DisplayName("a failure BEFORE the item is saved still propagates (nothing exists to report on)")
        void exchangeItselfFails() {
            when(client.exchangePublicToken(eq(ENV), anyString())).thenThrow(new RuntimeException("Plaid /item/public_token/exchange HTTP 400"));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> link.exchangePublicToken(CHURCH, 42, "pub", "actor"))
                    .isInstanceOf(RuntimeException.class);
            verify(itemRepo, org.mockito.Mockito.never()).save(any());
        }
    }

    @Nested @DisplayName("PlaidLinkController")
    class Controller {

        MockMvc mvc;
        MockHttpSession session;

        @BeforeEach
        void setUp() {
            PlaidLinkController c = new PlaidLinkController(guard, link, syncService, itemRepo, accountRepo, plaidEnv);
            mvc = MockMvcBuilders.standaloneSetup(c).build();
            session = new MockHttpSession();
            session.setAttribute("appClientId", CHURCH);
            session.setAttribute("username", "actor");
            session.setAttribute("appUserId", 42);
            when(guard.requireBankAccess(any())).thenReturn(null);
            when(guard.isSyncEnabled(CHURCH)).thenReturn(true);
        }

        @Test @DisplayName("exchange with a failed first sync is 200 status:success + firstSync failed + message")
        void exchangeReportsFirstSync() throws Exception {
            when(syncService.syncWithResult(any(), eq("actor"))).thenReturn(
                    new PlaidSyncService.SyncResult(PlaidSyncService.Outcome.RETRY_LATER, 0, "INSTITUTION_DOWN",
                            PlaidSyncService.describe("INSTITUTION_DOWN")));
            mvc.perform(post("/api/plaid/exchange").session(session).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"publicToken\":\"pub\"}"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("success"))
               .andExpect(jsonPath("$.connected").value(true))
               .andExpect(jsonPath("$.firstSync").value("failed"))
               .andExpect(jsonPath("$.errorCode").value("INSTITUTION_DOWN"))
               .andExpect(jsonPath("$.message").value(containsString("Bank connected.")))
               .andExpect(content().string(not(containsString("Could not complete"))));
        }

        @Test @DisplayName("exchange failing before the item exists stays a 400 'Could not complete'")
        void exchangeBeforeSaveFails() throws Exception {
            when(client.exchangePublicToken(eq(ENV), anyString())).thenThrow(new RuntimeException("Plaid HTTP 400"));
            mvc.perform(post("/api/plaid/exchange").session(session).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"publicToken\":\"pub\"}"))
               .andExpect(status().isBadRequest())
               .andExpect(jsonPath("$.message").value(containsString("Could not complete bank connection")));
        }

        @Test @DisplayName("sync now: OK → 200; transient → 502 retryable; login required → 409; keys compatible")
        void syncNowOutcomes() throws Exception {
            when(syncService.syncByItemIdWithResult(CHURCH, 9, "actor")).thenReturn(PlaidSyncService.SyncResult.ok(3));
            mvc.perform(post("/api/plaid/items/9/sync").session(session))
               .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("success"))
               .andExpect(jsonPath("$.stagedTransactions").value(3)).andExpect(jsonPath("$.outcome").value("OK"));

            when(syncService.syncByItemIdWithResult(CHURCH, 9, "actor")).thenReturn(
                    new PlaidSyncService.SyncResult(PlaidSyncService.Outcome.RETRY_LATER, 1, "RATE_LIMIT_EXCEEDED", PlaidSyncService.describe("RATE_LIMIT_EXCEEDED")));
            mvc.perform(post("/api/plaid/items/9/sync").session(session))
               .andExpect(status().isBadGateway()).andExpect(jsonPath("$.status").value("error"))
               .andExpect(jsonPath("$.retryable").value(true)).andExpect(jsonPath("$.stagedTransactions").value(1))
               .andExpect(jsonPath("$.message").value(containsString("busy")))
               .andExpect(content().string(not(containsString("Plaid /"))));

            when(syncService.syncByItemIdWithResult(CHURCH, 9, "actor")).thenReturn(
                    new PlaidSyncService.SyncResult(PlaidSyncService.Outcome.LOGIN_REQUIRED, 0, "ITEM_LOGIN_REQUIRED", PlaidSyncService.describe("ITEM_LOGIN_REQUIRED")));
            mvc.perform(post("/api/plaid/items/9/sync").session(session))
               .andExpect(status().isConflict()).andExpect(jsonPath("$.retryable").value(false))
               .andExpect(jsonPath("$.errorCode").value("ITEM_LOGIN_REQUIRED"))
               .andExpect(jsonPath("$.message").value(containsString("sign in again")));
        }

        @Test @DisplayName("sync now on another church's item → 400 not found (tenant scoped)")
        void syncNowNotFound() throws Exception {
            when(syncService.syncByItemIdWithResult(CHURCH, 9, "actor")).thenThrow(new IllegalArgumentException("Bank connection not found."));
            mvc.perform(post("/api/plaid/items/9/sync").session(session))
               .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Bank connection not found."));
        }
    }
}
