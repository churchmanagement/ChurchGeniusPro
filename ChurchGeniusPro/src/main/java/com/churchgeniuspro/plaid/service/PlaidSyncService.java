package com.churchgeniuspro.plaid.service;

import com.churchgeniuspro.plaid.entity.PlaidAccount;
import com.churchgeniuspro.plaid.entity.PlaidItem;
import com.churchgeniuspro.plaid.entity.PlaidTransactionStaging;
import com.churchgeniuspro.plaid.repository.PlaidAccountRepository;
import com.churchgeniuspro.plaid.repository.PlaidItemRepository;
import com.churchgeniuspro.plaid.repository.PlaidTransactionStagingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Pulls transactions from Plaid using the cursor-based {@code /transactions/sync}
 * endpoint and populates the review queue ({@code plaid_transaction_staging}).
 *
 * <p>Idempotent: rows are keyed by Plaid {@code transaction_id}. Already-reviewed
 * rows (APPROVED/REJECTED) are never silently overwritten by later modifications.
 * Plaid data never reaches the ledger here — only the staging queue.
 */
@Service
public class PlaidSyncService {

    private static final Logger log = LoggerFactory.getLogger(PlaidSyncService.class);

    private final PlaidClient client;
    private final PlaidTokenCipher cipher;
    private final PlaidItemRepository itemRepo;
    private final PlaidAccountRepository accountRepo;
    private final PlaidTransactionStagingRepository stagingRepo;
    private final PlaidAuditService audit;

    public PlaidSyncService(PlaidClient client,
                            PlaidTokenCipher cipher,
                            PlaidItemRepository itemRepo,
                            PlaidAccountRepository accountRepo,
                            PlaidTransactionStagingRepository stagingRepo,
                            PlaidAuditService audit) {
        this.client = client;
        this.cipher = cipher;
        this.itemRepo = itemRepo;
        this.accountRepo = accountRepo;
        this.stagingRepo = stagingRepo;
        this.audit = audit;
    }

    /**
     * Sync a single item to the latest cursor. Returns the number of added +
     * modified transactions staged in this run.
     */
    public int sync(PlaidItem item, String actor) {
        if (item == null || item.isDeleteFlag()) return 0;
        String token = cipher.decrypt(item.getAccessTokenEnc());
        String cursor = item.getSyncCursor();
        int changed = 0;
        try {
            boolean hasMore = true;
            while (hasMore) {
                Map<String, Object> resp = client.transactionsSync(token, cursor);

                upsertAccounts(item, asMapList(resp.get("accounts")));

                for (Map<String, Object> txn : asMapList(resp.get("added"))) {
                    if (upsertTransaction(item, txn, false)) changed++;
                }
                for (Map<String, Object> txn : asMapList(resp.get("modified"))) {
                    if (upsertTransaction(item, txn, false)) changed++;
                }
                for (Map<String, Object> removed : asMapList(resp.get("removed"))) {
                    markRemoved(asString(removed.get("transaction_id")));
                }

                cursor = asString(resp.get("next_cursor"));
                hasMore = Boolean.TRUE.equals(resp.get("has_more"));
            }
            item.setSyncCursor(cursor);
            item.setLastSyncedDate(new Date());
            item.setStatus("ACTIVE");
            item.setErrorCode(null);
            itemRepo.save(item);
            audit.record(item.getClientId(), actor, "SYNC_RUN", item.getItemId(),
                    "Synced " + changed + " transaction(s)");
        } catch (RestClientResponseException e) {
            String errCode = extractErrorCode(e.getResponseBodyAsString());
            item.setStatus("ITEM_LOGIN_REQUIRED".equals(errCode) ? "LOGIN_REQUIRED" : "ERROR");
            item.setErrorCode(errCode);
            itemRepo.save(item);
            audit.record(item.getClientId(), actor, "SYNC_RUN", item.getItemId(),
                    "Sync failed: " + errCode);
            log.warn("Plaid sync failed for item {}: {}", item.getItemId(), errCode);
        }
        return changed;
    }

    /** Manual "sync now" for one item, tenant-scoped. */
    public int syncByItemId(String clientId, Integer itemId, String actor) {
        PlaidItem item = itemRepo.findByIdAndClientId(itemId, clientId).orElse(null);
        if (item == null) throw new IllegalArgumentException("Bank connection not found.");
        return sync(item, actor);
    }

    // ── transaction upsert ──────────────────────────────────────────────────

