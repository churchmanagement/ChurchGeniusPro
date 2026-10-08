package com.churchgeniuspro.messaging;

import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.service.DemoAccessService;
import com.churchgeniuspro.service.MessagingPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * A church registered with Subscription Type TRIAL is evaluating the product; it
 * must not text or email real congregants on the platform's Twilio account and
 * mail reputation.
 *
 * <p>The rule is derived from {@code service_client.subscription_type} rather than
 * copied into a flag at registration, so these tests also pin the two properties
 * that choice buys: it applies the moment the client exists, and it lifts by
 * itself on upgrade.
 */
class MessagingPolicyTest {

    private static final String CLIENT = "CHR-1";

    private static ServiceClient client(String subscriptionType) {
        ServiceClient sc = new ServiceClient();
        sc.setClientId(CLIENT);
        sc.setSubscriptionType(subscriptionType);
        return sc;
    }

    private static MessagingPolicy policyFor(ServiceClient sc) {
        ServiceClientRepository repo = mock(ServiceClientRepository.class);
        when(repo.findByClientId(anyString())).thenReturn(Optional.ofNullable(sc));
        return new MessagingPolicy(repo);
    }

    @Test
    @DisplayName("Trial blocks both SMS and email")
    void trialBlocksBoth() {
        MessagingPolicy p = policyFor(client("TRIAL"));
        assertThat(p.smsAllowed(CLIENT)).isFalse();
        assertThat(p.emailAllowed(CLIENT)).isFalse();
        assertThat(p.smsBlockReason(CLIENT)).isEqualTo(MessagingPolicy.TRIAL_SMS_MSG);
        assertThat(p.emailBlockReason(CLIENT)).isEqualTo(MessagingPolicy.TRIAL_EMAIL_MSG);
    }

    @Test
    @DisplayName("Trial is matched case- and whitespace-insensitively")
    void trialMatchIsForgiving() {
        for (String v : new String[]{"trial", " Trial ", "TRIAL"}) {
            assertThat(policyFor(client(v)).smsAllowed(CLIENT))
                    .as("subscriptionType=%s", v).isFalse();
        }
    }

    @Test
    @DisplayName("Every other subscription type keeps working")
    void otherPlansUnaffected() {
        for (String v : new String[]{"FREE", "LIMITED", "FULL", "STANDARD", "PRO", null, ""}) {
            MessagingPolicy p = policyFor(client(v));
            assertThat(p.smsAllowed(CLIENT)).as("sms for %s", v).isTrue();
            assertThat(p.emailAllowed(CLIENT)).as("email for %s", v).isTrue();
        }
    }

    @Test
    @DisplayName("An upgrade out of Trial lifts the block once the cache is dropped")
    void upgradeLiftsTheBlock() {
        ServiceClientRepository repo = mock(ServiceClientRepository.class);
        ServiceClient sc = client("TRIAL");
        when(repo.findByClientId(CLIENT)).thenReturn(Optional.of(sc));
        MessagingPolicy p = new MessagingPolicy(repo);

        assertThat(p.smsAllowed(CLIENT)).isFalse();
        sc.setSubscriptionType("FULL");
        p.invalidate(CLIENT);                       // what ServiceClientService.update does
        assertThat(p.smsAllowed(CLIENT)).isTrue();
        assertThat(p.emailAllowed(CLIENT)).isTrue();
    }

    @Test
    @DisplayName("An unknown client is not treated as Trial")
    void unknownClientAllowed() {
        assertThat(policyFor(null).smsAllowed(CLIENT)).isTrue();
    }

    @Test
    @DisplayName("A tenant-less send is allowed — pre-login mail and admin broadcasts have no plan")
    void blankTenantAllowed() {
        MessagingPolicy p = policyFor(client("TRIAL"));
        assertThat(p.smsAllowed(null)).isTrue();
        assertThat(p.emailAllowed("")).isTrue();
        assertThat(p.smsBlockReason("   ")).isNull();
    }

    @Test
    @DisplayName("A lookup failure fails open — a database blip must not silence a paying church")
    void lookupFailureFailsOpen() {
        ServiceClientRepository repo = mock(ServiceClientRepository.class);
        when(repo.findByClientId(anyString())).thenThrow(new RuntimeException("db down"));
        MessagingPolicy p = new MessagingPolicy(repo);
        assertThat(p.smsAllowed(CLIENT)).isTrue();
        assertThat(p.emailAllowed(CLIENT)).isTrue();
    }

    @Test
    @DisplayName("The demo block still applies on top of the Trial rule")
    void demoBlockStillApplies() {
        MessagingPolicy p = policyFor(client("FULL"));       // not a trial
        DemoAccessService demo = mock(DemoAccessService.class);
        when(demo.sendingAllowed(anyString(), anyBoolean())).thenReturn(false);
        p.setDemoAccess(demo);
        assertThat(p.smsAllowed(CLIENT)).isFalse();
        assertThat(p.emailBlockReason(CLIENT)).isEqualTo(MessagingPolicy.DEMO_EMAIL_MSG);
    }

    @Test
    @DisplayName("Trial takes precedence over an explicitly-allowed demo tenant")
    void trialWinsOverDemoAllowance() {
        MessagingPolicy p = policyFor(client("TRIAL"));
        DemoAccessService demo = mock(DemoAccessService.class);
        when(demo.sendingAllowed(anyString(), anyBoolean())).thenReturn(true);
        p.setDemoAccess(demo);
        assertThat(p.smsBlockReason(CLIENT)).isEqualTo(MessagingPolicy.TRIAL_SMS_MSG);
    }

    @Test
    @DisplayName("The answer is cached, so a bulk send costs one lookup, not one per recipient")
    void answerIsCached() {
        ServiceClientRepository repo = mock(ServiceClientRepository.class);
        when(repo.findByClientId(CLIENT)).thenReturn(Optional.of(client("TRIAL")));
        MessagingPolicy p = new MessagingPolicy(repo);
        for (int i = 0; i < 50; i++) p.emailAllowed(CLIENT);
        verify(repo, times(1)).findByClientId(CLIENT);
    }
}
