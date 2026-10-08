package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.AccountHealthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
}
