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

    /** Plaid client id (env: PLAID_CLIENT_ID). */
    private String clientId = "";

    /** Plaid secret (env: PLAID_SECRET). Never logged. */
    private String secret = "";

    /** sandbox | production (env: PLAID_ENV). */
    private String env = "sandbox";

    /** Plaid API base URL (env: PLAID_BASE_URL). */
    private String baseUrl = "https://sandbox.plaid.com";

    /** Public webhook URL registered with Plaid (env: PLAID_WEBHOOK_URL). */
    private String webhookUrl = "https://churchgeniuspro.net/api/plaid/webhook";

    /** Comma-separated Plaid products (env: PLAID_PRODUCTS). */
    private String products = "transactions";

    /** Comma-separated country codes (env: PLAID_COUNTRY_CODES). */
    private String countryCodes = "US";

    /** Base64-encoded 32-byte AES key for token encryption (env: PLAID_TOKEN_ENC_KEY). */
    private String tokenEncKey = "";

    /** Days to retain rejected/removed staging rows before purge (env: PLAID_STAGING_RETENTION_DAYS). */
    private int stagingRetentionDays = 90;

    /** True when the core Plaid credentials are present. */
    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
                && secret != null && !secret.isBlank();
    }
}
