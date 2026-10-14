package com.churchgeniuspro.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Donation Review — the actual Stripe fee comes from the charge's expanded
 * balance transaction. These pin (1) that the verification call now asks Stripe
 * for it, (2) exact cents→dollars conversion, and (3) that a missing or
 * malformed balance transaction yields EMPTY, never a guessed or zero fee.
 */
@DisplayName("ChurchStripeGateway — Stripe fee from the balance transaction")
class ChurchStripeGatewayFeeTest {

    /** Records every URL the gateway asks for and answers with a canned object. */
    static final class RecordingTransport implements ChurchStripeGateway.Transport {
        final List<String> urls = new CopyOnWriteArrayList<>();
        Map<String, Object> answer = Map.of();
        @Override public Map<String, Object> post(String url, HttpHeaders h, MultiValueMap<String, String> p) { urls.add("POST " + url); return answer; }
        @Override public Map<String, Object> get(String url, HttpHeaders h) { urls.add("GET " + url); return answer; }
    }

    static Map<String, Object> intentWithBalanceTx(long fee, long net) {
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("id", "txn_1AbCdEfGhIjKlMnOpQrStUvW");
        tx.put("object", "balance_transaction");
        tx.put("amount", fee + net);
        tx.put("fee", fee);
        tx.put("net", net);
        Map<String, Object> charge = new LinkedHashMap<>();
        charge.put("id", "ch_3AbCdEfGhIjKlMnOpQrStUvW");
        charge.put("balance_transaction", tx);
        charge.put("payment_method_details", Map.of("type", "card", "card", Map.of("brand", "visa", "last4", "6676")));
        Map<String, Object> pi = new LinkedHashMap<>();
        pi.put("id", "pi_3AbCdEfGhIjKlMnOpQrStUvW");
        pi.put("status", "succeeded");
        pi.put("amount", 10000);
        pi.put("currency", "usd");
        pi.put("latest_charge", charge);
        return pi;
    }

    @Test
    @DisplayName("retrieveIntent asks Stripe to expand the charge AND its balance transaction")
    void retrieveIntentExpandsBalanceTransaction() {
        ChurchStripeGateway g = new ChurchStripeGateway();
        RecordingTransport t = new RecordingTransport();
        g.setTransport(t);
        g.retrieveIntent("sk_test_x", "pi_3AbCdEfGhIjKlMnOpQrStUvW");
        assertThat(t.urls).hasSize(1);
        assertThat(t.urls.get(0))
                .contains("/payment_intents/pi_3AbCdEfGhIjKlMnOpQrStUvW")
                .contains("expand[]=latest_charge")
                .contains("expand[]=latest_charge.balance_transaction");
    }

    @Test
    @DisplayName("$100.00 donation: Stripe fee 320 cents → $3.20, net 9680 → $96.80, exactly")
    void parsesFeeAndNetExactly() {
        Optional<ChurchStripeGateway.FeeInfo> fi = ChurchStripeGateway.feeInfo(intentWithBalanceTx(320, 9680));
        assertThat(fi).isPresent();
        assertThat(fi.get().fee()).isEqualByComparingTo(new BigDecimal("3.20"));
        assertThat(fi.get().fee().scale()).isEqualTo(2);
        assertThat(fi.get().net()).isEqualByComparingTo(new BigDecimal("96.80"));
        assertThat(fi.get().balanceTransactionId()).isEqualTo("txn_1AbCdEfGhIjKlMnOpQrStUvW");
    }

    @Test
    @DisplayName("a fee of 1 cent survives the conversion (no float, no truncation)")
    void oneCentFee() {
        Optional<ChurchStripeGateway.FeeInfo> fi = ChurchStripeGateway.feeInfo(intentWithBalanceTx(1, 49));
        assertThat(fi).isPresent();
        assertThat(fi.get().fee()).isEqualByComparingTo("0.01");
        assertThat(fi.get().net()).isEqualByComparingTo("0.49");
    }

    @Test
    @DisplayName("pending ACH: balance_transaction is null → empty, never zero")
    void nullBalanceTransactionIsEmpty() {
        Map<String, Object> charge = new HashMap<>();
        charge.put("id", "ch_3AbCdEfGhIjKlMnOpQrStUvW");
        charge.put("balance_transaction", null);          // Stripe: not yet settled
        Map<String, Object> pi = new HashMap<>();
        pi.put("status", "succeeded");
        pi.put("latest_charge", charge);
        assertThat(ChurchStripeGateway.feeInfo(pi)).isEmpty();
    }

