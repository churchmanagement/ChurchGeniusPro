package com.churchgeniuspro.config;

import com.churchgeniuspro.util.EncryptionUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One boot-time line per operator-supplied secret — <b>present/missing only, never the
 * value</b> — so a missing environment variable is visible in the startup log rather
 * than discovered in production when the feature that needs it first runs.
 *
 * <p>Production-readiness audit 2026-10-07 (Phase 4.3). Variables are grouped:
 * <ul>
 *   <li><b>required</b> — the application must not start in {@code prod} without it
 *       ({@code CGP_CID_KEY}: every public-form / unsubscribe token is signed with it;
 *       running on the built-in legacy key in production is a key compromise).</li>
 *   <li><b>expected</b> — a whole feature is disabled or degraded without it; WARN,
 *       the application still starts. {@code PAYROLL_SSN_ENC_KEY} is deliberately in
 *       this group, not "required", until the payroll key-rotation phase has run.</li>
 *   <li><b>optional</b> — INFO only.</li>
 *   <li><b>rotation-only</b> — set only while a key rotation is in progress; INFO only.</li>
 * </ul>
 * The property names below are the ones {@code application.properties} maps each
 * variable to; {@code CGP_CID_KEY} is read straight from the OS environment by
 * {@link EncryptionUtil}, so it is checked through that class.
 *
 * <p>Line format is {@code [Secrets] <status>  <NAME> …} — status BEFORE the name, with
 * no {@code :} or {@code =} after the name. {@code SensitiveDataMasker} (the logging
 * layout) redacts anything shaped {@code *_KEY: value} / {@code *_SECRET: value}, so the
 * earlier {@code NAME: missing} form came out as {@code NAME: ***REDACTED***} for exactly
 * the variables this report exists for. The {@code DB_*_PROD} entries are reported only
 * under the {@code prod} profile — in other profiles the datasource properties come from
 * the profile file, not those variables.
 */
@Component
public class SecretsStartupReport {

    private static final Logger log = LoggerFactory.getLogger(SecretsStartupReport.class);

    /** Variable name → Spring property that application.properties maps it to. */
    record Var(String name, String property, String note) { }

    static final List<Var> EXPECTED = List.of(
            new Var("DB_URL_PROD", "spring.datasource.url", "database"),
            new Var("DB_USER_PROD", "spring.datasource.username", "database"),
            new Var("DB_PASSWORD_PROD", "spring.datasource.password", "database"),
            new Var("PAYROLL_SSN_ENC_KEY", "payroll.ssn-enc-key", "payroll SSN last-4 runs on the derived development key"),
            new Var("PLAID_TOKEN_ENC_KEY", "plaid.token-enc-key", "Bank Sync tokens"),
            new Var("PLAID_CLIENT_ID", "plaid.client-id", "Bank Sync"),
            new Var("PLAID_SECRET", "plaid.secret", "Bank Sync (production)"),
            new Var("PLATFORM_STRIPE_SECRET_KEY", "platform.stripe.secret-key", "invoice online payment"),
            new Var("PLATFORM_STRIPE_PUBLISHABLE_KEY", "platform.stripe.publishable-key", "invoice online payment"),
            new Var("PLATFORM_STRIPE_WEBHOOK_SECRET", "platform.stripe.webhook-secret", "invoice payment confirmation"),
            new Var("TWILIO_ACCOUNT_SID", "twilio.account-sid", "SMS"),
            new Var("TWILIO_AUTH_TOKEN", "twilio.auth-token", "SMS"),
            new Var("OPENAI_API_KEY", "openai.api.key", "voice, AI search, scanners"),
            new Var("CG_MAIL_PASSWORD", "spring.mail.password", "outbound email"),
            new Var("PUSH_VAPID_PRIVATE_KEY", "push.vapid.private-key", "push notifications"));

    static final List<Var> OPTIONAL = List.of(
            new Var("RECAPTCHA_SITE_KEY", "public.forms.recaptcha.site-key", "public-form reCAPTCHA"),
            new Var("RECAPTCHA_SECRET", "public.forms.recaptcha.secret", "public-form reCAPTCHA"),
            new Var("GEOIP_DB_PATH", "geoip.database-path", "login geolocation"),
            new Var("PLATFORM_STRIPE_EXCLUDED_METHODS", "platform.stripe.excluded-methods", "payment-method exclusions"),
            new Var("PLAID_SANDBOX_SECRET", "plaid.sandbox-secret", "Bank Sync sandbox (trials)"));

    static final List<Var> ROTATION_ONLY = List.of(
            new Var("PLAID_TOKEN_ENC_KEY_PREVIOUS", "plaid.token-enc-key-previous", "Bank Sync token key rotation"),
            new Var("PAYROLL_SSN_ENC_KEY_PREVIOUS", "payroll.ssn-enc-key-previous", "payroll SSN last-4 key rotation"));

    private final Environment env;

    public SecretsStartupReport(Environment env) {
        this.env = env;
    }

    @PostConstruct
    void report() {
        boolean prod = env.acceptsProfiles(Profiles.of("prod"));

        // Required. Touching EncryptionUtil initialises it now; it logs its own detail line.
        if (EncryptionUtil.isKeyFromEnvironment()) {
            log.info("[Secrets] set      CGP_CID_KEY (required)");
        } else if (prod) {
            log.error("[Secrets] MISSING  CGP_CID_KEY (required) — refusing to start in prod");
            throw new IllegalStateException(
                    "CGP_CID_KEY is not set. Set a 16-character CGP_CID_KEY as an Azure application setting; "
                    + "the application does not run on the built-in legacy key in production.");
        } else {
            log.error("[Secrets] MISSING  CGP_CID_KEY (required) — running on the legacy built-in key. "
                    + "Set a 16-character CGP_CID_KEY as an OS environment variable / Azure application setting.");
        }

        for (Var v : EXPECTED) {
            if (!prod && v.name().endsWith("_PROD")) continue;   // profile file supplies the datasource outside prod
            if (isSet(v)) log.info("[Secrets] set      {}", v.name());
            else log.warn("[Secrets] MISSING  {} — {}", v.name(), v.note());
        }
        for (Var v : OPTIONAL) {
            log.info("[Secrets] {}  {} (optional — {})", isSet(v) ? "set    " : "not set", v.name(), v.note());
        }
        for (Var v : ROTATION_ONLY) {
            log.info("[Secrets] {}  {} (rotation-only — {})", isSet(v) ? "set    " : "not set", v.name(), v.note());
        }
    }

    /** Present means the mapped property resolves to a non-blank value; the value itself is never kept or logged. */
    private boolean isSet(Var v) {
        String value = env.getProperty(v.property());
        return value != null && !value.isBlank();
    }
}
