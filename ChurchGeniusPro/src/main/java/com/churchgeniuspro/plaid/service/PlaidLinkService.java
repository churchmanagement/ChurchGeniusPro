package com.churchgeniuspro.plaid.service;

import com.churchgeniuspro.plaid.entity.PlaidItem;
import com.churchgeniuspro.plaid.repository.PlaidItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
    private final PlaidSyncService syncService;
    private final PlaidAuditService audit;

    public PlaidLinkService(PlaidClient client,
                            PlaidTokenCipher cipher,
                            PlaidItemRepository itemRepo,
                            PlaidSyncService syncService,
                            PlaidAuditService audit) {
        this.client = client;
        this.cipher = cipher;
        this.itemRepo = itemRepo;
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

    private String str(Object o) {
        return o == null ? null : o.toString();
    }
}
