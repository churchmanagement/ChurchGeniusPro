package com.churchgeniuspro.publicscreens;

import com.churchgeniuspro.controller.PublicScreensController;
import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import com.churchgeniuspro.service.MessagingPolicy;
import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.service.TestDataService;
import com.churchgeniuspro.util.EncryptionUtil;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which pages Public Screens will publish, and for whom.
 *
 * <p>Three enforcement points have to agree or the restriction is decorative: the
 * dropdown, the generate endpoint, and — the one usually forgotten — resolution of
 * a link somebody already holds. Each is asserted separately below, because
 * removing an option from a list is easy and the other two are what make it real.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Public Screens — page options")
class PublicScreenOptionsTest {

    private static final String REGULAR = "CHR-real-church-01";
    private static final String TRIAL   = "CHR-on-trial-plan-02";
    private static final String DEMO    = TestDataService.DEMO_CLIENT_PREFIX + "1757300000123";
    private static final String TRIAL_TENANT = TestDataService.TRIAL_CLIENT_PREFIX + "1757300000456";

    private static final String MID_REG   = "/midRegMeetRsvp";
    private static final String GUESS_IT  = "/guessIt";
    private static final String PRAYER_PUBLIC   = "/publicPrayer";        // KEEP
    private static final String PRAYER_INTERNAL = "/viewPrayerRequest";   // withdrawn

    @Mock private PublicScreenLinkRepository linkRepo;
    @Mock private SubscriptionService        subscriptions;
    @Mock private MessagingPolicy            messagingPolicy;

    private PublicScreensController controller;

    @BeforeEach
    void setUp() {
        controller = new PublicScreensController(linkRepo, policy(), new com.churchgeniuspro.service.PublicLinkResolver(linkRepo, policy()));
        ReflectionTestUtils.setField(controller, "baseUrl", "https://churchgeniuspro.net");
        // Every plan feature on, so nothing below is confused with a plan gate.
        when(subscriptions.isFeatureEnabled(anyString(), anyString())).thenReturn(true);
        when(messagingPolicy.trialState(REGULAR)).thenReturn(Boolean.FALSE);
        when(messagingPolicy.trialState(TRIAL)).thenReturn(Boolean.TRUE);
        when(messagingPolicy.trialState(DEMO)).thenReturn(Boolean.FALSE);   // demo need not be Trial
        when(messagingPolicy.trialState(TRIAL_TENANT)).thenReturn(Boolean.TRUE);
        when(linkRepo.save(any(PublicScreenLink.class))).thenAnswer(i -> i.getArgument(0));
    }

    private MockHttpServletRequest requestFor(String clientId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("appClientId", clientId);
        session.setAttribute("clientId", clientId);
        session.setAttribute("username", "admin@example.org");
        session.setAttribute("role", "Admin");
        req.setSession(session);
        return req;
    }

    private List<String> offeredUrls(String clientId) {
        ResponseEntity<List<Map<String, String>>> res = controller.getAvailablePages(requestFor(clientId));
        return res.getBody().stream().map(m -> m.get("url")).toList();
    }

    private List<String> offeredLabels(String clientId) {
        ResponseEntity<List<Map<String, String>>> res = controller.getAvailablePages(requestFor(clientId));
        return res.getBody().stream().map(m -> m.get("label")).toList();
    }

    private ResponseEntity<Map<String, Object>> generate(String clientId, String pageUrl) {
        Map<String, Object> body = new HashMap<>();
        body.put("pageUrl", pageUrl);
        body.put("pageLabel", pageUrl);
        return controller.generate(body, requestFor(clientId));
    }

    /** A live link, as it would exist for a token generated earlier. */
    private String existingLink(String clientId, String pageUrl) throws Exception {
        String token = EncryptionUtil.encrypt(clientId + "|" + pageUrl);
        PublicScreenLink link = new PublicScreenLink();
        link.setToken(token);
        link.setAppClientId(clientId);
        link.setPageUrl(pageUrl);
        link.setRevoked(false);
        when(linkRepo.findByToken(token)).thenReturn(Optional.of(link));
        return token;
    }

