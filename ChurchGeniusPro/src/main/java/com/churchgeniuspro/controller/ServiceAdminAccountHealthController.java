package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.AccountHealthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service Admin → Account Health: expired / inactive / unused churches and
 * paused or idle bank connections. Read-only.
 */
@RestController
public class ServiceAdminAccountHealthController {

    private static final Logger log = LoggerFactory.getLogger(ServiceAdminAccountHealthController.class);

    private final AccountHealthService health;

    public ServiceAdminAccountHealthController(AccountHealthService health) {
        this.health = health;
    }

    @GetMapping("/api/serviceadmin/account-health")
    public ResponseEntity<Map<String, Object>> report(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null || !"ServiceAdmin".equals(s.getAttribute("role"))) {
            return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Not signed in as Service Admin"));
        }
        try {
            Map<String, Object> out = new LinkedHashMap<>(health.report());
            out.put("status", "success");
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.error("Account health report failed", e);
            return ResponseEntity.status(500).body(Map.of("status", "error", "message", "Could not build the account health report."));
        }
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.plaid.service.PlaidLinkService plaidLinks;

    /**
     * Closes the bank connections that belong to churches which have been removed. The list is worked out here
     * (never taken from the page), so a connection of a church that still exists can never be closed by this call.
     */
    @PostMapping("/api/serviceadmin/account-health/removed-church-bank-syncs/disconnect")
    public ResponseEntity<Map<String, Object>> disconnectRemovedChurches(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (s == null || !"ServiceAdmin".equals(s.getAttribute("role"))) {
            return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Not signed in as Service Admin"));
        }
        if (plaidLinks == null) {
            return ResponseEntity.status(503).body(Map.of("status", "error", "message", "Bank Sync is not available."));
        }
        try {
            String actor = ServiceAdminPlatformSettingsController.actor(req);
            int closed = 0, churches = 0;
            for (String clientId : health.removedChurchesWithBankSync()) {
                int n = plaidLinks.disconnectForRemovedChurch(clientId, actor);
                if (n > 0) { closed += n; churches++; }
            }
            log.info("AUDIT removed-church bank connections closed: {} connection(s) of {} church(es) by {}", closed, churches, actor);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "success");
            out.put("disconnected", closed);
            out.put("churches", churches);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.error("Closing bank connections of removed churches failed", e);
            return ResponseEntity.status(500).body(Map.of("status", "error", "message", "Could not close the connections."));
        }
    }
}
