package com.churchgeniuspro.trial;

import com.churchgeniuspro.controller.FollowUpController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.model.ServiceClientBO;
import com.churchgeniuspro.model.TrialRegistrationBO;
import com.churchgeniuspro.plaid.service.ServiceAdminPlaidService;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import com.churchgeniuspro.util.AppClock;
import com.churchgeniuspro.util.PasswordUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 2 of the subscriptions plan (section 23): sample-data vs empty-account
 * trials, the no-conversion rule, trial extension, America/Chicago dates, the
 * trial notice facts, the expiry gaps for temporary-access / NTAG sign-in, and the
 * people limit on the Follow-Ups and Import add paths.
 */
@DisplayName("Phase 2 — trial rules")
class TrialPhase2RulesTest {

    // ── B. Sample data or empty account ─────────────────────────────────────

    @Nested @DisplayName("Registration choice")
    class Choice {
        TrialTenantProvisioner provisioner = mock(TrialTenantProvisioner.class);
        ServiceClientService clients = mock(ServiceClientService.class);
        ServiceAdminPlaidService plaid = mock(ServiceAdminPlaidService.class);
        TrialRegistrationService svc;

        Choice() {
            svc = new TrialRegistrationService(provisioner, mock(AppUserService.class));
            svc.setServiceClients(clients);
            svc.setPlaidSettings(plaid);
        }

        TrialRegistrationBO bo(String type) {
            TrialRegistrationBO b = new TrialRegistrationBO();
            b.setChurchName("Grace Chapel"); b.setFirstName("Mary"); b.setLastName("Lee");
            b.setEmail("Mary@Example.org"); b.setPhone("555"); b.setCity("Austin");
            b.setAccountType(type);
            b.setTrialDays(60);
            return b;
        }

        @Test @DisplayName("empty account = Register Client + Approve: CHR client on TRIAL, link's days, Chicago start, Bank Sync sandbox")
        void emptyAccount() throws Exception {
            ServiceClient saved = new ServiceClient();
            saved.setId(9); saved.setClientId("CHR-abc"); saved.setChurchName("Grace Chapel");
            saved.setEmail("mary@example.org"); saved.setEndDate(AppClock.today().plusDays(60));
            when(clients.save(any(ServiceClientBO.class), anyString())).thenReturn(saved);

            Map<String, Object> out = svc.register(bo("EMPTY"));

            ArgumentCaptor<ServiceClientBO> c = ArgumentCaptor.forClass(ServiceClientBO.class);
            verify(clients).save(c.capture(), eq("trial-registration"));
            ServiceClientBO sc = c.getValue();
            assertThat(sc.getSubscriptionType()).isEqualTo("TRIAL");
            assertThat(sc.getActivePeriod()).isEqualTo(60);
            assertThat(sc.getActivePeriodUnit()).isEqualTo("DAYS");
            assertThat(sc.getStartDate()).isEqualTo(AppClock.today().toString());
            assertThat(sc.getPaymentStatus()).isEqualTo("NOT_REQUIRED");
            assertThat(sc.getEmail()).isEqualTo("mary@example.org");
            assertThat(sc.getName()).isEqualTo("Mary Lee");
            verify(clients).approve(9);                          // the existing church-registration email
            verify(plaid).updateSettings("CHR-abc", true, true, "TRIAL_REGISTRATION");
            verifyNoInteractions(provisioner);                   // no sample data
            assertThat(out).containsEntry("accountType", "EMPTY").containsEntry("clientId", "CHR-abc");
        }

        @Test @DisplayName("sample data (or no choice from an older page) uses the existing TRIAL- provisioning, unchanged")
        void sampleData() {
            when(provisioner.provision(any(), anyString(), anyInt())).thenReturn(
                    new TrialTenantProvisioner.Provisioned("TRIAL-1", "Grace Chapel", 1, "u", LocalDate.now()));
            svc.register(bo("SAMPLE"));
            svc.register(bo(null));
            verify(provisioner, times(2)).provision(any(), eq("TRIAL"), eq(60));
            verifyNoInteractions(clients);
        }

        @Test @DisplayName("an unknown choice is refused")
        void unknownChoice() {
            assertThatThrownBy(() -> svc.register(bo("BOTH"))).isInstanceOf(IllegalArgumentException.class);
        }

