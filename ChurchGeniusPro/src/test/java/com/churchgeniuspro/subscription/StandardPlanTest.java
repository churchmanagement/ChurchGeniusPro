package com.churchgeniuspro.subscription;

import com.churchgeniuspro.config.SubscriptionPlanSeeder;
import com.churchgeniuspro.controller.AppUserController;
import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.model.AppUserBO;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.AppUserService;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.PasswordResetService;
import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.util.SubscriptionFeatureCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Standard plan: features withheld, limits, and the 10-user limit on /viewusers. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Standard plan")
class StandardPlanTest {

    static List<SubscriptionPlan> seeded() throws Exception {
        SubscriptionPlanRepository plans = mock(SubscriptionPlanRepository.class);
        List<SubscriptionPlan> saved = new ArrayList<>();
        when(plans.existsByPlanCodeIgnoreCase(anyString())).thenReturn(false);
        when(plans.save(any(SubscriptionPlan.class))).thenAnswer(i -> {
            SubscriptionPlan p = i.getArgument(0);
            if (saved.stream().noneMatch(x -> x == p)) saved.add(p);   // commercial() re-saves the same row
            return p;
        });
        // The seeder sets price / Bank Sync limit / trial length on the row it just created.
        when(plans.findByPlanCodeIgnoreCase(anyString())).thenAnswer(i -> saved.stream()
                .filter(x -> x.getPlanCode().equalsIgnoreCase(i.getArgument(0))).findFirst());
        ApplicationRunner runner = (ApplicationRunner) ReflectionTestUtils.invokeMethod(
                new SubscriptionPlanSeeder(), "seedSubscriptionPlans", plans);
        runner.run(null);
        return saved;
    }
    static SubscriptionPlan plan(String code) throws Exception {
        return seeded().stream().filter(p -> p.getPlanCode().equals(code)).findFirst().orElseThrow();
    }

    /** A SubscriptionService whose one client is on the given seeded plan. */
    static SubscriptionService serviceOn(SubscriptionPlan p) {
        ServiceClientRepository clients = mock(ServiceClientRepository.class);
        SubscriptionPlanRepository plans = mock(SubscriptionPlanRepository.class);
        ServiceClient sc = new ServiceClient();
        sc.setClientId("CHR-x");
        sc.setSubscriptionType(p.getPlanCode());
        when(clients.findByClientId("CHR-x")).thenReturn(Optional.of(sc));
        when(plans.findByPlanCodeIgnoreCase(p.getPlanCode())).thenReturn(Optional.of(p));
        return new SubscriptionService(clients, plans, mock(SubscriptionUsageRepository.class));
    }

    @Nested
    @DisplayName("features")
    class Features {
        @ParameterizedTest(name = "{0} is unavailable on Standard (and {1} is gated by it)")
        @CsvSource({
            "payroll,    /api/payroll/runs",
            "aiVoice,    /api/voice/command",
            "aiConverse, /api/ai-assist",
            "scanCheck,  /api/expense/check-scan",
        })
        void unavailable(String feature, String path) throws Exception {
            SubscriptionService s = serviceOn(plan("STANDARD"));
            assertThat(s.isFeatureEnabled("CHR-x", feature)).isFalse();
            assertThat(SubscriptionFeatureCatalog.keyForPath(path)).isEqualTo(feature);
        }

        @Test
        @DisplayName("Bank Sync is included on Standard, capped at 3 connected accounts")
        void bankSyncCappedAtThree() throws Exception {
            SubscriptionPlan standard = plan("STANDARD");
            SubscriptionService s = serviceOn(standard);
            assertThat(s.isFeatureEnabled("CHR-x", "bankSync")).isTrue();
            assertThat(SubscriptionFeatureCatalog.keyForPath("/api/plaid/link-token")).isEqualTo("bankSync");
            assertThat(standard.getMaxBankAccounts()).isEqualTo(3);
            assertThat(s.checkBankAccountLimit("CHR-x", 3, 1)).contains("up to 3");
        }

        @Test
        @DisplayName("Free has no Bank Sync; Pro and Trial are unlimited")
        void bankSyncOtherPlans() throws Exception {
            SubscriptionPlan free = plan("FREE");
            assertThat(serviceOn(free).isFeatureEnabled("CHR-x", "bankSync")).isFalse();
            assertThat(free.getMaxBankAccounts()).isEqualTo(0);
            assertThat(plan("PRO").getMaxBankAccounts()).isNull();
            assertThat(plan("TRIAL").getMaxBankAccounts()).isNull();
        }

        @Test
        @DisplayName("Pro and Trial keep them")
        void proAndTrialKeepThem() throws Exception {
            for (String code : List.of("PRO", "TRIAL")) {
                SubscriptionService s = serviceOn(plan(code));
                for (String f : List.of("bankSync", "payroll", "aiVoice", "aiConverse", "scanCheck")) {
                    assertThat(s.isFeatureEnabled("CHR-x", f)).as(code + " " + f).isTrue();
                }
            }
        }

        @Test
        @DisplayName("Midwest Region Meet is no longer a plan feature for any plan")
        void noMidRegMeetCheckbox() throws Exception {
            assertThat(SubscriptionFeatureCatalog.FEATURES).noneMatch(f -> f.key().equals("midRegMeet")
                    || f.label().contains("Midwest"));
            for (SubscriptionPlan p : seeded()) {
                assertThat(p.getFeaturesJson()).doesNotContain("midRegMeet");
            }
        }
    }

