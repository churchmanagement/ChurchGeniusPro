package com.churchgeniuspro.plaid.controller;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.plaid.entity.PlaidItem;
import com.churchgeniuspro.plaid.repository.PlaidItemRepository;
import com.churchgeniuspro.plaid.service.PlaidLinkService;
import com.churchgeniuspro.plaid.util.PlaidGuard;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.VerificationStore;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OTP-confirmed deletion of a connected bank ("Delete Connected Bank" on the
 * Bank Sync page). Deleting is destructive — it revokes the Plaid item, purges
 * the review queue for the connection, and removes its sync data — so the user
 * must confirm a one-time code sent to the email registered on their account.
 *
 * <p>Flow: {@code POST /api/plaid/items/{id}/delete/send-code} emails the code,
 * then {@code POST /api/plaid/items/{id}/delete/confirm} with the code performs
 * the deletion. Codes expire after 10 minutes ({@link VerificationStore}) and
 * are keyed to the user AND the specific item, so a code sent for one bank can
 * never delete another. Session-authenticated + {@link PlaidGuard} gated.
 */
@RestController
@RequestMapping("/api/plaid/items")
public class PlaidItemDeleteController {

    private static final Logger log = LoggerFactory.getLogger(PlaidItemDeleteController.class);
    private static final String TYPE_PREFIX = "PLAID_DELETE_";

    private final PlaidGuard guard;
    private final PlaidLinkService linkService;
    private final PlaidItemRepository itemRepo;
    private final AppUserRepository appUserRepository;
    private final VerificationStore verificationStore;
    private final EmailService emailService;

    public PlaidItemDeleteController(PlaidGuard guard,
                                     PlaidLinkService linkService,
                                     PlaidItemRepository itemRepo,
                                     AppUserRepository appUserRepository,
                                     VerificationStore verificationStore,
                                     EmailService emailService) {
        this.guard = guard;
        this.linkService = linkService;
        this.itemRepo = itemRepo;
        this.appUserRepository = appUserRepository;
        this.verificationStore = verificationStore;
        this.emailService = emailService;
    }

    /** Step 1 — email a one-time deletion code to the current user's registered address. */
    @PostMapping("/{id}/delete/send-code")
    public ResponseEntity<Map<String, Object>> sendCode(@PathVariable Integer id, HttpServletRequest req) {
        String deny = guard.requireBankAccess(req);
        if (deny != null) return status(403, deny);

        String clientId = SessionUtil.getAppClientId(req);
        Integer appUserId = appUserId(req);
        if (appUserId == null) return status(403, "A staff account is required to delete a bank connection.");

        PlaidItem item = itemRepo.findByIdAndClientId(id, clientId).orElse(null);
        if (item == null || item.isDeleteFlag()) return status(404, "Bank connection not found.");

        AppUser user = appUserRepository.findById(appUserId).orElse(null);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            return status(400, "No email address is registered on your account.");
        }

        String institution = item.getInstitutionName() != null ? item.getInstitutionName() : "your bank";
        try {
            String code = verificationStore.generateAndStore(clientId, typeKey(appUserId, id), user.getEmail());
            String html = "<p>You requested to <strong>delete the connected bank \"" + escape(institution)
                    + "\"</strong> from ChurchGeniusPro Bank Sync.</p>"
                    + "<p>Your confirmation code is:</p>"
                    + "<p style=\"font-size:22px;font-weight:700;letter-spacing:3px;\">" + code + "</p>"
                    + "<p>Entering this code will disconnect the bank and permanently remove its pending "
                    + "transactions from the review queue. Already-approved ledger entries are not affected.</p>"
                    + "<p>This code expires in 10 minutes. If you did not request this, you can ignore this email.</p>";
            emailService.sendAccountEmail(user.getEmail(), "Confirm deletion of a connected bank", html, clientId);
            return ResponseEntity.ok(ok("message", "A confirmation code was sent to your email."));
        } catch (Exception e) {
            log.warn("delete send-code failed for item {}: {}", id, e.getMessage());
            return status(500, "Could not send the confirmation code. Please try again.");
        }
    }

    /** Step 2 — verify the code and perform the deletion. */
    @PostMapping("/{id}/delete/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@PathVariable Integer id,
                                                       @RequestBody Map<String, Object> body,
                                                       HttpServletRequest req) {
        String deny = guard.requireBankAccess(req);
        if (deny != null) return status(403, deny);

        String clientId = SessionUtil.getAppClientId(req);
        Integer appUserId = appUserId(req);
        if (appUserId == null) return status(403, "A staff account is required to delete a bank connection.");

        String code = body.get("code") != null ? body.get("code").toString().trim() : "";
        if (code.isEmpty()) return status(400, "Enter the confirmation code from your email.");

        if (!verificationStore.validate(clientId, typeKey(appUserId, id), code)) {
            return status(400, "Invalid or expired code. Request a new one and try again.");
        }

        try {
            Map<String, Object> result = linkService.deleteItem(clientId, id, SessionUtil.getUsername(req));
            verificationStore.remove(clientId, typeKey(appUserId, id));
            Map<String, Object> resp = new LinkedHashMap<>(result);
            resp.put("status", "success");
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return status(400, e.getMessage());
        } catch (Exception e) {
            log.warn("delete confirm failed for item {}: {}", id, e.getMessage());
            return status(500, "Could not delete the bank connection. Please try again.");
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Code key bound to both the requesting user and the target item. */
    private String typeKey(Integer appUserId, Integer itemDbId) {
        return TYPE_PREFIX + appUserId + "_ITEM_" + itemDbId;
    }

    private Integer appUserId(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        Object v = s != null ? s.getAttribute("appUserId") : null;
        return v instanceof Integer i ? i : null;
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private Map<String, Object> ok(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "success");
        m.put(key, value);
        return m;
    }

    private ResponseEntity<Map<String, Object>> status(int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("message", message);
        return ResponseEntity.status(code).body(m);
    }
}
