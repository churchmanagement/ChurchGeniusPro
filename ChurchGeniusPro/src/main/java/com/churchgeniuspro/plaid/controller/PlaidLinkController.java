package com.churchgeniuspro.plaid.controller;

import com.churchgeniuspro.plaid.entity.PlaidAccount;
import com.churchgeniuspro.plaid.entity.PlaidItem;
import com.churchgeniuspro.plaid.repository.PlaidAccountRepository;
import com.churchgeniuspro.plaid.repository.PlaidItemRepository;
import com.churchgeniuspro.plaid.service.PlaidLinkService;
import com.churchgeniuspro.plaid.service.PlaidSyncService;
import com.churchgeniuspro.plaid.util.PlaidGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bank-connection endpoints for the church admin/accountant (desktop + mobile web).
 * All endpoints are session-authenticated (via AuthFilter), tenant-scoped, and
 * gated by {@link PlaidGuard}. The webhook lives in a separate controller and is
 * the only public Plaid path.
 */
@RestController
@RequestMapping("/api/plaid")
public class PlaidLinkController {

    private static final Logger log = LoggerFactory.getLogger(PlaidLinkController.class);

    private final PlaidGuard guard;
    private final PlaidLinkService linkService;
    private final PlaidSyncService syncService;
    private final PlaidItemRepository itemRepo;
    private final PlaidAccountRepository accountRepo;
    private final com.churchgeniuspro.plaid.service.PlaidEnvironmentService plaidEnv;

    public PlaidLinkController(PlaidGuard guard,
                              PlaidLinkService linkService,
                              PlaidSyncService syncService,
                              PlaidItemRepository itemRepo,
                              PlaidAccountRepository accountRepo,
                              com.churchgeniuspro.plaid.service.PlaidEnvironmentService plaidEnv) {
        this.guard = guard;
        this.linkService = linkService;
        this.syncService = syncService;
        this.itemRepo = itemRepo;
        this.accountRepo = accountRepo;
        this.plaidEnv = plaidEnv;
    }

    /** Create a Plaid Link token for the current user to open Plaid Link in the browser. */
    @PostMapping("/link-token")
    public ResponseEntity<Map<String, Object>> createLinkToken(HttpServletRequest req) {
        String deny = guard.requireBankAccess(req);
        if (deny != null) return forbidden(deny);
        String clientId = SessionUtil.getAppClientId(req);
        try {
            String linkToken = linkService.createLinkToken(clientId, clientUserId(req), SessionUtil.getUsername(req));
            return ResponseEntity.ok(ok("linkToken", linkToken));
        } catch (PlaidLinkService.AccountLimitExceeded e) {
            return error(e.getMessage());     // the plan limit, in the user's words
        } catch (Exception e) {
            log.warn("createLinkToken failed: {}", e.getMessage());
            return error("Could not start bank connection. Please try again.");
        }
    }

    /** Exchange the public token returned by Plaid Link for a stored connection. */
    @PostMapping("/exchange")
    public ResponseEntity<Map<String, Object>> exchange(@RequestBody Map<String, Object> body,
                                                        HttpServletRequest req) {
        String deny = guard.requireBankAccess(req);
        if (deny != null) return forbidden(deny);
        String clientId = SessionUtil.getAppClientId(req);
        String publicToken = body.get("publicToken") != null ? body.get("publicToken").toString()
                : (body.get("public_token") != null ? body.get("public_token").toString() : null);
        if (publicToken == null || publicToken.isBlank()) {
            return error("Missing public token.");
        }
        try {
            Map<String, Object> result = linkService.exchangePublicToken(
                    clientId, appUserId(req), publicToken, SessionUtil.getUsername(req));
            Map<String, Object> resp = new LinkedHashMap<>(result);
            resp.put("status", "success");
            return ResponseEntity.ok(resp);
        } catch (PlaidLinkService.AccountLimitExceeded e) {
            return error(e.getMessage());     // nothing was stored; see PlaidLinkService
        } catch (Exception e) {
            log.warn("exchange failed: {}", e.getMessage());
            return error("Could not complete bank connection.");
        }
    }

