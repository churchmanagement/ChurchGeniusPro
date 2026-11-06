package com.churchgeniuspro.ai.consent;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * App Store readiness D6 — one-time voice tokens that let the server (not the client) decide
 * that a follow-up text request came from speech.
 *
 * <p>{@code /api/voice/command} issues a token ONLY after the request passed the Voice consent
 * check in the app ({@code AiGuardFilter} sets {@link #ISSUE_ATTR}). The token is bound to:
 * the HTTP session, the church, the individual login, the voice operation (the recorder's
 * context: home / nav / help / meeting) and a SHA-256 of the normalised transcript. A
 * follow-up ({@code /api/ai-assist}, {@code /api/help/assist}, {@code /api/voice/extract})
 * counts as voice only when it presents the token for the SAME session, church, login, an
 * allowed operation and the SAME text; the token is removed atomically on that use.
 * Expired (5 min), reused, unknown or mismatched tokens are refused.
 *
 * <p>Held in memory: one-time use is guaranteed by an atomic {@code remove}, which a
 * session attribute (spring-session JDBC copies per request) cannot guarantee under
 * concurrency. Single instance only, like the AI rate limiter; a restart drops tokens, and a
 * follow-up without a valid token simply needs the Assistant notice (fails safe).
 */
@Component
public class AiVoiceTokens {

    public static final String HEADER = "X-CGP-Voice-Token";
    /** Request attribute set by the filter when the Voice consent passed for /api/voice/command. */
    public static final String ISSUE_ATTR = "cgp.voiceTokenAllowed";
    public static final long TTL_MILLIS = 5 * 60 * 1000L;
    static final int MAX_LIVE = 20_000;

    public enum Result { OK, UNKNOWN, EXPIRED, REUSED, MISMATCH }

    record Entry(String sessionId, String clientId, Integer loginId, String op, String textHash, long expiresAt) {}

    private final Map<String, Entry> live = new ConcurrentHashMap<>();
    /** Used tokens, kept until their expiry so a replay is reported as REUSED. */
    private final Map<String, Long> used = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final java.util.function.LongSupplier clock;

    public AiVoiceTokens() { this(System::currentTimeMillis); }

    AiVoiceTokens(java.util.function.LongSupplier clock) { this.clock = clock; }

    /** Normalised for comparison: letters and digits only, single spaces, lower case. */
    static String normalise(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalise(text).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String issue(String sessionId, String clientId, Integer loginId, String op, String transcript) {
        if (sessionId == null || clientId == null || loginId == null || op == null) return null;
        if (normalise(transcript).isEmpty()) return null;
        sweep();
        byte[] b = new byte[24];
        random.nextBytes(b);
        String token = "vt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        live.put(token, new Entry(sessionId, clientId, loginId, op.toLowerCase(Locale.ROOT), hash(transcript),
                clock.getAsLong() + TTL_MILLIS));
        return token;
    }

    /**
     * Checks and CONSUMES the token. A token whose binding does not match is consumed too, so
     * a stolen or misdirected token cannot be retried with other text.
     */
    public Result consume(String token, String sessionId, String clientId, Integer loginId, Set<String> allowedOps, String text) {
        if (token == null || token.isBlank()) return Result.UNKNOWN;
        long now = clock.getAsLong();
        Entry e = live.remove(token);
        if (e == null) {
            Long until = used.get(token);
            return until != null && until > now ? Result.REUSED : Result.UNKNOWN;
        }
        used.put(token, e.expiresAt());
        if (e.expiresAt() <= now) return Result.EXPIRED;
        if (!e.sessionId().equals(sessionId) || !e.clientId().equals(clientId) || !e.loginId().equals(loginId)
                || allowedOps == null || !allowedOps.contains(e.op()) || !e.textHash().equals(hash(text))) {
            return Result.MISMATCH;
        }
        return Result.OK;
    }

    private void sweep() {
        long now = clock.getAsLong();
        if (live.size() > MAX_LIVE / 2) {
            for (Iterator<Map.Entry<String, Entry>> it = live.entrySet().iterator(); it.hasNext(); ) {
                if (it.next().getValue().expiresAt() <= now) it.remove();
            }
        }
        if (used.size() > MAX_LIVE / 2) used.values().removeIf(t -> t <= now);
        if (live.size() >= MAX_LIVE) live.clear();   // hard cap: fail safe (follow-ups then need the Assistant notice)
        if (used.size() >= MAX_LIVE) used.clear();
    }

    int liveCount() { return live.size(); }
}