        @Test @DisplayName("a failed approval removes the half-made client so the link can be reused")
        void approvalFailure() throws Exception {
            ServiceClient saved = new ServiceClient();
            saved.setId(9); saved.setClientId("CHR-abc");
            when(clients.save(any(ServiceClientBO.class), anyString())).thenReturn(saved);
            doThrow(new RuntimeException("db")).when(clients).approve(9);
            assertThatThrownBy(() -> svc.register(bo("EMPTY"))).isInstanceOf(IllegalStateException.class);
            verify(clients).softDelete(9);
        }

        @Test @DisplayName("no active TRIAL plan: the visitor gets 'not available', not a plan error")
        void noTrialPlan() {
            when(clients.save(any(ServiceClientBO.class), anyString()))
                    .thenThrow(new IllegalArgumentException("Subscription plan 'TRIAL' does not exist or is inactive."));
            assertThatThrownBy(() -> svc.register(bo("EMPTY")))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("not available");
        }
    }

    // ── C. Conversion rule ──────────────────────────────────────────────────

    @Nested @DisplayName("Conversion rule")
    class Conversion {
        @Test @DisplayName("TRIAL- (sample data) may not leave TRIAL; CHR trials and DEMO- accounts may")
        void rule() {
            assertThatThrownBy(() -> SubscriptionLifecycleService.checkConversionAllowed("TRIAL-1", "PRO"))
                    .hasMessage(SubscriptionLifecycleService.SAMPLE_TRIAL_NOT_CONVERTIBLE);
            assertThatThrownBy(() -> SubscriptionLifecycleService.checkConversionAllowed("TRIAL-1", "FREE"))
                    .isInstanceOf(IllegalArgumentException.class);
            SubscriptionLifecycleService.checkConversionAllowed("TRIAL-1", "TRIAL");      // extension: fine
            SubscriptionLifecycleService.checkConversionAllowed("CHR-1", "PRO");          // empty trial: fine
            SubscriptionLifecycleService.checkConversionAllowed("DEMO-1", "STANDARD");    // decision 3
        }

        // The shared demo/trial update and the Registered Clients edit both call this
        // rule; see DemoSubscriptionTest and ManagedTenantClientEditTest.
    }

    // ── D. Extension, dates, expiry gaps ────────────────────────────────────

    @Nested @DisplayName("Extend trial")
    class Extend {
        ServiceClientRepository repo = mock(ServiceClientRepository.class);
        ServiceClientService svc = new ServiceClientService(repo, mock(EmailService.class),
                mock(WhatsAppSenderService.class), mock(LoginRepository.class));
        TestDataService tds = mock(TestDataService.class);
        ServiceClient row = new ServiceClient();

        Extend() {
            org.springframework.test.util.ReflectionTestUtils.setField(svc, "testDataService", tds);
            row.setId(5); row.setClientId("CHR-1"); row.setSubscriptionType("TRIAL");
            row.setStartDate(AppClock.today().minusDays(70)); row.setEndDate(AppClock.today().minusDays(10));
            row.setStatus("Hold");                               // set on purpose by an admin
            when(repo.findById(5)).thenReturn(Optional.of(row));
            when(repo.save(any(ServiceClient.class))).thenAnswer(i -> i.getArgument(0));
        }

        @Test @DisplayName("an ended empty trial gets a new end date; plan and status are left alone")
        void extendsChrTrial() {
            LocalDate newEnd = AppClock.today().plusDays(20);
            ServiceClient s = svc.extendTrial(5, newEnd, "ops");
            assertThat(s.getEndDate()).isEqualTo(newEnd);
            assertThat(s.getSubscriptionType()).isEqualTo("TRIAL");
            assertThat(s.getStatus()).isEqualTo("Hold");
            assertThat(s.getActivePeriodUnit()).isEqualTo("DAYS");
            assertThat(s.getActivePeriod()).isEqualTo(90);
        }

        @Test @DisplayName("refused for a non-trial plan, a date not after today, or a date not after the current end")
        void refusals() {
            assertThatThrownBy(() -> svc.extendTrial(5, AppClock.today(), "ops")).hasMessageContaining("after today");
            row.setEndDate(AppClock.today().plusDays(30));
            assertThatThrownBy(() -> svc.extendTrial(5, AppClock.today().plusDays(5), "ops"))
                    .hasMessageContaining("later than the current end date");
            row.setSubscriptionType("PRO");
            assertThatThrownBy(() -> svc.extendTrial(5, AppClock.today().plusDays(60), "ops"))
                    .hasMessageContaining("only for clients on the Trial plan");
        }

