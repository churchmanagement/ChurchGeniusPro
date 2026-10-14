package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.StripeSettings;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
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

    private Transport transport = new Transport() {
        private final RestTemplate restTemplate = new RestTemplate();
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
        } catch (HttpClientErrorException ex) {
            // Stripe returns 4xx with a JSON error body — parse it via a fresh exchange on the error response
            return Map.of("error", Map.of("message", ex.getStatusText()));
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
        } catch (HttpClientErrorException ex) {
            return Map.of("error", Map.of("message", ex.getStatusText()));
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
        } catch (HttpClientErrorException ex) {
            // The HTTP status is kept so the caller can tell "the key may not read
            // charges / balance transactions" (401/403 — a restricted key) from "no such
            // charge" (404). Stripe's own message is safe to surface; the key never is.
            return Map.of("error", Map.of("message", ex.getStatusText(), "status", ex.getStatusCode().value()));
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
