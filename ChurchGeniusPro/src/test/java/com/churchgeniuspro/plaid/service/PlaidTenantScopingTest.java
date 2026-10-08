package com.churchgeniuspro.plaid.service;

import com.churchgeniuspro.plaid.entity.PlaidAccount;
import com.churchgeniuspro.plaid.entity.PlaidItem;
import com.churchgeniuspro.plaid.entity.PlaidTransactionStaging;
import com.churchgeniuspro.plaid.repository.PlaidAccountRepository;
import com.churchgeniuspro.plaid.repository.PlaidItemRepository;
import com.churchgeniuspro.plaid.repository.PlaidTransactionStagingRepository;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Financial audit M7: Plaid's own identifiers ({@code item_id}, {@code
 * account_id}, {@code transaction_id}) are not guaranteed unique across
 * tenants — most concretely, Trial/Demo signups routinely connect the same
 * shared Plaid sandbox test institution, whose ids are deterministic per
 * institution rather than randomized per connection. Every lookup that used
 * to decide what to write by one of these ids alone, with no tenant filter,
 * could let one church's sync (or Link flow) silently take over another
 * church's row: {@link PlaidSyncService} for staged transactions and
 * accounts, and {@link PlaidLinkService} for the item itself — the most
 * severe case, since the item row carries the encrypted access token, and an
 * adopted row would have pointed one church's own sync at a different
 * church's bank data.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Plaid — external-id lookups are tenant-scoped (M7)")
class PlaidTenantScopingTest {

    private static final String OURS = "CHR-ours";
    private static final String ENV  = "sandbox";

    @Mock PlaidClient                          client;
    @Mock PlaidEnvironmentService               plaidEnv;
    @Mock PlaidTokenCipher                      cipher;
    @Mock PlaidItemRepository                   itemRepo;
    @Mock PlaidAccountRepository                accountRepo;
    @Mock PlaidTransactionStagingRepository     stagingRepo;
    @Mock PlaidAuditService                     audit;

    private static PlaidItem item(int id, String clientId) {
        PlaidItem it = new PlaidItem();
        it.setId(id);
        it.setClientId(clientId);
        it.setItemId("plaid-item-" + id);
        it.setAccessTokenEnc("enc-token");
        it.setSyncCursor(null);
        it.setStatus("ACTIVE");
        it.setDeleteFlag(false);
        return it;
    }

    private static Map<String, Object> txnMap(String txnId, String acctId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transaction_id", txnId);
        m.put("account_id", acctId);
        m.put("amount", "50.00");
        m.put("date", "2026-01-10");
        m.put("name", "Coffee Shop");
        m.put("pending", false);
        return m;
    }

    private static Map<String, Object> acctMap(String acctId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("account_id", acctId);
        m.put("name", "Checking");
        m.put("mask", "1234");
        m.put("type", "depository");
        return m;
    }

    private static Map<String, Object> syncResponse(List<Map<String, Object>> accounts,
                                                     List<Map<String, Object>> added,
                                                     List<Map<String, Object>> removed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accounts", accounts);
        m.put("added", added);
        m.put("modified", List.of());
        m.put("removed", removed);
        m.put("next_cursor", "cursor-next");
        m.put("has_more", false);
        return m;
    }

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
        @DisplayName("a new transaction is looked up (and staged) scoped to the syncing tenant, not globally")
        void transactionLookupIsTenantScoped() {
            PlaidItem it = item(1, OURS);
            when(client.transactionsSync(eq(ENV), eq("plain-token"), any()))
                    .thenReturn(syncResponse(List.of(), List.of(txnMap("txn-1", "acct-1")), List.of()));
            when(stagingRepo.findByPlaidTransactionIdAndClientId("txn-1", OURS)).thenReturn(Optional.empty());

            sync.sync(it, "actor");

            verify(stagingRepo).findByPlaidTransactionIdAndClientId(eq("txn-1"), eq(OURS));
            ArgumentCaptor<PlaidTransactionStaging> saved = ArgumentCaptor.forClass(PlaidTransactionStaging.class);
            verify(stagingRepo).save(saved.capture());
            assertThat(saved.getValue().getClientId()).isEqualTo(OURS);
            assertThat(saved.getValue().getPlaidTransactionId()).isEqualTo("txn-1");
        }

        @Test
        @DisplayName("the account resolved onto a staged transaction is looked up scoped to the syncing tenant")
        void transactionAccountResolutionIsTenantScoped() {
            PlaidItem it = item(1, OURS);
            when(client.transactionsSync(eq(ENV), eq("plain-token"), any()))
                    .thenReturn(syncResponse(List.of(), List.of(txnMap("txn-1", "acct-1")), List.of()));
            when(stagingRepo.findByPlaidTransactionIdAndClientId("txn-1", OURS)).thenReturn(Optional.empty());
            PlaidAccount ourAccount = new PlaidAccount();
            ourAccount.setId(5);
            ourAccount.setClientId(OURS);
            when(accountRepo.findByAccountIdAndClientId("acct-1", OURS)).thenReturn(Optional.of(ourAccount));

            sync.sync(it, "actor");

            verify(accountRepo).findByAccountIdAndClientId(eq("acct-1"), eq(OURS));
            ArgumentCaptor<PlaidTransactionStaging> saved = ArgumentCaptor.forClass(PlaidTransactionStaging.class);
            verify(stagingRepo).save(saved.capture());
            assertThat(saved.getValue().getPlaidAccountId()).isEqualTo(5);
        }

