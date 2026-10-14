package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.Donation;
import com.churchgeniuspro.hibernate.DonationSettings;
import com.churchgeniuspro.hibernate.StripeSettings;
import com.churchgeniuspro.repository.DonationRepository;
import com.churchgeniuspro.repository.StripeSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Donation Review — the Stripe fee is Stripe's own figure when available, a
 * labelled estimate when the church configured a rate, and a refusal otherwise.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DonationFeeService — actual, estimated, or refused")
class DonationFeeServiceTest {

    private static final String CLIENT = "CHR-1";

    @Mock DonationRepository donationRepo;
    @Mock StripeSettingsRepository stripeRepo;

    ChurchStripeGateway gateway;
    Map<String, Object> stripeAnswer;
    int stripeCalls;
    DonationFeeService svc;

    @BeforeEach
    void setUp() {
        gateway = new ChurchStripeGateway();
        stripeCalls = 0;
        stripeAnswer = Map.of("error", Map.of("message", "No such charge"));
        gateway.setTransport(new ChurchStripeGateway.Transport() {
            @Override public Map<String, Object> post(String u, HttpHeaders h, MultiValueMap<String, String> p) { stripeCalls++; return stripeAnswer; }
            @Override public Map<String, Object> get(String u, HttpHeaders h) { stripeCalls++; return stripeAnswer; }
        });
        StripeSettings ss = new StripeSettings();
        ss.setClientId(CLIENT); ss.setSecretKey("sk_test_church"); ss.setPublishableKey("pk_test_church");
        when(stripeRepo.findByClientId(CLIENT)).thenReturn(Optional.of(ss));
        svc = new DonationFeeService(donationRepo, stripeRepo, gateway);
    }

    static Donation donation(String amount, String chargeId) {
        Donation d = new Donation();
        d.setId(7L); d.setClientId(CLIENT);
        d.setAmount(new BigDecimal(amount)); d.setIntendedAmount(new BigDecimal(amount));
        d.setFeeCovered(BigDecimal.ZERO);
        d.setStripePaymentIntentId("pi_3AbCdEfGhIjKlMnOpQrStUvW");
        d.setStripeChargeId(chargeId);
        return d;
    }

    static DonationSettings rate(String pct, String fixed) {
        DonationSettings s = new DonationSettings();
        s.setClientId(CLIENT);
        s.setFeePercent(pct == null ? null : new BigDecimal(pct));
        s.setFeeFixed(fixed == null ? null : new BigDecimal(fixed));
        return s;
    }

    static Map<String, Object> chargeWithTx(long fee, long net) {
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("id", "txn_1x"); tx.put("fee", fee); tx.put("net", net);
        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("id", "ch_3AbCdEfGhIjKlMnOpQrStUvW"); ch.put("balance_transaction", tx);
        return ch;
    }

    @Test
    @DisplayName("the brief's example: $100.00 × 2.9% + $0.30 = $3.20 estimated, net $96.80")
    void estimateMatchesBrief() {
        Donation d = donation("100.00", null);                 // no charge id → nothing to ask Stripe
        DonationFeeService.Resolved r = svc.resolve(d, rate("2.9", "0.30"));
        assertThat(r.fee()).isEqualByComparingTo("3.20");
        assertThat(r.net()).isEqualByComparingTo("96.80");
        assertThat(r.source()).isEqualTo(Donation.FEE_SOURCE_ESTIMATED);
        assertThat(r.estimated()).isTrue();
        assertThat(stripeCalls).isZero();
        verify(donationRepo, never()).save(any());             // an estimate is never persisted
    }

    @Test
    @DisplayName("rounding is HALF_UP at two decimals, applied once: $33.33 → $1.2666 → $1.27")
    void roundsHalfUpOnce() {
        assertThat(DonationFeeService.estimate(new BigDecimal("33.33"), new BigDecimal("2.9"), new BigDecimal("0.30")))
                .isEqualByComparingTo("1.27");
        assertThat(DonationFeeService.estimate(new BigDecimal("0.50"), new BigDecimal("2.9"), new BigDecimal("0.30")))
                .isEqualByComparingTo("0.31");                 // 0.0145 + 0.30 = 0.3145 → 0.31
        assertThat(DonationFeeService.estimate(new BigDecimal("250.00"), new BigDecimal("2.9"), new BigDecimal("0.30")))
                .isEqualByComparingTo("7.55");
    }

