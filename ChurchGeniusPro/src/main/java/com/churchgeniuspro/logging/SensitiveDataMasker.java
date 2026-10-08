package com.churchgeniuspro.logging;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts credentials from text on its way into a log file.
 *
 * <p>This is the last line of defence, not the first. The right fix for a secret in a log
 * is to not pass it to the logger — but a codebase this size has 90+ controllers, and any
 * one of them can log a request body, an exception message from an HTTP client, or a
 * {@code Map} that happens to contain a password field. This class means that when that
 * happens, the value does not reach disk.
 *
 * <p>What is redacted:
 * <ul>
 *   <li>password / passwd / pwd / newPassword / currentPassword / demoPassword</li>
 *   <li>token / accessToken / refreshToken / idToken / rememberToken / csrf</li>
 *   <li>secret / clientSecret / apiKey / api-key / privateKey / encKey</li>
 *   <li>{@code Authorization: Bearer …} and {@code Basic …} header values</li>
 *   <li>{@code Cookie} / {@code Set-Cookie} header values, {@code JSESSIONID}, {@code SESSION}</li>
 *   <li>BCrypt hashes ({@code $2a$…}) — a hash is still a credential</li>
 *   <li>Vendor key shapes: OpenAI {@code sk-…}, Twilio {@code SK…}/{@code AC…}, Plaid
 *       {@code access-sandbox-…}/{@code access-production-…}</li>
 * </ul>
 *
 * <p>Both {@code key=value} and {@code "key":"value"} forms are handled, so it works on
 * query strings, form bodies, JSON payloads and {@code Map.toString()} output alike.
 *
 * <p><b>Performance, and the correctness trap in it.</b> The patterns are split in two.
 * <em>Field-name</em> patterns ({@code password=…}) only run when the line contains one of the
 * trigger words, found by a single lower-cased scan — that keeps ordinary log lines at one
 * pass over the string. <em>Value-shape</em> patterns ({@code $2a$…}, {@code sk-…}, a Twilio
 * SID) run <b>unconditionally</b>: a bare key can appear with no field name anywhere near it,
 * so gating them on a trigger word would leave exactly the gap they exist to close. They all
 * begin with a literal prefix, so the regex engine rejects a non-matching line almost
 * immediately.
 */
public final class SensitiveDataMasker {

    private SensitiveDataMasker() {}

    /** What a redacted value is replaced with. */
    public static final String REDACTED = "***REDACTED***";

    /**
     * Lower-case trigger words. If a line contains none of these it cannot match any
     * pattern below, so the regex work is skipped entirely.
     */
    private static final String[] TRIGGERS = {
            "password", "passwd", "pwd", "token", "secret", "apikey", "api-key", "api_key",
            "authorization", "cookie", "jsessionid", "session=", "credential", "privatekey",
            "private-key", "enckey", "enc-key", "$2a$", "$2b$", "$2y$", "sk-", "access-sandbox-",
            "access-production-", "bearer", "basic ", "vapid", "auth-token", "authtoken"
    };

    /** Field names treated as secret in {@code key=value} / {@code "key":"value"} forms. */
    private static final String KEY_ALTERNATION =
            "(?:new|current|old|confirm|demo|user|admin|db|mail|plaid|twilio|openai|push)?"
          + "[-_]?"
          + "(?:password|passwd|pwd|token|secret|apikey|api[-_]?key|private[-_]?key|"
          + "enc[-_]?key|credential|auth[-_]?token|client[-_]?secret|remember[-_]?token|"
          + "access[-_]?token|refresh[-_]?token|id[-_]?token|session[-_]?id|csrf|"
          + "member[-_]?ref|join[-_]?code|invite[-_]?token|pickup[-_]?token|registration[-_]?token)";

    /**
     * Start/end guards instead of {@code \b}.
     *
     * <p>{@code \b} does not fire between {@code _} and a letter, because {@code _} is a word
     * character — so {@code \bENC_KEY\b} silently fails to match inside
     * {@code PLAID_TOKEN_ENC_KEY}, which is exactly how secrets are named in this codebase's
     * environment variables. These guards treat {@code _} and {@code -} as separators.
     */
    private static final String START_GUARD = "(?<![A-Za-z0-9])";
    private static final String END_GUARD   = "(?![A-Za-z0-9])";

