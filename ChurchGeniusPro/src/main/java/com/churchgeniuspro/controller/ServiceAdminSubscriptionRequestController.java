package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.SubscriptionRequestService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Service Admin → Subscription Requests: list, mark in progress, decline. Convert lives on the client. */
@RestController
public class ServiceAdminSubscriptionRequestController {

    private final SubscriptionRequestService requests;

    public ServiceAdminSubscriptionRequestController(SubscriptionRequestService requests) {
        this.requests = requests;
    }

    @GetMapping("/api/serviceadmin/subscription-requests")
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return ResponseEntity.ok(Map.of("status", "success", "requests", requests.listForAdmin()));
    }

    @PostMapping("/api/serviceadmin/subscription-requests/{id}/in-progress")
    public ResponseEntity<Map<String, Object>> inProgress(@PathVariable Long id, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        try {
            requests.markInProgress(id, ServiceAdminPlatformSettingsController.actor(req));
            return ResponseEntity.ok(Map.of("status", "success", "message", "Marked in progress."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @PostMapping("/api/serviceadmin/subscription-requests/{id}/decline")
    public ResponseEntity<Map<String, Object>> decline(@PathVariable Long id,
                                                       @RequestBody(required = false) Map<String, Object> body,
                                                       HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        try {
            String reason = body == null || body.get("reason") == null ? null : String.valueOf(body.get("reason"));
            requests.decline(id, ServiceAdminPlatformSettingsController.actor(req), reason);
            return ResponseEntity.ok(Map.of("status", "success", "message", "Request declined. No email was sent to the church."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /** Marks a request completed after the church was registered as a new client (sample-data trials). */
    @PostMapping("/api/serviceadmin/subscription-requests/{id}/complete")
    public ResponseEntity<Map<String, Object>> complete(@PathVariable Long id, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        try {
            requests.complete(id, null, ServiceAdminPlatformSettingsController.actor(req));
            return ResponseEntity.ok(Map.of("status", "success", "message", "Request marked completed."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    private static boolean isServiceAdmin(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null && "ServiceAdmin".equals(s.getAttribute("role"));
    }

    private static ResponseEntity<Map<String, Object>> denied() {
        return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Not signed in as Service Admin"));
    }
}
