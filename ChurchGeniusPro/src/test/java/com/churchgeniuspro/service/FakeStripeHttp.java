package com.churchgeniuspro.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory stand-in for the Stripe REST API (PaymentIntents + balance), honouring
 * Idempotency-Key the way Stripe does. Tests never reach the real Stripe.
 */
public class FakeStripeHttp implements PlatformStripeService.StripeHttp {

    public static final String SECRET  = "sk_test_" + "A1b2C3d4E5f6G7h8I9j0K1l2";
    public static final String PUBLISH = "pk_test_" + "Z9y8X7w6V5u4T3s2R1q0P9o8";
    public static final String WEBHOOK = "whsec_" + "Mn0Pq1Rs2Tu3Vw4Xy5Za6Bc7";

    static final ObjectMapper JSON = new ObjectMapper();
    public final Map<String, ObjectNode> intents = new ConcurrentHashMap<>();
    public final Map<String, String> idempotency = new ConcurrentHashMap<>();
    public final List<String> calls = new CopyOnWriteArrayList<>();
    public final List<Map<String, String>> createForms = new CopyOnWriteArrayList<>();
    public final List<String> idempotencyKeys = new CopyOnWriteArrayList<>();
    private final AtomicInteger seq = new AtomicInteger(1000);
    public volatile String balanceError;   // when set, /balance answers 401 with this message
    public volatile String customerError;  // when set, POST /customers answers 403 with this message
    public final List<Map<String, String>> customerForms = new CopyOnWriteArrayList<>();
    public final List<String> retrievePaths = new CopyOnWriteArrayList<>();

    @Override
    public synchronized PlatformStripeService.Response send(String method, String path, Map<String, String> form,
                                                           String secretKey, String idem) throws Exception {
        calls.add(method + " " + path);
        if (!SECRET.equals(secretKey)) return err(401, "Invalid API Key provided: " + secretKey);
        if ("GET".equals(method) && path.equals("/balance")) {
            if (balanceError != null) return err(401, balanceError);
            return new PlatformStripeService.Response(200, "{\"object\":\"balance\",\"livemode\":false,\"available\":[{\"amount\":12345,\"currency\":\"usd\"}]}");
        }
        if ("POST".equals(method) && path.equals("/customers")) {
            if (customerError != null) return err(403, customerError);
            customerForms.add(new LinkedHashMap<>(form));
            ObjectNode c = JSON.createObjectNode();
            c.put("id", "cus_" + "Fake" + seq.incrementAndGet() + "abcdefgh").put("object", "customer");
            return ok(c);
        }
        if ("POST".equals(method) && path.equals("/payment_intents")) {
            idempotencyKeys.add(String.valueOf(idem));
            if (idem != null && idempotency.containsKey(idem)) return ok(intents.get(idempotency.get(idem)));
            createForms.add(new LinkedHashMap<>(form));
            // Globally unique like real Stripe ids (V12 refuses one PaymentIntent on two invoices).
            String id = "pi_" + "Test" + seq.incrementAndGet() + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            ObjectNode pi = JSON.createObjectNode();
            pi.put("id", id).put("object", "payment_intent").put("status", "requires_payment_method")
              .put("amount", Long.parseLong(form.get("amount"))).put("amount_received", 0)
              .put("currency", form.get("currency")).put("livemode", false)
              .put("client_secret", id + "_secret_" + "QrStUvWxYz012345");
            ObjectNode meta = pi.putObject("metadata");
            form.forEach((k, v) -> { if (k.startsWith("metadata[")) meta.put(k.substring(9, k.length() - 1), v); });
            if (form.get("customer") != null) pi.put("customer", form.get("customer"));
            intents.put(id, pi);
            if (idem != null) idempotency.put(idem, id);
            return ok(pi);
        }
        if (path.startsWith("/payment_intents/")) {
            if ("GET".equals(method)) retrievePaths.add(path);
            String rest = path.substring("/payment_intents/".length());
            if (rest.contains("?")) rest = rest.substring(0, rest.indexOf('?'));
            String id = rest.contains("/") ? rest.substring(0, rest.indexOf('/')) : rest;
            ObjectNode pi = intents.get(id);
            if (pi == null) return err(404, "No such payment_intent");
            if ("POST".equals(method) && rest.endsWith("/cancel")) {
                if ("succeeded".equals(pi.path("status").asText())) return err(400, "already succeeded");
                pi.put("status", "canceled");
            }
            return ok(pi);
        }
        return err(404, "unknown path");
    }

