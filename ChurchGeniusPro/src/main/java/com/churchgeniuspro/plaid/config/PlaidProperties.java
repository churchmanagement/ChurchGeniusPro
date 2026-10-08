package com.churchgeniuspro.plaid.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds all {@code plaid.*} configuration. Every sensitive value is supplied via
 * an environment variable (see application.properties); secrets are never
 * committed to source.
 */
@Data
@Component
@ConfigurationProperties(prefix = "plaid")
public class PlaidProperties {

    /** The two Plaid environments this application talks to. */
    public static final String SANDBOX    = "sandbox";
    public static final String PRODUCTION = "production";

    /** Plaid client id (env: PLAID_CLIENT_ID). Shared by both environments. */
    private String clientId = "";

    /** Plaid secret (env: PLAID_SECRET). Never logged. */
    private String secret = "";

    /** sandbox | production (env: PLAID_ENV). */
    private String env = "sandbox";

    /** Plaid API base URL (env: PLAID_BASE_URL). */
    private String baseUrl = "https://sandbox.plaid.com";

    /**
     * Plaid SANDBOX secret (env: PLAID_SANDBOX_SECRET). Never logged.
     *
     * <p>Separate from {@link #secret} because Plaid issues a different secret per
     * environment — the client id is shared, the secret is not. A deployment whose
     * {@link #env} is already {@code sandbox} does not need this: {@link
     * #secretFor} falls back to the main secret there, which IS the sandbox one.
     * A production deployment that wants Trial tenants confined to sandbox must
     * set it, or Trial link attempts fail closed rather than reaching production.
     */
    private String sandboxSecret = "";

    /** Plaid sandbox API base URL (env: PLAID_SANDBOX_BASE_URL). Rarely changed. */
    private String sandboxBaseUrl = "https://sandbox.plaid.com";

    /**
     * OAuth redirect URI for SANDBOX link tokens (env: PLAID_SANDBOX_REDIRECT_URI).
     *
     * <p>Blank by default, which falls back to {@link #redirectUri}. Plaid keeps a
     * separate allowed-redirect-URI list per environment, so the same URI must be
     * registered under Sandbox as well as Production for the fallback to work —
     * an unregistered one makes {@code /link/token/create} reject every Trial
     * connection with {@code INVALID_FIELD}. Set this only when the two
     * environments genuinely need different URIs.
     */
    private String sandboxRedirectUri = "";

    /** Public webhook URL registered with Plaid (env: PLAID_WEBHOOK_URL). */
    private String webhookUrl = "https://churchgeniuspro.net/api/plaid/webhook";

    /**
     * OAuth redirect URI (env: PLAID_REDIRECT_URI). Optional — only sent to Plaid
     * when non-blank, and only needed for OAuth institutions. Must EXACTLY match a
     * URI registered in the Plaid Dashboard, otherwise /link/token/create rejects
     * every request with INVALID_FIELD. Blank by default so plain (non-OAuth)
     * Link flows work out of the box.
     */
    private String redirectUri = "";

    /** Comma-separated Plaid products (env: PLAID_PRODUCTS). */
    private String products = "transactions";

    /** Comma-separated country codes (env: PLAID_COUNTRY_CODES). */
    private String countryCodes = "US";

    /** Base64-encoded 32-byte AES key for token encryption (env: PLAID_TOKEN_ENC_KEY). */
    private String tokenEncKey = "";

    /**
     * The PREVIOUS token-encryption key, during a key rotation
     * (env: PLAID_TOKEN_ENC_KEY_PREVIOUS). Decrypt-only — nothing is ever written
     * with it.
     *
     * <p>Set it to the outgoing key while {@link #tokenEncKey} holds the new one.
     * Every stored token then keeps decrypting, so the application runs normally
     * from the moment the new key is deployed, and
     * {@code PlaidTokenRotationService} can re-encrypt the rows in the background.
     * Clear it once the rotation reports nothing left to do.
     *
     * <p>Blank by default: with no rotation in progress there is only one key.
     */
    private String tokenEncKeyPrevious = "";

    /** Days to retain rejected/removed staging rows before purge (env: PLAID_STAGING_RETENTION_DAYS). */
    private int stagingRetentionDays = 90;

    /** True when the core Plaid credentials are present. */
    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
                && secret != null && !secret.isBlank();
    }

    /* ── per-environment resolution ─────────────────────────────────────── */

    /** True when this deployment's global environment is Plaid sandbox. */
    public boolean isGlobalSandbox() {
        return SANDBOX.equalsIgnoreCase(trim(env));
    }

    /** Normalises anything to one of {@link #SANDBOX} / {@link #PRODUCTION}. */
    public static String normaliseEnv(String value) {
        return SANDBOX.equalsIgnoreCase(trim(value)) ? SANDBOX : PRODUCTION;
    }

    /**
     * The secret for one environment.
     *
     * <p>Sandbox falls back to the main secret when no dedicated sandbox secret is
     * configured AND this deployment is globally on sandbox — in that setup the
     * main secret already is the sandbox secret, so dev and test keep working with
     * no new configuration. It never falls back the other way: a production secret
     * is never handed to a sandbox call, or the reverse.
     */
    public String secretFor(String environment) {
        if (!SANDBOX.equals(normaliseEnv(environment))) return secret;
        if (sandboxSecret != null && !sandboxSecret.isBlank()) return sandboxSecret;
        return isGlobalSandbox() ? secret : "";
    }

    /** The API base URL for one environment. */
    public String baseUrlFor(String environment) {
        if (!SANDBOX.equals(normaliseEnv(environment))) return baseUrl;
        return (sandboxBaseUrl != null && !sandboxBaseUrl.isBlank())
                ? sandboxBaseUrl : "https://sandbox.plaid.com";
    }

    /** The OAuth redirect URI to send for one environment; may be blank (omitted). */
    public String redirectUriFor(String environment) {
        if (!SANDBOX.equals(normaliseEnv(environment))) return redirectUri;
        return (sandboxRedirectUri != null && !sandboxRedirectUri.isBlank())
                ? sandboxRedirectUri : redirectUri;
    }

    /** True when this environment has a usable client id + secret pair. */
    public boolean isConfiguredFor(String environment) {
        String s = secretFor(environment);
        return clientId != null && !clientId.isBlank() && s != null && !s.isBlank();
    }

    private static String trim(String v) { return v == null ? "" : v.trim(); }
}
