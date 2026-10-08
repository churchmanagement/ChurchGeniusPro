package com.churchgeniuspro.donation;

import com.churchgeniuspro.controller.DonationController;
import com.churchgeniuspro.hibernate.ChurchLogo;
import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.StripeSettings;
import com.churchgeniuspro.repository.ChurchLogoRepository;
import com.churchgeniuspro.repository.DonationRepository;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.StripeSettingsRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The public donation page when the church has not finished Stripe setup —
 * which is every Trial/Demo tenant, and any real church that generated a donate
 * link before configuring keys.
 *
 * <p>The page used to refuse to render at all, so a visitor saw "Online giving is
 * not configured" instead of the church's giving page. It now renders normally and
 * the donation is refused at submit time. That split is only safe if the SERVER is
 * the thing refusing it, so the config endpoint (which decides what renders) and
 * the intent endpoint (which decides what is charged) are asserted separately:
 * the first is deliberately permissive, the second must not be.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Donation page — online giving not configured")
class DonationPageAvailabilityTest {

    /** The exact wording the donor sees. Spelled out rather than imported so a
     *  change to the message has to be made deliberately, in two places. */
    private static final String NOT_CONFIGURED =
            "Online giving is not configured for this church account.";

    private static final String TOKEN     = "SR8MWnwI-vZBIXG1nOM9HiY2B79RSUdriP_WZ9Wou_Q";
    private static final String CLIENT_ID = "TRIAL-1757300000123";

    @Mock private DonationRepository         donationRepo;
    @Mock private StripeSettingsRepository   stripeRepo;
    @Mock private PublicScreenLinkRepository linkRepo;
    @Mock private ServiceClientRepository    clientRepo;
    @Mock private ChurchLogoRepository       logoRepo;
    @Mock private EmailService               emailService;
    @Mock private SubscriptionService        subscriptionService;
    @Mock private com.churchgeniuspro.service.DonationIncomePostingService donationIncomePoster;

    private DonationController controller;

    @BeforeEach
    void setUp() {
        controller = new DonationController(donationRepo, stripeRepo, linkRepo,
                clientRepo, logoRepo, emailService, subscriptionService, donationIncomePoster,
                new com.churchgeniuspro.util.PublicSendLimiter());

        PublicScreenLink link = new PublicScreenLink();
        link.setToken(TOKEN);
        link.setAppClientId(CLIENT_ID);
        link.setPageUrl("/donate");        // only a Donation link resolves here now
        link.setRevoked(false);
        link.setExpirationDate(null);
        when(linkRepo.findByToken(TOKEN)).thenReturn(Optional.of(link));

        when(clientRepo.findByClientId(CLIENT_ID)).thenReturn(Optional.empty());
        when(logoRepo.findByClientId(CLIENT_ID)).thenReturn(Optional.empty());
    }

    /** No Stripe row at all — the Trial/Demo case. */
    private void noStripeSettings() {
        when(stripeRepo.findByClientId(CLIENT_ID)).thenReturn(Optional.empty());
    }

    private void stripeSettings(String publishable, String secret) {
        StripeSettings s = new StripeSettings();
        s.setClientId(CLIENT_ID);
        s.setPublishableKey(publishable);
        s.setSecretKey(secret);
        when(stripeRepo.findByClientId(CLIENT_ID)).thenReturn(Optional.of(s));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<?> res) {
        return (Map<String, Object>) res.getBody();
    }

    // ══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("config — decides whether the page renders")
    class Config {

        @Test
        @DisplayName("unconfigured org still gets the page, not an error screen")
        void unconfiguredRenders() {
            noStripeSettings();

            ResponseEntity<?> res = controller.config(TOKEN);

            // 200, not 400 — a 400 is what made donation.html show "Unable to load".
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(body(res)).containsEntry("onlineGivingEnabled", false);
            // No "error" key: the page keys its failure screen off that.
            assertThat(body(res)).doesNotContainKey("error");
            // And no key is handed out, so the browser cannot init Stripe.js.
            assertThat(body(res)).doesNotContainKey("publishableKey");
            assertThat(body(res)).containsEntry("message", NOT_CONFIGURED);
        }

        @Test
        @DisplayName("configured org is completely unaffected")
        void configuredUnchanged() {
            stripeSettings("pk_live_abc", "sk_live_abc");

            ResponseEntity<?> res = controller.config(TOKEN);

            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(body(res)).containsEntry("publishableKey", "pk_live_abc");
            assertThat(body(res)).containsEntry("onlineGivingEnabled", true);
            // The donor is told nothing, because nothing is wrong.
            assertThat(body(res)).doesNotContainKey("message");
        }

        @Test
        @DisplayName("publishable key without a secret key counts as unconfigured")
        void halfConfigured() {
            // Previously this rendered a working-looking card field that could only
            // fail at intent time, after the donor had typed their card in.
            stripeSettings("pk_live_abc", "  ");

            ResponseEntity<?> res = controller.config(TOKEN);

            assertThat(body(res)).containsEntry("onlineGivingEnabled", false);
            assertThat(body(res)).doesNotContainKey("publishableKey");
        }

