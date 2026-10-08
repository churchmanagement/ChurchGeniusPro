package com.churchgeniuspro.plaid;

import com.churchgeniuspro.plaid.config.PlaidProperties;
import com.churchgeniuspro.plaid.entity.PlaidItem;
import com.churchgeniuspro.plaid.service.PlaidEnvironmentService;
import com.churchgeniuspro.service.MessagingPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Trial subscriptions are confined to the Plaid sandbox.
 *
 * <p>The guarantee under test is one-directional: a Trial tenant must never be
 * handed production credentials or a production base URL, by any path — including
 * the failure paths, which are where a containment rule usually leaks. Refusing is
 * always acceptable; quietly reaching production never is.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Plaid — Trial sandbox confinement")
class PlaidTrialSandboxTest {

    private static final String TRIAL_TENANT = "CHR-trial-01";
    private static final String PAID_TENANT  = "CHR-paid-02";

    @Mock private MessagingPolicy messagingPolicy;

    private PlaidProperties props;
    private PlaidEnvironmentService envService;

    /** A production deployment that has been given a sandbox secret too. */
    @BeforeEach
    void setUp() {
        props = new PlaidProperties();
        props.setClientId("cid-shared");
        props.setSecret("PROD-SECRET");
        props.setEnv("production");
        props.setBaseUrl("https://production.plaid.com");
        props.setSandboxSecret("SANDBOX-SECRET");
        props.setSandboxBaseUrl("https://sandbox.plaid.com");
        envService = new PlaidEnvironmentService(props, messagingPolicy);

        when(messagingPolicy.trialState(TRIAL_TENANT)).thenReturn(Boolean.TRUE);
        when(messagingPolicy.trialState(PAID_TENANT)).thenReturn(Boolean.FALSE);
    }

    /* ── credential separation ──────────────────────────────────────────── */

    @Nested
    @DisplayName("credentials")
    class Credentials {

        @Test
        @DisplayName("each environment gets its own secret and base URL")
        void secretsAreSeparate() {
            assertThat(props.secretFor(PlaidProperties.SANDBOX)).isEqualTo("SANDBOX-SECRET");
            assertThat(props.secretFor(PlaidProperties.PRODUCTION)).isEqualTo("PROD-SECRET");
            assertThat(props.baseUrlFor(PlaidProperties.SANDBOX)).isEqualTo("https://sandbox.plaid.com");
            assertThat(props.baseUrlFor(PlaidProperties.PRODUCTION)).isEqualTo("https://production.plaid.com");
        }

        @Test
        @DisplayName("the production secret is never used for a sandbox call")
        void productionSecretNeverLeaksIntoSandbox() {
            props.setSandboxSecret("");          // not configured
            // No silent fallback: an unconfigured sandbox has no secret at all,
            // rather than borrowing production's.
            assertThat(props.secretFor(PlaidProperties.SANDBOX)).isEmpty();
            assertThat(props.isConfiguredFor(PlaidProperties.SANDBOX)).isFalse();
        }

        @Test
        @DisplayName("the redirect URI falls back to the production one for sandbox")
        void redirectUriFallsBack() {
            props.setRedirectUri("https://churchgeniuspro.net/plaid-oauth.html");

            // One URI serves both, provided it is registered under Sandbox as well
            // as Production in the Plaid Dashboard.
            assertThat(props.redirectUriFor(PlaidProperties.SANDBOX))
                    .isEqualTo("https://churchgeniuspro.net/plaid-oauth.html");
            assertThat(props.redirectUriFor(PlaidProperties.PRODUCTION))
                    .isEqualTo("https://churchgeniuspro.net/plaid-oauth.html");
        }

        @Test
        @DisplayName("a sandbox-specific redirect URI overrides the fallback")
        void redirectUriOverride() {
            props.setRedirectUri("https://churchgeniuspro.net/plaid-oauth.html");
            props.setSandboxRedirectUri("https://sandbox.churchgeniuspro.net/plaid-oauth.html");

            assertThat(props.redirectUriFor(PlaidProperties.SANDBOX))
                    .isEqualTo("https://sandbox.churchgeniuspro.net/plaid-oauth.html");
            // The production URI is never affected by the sandbox override.
            assertThat(props.redirectUriFor(PlaidProperties.PRODUCTION))
                    .isEqualTo("https://churchgeniuspro.net/plaid-oauth.html");
        }

        @Test
        @DisplayName("a sandbox deployment reuses its main secret, needing no new config")
        void sandboxDeploymentNeedsNoExtraSecret() {
            props.setEnv("sandbox");
            props.setSandboxSecret("");
            assertThat(props.secretFor(PlaidProperties.SANDBOX)).isEqualTo("PROD-SECRET");
            assertThat(props.isConfiguredFor(PlaidProperties.SANDBOX)).isTrue();
        }
    }

    /* ── who goes where ─────────────────────────────────────────────────── */

    @Test
    @DisplayName("Trial resolves to sandbox")
    void trialGoesToSandbox() {
        assertThat(envService.envForClient(TRIAL_TENANT)).isEqualTo(PlaidProperties.SANDBOX);
    }

    @Test
    @DisplayName("a paid subscription keeps the deployment's environment")
    void paidKeepsProduction() {
        assertThat(envService.envForClient(PAID_TENANT)).isEqualTo(PlaidProperties.PRODUCTION);
    }

