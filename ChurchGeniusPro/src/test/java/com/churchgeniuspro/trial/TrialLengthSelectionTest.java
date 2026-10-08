package com.churchgeniuspro.trial;

import com.churchgeniuspro.config.SubscriptionPlanSeeder;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.hibernate.TrialRegistrationLink;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import com.churchgeniuspro.repository.TrialRegistrationLinkRepository;
import com.churchgeniuspro.service.TrialRegistrationLinkService;
import com.churchgeniuspro.service.TrialRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.ApplicationRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Selectable trial length on registration links, and the Standard kids-portal limit. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Trial length selection + Standard plan kids-portal limit")
class TrialLengthSelectionTest {

    @Mock private TrialRegistrationLinkRepository repo;
    private TrialRegistrationLinkService service;

    @BeforeEach
    void setUp() {
        service = new TrialRegistrationLinkService(repo);
        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:8080");
        when(repo.save(any(TrialRegistrationLink.class))).thenAnswer(i -> i.getArgument(0));
        when(repo.findByProspectEmailIgnoreCaseAndUsedAtIsNullAndRevokedFalse(anyString())).thenReturn(List.of());
    }

    @Test
    @DisplayName("a link carries the trial length chosen for it")
    void chosenLength() {
        TrialRegistrationLink l = service.generate("G", "g@x.org", null, 7, 90, "admin");
        assertThat(l.getTrialDays()).isEqualTo(90);
        assertThat(TrialRegistrationLinkService.trialDaysFor(l)).isEqualTo(90);
        assertThat(service.toRow(l)).containsEntry("trialDays", 90);
    }

    @Test
    @DisplayName("no choice, or out of range, is the standard 30 days")
    void defaults() {
        assertThat(service.generate("G", "a@x.org", null, 7, "admin").getTrialDays())
                .isEqualTo(TrialRegistrationService.TRIAL_DAYS);
        assertThat(service.generate("G", "b@x.org", null, 7, 0, "admin").getTrialDays()).isEqualTo(30);
        assertThat(service.generate("G", "c@x.org", null, 7, 9999, "admin").getTrialDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("a link issued before the column existed (null) still grants 30 days")
    void legacyLink() {
        TrialRegistrationLink old = new TrialRegistrationLink();
        assertThat(TrialRegistrationLinkService.trialDaysFor(old)).isEqualTo(30);
        assertThat(TrialRegistrationLinkService.trialDaysFor(null)).isEqualTo(30);
    }

    @Test
    @DisplayName("the admin screen's options include 30, 60 and 90")
    void options() {
        assertThat(TrialRegistrationLinkService.TRIAL_DAY_OPTIONS).contains(30, 60, 90);
    }

    @Test
    @DisplayName("Standard plan is seeded with a Kids Portal limit of 3")
    void standardKidsPortals() throws Exception {
        SubscriptionPlanRepository plans = org.mockito.Mockito.mock(SubscriptionPlanRepository.class);
        List<SubscriptionPlan> saved = new ArrayList<>();
        when(plans.existsByPlanCodeIgnoreCase(anyString())).thenReturn(false);
        when(plans.save(any(SubscriptionPlan.class))).thenAnswer(i -> { saved.add(i.getArgument(0)); return i.getArgument(0); });

        SubscriptionPlanSeeder seeder = new SubscriptionPlanSeeder();
        ApplicationRunner runner = (ApplicationRunner) ReflectionTestUtils.invokeMethod(seeder, "seedSubscriptionPlans", plans);
        runner.run(null);

        SubscriptionPlan standard = saved.stream().filter(p -> p.getPlanCode().equals("STANDARD")).findFirst().orElseThrow();
        assertThat(standard.getMaxKidsPortals()).isEqualTo(3);
        SubscriptionPlan pro = saved.stream().filter(p -> p.getPlanCode().equals("PRO")).findFirst().orElseThrow();
        assertThat(pro.getMaxKidsPortals()).isNull();   // unchanged: unlimited
    }
}
