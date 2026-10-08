package com.churchgeniuspro.subscription;

import com.churchgeniuspro.controller.DonationController;
import com.churchgeniuspro.repository.ChurchLogoRepository;
import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.StripeSettings;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.repository.DonationRepository;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.StripeSettingsRepository;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import com.churchgeniuspro.repository.SubscriptionUsageRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.MessagingPolicy;
import com.churchgeniuspro.service.PublicPagePolicy;
import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.service.TestDataService;
import com.churchgeniuspro.util.SubscriptionFeatureCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * M1 + M2: online giving and Midwest Region Meet are properly gated.
 *
 * <p><b>M1.</b> Nothing said an evaluation tenant may not take money. The rule was
 * only that a trial has no Stripe keys — and the screen for entering them was not
 * gated either, so a trial admin could put in their own, publish a donate link and
 * accept live donations.
 *
 * <p><b>M2.</b> The Midwest Region Meet restriction covered the public RSVP link
 * and nothing else: the whole admin surface was role-guarded only, and the
 * restricted-page test was an exact string match on a caller-supplied
 * {@code pageUrl}, so {@code "/midRegMeetRsvp?x"} walked past it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Evaluation gating — giving and Midwest Meet (M1, M2)")
class EvaluationGatingTest {

    private static final String DEMO   = TestDataService.DEMO_CLIENT_PREFIX + "1757300000123";
    private static final String TRIAL  = "CHR-on-trial-plan";
    private static final String PAYING = "CHR-paying-church";

    @Mock ServiceClientRepository     clientRepo;
    @Mock SubscriptionPlanRepository  planRepo;
    @Mock SubscriptionUsageRepository usageRepo;
    @Mock MessagingPolicy             messagingPolicy;

    private SubscriptionService subs;
    private PublicPagePolicy policy;

    @BeforeEach
    void setUp() {
        subs = new SubscriptionService(clientRepo, planRepo, usageRepo);
        subs.setMessagingPolicy(messagingPolicy);
        policy = new PublicPagePolicy(subs, messagingPolicy);

        // A Pro plan with nothing disabled, so anything withheld below comes from the
        // tenant being an evaluation account rather than from plan configuration.
        SubscriptionPlan pro = new SubscriptionPlan();
        pro.setPlanCode("PRO");
        pro.setPlanName("Pro Plan");
        pro.setActive(true);
        pro.setFeaturesJson("{}");
        when(planRepo.findByPlanCodeIgnoreCase("PRO")).thenReturn(Optional.of(pro));
        for (String id : new String[] { DEMO, TRIAL, PAYING }) {
            ServiceClient sc = new ServiceClient();
            sc.setClientId(id);
            sc.setSubscriptionType("FULL");          // legacy value mapping to PRO
            when(clientRepo.findByClientId(id)).thenReturn(Optional.of(sc));
        }
        when(messagingPolicy.trialState(DEMO)).thenReturn(Boolean.FALSE);   // plan says Pro
        when(messagingPolicy.trialState(TRIAL)).thenReturn(Boolean.TRUE);
        when(messagingPolicy.trialState(PAYING)).thenReturn(Boolean.FALSE);
    }

    /* ── the overlay ────────────────────────────────────────────────────── */

    @Nested
    @DisplayName("the evaluation overlay")
    class Overlay {

        @ParameterizedTest(name = "{0} is withheld from evaluation tenants")
        @ValueSource(strings = { "onlineGiving", "activityCorner" })
        void withheldFromEvaluationTenants(String feature) {
            assertThat(subs.isFeatureEnabled(DEMO, feature)).isFalse();
            assertThat(subs.isFeatureEnabled(TRIAL, feature)).isFalse();
            assertThat(subs.featureMap(DEMO)).containsEntry(feature, false);
        }

        @ParameterizedTest(name = "{0} is untouched for a paying church")
        @ValueSource(strings = { "onlineGiving", "activityCorner" })
        void payingChurchKeepsEverything(String feature) {
            assertThat(subs.isFeatureEnabled(PAYING, feature)).isTrue();
            assertThat(subs.featureMap(PAYING)).doesNotContainKey(feature);
        }

        @Test
        @DisplayName("a plan that disables the feature still governs everyone else")
        void planFlagStillApplies() {
            SubscriptionPlan free = new SubscriptionPlan();
            free.setPlanCode("FREE");
            free.setActive(true);
            free.setFeaturesJson("{\"onlineGiving\":false}");
            when(planRepo.findByPlanCodeIgnoreCase("FREE")).thenReturn(Optional.of(free));
            ServiceClient sc = new ServiceClient();
            sc.setClientId("CHR-free");
            sc.setSubscriptionType("FREE");
            when(clientRepo.findByClientId("CHR-free")).thenReturn(Optional.of(sc));
            when(messagingPolicy.trialState("CHR-free")).thenReturn(Boolean.FALSE);

            assertThat(subs.isFeatureEnabled("CHR-free", "onlineGiving")).isFalse();
            assertThat(subs.isFeatureEnabled("CHR-free", "activityCorner")).isTrue();   // missing key = enabled
        }
    }

    /* ── the paths those features cover ─────────────────────────────────── */