    /** Only run when the line contains a trigger word — see the class javadoc. */
    private static final List<Pattern> FIELD_NAME_PATTERNS = List.of(
            // JSON: "password":"value"   /   "password": "value"
            Pattern.compile("(?i)([\"']" + KEY_ALTERNATION + "[\"']\\s*:\\s*)[\"'][^\"']*[\"']"),
            // JSON with a non-string value: "token": 12345
            Pattern.compile("(?i)([\"']" + KEY_ALTERNATION + "[\"']\\s*:\\s*)(?![\"'])[^,}\\s]+"),
            // key=value  (query strings, form bodies, Map.toString, key: value in prose,
            // and SCREAMING_SNAKE environment variable names)
            Pattern.compile("(?i)(" + START_GUARD + KEY_ALTERNATION + END_GUARD
                          + "\\s*[=:]\\s*)(?![=:])[^,;&)}\\]\\s]+"),
            // Authorization: Bearer xxx  /  Authorization: Basic xxx  /  bare "Bearer xxx"
            Pattern.compile("(?i)((?:authorization\\s*[:=]\\s*)?\\b(?:bearer|basic)\\s+)[A-Za-z0-9._~+/=-]{8,}"),
            // Cookie / Set-Cookie header values (whole value — any cookie may be a session)
            Pattern.compile("(?i)((?:set-)?cookie\\s*[:=]\\s*).+"),
            // Session identifiers wherever they appear
            Pattern.compile("(?i)(" + START_GUARD + "(?:jsessionid|session)\\s*[=:]\\s*)[A-Za-z0-9._-]+")
    );

    /**
     * Always run: a bare key may appear with no field name near it, so a trigger-word gate
     * would defeat the purpose. Each starts with a literal, so non-matching lines are
     * rejected almost immediately.
     */
    private static final List<Pattern> VALUE_SHAPE_PATTERNS = List.of(
            // BCrypt hashes — a stored hash is still a credential
            Pattern.compile("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}"),
            // OpenAI
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{16,}"),
            // Twilio account / API key SIDs
            Pattern.compile("\\b(?:AC|SK)[0-9a-fA-F]{32}\\b"),
            // Plaid access tokens
            Pattern.compile("\\baccess-(?:sandbox|development|production)-[0-9a-zA-Z-]{16,}"),
            // Stripe PaymentIntent / SetupIntent client secrets (before the key shapes below)
            Pattern.compile("\\b(?:pi|seti)_[A-Za-z0-9]{8,}_secret_[A-Za-z0-9]{8,}"),
            // Stripe secret, restricted and publishable keys, and webhook signing secrets
            Pattern.compile("\\b(?:sk|rk|pk)_(?:live|test)_[A-Za-z0-9]{8,}"),
            Pattern.compile("\\bwhsec_[A-Za-z0-9]{8,}")
    );

    /**
     * Returns {@code text} with every recognised credential replaced by {@link #REDACTED}.
     * {@code null} in, {@code null} out. Returns the original instance untouched when nothing
     * matched, so an ordinary log line allocates nothing.
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) return text;

        String result = text;
        if (mightContainSecret(text)) {
            result = applyAll(result, FIELD_NAME_PATTERNS);
        }
        return applyAll(result, VALUE_SHAPE_PATTERNS);
    }

    private static String applyAll(String text, List<Pattern> patterns) {
        String result = text;
        for (int i = 0; i < patterns.size(); i++) {
            Matcher m = patterns.get(i).matcher(result);
            if (!m.find()) continue;
            m.reset();
            // Patterns with a capturing group keep the field name and redact only the value;
            // the value-shape patterns (no group) are replaced whole.
            result = m.groupCount() >= 1
                    ? m.replaceAll("$1" + Matcher.quoteReplacement(REDACTED))
                    : m.replaceAll(Matcher.quoteReplacement(REDACTED));
        }
        return result;
    }

    /** Cheap pre-check: one lower-cased scan instead of six regex passes. */
    private static boolean mightContainSecret(String text) {
        String lower = text.toLowerCase();
        for (String trigger : TRIGGERS) {
            if (lower.contains(trigger)) return true;
        }
        return false;
    }
}
