package com.churchgeniuspro.plaid.service;

import com.churchgeniuspro.plaid.entity.ChurchPlaidSetting;
import com.churchgeniuspro.plaid.entity.PlaidAccount;
import com.churchgeniuspro.plaid.entity.PlaidItem;
import com.churchgeniuspro.plaid.repository.ChurchPlaidSettingRepository;
import com.churchgeniuspro.plaid.repository.PlaidAccountRepository;
import com.churchgeniuspro.plaid.repository.PlaidItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service Admin controls for Plaid, scoped per church ({@code clientId}):
 * enable/disable the integration and automatic sync, view connected accounts,
 * and force-disconnect a bank connection.
 */
@Service
public class ServiceAdminPlaidService {

    private static final Logger log = LoggerFactory.getLogger(ServiceAdminPlaidService.class);

    private final ChurchPlaidSettingRepository settingRepo;
    private final PlaidItemRepository itemRepo;
    private final PlaidAccountRepository accountRepo;
    private final PlaidClient client;
    private final PlaidEnvironmentService plaidEnv;
    private final PlaidTokenCipher cipher;
    private final PlaidAuditService audit;

    public ServiceAdminPlaidService(ChurchPlaidSettingRepository settingRepo,
                                    PlaidItemRepository itemRepo,
                                    PlaidAccountRepository accountRepo,
                                    PlaidClient client,
                                    PlaidEnvironmentService plaidEnv,
                                    PlaidTokenCipher cipher,
                                    PlaidAuditService audit) {
        this.settingRepo = settingRepo;
        this.itemRepo = itemRepo;
        this.accountRepo = accountRepo;
        this.client = client;
        this.plaidEnv = plaidEnv;
        this.cipher = cipher;
        this.audit = audit;
    }

    /** Settings + connected items/accounts for one church. */
    @Transactional(readOnly = true)
    public Map<String, Object> getChurchPlaid(String clientId) {
        ChurchPlaidSetting s = settingRepo.findByClientId(clientId).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plaidEnabled", s != null && s.isPlaidEnabled());
        out.put("syncEnabled", s != null && s.isSyncEnabled());

        List<Map<String, Object>> items = new ArrayList<>();
        for (PlaidItem it : itemRepo.findByClientIdAndDeleteFlagFalse(clientId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", it.getId());
            m.put("institutionName", it.getInstitutionName());
            m.put("institutionId", it.getInstitutionId());
            m.put("status", it.getStatus());
            m.put("lastSyncedDate", it.getLastSyncedDate());
            List<Map<String, Object>> accts = new ArrayList<>();
            for (PlaidAccount a : accountRepo.findByPlaidItemId(it.getId())) {
                Map<String, Object> am = new LinkedHashMap<>();
                am.put("name", a.getName());
                am.put("mask", a.getMask());
                am.put("type", a.getType());
                am.put("active", a.isActive());
                accts.add(am);
            }
            m.put("accounts", accts);
            items.add(m);
        }
        out.put("items", items);
        return out;
    }

    /** Enable/disable Plaid and/or automatic sync for a church. */
    @Transactional
    public Map<String, Object> updateSettings(String clientId, Boolean plaidEnabled,
                                              Boolean syncEnabled, String actor) {
        ChurchPlaidSetting s = settingRepo.findByClientId(clientId).orElseGet(() -> {
            ChurchPlaidSetting n = new ChurchPlaidSetting();
            n.setClientId(clientId);
            return n;
        });
        if (plaidEnabled != null) s.setPlaidEnabled(plaidEnabled);
        if (syncEnabled != null) s.setSyncEnabled(syncEnabled);
        // Sync cannot be on when Plaid is off.
        if (!s.isPlaidEnabled()) s.setSyncEnabled(false);
        s.setUpdatedBy(actor);
        s.setUpdatedDate(new Date());
        settingRepo.save(s);
        audit.record(clientId, actor, "PLAID_ENABLED", clientId,
                "plaidEnabled=" + s.isPlaidEnabled() + ", syncEnabled=" + s.isSyncEnabled());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("plaidEnabled", s.isPlaidEnabled());
        out.put("syncEnabled", s.isSyncEnabled());
        return out;
    }

    /**
     * Force-disconnect a bank connection: remove the item at Plaid, then soft-delete
     * it locally and deactivate its accounts. Local cleanup runs even if the remote
     * removal call fails, so the connection is always severed on our side.
     */
    @Transactional
    public void forceDisconnect(String clientId, Integer itemPk, String actor) {
        PlaidItem item = itemRepo.findByIdAndClientId(itemPk, clientId).orElse(null);
        if (item == null) throw new IllegalArgumentException("Bank connection not found.");
        try {
            client.itemRemove(plaidEnv.envForItem(item), cipher.decrypt(item.getAccessTokenEnc()));
        } catch (Exception e) {
            log.warn("Plaid item/remove failed for {} (continuing with local disconnect): {}",
                    item.getItemId(), e.getMessage());
        }
        item.setStatus("DISCONNECTED");
        item.setDeleteFlag(true);
        itemRepo.save(item);
        for (PlaidAccount a : accountRepo.findByPlaidItemId(item.getId())) {
            a.setActive(false);
            a.setUpdatedDate(new Date());
            accountRepo.save(a);
        }
        audit.record(clientId, actor, "ITEM_DISCONNECTED", item.getItemId(), "Force-disconnected by service admin");
    }
}
