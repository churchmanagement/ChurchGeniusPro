package com.churchgeniuspro.subscription;

import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.plaid.entity.PlaidAccount;
import com.churchgeniuspro.plaid.repository.PlaidAccountRepository;
import com.churchgeniuspro.plaid.repository.PlaidItemRepository;
import com.churchgeniuspro.plaid.repository.PlaidTransactionStagingRepository;
import com.churchgeniuspro.plaid.service.*;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import com.churchgeniuspro.repository.SubscriptionUsageRepository;
import com.churchgeniuspro.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Plan limit on connected bank accounts: counted per PlaidAccount, enforced before
 * Plaid Link opens and again at exchange, all-or-nothing, never deleting anything.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Bank Sync — plan limit on connected accounts")
class BankAccountLimitTest {

    static final String CHURCH = "CHR-1", ENV = "sandbox";

    @Mock ServiceClientRepository clientRepo;
    @Mock SubscriptionPlanRepository planRepo;
    @Mock SubscriptionUsageRepository usageRepo;
    @Mock PlaidClient client;
    @Mock PlaidEnvironmentService plaidEnv;
    @Mock PlaidTokenCipher cipher;
    @Mock PlaidItemRepository itemRepo;
    @Mock PlaidAccountRepository accountRepo;
    @Mock PlaidTransactionStagingRepository stagingRepo;
    @Mock PlaidSyncService syncService;
    @Mock PlaidAuditService audit;

    SubscriptionService subscriptions;
    PlaidLinkService link;
    SubscriptionPlan standard;

