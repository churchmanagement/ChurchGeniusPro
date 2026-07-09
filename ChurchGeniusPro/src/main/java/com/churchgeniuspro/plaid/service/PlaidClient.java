package com.churchgeniuspro.plaid.service;

import com.churchgeniuspro.plaid.config.PlaidProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin REST wrapper over the Plaid API (no third-party SDK). Every request
 * carries {@code client_id} and {@code secret} in the JSON body per Plaid's API;
 * these are pulled from {@link PlaidProperties} (env-backed) and are never logged.
 *
 * <p>This class only performs transport. Persistence, encryption, and business
 * rules live in the higher-level services (added in later phases).
 */
@Component
public class PlaidClient {

    private static final Logger log = LoggerFactory.getLogger(PlaidClient.class);

    private final PlaidProperties props;
    private final RestClient rest;

    public PlaidClient(PlaidProperties props) {
        this.props = props;
        // RestClient uses the JVM's default TLS (negotiates TLS 1.2/1.3); Plaid
        // requires TLS 1.2+. baseUrl is applied per-call from properties.
        this.rest = RestClient.builder()
                .baseUrl(props.getBaseUrl() == null ? "" : props.getBaseUrl())
                .build();
    }

    /** Create a Link token for a specific church user. Returns the parsed response. */
    public Map<String, Object> createLinkToken(String clientUserId, String clientName) {
        Map<String, Object> body = baseBody();
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("client_user_id", clientUserId);
        body.put("user", user);
        body.put("client_name", clientName != null ? clientName : "ChurchGeniusPro");
        body.put("products", csvToList(props.getProducts()));
        body.put("country_codes", csvToList(props.getCountryCodes()));
        body.put("language", "en");
        if (props.getWebhookUrl() != null && !props.getWebhookUrl().isBlank()) {
            body.put("webhook", props.getWebhookUrl());
        }
        return post("/link/token/create", body);
    }

    /** Exchange a public token for a long-lived access token + item id. */
    public Map<String, Object> exchangePublicToken(String publicToken) {
        Map<String, Object> body = baseBody();
        body.put("public_token", publicToken);
        return post("/item/public_token/exchange", body);
    }

    /** Incremental transaction pull using the cursor model. Cursor may be null on first call. */
    public Map<String, Object> transactionsSync(String accessToken, String cursor) {
        Map<String, Object> body = baseBody();
        body.put("access_token", accessToken);
        if (cursor != null && !cursor.isBlank()) {
            body.put("cursor", cursor);
        }
        return post("/transactions/sync", body);
    }

    /** Fetch accounts (with balances) for an item. */
    public Map<String, Object> accountsGet(String accessToken) {
        Map<String, Object> body = baseBody();
        body.put("access_token", accessToken);
        return post("/accounts/get", body);
    }

    /** Remove an item (force-disconnect a bank connection). */
    public Map<String, Object> itemRemove(String accessToken) {
        Map<String, Object> body = baseBody();
        body.put("access_token", accessToken);
        return post("/item/remove", body);
    }

    /** Fetch the JWK used to verify webhook signatures for a given key id. */
    public Map<String, Object> webhookVerificationKeyGet(String keyId) {
        Map<String, Object> body = baseBody();
        body.put("key_id", keyId);
        return post("/webhook_verification_key/get", body);
    }

    // ── internals ──────────────────────────────────────────────────────────

    private Map<String, Object> baseBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", props.getClientId());
        body.put("secret", props.getSecret());
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> body) {
        if (!props.isConfigured()) {
            throw new IllegalStateException("Plaid is not configured (missing PLAID_CLIENT_ID / PLAID_SECRET).");
        }
        // Log the path only — never the body (contains client_id/secret/tokens).
        log.debug("Plaid POST {}", path);
        return rest.post()
                .uri(path)
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    private List<String> csvToList(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
