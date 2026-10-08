package com.churchgeniuspro.trial;

import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import com.churchgeniuspro.repository.SubscriptionUsageRepository;
import com.churchgeniuspro.service.MessagingPolicy;
import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.service.TestDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Activity Corner is withheld from evaluation tenants whatever their plan says.
 *
 * <p>This exists because the first attempt at the restriction used only a plan
 * feature flag, and that structurally cannot work: {@code loadSmallDemo} creates a
 * demo tenant on ANY plan, so "disable it on the Trial plan" misses every demo
 * tenant, and misses a trial whose plan row has not been configured yet. The
 * client-id prefix needs no database and covers both.
 *
 * <p>The overlay is applied by both readers of feature state, so the server-side
 * filter and everything the front end hides stay in agreement.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Evaluation feature overlay")
class EvaluationFeatureOverlayTest {

    private static final String KEY = "activityCorner";

    private static final String PAYING = "CHR-real-church";
    private static final String TRIAL_PLAN_CLIENT = "CHR-on-trial-plan";
    private static final String DEMO  = TestDataService.DEMO_CLIENT_PREFIX + "1757300000123";
    private static final String TRIAL = TestDataService.TRIAL_CLIENT_PREFIX + "1757300000456";

    @Mock private ServiceClientRepository     clientRepo;
    @Mock private SubscriptionPlanRepository  planRepo;
    @Mock private SubscriptionUsageRepository usageRepo;
    @Mock private MessagingPolicy             messagingPolicy;

    private SubscriptionService subs;

    @BeforeEach
    void setUp() {
        subs = new SubscriptionService(clientRepo, planRepo, usageRepo);
        subs.setMessagingPolicy(messagingPolicy);

        // A Pro plan with NOTHING disabled — so anything withheld below comes from
        // the tenant being an evaluation account, not from plan configuration.
        SubscriptionPlan pro = new SubscriptionPlan();
        pro.setPlanCode("PRO");
        pro.setPlanName("Pro Plan");
        pro.setActive(true);
        pro.setFeaturesJson("{}");
        when(planRepo.findByPlanCodeIgnoreCase("PRO")).thenReturn(Optional.of(pro));

        for (String id : new String[] { PAYING, TRIAL_PLAN_CLIENT, DEMO, TRIAL }) {
            ServiceClient sc = new ServiceClient();
            sc.setClientId(id);
            sc.setSubscriptionType("FULL");           // legacy value mapping to PRO
            when(clientRepo.findByClientId(id)).thenReturn(Optional.of(sc));
        }
        when(messagingPolicy.trialState(PAYING)).thenReturn(Boolean.FALSE);
        when(messagingPolicy.trialState(TRIAL_PLAN_CLIENT)).thenReturn(Boolean.TRUE);
        when(messagingPolicy.trialState(DEMO)).thenReturn(Boolean.FALSE);
        // A self-service trial that has NOT been upgraded: its plan still says Trial.
        when(messagingPolicy.trialState(TRIAL)).thenReturn(Boolean.TRUE);
    }

    /* ── the cases the plan flag could not reach ────────────────────────── */

    @Test
    @DisplayName("a DEMO- tenant loses it even on a Pro plan with nothing disabled")
    void demoTenantOnPaidPlan() {
        // The bug: this tenant is not Trial and its plan disables nothing, so a
        // plan-flag-only implementation left Activity Corner fully visible.
        assertThat(messagingPolicy.trialState(DEMO)).isFalse();

        assertThat(subs.isFeatureEnabled(DEMO, KEY)).isFalse();
        assertThat(subs.featureMap(DEMO)).containsEntry(KEY, false);
    }

    @Test
    @DisplayName("a TRIAL- tenant loses it")
    void trialTenantByPrefix() {
        assertThat(subs.isFeatureEnabled(TRIAL, KEY)).isFalse();
        assertThat(subs.featureMap(TRIAL)).containsEntry(KEY, false);
    }

