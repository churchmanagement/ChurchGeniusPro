package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.PlatformSettingService;
import com.churchgeniuspro.service.TrialRequestService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service Admin → Trial Requests: the shareable request-page link (generate,
 * regenerate, revoke) and the list of requests with Approve / Reject.
 * Approve issues an ordinary Trial Registration Link — see {@link TrialRequestService}.
 */
@RestController
public class ServiceAdminTrialRequestController {

    private static final Logger log = LoggerFactory.getLogger(ServiceAdminTrialRequestController.class);

    private final TrialRequestService requests;
    private final PlatformSettingService settings;

    public ServiceAdminTrialRequestController(TrialRequestService requests, PlatformSettingService settings) {
        this.requests = requests;
        this.settings = settings;
    }

    @GetMapping("/api/serviceadmin/trial-requests")
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "success");
        out.put("linkUrl", requests.linkUrl());
        out.put("trialDays", requests.trialDays());
        out.put("supportEmail", settings.supportEmail());
        out.put("requests", requests.listForAdmin());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/api/serviceadmin/trial-requests/link")
    public ResponseEntity<Map<String, Object>> regenerate(HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        String url = requests.regenerateLink(actor(req));
        return ResponseEntity.ok(Map.of("status", "success", "linkUrl", url,
                "message", "New Trial Request link generated. Any previous link no longer works."));
    }

    @PostMapping("/api/serviceadmin/trial-requests/link/revoke")
    public ResponseEntity<Map<String, Object>> revoke(HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        requests.revokeLink(actor(req));
        return ResponseEntity.ok(Map.of("status", "success",
                "message", "Trial Request link revoked. The page is closed until a new link is generated."));
    }

    @PostMapping("/api/serviceadmin/trial-requests/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable Long id, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        try {
            TrialRequestService.Approval a = requests.approve(id, actor(req));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "success");
            out.put("registrationUrl", a.registrationUrl());
            out.put("trialDays", a.trialDays());
            out.put("emailSent", a.emailSent());
            out.put("message", a.registrationUrl() == null
                    ? "Approved again. The trial account is active and its logins work once more."
                    : a.emailSent()
                        ? "Approved. A " + a.trialDays() + "-day Trial Registration Link was emailed to " + a.request().getEmail() + "."
                        : a.emailError());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Trial request approve failed for id={}", id, e);
            return ResponseEntity.status(500).body(Map.of("status", "error",
                    "message", "Could not approve the request. Nothing was sent; please try again."));
        }
    }

    @PostMapping("/api/serviceadmin/trial-requests/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable Long id,
                                                      @RequestBody(required = false) Map<String, Object> body,
                                                      HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        try {
            String reason = body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason"));
            requests.reject(id, actor(req), reason);
            return ResponseEntity.ok(Map.of("status", "success", "message", "Request rejected."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    // ── Phase C: account actions, separate from approve / reject ─────────────

    @PostMapping("/api/serviceadmin/trial-requests/{id}/disable")
    public ResponseEntity<Map<String, Object>> disable(@PathVariable Long id, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return accountAction(() -> requests.disable(id, actor(req)), id, "disable");
    }

    @PostMapping("/api/serviceadmin/trial-requests/{id}/delete")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return accountAction(() -> requests.delete(id, actor(req)), id, "delete");
    }

    @PostMapping("/api/serviceadmin/trial-requests/{id}/pending")
    public ResponseEntity<Map<String, Object>> backToPending(@PathVariable Long id, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return accountAction(() -> requests.backToPending(id, actor(req)), id, "back to pending");
    }

    private ResponseEntity<Map<String, Object>> accountAction(java.util.function.Supplier<TrialRequestService.AccountAction> op,
                                                              Long id, String what) {
        try {
            TrialRequestService.AccountAction a = op.get();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "success");
            out.put("requestStatus", a.request().getStatus());
            out.put("tenantClientId", a.tenantClientId());
            out.put("message", a.message());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        } catch (Exception e) {
            log.error("Trial request {} failed for id={}", what, id, e);
            return ResponseEntity.status(500).body(Map.of("status", "error", "message", "Could not " + what + " the request."));
        }
    }

    private static boolean isServiceAdmin(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null && "ServiceAdmin".equals(s.getAttribute("role"));
    }

    private static String actor(HttpServletRequest req) {
        return ServiceAdminPlatformSettingsController.actor(req);
    }

    private static ResponseEntity<Map<String, Object>> denied() {
        return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Not signed in as Service Admin"));
    }
}