    @Test
    @DisplayName("limits: 100 people, 50 emails, 50 SMS, 3 member portals, 3 kids portals, 10 users")
    void limits() throws Exception {
        SubscriptionPlan p = plan("STANDARD");
        assertThat(p.getMaxPeople()).isEqualTo(100);
        assertThat(p.getMaxEmailsPerMonth()).isEqualTo(50);
        assertThat(p.getMaxSmsPerMonth()).isEqualTo(50);
        assertThat(p.getMaxMemberPortals()).isEqualTo(3);
        assertThat(p.getMaxKidsPortals()).isEqualTo(3);
        assertThat(p.getMaxStaffUsers()).isEqualTo(10);
        // Other plans: no user limit introduced.
        assertThat(plan("FREE").getMaxStaffUsers()).isNull();
        assertThat(plan("PRO").getMaxStaffUsers()).isNull();
        assertThat(plan("TRIAL").getMaxStaffUsers()).isNull();
    }

    @Test
    // CHANGED 2026-10-05 (Phase 4, spec section 11): allowances are counted per 30-day period
    // from the subscription start date, not per calendar month. The client now carries a start
    // date and the stubbed row is looked up by its period start.
    @DisplayName("email and SMS allowances are counted per 30-day usage period")
    void monthlyUsageKey() throws Exception {
        SubscriptionPlan p = plan("STANDARD");
        ServiceClientRepository clients = mock(ServiceClientRepository.class);
        SubscriptionPlanRepository plans = mock(SubscriptionPlanRepository.class);
        SubscriptionUsageRepository usage = mock(SubscriptionUsageRepository.class);
        ServiceClient sc = new ServiceClient(); sc.setClientId("CHR-x"); sc.setSubscriptionType("STANDARD");
        java.time.LocalDate start = com.churchgeniuspro.util.AppClock.today().minusDays(45);
        sc.setStartDate(start);
        when(clients.findByClientId("CHR-x")).thenReturn(Optional.of(sc));
        when(plans.findByPlanCodeIgnoreCase("STANDARD")).thenReturn(Optional.of(p));
        com.churchgeniuspro.hibernate.SubscriptionUsage row = new com.churchgeniuspro.hibernate.SubscriptionUsage();
        row.setEmailsSent(50); row.setSmsSent(49);
        when(usage.findByClientIdAndPeriodStart("CHR-x", start.plusDays(30))).thenReturn(Optional.of(row));   // day 45 = period 2
        SubscriptionService s = new SubscriptionService(clients, plans, usage);
        assertThat(s.canSendEmail("CHR-x")).isFalse();   // 50 of 50 used this period
        assertThat(s.canSendSms("CHR-x")).isTrue();      // 49 of 50
    }

    /* ── 10-user limit on /viewusers ───────────────────────────────────── */

    @Nested
    @DisplayName("church-added users")
    class Users {
        @Mock AppUserService userService;
        @Mock UserPermissionsRepository permsRepo;
        @Mock FamilyMemberRepository familyRepo;
        @Mock LoginRepository loginRepo;
        @Mock EmailService email;
        @Mock PasswordResetService reset;
        @Mock AppUserRepository users;
        AppUserController controller;

        @BeforeEach
        void setUp() throws Exception {
            controller = new AppUserController(userService, permsRepo, familyRepo, loginRepo, email, reset);
            controller.setPlanLimitDeps(serviceOn(plan("STANDARD")), users);
            AppUser created = new AppUser(); created.setId(99);
            when(userService.create(any(AppUserBO.class))).thenReturn(created);
        }

        MockHttpServletRequest church() {
            MockHttpSession s = new MockHttpSession();
            s.setAttribute("church", true); s.setAttribute("clientId", "CHR-x"); s.setAttribute("username", "c");
            MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
        }
        AppUserBO bo() {
            AppUserBO b = new AppUserBO();
            b.setFirstName("A"); b.setLastName("B"); b.setEmail("a@b.org"); b.setRole("User");
            return b;
        }

        @Test
        @DisplayName("the 10th user can be added")
        void tenthAllowed() throws Exception {
            when(users.countByClientIdAndDeleteFlagFalse("CHR-x")).thenReturn(9L);
            assertThat(controller.create(bo(), church()).getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("the 11th user is refused on the server, and nothing is created")
        void eleventhRefused() throws Exception {
            when(users.countByClientIdAndDeleteFlagFalse("CHR-x")).thenReturn(10L);
            ResponseEntity<Map<String, Object>> res = controller.create(bo(), church());
            assertThat(res.getStatusCode().value()).isEqualTo(403);
            assertThat(String.valueOf(res.getBody().get("error"))).contains("10 users");
            verify(userService, never()).create(any());
        }

        @Test
        @DisplayName("deleted users do not count — the count query excludes them")
        void deletedExcluded() throws Exception {
            // countByClientIdAndDeleteFlagFalse is the only count used; a church with 10 rows,
            // 3 of them deleted, reports 7 and may add more.
            when(users.countByClientIdAndDeleteFlagFalse("CHR-x")).thenReturn(7L);
            assertThat(controller.create(bo(), church()).getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("Pro has no user limit")
        void proUnlimited() throws Exception {
            controller.setPlanLimitDeps(serviceOn(plan("PRO")), users);
            when(users.countByClientIdAndDeleteFlagFalse("CHR-x")).thenReturn(500L);
            assertThat(controller.create(bo(), church()).getStatusCode().value()).isEqualTo(200);
        }
    }
}
