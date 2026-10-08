package com.churchgeniuspro.donation;

import com.churchgeniuspro.controller.DonationController;
import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.hibernate.StripeSettings;
import com.churchgeniuspro.repository.ChurchLogoRepository;
import com.churchgeniuspro.repository.DonationRepository;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.StripeSettingsRepository;
import com.churchgeniuspro.service.DonationIncomePostingService;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Financial audit M2: {@code /api/public/donate/intent} took the {@code currency}
 * field from the request body with no check. The donation page never lets a donor
 * pick one — it always sends {@code "usd"} — so this only ever mattered for a
 * request built outside the page, and every dollar amount downstream (the
 * cents-multiply here, tax statements, reports) assumes two-decimal USD. A
 * {@code currency:"jpy"} body used to reach Stripe and charge ¥2500 for a
 * "25.00" gift; it is now refused before any PaymentIntent is created.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Donation intent — currency whitelist")
class DonationCurrencyTest {

    private static final String TOKEN     = "SR8MWnwI-vZBIXG1nOM9HiY2B79RSUdriP_WZ9Wou_Q";
    private static final String CLIENT_ID = "CHR-1";

    @Mock private DonationRepository         donationRepo;
    @Mock private StripeSettingsRepository   stripeRepo;
    @Mock private PublicScreenLinkRepository linkRepo;
    @Mock private ServiceClientRepository    clientRepo;
    @Mock private ChurchLogoRepository       logoRepo;
    @Mock private EmailService               emailService;
    @Mock private SubscriptionService        subscriptionService;
    @Mock private DonationIncomePostingService donationIncomePoster;

    private DonationController controller;

    @BeforeEach
    void setUp() {
        controller = new DonationController(donationRepo, stripeRepo, linkRepo,
                clientRepo, logoRepo, emailService, subscriptionService, donationIncomePoster,
                new com.churchgeniuspro.util.PublicSendLimiter());

        PublicScreenLink link = new PublicScreenLink();
        link.setToken(TOKEN);
        link.setAppClientId(CLIENT_ID);
        link.setPageUrl("/donate");
        link.setRevoked(false);
        link.setExpirationDate(null);
        when(linkRepo.findByToken(TOKEN)).thenReturn(Optional.of(link));

        StripeSettings settings = new StripeSettings();
        settings.setClientId(CLIENT_ID);
        settings.setPublishableKey("pk_live_abc");
        settings.setSecretKey("sk_live_abc");
        when(stripeRepo.findByClientId(CLIENT_ID)).thenReturn(Optional.of(settings));

        when(subscriptionService.isFeatureEnabled(CLIENT_ID, "onlineGiving")).thenReturn(true);
        when(subscriptionService.canAcceptOnlineGiving(CLIENT_ID)).thenReturn(true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<?> res) {
        return (Map<String, Object>) res.getBody();
    }

    private static Map<String, Object> requestBody(Object currency) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("amount", 25.00);
        b.put("currency", currency);
        return b;
    }

    @ParameterizedTest(name = "currency \"{0}\" is refused")
    @ValueSource(strings = { "jpy", "eur", "gbp", "JPY", "cad" })
    @DisplayName("a non-USD currency is refused before any PaymentIntent is created")
    void nonUsdCurrencyRefused(String currency) {
        ResponseEntity<?> res = controller.createIntent(TOKEN, requestBody(currency), new org.springframework.mock.web.MockHttpServletRequest());

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(body(res)).containsEntry("error", "Only USD donations are supported.");
    }

    @Test
    @DisplayName("a fully-configured, plan-eligible org still refuses a non-USD currency")
    void refusalIsNotMaskedByEarlierGates() {
        // Distinguishes "refused by the M2 currency check" from "refused by an
        // earlier gate" — every earlier gate (link, config, plan) is deliberately
        // satisfied here, so a 400 with this exact message can only come from the
        // currency check itself.
        ResponseEntity<?> res = controller.createIntent(TOKEN, requestBody("jpy"), new org.springframework.mock.web.MockHttpServletRequest());

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(body(res).get("error"))).isEqualTo("Only USD donations are supported.");
    }

    @Test
    @DisplayName("garbage that isn't a currency at all is refused the same way")
    void nonsenseCurrencyRefused() {
        ResponseEntity<?> res = controller.createIntent(TOKEN, requestBody("not-a-currency"), new org.springframework.mock.web.MockHttpServletRequest());

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(body(res)).containsEntry("error", "Only USD donations are supported.");
    }
}