    @Test
    @DisplayName("an unexpanded fetch (balance_transaction is a bare id string) → empty")
    void bareIdStringIsEmpty() {
        Map<String, Object> pi = intentWithBalanceTx(320, 9680);
        @SuppressWarnings("unchecked")
        Map<String, Object> charge = (Map<String, Object>) pi.get("latest_charge");
        charge.put("balance_transaction", "txn_1AbCdEfGhIjKlMnOpQrStUvW");
        assertThat(ChurchStripeGateway.feeInfo(pi)).isEmpty();
    }

    @Test
    @DisplayName("no latest_charge, or null intent → empty")
    void missingChargeIsEmpty() {
        assertThat(ChurchStripeGateway.feeInfo(null)).isEmpty();
        assertThat(ChurchStripeGateway.feeInfo(Map.of("id", "pi_x", "status", "succeeded"))).isEmpty();
        assertThat(ChurchStripeGateway.feeInfo(Map.of("latest_charge", "ch_x"))).isEmpty();
    }

    @Test
    @DisplayName("a negative fee (a reversal shape) is not a donation fee → empty")
    void negativeFeeIsEmpty() {
        assertThat(ChurchStripeGateway.feeInfo(intentWithBalanceTx(-320, -9680))).isEmpty();
    }

    @Test
    @DisplayName("retrieveCharge refuses anything that is not a Stripe charge id, without a network call")
    void retrieveChargeValidatesId() {
        ChurchStripeGateway g = new ChurchStripeGateway();
        RecordingTransport t = new RecordingTransport();
        g.setTransport(t);
        assertThat(g.retrieveCharge("sk_test_x", "../payment_intents/pi_x")).containsKey("error");
        assertThat(g.retrieveCharge("sk_test_x", "")).containsKey("error");
        assertThat(g.retrieveCharge("sk_test_x", null)).containsKey("error");
        assertThat(t.urls).isEmpty();

        g.retrieveCharge("sk_test_x", "ch_3AbCdEfGhIjKlMnOpQrStUvW");
        assertThat(t.urls).hasSize(1);
        assertThat(t.urls.get(0)).contains("/charges/ch_3AbCdEfGhIjKlMnOpQrStUvW").contains("expand[]=balance_transaction");
    }

    @Test
    @DisplayName("a 401/403 from Stripe on the charge lookup is reported as a permission error with the status, without the key")
    void permissionErrorCarriesStatus() {
        ChurchStripeGateway g = new ChurchStripeGateway();
        g.setTransport(new ChurchStripeGateway.Transport() {
            @Override public Map<String, Object> post(String u, HttpHeaders h, MultiValueMap<String, String> p) { return Map.of(); }
            @Override public Map<String, Object> get(String u, HttpHeaders h) {
                throw org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY, new byte[0], null);
            }
        });
        Map<String, Object> reply = g.retrieveCharge("sk_test_secret", "ch_3AbCdEfGhIjKlMnOpQrStUvW");
        assertThat(ChurchStripeGateway.isPermissionError(reply)).isTrue();
        assertThat(reply.toString()).doesNotContain("sk_test_secret");
        assertThat(ChurchStripeGateway.isPermissionError(Map.of("error", Map.of("message", "No such charge", "status", 404)))).isFalse();
        assertThat(ChurchStripeGateway.isPermissionError(Map.of("id", "ch_x"))).isFalse();
    }

    @Test
    @DisplayName("feeInfoFromCharge reads a Charge object the same way")
    void feeInfoFromCharge() {
        @SuppressWarnings("unchecked")
        Map<String, Object> charge = (Map<String, Object>) intentWithBalanceTx(59, 1941).get("latest_charge");
        Optional<ChurchStripeGateway.FeeInfo> fi = ChurchStripeGateway.feeInfoFromCharge(charge);
        assertThat(fi).isPresent();
        assertThat(fi.get().fee()).isEqualByComparingTo("0.59");
        assertThat(fi.get().net()).isEqualByComparingTo("19.41");
    }
}
