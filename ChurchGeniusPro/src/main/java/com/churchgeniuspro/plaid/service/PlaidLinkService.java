package com.churchgeniuspro.plaid.service;

import com.churchgeniuspro.plaid.entity.PlaidItem;
import com.churchgeniuspro.plaid.entity.PlaidAccount;
import com.churchgeniuspro.plaid.repository.PlaidAccountRepository;
import com.churchgeniuspro.plaid.repository.PlaidItemRepository;
import com.churchgeniuspro.plaid.repository.PlaidTransactionStagingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Handles the Plaid Link handshake: creating Link tokens and exchanging the
 * returned public token for a long-lived access token, which is stored encrypted.
 * After a successful link, an initial transaction sync populates the review queue.
 */
@Service
public class PlaidLinkService {

    private static final Logger log = LoggerFactory.getLogger(PlaidLinkService.class);

    private final PlaidClient client;
    private final PlaidTokenCipher cipher;
    private final PlaidItemRepository itemRepo;
    private final PlaidAccountRepository accountRepo;
    private final PlaidTransactionStagingRepository stagingRepo;
    private final PlaidSyncService syncService;
    private final PlaidAuditService audit;
    private final PlaidEnvironmentService plaidEnv;

    /**
     * Plan limits (Bank Sync account cap). Setter-injected so the constructor and the
     * tests built on it are unchanged; without it no cap is enforced.
     */
    private com.churchgeniuspro.service.SubscriptionService subscriptions;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSubscriptions(com.churchgeniuspro.service.SubscriptionService s) { this.subscriptions = s; }

    /**
     * Accounts currently connected for this church. A user-initiated delete purges the
     * rows, but a Service Admin force-disconnect keeps them with {@code active=false}
     * (ServiceAdminPlaidService) — those are not connected and must not count, or a
     * force-disconnected church could never reconnect within its limit.
     */
    public long connectedAccountCount(String clientId) {
        return accountRepo.findByClientId(clientId).stream().filter(PlaidAccount::isActive).count();
    }

    /** The plan's cap on connected accounts; null = unlimited. */
    public Integer accountLimit(String clientId) {
        return subscriptions == null ? null : subscriptions.bankAccountLimit(clientId);
    }

    /**
     * Thrown with a user-facing message when a connection would exceed the plan's
     * Bank Sync account limit. Nothing is persisted when this is raised.
     */
    public static class AccountLimitExceeded extends IllegalArgumentException {
        public AccountLimitExceeded(String message) { super(message); }
    }

    private void requireRoomFor(String clientId, int adding) {
        if (subscriptions == null) return;
        String deny = subscriptions.checkBankAccountLimit(clientId, connectedAccountCount(clientId), adding);
        if (deny != null) throw new AccountLimitExceeded(deny);
    }

    public PlaidLinkService(PlaidClient client,
                            PlaidEnvironmentService plaidEnv,
                            PlaidTokenCipher cipher,
                            PlaidItemRepository itemRepo,
                            PlaidAccountRepository accountRepo,
                            PlaidTransactionStagingRepository stagingRepo,
                            PlaidSyncService syncService,
                            PlaidAuditService audit) {
        this.client = client;
        this.plaidEnv = plaidEnv;
        this.cipher = cipher;
        this.itemRepo = itemRepo;
        this.accountRepo = accountRepo;
        this.stagingRepo = stagingRepo;
        this.syncService = syncService;
        this.audit = audit;
    }

    /**
     * Create a Plaid Link token for the given church user. Returns the link_token.
     *
     * <p>The environment comes from the tenant's subscription, so a Trial tenant is
     * handed a sandbox Link token and Link itself will only ever offer them Plaid's
     * test institutions. This is the front door of the whole flow: everything
     * downstream inherits the environment of the token issued here.
     */
    public String createLinkToken(String clientId, String clientUserId, String actor) {
        // Already at (or over) the plan's account limit: refuse before Plaid Link opens,
        // rather than letting the user pick a bank and fail at the end.
        requireRoomFor(clientId, 1);
        String env = plaidEnv.envForClient(clientId);
        Map<String, Object> resp = client.createLinkToken(env, clientUserId, "ChurchGeniusPro");
        Object token = resp.get("link_token");
        audit.record(clientId, actor, "LINK_CREATED", clientUserId,
                "Link token created (" + env + ")");
        return token != null ? token.toString() : null;
    }