        @Test
        @DisplayName("church name and logo are still sent, so the page looks normal")
        void brandingSurvives() {
            noStripeSettings();
            ServiceClient client = mock(ServiceClient.class);
            when(client.getChurchName()).thenReturn("Grace Chapel");
            when(clientRepo.findByClientId(CLIENT_ID)).thenReturn(Optional.of(client));
            ChurchLogo logo = mock(ChurchLogo.class);
            when(logo.getLogoData()).thenReturn(new byte[] { 1, 2, 3 });
            when(logoRepo.findByClientId(CLIENT_ID)).thenReturn(Optional.of(logo));

            Map<String, Object> out = body(controller.config(TOKEN));

            assertThat(out).containsEntry("churchName", "Grace Chapel");
            assertThat(out).containsEntry("logoUrl",
                    "/api/public/donate/logo?cid=" + TOKEN);
        }

        @Test
        @DisplayName("a revoked link is still refused outright")
        void revokedLinkStillFails() {
            // Rendering the page for an unconfigured org must not turn the endpoint
            // into a page that renders for ANY token.
            PublicScreenLink revoked = new PublicScreenLink();
            revoked.setToken(TOKEN);
            revoked.setAppClientId(CLIENT_ID);
            revoked.setRevoked(true);
            when(linkRepo.findByToken(TOKEN)).thenReturn(Optional.of(revoked));

            ResponseEntity<?> res = controller.config(TOKEN);

            assertThat(res.getStatusCode().value()).isEqualTo(400);
            assertThat(body(res)).containsKey("error");
        }

        @Test
        @DisplayName("an unknown token is still refused outright")
        void unknownTokenStillFails() {
            when(linkRepo.findByToken(anyString())).thenReturn(Optional.empty());

            ResponseEntity<?> res = controller.config("nope");

            assertThat(res.getStatusCode().value()).isEqualTo(400);
            assertThat(body(res)).containsKey("error");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("intent — the actual enforcement point")
    class Intent {

        @Test
        @DisplayName("posting directly to intent is refused for an unconfigured org")
        void directPostRefused() {
            // This is the bypass the rendered form invites: the page now loads, so a
            // visitor can re-enable the button in devtools and POST anyway.
            noStripeSettings();

            ResponseEntity<?> res = controller.createIntent(TOKEN,
                    Map.of("amount", 25.00, "currency", "usd"), new org.springframework.mock.web.MockHttpServletRequest());

            assertThat(res.getStatusCode().value()).isEqualTo(400);
            assertThat(body(res)).containsEntry("error", NOT_CONFIGURED);
        }

        @Test
        @DisplayName("the refusal happens before any Stripe or plan work")
        void refusedEarly() {
            noStripeSettings();

            controller.createIntent(TOKEN, Map.of("amount", 25.00, "currency", "usd"), new org.springframework.mock.web.MockHttpServletRequest());

            // Never reaching the plan checks proves the method returned before the
            // Stripe call below them — no PaymentIntent can have been created.
            verify(subscriptionService, never()).isFeatureEnabled(anyString(), anyString());
            verify(subscriptionService, never()).canAcceptOnlineGiving(anyString());
        }

        @Test
        @DisplayName("half-configured org cannot charge either")
        void halfConfiguredRefused() {
            stripeSettings("pk_live_abc", null);

            ResponseEntity<?> res = controller.createIntent(TOKEN,
                    Map.of("amount", 25.00, "currency", "usd"), new org.springframework.mock.web.MockHttpServletRequest());

            assertThat(res.getStatusCode().value()).isEqualTo(400);
            assertThat(body(res)).containsEntry("error", NOT_CONFIGURED);
        }

        @Test
        @DisplayName("a configured org still gets past the setup gate to its plan checks")
        void configuredReachesPlanChecks() {
            stripeSettings("pk_live_abc", "sk_live_abc");
            when(subscriptionService.isFeatureEnabled(CLIENT_ID, "onlineGiving")).thenReturn(false);

            ResponseEntity<?> res = controller.createIntent(TOKEN,
                    Map.of("amount", 25.00, "currency", "usd"), new org.springframework.mock.web.MockHttpServletRequest());

            // 403 from the plan check, not 400 from the setup check: the new gate did
            // not swallow orgs that are properly configured.
            assertThat(res.getStatusCode().value()).isEqualTo(403);
            assertThat(String.valueOf(body(res).get("error")))
                    .doesNotContain("not configured for this church account");
        }

        @Test
        @DisplayName("an invalid link is still refused before anything else")
        void invalidLinkRefused() {
            when(linkRepo.findByToken(anyString())).thenReturn(Optional.empty());

            ResponseEntity<?> res = controller.createIntent("nope",
                    Map.of("amount", 25.00, "currency", "usd"), new org.springframework.mock.web.MockHttpServletRequest());

            assertThat(res.getStatusCode().value()).isEqualTo(400);
            assertThat(String.valueOf(body(res).get("error"))).contains("Invalid or expired");
        }
    }
}