    @Test
    @DisplayName("a TRIAL- tenant still loses it when its subscription cannot be read")
    void trialTenantUnreadableSubscription() {
        // The prefix carries the answer on its own when the plan row is missing or
        // unreadable, which is why the original bug survived the migration not
        // being run. Only a subscription we can positively read as "not Trial"
        // releases a TRIAL- tenant.
        when(messagingPolicy.trialState(TRIAL)).thenReturn(null);
        assertThat(subs.isFeatureEnabled(TRIAL, KEY)).isFalse();

        when(messagingPolicy.trialState(TRIAL)).thenThrow(new RuntimeException("db down"));
        assertThat(subs.isFeatureEnabled(TRIAL, KEY)).isFalse();
    }

    @Test
    @DisplayName("a TRIAL- tenant that has been UPGRADED gets it back")
    void upgradedTrialIsReleased() {
        // The client id keeps its TRIAL- prefix for life, so treating the prefix as
        // the whole answer left a paying church restricted forever — no Activity
        // Corner, no public Guess It or RSVP link — with no way back short of
        // recreating the tenant. A demo tenant is deliberately NOT released this
        // way: DEMO- data is never a real customer, whatever plan it carries.
        when(messagingPolicy.trialState(TRIAL)).thenReturn(Boolean.FALSE);

        assertThat(subs.isFeatureEnabled(TRIAL, KEY)).isTrue();
        assertThat(subs.featureMap(TRIAL)).doesNotContainKey(KEY);
        assertThat(subs.isFeatureEnabled(DEMO, KEY)).isFalse();
    }

    @Test
    @DisplayName("a CHR- client on the Trial plan loses it too")
    void trialSubscriptionOnRegularClientId() {
        assertThat(subs.isFeatureEnabled(TRIAL_PLAN_CLIENT, KEY)).isFalse();
        assertThat(subs.featureMap(TRIAL_PLAN_CLIENT)).containsEntry(KEY, false);
    }

    /* ── and what must not change ───────────────────────────────────────── */

    @Test
    @DisplayName("a paying church keeps Activity Corner")
    void payingChurchUnaffected() {
        assertThat(subs.isFeatureEnabled(PAYING, KEY)).isTrue();
        assertThat(subs.featureMap(PAYING)).doesNotContainKey(KEY);
    }

    @Test
    @DisplayName("no other feature is touched for an evaluation tenant")
    void onlyActivityCornerIsWithheld() {
        for (String other : new String[] { "accounting", "bankSync", "groups", "reminders",
                                           "certificates", "memberPortal", "payroll" }) {
            assertThat(subs.isFeatureEnabled(DEMO, other)).as("demo %s", other).isTrue();
            assertThat(subs.isFeatureEnabled(TRIAL, other)).as("trial %s", other).isTrue();
        }
    }

    @Test
    @DisplayName("the overlay does not write into the shared plan cache")
    void overlayDoesNotPoisonTheCache() {
        // featureMap returns the cached map for the client; merging in place would
        // leak one tenant's restriction to every later reader.
        subs.featureMap(DEMO);
        assertThat(subs.featureMap(PAYING)).doesNotContainKey(KEY);
        assertThat(subs.isFeatureEnabled(PAYING, KEY)).isTrue();
    }

    @Test
    @DisplayName("an unreadable subscription does not withhold it from a paying church")
    void lookupFailureFailsOpen() {
        when(messagingPolicy.trialState(PAYING)).thenThrow(new RuntimeException("db down"));

        // Fail-open matches this service's stated contract. Safe because the
        // tenants that matter most are caught by their id, with no query at all.
        assertThat(subs.isFeatureEnabled(PAYING, KEY)).isTrue();
        assertThat(subs.isFeatureEnabled(DEMO, KEY)).isFalse();
    }

    @Test
    @DisplayName("with no gate wired at all, nothing is withheld")
    void withoutTheGateNothingChanges() {
        SubscriptionService bare = new SubscriptionService(clientRepo, planRepo, usageRepo);

        // Field injection is optional, so an unwired instance behaves exactly as
        // before this change — except for the prefix check, which needs nothing.
        assertThat(bare.isFeatureEnabled(PAYING, KEY)).isTrue();
        assertThat(bare.isFeatureEnabled(DEMO, KEY)).isFalse();
    }
}
