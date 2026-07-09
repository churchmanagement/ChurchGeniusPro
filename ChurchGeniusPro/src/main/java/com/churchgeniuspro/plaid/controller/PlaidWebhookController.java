package com.churchgeniuspro.plaid.controller;

import com.churchgeniuspro.plaid.service.PlaidWebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public Plaid webhook endpoint. This is the only unauthenticated Plaid path
 * (whitelisted in AuthFilter); security comes from JWT signature verification in
 * {@link PlaidWebhookService}, not from a session.
 *
 * <p>Returns 200 quickly and always — even for invalid signatures — to avoid
 * Plaid retry storms. Invalid events are recorded and ignored; valid events are
 * processed asynchronously.
 *
 * <p>The body is received as a raw String so its exact bytes can be hashed for
 * the SHA-256 body-integrity check.
 */
@RestController
public class PlaidWebhookController {

    private final PlaidWebhookService webhookService;

    public PlaidWebhookController(PlaidWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/api/plaid/webhook")
    public ResponseEntity<String> webhook(
            @RequestBody(required = false) String body,
            @RequestHeader(value = "Plaid-Verification", required = false) String verification) {
        try {
            webhookService.handle(verification, body);
        } catch (Exception ignored) {
            // Never surface errors to Plaid; failures are logged inside the service.
        }
        return ResponseEntity.ok("{\"status\":\"received\"}");
    }
}