    /** The payer's card succeeds. */
    public synchronized ObjectNode succeed(String id) { return succeed(id, "card"); }

    /** The payment succeeds with the given Stripe payment method type (card, us_bank_account, customer_balance, …). */
    public synchronized ObjectNode succeed(String id, String methodType) {
        ObjectNode pi = intents.get(id);
        pi.put("status", "succeeded").put("amount_received", pi.path("amount").asLong());
        pi.putObject("payment_method").put("id", "pm_" + "Fake" + id.substring(3)).put("type", methodType);
        pi.remove("next_action");
        return pi;
    }

    public synchronized void setStatus(String id, String status) { intents.get(id).put("status", status); }

    /** A bank transfer was chosen: Stripe waits for the funds and hosts the transfer instructions. */
    public synchronized ObjectNode awaitBankTransfer(String id, String instructionsUrl) {
        ObjectNode pi = intents.get(id);
        pi.put("status", "requires_action");
        pi.putObject("next_action").put("type", "display_bank_transfer_instructions")
          .putObject("display_bank_transfer_instructions").put("hosted_instructions_url", instructionsUrl);
        return pi;
    }

    /** An ACH debit was submitted and is clearing. */
    public synchronized ObjectNode processing(String id) {
        ObjectNode pi = intents.get(id);
        pi.put("status", "processing");
        pi.putObject("payment_method").put("id", "pm_" + "Fake" + id.substring(3)).put("type", "us_bank_account");
        return pi;
    }

    /** The attempt failed (declined card / returned debit). */
    public synchronized ObjectNode fail(String id, String message) {
        ObjectNode pi = intents.get(id);
        pi.put("status", "requires_payment_method");
        pi.putObject("last_payment_error").put("message", message).put("code", "card_declined");
        return pi;
    }

    /** A signed payment_intent.succeeded webhook for {@code pi}. */
    public static String[] webhook(String eventId, ObjectNode pi, long timestamp, String secret, boolean livemode) throws Exception {
        return webhook(eventId, "payment_intent.succeeded", pi, timestamp, secret, livemode);
    }

    /** A signed webhook of the given type for {@code pi}, as Stripe sends it: the payment method is an id, not an object. */
    public static String[] webhook(String eventId, String type, ObjectNode pi, long timestamp, String secret, boolean livemode) throws Exception {
        ObjectNode ev = JSON.createObjectNode();
        ev.put("id", eventId).put("object", "event").put("type", type).put("livemode", livemode);
        ObjectNode copy = pi.deepCopy();
        if (copy.path("payment_method").isObject()) copy.put("payment_method", copy.path("payment_method").path("id").asText());
        ev.putObject("data").set("object", copy);
        String payload = JSON.writeValueAsString(ev);
        return new String[]{ payload, "t=" + timestamp + ",v1=" + PlatformStripeService.hmacHex(secret, timestamp + "." + payload) };
    }

    private static PlatformStripeService.Response ok(ObjectNode n) throws Exception {
        return new PlatformStripeService.Response(200, JSON.writeValueAsString(n));
    }

    private static PlatformStripeService.Response err(int status, String message) throws Exception {
        ObjectNode e = JSON.createObjectNode();
        e.putObject("error").put("message", message).put("type", "invalid_request_error");
        return new PlatformStripeService.Response(status, JSON.writeValueAsString(e));
    }
}
