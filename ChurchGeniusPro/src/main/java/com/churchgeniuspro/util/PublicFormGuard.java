package com.churchgeniuspro.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Anti-spam / anti-bot protection for the PUBLIC forms (Connect With Us and
 * Public Prayer Request). Layers:
 *
 * <ol>
 *   <li><b>Honeypot</b> — hidden {@code website} field; bots fill it, humans
 *       never see it. Callers should pretend success when tripped.</li>
 *   <li><b>Time-trap token</b> — the page receives an encrypted token from
 *       {@code /api/public/engage-info}; submissions must return it, and it
 *       must be at least {@value #MIN_FORM_SECONDS}s and at most
 *       {@value #MAX_FORM_HOURS}h old. Instant bot posts fail.</li>
 *   <li><b>Rate limiting</b> — sliding window per client IP per form
 *       (max {@value #MAX_PER_WINDOW} per {@value #WINDOW_MINUTES} min) plus an
 *       hourly cap. In-memory (single-instance deployment).</li>
 *   <li><b>Server-side validation</b> — email/phone shape, length caps, and a
 *       link-spam check for free-text fields.</li>
 *   <li><b>Optional Google reCAPTCHA</b> — enabled automatically when
 *       {@code public.forms.recaptcha.secret} is configured; the page then
 *       renders the widget (site key exposed via engage-info) and the server
 *       verifies the token.</li>
 * </ol>
 */
@Component
public class PublicFormGuard {

    private static final Logger LOG = LoggerFactory.getLogger(PublicFormGuard.class);

    public static final int MIN_FORM_SECONDS = 3;
    public static final int MAX_FORM_HOURS   = 2;
    public static final int MAX_PER_WINDOW   = 5;
    public static final int WINDOW_MINUTES   = 10;
    public static final int MAX_PER_HOUR     = 15;

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern URL_IN_TEXT = Pattern.compile("https?://", Pattern.CASE_INSENSITIVE);
    private static final String TOKEN_PREFIX = "PFT";

    /** ip+form → submission timestamps (ms). */
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    @Value("${public.forms.recaptcha.secret:}")
    private String recaptchaSecret;

    @Value("${public.forms.recaptcha.site-key:}")
    private String recaptchaSiteKey;

    // ── honeypot ──────────────────────────────────────────────────────────

    /** True when the hidden honeypot field was filled — treat as a bot. */
    public boolean isHoneypotTripped(Map<String, Object> body) {
        Object v = body == null ? null : body.get("website");
        return v != null && !v.toString().isBlank();
    }

    // ── time-trap token ───────────────────────────────────────────────────
    //
    // Tokens are HMAC-signed with a key generated when this process starts. They
    // live for at most MAX_FORM_HOURS, so nothing needs to survive a restart (a
    // visitor with a form open across one just reloads the page), and nothing
    // about them can be minted from a copy of the JAR — which was the case while
    // they were AES under EncryptionUtil's built-in fallback key (security audit P10).

    private static final java.security.SecureRandom TOKEN_RANDOM = new java.security.SecureRandom();
    private final byte[] tokenKey = newTokenKey();

    private static byte[] newTokenKey() {
        byte[] k = new byte[32];
        TOKEN_RANDOM.nextBytes(k);
        return k;
    }

    private java.util.function.LongSupplier clock = System::currentTimeMillis;

    /** Test seam — the clock token ages are measured against. */
    public void setClock(java.util.function.LongSupplier clock) {
        this.clock = clock != null ? clock : System::currentTimeMillis;
    }

    private String sign(String payload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(tokenKey, "HmacSHA256"));
            return java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    /** Issues a signed form token bound to the (public-link) cid param. */
    public String issueToken(String cid) {
        try {
            String payload = TOKEN_PREFIX + "|" + safe(cid) + "|" + clock.getAsLong();
            String body = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return body + "." + sign(payload);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Validates the returned token. Returns an error message, or null when OK.
     * A missing token is rejected (all legitimate pages receive one).
     */
    public String checkToken(String token, String cid) {
        if (token == null || token.isBlank()) return "Invalid submission. Please reload the page and try again.";
        String plain;
        try {
            String t = token.trim();
            int dot = t.indexOf('.');
            if (dot <= 0) return "Invalid submission. Please reload the page and try again.";
            plain = new String(java.util.Base64.getUrlDecoder().decode(t.substring(0, dot)),
                               java.nio.charset.StandardCharsets.UTF_8);
            byte[] expected = sign(plain).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] given    = t.substring(dot + 1).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (!java.security.MessageDigest.isEqual(expected, given)) {
                return "Invalid submission. Please reload the page and try again.";
            }
        } catch (Exception e) {
            return "Invalid submission. Please reload the page and try again.";
        }
        String[] parts = plain.split("\\|");
        if (parts.length != 3 || !TOKEN_PREFIX.equals(parts[0]) || !safe(cid).equals(parts[1])) {
            return "Invalid submission. Please reload the page and try again.";
        }
        long issued;
        try { issued = Long.parseLong(parts[2]); } catch (NumberFormatException e) { return "Invalid submission."; }
        long age = clock.getAsLong() - issued;
        if (age < MIN_FORM_SECONDS * 1000L) return "Please take a moment to review your details, then submit again.";
        if (age > MAX_FORM_HOURS * 3600_000L) return "This page has expired. Please reload it and try again.";
        return null;
    }

    // ── rate limiting ─────────────────────────────────────────────────────

    /**
     * Sliding-window rate limit per client IP per form. Returns an error
     * message when over the limit, or null when allowed (and records the hit).
     */
    public synchronized String checkRate(String ip, String form) {
        long now = System.currentTimeMillis();
        String key = safe(ip) + "|" + safe(form);
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() > 3600_000L) q.pollFirst();
        long inWindow = q.stream().filter(t -> now - t <= WINDOW_MINUTES * 60_000L).count();
        if (inWindow >= MAX_PER_WINDOW || q.size() >= MAX_PER_HOUR) {
            return "Too many submissions. Please try again later.";
        }
        q.addLast(now);
        if (hits.size() > 10_000) hits.clear();   // safety valve
        return null;
    }

    // ── validation ────────────────────────────────────────────────────────

    /** Returns an error for a malformed (non-blank) email; null when OK/blank. */
    public String checkEmail(String email) {
        if (email == null || email.isBlank()) return null;
        if (email.length() > 200 || !EMAIL.matcher(email.trim()).matches()) {
            return "Please enter a valid email address.";
        }
        return null;
    }

    /** Returns an error for a malformed (non-blank) phone; null when OK/blank. */
    public String checkPhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() < 7 || digits.length() > 15) return "Please enter a valid phone number.";
        return null;
    }

    /**
     * Link-spam / length check for a free-text field. Returns an error when the
     * text is too long or contains more than {@code maxLinks} URLs.
     */
    public String checkText(String text, int maxLen, int maxLinks) {
        if (text == null) return null;
        if (text.length() > maxLen) return "Your message is too long.";
        int links = 0;
        var m = URL_IN_TEXT.matcher(text);
        while (m.find()) links++;
        if (links > maxLinks) return "Links are not allowed in this form.";
        return null;
    }

    // ── optional reCAPTCHA ────────────────────────────────────────────────

    public boolean captchaEnabled() {
        return recaptchaSecret != null && !recaptchaSecret.isBlank();
    }

    public String captchaSiteKey() {
        return recaptchaSiteKey == null || recaptchaSiteKey.isBlank() ? null : recaptchaSiteKey;
    }

    /** Verifies a reCAPTCHA token (only called when enabled). Error or null. */
    public String checkCaptcha(String token, String ip) {
        if (!captchaEnabled()) return null;
        if (token == null || token.isBlank()) return "Please complete the CAPTCHA.";
        try {
            String form = "secret=" + URLEncoder.encode(recaptchaSecret, StandardCharsets.UTF_8)
                    + "&response=" + URLEncoder.encode(token, StandardCharsets.UTF_8)
                    + (ip != null ? "&remoteip=" + URLEncoder.encode(ip, StandardCharsets.UTF_8) : "");
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.google.com/recaptcha/api/siteverify"))
                    .timeout(Duration.ofSeconds(6))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofString());
            boolean ok = resp.statusCode() == 200 && resp.body() != null
                    && (resp.body().contains("\"success\": true") || resp.body().contains("\"success\":true"));
            if (ok) return null;
            return "CAPTCHA verification failed. Please try again.";
        } catch (Exception e) {
            LOG.warn("[PublicFormGuard] captcha verify error: {}", e.toString());
            return null;   // fail open — other layers still protect
        }
    }

    /** Best-effort client IP (honours X-Forwarded-For behind a proxy). */
    public static String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) return xf.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }
}