    /** List this church's connected items and their accounts (no tokens exposed). */
    @GetMapping("/items")
    public ResponseEntity<Map<String, Object>> listItems(HttpServletRequest req) {
        String deny = guard.requireBankAccess(req);
        if (deny != null) return forbidden(deny);
        String clientId = SessionUtil.getAppClientId(req);
        List<Map<String, Object>> items = new ArrayList<>();
        for (PlaidItem it : itemRepo.findByClientIdAndDeleteFlagFalse(clientId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", it.getId());
            m.put("itemId", it.getItemId());
            m.put("institutionId", it.getInstitutionId());
            m.put("institutionName", it.getInstitutionName());
            m.put("status", it.getStatus());
            m.put("errorCode", it.getErrorCode());
            m.put("plaidEnv", plaidEnv.stampedEnv(it));   // display only; calls go through envForItem
            m.put("lastSyncedDate", it.getLastSyncedDate());
            List<Map<String, Object>> accts = new ArrayList<>();
            for (PlaidAccount a : accountRepo.findByPlaidItemId(it.getId())) {
                Map<String, Object> am = new LinkedHashMap<>();
                am.put("id", a.getId());
                am.put("name", a.getName());
                am.put("mask", a.getMask());
                am.put("type", a.getType());
                am.put("subtype", a.getSubtype());
                am.put("currentBalance", a.getCurrentBalance());
                accts.add(am);
            }
            m.put("accounts", accts);
            items.add(m);
        }
        Map<String, Object> body = ok("items", items);
        // Connected accounts vs. the plan's cap, so the page can say "3 of 3 connected"
        // and explain what to do; null limit = unlimited.
        body.put("connectedAccounts", linkService.connectedAccountCount(clientId));
        body.put("accountLimit",      linkService.accountLimit(clientId));
        // Lets the page label itself as test mode. Display only — the binding that
        // matters is server-side, in the environment the Link token is issued for.
        body.put("sandbox", plaidEnv.isSandboxTenant(clientId));
        body.put("plaidEnv", plaidEnv.isSandboxTenant(clientId)
                ? com.churchgeniuspro.plaid.config.PlaidProperties.SANDBOX
                : plaidEnv.defaultEnv());
        return ResponseEntity.ok(body);
    }

    /** Manually trigger a sync for one connected item ("sync now"). */
    @PostMapping("/items/{id}/sync")
    public ResponseEntity<Map<String, Object>> syncNow(@PathVariable Integer id, HttpServletRequest req) {
        String deny = guard.requireBankAccess(req);
        if (deny != null) return forbidden(deny);
        String clientId = SessionUtil.getAppClientId(req);
        if (!guard.isSyncEnabled(clientId)) {
            return forbidden("Transaction sync is disabled for your organization.");
        }
        try {
            int staged = syncService.syncByItemId(clientId, id, SessionUtil.getUsername(req));
            return ResponseEntity.ok(ok("stagedTransactions", staged));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            log.warn("syncNow failed for item {}: {}", id, e.getMessage());
            return error("Sync failed. Please try again.");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String clientUserId(HttpServletRequest req) {
        Integer uid = appUserId(req);
        if (uid != null) return "usr-" + uid;
        String clientId = SessionUtil.getAppClientId(req);
        return "church-" + (clientId != null ? clientId : "unknown");
    }

    private Integer appUserId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null) return null;
        Object v = s.getAttribute("appUserId");
        return v instanceof Integer i ? i : null;
    }

    private Map<String, Object> ok(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "success");
        m.put(key, value);
        return m;
    }

    private ResponseEntity<Map<String, Object>> forbidden(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("message", message);
        return ResponseEntity.status(403).body(m);
    }

    private ResponseEntity<Map<String, Object>> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("message", message);
        return ResponseEntity.status(400).body(m);
    }
}
