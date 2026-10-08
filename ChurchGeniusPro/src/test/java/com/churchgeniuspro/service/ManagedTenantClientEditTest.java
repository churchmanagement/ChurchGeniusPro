package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.model.ServiceClientBO;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Registered Clients → Edit on a TRIAL-/DEMO- tenant goes through the same
 * subscription update as Test/Demo → Edit/Extend, and the three defects of the
 * old path stay fixed:
 * <ol>
 *   <li>a day-based trial re-saved with a blank/unknown unit became N MONTHS;</li>
 *   <li>the login access windows were not extended (only the shared path does it);</li>
 *   <li>an unlisted plan code came back blank and was stored, i.e. "no plan".</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Registered Clients edit — trial/demo tenants use the shared subscription update")
class ManagedTenantClientEditTest {

    private static final String TRIAL_ID = "TRIAL-1759000000000";
    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate END   = START.plusDays(30);

    @Mock private ServiceClientRepository repo;
    @Mock private EmailService            email;
    @Mock private WhatsAppSenderService   whatsApp;
    @Mock private LoginRepository         logins;
    @Mock private TestDataService         testData;

    private ServiceClientService service;
    private ServiceClient row;

    @BeforeEach
    void setUp() {
        service = new ServiceClientService(repo, email, whatsApp, logins);
        service.setTestDataService(testData);

        row = new ServiceClient();
        row.setId(7);
        row.setClientId(TRIAL_ID);
        row.setChurchName("Grace Chapel");
        row.setSubscriptionType("TRIAL");
        row.setStartDate(START);
        row.setEndDate(END);
        row.setActivePeriod(30);
        row.setActivePeriodUnit("DAYS");
        row.setStatus("Active");

        when(repo.findById(7)).thenReturn(Optional.of(row));
        when(repo.findByClientId(TRIAL_ID)).thenReturn(Optional.of(row));
        when(repo.save(any(ServiceClient.class))).thenAnswer(i -> i.getArgument(0));
        // The shared path mutates the same row, as the real one does.
        doAnswer(i -> {
            String plan = i.getArgument(1);
            LocalDate exp = i.getArgument(2);
            if (plan != null) row.setSubscriptionType(plan);
            if (exp != null) {
                row.setEndDate(exp);
                if (exp.isAfter(LocalDate.now())) row.setStatus("Active");
                row.setActivePeriodUnit("DAYS");
            }
            return null;
        }).when(testData).updateDemoSubscription(anyString(), any(), any());
    }

    /** What the form posts back for this row when nothing is edited. */
    private ServiceClientBO untouched() {
        ServiceClientBO bo = new ServiceClientBO();
        bo.setName("Pat");
        bo.setChurchName("Grace Chapel");
        bo.setEmail("pat@example.org");
        bo.setActivePeriod(30);
        bo.setActivePeriodUnit("DAYS");
        bo.setStartDate(START.toString());
        bo.setSubscriptionType("TRIAL");
        bo.setStatus("Active");
        return bo;
    }

    @Nested
    @DisplayName("Problem 1 — day-based period")
    class DayPeriod {
        @Test
        @DisplayName("re-saving unchanged leaves the subscription alone")
        void untouchedIsNoChange() {
            service.update(7, untouched());
            verify(testData, never()).updateDemoSubscription(anyString(), any(), any());
            assertThat(row.getEndDate()).isEqualTo(END);
            assertThat(row.getActivePeriodUnit()).isEqualTo("DAYS");
        }

        @Test
        @DisplayName("a blank unit (older page) is the stored unit, never MONTHS — no 30-month trial")
        void blankUnitIsNotMonths() {
            ServiceClientBO bo = untouched();
            bo.setActivePeriodUnit("");
            service.update(7, bo);
            verify(testData, never()).updateDemoSubscription(anyString(), any(), any());
            assertThat(row.getEndDate()).isEqualTo(END);
        }

        @Test
        @DisplayName("choosing 60 days moves the end date through the shared path")
        void sixtyDays() {
            ServiceClientBO bo = untouched();
            bo.setActivePeriod(60);
            service.update(7, bo);
            verify(testData).updateDemoSubscription(eq(TRIAL_ID), isNull(), eq(START.plusDays(60)));
            assertThat(row.getEndDate()).isEqualTo(START.plusDays(60));
        }
    }

    @Nested
    @DisplayName("Problem 2 — every subscription change uses the shared path (which extends windows)")
    class SharedPath {
        // CHANGED 2026-10-05 (Phase 2, spec section 4): a sample-data trial (TRIAL- id)
        // may no longer be moved to a paid plan. These two cases used to expect the
        // upgrade to go through the shared path; they now expect it to be refused before
        // anything is written. The shared path itself is still covered for a DEMO- tenant
        // (internal demo accounts keep allowing any plan) by the two tests that follow.
        @Test
        @DisplayName("sample-data Trial → Pro (legacy FULL) is refused: it cannot be converted")
        void upgradeToPro() {
            ServiceClientBO bo = untouched();
            bo.setSubscriptionType("FULL");
            bo.setActivePeriod(12);
            bo.setActivePeriodUnit("MONTHS");
            assertThatThrownBy(() -> service.update(7, bo))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be converted");
            verify(testData, never()).updateDemoSubscription(anyString(), any(), any());
            verify(repo, never()).save(any(ServiceClient.class));
            assertThat(row.getSubscriptionType()).isEqualTo("TRIAL");
        }

