package com.churchgeniuspro.subscription;

import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.hibernate.TrialRegistrationLink;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import com.churchgeniuspro.repository.TrialRegistrationLinkRepository;
import com.churchgeniuspro.service.TrialPolicy;
import com.churchgeniuspro.service.TrialRegistrationLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * TrialPolicy is the one source of truth for trial length: it reads the TRIAL plan's
 * trial_days, falls back to the historical 30 when nothing usable is configured, and
 * a change is live immediately after refresh(). Links capture the value at issue
 * time and never follow later changes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TrialPolicy — configured trial duration")
class TrialPolicyTest {

    @Mock SubscriptionPlanRepository plans;
    private SubscriptionPlan trial;
    private TrialPolicy policy;

    @BeforeEach
    void setUp() {
        trial = new SubscriptionPlan();
        trial.setPlanCode("TRIAL");
        trial.setPlanName("Trial Plan");
        trial.setActive(true);
        trial.setTrialDays(60);
        when(plans.findByPlanCodeIgnoreCase("TRIAL")).thenReturn(Optional.of(trial));
        policy = new TrialPolicy(plans);
    }

    @Test @DisplayName("reads trial_days from the TRIAL plan")
    void readsPlan() {
        assertThat(policy.trialDays()).isEqualTo(60);
    }

    @Test @DisplayName("falls back to 30 when unset, out of range, inactive, or missing")
    void fallbacks() {
        trial.setTrialDays(null);
        assertThat(new TrialPolicy(plans).trialDays()).isEqualTo(TrialPolicy.FALLBACK_DAYS);
        trial.setTrialDays(0);
        assertThat(new TrialPolicy(plans).trialDays()).isEqualTo(30);
        trial.setTrialDays(9999);
        assertThat(new TrialPolicy(plans).trialDays()).isEqualTo(30);
        trial.setTrialDays(60); trial.setActive(false);
        assertThat(new TrialPolicy(plans).trialDays()).isEqualTo(30);
        when(plans.findByPlanCodeIgnoreCase("TRIAL")).thenReturn(Optional.empty());
        assertThat(new TrialPolicy(plans).trialDays()).isEqualTo(30);
    }

    @Test @DisplayName("a saved change is live at once after refresh(), without a restart")
    void refreshIsImmediate() {
        assertThat(policy.trialDays()).isEqualTo(60);
        trial.setTrialDays(90);
        assertThat(policy.trialDays()).as("still the cached value until refresh").isEqualTo(60);
        policy.refresh();
        assertThat(policy.trialDays()).isEqualTo(90);
    }

    @Test @DisplayName("resolve() honours an in-range per-link override, else the configured default")
    void resolve() {
        assertThat(policy.resolve(120)).isEqualTo(120);
        assertThat(policy.resolve(null)).isEqualTo(60);
        assertThat(policy.resolve(0)).isEqualTo(60);
        assertThat(policy.resolve(366)).isEqualTo(60);
    }

    @Nested
    @DisplayName("Trial Registration Links capture the duration when issued")
    class Links {
        @Mock TrialRegistrationLinkRepository linkRepo;
        private TrialRegistrationLinkService links;

        @BeforeEach
        void setUp() {
            links = new TrialRegistrationLinkService(linkRepo);
            links.setTrialPolicy(policy);
            ReflectionTestUtils.setField(links, "baseUrl", "http://localhost:8080");
            when(linkRepo.save(any(TrialRegistrationLink.class))).thenAnswer(i -> i.getArgument(0));
            when(linkRepo.findByProspectEmailIgnoreCaseAndUsedAtIsNullAndRevokedFalse(anyString())).thenReturn(List.of());
        }

        @Test @DisplayName("no explicit length → the configured default is stored on the link")
        void defaultIsConfigured() {
            TrialRegistrationLink l = links.generate("G", "g@x.org", null, 7, null, "admin");
            assertThat(l.getTrialDays()).isEqualTo(60);
            assertThat(TrialRegistrationLinkService.trialDaysFor(l)).isEqualTo(60);
        }

        @Test @DisplayName("an admin can still override the length for one link")
        void overrideKept() {
            assertThat(links.generate("G", "g@x.org", null, 7, 90, "admin").getTrialDays()).isEqualTo(90);
        }

        @Test @DisplayName("changing the plan later does not change a link already issued")
        void existingLinkUnchanged() {
            TrialRegistrationLink l = links.generate("G", "g@x.org", null, 7, null, "admin");
            trial.setTrialDays(14);
            policy.refresh();
            assertThat(policy.trialDays()).isEqualTo(14);
            assertThat(TrialRegistrationLinkService.trialDaysFor(l)).as("the link keeps its own 60").isEqualTo(60);
            assertThat(links.generate("H", "h@x.org", null, 7, null, "admin").getTrialDays()).as("new links get 14").isEqualTo(14);
        }
    }
}