    @Test
    @DisplayName("a paid subscription is unaffected when no sandbox secret exists")
    void paidUnaffectedByMissingSandboxSecret() {
        props.setSandboxSecret("");
        assertThat(envService.envForClient(PAID_TENANT)).isEqualTo(PlaidProperties.PRODUCTION);
    }

    /* ── the failure paths, where containment usually leaks ─────────────── */

    @Test
    @DisplayName("an unreadable subscription refuses rather than assuming production")
    void unknownSubscriptionRefuses() {
        when(messagingPolicy.trialState(TRIAL_TENANT)).thenReturn(null);

        assertThatThrownBy(() -> envService.envForClient(TRIAL_TENANT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("subscription");
    }

    @Test
    @DisplayName("a missing sandbox secret refuses instead of promoting Trial to production")
    void missingSandboxSecretRefuses() {
        props.setSandboxSecret("");

        assertThatThrownBy(() -> envService.envForClient(TRIAL_TENANT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trial");
        // The point of the test: the refusal above, not a production environment.
        assertThat(props.secretFor(PlaidProperties.SANDBOX)).isEmpty();
    }

    /* ── existing connections follow their own stamp, not today's plan ──── */

    @Test
    @DisplayName("an item is used in the environment that issued its token")
    void itemUsesItsStamp() {
        PlaidItem sandboxItem = new PlaidItem();
        sandboxItem.setPlaidEnv("sandbox");
        PlaidItem prodItem = new PlaidItem();
        prodItem.setPlaidEnv("production");

        assertThat(envService.envForItem(sandboxItem)).isEqualTo(PlaidProperties.SANDBOX);
        assertThat(envService.envForItem(prodItem)).isEqualTo(PlaidProperties.PRODUCTION);
    }

    @Test
    @DisplayName("an upgraded tenant's old sandbox items stay on sandbox")
    void upgradeDoesNotRepointOldItems() {
        // Tenant is no longer Trial...
        when(messagingPolicy.trialState(TRIAL_TENANT)).thenReturn(Boolean.FALSE);
        PlaidItem oldSandboxItem = new PlaidItem();
        oldSandboxItem.setClientId(TRIAL_TENANT);
        oldSandboxItem.setPlaidEnv("sandbox");

        // ...but its token is still a sandbox token, and only sandbox can use it.
        assertThat(envService.envForItem(oldSandboxItem)).isEqualTo(PlaidProperties.SANDBOX);
        // New connections do follow the new plan.
        assertThat(envService.envForClient(TRIAL_TENANT)).isEqualTo(PlaidProperties.PRODUCTION);
    }

    @Test
    @DisplayName("a pre-migration item falls back to the deployment's environment")
    void unstampedItemFallsBack() {
        PlaidItem legacy = new PlaidItem();          // plaidEnv null
        assertThat(envService.envForItem(legacy)).isEqualTo(PlaidProperties.PRODUCTION);
    }

    @Test
    @DisplayName("using an existing item never consults the subscription")
    void itemPathSurvivesAnUnreadableSubscription() {
        when(messagingPolicy.trialState(TRIAL_TENANT))
                .thenThrow(new RuntimeException("db down"));
        PlaidItem item = new PlaidItem();
        item.setClientId(TRIAL_TENANT);
        item.setPlaidEnv("sandbox");

        // The scheduled sync must keep working through a database blip.
        assertThat(envService.envForItem(item)).isEqualTo(PlaidProperties.SANDBOX);
    }

    /* ── normalisation ──────────────────────────────────────────────────── */

    @Test
    @DisplayName("anything that is not sandbox normalises to production")
    void normalisation() {
        assertThat(PlaidProperties.normaliseEnv("sandbox")).isEqualTo(PlaidProperties.SANDBOX);
        assertThat(PlaidProperties.normaliseEnv("SANDBOX")).isEqualTo(PlaidProperties.SANDBOX);
        assertThat(PlaidProperties.normaliseEnv("  Sandbox ")).isEqualTo(PlaidProperties.SANDBOX);
        assertThat(PlaidProperties.normaliseEnv("production")).isEqualTo(PlaidProperties.PRODUCTION);
        assertThat(PlaidProperties.normaliseEnv("development")).isEqualTo(PlaidProperties.PRODUCTION);
        assertThat(PlaidProperties.normaliseEnv(null)).isEqualTo(PlaidProperties.PRODUCTION);
        assertThat(PlaidProperties.normaliseEnv("")).isEqualTo(PlaidProperties.PRODUCTION);
    }

    /* ── isTrial keeps its documented fail-open ─────────────────────────── */

    @Test
    @DisplayName("splitting out trialState leaves isTrial's fail-open behaviour intact")
    void isTrialStillFailsOpen() {
        com.churchgeniuspro.repository.ServiceClientRepository repo =
                org.mockito.Mockito.mock(com.churchgeniuspro.repository.ServiceClientRepository.class);
        when(repo.findByClientId("CHR-x")).thenThrow(new RuntimeException("db down"));
        MessagingPolicy real = new MessagingPolicy(repo);

        // An unreadable subscription still answers "not Trial" for messaging, so a
        // database blip cannot silence a paying church's reminders — while
        // trialState surfaces the uncertainty the Plaid path needs.
        assertThat(real.isTrial("CHR-x")).isFalse();
        assertThat(real.trialState("CHR-x")).isNull();
    }
}