        @Test
        @DisplayName("sample-data Trial → Standard (legacy LIMITED) is refused")
        void upgradeToStandard() {
            ServiceClientBO bo = untouched();
            bo.setSubscriptionType("LIMITED");
            assertThatThrownBy(() -> service.update(7, bo))
                    .hasMessageContaining("Register a new client");
            verify(testData, never()).updateDemoSubscription(anyString(), any(), any());
        }

        @Test
        @DisplayName("DEMO- tenant → Pro (legacy FULL) still uses the shared path with the canonical code")
        void demoUpgradeToPro() {
            row.setClientId("DEMO-1759000000000");
            when(repo.findByClientId("DEMO-1759000000000")).thenReturn(Optional.of(row));
            ServiceClientBO bo = untouched();
            bo.setSubscriptionType("FULL");
            bo.setActivePeriod(12);
            bo.setActivePeriodUnit("MONTHS");
            service.update(7, bo);
            verify(testData).updateDemoSubscription("DEMO-1759000000000", "PRO", START.plusMonths(12));
            assertThat(row.getSubscriptionType()).isEqualTo("PRO");
        }

        @Test
        @DisplayName("DEMO- tenant → Standard (legacy LIMITED)")
        void demoUpgradeToStandard() {
            row.setClientId("DEMO-1759000000000");
            when(repo.findByClientId("DEMO-1759000000000")).thenReturn(Optional.of(row));
            ServiceClientBO bo = untouched();
            bo.setSubscriptionType("LIMITED");
            service.update(7, bo);
            verify(testData).updateDemoSubscription("DEMO-1759000000000", "STANDARD", null);
        }

        @Test
        @DisplayName("contact fields save without touching the subscription")
        void contactOnly() {
            ServiceClientBO bo = untouched();
            bo.setChurchName("Grace Chapel West");
            service.update(7, bo);
            verify(testData, never()).updateDemoSubscription(anyString(), any(), any());
            assertThat(row.getChurchName()).isEqualTo("Grace Chapel West");
            assertThat(row.getSubscriptionType()).isEqualTo("TRIAL");
        }

        @Test
        @DisplayName("an explicit Hold in the same save survives the extension's reactivation")
        void holdIsKept() {
            ServiceClientBO bo = untouched();
            bo.setActivePeriod(365);
            bo.setStartDate(LocalDate.now().toString());
            // CHANGED 2026-10-05 (Phase 4 follow-up): this save changes the start date, which now
            // needs the admin's explicit confirmation — the form sends it after the warning.
            bo.setConfirmStartDateChange(true);
            bo.setStatus("Hold");
            service.update(7, bo);
            assertThat(row.getStatus()).isEqualTo("Hold");
        }
    }

    @Nested
    @DisplayName("Problem 3 — a blank plan is never stored")
    class BlankPlan {
        @Test
        @DisplayName("trial tenant: blank Subscription Type means unchanged")
        void managedBlank() {
            row.setSubscriptionType("STANDARD");
            ServiceClientBO bo = untouched();
            bo.setSubscriptionType("");
            service.update(7, bo);
            verify(testData, never()).updateDemoSubscription(anyString(), any(), any());
            assertThat(row.getSubscriptionType()).isEqualTo("STANDARD");
        }

        @Test
        @DisplayName("same plan written with its legacy alias is not a change")
        void aliasIsNotAChange() {
            row.setSubscriptionType("PRO");
            ServiceClientBO bo = untouched();
            bo.setSubscriptionType("FULL");
            service.update(7, bo);
            verify(testData, never()).updateDemoSubscription(anyString(), any(), any());
        }

        @Test
        @DisplayName("regular client: blank Subscription Type keeps the current one")
        void regularBlank() {
            row.setClientId("CHR-abc");
            row.setSubscriptionType("STANDARD");
            ServiceClientBO bo = untouched();
            bo.setSubscriptionType("");
            bo.setActivePeriod(12);
            bo.setActivePeriodUnit("MONTHS");
            service.update(7, bo);
            assertThat(row.getSubscriptionType()).isEqualTo("STANDARD");
            verify(testData, never()).updateDemoSubscription(anyString(), any(), any());
        }
    }

    @Test
    @DisplayName("regular clients keep their own path; DAYS is now honoured there too")
    void regularClientDays() {
        row.setClientId("CHR-abc");
        ServiceClientBO bo = untouched();
        bo.setActivePeriod(45);
        service.update(7, bo);
        verify(testData, never()).updateDemoSubscription(anyString(), any(), any());
        assertThat(row.getEndDate()).isEqualTo(START.plusDays(45));
    }

    @Test
    @DisplayName("end-date arithmetic: DAYS, MONTHS (default), YEARS")
    void endDateFor() {
        assertThat(ServiceClientService.endDateFor(START, 90, "DAYS")).isEqualTo(START.plusDays(90));
        assertThat(ServiceClientService.endDateFor(START, 3, null)).isEqualTo(START.plusMonths(3));
        assertThat(ServiceClientService.endDateFor(START, 3, "MONTHS")).isEqualTo(START.plusMonths(3));
        assertThat(ServiceClientService.endDateFor(START, 1, "YEARS")).isEqualTo(START.plusYears(1));
    }
}