    private String resolve(String token) {
        return controller.publicAccess(token, new MockHttpServletRequest(), new MockHttpServletResponse());
    }

    /* ── withdrawn for everyone ─────────────────────────────────────────── */

    @Nested
    @DisplayName("withdrawn for all accounts")
    class Withdrawn {

        @Test
        @DisplayName("the five withdrawn options are gone from the dropdown")
        void goneFromTheDropdown() {
            assertThat(offeredLabels(REGULAR))
                    .doesNotContain("Events", "Groups", "Meetings", "Prayer Requests", "Certificates");
            assertThat(offeredUrls(REGULAR))
                    .doesNotContain("/event", "/groups", "/meetings", PRAYER_INTERNAL, "/certificates");
        }

        @Test
        @DisplayName("Prayer Request (Public) is NOT withdrawn — it is a different page")
        void publicPrayerFormSurvives() {
            // The one that must not be caught by the Prayer Requests removal.
            assertThat(offeredLabels(REGULAR)).contains("Prayer Request (Public)");
            assertThat(offeredUrls(REGULAR)).contains(PRAYER_PUBLIC);
            assertThat(generate(REGULAR, PRAYER_PUBLIC).getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("generation is refused even by calling the endpoint directly")
        void generationRefused() {
            for (String url : List.of("/event", "/groups", "/meetings", PRAYER_INTERNAL, "/certificates")) {
                ResponseEntity<Map<String, Object>> res = generate(REGULAR, url);
                assertThat(res.getStatusCode().value()).as("POST %s", url).isEqualTo(400);
                assertThat(res.getBody().get("error")).asString().contains("no longer available");
            }
            verify(linkRepo, never()).save(any(PublicScreenLink.class));
        }

        @Test
        @DisplayName("a link generated before the change stops resolving")
        void existingLinkStopsWorking() throws Exception {
            String token = existingLink(REGULAR, "/groups");

            // Otherwise every link issued before today would be a standing bypass
            // and the withdrawal would apply only to new ones.
            assertThat(resolve(token)).isEqualTo("redirect:/login");
        }
    }

    /* ── the nine that remain ───────────────────────────────────────────── */

    @Test
    @DisplayName("a regular account is offered exactly the nine expected options (Midwest Region Meet retired)")
    void regularAccountSeesTheFullList() {
        assertThat(offeredLabels(REGULAR)).containsExactly(
                "Event Calendar", "Membership Form", "Donation Page", "Member Signup",
                "SMS Opt-In Form", "Guess It",
                "Kids Check-In", "Connect With Us", "Prayer Request (Public)");
    }

    /* ── Midwest Region Meet retired ────────────────────────────────────── */

    @Test
    @DisplayName("Midwest Region Meet RSVP is not offered, cannot be generated, and old links no longer open")
    void midwestRegionMeetRetired() throws Exception {
        for (String tenant : List.of(REGULAR, TRIAL, DEMO, TRIAL_TENANT)) {
            assertThat(offeredLabels(tenant)).as(tenant).doesNotContain("Midwest Region Meet RSVP");
            ResponseEntity<Map<String, Object>> res = generate(tenant, MID_REG);
            assertThat(res.getStatusCode().value()).as(tenant).isEqualTo(400);
        }
        assertThat(com.churchgeniuspro.service.PublicPagePolicy.AVAILABLE_PAGES)
                .noneMatch(p -> p.get("url").startsWith(MID_REG));
        String regularToken = existingLink(REGULAR, MID_REG);
        assertThat(resolve(regularToken)).isEqualTo("redirect:/login");
    }

    /* ── hidden for Trial and Demo ──────────────────────────────────────── */

    @Nested
    @DisplayName("restricted for Trial and Demo")
    class TrialAndDemo {

        @Test
        @DisplayName("a Trial subscription is not offered the restricted option")
        void trialSubscription() {
            assertThat(offeredLabels(TRIAL))
                    .doesNotContain("Guess It")
                    // ...and keeps everything else, which is the half that breaks quietly.
                    .contains("Event Calendar", "Membership Form", "Donation Page",
                              "Member Signup", "SMS Opt-In Form", "Kids Check-In",
                              "Connect With Us", "Prayer Request (Public)");
        }

        @Test
        @DisplayName("a DEMO- tenant is restricted even when its plan is not Trial")
        void demoTenantEvenOnAPaidPlan() {
            // loadSmallDemo can create a demo tenant on any plan, so the prefix
            // check is what covers it — the subscription check would not.
            assertThat(messagingPolicy.trialState(DEMO)).isFalse();
            assertThat(offeredLabels(DEMO)).doesNotContain("Guess It");
        }

        @Test
        @DisplayName("a TRIAL- tenant is restricted by prefix as well as plan")
        void trialPrefixTenant() {
            assertThat(offeredLabels(TRIAL_TENANT)).doesNotContain("Guess It");
        }

        @Test
        @DisplayName("generation is refused for Trial and Demo, allowed for a regular account")
        void generationGate() {
            for (String tenant : List.of(TRIAL, DEMO, TRIAL_TENANT)) {
                for (String url : List.of(GUESS_IT)) {
                    ResponseEntity<Map<String, Object>> res = generate(tenant, url);
                    assertThat(res.getStatusCode().value()).as("%s %s", tenant, url).isEqualTo(400);
                    assertThat(res.getBody().get("error")).asString().contains("trial or demo");
                }
            }
            // Unchanged for everyone else — the point of the restriction.
            assertThat(generate(REGULAR, GUESS_IT).getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("a link held by a Trial tenant cannot be used to bypass the restriction")
        void existingLinkCannotBypass() throws Exception {
            String trialToken   = existingLink(TRIAL, GUESS_IT);
            String demoToken    = existingLink(DEMO, GUESS_IT);
            String regularToken = existingLink(REGULAR, GUESS_IT);

            // A link minted before the tenant became a trial — or before this
            // change — is re-checked against the tenant it belongs to.
            assertThat(resolve(trialToken)).isEqualTo("redirect:/login");
            assertThat(resolve(demoToken)).isEqualTo("redirect:/login");
            // The same page still resolves for an account that may have it — to the
            // page's own address, carrying the token it reads (audit P11).
            assertThat(resolve(regularToken)).isEqualTo("redirect:" + GUESS_IT + "?cid="
                    + java.net.URLEncoder.encode(regularToken, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /* ── plan features still apply on top ───────────────────────────────── */

    @Test
    @DisplayName("Guess It also follows the Activity Corner plan feature")
    void guessItFollowsTheActivityCornerFeature() {
        when(subscriptions.isFeatureEnabled(REGULAR, "activityCorner")).thenReturn(false);

        assertThat(offeredLabels(REGULAR)).doesNotContain("Guess It");
        assertThat(generate(REGULAR, GUESS_IT).getStatusCode().value()).isEqualTo(400);
        // Unrelated options are untouched by that flag.
        assertThat(offeredLabels(REGULAR)).contains("Event Calendar", "Kids Check-In");
    }

    @Test
    @DisplayName("an unreadable subscription withholds the restricted options rather than publishing them")
    void lookupFailureIsRestrictive() {
        when(messagingPolicy.trialState(REGULAR)).thenThrow(new RuntimeException("db down"));

        assertThat(offeredLabels(REGULAR)).doesNotContain("Guess It");
        assertThat(offeredLabels(REGULAR)).contains("Event Calendar", "Prayer Request (Public)");
    }

    private com.churchgeniuspro.service.PublicPagePolicy policy() {
        return new com.churchgeniuspro.service.PublicPagePolicy(subscriptions, messagingPolicy);
    }
}
