package com.churchgeniuspro.plaid;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.plaid.config.PlaidProperties;
import com.churchgeniuspro.plaid.entity.PlaidItem;
import com.churchgeniuspro.plaid.repository.BankSyncTrustedDeviceRepository;
import com.churchgeniuspro.plaid.repository.BankSyncVerificationRepository;
import com.churchgeniuspro.plaid.service.BankSyncGateService;
import com.churchgeniuspro.plaid.service.PlaidAuditService;
import com.churchgeniuspro.plaid.service.PlaidEnvironmentService;
import com.churchgeniuspro.plaid.service.PlaidTokenCipher;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.MessagingPolicy;
import com.churchgeniuspro.service.TestDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H5: demo tenants get exactly the Trial Plaid treatment — sandbox only.
 *
 * <p>The sandbox decision used to key on {@code subscription_type == TRIAL} alone.
 * A {@code DEMO-} tenant is created by {@code loadSmallDemo} on ANY plan, usually
 * Pro, so it resolved to this deployment's environment — production — and a demo
 * login, whose credentials the Service Admin console shows in plain text, could
 * link a real bank. The decision now recognises the demo/trial client-id prefix
 * first (no lookup needed) and falls back to the plan for {@code CHR-} clients.
 *
 * <p>Every Trial assertion in {@link PlaidTrialSandboxTest} and
 * {@link BankSyncTrialGateTest} still holds unchanged; these tests add the demo
 * half and pin that a paid church is untouched.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Plaid — Demo sandbox confinement (H5)")
class PlaidDemoSandboxTest {

    private static final String DEMO_ON_PRO    = TestDataService.DEMO_CLIENT_PREFIX  + "1757300000123";
    private static final String TRIAL_REGISTER = TestDataService.TRIAL_CLIENT_PREFIX + "1757300000456";
    private static final String CHR_ON_TRIAL   = "CHR-on-trial-plan";
    private static final String CHR_PAID       = "CHR-paid-church";

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