        @Test @DisplayName("a sample-data trial is extended through the shared update (which moves every login window)")
        void extendsTrialTenant() {
            row.setClientId("TRIAL-1");
            when(repo.findByClientId("TRIAL-1")).thenReturn(Optional.of(row));
            LocalDate newEnd = AppClock.today().plusDays(15);
            svc.extendTrial(5, newEnd, "ops");
            verify(tds).updateDemoSubscription("TRIAL-1", null, newEnd);
        }
    }

    @Nested @DisplayName("Dates and the trial notice")
    class Dates {
        @Test @DisplayName("a 60-day trial starting Oct 5 ends Dec 4; Dec 3 is the last day; days counted from Chicago today")
        void trialInfo() {
            ServiceClientRepository repo = mock(ServiceClientRepository.class);
            ServiceClient sc = new ServiceClient();
            sc.setClientId("CHR-1"); sc.setSubscriptionType("TRIAL");
            sc.setStartDate(LocalDate.of(2026, 10, 5)); sc.setEndDate(LocalDate.of(2026, 12, 4));
            when(repo.findByClientId("CHR-1")).thenReturn(Optional.of(sc));
            SubscriptionService s = new SubscriptionService(repo, mock(SubscriptionPlanRepository.class),
                    mock(SubscriptionUsageRepository.class));
            Map<String, Object> t = s.trialInfo("CHR-1");
            assertThat(t).containsEntry("days", 60L).containsEntry("startDate", "2026-10-05")
                         .containsEntry("endDate", "2026-12-04").containsEntry("lastDay", "2026-12-03")
                         .containsEntry("managedTenant", false);
            long expected = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(AppClock.today(), sc.getEndDate()));
            assertThat(t.get("daysRemaining")).isEqualTo(expected);

            sc.setSubscriptionType("PRO");
            assertThat(s.trialInfo("CHR-1")).isNull();          // only Trial-plan accounts
        }