    @Nested
    @DisplayName("the catalog")
    class Catalog {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
            // M1: the church's own Stripe configuration is gated, so a plan without
            // Online Giving cannot put keys in place to begin with.
            "/stripeIntegration,                onlineGiving",
            "/stripeIntegration.html,           onlineGiving",
            "/api/stripe-settings,              onlineGiving",
            // M6: Kids Check-in had a feature key that gated nothing at all.
            "/api/kids-ministry/checkins,       kidsCheckin",
            "/api/kids-ministry/checkins/active, kidsCheckin",
            "/api/kids-ministry/checkin-cid,    kidsCheckin",
            "/pickup-dashboard,                 kidsCheckin",
            // ...and the rest of Kids Ministry is still its own feature.
            "/api/kids-ministry/children,       kidsMinistry",
        })
        void pathsAreGated(String path, String expected) {
            assertThat(SubscriptionFeatureCatalog.keyForPath(path)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} stays reachable without a session")
        @ValueSource(strings = {
            "/api/public/donate/config",        // the donor's page explains itself
            "/api/public/donate/intent",
            "/api/public/kids-checkin/search",  // the kiosk
        })
        void publicEndpointsAreNotGated(String path) {
            assertThat(SubscriptionFeatureCatalog.keyForPath(path)).isNull();
        }
    }

    /* ── public links ───────────────────────────────────────────────────── */

    @Nested
    @DisplayName("public links")
    class PublicLinks {

        @Test
        @DisplayName("the retired Midwest Meet RSVP link is refused to everyone, paying churches included")
        void rsvpRetiredForEveryone() {
            assertThat(policy.mayPublish(PublicPagePolicy.MID_REG_MEET_RSVP_URL, DEMO)).isFalse();
            assertThat(policy.mayPublish(PublicPagePolicy.MID_REG_MEET_RSVP_URL, TRIAL)).isFalse();
            assertThat(policy.mayPublish(PublicPagePolicy.MID_REG_MEET_RSVP_URL, PAYING)).isFalse();
            assertThat(policy.denialReason(PublicPagePolicy.MID_REG_MEET_RSVP_URL, PAYING))
                    .contains("no longer available");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "/midRegMeetRsvp?x",        // a query string defeated the exact match
            "/midRegMeetRsvp/",
            "/midRegMeetRsvp.html",
            "/guessIt?x",
            "//evil.example",           // and this became a redirect off-site
            "/home",
            "/viewUsers",
        })
        @DisplayName("a page that is not on the list cannot be published at all")
        void unknownPagesRefused(String pageUrl) {
            assertThat(policy.mayPublish(pageUrl, PAYING)).isFalse();
            assertThat(policy.mayPublish(pageUrl, DEMO)).isFalse();
        }

        @Test
        @DisplayName("every page the dropdown offers is publishable by a paying church")
        void everyOfferedPageIsPublishable() {
            for (Map<String, String> page : PublicPagePolicy.AVAILABLE_PAGES) {
                assertThat(policy.denialReason(page.get("url"), PAYING))
                        .as("dropdown offers %s", page.get("label"))
                        .isNull();
            }
        }

        @Test
        @DisplayName("the donate link follows the Online Giving feature, so evaluation tenants lose it")
        void donateLinkFollowsTheFeature() {
            assertThat(policy.mayPublish(PublicPagePolicy.DONATION_PAGE_URL, PAYING)).isTrue();
            assertThat(policy.mayPublish(PublicPagePolicy.DONATION_PAGE_URL, DEMO)).isFalse();
            assertThat(policy.mayPublish(PublicPagePolicy.DONATION_PAGE_URL, TRIAL)).isFalse();
        }
    }

    /* ── the donation endpoints ─────────────────────────────────────────── */

    @Nested
    @DisplayName("recording a donation")
    class Saving {

        @Mock DonationRepository         donationRepo;
        @Mock StripeSettingsRepository   stripeRepo;
        @Mock PublicScreenLinkRepository linkRepo;
        @Mock ChurchLogoRepository       logoRepo;
        @Mock EmailService               emailService;
        @Mock SubscriptionService        subscriptionService;
        @Mock com.churchgeniuspro.service.DonationIncomePostingService donationIncomePoster;

        private DonationController controller;

        @BeforeEach
        void setUp() {
            controller = new DonationController(donationRepo, stripeRepo, linkRepo,
                    clientRepo, logoRepo, emailService, subscriptionService, donationIncomePoster,
                    new com.churchgeniuspro.util.PublicSendLimiter());

            PublicScreenLink link = new PublicScreenLink();
            link.setToken("tok");
            link.setAppClientId(PAYING);
            link.setPageUrl(PublicPagePolicy.DONATION_PAGE_URL);
            link.setRevoked(false);
            when(linkRepo.findByToken("tok")).thenReturn(Optional.of(link));

            StripeSettings stripe = new StripeSettings();
            stripe.setPublishableKey("pk_test_x");
            stripe.setSecretKey("sk_test_x");
            when(stripeRepo.findByClientId(PAYING)).thenReturn(Optional.of(stripe));
        }

        private Map<String, Object> body() {
            Map<String, Object> b = new HashMap<>();
            b.put("paymentIntentId", "pi_123");
            b.put("amount", "25.00");
            return b;
        }

        @Test
        @DisplayName("a donation is refused at save when the feature has gone away since the intent")
        void saveRefusedWhenFeatureRevoked() {
            when(subscriptionService.isFeatureEnabled(PAYING, "onlineGiving")).thenReturn(false);
            ResponseEntity<?> res = controller.saveDonation("tok", body());
            assertThat(res.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        @DisplayName("a permitted donation is still recorded")
        void savePermitted() {
            when(subscriptionService.isFeatureEnabled(PAYING, "onlineGiving")).thenReturn(true);
            when(donationRepo.findByStripePaymentIntentId("pi_123")).thenReturn(Optional.empty());
            ResponseEntity<?> res = controller.saveDonation("tok", body());
            assertThat(res.getStatusCode().value()).isNotEqualTo(403);
        }
    }
}
