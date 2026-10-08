package com.churchgeniuspro.membergive;

import com.churchgeniuspro.service.ChurchStripeGateway;
import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;

import java.util.*;

/** An in-memory stand-in for the church's Stripe account: PaymentIntents with a settable status. */
public final class FakeChurchStripe implements ChurchStripeGateway.Transport {
    public final Map<String, Map<String, Object>> intents = new LinkedHashMap<>();
    public final List<MultiValueMap<String, String>> creates = new ArrayList<>();
    public final List<String> secretsSeen = new ArrayList<>();
    private int n = 0;

    @Override public Map<String, Object> post(String url, HttpHeaders headers, MultiValueMap<String, String> params) {
        secretsSeen.add(headers.getFirst("Authorization"));
        creates.add(params);
        String id = "pi_fake" + (++n) + "x".repeat(10);
        Map<String, Object> pi = new LinkedHashMap<>();
        pi.put("id", id); pi.put("object", "payment_intent");
        pi.put("amount", Long.parseLong(params.getFirst("amount")));
        pi.put("currency", params.getFirst("currency"));
        pi.put("status", "requires_payment_method");
        pi.put("client_secret", id + "_secret_abc");
        Map<String, Object> md = new LinkedHashMap<>();
        params.forEach((k, v) -> { if (k.startsWith("metadata[")) md.put(k.substring(9, k.length() - 1), v.get(0)); });
        pi.put("metadata", md);
        intents.put(id, pi);
        return new LinkedHashMap<>(pi);
    }

    @Override public Map<String, Object> get(String url, HttpHeaders headers) {
        secretsSeen.add(headers.getFirst("Authorization"));
        String id = url.substring(url.lastIndexOf('/') + 1).split("\\?")[0];
        Map<String, Object> pi = intents.get(id);
        if (pi == null) return Map.of("error", Map.of("message", "No such payment_intent: " + id));
        return new LinkedHashMap<>(pi);
    }

    /** The card was charged. */
    public void succeed(String id, String brand, String last4) {
        Map<String, Object> pi = intents.get(id);
        pi.put("status", "succeeded");
        pi.put("latest_charge", Map.of("id", "ch_" + id, "payment_method_details",
                Map.of("type", "card", "card", Map.of("brand", brand, "last4", last4))));
    }
    public void fail(String id) { intents.get(id).put("status", "requires_payment_method"); }
    public String lastId() { return intents.keySet().stream().reduce((a, b) -> b).orElse(null); }
}
