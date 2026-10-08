package com.churchgeniuspro.plaid.controller;

import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.plaid.service.ServiceAdminPlaidService;
import com.churchgeniuspro.repository.ServiceClientRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service Admin per-church Plaid controls. Mounted under {@code /api/serviceadmin}
 * (which AuthFilter leaves to the Service Admin's own auth), so this controller
 * enforces the {@code ServiceAdmin} session role itself. Churches are addressed
 * by {@code service_client.id}; the tenant {@code clientId} is resolved internally.
 */
@RestController
public class ServiceAdminPlaidController {

    private static final Logger log = LoggerFactory.getLogger(ServiceAdminPlaidController.class);

    private final ServiceAdminPlaidService service;
    private final ServiceClientRepository serviceClientRepository;
    private final com.churchgeniuspro.plaid.service.PlaidTokenRotationService rotationService;

    public ServiceAdminPlaidController(ServiceAdminPlaidService service,
                                       ServiceClientRepository serviceClientRepository,
                                       com.churchgeniuspro.plaid.service.PlaidTokenRotationService rotationService) {
        this.service = service;
        this.serviceClientRepository = serviceClientRepository;
        this.rotationService = rotationService;
    }

    /** Current Plaid settings + connected accounts for one church. */
    @GetMapping("/api/serviceadmin/clients/{id}/plaid")
    public ResponseEntity<Map<String, Object>> get(@PathVariable Integer id, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return unauthorized();
        String clientId = clientIdFor(id);
        if (clientId == null) return notFound();
        Map<String, Object> data = new LinkedHashMap<>(service.getChurchPlaid(clientId));
        data.put("status", "success");
        return ResponseEntity.ok(data);
    }

    /** Enable/disable Plaid and/or sync for one church. */
    @PutMapping("/api/serviceadmin/clients/{id}/plaid")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Integer id,
                                                      @RequestBody Map<String, Object> body,
                                                      HttpServletRequest req) {
        if (!isServiceAdmin(req)) return unauthorized();
        String clientId = clientIdFor(id);
        if (clientId == null) return notFound();
        Boolean plaidEnabled = boolOrNull(body.get("plaidEnabled"));
        Boolean syncEnabled = boolOrNull(body.get("syncEnabled"));
        Map<String, Object> data = new LinkedHashMap<>(
                service.updateSettings(clientId, plaidEnabled, syncEnabled, actor(req)));
        data.put("status", "success");
        data.put("message", "Plaid settings updated.");
        return ResponseEntity.ok(data);
    }

    /** Force-disconnect one bank connection. */
    @PostMapping("/api/serviceadmin/clients/{id}/plaid/items/{itemId}/disconnect")
    public ResponseEntity<Map<String, Object>> disconnect(@PathVariable Integer id,
                                                          @PathVariable Integer itemId,
                                                          HttpServletRequest req) {
        if (!isServiceAdmin(req)) return unauthorized();
        String clientId = clientIdFor(id);
        if (clientId == null) return notFound();
        try {
            service.forceDisconnect(clientId, itemId, actor(req));
            return ResponseEntity.ok(msg("success", "Bank connection disconnected."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(400).body(msg("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("force disconnect failed: {}", e.getMessage());
            return ResponseEntity.status(500).body(msg("error", "Could not disconnect."));
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /* ── Token-encryption key rotation ──────────────────────────────────── */

    /**
     * What a rotation would do. Read-only — run this before the real thing.
     */
    @GetMapping("/api/serviceadmin/plaid/token-rotation")
    public ResponseEntity<Map<String, Object>> rotationStatus(HttpServletRequest req) {
        if (!isServiceAdmin(req)) return unauthorized();
        return ResponseEntity.ok(rotationService.status());
    }

    /**
     * Re-encrypts stored access tokens onto the current PLAID_TOKEN_ENC_KEY.
     *
     * <p>Safe to run repeatedly: rows already on the current key are skipped.
     * Pass {@code ?invalidateCodes=true} to also retire outstanding Bank Sync
     * verification codes, which the old key can no longer open.
     */
    @PostMapping("/api/serviceadmin/plaid/token-rotation")
    public ResponseEntity<Map<String, Object>> rotate(
            HttpServletRequest req,
            @RequestParam(name = "invalidateCodes", defaultValue = "false") boolean invalidateCodes) {
        if (!isServiceAdmin(req)) return unauthorized();
        String actor = String.valueOf(req.getSession(false) == null
                ? "SERVICE_ADMIN" : req.getSession(false).getAttribute("username"));
        Map<String, Object> out = new java.util.LinkedHashMap<>(rotationService.rotate(false, actor));
        if (invalidateCodes) {
            out.put("verificationCodesInvalidated",
                    rotationService.invalidateOutstandingVerificationCodes());
        }
        return ResponseEntity.ok(out);
    }

    private boolean isServiceAdmin(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null && "ServiceAdmin".equals(s.getAttribute("role"));
    }

    private String actor(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        Object u = s != null ? s.getAttribute("serviceAdminUsername") : null;
        return u != null ? u.toString() : "ServiceAdmin";
    }

    private String clientIdFor(Integer id) {
        return serviceClientRepository.findById(id).map(ServiceClient::getClientId).orElse(null);
    }

    private Boolean boolOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        String s = o.toString().trim();
        if (s.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (s.equalsIgnoreCase("false")) return Boolean.FALSE;
        return null;
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(msg("error", "Service admin session required."));
    }

    private ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(404).body(msg("error", "Client not found."));
    }

    private Map<String, Object> msg(String status, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("message", message);
        return m;
    }
}