        // The demo tenant's PLAN says Pro — the plan alone would route it to production.
        when(messagingPolicy.trialState(DEMO_ON_PRO)).thenReturn(Boolean.FALSE);
        when(messagingPolicy.trialState(TRIAL_REGISTER)).thenReturn(Boolean.TRUE);
        when(messagingPolicy.trialState(CHR_ON_TRIAL)).thenReturn(Boolean.TRUE);
        when(messagingPolicy.trialState(CHR_PAID)).thenReturn(Boolean.FALSE);
    }

    /* ── new connections ────────────────────────────────────────────────── */

    @Nested
    @DisplayName("new connections")
    class NewConnections {

        @Test
        @DisplayName("a DEMO- tenant on the Pro plan is still confined to sandbox")
        void demoOnProIsSandbox() {
            assertThat(envService.envForClient(DEMO_ON_PRO)).isEqualTo(PlaidProperties.SANDBOX);
            assertThat(envService.isSandboxTenant(DEMO_ON_PRO)).isTrue();
        }

        @Test
        @DisplayName("a DEMO- tenant is classified without consulting the subscription at all")
        void demoNeedsNoLookup() {
            when(messagingPolicy.trialState(DEMO_ON_PRO)).thenThrow(new RuntimeException("db down"));
            assertThat(envService.envForClient(DEMO_ON_PRO)).isEqualTo(PlaidProperties.SANDBOX);
            assertThat(envService.isSandboxTenant(DEMO_ON_PRO)).isTrue();
        }

        @Test
        @DisplayName("a self-registered TRIAL- tenant resolves to sandbox (unchanged)")
        void trialRegisteredIsSandbox() {
            assertThat(envService.envForClient(TRIAL_REGISTER)).isEqualTo(PlaidProperties.SANDBOX);
        }

        @Test
        @DisplayName("a CHR- client on the Trial plan resolves to sandbox (unchanged)")
        void chrOnTrialPlanIsSandbox() {
            assertThat(envService.envForClient(CHR_ON_TRIAL)).isEqualTo(PlaidProperties.SANDBOX);
        }

        @Test
        @DisplayName("a paid church keeps the deployment's environment (unchanged)")
        void paidChurchIsProduction() {
            assertThat(envService.envForClient(CHR_PAID)).isEqualTo(PlaidProperties.PRODUCTION);
            assertThat(envService.isSandboxTenant(CHR_PAID)).isFalse();
        }

        @Test
        @DisplayName("an unreadable subscription still refuses for a CHR- client (unchanged)")
        void chrUnreadableStillRefuses() {
            when(messagingPolicy.trialState(CHR_PAID)).thenReturn(null);
            assertThatThrownBy(() -> envService.envForClient(CHR_PAID))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a missing sandbox secret refuses a demo tenant instead of promoting it to production")
        void missingSandboxSecretRefusesDemo() {
            props.setSandboxSecret("");
            assertThatThrownBy(() -> envService.envForClient(DEMO_ON_PRO))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("demo");
        }
    }

    /* ── existing items ─────────────────────────────────────────────────── */

    @Nested
    @DisplayName("existing items")
    class ExistingItems {

        @Test
        @DisplayName("a demo tenant's sandbox item is used on sandbox")
        void demoSandboxItemOk() {
            PlaidItem item = new PlaidItem();
            item.setClientId(DEMO_ON_PRO);
            item.setPlaidEnv("sandbox");
            assertThat(envService.envForItem(item)).isEqualTo(PlaidProperties.SANDBOX);
        }

        @Test
        @DisplayName("a demo tenant's production-stamped item is refused, never used")
        void demoProductionItemRefused() {
            PlaidItem item = new PlaidItem();
            item.setId(42);
            item.setClientId(DEMO_ON_PRO);
            item.setPlaidEnv("production");
            assertThatThrownBy(() -> envService.envForItem(item))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Demo accounts");
        }

        @Test
        @DisplayName("a demo tenant's pre-migration (unstamped) item on a production deployment is refused too")
        void demoUnstampedItemRefused() {
            PlaidItem legacy = new PlaidItem();
            legacy.setClientId(DEMO_ON_PRO);
            assertThatThrownBy(() -> envService.envForItem(legacy))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a TRIAL- tenant that upgraded may hold production items (unchanged)")
        void upgradedTrialProductionItemAllowed() {
            PlaidItem item = new PlaidItem();
            item.setClientId(TRIAL_REGISTER);
            item.setPlaidEnv("production");
            assertThat(envService.envForItem(item)).isEqualTo(PlaidProperties.PRODUCTION);
        }

        @Test
        @DisplayName("a paid church's items are untouched")
        void paidItemsUntouched() {
            PlaidItem prod = new PlaidItem();
            prod.setClientId(CHR_PAID);
            prod.setPlaidEnv("production");
            assertThat(envService.envForItem(prod)).isEqualTo(PlaidProperties.PRODUCTION);
        }
    }

    /* ── the verification gate ──────────────────────────────────────────── */

    @Nested
    @DisplayName("Bank Sync gate")
    class Gate {

        private static final Integer USER_ID = 7;

        @Mock private BankSyncVerificationRepository  verificationRepo;
        @Mock private BankSyncTrustedDeviceRepository deviceRepo;
        @Mock private PlaidTokenCipher                cipher;
        @Mock private EmailService                    emailService;
        @Mock private AppUserRepository               appUserRepository;
        @Mock private PlaidAuditService               audit;

        private BankSyncGateService gate;

        @BeforeEach
        void setUp() {
            gate = new BankSyncGateService(verificationRepo, deviceRepo, cipher, emailService,
                                           appUserRepository, audit, messagingPolicy);
            AppUser u = new AppUser();
            u.setId(USER_ID);
            u.setClientId(DEMO_ON_PRO);
            u.setEmail("demo.treasurer@example.invalid");
            when(appUserRepository.findById(USER_ID)).thenReturn(Optional.of(u));
            when(verificationRepo.findByAppUserIdAndUsed(USER_ID, false)).thenReturn(List.of());
            when(cipher.encrypt(anyString())).thenReturn("enc");
            when(messagingPolicy.isTrial(DEMO_ON_PRO)).thenReturn(false);   // plan says Pro
            when(messagingPolicy.isTrial(CHR_PAID)).thenReturn(false);
            when(messagingPolicy.isTrial(CHR_ON_TRIAL)).thenReturn(true);
        }

        private MockHttpServletRequest staffRequest(String tenant) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/bankSync");
            MockHttpSession session = new MockHttpSession();
            session.setAttribute("appUserId",   USER_ID);
            session.setAttribute("appClientId", tenant);
            session.setAttribute("clientId",    tenant);
            session.setAttribute("username",    "treasurer");
            req.setSession(session);
            return req;
        }

        @Test
        @DisplayName("a demo tenant on the Pro plan gets the Trial exemption — no code is required or sent")
        void demoSkipsVerification() {
            assertThat(gate.verificationRequired(staffRequest(DEMO_ON_PRO))).isFalse();
            verify(emailService, never()).sendAccountEmail(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("a paid church is still gated (unchanged)")
        void paidStillGated() {
            assertThat(gate.verificationRequired(staffRequest(CHR_PAID))).isTrue();
        }

        /* ── M8: the exemption never reaches a live bank ─────────────────── */

        @Test
        @DisplayName("a tenant holding a PRODUCTION connection keeps the step-up, whatever its plan says")
        void productionConnectionKeepsVerification() {
            // A paying church moved onto the Trial plan — a lapsed renewal, an
            // extended evaluation — still has live bank connections. The exemption is
            // safe only because evaluation tenants are sandbox-only; switching a
            // security step-up off underneath real bank data is not part of that.
            com.churchgeniuspro.plaid.repository.PlaidItemRepository itemRepo =
                    org.mockito.Mockito.mock(com.churchgeniuspro.plaid.repository.PlaidItemRepository.class);
            gate.setConnectionLookup(itemRepo, envService);

            PlaidItem live = new PlaidItem();
            live.setClientId(CHR_ON_TRIAL);
            live.setPlaidEnv("production");
            when(itemRepo.findByClientIdAndDeleteFlagFalse(CHR_ON_TRIAL)).thenReturn(java.util.List.of(live));
            when(messagingPolicy.isTrial(CHR_ON_TRIAL)).thenReturn(true);

            assertThat(gate.verificationRequired(staffRequest(CHR_ON_TRIAL))).isTrue();
        }

        @Test
        @DisplayName("a sandbox-only evaluation tenant is still exempt")
        void sandboxOnlyTenantStillExempt() {
            com.churchgeniuspro.plaid.repository.PlaidItemRepository itemRepo =
                    org.mockito.Mockito.mock(com.churchgeniuspro.plaid.repository.PlaidItemRepository.class);
            gate.setConnectionLookup(itemRepo, envService);

            PlaidItem sandbox = new PlaidItem();
            sandbox.setClientId(DEMO_ON_PRO);
            sandbox.setPlaidEnv("sandbox");
            when(itemRepo.findByClientIdAndDeleteFlagFalse(DEMO_ON_PRO)).thenReturn(java.util.List.of(sandbox));

            assertThat(gate.verificationRequired(staffRequest(DEMO_ON_PRO))).isFalse();
        }

        @Test
        @DisplayName("a connection lookup that fails keeps the step-up rather than dropping it")
        void lookupFailureKeepsVerification() {
            com.churchgeniuspro.plaid.repository.PlaidItemRepository itemRepo =
                    org.mockito.Mockito.mock(com.churchgeniuspro.plaid.repository.PlaidItemRepository.class);
            gate.setConnectionLookup(itemRepo, envService);
            org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                    .when(itemRepo).findByClientIdAndDeleteFlagFalse(DEMO_ON_PRO);

            assertThat(gate.verificationRequired(staffRequest(DEMO_ON_PRO))).isTrue();
        }
    }
}
