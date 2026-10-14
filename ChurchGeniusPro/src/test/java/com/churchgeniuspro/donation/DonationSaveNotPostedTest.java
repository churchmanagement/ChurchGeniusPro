package com.churchgeniuspro.donation;

import com.churchgeniuspro.controller.DonationController;
import com.churchgeniuspro.hibernate.Donation;
import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.hibernate.StripeSettings;
import com.churchgeniuspro.repository.ChurchLogoRepository;
import com.churchgeniuspro.repository.DonationRepository;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.StripeSettingsRepository;
import com.churchgeniuspro.service.ChurchStripeGateway;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Public donation regression (Donation Review, V14): a successful payment is still
 * verified with Stripe and saved — with Stripe's fee captured when settled — but is
 * NOT posted to income at save time, and the card label never becomes a
 * transaction type. The donor-facing response is unchanged.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Public donation save — verified and saved, fee captured, not posted")
class DonationSaveNotPostedTest {

    static final String CLIENT_ID = "CHR-1";
    static final String TOKEN = "tok_abcdefghijklmnopqrstuvwxyz0123456789ABCDEF";
    static final String PI = "pi_3AbCdEfGhIjKlMnOpQrStUvW";

    @Mock DonationRepository donationRepo;
    @Mock StripeSettingsRepository stripeRepo;
    @Mock PublicScreenLinkRepository linkRepo;
    @Mock ServiceClientRepository clientRepo;
    @Mock ChurchLogoRepository logoRepo;
    @Mock EmailService emailService;
    @Mock SubscriptionService subscriptionService;

    DonationController controller;
    Map<String, Object> stripeIntent;
    List<String> stripeUrls;

    @BeforeEach
    void setUp() {
        controller = new DonationController(donationRepo, stripeRepo, linkRepo, clientRepo, logoRepo,
                emailService, subscriptionService, new com.churchgeniuspro.util.PublicSendLimiter());
        PublicScreenLink link = new PublicScreenLink();
        link.setToken(TOKEN); link.setAppClientId(CLIENT_ID); link.setPageUrl("/donate"); link.setRevoked(false);
        when(linkRepo.findByToken(TOKEN)).thenReturn(Optional.of(link));
        StripeSettings settings = new StripeSettings();
        settings.setClientId(CLIENT_ID); settings.setPublishableKey("pk_test_abc"); settings.setSecretKey("sk_test_abc");
        when(stripeRepo.findByClientId(CLIENT_ID)).thenReturn(Optional.of(settings));
        when(subscriptionService.isFeatureEnabled(CLIENT_ID, "onlineGiving")).thenReturn(true);
        when(subscriptionService.canAcceptOnlineGiving(CLIENT_ID)).thenReturn(true);
        when(donationRepo.findByStripePaymentIntentId(PI)).thenReturn(Optional.empty());

        stripeUrls = new java.util.ArrayList<>();
        ChurchStripeGateway gateway = new ChurchStripeGateway();
        gateway.setTransport(new ChurchStripeGateway.Transport() {
            @Override public Map<String, Object> post(String u, HttpHeaders h, MultiValueMap<String, String> p) { stripeUrls.add(u); return Map.of(); }
            @Override public Map<String, Object> get(String u, HttpHeaders h) { stripeUrls.add(u); return stripeIntent; }
        });
        controller.setStripeGateway(gateway);
        stripeIntent = succeededIntent(true);
    }

    static Map<String, Object> succeededIntent(boolean withBalanceTx) {
        Map<String, Object> charge = new LinkedHashMap<>();
        charge.put("id", "ch_3AbCdEfGhIjKlMnOpQrStUvW");
        charge.put("payment_method_details", Map.of("type", "card", "card", Map.of("brand", "visa", "last4", "6676")));
        if (withBalanceTx) {
            Map<String, Object> tx = new LinkedHashMap<>();
            tx.put("id", "txn_1x"); tx.put("fee", 320); tx.put("net", 9680);
            charge.put("balance_transaction", tx);
        } else {
            charge.put("balance_transaction", null);
        }
        Map<String, Object> pi = new LinkedHashMap<>();
        pi.put("id", PI); pi.put("status", "succeeded"); pi.put("amount", 10000); pi.put("currency", "usd");
        pi.put("latest_charge", charge);
        return pi;
    }

    @Test
    @DisplayName("a settled card payment is saved with Stripe's fee ($3.20 / net $96.80) and is not posted")
    void savedWithFeeNotPosted() {
        ResponseEntity<?> r = controller.saveDonation(TOKEN, Map.of("paymentIntentId", PI,
                "firstName", "Binny", "lastName", "Varghese", "email", "binny@example.org", "note", "Rajan Uncle"));
        assertThat(r.getStatusCode().value()).isEqualTo(200);

        ArgumentCaptor<Donation> saved = ArgumentCaptor.forClass(Donation.class);
        verify(donationRepo).save(saved.capture());
        Donation d = saved.getValue();
        assertThat(d.getAmount()).isEqualByComparingTo("100.00");
        assertThat(d.getPaymentMethod()).isEqualTo("Visa •••• 6676");   // kept on the donation, for the note
        assertThat(d.getStripeFee()).isEqualByComparingTo("3.20");
        assertThat(d.getStripeNet()).isEqualByComparingTo("96.80");
        assertThat(d.getStripeBalanceTransactionId()).isEqualTo("txn_1x");
        assertThat(d.getFeeSource()).isEqualTo(Donation.FEE_SOURCE_STRIPE);
        // not posted: no income, no links, review still pending
        assertThat(d.isPosted()).isFalse();
        assertThat(d.getPostedIncomeId()).isNull();
        assertThat(d.getPostedExpenseId()).isNull();
        assertThat(d.isReviewCompleted()).isFalse();
        // the verification call asked Stripe for the balance transaction
        assertThat(stripeUrls).anySatisfy(u -> assertThat(u).contains("expand[]=latest_charge.balance_transaction"));
        // donor-facing behaviour unchanged
        verify(subscriptionService).recordOnlineGiving(CLIENT_ID);
    }

    @Test
    @DisplayName("a pending ACH payment (no balance transaction yet) is saved with the fee left unknown — never zero")
    void pendingAchFeeUnknown() {
        stripeIntent = succeededIntent(false);
        ResponseEntity<?> r = controller.saveDonation(TOKEN, Map.of("paymentIntentId", PI, "firstName", "A", "lastName", "B"));
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<Donation> saved = ArgumentCaptor.forClass(Donation.class);
        verify(donationRepo).save(saved.capture());
        assertThat(saved.getValue().getStripeFee()).isNull();
        assertThat(saved.getValue().getFeeSource()).isNull();
        assertThat(saved.getValue().getStripeChargeId()).isEqualTo("ch_3AbCdEfGhIjKlMnOpQrStUvW");   // so the fee can be refreshed later
    }

    @Test
    @DisplayName("a repeat of the same PaymentIntent is 'already recorded' and saves nothing new")
    void repeatIsIdempotent() {
        Donation existing = new Donation(); existing.setId(5L); existing.setClientId(CLIENT_ID); existing.setStripePaymentIntentId(PI);
        when(donationRepo.findByStripePaymentIntentId(PI)).thenReturn(Optional.of(existing));
        ResponseEntity<?> r = controller.saveDonation(TOKEN, Map.of("paymentIntentId", PI));
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        verify(donationRepo, org.mockito.Mockito.never()).save(any());
        assertThat(stripeUrls).isEmpty();
    }
}
