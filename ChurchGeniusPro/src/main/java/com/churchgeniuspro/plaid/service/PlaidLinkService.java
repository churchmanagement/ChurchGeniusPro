package com.churchgeniuspro.plaid.service;

import com.churchgeniuspro.plaid.entity.PlaidItem;
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

    public PlaidLinkService(PlaidClient client,
                            PlaidTokenCipher cipher,
                            PlaidItemRepository itemRepo,
                            PlaidAccountRepository accountRepo,
                            PlaidTransactionStagingRepository stagingRepo,
                            PlaidSyncService syncService,
                            PlaidAuditService audit) {
        this.client = client;
        this.cipher = cipher;
        this.itemRepo = itemRepo;
        this.accountRepo = accountRepo;
        this.stagingRepo = stagingRepo;
        this.syncService = syncService;
        this.audit = audit;
    }

    /** Create a Plaid Link token for the given church user. Returns the link_token. */
    public String createLinkToken(String clientId, String clientUserId, String actor) {
        Map<String, Object> resp = client.createLinkToken(clientUserId, "ChurchGeniusPro");
        Object token = resp.get("link_token");
        audit.record(clientId, actor, "LINK_CREATED", clientUserId, "Link token created");
        return token != null ? token.toString() : null;
    }

    /**
     * Exchange the public token for an access token, persist the item + accounts
     * (via the initial sync), and return a summary. The raw access token is
     * encrypted before storage and never returned.
     */
    public Map<String, Object> exchangePublicToken(String clientId, Integer appUserId,
                                                   String publicToken, String actor) {
        Map<String, Object> ex = client.exchangePublicToken(publicToken);
        String accessToken = str(ex.get("access_token"));
        String itemId = str(ex.get("item_id"));
        if (accessToken == null || itemId == null) {
            throw new IllegalStateException("Plaid did not return an access token.");
        }

        // Re-link support: update the token if this item already exists for the tenant.
        PlaidItem item = itemRepo.findByItemId(itemId).orElseGet(PlaidItem::new);
        boolean isNew = item.getId() == null;
        if (isNew) {
            item.setClientId(clientId);
            item.setItemId(itemId);
            item.setCreatedByUserId(appUserId);
        }
        item.setAccessTokenEnc(cipher.encrypt(accessToken));
        item.setStatus("ACTIVE");
        item.setDeleteFlag(false);

        // Institution id (name resolution deferred to a later phase).
        try {
            Map<String, Object> acctResp = client.accountsGet(accessToken);
            Object itemObj = acctResp.get("item");
            if (itemObj instanceof Map<?, ?> m) {
                item.setInstitutionId(str(m.get("institution_id")));
            }
        } catch (Exception e) {
            log.debug("accounts/get during link failed (non-fatal): {}", e.getMessage());
        }

        itemRepo.save(item);
        audit.record(clientId, actor, "ITEM_LINKED", itemId, isNew ? "Bank connected" : "Bank re-linked");

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
                client.itemRemove(accessToken);
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