        @Test
        @DisplayName("an account not found for this tenant is created fresh for it, not adopted from another tenant")
        void accountUpsertIsTenantScoped() {
            PlaidItem it = item(1, OURS);
            when(client.transactionsSync(eq(ENV), eq("plain-token"), any()))
                    .thenReturn(syncResponse(List.of(acctMap("acct-1")), List.of(), List.of()));
            when(accountRepo.findByAccountIdAndClientId("acct-1", OURS)).thenReturn(Optional.empty());

            sync.sync(it, "actor");

            verify(accountRepo).findByAccountIdAndClientId(eq("acct-1"), eq(OURS));
            ArgumentCaptor<PlaidAccount> saved = ArgumentCaptor.forClass(PlaidAccount.class);
            verify(accountRepo).save(saved.capture());
            assertThat(saved.getValue().getClientId()).isEqualTo(OURS);
            assertThat(saved.getValue().getPlaidItemId()).isEqualTo(1);
            assertThat(saved.getValue().getAccountId()).isEqualTo("acct-1");
        }

        @Test
        @DisplayName("a removed transaction is looked up and flagged scoped to the syncing tenant")
        void removedLookupIsTenantScoped() {
            PlaidItem it = item(1, OURS);
            when(client.transactionsSync(eq(ENV), eq("plain-token"), any()))
                    .thenReturn(syncResponse(List.of(), List.of(), List.of(Map.of("transaction_id", "txn-gone"))));
            PlaidTransactionStaging existing = new PlaidTransactionStaging();
            existing.setId(9);
            existing.setClientId(OURS);
            existing.setPlaidTransactionId("txn-gone");
            existing.setStatus("PENDING");
            when(stagingRepo.findByPlaidTransactionIdAndClientId("txn-gone", OURS)).thenReturn(Optional.of(existing));

            sync.sync(it, "actor");

            verify(stagingRepo).findByPlaidTransactionIdAndClientId(eq("txn-gone"), eq(OURS));
            assertThat(existing.isRemoved()).isTrue();
        }
    }

    @Nested
    @DisplayName("PlaidLinkService")
    class Link {

        @Mock PlaidSyncService syncService;
        private PlaidLinkService linkService;

        @BeforeEach
        void setUp() {
            linkService = new PlaidLinkService(client, plaidEnv, cipher, itemRepo, accountRepo, stagingRepo,
                    syncService, audit);
            when(plaidEnv.envForClient(OURS)).thenReturn(ENV);
            when(client.exchangePublicToken(eq(ENV), anyString()))
                    .thenReturn(Map.of("access_token", "raw-token", "item_id", "shared-item-id"));
            when(cipher.encrypt("raw-token")).thenReturn("enc-raw-token");
        }

        @Test
        @DisplayName("an item_id already used by ANOTHER tenant is not adopted — a fresh item is created for this one")
        void collidingItemIdIsNotAdoptedAcrossTenants() {
            // Simulate the real risk this fix closes: Plaid returns an item_id that
            // (say, via a shared sandbox institution) already belongs to some other
            // church. Scoped by OUR clientId, we correctly find nothing of ours.
            when(itemRepo.findByItemIdAndClientId("shared-item-id", OURS)).thenReturn(Optional.empty());

            linkService.exchangePublicToken(OURS, 42, "public-tok", "actor");

            verify(itemRepo).findByItemIdAndClientId(eq("shared-item-id"), eq(OURS));
            ArgumentCaptor<PlaidItem> saved = ArgumentCaptor.forClass(PlaidItem.class);
            verify(itemRepo).save(saved.capture());
            assertThat(saved.getValue().getClientId()).isEqualTo(OURS);
            assertThat(saved.getValue().getAccessTokenEnc()).isEqualTo("enc-raw-token");
        }

        @Test
        @DisplayName("re-linking this tenant's own existing item updates that same row, not a new one")
        void ownExistingItemIsReusedOnRelink() {
            PlaidItem existing = item(7, OURS);
            existing.setItemId("shared-item-id");
            existing.setAccessTokenEnc("stale-enc-token");
            when(itemRepo.findByItemIdAndClientId("shared-item-id", OURS)).thenReturn(Optional.of(existing));

            linkService.exchangePublicToken(OURS, 42, "public-tok", "actor");

            ArgumentCaptor<PlaidItem> saved = ArgumentCaptor.forClass(PlaidItem.class);
            verify(itemRepo).save(saved.capture());
            assertThat(saved.getValue().getId()).isEqualTo(7);
            assertThat(saved.getValue().getClientId()).isEqualTo(OURS);
            assertThat(saved.getValue().getAccessTokenEnc()).isEqualTo("enc-raw-token");
        }

        @Test
        @DisplayName("never falls back to the global, unscoped item lookup")
        void neverCallsUnscopedFinder() {
            when(itemRepo.findByItemIdAndClientId("shared-item-id", OURS)).thenReturn(Optional.empty());

            linkService.exchangePublicToken(OURS, 42, "public-tok", "actor");

            verify(itemRepo, never()).findByItemId(anyString());
        }
    }
}
