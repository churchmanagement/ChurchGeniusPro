package com.churchgeniuspro.service;

import com.churchgeniuspro.logging.SensitiveDataMasker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/**
 * The Service Admin's OWN Stripe account, used only to collect card payments for
 * ChurchGeniusPro invoices (Phase 6). Completely separate from the church donation
 * integration (each church's own keys, used only by the donation code), which this class
 * never reads — StripeSeparationRatchetTest enforces the separation in both directions.
 *
 * <p><b>Keys</b> come only from Azure App Service settings
 * ({@code PLATFORM_STRIPE_SECRET_KEY}, {@code PLATFORM_STRIPE_PUBLISHABLE_KEY},
 * {@code PLATFORM_STRIPE_WEBHOOK_SECRET}); they are never stored in the database. The
 * secret key and webhook secret never leave the server: status shows masked hints only,
 * and every Stripe error text is passed through {@link SensitiveDataMasker} before it is
 * logged or returned. The publishable key is, by Stripe's design, public — Stripe.js needs
 * it in the payer's browser — and is still shown masked on the Service Admin screen.
 *
 * <p>Direct REST calls (no SDK), in the same style as the donation integration.
 *
 * <p><b>Payment methods are managed in the Stripe Dashboard</b> (Settings → Payment methods),
 * not in code: on API version 2026-09-30 and later Stripe refuses {@code payment_method_types},
 * so a PaymentIntent is created without it and the Payment Element shows whatever the
 * Dashboard enables (card, ACH Direct Debit, bank transfer, Link, …). Methods that must
 * never be offered for platform invoices can be listed in {@code PLATFORM_STRIPE_EXCLUDED_METHODS}
 * (comma-separated Stripe type names, e.g. {@code affirm,klarna}). Bank transfer additionally
 * needs a Stripe Customer on the intent, which {@link #createCustomer} provides.
 */
@Service
public class PlatformStripeService {

    private static final Logger log = LoggerFactory.getLogger(PlatformStripeService.class);
    static final String API = "https://api.stripe.com/v1";
    /** Webhook timestamps older (or newer) than this are refused — replay protection. */
    public static final long WEBHOOK_TOLERANCE_SECONDS = 300;
    /** metadata[purpose] on every PaymentIntent this class creates. */
    public static final String PURPOSE = "cgp_platform_invoice";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${platform.stripe.secret-key:}")      private String secretKey;
    @Value("${platform.stripe.publishable-key:}") private String publishableKey;
    @Value("${platform.stripe.webhook-secret:}")  private String webhookSecret;
    /** Comma-separated Stripe payment method types never offered for platform invoices (may be blank). */
    @Value("${platform.stripe.excluded-methods:}") private String excludedMethods;

    /** One HTTP exchange with Stripe; replaceable in tests. */
    public interface StripeHttp {
        Response send(String method, String path, Map<String, String> form, String secretKey, String idempotencyKey) throws Exception;
    }

    public record Response(int status, String body) {}

    private StripeHttp http = new JdkStripeHttp();

    /** Test seams. */
    public void setHttp(StripeHttp http) { this.http = http; }
    public void setKeys(String secret, String publishable, String webhook) {
        this.secretKey = secret; this.publishableKey = publishable; this.webhookSecret = webhook;
    }
    public void setExcludedMethods(String excludedMethods) { this.excludedMethods = excludedMethods; }

    /** The excluded payment method types, normalised (lower case, de-duplicated, order kept). */
    public List<String> excludedMethods() {
        List<String> out = new ArrayList<>();
        if (excludedMethods == null) return out;
        for (String t : excludedMethods.split(",")) {
            String k = t.trim().toLowerCase(Locale.ROOT);
            if (!k.isEmpty() && k.matches("^[a-z0-9_]{2,40}$") && !out.contains(k)) out.add(k);
        }
        return out;
    }

    // ══ Configuration status ═════════════════════════════════════════════

