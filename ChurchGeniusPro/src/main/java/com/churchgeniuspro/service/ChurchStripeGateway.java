package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.StripeSettings;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * The church-side Stripe integration (online giving through the church's OWN
 * connected Stripe account, keyed by {@code stripe_settings}) — the exact REST calls
 * {@code DonationController} has always made, moved here so the Member Portal's
 * Give / Contribute (Phase D) uses the same integration rather than a second one.
 * No SDK: two calls over Spring's RestTemplate, as before.
 *
 * <p>This is the church-side integration only. The separate platform billing
 * integration (Phase 6) has its own keys and code; the two never share either.
 */
@Service
public class ChurchStripeGateway {

    public static final String STRIPE_API_BASE = "https://api.stripe.com/v1";

    /** A Stripe PaymentIntent id, and nothing that could steer the URL it is put into. */
    public static final Pattern PAYMENT_INTENT_ID = Pattern.compile("^pi_[A-Za-z0-9]{1,64}$");

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    /** The HTTP seam, so tests can stand in for Stripe. */
    public interface Transport {
        Map<String, Object> post(String url, HttpHeaders headers, MultiValueMap<String, String> params);
        Map<String, Object> get(String url, HttpHeaders headers);
    }

    /** Error-handling audit 2026-10-28, H5: the default RestTemplate had NO timeouts — a Stripe stall held the donor's request thread forever. */
    static RestTemplate timedRestTemplate() {
        org.springframework.http.client.SimpleClientHttpRequestFactory f = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10_000);
        f.setReadTimeout(30_000);
        return new RestTemplate(f);
    }

    private Transport transport = new Transport() {
        private final RestTemplate restTemplate = timedRestTemplate();
        @Override public Map<String, Object> post(String url, HttpHeaders headers, MultiValueMap<String, String> params) {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(params, headers), MAP_TYPE);
            return resp.getBody() != null ? resp.getBody() : Map.of();
        }
        @Override public Map<String, Object> get(String url, HttpHeaders headers) {
            ResponseEntity<Map<String, Object>> resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), MAP_TYPE);
            return resp.getBody() != null ? resp.getBody() : Map.of();
        }
    };

    /** Test seam. */
    public void setTransport(Transport t) { this.transport = t; }

    /**
     * True when the church can actually take a card payment. Both keys are required:
     * the publishable key for Stripe.js in the browser and the secret key for the
     * server-side PaymentIntent, so a half-finished setup counts as not configured.
     */
    public boolean configured(StripeSettings s) {
        return s != null && !isBlank(s.getPublishableKey()) && !isBlank(s.getSecretKey());
    }

    /** Creates a PaymentIntent exactly as the donation page always has (automatic payment methods). */
    public Map<String, Object> createIntent(String secretKey, long cents, String currency) {
        return createIntent(secretKey, cents, currency, null);
    }

    /**
     * As above, with optional metadata (the Member Portal stamps the member and
     * purpose on the intent so the save step can prove it is theirs).
     */
    public Map<String, Object> createIntent(String secretKey, long cents, String currency, Map<String, String> metadata) {
        HttpHeaders headers = headers(secretKey);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("amount",   String.valueOf(cents));
        params.add("currency", currency);
        params.add("automatic_payment_methods[enabled]", "true");
        if (metadata != null) metadata.forEach((k, v) -> params.add("metadata[" + k + "]", v));
        try {
            return transport.post(STRIPE_API_BASE + "/payment_intents", headers, params);
        } catch (HttpStatusCodeException ex) {
            return errorFrom(ex);
        } catch (ResourceAccessException ex) {
            return transientError(ex);
        }
    }

    /**
     * Retrieves a PaymentIntent with its latest charge — and that charge's balance
     * transaction — expanded. The id must match {@link #PAYMENT_INTENT_ID}.
     *
     * <p>The balance transaction is where Stripe reports the processor fee it kept
     * ({@code fee}) and what the church actually receives ({@code net}); Donation
     * Review posts the fee as an Expense, so it is fetched here, in the same call
     * that already verifies the payment, rather than in a second round trip. For a
     * card payment it is present as soon as the charge succeeds; for an ACH debit
     * that is still pending it is {@code null} until the payment settles, and
     * {@link #feeInfo} returns empty — see {@link DonationFeeService}.
     */
    public Map<String, Object> retrieveIntent(String secretKey, String piId) {
        try {
            return transport.get(STRIPE_API_BASE + "/payment_intents/" + piId
                    + "?expand[]=latest_charge&expand[]=latest_charge.balance_transaction", headers(secretKey));
        } catch (HttpStatusCodeException ex) {
            return errorFrom(ex);
        } catch (ResourceAccessException ex) {
            return transientError(ex);
        }
    }

    /** A Stripe Charge id, and nothing that could steer the URL it is put into. */
    public static final Pattern CHARGE_ID = Pattern.compile("^ch_[A-Za-z0-9]{1,64}$");

    /**
     * Retrieves a Charge with its balance transaction expanded — used to pick up the
     * actual fee of a donation whose fee was unknown when it was saved (an ACH
     * payment that has since settled, or a donation recorded before fees were
     * captured). The id must match {@link #CHARGE_ID}; anything else is refused
     * without touching the network.
     */
    public Map<String, Object> retrieveCharge(String secretKey, String chargeId) {
        if (chargeId == null || !CHARGE_ID.matcher(chargeId).matches()) {
            return Map.of("error", Map.of("message", "Invalid charge id."));
        }
        try {
            return transport.get(STRIPE_API_BASE + "/charges/" + chargeId + "?expand[]=balance_transaction", headers(secretKey));
        } catch (HttpStatusCodeException ex) {
            return errorFrom(ex);
        } catch (ResourceAccessException ex) {
            return transientError(ex);
        }
    }

    /** True when an error map from this gateway is an authentication/permission refusal (401/403). */
    public static boolean isPermissionError(Map<String, Object> reply) {
        Object err = reply == null ? null : reply.get("error");
        if (err instanceof Map<?, ?> e && e.get("status") instanceof Integer code) return code == 401 || code == 403;
        return false;
    }

    private static HttpHeaders headers(String secretKey) {
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer " + secretKey);
        return h;
    }

    public static String errorMessage(Map<String, Object> pi) {
        Object err = pi.get("error");
        if (err instanceof Map<?, ?> e) {
            Object msg = e.get("message");
            if (msg != null) return msg.toString();
        }
        return "A payment error occurred.";
    }

    /** True when the error map came from a timeout / outage / Stripe 5xx — worth a retry, answer 502. */
    public static boolean isTransient(Map<String, Object> pi) {
        Object err = pi.get("error");
        return err instanceof Map<?, ?> e && Boolean.TRUE.equals(e.get("transient"));
    }

    /** The user-facing message for a church's own Stripe setup fault (wrong/revoked key, account restricted). */
    public static final String SETUP_MSG =
            "Online giving is not set up correctly for this church. Please tell the church office.";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChurchStripeGateway.class);

    /**
     * Error-handling audit 2026-10-28, H5. Stripe answers 4xx with a JSON body
     * {@code {"error":{"type","code","message"}}}; the old code threw that away and showed the
     * donor the HTTP reason phrase ("Unauthorized", "Bad Request"). Now: a {@code card_error}
     * is Stripe's own sentence (authored for cardholders); a key/account/request fault is the
     * church's problem, not the donor's, so a fixed sentence goes out and the real text goes to
     * the log; 5xx is transient.
     */
    static Map<String, Object> errorFrom(HttpStatusCodeException ex) {
        String type = null, code = null, message = null;
        try {
            Map<?, ?> body = new com.fasterxml.jackson.databind.ObjectMapper().readValue(ex.getResponseBodyAsString(), Map.class);
            if (body.get("error") instanceof Map<?, ?> e) {
                type = str(e.get("type")); code = str(e.get("code")); message = str(e.get("message"));
            }
        } catch (Exception ignored) { /* not JSON — fall through to the status */ }
        int status = ex.getStatusCode().value();
        Map<String, Object> out;
        if (status >= 500) {
            log.warn("Stripe {} {}: transient", status, type);
            out = transientMap("Stripe is temporarily unavailable. Please try again in a moment.", type, code);
        } else if ("card_error".equals(type) && message != null && !message.isBlank()) {
            out = errorMap(message, type, code, false);
        } else {
            // authentication_error (bad key), invalid_request_error (account restricted, bad params),
            // rate_limit_error, api_error — the donor cannot fix any of these.
            log.warn("Stripe {} type={} code={}: {}", status, type, code, com.churchgeniuspro.util.LogSafe.text(message));
            out = errorMap(SETUP_MSG, type, code, false);
        }
        // the HTTP status stays on the map: DonationFeeService.isPermissionError keys on 401/403
        @SuppressWarnings("unchecked") Map<String, Object> e = (Map<String, Object>) out.get("error");
        e.put("status", status);
        return out;
    }

    static Map<String, Object> transientError(Exception ex) {
        log.warn("Stripe unreachable: {}", com.churchgeniuspro.util.LogSafe.exception(ex));
        return transientMap("Could not reach the payment service. Please try again in a moment.", "api_connection_error", null);
    }

    private static Map<String, Object> transientMap(String message, String type, String code) {
        return errorMap(message, type, code, true);
    }

    private static Map<String, Object> errorMap(String message, String type, String code, boolean transientFlag) {
        Map<String, Object> e = new java.util.LinkedHashMap<>();
        e.put("message", message);
        if (type != null) e.put("type", type);
        if (code != null) e.put("code", code);
        e.put("transient", transientFlag);
        Map<String, Object> wrap = new java.util.LinkedHashMap<>();
        wrap.put("error", e);
        return wrap;
    }

    /**
     * Read-only probe of a secret key — {@code GET /v1/balance} — so a church can check its keys
     * before a donor does (audit 2026-10-28, H5). Returns {@code ok} plus {@code livemode}, or the
     * same error map shape as the payment calls.
     */
    public Map<String, Object> testConnection(String secretKey) {
        try {
            Map<String, Object> bal = transport.get(STRIPE_API_BASE + "/balance", headers(secretKey));
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("ok", true);
            out.put("livemode", Boolean.TRUE.equals(bal.get("livemode")));
            return out;
        } catch (HttpStatusCodeException ex) {
            return errorFrom(ex);
        } catch (ResourceAccessException ex) {
            return transientError(ex);
        }
    }

    /** Charge id and a display label such as "Visa •••• 4242", read off the expanded latest charge. */
    public record ChargeInfo(String chargeId, String paymentMethod) {}

    public static ChargeInfo chargeInfo(Map<String, Object> pi) {
        String chargeId = null;
        String paymentMethod = "Card";
        try {
            Object latestCharge = pi.get("latest_charge");
            if (latestCharge instanceof Map<?, ?> charge) {
                chargeId = str(charge.get("id"));
                Object pmd = charge.get("payment_method_details");
                if (pmd instanceof Map<?, ?> details) {
                    String type = str(details.get("type"));
                    if ("card".equals(type) && details.get("card") instanceof Map<?, ?> card) {
                        String brand = str(card.get("brand"));
                        Object last4 = card.get("last4");
                        if (brand != null) brand = capitalize(brand);
                        paymentMethod = (brand != null ? brand : "Card")
                                + (last4 != null ? " •••• " + last4 : "");
                    } else if ("us_bank_account".equals(type)) {
                        paymentMethod = "Bank Transfer";
                    } else if (type != null) {
                        paymentMethod = capitalize(type.replace("_", " "));
                    }
                }
            }
        } catch (Exception ignored) { /* keep default */ }
        return new ChargeInfo(chargeId, paymentMethod);
    }

    /**
     * The processor fee Stripe kept and the net the church receives, read off an
     * expanded {@code balance_transaction}; both in major units (dollars), exactly
     * converted from Stripe's integer cents. {@code balanceTransactionId} is kept for
     * reconciliation against the Stripe dashboard.
     */
    public record FeeInfo(java.math.BigDecimal fee, java.math.BigDecimal net, String balanceTransactionId) {}

    /**
     * Fee details from a PaymentIntent retrieved by {@link #retrieveIntent}, or empty
     * when the balance transaction is absent or not yet expanded (pending ACH, an
     * older unexpanded fetch, a non-card object shape). Never throws, never guesses:
     * an empty result means "Stripe has not told us the fee", not "the fee is zero".
     */
    public static java.util.Optional<FeeInfo> feeInfo(Map<String, Object> pi) {
        if (pi == null) return java.util.Optional.empty();
        Object latestCharge = pi.get("latest_charge");
        if (!(latestCharge instanceof Map<?, ?> charge)) return java.util.Optional.empty();
        return feeInfoFromCharge(charge);
    }

    /** As {@link #feeInfo}, from a Charge retrieved by {@link #retrieveCharge}. */
    public static java.util.Optional<FeeInfo> feeInfoFromCharge(Map<?, ?> charge) {
        if (charge == null) return java.util.Optional.empty();
        Object bt = charge.get("balance_transaction");
        if (!(bt instanceof Map<?, ?> tx)) return java.util.Optional.empty();   // a bare id string, or null while pending
        Long feeCents = asLong(tx.get("fee"));
        Long netCents = asLong(tx.get("net"));
        if (feeCents == null || netCents == null) return java.util.Optional.empty();
        if (feeCents < 0) return java.util.Optional.empty();                      // a refund/reversal shape — not a donation fee
        java.math.BigDecimal fee = java.math.BigDecimal.valueOf(feeCents).movePointLeft(2).setScale(2);
        java.math.BigDecimal net = java.math.BigDecimal.valueOf(netCents).movePointLeft(2).setScale(2);
        return java.util.Optional.of(new FeeInfo(fee, net, str(tx.get("id"))));
    }

    private static Long asLong(Object o) {
        if (o instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) ? n.longValue() : null;     // Stripe sends integer cents; anything else is not ours
        }
        if (o instanceof String s && s.matches("-?\\d{1,15}")) return Long.parseLong(s);
        return null;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
    private static String str(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }
    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
