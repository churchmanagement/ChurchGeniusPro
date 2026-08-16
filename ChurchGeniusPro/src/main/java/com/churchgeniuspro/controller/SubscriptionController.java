package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.SubscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Frontend-facing subscription info for the logged-in user's church.
 *
 * <p>{@code GET /api/subscription/features} → plan name/code, effective
 * feature flags (explicit values only — a missing key means enabled), limits,
 * and this month's usage. Session-protected by {@code AuthFilter};
 * {@code session.js} fetches this once per session and exposes
 * {@code window.CGP_FEATURES} for nav/menu/button hiding.
 */
@RestController
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @GetMapping("/api/subscription/features")
    public ResponseEntity<?> features(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return ResponseEntity.status(401).body(Map.of("error", "Not logged in"));

        String clientId = null;
        Object app = session.getAttribute("appClientId");
        if (app instanceof String s && !s.isBlank()) clientId = s;
        if (clientId == null) {
            Object cid = session.getAttribute("clientId");
            if (cid instanceof String s && !s.isBlank()) clientId = s;
        }
        if (clientId == null) {
            // No org context (e.g. service admin) — report everything enabled.
            return ResponseEntity.ok(Map.of("planCode", "NONE", "features", Map.of(), "limits", Map.of()));
        }
        return ResponseEntity.ok(subscriptionService.describe(clientId));
    }
}
