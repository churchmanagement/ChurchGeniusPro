package com.churchgeniuspro.trial;

import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.model.TrialRegistrationBO;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import com.churchgeniuspro.repository.SubscriptionUsageRepository;
import com.churchgeniuspro.service.AppUserService;
import com.churchgeniuspro.service.DemoAccessService;
import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.service.TestDataService;
import com.churchgeniuspro.service.TrialRegistrationService;
import com.churchgeniuspro.service.TrialTenantProvisioner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Self-service trial registration.
 *
 * <p>The provisioning itself is repository-heavy and covered end-to-end by the
 * integration suite; what is pinned here is the behaviour that is easy to break
 * by accident — the form's contract, the widening of the demo machinery to a
 * second client-id prefix, and the promise that changing plan never deletes data.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Trial registration")
class TrialRegistrationTest {

    /* ── the form's contract ────────────────────────────────────────────── */

    @Nested
    @DisplayName("form validation")
    class Validation {

        private TrialRegistrationBO valid() {
            TrialRegistrationBO bo = new TrialRegistrationBO();
            bo.setChurchName("Grace Chapel");
            bo.setFirstName("Ada");
            bo.setLastName("Okoye");
            bo.setEmail("ada@gracechapel.org");
            return bo;
        }

        @Test
        @DisplayName("the four required fields are enough — address and phone are optional")
        void minimalFormPasses() {
            assertThatCode(() -> valid().validate()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("each required field is named in its own message")
        void requiredFieldsAreNamed() {
            TrialRegistrationBO noChurch = valid(); noChurch.setChurchName("  ");
            assertThatThrownBy(noChurch::validate).hasMessageContaining("Church name");

            TrialRegistrationBO noFirst = valid(); noFirst.setFirstName(null);
            assertThatThrownBy(noFirst::validate).hasMessageContaining("First name");

            TrialRegistrationBO noLast = valid(); noLast.setLastName("");
            assertThatThrownBy(noLast::validate).hasMessageContaining("Last name");

            TrialRegistrationBO noEmail = valid(); noEmail.setEmail(null);
            assertThatThrownBy(noEmail::validate).hasMessageContaining("Email");
        }

        @Test
        @DisplayName("an obviously malformed email is rejected")
        void malformedEmailRejected() {
            for (String bad : List.of("ada", "ada@", "@example.org", "ada@example", "a b@x.org")) {
                TrialRegistrationBO bo = valid();
                bo.setEmail(bad);
                assertThatThrownBy(bo::validate)
                        .as("should reject '%s'", bad)
                        .hasMessageContaining("valid email");
            }
        }

        @Test
        @DisplayName("an address is never required — a lead is not lost over a blank field")
        void addressIsOptional() {
            TrialRegistrationBO bo = valid();
            bo.setAddressLine1(null); bo.setCity(null); bo.setState(null); bo.setPinCode(null);
            assertThatCode(bo::validate).doesNotThrowAnyException();
        }
    }

    /* ── the demo machinery, widened to a second prefix ─────────────────── */

    @Nested
    @DisplayName("tenant prefixes")
    class Prefixes {

        @Mock private com.churchgeniuspro.repository.DemoRoleAccessRepository accessRepo;
        @Mock private com.churchgeniuspro.repository.DemoClientSettingsRepository settingsRepo;
        @Mock private org.springframework.jdbc.core.JdbcTemplate jdbc;

        @Test
        @DisplayName("Demo Role Access governs trial tenants as well as demo ones")
        void demoAccessCoversTrialTenants() {
            DemoAccessService svc = new DemoAccessService(accessRepo, settingsRepo, jdbc);

            assertThat(svc.isDemoClient("DEMO-123")).isTrue();
            assertThat(svc.isDemoClient("TRIAL-123")).isTrue();
            // A paying tenant is untouched — this is what keeps their SMS and email
            // flowing while trial tenants start blocked.
            assertThat(svc.isDemoClient("CHR-abc")).isFalse();
            assertThat(svc.isDemoClient(null)).isFalse();
        }

        @Test
        @DisplayName("managed-tenant checks accept both prefixes and nothing else")
        void managedTenantChecks() {
            assertThat(TestDataService.isManagedTenant("DEMO-1")).isTrue();
            assertThat(TestDataService.isManagedTenant("TRIAL-1")).isTrue();
            assertThat(TestDataService.isManagedTenant("CHR-1")).isFalse();
            assertThat(TestDataService.isManagedTenant(null)).isFalse();

            assertThat(TestDataService.isTrialTenant("TRIAL-1")).isTrue();
            assertThat(TestDataService.isTrialTenant("DEMO-1")).isFalse();
        }

        @Test
        @DisplayName("usernames get the same short suffix whichever prefix the tenant has")
        void suffixIsPrefixAgnostic() {
            assertThat(TestDataService.prettySuffix("DEMO-1757300000123")).isEqualTo("000123");
            assertThat(TestDataService.prettySuffix("TRIAL-1757300000123")).isEqualTo("000123");
            assertThat(TestDataService.prettySuffix("TRIAL-12")).isEqualTo("12");
        }
    }

    /* ── which logins a trial tenant gets ───────────────────────────────── */

    @Test
    @DisplayName("a trial tenant asks for SuperAdmin plus one Member and one Kids portal")
    void trialSpecIsChurchAndSuperAdminOnly() {
        TestDataService.TenantSpec spec = new TestDataService.TenantSpec(
                "TRIAL-1", "Grace Chapel", List.of("SuperAdmin"), 1,
                TestDataService.TenantSpec.Contact.of("Ada", "Okoye", "ada@x.org", "555"));

        // Still only one staff login — a trial is not given the demo tenant's cast
        // of four staff characters.
        assertThat(spec.staffRoles()).containsExactly("SuperAdmin");
        assertThat(spec.staffRoles()).doesNotContain("Admin", "Accountant", "User");
        // ...but it does get the two portals, one of each, so the person evaluating
        // can see what a member and a child see.
        assertThat(spec.hasPortalLogins()).isTrue();
        assertThat(spec.portalLogins()).isEqualTo(1);
    }

    @Test
    @DisplayName("the demo tenant keeps every role and its portals, unchanged")
    void demoSpecIsUnchanged() {
        TestDataService.TenantSpec spec = TestDataService.TenantSpec.demo("DEMO-9");

        assertThat(spec.staffRoles()).containsExactly("SuperAdmin", "Admin", "Accountant", "User");
        assertThat(spec.hasPortalLogins()).isTrue();
        assertThat(spec.portalLogins()).as("the demo tenant keeps its pair").isEqualTo(2);
        assertThat(spec.churchName()).isEqualTo("Demo Church 9");
    }

    /* ── orchestration ──────────────────────────────────────────────────── */

    @Nested
    @DisplayName("registration flow")
    class Flow {

        @Mock private TrialTenantProvisioner provisioner;
        @Mock private AppUserService appUserService;

        private TrialRegistrationService service;
        private TrialRegistrationBO bo;

        @BeforeEach
        void setUp() {
            service = new TrialRegistrationService(provisioner, appUserService);
            bo = new TrialRegistrationBO();
            bo.setChurchName("Grace Chapel");
            bo.setFirstName("Ada");
            bo.setLastName("Okoye");
            bo.setEmail("Ada@GraceChapel.org");
            when(provisioner.provision(any(), anyString(), anyInt())).thenReturn(
                    new TrialTenantProvisioner.Provisioned(
                            "TRIAL-1", "Grace Chapel", 42, "ada.okoye_000001",
                            LocalDate.now().plusDays(30)));
        }

        @Test
        @DisplayName("the invite goes out through the Invite button's own method")
        void invitesViaExistingMechanism() throws Exception {
            Map<String, Object> out = service.register(bo);

            verify(appUserService).sendEmail(42);
            assertThat(out.get("invited")).isEqualTo(true);
            assertThat(out.get("clientId")).isEqualTo("TRIAL-1");
            assertThat(out.get("email")).isEqualTo("ada@gracechapel.org");   // normalised
        }

        @Test
        @DisplayName("a failed invite does not discard the provisioned tenant")
        void failedInviteKeepsTheTenant() throws Exception {
            doThrow(new RuntimeException("smtp down")).when(appUserService).sendEmail(42);

            Map<String, Object> out = service.register(bo);

            // The church exists and a Service Admin can press Invite on it. Losing
            // the whole signup because a mail server blipped would be worse.
            assertThat(out.get("clientId")).isEqualTo("TRIAL-1");
            assertThat(out.get("invited")).isEqualTo(false);
            assertThat(out.get("inviteError")).asString().contains("smtp down");
        }

        @Test
        @DisplayName("an invalid form never reaches provisioning")
        void invalidFormProvisionsNothing() {
            bo.setEmail("not-an-email");

            assertThatThrownBy(() -> service.register(bo))
                    .isInstanceOf(IllegalArgumentException.class);

            org.mockito.Mockito.verifyNoInteractions(provisioner);
        }

        @Test
        @DisplayName("a missing TRIAL plan is reported as unavailable, not as a plan-code error")
        void missingTrialPlanIsHandled() {
            when(provisioner.provision(any(), anyString(), anyInt()))
                    .thenThrow(new IllegalArgumentException(
                            "Unknown subscription plan 'TRIAL'. Available: FREE, STANDARD, PRO"));

            assertThatThrownBy(() -> service.register(bo))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not available");
        }
    }

    /* ── anti-bot layers on the public form ─────────────────────────────── */

    @Nested
    @DisplayName("public-form protection")
    class BotProtection {

        private final com.churchgeniuspro.util.PublicFormGuard guard =
                new com.churchgeniuspro.util.PublicFormGuard();
        /** The guard's clock — tokens are signed with the guard's own key (audit P10), so a
         *  test ages one by moving this rather than by minting a token of its own. */
        private final java.util.concurrent.atomic.AtomicLong clock =
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        { guard.setClock(clock::get); }

        @Test
        @DisplayName("the honeypot catches a filled hidden field")
        void honeypot() {
            assertThat(guard.isHoneypotTripped(Map.of("website", "http://spam.example"))).isTrue();
            assertThat(guard.isHoneypotTripped(Map.of("website", ""))).isFalse();
            assertThat(guard.isHoneypotTripped(Map.of("churchName", "Grace"))).isFalse();
        }

        @Test
        @DisplayName("an instant post is refused; a human-paced one is accepted")
        void timeTrap() throws Exception {
            String token = guard.issueToken(null);

            // Submitted the moment the page loaded — no human fills twelve fields
            // that fast.
            assertThat(guard.checkToken(token, null)).isNotNull();

            // The same token, aged past the minimum.
            clock.addAndGet(10_000L);
            assertThat(guard.checkToken(token, null)).isNull();
        }

        @Test
        @DisplayName("a missing, forged or stale token is refused")
        void tokenIntegrity() throws Exception {
            assertThat(guard.checkToken(null, null)).isNotNull();
            assertThat(guard.checkToken("", null)).isNotNull();
            assertThat(guard.checkToken("not-a-real-token", null)).isNotNull();

            String stale = guard.issueToken(null);
            clock.addAndGet(3L * 3600_000L);
            assertThat(guard.checkToken(stale, null)).contains("expired");

            // A token minted outside this process — e.g. AES under the legacy built-in
            // key, which is what every copy of the JAR used to accept — is refused.
            String minted = com.churchgeniuspro.util.EncryptionUtil.encrypt(
                    "PFT||" + (System.currentTimeMillis() - 10_000L));
            assertThat(guard.checkToken(minted, null)).isNotNull();
        }

        @Test
        @DisplayName("the per-IP window closes after the allowed burst")
        void rateLimit() {
            String ip = "203.0.113.9";
            for (int i = 0; i < com.churchgeniuspro.util.PublicFormGuard.MAX_PER_WINDOW; i++) {
                assertThat(guard.checkRate(ip, "trial-registration")).as("attempt %d", i + 1).isNull();
            }
            assertThat(guard.checkRate(ip, "trial-registration")).isNotNull();

            // Buckets are per form and per IP: neither another form nor another
            // visitor inherits this one's exhausted window.
            assertThat(guard.checkRate(ip, "connect")).isNull();
            assertThat(guard.checkRate("203.0.113.10", "trial-registration")).isNull();
        }

        @Test
        @DisplayName("with no secret configured the captcha is dormant and passes everything")
        void captchaDormantUntilConfigured() {
            // The default state: no key set, so the captcha never blocks a
            // legitimate signup. The other three layers still run.
            assertThat(guard.captchaEnabled()).isFalse();
            assertThat(guard.captchaSiteKey()).isNull();
            assertThat(guard.checkCaptcha(null, "203.0.113.9")).isNull();
        }

        @Test
        @DisplayName("once configured, a missing captcha response is refused")
        void captchaRequiredWhenConfigured() {
            org.springframework.test.util.ReflectionTestUtils.setField(
                    guard, "recaptchaSecret", "test-secret");
            org.springframework.test.util.ReflectionTestUtils.setField(
                    guard, "recaptchaSiteKey", "test-site-key");

            assertThat(guard.captchaEnabled()).isTrue();
            assertThat(guard.captchaSiteKey()).isEqualTo("test-site-key");
            // No network call: an absent response is refused before verification.
            assertThat(guard.checkCaptcha("", "203.0.113.9")).contains("CAPTCHA");
        }
    }

    /* ── requirement 10: changing plan never deletes data ───────────────── */

    @Nested
    @DisplayName("plan limits")
    class PlanLimits {

        @Mock private ServiceClientRepository     clientRepo;
        @Mock private SubscriptionPlanRepository  planRepo;
        @Mock private SubscriptionUsageRepository usageRepo;

        private SubscriptionService subs;

        @BeforeEach
        void setUp() {
            subs = new SubscriptionService(clientRepo, planRepo, usageRepo);
            ServiceClient sc = new ServiceClient();
            sc.setClientId("CHR-1");
            sc.setSubscriptionType("FREE");
            when(clientRepo.findByClientId("CHR-1")).thenReturn(Optional.of(sc));

            SubscriptionPlan free = new SubscriptionPlan();
            free.setPlanCode("FREE");
            free.setPlanName("Free Plan");
            free.setMaxPeople(50);
            free.setActive(true);
            when(planRepo.findByPlanCodeIgnoreCase("FREE")).thenReturn(Optional.of(free));
        }

        @Test
        @DisplayName("75 people on a 50-person plan are kept; only new additions are refused")
        void overLimitDataIsRetained() {
            // The scenario from the requirement: downgraded to Free while already
            // holding 75 people. Nothing deletes them — the check only ever answers
            // a question about ADDING, and it is asked before the add.
            String refusal = subs.checkPeopleLimit("CHR-1", 75, 1);

            assertThat(refusal).isNotNull();
            assertThat(refusal).contains("50").contains("75");
            // Nothing in SubscriptionService can remove a person: it returns a
            // message or null, and takes the current count as an argument.
        }

        @Test
        @DisplayName("adding resumes once the count falls back under the limit")
        void addingResumesBelowTheLimit() {
            assertThat(subs.checkPeopleLimit("CHR-1", 49, 1)).isNull();
            assertThat(subs.checkPeopleLimit("CHR-1", 50, 1)).isNotNull();
        }

        @Test
        @DisplayName("a plan with no people limit never refuses")
        void unlimitedPlanNeverRefuses() {
            SubscriptionPlan pro = new SubscriptionPlan();
            pro.setPlanCode("PRO");
            pro.setPlanName("Pro Plan");
            pro.setMaxPeople(null);           // null = unlimited
            pro.setActive(true);
            when(planRepo.findByPlanCodeIgnoreCase("PRO")).thenReturn(Optional.of(pro));
            ServiceClient sc = new ServiceClient();
            sc.setClientId("CHR-2");
            sc.setSubscriptionType("FULL");   // legacy value mapping to PRO
            when(clientRepo.findByClientId("CHR-2")).thenReturn(Optional.of(sc));

            assertThat(subs.checkPeopleLimit("CHR-2", 10_000, 1)).isNull();
        }
    }
}
