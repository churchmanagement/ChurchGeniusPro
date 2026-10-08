package com.churchgeniuspro.trial;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.model.TrialRegistrationBO;
import com.churchgeniuspro.plaid.entity.ChurchPlaidSetting;
import com.churchgeniuspro.plaid.repository.ChurchPlaidSettingRepository;
import com.churchgeniuspro.plaid.service.PlaidAuditService;
import com.churchgeniuspro.plaid.service.ServiceAdminPlaidService;
import com.churchgeniuspro.plaid.util.PlaidGuard;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.service.DemoAccessService;
import com.churchgeniuspro.service.TestDataService;
import com.churchgeniuspro.service.TrialTenantProvisioner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bank Sync must be switched on for a self-service trial tenant.
 *
 * <p>{@link PlaidGuard} reads {@code church_plaid_setting} and treats a MISSING
 * row as disabled, so a freshly provisioned tenant reaches Bank Sync only to be
 * told "Bank sync is not enabled for your organization" — the manual Service
 * Admin step that trial registration exists to remove.
 *
 * <p>Safe to enable unattended only because a trial tenant is on the TRIAL plan
 * and therefore sandbox-confined; the test below asserts the demo path is NOT
 * given the same treatment, since a demo tenant can be created on a plan that
 * routes to production Plaid.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Trial Bank Sync enablement")
class TrialBankSyncEnablementTest {

    private static final String TENANT = TestDataService.TRIAL_CLIENT_PREFIX + "1757300000123";

    @Mock private TestDataService          testData;
    @Mock private DemoAccessService        demoAccess;
    @Mock private AppUserRepository        appUserRepo;
    @Mock private LoginRepository          loginRepo;
    @Mock private ServiceAdminPlaidService plaidSettings;

    private TrialTenantProvisioner provisioner;

    @BeforeEach
    void setUp() {
        provisioner = new TrialTenantProvisioner(testData, demoAccess, appUserRepo, loginRepo, plaidSettings);

        when(testData.provisionTenant(any(), anyString(), any()))
                .thenReturn(Map.of("churchName", "Grace Chapel"));

        AppUser superAdmin = new AppUser();
        superAdmin.setId(7);
        superAdmin.setUserId("USR-abc");
        superAdmin.setClientId(TENANT);
        superAdmin.setRole("SuperAdmin");
        when(appUserRepo.findByClientIdAndDeleteFlagFalseOrderByLastNameAscFirstNameAsc(anyString()))
                .thenReturn(List.of(superAdmin));
        when(appUserRepo.save(any(AppUser.class))).thenAnswer(i -> i.getArgument(0));
        when(demoAccess.allDemoLogins()).thenReturn(List.of());
        when(loginRepo.findAllByClientId(anyString())).thenReturn(List.of());
    }

    private TrialRegistrationBO form() {
        TrialRegistrationBO bo = new TrialRegistrationBO();
        bo.setChurchName("Grace Chapel");
        bo.setFirstName("Ada");
        bo.setLastName("Okoye");
        bo.setEmail("ada@gracechapel.org");
        return bo;
    }

    @Test
    @DisplayName("provisioning turns Bank Sync on for the new tenant")
    void bankSyncIsEnabled() {
        provisioner.provision(form(), "TRIAL", 30);

        ArgumentCaptor<String> clientId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> plaid = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Boolean> sync  = ArgumentCaptor.forClass(Boolean.class);
        verify(plaidSettings).updateSettings(clientId.capture(), plaid.capture(),
                                             sync.capture(), anyString());

        assertThat(clientId.getValue()).startsWith(TestDataService.TRIAL_CLIENT_PREFIX);
        assertThat(plaid.getValue()).isTrue();
        assertThat(sync.getValue()).isTrue();
    }

    @Test
    @DisplayName("a Bank Sync failure does not cost the registration")
    void enablementFailureIsNonFatal() {
        when(plaidSettings.updateSettings(anyString(), any(), any(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        // The church, the SuperAdmin and the invite matter more than a toggle a
        // Service Admin can flip by hand.
        TrialTenantProvisioner.Provisioned p = provisioner.provision(form(), "TRIAL", 30);

        assertThat(p.clientId()).startsWith(TestDataService.TRIAL_CLIENT_PREFIX);
        assertThat(p.appUserId()).isEqualTo(7);
    }

    /* ── the guard's own default, which is what produced the message ────── */

    @Test
    @DisplayName("a missing settings row reads as disabled — the bug's actual mechanism")
    void missingRowMeansDisabled() {
        ChurchPlaidSettingRepository settingRepo =
                org.mockito.Mockito.mock(ChurchPlaidSettingRepository.class);
        PlaidGuard guard = new PlaidGuard(settingRepo,
                org.mockito.Mockito.mock(com.churchgeniuspro.plaid.service.BankSyncGateService.class));

        when(settingRepo.findByClientId(TENANT)).thenReturn(Optional.empty());
        assertThat(guard.isPlaidEnabled(TENANT)).isFalse();

        ChurchPlaidSetting on = new ChurchPlaidSetting();
        on.setClientId(TENANT);
        on.setPlaidEnabled(true);
        when(settingRepo.findByClientId(TENANT)).thenReturn(Optional.of(on));
        assertThat(guard.isPlaidEnabled(TENANT)).isTrue();
    }

    @Test
    @DisplayName("only the trial path enables Plaid — the demo seeding never touches it")
    void demoSeedingDoesNotEnablePlaid() {
        // The distinction that makes auto-enabling safe: a trial tenant is always
        // on the TRIAL plan and therefore sandbox-confined, while loadSmallDemo
        // accepts ANY plan, including ones that route to production Plaid. So the
        // toggle is flipped here, in the trial provisioner, and TestDataService --
        // which both paths share -- has no Plaid dependency at all to flip it with.
        assertThat(TrialTenantProvisioner.class.getDeclaredFields())
                .anyMatch(f -> f.getType().equals(ServiceAdminPlaidService.class));
        assertThat(TestDataService.class.getDeclaredFields())
                .noneMatch(f -> f.getType().equals(ServiceAdminPlaidService.class));
    }
}
