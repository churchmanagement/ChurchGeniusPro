package com.churchgeniuspro.plaid.service;

import com.churchgeniuspro.plaid.config.PlaidProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
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
 *
 * <h2>Environment is an argument, not a default</h2>
 * Every method takes the Plaid environment ({@code sandbox} / {@code production})
 * explicitly, and this class holds no fallback. Trial tenants are confined to the
 * sandbox, and a transport layer that could pick an environment on its own would
 * be one forgotten parameter away from putting them in front of real banks.
 * {@link com.churchgeniuspro.plaid.service.PlaidEnvironmentService} is the only
 * thing that decides; this class only obeys.
 *
 * <p>Each environment gets its own {@link RestClient}, because a client's base URL
 * is fixed at construction, and its own secret — Plaid issues one per environment
 * and rejects the other outright.
 */
@Component
public class PlaidClient {

    private static final Logger log = LoggerFactory.getLogger(PlaidClient.class);

    private final PlaidProperties props;
    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    public PlaidClient(PlaidProperties props) {
        this.props = props;
    }

    /**
     * The transport for one environment, built once and reused.
     *
     * <p>RestClient uses the JVM's default TLS (negotiates TLS 1.2/1.3); Plaid
     * requires TLS 1.2+.
     */
    private RestClient clientFor(String env) {
        return clients.computeIfAbsent(PlaidProperties.normaliseEnv(env), e -> {
            String base = props.baseUrlFor(e);
            log.info("Plaid transport initialised for env={} ({})", e, base);
            return RestClient.builder().baseUrl(base == null ? "" : base).build();
        });
    }

    /** Create a Link token for a specific church user. Returns the parsed response. */
    public Map<String, Object> createLinkToken(String env, String clientUserId, String clientName) {
        Map<String, Object> body = baseBody(env);
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
        // Required for OAuth institutions (Chase, BofA, ...): must exactly match a
        // redirect URI registered in the Plaid Dashboard (Developers -> API).
        String redirectUri = props.redirectUriFor(env);
        if (redirectUri != null && !redirectUri.isBlank()) {
            body.put("redirect_uri", redirectUri);
        }
        return post(env, "/link/token/create", body);
    }

    /** Exchange a public token for a long-lived access token + item id. */
    public Map<String, Object> exchangePublicToken(String env, String publicToken) {
        Map<String, Object> body = baseBody(env);
        body.put("public_token", publicToken);
        return post(env, "/item/public_token/exchange", body);
    }

    /** Incremental transaction pull using the cursor model. Cursor may be null on first call. */
    public Map<String, Object> transactionsSync(String env, String accessToken, String cursor) {
        Map<String, Object> body = baseBody(env);
        body.put("access_token", accessToken);
        if (cursor != null && !cursor.isBlank()) {
            body.put("cursor", cursor);
        }
        return post(env, "/transactions/sync", body);
    }

    /** Fetch accounts (with balances) for an item. */
    public Map<String, Object> accountsGet(String env, String accessToken) {
        Map<String, Object> body = baseBody(env);
        body.put("access_token", accessToken);
        return post(env, "/accounts/get", body);
    }

    /** Remove an item (force-disconnect a bank connection). */
    public Map<String, Object> itemRemove(String env, String accessToken) {
        Map<String, Object> body = baseBody(env);
        body.put("access_token", accessToken);
        return post(env, "/item/remove", body);
    }

    /** Fetch the JWK used to verify webhook signatures for a given key id. */
    public Map<String, Object> webhookVerificationKeyGet(String env, String keyId) {
        Map<String, Object> body = baseBody(env);
        body.put("key_id", keyId);
        return post(env, "/webhook_verification_key/get", body);
    }

    // ── internals ──────────────────────────────────────────────────────────

    /** Credentials for one environment. The client id is shared; the secret is not. */
    private Map<String, Object> baseBody(String env) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", props.getClientId());
        body.put("secret", props.secretFor(env));
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String env, String path, Map<String, Object> body) {
        String target = PlaidProperties.normaliseEnv(env);
        if (!props.isConfiguredFor(target)) {
            throw new IllegalStateException("Plaid is not configured for the "
                    + target + " environment (missing client id or secret).");
        }
        // Log the path and environment only — never the body (client_id/secret/tokens).
        log.debug("Plaid POST {} [{}]", path, target);
        return clientFor(target).post()
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