        @Test @DisplayName("sign-in checks pass today's America/Chicago date to the login queries")
        void loginUsesChicagoDate() {
            LoginRepository repo = mock(LoginRepository.class, Mockito.CALLS_REAL_METHODS);
            repo.countValidChurchLogin("pastor");
            repo.countValidNonChurchLogin("staff");
            verify(repo).countValidChurchLoginOn("pastor", AppClock.today());
            verify(repo).countValidNonChurchLoginOn("staff", AppClock.today());
            assertThat(AppClock.ZONE.getId()).isEqualTo("America/Chicago");
        }
    }

    @Nested @DisplayName("Expired church: temporary access and NTAG sign-in")
    class ExpiryGaps {
        @Test @DisplayName("a valid temporary pass of an ended church cannot sign in; of an active church it can")
        void temporaryAccess() {
            TemporaryAccessRepository accessRepo = mock(TemporaryAccessRepository.class);
            TemporaryAccess a = new TemporaryAccess();
            a.setClientId("CHR-1"); a.setStatus("ACTIVE");
            a.setStartDateTime(LocalDateTime.now().minusHours(1)); a.setEndDateTime(LocalDateTime.now().plusHours(1));
            a.setAccessCodeHash(PasswordUtil.encode("123456"));
            when(accessRepo.findByBarcodeValueAndDeleteFlagFalse("B1")).thenReturn(Optional.of(a));
            TemporaryAccessService svc = new TemporaryAccessService(accessRepo, mock(AccessAuditRepository.class),
                    mock(FamilyMemberRepository.class), mock(VolunteerProfileRepository.class));
            AccountStatusService status = mock(AccountStatusService.class);
            svc.setAccountStatus(status);

            assertThat(svc.validate("B1", "123456").result()).isEqualTo(TemporaryAccessService.CheckResult.OK);
            when(status.forTenant("CHR-1")).thenReturn(new AccountStatusService.Block("ended", "SUBSCRIPTION_EXPIRED"));
            assertThat(svc.validate("B1", "123456").result()).isEqualTo(TemporaryAccessService.CheckResult.SUBSCRIPTION_ENDED);
            // a wrong code still learns nothing about the church
            assertThat(svc.validate("B1", "000000").result()).isEqualTo(TemporaryAccessService.CheckResult.BAD_CODE);
        }

        @Test @DisplayName("an NTAG tag of an ended church cannot start a sign-in")
        void ntag() {
            NtagCredentialRepository creds = mock(NtagCredentialRepository.class);
            NtagCredential c = new NtagCredential();
            c.setClientId("CHR-1"); c.setNtagSerial("ABC123");
            when(creds.findByNtagSerialAndDeleteFlagFalse("ABC123")).thenReturn(Optional.of(c));
            NtagService svc = new NtagService(creds, mock(NtagLoginChallengeRepository.class),
                    mock(NtagLoginHistoryRepository.class), mock(ChurchRegistrationRepository.class),
                    mock(EmailService.class), mock(TemporaryAccessService.class));
            AccountStatusService status = mock(AccountStatusService.class);
            when(status.forTenant("CHR-1")).thenReturn(new AccountStatusService.Block("ended", "SUBSCRIPTION_EXPIRED"));
            svc.setAccountStatus(status);
            assertThatThrownBy(() -> svc.start("ABC123", "1.1.1.1", "dev"))
                    .hasMessageContaining("subscription has ended");
        }
    }

    // ── F. People limit on every add path ───────────────────────────────────

    @Nested @DisplayName("People limit on Follow-Ups and Import")
    class PeopleLimit {
        @Test @DisplayName("Follow-Ups 'Add New Volunteer' is refused at the limit and creates nothing")
        void followUps() {
            FamilyRepository families = mock(FamilyRepository.class);
            FamilyMemberRepository members = mock(FamilyMemberRepository.class);
            when(members.countActiveMembers("CHR-1")).thenReturn(50L);
            SubscriptionService subs = mock(SubscriptionService.class);
            when(subs.checkPeopleLimit("CHR-1", 50L, 1)).thenReturn("Your current subscription allows up to 50 members.");
            FollowUpController c = new FollowUpController(mock(FollowUpRepository.class), mock(FamilyService.class),
                    families, members, mock(VolunteerProfileRepository.class));
            c.setSubscriptionService(subs);
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.getSession(true).setAttribute("username", "staff");
            req.getSession().setAttribute("role", "Admin");
            req.getSession().setAttribute("appClientId", "CHR-1");

            ResponseEntity<?> r = c.addVolunteer(new HashMap<>(Map.of("firstName", "New", "lastName", "Person")), req);
            assertThat(r.getStatusCode().value()).isEqualTo(403);
            assertThat(String.valueOf(r.getBody())).contains("allows up to 50");
            verify(families, never()).save(any());
            verify(members, never()).save(any());
        }

        @Test @DisplayName("Import loads nothing when its new people would pass the limit; updates and skips do not count")
        void importBatch() throws Exception {
            FamilyMemberRepository members = mock(FamilyMemberRepository.class);
            when(members.countActiveMembers("CHR-1")).thenReturn(48L);
            SubscriptionService subs = mock(SubscriptionService.class);
            when(subs.checkPeopleLimit(eq("CHR-1"), eq(48L), anyInt())).thenAnswer(i ->
                    48 + (int) i.getArgument(2) > 50 ? "Your current subscription allows up to 50 members." : null);
            EtlLoadService etl = mock(EtlLoadService.class, Mockito.CALLS_REAL_METHODS);
            org.springframework.test.util.ReflectionTestUtils.setField(etl, "memberRepo", members);
            etl.setSubscriptionService(subs);
            Method m = EtlLoadService.class.getDeclaredMethod("requireRoomForNewPeople", List.class, String.class);
            m.setAccessible(true);

            List<com.churchgeniuspro.hibernate.StagingFamily> rows = new ArrayList<>();
            rows.add(row("INSERT", null)); rows.add(row("INSERT", null));
            rows.add(row("UPDATE", 7L));   rows.add(row(null, 8L));           // update + detected duplicate (skip)
            m.invoke(etl, rows, "CHR-1");                                     // 48 + 2 = 50: fits

            rows.add(row(null, null));                                        // a third new person
            assertThatThrownBy(() -> {
                try { m.invoke(etl, rows, "CHR-1"); }
                catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
            }).isInstanceOf(EtlLoadService.LoadException.class)
              .hasMessageContaining("would add 3 new people, so nothing was loaded");
        }

        private com.churchgeniuspro.hibernate.StagingFamily row(String action, Long match) {
            com.churchgeniuspro.hibernate.StagingFamily r = new com.churchgeniuspro.hibernate.StagingFamily();
            r.setDedupeAction(action);
            r.setDedupeMatchId(match);
            r.setRowStatus("APPROVED");
            return r;
        }
    }
}