    /** Returns true when a row was created or updated (i.e. counted as changed). */
    private boolean upsertTransaction(PlaidItem item, Map<String, Object> txn, boolean removed) {
        String txnId = asString(txn.get("transaction_id"));
        if (txnId == null) return false;

        PlaidTransactionStaging row = stagingRepo.findByPlaidTransactionId(txnId).orElse(null);
        // Never overwrite a row a human has already actioned.
        if (row != null && !"PENDING".equals(row.getStatus())) {
            return false;
        }
        boolean isNew = (row == null);
        if (isNew) {
            row = new PlaidTransactionStaging();
            row.setClientId(item.getClientId());
            row.setPlaidItemId(item.getId());
            row.setPlaidTransactionId(txnId);
            row.setStatus("PENDING");
        }

        // Plaid convention: positive amount = money out (expense), negative = money in (income).
        BigDecimal signed = asBigDecimal(txn.get("amount"));
        BigDecimal abs = signed == null ? BigDecimal.ZERO : signed.abs();
        row.setAmount(abs);
        row.setDirection(signed != null && signed.signum() > 0 ? "EXPENSE" : "INCOME");

        String acctId = asString(txn.get("account_id"));
        if (acctId != null) {
            PlaidAccount acct = accountRepo.findByAccountId(acctId).orElse(null);
            if (acct != null) row.setPlaidAccountId(acct.getId());
        }
        row.setTxnDate(parseDate(asString(txn.getOrDefault("date", txn.get("authorized_date")))));
        row.setName(asString(txn.get("name")));
        row.setMerchantName(asString(txn.get("merchant_name")));
        if (row.getDescription() == null) {
            row.setDescription(asString(txn.get("name")));
        }
        row.setPlaidCategory(extractCategory(txn));
        Object pending = txn.get("pending");
        row.setPending(Boolean.TRUE.equals(pending));
        row.setUpdatedDate(new Date());
        stagingRepo.save(row);
        return true;
    }

    private void markRemoved(String txnId) {
        if (txnId == null) return;
        stagingRepo.findByPlaidTransactionId(txnId).ifPresent(row -> {
            row.setRemoved(true);
            row.setUpdatedDate(new Date());
            stagingRepo.save(row);
        });
    }

    // ── account upsert ──────────────────────────────────────────────────────

    private void upsertAccounts(PlaidItem item, List<Map<String, Object>> accounts) {
        for (Map<String, Object> a : accounts) {
            String acctId = asString(a.get("account_id"));
            if (acctId == null) continue;
            PlaidAccount acc = accountRepo.findByAccountId(acctId).orElseGet(PlaidAccount::new);
            boolean isNew = acc.getId() == null;
            if (isNew) {
                acc.setClientId(item.getClientId());
                acc.setPlaidItemId(item.getId());
                acc.setAccountId(acctId);
            }
            acc.setName(asString(a.get("name")));
            acc.setOfficialName(asString(a.get("official_name")));
            acc.setMask(asString(a.get("mask")));
            acc.setType(asString(a.get("type")));
            acc.setSubtype(asString(a.get("subtype")));
            Object balances = a.get("balances");
            if (balances instanceof Map<?, ?> b) {
                acc.setCurrentBalance(asBigDecimal(b.get("current")));
                acc.setAvailableBalance(asBigDecimal(b.get("available")));
            }
            acc.setUpdatedDate(new Date());
            accountRepo.save(acc);
        }
    }

    // ── parsing helpers ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMapList(Object o) {
        if (o instanceof List<?> l) return (List<Map<String, Object>>) l;
        return List.of();
    }

    private String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private BigDecimal asBigDecimal(Object o) {
        if (o == null) return null;
        try { return new BigDecimal(o.toString()); } catch (NumberFormatException e) { return null; }
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s); }
        catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private String extractCategory(Map<String, Object> txn) {
        Object pfc = txn.get("personal_finance_category");
        if (pfc instanceof Map<?, ?> m && m.get("primary") != null) {
            return m.get("primary").toString();
        }
        Object cat = txn.get("category");
        if (cat instanceof List<?> l && !l.isEmpty()) {
            return l.get(0).toString();
        }
        return null;
    }

    private String extractErrorCode(String body) {
        if (body == null) return "ERROR";
        int i = body.indexOf("\"error_code\"");
        if (i < 0) return "ERROR";
        int colon = body.indexOf(':', i);
        int q1 = body.indexOf('"', colon + 1);
        int q2 = body.indexOf('"', q1 + 1);
        if (q1 >= 0 && q2 > q1) return body.substring(q1 + 1, q2);
        return "ERROR";
    }
}