    @BeforeEach
    void setUp() {
        standard = new SubscriptionPlan();
        standard.setPlanCode("STANDARD"); standard.setPlanName("Standard Plan"); standard.setActive(true);
        standard.setMaxBankAccounts(3);
        ServiceClient sc = new ServiceClient(); sc.setClientId(CHURCH); sc.setSubscriptionType("STANDARD");
        when(clientRepo.findByClientId(CHURCH)).thenReturn(Optional.of(sc));
        when(planRepo.findByPlanCodeIgnoreCase("STANDARD")).thenReturn(Optional.of(standard));
        subscriptions = new SubscriptionService(clientRepo, planRepo, usageRepo);

        link = new PlaidLinkService(client, plaidEnv, cipher, itemRepo, accountRepo, stagingRepo, syncService, audit);
        link.setSubscriptions(subscriptions);
        when(plaidEnv.envForClient(CHURCH)).thenReturn(ENV);
        when(client.exchangePublicToken(eq(ENV), anyString()))
                .thenReturn(Map.of("access_token", "raw-token", "item_id", "item-1"));
        when(cipher.encrypt("raw-token")).thenReturn("enc");
        when(itemRepo.findByItemIdAndClientId("item-1", CHURCH)).thenReturn(Optional.empty());
        when(itemRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static PlaidAccount account(boolean active) {
        PlaidAccount a = new PlaidAccount();
        a.setActive(active);
        return a;
    }
    private void connected(int n) {
        when(accountRepo.findByClientId(CHURCH)).thenReturn(java.util.Collections.nCopies(n, account(true)));
    }
    private void plaidReturnsAccounts(int n) {
        when(client.accountsGet(eq(ENV), eq("raw-token")))
                .thenReturn(Map.of("item", Map.of("institution_id", "ins_1"),
                                   "accounts", java.util.Collections.nCopies(n, Map.of("account_id", "a"))));
    }

    @Test @DisplayName("the message names the limit and the current count")
    void messages() {
        assertThat(subscriptions.checkBankAccountLimit(CHURCH, 2, 1)).isNull();
        assertThat(subscriptions.checkBankAccountLimit(CHURCH, 3, 1))
                .contains("up to 3 connected bank accounts").contains("3 connected accounts");
        assertThat(subscriptions.checkBankAccountLimit(CHURCH, 1, 4))
                .contains("would add 4").contains("2 remaining");
        standard.setMaxBankAccounts(0);
        subscriptions.clearCache();
        assertThat(subscriptions.checkBankAccountLimit(CHURCH, 0, 1)).contains("not included");
        standard.setMaxBankAccounts(null);
        subscriptions.clearCache();
        assertThat(subscriptions.checkBankAccountLimit(CHURCH, 100, 5)).as("unlimited").isNull();
    }

    @Test @DisplayName("at the limit, Plaid Link is refused before it opens")
    void linkTokenRefusedAtLimit() {
        connected(3);
        assertThatThrownBy(() -> link.createLinkToken(CHURCH, "u1", "actor"))
                .isInstanceOf(PlaidLinkService.AccountLimitExceeded.class)
                .hasMessageContaining("up to 3");
        verify(client, never()).createLinkToken(any(), any(), any());
    }

    @Test @DisplayName("a connection whose selected accounts exceed the limit is refused whole: nothing stored, item revoked")
    void exchangeRefusedAllOrNothing() {
        connected(1);
        plaidReturnsAccounts(3);     // 1 + 3 > 3
        assertThatThrownBy(() -> link.exchangePublicToken(CHURCH, 42, "pub", "actor"))
                .isInstanceOf(PlaidLinkService.AccountLimitExceeded.class)
                .hasMessageContaining("would add 3").hasMessageContaining("2 remaining");
        verify(itemRepo, never()).save(any());
        verify(syncService, never()).sync(any(), any());
        verify(client).itemRemove(ENV, "raw-token");
    }

    @Test @DisplayName("within the limit the connection proceeds as before")
    void exchangeWithinLimit() {
        connected(1);
        plaidReturnsAccounts(2);     // 1 + 2 = 3
        link.exchangePublicToken(CHURCH, 42, "pub", "actor");
        verify(itemRepo).save(any());
        verify(client, never()).itemRemove(any(), any());
    }

    @Test @DisplayName("downgraded over the limit: existing accounts stay, only new ones are refused")
    void downgradeKeepsAccounts() {
        connected(10);               // a former Pro church now on Standard (3)
        assertThat(subscriptions.checkBankAccountLimit(CHURCH, 10, 1)).contains("already have 10");
        verify(accountRepo, never()).deleteByPlaidItemId(any());
        verify(itemRepo, never()).delete(any());
    }

    @Test @DisplayName("re-linking an existing item adds nothing and is never counted")
    void relinkNotCounted() {
        connected(3);
        plaidReturnsAccounts(3);
        com.churchgeniuspro.plaid.entity.PlaidItem existing = new com.churchgeniuspro.plaid.entity.PlaidItem();
        existing.setId(7); existing.setClientId(CHURCH); existing.setItemId("item-1");
        when(itemRepo.findByItemIdAndClientId("item-1", CHURCH)).thenReturn(Optional.of(existing));
        link.exchangePublicToken(CHURCH, 42, "pub", "actor");
        verify(itemRepo).save(existing);
    }

    @Test @DisplayName("force-disconnected accounts (kept with active=false) do not count toward the limit")
    void forceDisconnectedNotCounted() {
        java.util.List<PlaidAccount> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(account(false));   // a Service Admin force-disconnect
        rows.add(account(true));
        when(accountRepo.findByClientId(CHURCH)).thenReturn(rows);
        assertThat(link.connectedAccountCount(CHURCH)).isEqualTo(1);
        plaidReturnsAccounts(2);                                  // 1 + 2 = 3 fits
        link.exchangePublicToken(CHURCH, 42, "pub", "actor");
        verify(itemRepo).save(any());
    }

    @Test @DisplayName("with a finite limit, a connection whose account count cannot be read is refused whole")
    void unknownAccountCountRefused() {
        connected(0);
        when(client.accountsGet(eq(ENV), eq("raw-token"))).thenThrow(new RuntimeException("Plaid timeout"));
        assertThatThrownBy(() -> link.exchangePublicToken(CHURCH, 42, "pub", "actor"))
                .isInstanceOf(PlaidLinkService.AccountLimitExceeded.class)
                .hasMessageContaining("could not confirm");
        verify(itemRepo, never()).save(any());
        verify(syncService, never()).sync(any(), any());
        verify(client).itemRemove(ENV, "raw-token");
    }

    @Test @DisplayName("unlimited plan: an unreadable account count does not block the connection")
    void unknownAccountCountUnlimited() {
        standard.setMaxBankAccounts(null);
        subscriptions.clearCache();
        connected(0);
        when(client.accountsGet(eq(ENV), eq("raw-token"))).thenThrow(new RuntimeException("Plaid timeout"));
        link.exchangePublicToken(CHURCH, 42, "pub", "actor");
        verify(itemRepo).save(any());
    }
}