    /** "live", "test", or null when the key is missing or unrecognised. */
    static String modeOf(String key) {
        if (key == null) return null;
        String k = key.trim();
        if (k.matches("^(sk|rk|pk)_live_[A-Za-z0-9]+$")) return "live";
        if (k.matches("^(sk|rk|pk)_test_[A-Za-z0-9]+$")) return "test";
        return null;
    }

    /** Problems that make card payment unavailable; empty when ready. */
    public List<String> problems() {
        List<String> p = new ArrayList<>();
        String s = trim(secretKey), pk = trim(publishableKey);
        if (s == null) p.add("PLATFORM_STRIPE_SECRET_KEY is not set.");
        else if (!s.startsWith("sk_") && !s.startsWith("rk_") || modeOf(s) == null) p.add("PLATFORM_STRIPE_SECRET_KEY is not a Stripe secret key (sk_live_… / sk_test_…).");
        if (pk == null) p.add("PLATFORM_STRIPE_PUBLISHABLE_KEY is not set.");
        else if (!pk.startsWith("pk_") || modeOf(pk) == null) p.add("PLATFORM_STRIPE_PUBLISHABLE_KEY is not a Stripe publishable key (pk_live_… / pk_test_…).");
        if (p.isEmpty() && !Objects.equals(modeOf(s), modeOf(pk))) {
            p.add("The secret key and the publishable key are for different modes (one test, one live).");
        }
        return p;
    }

    /** Ready to take card payments (secret + publishable keys valid and in the same mode). */
    public boolean isConfigured() { return problems().isEmpty(); }

    public boolean webhookConfigured() {
        String w = trim(webhookSecret);
        return w != null && w.startsWith("whsec_") && w.length() > 10;
    }

    public boolean isLive() { return "live".equals(modeOf(trim(secretKey))); }

    /** The publishable key, for Stripe.js on the invoice page (public by design). Null when not configured. */
    public String publishableKey() { return isConfigured() ? trim(publishableKey) : null; }

    /** What the Service Admin Stripe card shows: status and masked hints only. Never a key. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", isConfigured());
        m.put("mode", isConfigured() ? modeOf(trim(secretKey)) : null);
        m.put("secretKeyHint", mask(secretKey));
        m.put("publishableKeyHint", mask(publishableKey));
        m.put("webhookConfigured", webhookConfigured());
        m.put("webhookSecretHint", mask(webhookSecret));
        m.put("problems", problems());
        m.put("excludedMethods", excludedMethods());
        if (!webhookConfigured()) {
            m.put("webhookNote", "PLATFORM_STRIPE_WEBHOOK_SECRET is not set: card payments are still recorded when the payer's "
                    + "browser confirms, but ACH Direct Debit and bank transfer payments settle days later and are ONLY "
                    + "recorded by the webhook — set it before enabling those methods in the Stripe Dashboard.");
        }
        return m;
    }

    /** "sk_live_…4f2a" — the prefix and the last four characters, never more. */
    static String mask(String key) {
        String k = trim(key);
        if (k == null) return null;
        String prefix;
        int i = k.indexOf("_live_"), j = k.indexOf("_test_");
        if (i > 0) prefix = k.substring(0, i + 6);
        else if (j > 0) prefix = k.substring(0, j + 6);
        else if (k.startsWith("whsec_")) prefix = "whsec_";
        else prefix = "";
        return k.length() <= prefix.length() + 8 ? prefix + "…" : prefix + "…" + k.substring(k.length() - 4);
    }