    @Test
    @DisplayName("Stripe reports the fee → actual wins over the configured rate and is stored on the donation")
    void actualFeeWinsAndIsStored() {
        stripeAnswer = chargeWithTx(320, 9680);
        Donation d = donation("100.00", "ch_3AbCdEfGhIjKlMnOpQrStUvW");
        DonationFeeService.Resolved r = svc.resolve(d, rate("5.0", "1.00"));   // a wrong rate must not be used
        assertThat(r.fee()).isEqualByComparingTo("3.20");
        assertThat(r.net()).isEqualByComparingTo("96.80");
        assertThat(r.source()).isEqualTo(Donation.FEE_SOURCE_STRIPE);
        assertThat(r.estimated()).isFalse();
        assertThat(d.getStripeFee()).isEqualByComparingTo("3.20");
        assertThat(d.getStripeNet()).isEqualByComparingTo("96.80");
        assertThat(d.getStripeBalanceTransactionId()).isEqualTo("txn_1x");
        assertThat(d.getFeeSource()).isEqualTo(Donation.FEE_SOURCE_STRIPE);
        verify(donationRepo).save(d);
        assertThat(stripeCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("a fee already stored as Stripe's is reused without another Stripe call")
    void storedActualFeeIsReused() {
        Donation d = donation("100.00", "ch_3AbCdEfGhIjKlMnOpQrStUvW");
        d.setStripeFee(new BigDecimal("3.20")); d.setStripeNet(new BigDecimal("96.80"));
        d.setFeeSource(Donation.FEE_SOURCE_STRIPE);
        DonationFeeService.Resolved r = svc.resolve(d, null);
        assertThat(r.fee()).isEqualByComparingTo("3.20");
        assertThat(r.source()).isEqualTo(Donation.FEE_SOURCE_STRIPE);
        assertThat(stripeCalls).isZero();
    }

    @Test
    @DisplayName("pending ACH (Stripe has no balance transaction yet) + a configured rate → estimate, flagged")
    void pendingAchFallsBackToEstimate() {
        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("id", "ch_3AbCdEfGhIjKlMnOpQrStUvW"); ch.put("balance_transaction", null);
        stripeAnswer = ch;
        Donation d = donation("100.00", "ch_3AbCdEfGhIjKlMnOpQrStUvW");
        DonationFeeService.Resolved r = svc.resolve(d, rate("0.8", "0.00"));   // Stripe's ACH pricing shape
        assertThat(r.estimated()).isTrue();
        assertThat(r.fee()).isEqualByComparingTo("0.80");
        assertThat(d.getFeeSource()).isNull();                 // nothing stored for an estimate
        verify(donationRepo, never()).save(any());
    }

    @Test
    @DisplayName("no Stripe fee and no configured rate → refused with an explanation, nothing invented")
    void refusesWithoutRate() {
        Donation d = donation("100.00", "ch_3AbCdEfGhIjKlMnOpQrStUvW");
        assertThatThrownBy(() -> svc.resolve(d, rate(null, null)))
                .isInstanceOf(DonationFeeService.FeeUnavailableException.class)
                .hasMessageContaining("no Stripe fee rate is configured");
        assertThatThrownBy(() -> svc.resolve(d, null))
                .isInstanceOf(DonationFeeService.FeeUnavailableException.class);
    }

    @Test
    @DisplayName("half a rate (percent without fixed) is not a rate")
    void halfRateIsNoRate() {
        Donation d = donation("100.00", null);
        assertThatThrownBy(() -> svc.resolve(d, rate("2.9", null)))
                .isInstanceOf(DonationFeeService.FeeUnavailableException.class);
    }

    @Test
    @DisplayName("no church Stripe key configured → refresh is skipped silently, estimate still works")
    void noStripeKeySkipsRefresh() {
        when(stripeRepo.findByClientId(CLIENT)).thenReturn(Optional.empty());
        Donation d = donation("100.00", "ch_3AbCdEfGhIjKlMnOpQrStUvW");
        DonationFeeService.Resolved r = svc.resolve(d, rate("2.9", "0.30"));
        assertThat(r.estimated()).isTrue();
        assertThat(stripeCalls).isZero();
    }

    @Test
    @DisplayName("Stripe errors on the charge lookup → not fatal; falls through to the rate")
    void stripeErrorFallsThrough() {
        stripeAnswer = Map.of("error", Map.of("message", "No such charge"));
        Donation d = donation("100.00", "ch_3AbCdEfGhIjKlMnOpQrStUvW");
        DonationFeeService.Resolved r = svc.resolve(d, rate("2.9", "0.30"));
        assertThat(r.estimated()).isTrue();
        assertThat(stripeCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("a restricted key that may not read charges (Stripe 403) is reported as KEY_LACKS_PERMISSION — estimated, never actual")
    void permissionRefusalIsNeverAnActualFee() {
        stripeAnswer = Map.of("error", Map.of("message", "Forbidden", "status", 403));
        Donation d = donation("100.00", "ch_3AbCdEfGhIjKlMnOpQrStUvW");
        DonationFeeService.Refresh r = svc.refresh(d);
        assertThat(r.status()).isEqualTo(DonationFeeService.RefreshStatus.KEY_LACKS_PERMISSION);
        assertThat(r.found()).isFalse();
        assertThat(r.message()).contains("not permitted").contains("Balance transactions: read");
        assertThat(d.getFeeSource()).isNull();
        assertThat(d.getStripeFee()).isNull();
        verify(donationRepo, never()).save(any());

        DonationFeeService.Resolved res = svc.resolve(d, rate("2.9", "0.30"));
        assertThat(res.estimated()).isTrue();
        assertThat(res.source()).isEqualTo(Donation.FEE_SOURCE_ESTIMATED);
        assertThat(res.whyEstimated()).contains("not permitted");
        assertThatThrownBy(() -> svc.resolve(donation("100.00", "ch_3AbCdEfGhIjKlMnOpQrStUvW"), rate(null, null)))
                .isInstanceOf(DonationFeeService.FeeUnavailableException.class);
    }

    @Test
    @DisplayName("every non-FOUND refresh outcome leaves the donation's fee fields untouched")
    void nonFoundOutcomesWriteNothing() {
        Donation noCharge = donation("100.00", null);
        assertThat(svc.refresh(noCharge).status()).isEqualTo(DonationFeeService.RefreshStatus.NO_CHARGE_ID);
        when(stripeRepo.findByClientId(CLIENT)).thenReturn(Optional.empty());
        assertThat(svc.refresh(donation("100.00", "ch_3AbCdEfGhIjKlMnOpQrStUvW")).status()).isEqualTo(DonationFeeService.RefreshStatus.NO_STRIPE_KEY);
        verify(donationRepo, never()).save(any());
    }

    @Test
    @DisplayName("nothing the fee service logs or returns contains the Stripe key")
    void neverLeaksKey() {
        stripeAnswer = Map.of("error", Map.of("message", "Forbidden", "status", 403));
        Donation d = donation("100.00", "ch_3AbCdEfGhIjKlMnOpQrStUvW");
        DonationFeeService.Refresh r = svc.refresh(d);
        assertThat(r.message()).doesNotContain("sk_test_church");
        assertThat(String.valueOf(r.detail())).doesNotContain("sk_test_church");
    }

    @Test
    @DisplayName("refresh uses the church's own Stripe key, never a platform key")
    void refreshUsesChurchKey() {
        final String[] seenAuth = new String[1];
        gateway.setTransport(new ChurchStripeGateway.Transport() {
            @Override public Map<String, Object> post(String u, HttpHeaders h, MultiValueMap<String, String> p) { return Map.of(); }
            @Override public Map<String, Object> get(String u, HttpHeaders h) { seenAuth[0] = h.getFirst("Authorization"); return chargeWithTx(320, 9680); }
        });
        svc.refreshFromStripe(donation("100.00", "ch_3AbCdEfGhIjKlMnOpQrStUvW"));
        assertThat(seenAuth[0]).isEqualTo("Bearer sk_test_church");
    }
}