    /**
     * Exchange the public token for an access token, persist the item + accounts
     * (via the initial sync), and return a summary. The raw access token is
     * encrypted before storage and never returned.
     */
    public Map<String, Object> exchangePublicToken(String clientId, Integer appUserId,
                                                   String publicToken, String actor) {
        // Resolved from the plan again rather than trusted from the caller: the
        // public token arrives from the browser, and the environment it is redeemed
        // in decides which Plaid the resulting access token can reach.
        String env = plaidEnv.envForClient(clientId);
        Map<String, Object> ex = client.exchangePublicToken(env, publicToken);
        String accessToken = str(ex.get("access_token"));
        String itemId = str(ex.get("item_id"));
        if (accessToken == null || itemId == null) {
            throw new IllegalStateException("Plaid did not return an access token.");
        }

        // Re-link support: update the token if this item already exists for the
        // tenant. Scoped by clientId (financial audit M7) — a Plaid item_id is
        // not guaranteed unique across tenants, and an unscoped lookup here would
        // let one church's Link flow silently overwrite another church's item
        // row, access token included, quietly pointing that church's own sync at
        // a different church's bank data.
        PlaidItem item = itemRepo.findByItemIdAndClientId(itemId, clientId).orElseGet(PlaidItem::new);
        boolean isNew = item.getId() == null;
        if (isNew) {
            item.setClientId(clientId);
            item.setItemId(itemId);
            item.setCreatedByUserId(appUserId);
        }
        item.setAccessTokenEnc(cipher.encrypt(accessToken));
        item.setPlaidEnv(env);          // the token is only valid in this environment
        item.setStatus("ACTIVE");
        item.setDeleteFlag(false);

        // Institution id (name resolution deferred to a later phase), and the
        // accounts this connection brings — needed for the plan limit below.
        int incomingAccounts = 1;
        boolean accountCountKnown = false;
        try {
            Map<String, Object> acctResp = client.accountsGet(env, accessToken);
            Object itemObj = acctResp.get("item");
            if (itemObj instanceof Map<?, ?> m) {
                item.setInstitutionId(str(m.get("institution_id")));
            }
            if (acctResp.get("accounts") instanceof java.util.List<?> accts && !accts.isEmpty()) {
                incomingAccounts = accts.size();
                accountCountKnown = true;
            }
        } catch (Exception e) {
            log.debug("accounts/get during link failed (non-fatal): {}", e.getMessage());
        }

        // Plan limit on connected bank accounts (each account selected in Plaid Link
        // counts). Checked for a NEW connection only — a re-link of an existing item
        // adds nothing. Over the limit: revoke the brand-new item at Plaid so no
        // orphaned access token survives, persist nothing, and tell the user how
        // many accounts their plan allows. All-or-nothing, never a partial connection.
        if (isNew) {
            try {
                // With a finite limit, an unknown account count cannot be checked — and
                // the initial sync below would store however many accounts there are.
                // Refuse rather than risk a partial over-limit connection.
                if (!accountCountKnown && accountLimit(clientId) != null) {
                    throw new AccountLimitExceeded("We could not confirm how many bank accounts this "
                            + "connection includes, so it was not saved. Please try connecting again.");
                }
                requireRoomFor(clientId, incomingAccounts);
            } catch (AccountLimitExceeded limit) {
                try { client.itemRemove(env, accessToken); }
                catch (Exception e) { log.warn("item/remove after limit refusal failed: {}", e.getMessage()); }
                audit.record(clientId, actor, "ITEM_REFUSED_LIMIT", itemId,
                        "Bank connection refused — " + incomingAccounts + " account(s) would exceed the plan limit");
                throw limit;
            }
        }

        itemRepo.save(item);
        audit.record(clientId, actor, "ITEM_LINKED", itemId,
                (isNew ? "Bank connected" : "Bank re-linked") + " (" + env + ")");

        // Initial pull → accounts + transactions into the review queue.
        int staged = syncService.sync(item, actor);

        return Map.of(
                "itemId", itemId,
                "status", item.getStatus(),
                "stagedTransactions", staged);
    }

    /**
     * OTP-confirmed deletion of a bank connection. Revokes the item at Plaid,
     * purges every staged review-queue row and account row for the connection,
     * and clears all sync data (token + cursor). Promoted ledger entries
     * (income/expense) are untouched. Reconnecting later creates a brand-new
     * Plaid item via Link.
     */
    @Transactional
    public Map<String, Object> deleteItem(String clientId, Integer itemDbId, String actor) {
        PlaidItem item = itemRepo.findByIdAndClientId(itemDbId, clientId)
                .orElseThrow(() -> new IllegalArgumentException("Bank connection not found."));
        if (item.isDeleteFlag()) {
            throw new IllegalArgumentException("This bank is already disconnected.");
        }

        // 1) Revoke the connection at Plaid (best effort — local cleanup proceeds
        //    even if Plaid is unreachable or the token is unusable; the item is
        //    unusable without its token anyway).
        if (item.getAccessTokenEnc() != null && !item.getAccessTokenEnc().isBlank()) {
            try {
                String accessToken = cipher.decrypt(item.getAccessTokenEnc());
                client.itemRemove(plaidEnv.envForItem(item), accessToken);
            } catch (Exception e) {
                log.warn("Plaid /item/remove failed for item {} (continuing local cleanup): {}",
                        item.getItemId(), e.getMessage());
            }
        }

        // 2) Purge the review queue and account rows for this connection.
        long removedTxns = stagingRepo.deleteByPlaidItemId(item.getId());
        long removedAccts = accountRepo.deleteByPlaidItemId(item.getId());

        // 3) Clear sync data and soft-delete the item (kept as an audit stub only:
        //    no token, no cursor — a future reconnect is a brand-new item).
        //    access_token_enc is NOT NULL in the schema, so the revoked token is
        //    blanked to an empty string rather than null (empty = "no token").
        String institution = item.getInstitutionName() != null ? item.getInstitutionName() : item.getInstitutionId();
        item.setAccessTokenEnc("");
        item.setSyncCursor(null);
        item.setStatus("DISCONNECTED");
        item.setErrorCode(null);
        item.setDeleteFlag(true);
        itemRepo.save(item);

        audit.record(clientId, actor, "ITEM_DELETED", item.getItemId(),
                "Bank deleted after OTP confirmation (" + institution + "): removed "
                        + removedTxns + " staged transaction(s), " + removedAccts + " account(s).");

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("institution", institution);
        out.put("removedTransactions", removedTxns);
        out.put("removedAccounts", removedAccts);
        return out;
    }

    private String str(Object o) {
        return o == null ? null : o.toString();
    }
}
