package com.churchgeniuspro.plaid.service;

import com.churchgeniuspro.plaid.entity.PlaidItem;
import com.churchgeniuspro.plaid.repository.BankSyncVerificationRepository;
import com.churchgeniuspro.plaid.repository.PlaidItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-encrypts stored Plaid access tokens onto the current {@code PLAID_TOKEN_ENC_KEY}.
 *
 * <h2>Why this exists</h2>
 * The token key is not a password — it is the key every stored bank token was
 * encrypted with. Swapping the value alone makes every {@code plaid_item} row
 * undecryptable, and the only recovery is asking every church to reconnect its
 * bank through Plaid Link. This service is what makes a rotation a background
 * operation instead of an outage.
 *
 * <h2>The procedure</h2>
 * <ol>
 *   <li>Set {@code PLAID_TOKEN_ENC_KEY_PREVIOUS} to the OLD key and
 *       {@code PLAID_TOKEN_ENC_KEY} to the NEW one, then deploy. Nothing breaks:
 *       {@link PlaidTokenCipher} decrypts with either, and writes with the new
 *       one. The application is fully functional at this point.</li>
 *   <li>Run {@link #rotate} with {@code dryRun=true} to see how many rows would
 *       move, then again with {@code dryRun=false}.</li>
 *   <li>Re-run until {@code pending} is 0, then remove
 *       {@code PLAID_TOKEN_ENC_KEY_PREVIOUS} and deploy again.</li>
 * </ol>
 *
 * <h2>Properties this pass holds to</h2>
 * <b>Idempotent</b> — a row already on the current key is skipped, so re-running
 * costs nothing and changes nothing. <b>Resumable</b> — each row is its own
 * transaction, so an interrupted run leaves a consistent mixture the next run
 * finishes. <b>Isolated</b> — one unreadable row is recorded and stepped over
 * rather than aborting the pass, following the same rule as the reminder
 * schedulers: a partial run must be diagnosable afterwards.
 *
 * <p>Nothing here logs or returns a token value, in plaintext or ciphertext.
 */
@Service
public class PlaidTokenRotationService {

    private static final Logger log = LoggerFactory.getLogger(PlaidTokenRotationService.class);

    private final PlaidItemRepository itemRepo;
    private final BankSyncVerificationRepository verificationRepo;
    private final PlaidTokenCipher cipher;
    private final PlaidAuditService audit;

    public PlaidTokenRotationService(PlaidItemRepository itemRepo,
                                     BankSyncVerificationRepository verificationRepo,
                                     PlaidTokenCipher cipher,
                                     PlaidAuditService audit) {
        this.itemRepo         = itemRepo;
        this.verificationRepo = verificationRepo;
        this.cipher           = cipher;
        this.audit            = audit;
    }

    /**
     * Reports what a rotation would do, without writing anything.
     *
     * <p>Run this first. It is the difference between knowing 40 connections will
     * move and discovering afterwards that 3 of them could not be read.
     */
    public Map<String, Object> status() {
        return rotate(true, null);
    }

    /**
     * Re-encrypts every stored token that still opens with the previous key.
     *
     * @param dryRun when true, nothing is written; the counts are still accurate
     * @param actor  who asked, for the audit trail (may be null)
     * @return counts plus the ids of any rows that could not be read
     */
    public Map<String, Object> rotate(boolean dryRun, String actor) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", dryRun);
        out.put("rotationInProgress", cipher.rotationInProgress());

        if (!cipher.isConfigured()) {
            out.put("status", "error");
            out.put("message", "PLAID_TOKEN_ENC_KEY is not set or is not a valid AES key.");
            return out;
        }

        List<PlaidItem> items = itemRepo.findAll();
        int examined = 0, alreadyCurrent = 0, rotated = 0, blank = 0;
        List<Integer> unreadable = new ArrayList<>();

        for (PlaidItem item : items) {
            examined++;
            String stored = item.getAccessTokenEnc();

            // A soft-deleted item carries "" as its token stub (the column is NOT
            // NULL), which is not a value to rotate.
            if (stored == null || stored.isBlank()) { blank++; continue; }

            boolean needs;
            try {
                needs = cipher.needsRotation(stored);
            } catch (Exception e) {
                unreadable.add(item.getId());
                continue;
            }

            if (!needs) {
                // Either already current, or readable by neither key. Separate the
                // two so an unreadable row is reported rather than counted as done.
                if (isReadable(stored)) alreadyCurrent++;
                else unreadable.add(item.getId());
                continue;
            }

            if (dryRun) { rotated++; continue; }

            try {
                reEncryptOne(item.getId());
                rotated++;
            } catch (Exception e) {
                // One bad row must not cost the rest their rotation.
                log.warn("Token rotation: item {} could not be re-encrypted — {}",
                         item.getId(), e.getMessage());
                unreadable.add(item.getId());
            }
        }

        out.put("status", "success");
        out.put("examined", examined);
        out.put("blankStubs", blank);
        out.put("alreadyOnCurrentKey", alreadyCurrent);
        out.put(dryRun ? "wouldRotate" : "rotated", rotated);
        out.put("unreadableItemIds", unreadable);
        out.put("pending", dryRun ? rotated : 0);

        if (!dryRun && rotated > 0) {
            audit.record(null, actor == null ? "SERVICE_ADMIN" : actor,
                    "TOKEN_KEY_ROTATED", "plaid_item",
                    "Re-encrypted " + rotated + " access token(s) onto the current key");
        }
        log.info("Token rotation {}: examined={} alreadyCurrent={} {}={} unreadable={}",
                 dryRun ? "(dry run)" : "(applied)", examined, alreadyCurrent,
                 dryRun ? "wouldRotate" : "rotated", rotated, unreadable.size());

        if (!unreadable.isEmpty()) {
            out.put("warning", unreadable.size() + " connection(s) could not be read with either key. "
                  + "They must be reconnected through Plaid Link; no data was changed for them.");
        }
        return out;
    }

    /**
     * Re-encrypts one item.
     *
     * <p>Deliberately NOT annotated {@code @Transactional}: it is called from
     * {@link #rotate} on the same bean, where a proxy-applied annotation would not
     * fire at all — an inert annotation that reads as a guarantee is worse than
     * none. The boundary is real regardless, because {@code JpaRepository.save}
     * carries its own transaction, which is exactly the granularity wanted here:
     * one row per commit, so a pass over hundreds of rows that dies partway leaves
     * a consistent mixture the next run finishes rather than rolling everything
     * back.
     */
    public void reEncryptOne(Integer itemId) {
        PlaidItem item = itemRepo.findById(itemId).orElse(null);
        if (item == null) return;
        String stored = item.getAccessTokenEnc();
        if (stored == null || stored.isBlank()) return;

        String plaintext = cipher.decrypt(stored);          // current-or-previous key
        item.setAccessTokenEnc(cipher.encrypt(plaintext));  // always the current key
        itemRepo.save(item);
    }

    /**
     * Clears unused Bank Sync verification codes.
     *
     * <p>{@code bank_sync_verification.token_enc} uses the same cipher, but the
     * codes live 15 minutes and are single-use, so they are not worth re-encrypting
     * — and {@code BankSyncGateService} already steps over one it cannot decrypt,
     * so a rotation degrades to "that code no longer works" rather than an error.
     * Marking the outstanding ones used makes that explicit instead of leaving
     * people staring at a code that will silently never match.
     *
     * @return how many codes were invalidated
     */
    @Transactional
    public int invalidateOutstandingVerificationCodes() {
        int cleared = 0;
        for (var v : verificationRepo.findAll()) {
            if (v.isUsed()) continue;
            if (!cipher.needsRotation(v.getTokenEnc())) continue;
            v.setUsed(true);
            v.setUsedDate(new java.util.Date());
            verificationRepo.save(v);
            cleared++;
        }
        if (cleared > 0) log.info("Token rotation: invalidated {} outstanding verification code(s)", cleared);
        return cleared;
    }

    /** True when the stored value opens with either configured key. */
    private boolean isReadable(String stored) {
        try {
            cipher.decrypt(stored);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