    /**
     * Read-only connectivity check: {@code GET /v1/balance}. Returns whether the key works
     * and its mode — no balances, no key material.
     */
    public Map<String, Object> testConnection() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!isConfigured()) {
            m.put("ok", false);
            m.put("message", String.join(" ", problems()));
            return m;
        }
        try {
            Response r = http.send("GET", "/balance", null, trim(secretKey), null);
            JsonNode body = JSON.readTree(r.body() == null ? "{}" : r.body());
            if (r.status() == 200) {
                m.put("ok", true);
                m.put("livemode", body.path("livemode").asBoolean(false));
                m.put("message", "Connected to Stripe (" + (body.path("livemode").asBoolean(false) ? "live" : "test") + " mode).");
            } else {
                m.put("ok", false);
                m.put("message", "Stripe refused the request: " + safeError(body, r.status()));
            }
        } catch (Exception e) {
            m.put("ok", false);
            m.put("message", "Could not reach Stripe: " + SensitiveDataMasker.mask(String.valueOf(e.getMessage())));
        }
        return m;
    }

    // ══ PaymentIntents ═══════════════════════════════════════════════════

    /**
     * A PaymentIntent as far as billing needs it. {@code clientSecret} is never logged.
     * {@code paymentMethodType} is Stripe's type of the method actually used ({@code card},
     * {@code us_bank_account}, {@code customer_balance}, {@code link}, …) when known — it is
     * known on a retrieve (the payment method is expanded) and on a webhook only when Stripe
     * embedded it; otherwise null. {@code instructionsUrl} is Stripe's hosted page with the
     * bank transfer details while a bank transfer is awaited, else null.
     */
    public record Intent(String id, String status, long amount, long amountReceived, String currency,
                         Map<String, String> metadata, boolean livemode, String clientSecret,
                         String paymentMethodType, String instructionsUrl, String lastErrorMessage) {
        public Intent(String id, String status, long amount, long amountReceived, String currency,
                      Map<String, String> metadata, boolean livemode, String clientSecret) {
            this(id, status, amount, amountReceived, currency, metadata, livemode, clientSecret, null, null, null);
        }
        @Override public String toString() {   // keep the client secret out of any accidental log line
            return "Intent[" + id + ", " + status + ", " + amount + " " + currency + "]";
        }
    }

    /** Raised for any Stripe failure; the message is already masked. */
    public static class StripeException extends RuntimeException {
        public StripeException(String message) { super(message); }
    }

    /** Stripe answered 404: the object (PaymentIntent, Customer) does not exist for this key/mode. */
    public static class StripeNotFoundException extends StripeException {
        public StripeNotFoundException(String message) { super(message); }
    }

    /**
     * Creates a PaymentIntent for exactly {@code amountCents} USD, offering the payment
     * methods enabled in the Stripe Dashboard minus {@link #excludedMethods()}. With a
     * Stripe Customer ({@code cus_…}) the intent can also be paid by bank transfer. The
     * idempotency key makes a retried or concurrent create return the same intent; a short
     * hash of the request parameters is appended to it, because Stripe keeps a key for 24 h
     * and refuses it with a different body ({@code idempotency_error}) — so any change to
     * what is sent (a code change, a Customer attached, an excluded method) gets a fresh
     * key instead of locking the invoice out for a day, while identical concurrent calls
     * still collapse onto one intent.
     */
    public Intent createIntent(long invoiceId, String invoiceNumber, long amountCents, String customerId, String idempotencyKey) {
        requireConfigured();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("amount", String.valueOf(amountCents));
        form.put("currency", "usd");
        if (customerId != null) form.put("customer", safeCustomerId(customerId));
        List<String> excluded = excludedMethods();
        for (int i = 0; i < excluded.size(); i++) form.put("excluded_payment_method_types[" + i + "]", excluded.get(i));
        form.put("description", "ChurchGeniusPro invoice " + invoiceNumber);
        form.put("metadata[purpose]", PURPOSE);
        form.put("metadata[invoice_id]", String.valueOf(invoiceId));
        form.put("metadata[invoice_number]", invoiceNumber);
        String key = idempotencyKey == null ? null : idempotencyKey + "-" + paramsHash(form);
        return intentCall("POST", "/payment_intents", form, key, "create");
    }

    /** First 8 hex chars of SHA-256 over the form's {@code key=value} pairs in order. */
    static String paramsHash(Map<String, String> form) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, String> e : form.entrySet()) {
                md.update((e.getKey() + "=" + e.getValue() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(md.digest()).substring(0, 8);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    /** @see #createIntent(long, String, long, String, String) — without a Customer (no bank transfer). */
    public Intent createIntent(long invoiceId, String invoiceNumber, long amountCents, String idempotencyKey) {
        return createIntent(invoiceId, invoiceNumber, amountCents, null, idempotencyKey);
    }

    /** Re-reads a PaymentIntent with its payment method expanded (so the method used is known). */
    public Intent retrieveIntent(String paymentIntentId) {
        requireConfigured();
        return intentCall("GET", "/payment_intents/" + safeId(paymentIntentId) + "?expand[]=payment_method", null, null, "retrieve");
    }

    /**
     * Creates the Stripe Customer that bank transfers require (one per client, created on
     * first use). Returns the {@code cus_…} id. Nothing but the church name, billing email
     * and client id goes to Stripe.
     */
    public String createCustomer(String name, String email, String clientId) {
        requireConfigured();
        Map<String, String> form = new LinkedHashMap<>();
        if (name != null && !name.isBlank()) form.put("name", name.trim());
        if (email != null && !email.isBlank()) form.put("email", email.trim());
        form.put("description", "ChurchGeniusPro client " + clientId);
        form.put("metadata[purpose]", PURPOSE);
        form.put("metadata[client_id]", clientId);
        JsonNode body = call("POST", "/customers", form, "cgp-cus-" + clientId, "customer create");
        String id = body.path("id").asText(null);
        if (id == null || !id.matches("^cus_[A-Za-z0-9]{8,64}$")) throw new StripeException("Stripe returned no customer id.");
        return id;
    }

    /** Cancels an open PaymentIntent (best effort, used when an invoice is voided). */
    public Intent cancelIntent(String paymentIntentId) {
        requireConfigured();
        return intentCall("POST", "/payment_intents/" + safeId(paymentIntentId) + "/cancel", Map.of(), null, "cancel");
    }

    private Intent intentCall(String method, String path, Map<String, String> form, String idem, String what) {
        return toIntent(call(method, path, form, idem, what));
    }

    private JsonNode call(String method, String path, Map<String, String> form, String idem, String what) {
        Response r;
        try {
            r = http.send(method, path, form, trim(secretKey), idem);
        } catch (Exception e) {
            throw new StripeException("Could not reach Stripe (" + what + "): " + SensitiveDataMasker.mask(String.valueOf(e.getMessage())));
        }
        JsonNode body;
        try {
            body = JSON.readTree(r.body() == null ? "{}" : r.body());
        } catch (Exception e) {
            throw new StripeException("Stripe returned an unreadable response (" + what + ", HTTP " + r.status() + ").");
        }
        if (r.status() == 404) {
            throw new StripeNotFoundException("Stripe refused the " + what + ": " + safeError(body, r.status()));
        }
        if (r.status() < 200 || r.status() >= 300) {
            String type = body.path("error").path("type").asText("");
            String prefix = "idempotency_error".equals(type) ? "Stripe refused the " + what + " (idempotency key reused with a different request): "
                                                            : "Stripe refused the " + what + ": ";
            throw new StripeException(prefix + safeError(body, r.status()));
        }
        return body;
    }

    static Intent toIntent(JsonNode o) {
        Map<String, String> meta = new LinkedHashMap<>();
        o.path("metadata").fields().forEachRemaining(e -> meta.put(e.getKey(), e.getValue().asText()));
        JsonNode pm = o.path("payment_method");
        String type = pm.isObject() ? pm.path("type").asText(null) : null;
        if (type == null && o.path("payment_method_types").isArray() && o.path("payment_method_types").size() == 1
                && pm.isTextual()) {
            type = o.path("payment_method_types").get(0).asText(null);   // a single offered type ⇒ the one used
        }
        String instructions = o.path("next_action").path("display_bank_transfer_instructions").path("hosted_instructions_url").asText(null);
        String lastError = o.path("last_payment_error").path("message").asText(null);
        return new Intent(o.path("id").asText(null), o.path("status").asText(null), o.path("amount").asLong(0),
                o.path("amount_received").asLong(0), o.path("currency").asText(null), meta,
                o.path("livemode").asBoolean(false), o.path("client_secret").asText(null),
                type, instructions, lastError == null ? null : SensitiveDataMasker.mask(lastError));
    }

    private void requireConfigured() {
        if (!isConfigured()) throw new StripeException("Card payment is not available (platform Stripe is not configured).");
    }

    private static String safeId(String id) {
        if (id == null || !id.matches("^pi_[A-Za-z0-9]{8,64}$")) throw new StripeException("Invalid PaymentIntent id.");
        return id;
    }

    private static String safeCustomerId(String id) {
        if (id == null || !id.matches("^cus_[A-Za-z0-9]{8,64}$")) throw new StripeException("Invalid Customer id.");
        return id;
    }

    private static String safeError(JsonNode body, int status) {
        String msg = body.path("error").path("message").asText("");
        return SensitiveDataMasker.mask(msg.isBlank() ? "HTTP " + status : msg);
    }

    // ══ Webhooks ═════════════════════════════════════════════════════════

    /** A verified webhook event. */
    public record WebhookEvent(String id, String type, boolean livemode, JsonNode object) {}

    public static class WebhookSignatureException extends RuntimeException {
        public WebhookSignatureException(String message) { super(message); }
    }

    /**
     * Verifies a {@code Stripe-Signature} header ({@code t=…,v1=…}) over the raw body with
     * the webhook signing secret (HMAC-SHA256 of {@code t + "." + body}), compares in
     * constant time, and refuses timestamps outside ±{@link #WEBHOOK_TOLERANCE_SECONDS}.
     */
    public WebhookEvent verifyWebhook(String payload, String signatureHeader, long nowEpochSeconds) {
        if (!webhookConfigured()) throw new WebhookSignatureException("webhook secret not configured");
        if (payload == null || signatureHeader == null || signatureHeader.isBlank()) {
            throw new WebhookSignatureException("missing signature");
        }
        long t = -1;
        List<String> v1 = new ArrayList<>();
        for (String part : signatureHeader.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            if ("t".equals(kv[0])) {
                try { t = Long.parseLong(kv[1].trim()); } catch (NumberFormatException ignored) { }
            } else if ("v1".equals(kv[0])) {
                v1.add(kv[1].trim());
            }
        }
        if (t < 0 || v1.isEmpty()) throw new WebhookSignatureException("malformed signature header");
        if (Math.abs(nowEpochSeconds - t) > WEBHOOK_TOLERANCE_SECONDS) throw new WebhookSignatureException("timestamp outside tolerance");
        byte[] expected = hmacHex(trim(webhookSecret), t + "." + payload).getBytes(StandardCharsets.US_ASCII);
        boolean ok = false;
        for (String sig : v1) {
            ok |= MessageDigest.isEqual(expected, sig.getBytes(StandardCharsets.US_ASCII));
        }
        if (!ok) throw new WebhookSignatureException("signature mismatch");
        try {
            JsonNode e = JSON.readTree(payload);
            return new WebhookEvent(e.path("id").asText(null), e.path("type").asText(null),
                    e.path("livemode").asBoolean(false), e.path("data").path("object"));
        } catch (Exception ex) {
            throw new WebhookSignatureException("unreadable event body");
        }
    }

    static String hmacHex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable");
        }
    }

    private static String trim(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    // ══ HTTP ═════════════════════════════════════════════════════════════

    /** JDK HTTP client; the secret key goes only into the Authorization header. */
    static final class JdkStripeHttp implements StripeHttp {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        @Override
        public Response send(String method, String path, Map<String, String> form, String secretKey, String idem) throws Exception {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + secretKey);
            if (idem != null) b.header("Idempotency-Key", idem);
            if ("GET".equals(method)) {
                b.GET();
            } else {
                StringBuilder sb = new StringBuilder();
                if (form != null) {
                    for (Map.Entry<String, String> e : form.entrySet()) {
                        if (sb.length() > 0) sb.append('&');
                        sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                          .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
                    }
                }
                b.header("Content-Type", "application/x-www-form-urlencoded")
                 .POST(HttpRequest.BodyPublishers.ofString(sb.toString()));
            }
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body());
        }
    }
}
