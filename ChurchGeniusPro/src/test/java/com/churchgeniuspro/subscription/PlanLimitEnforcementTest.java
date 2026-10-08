package com.churchgeniuspro.subscription;

import com.churchgeniuspro.controller.SubscriptionPlanAdminController;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import com.churchgeniuspro.service.SmsService;
import com.churchgeniuspro.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * M6 + M7: plan limits are enforced where sends and plans actually happen.
 *
 * <p><b>M6.</b> The monthly SMS allowance was checked in {@code WhatsAppSenderService},
 * which only some senders route through. Pickup alerts, volunteer and kids-ministry
 * broadcasts, event RSVP confirmations and the opt-in reply all called
 * {@code SmsService.sendForClient} directly and were never counted — a plan with a
 * 50-message allowance could send any number of them.
 *
 * <p><b>M7.</b> Deactivating a plan does not restrict the clients on it: it removes
 * the row that says what they may do, and the service then fails open — every
 * feature enabled, every limit unlimited.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Plan limits (M6, M7)")
class PlanLimitEnforcementTest {

    /* ── M6: the monthly SMS allowance ──────────────────────────────────── */

    @Nested
    @DisplayName("the monthly SMS allowance")
    class SmsAllowance {

        private static final String TENANT = "CHR-church-01";

        @Mock SubscriptionService subs;

        private SmsService sms;

        @BeforeEach
        void setUp() {
            // No Twilio credentials: the send never leaves the process, which is what
            // these tests want — they are about which check runs and in what order.
            sms = new SmsService("", "", "");
            sms.setSubscriptionService(subs);
        }

        @Test
        @DisplayName("a send is refused once the allowance is spent, whichever method the caller used")
        void allowanceRefusesEveryOverload() {
            when(subs.canSendSms(TENANT)).thenReturn(false);

            // sendForClient is what the pickup alerts, volunteer and kids-ministry
            // broadcasts, RSVP confirmations and the opt-in reply all call.
            SmsService.SendOutcome direct = sms.sendForClient(TENANT, "+12175550100", "hi");
            assertThat(direct.sent()).isFalse();
            assertThat(direct.reason()).isEqualTo(SmsService.ALLOWANCE_SPENT_MSG);

            assertThat(sms.sendConfirmationRequest("+12175550100", "Grace Chapel", TENANT)).isFalse();
            verify(subs, never()).recordSmsSent(anyString());
        }

        @Test
        @DisplayName("the allowance is checked before the provider, so an unconfigured Twilio never hides it")
        void allowanceCheckedBeforeProvider() {
            when(subs.canSendSms(TENANT)).thenReturn(false);
            assertThat(sms.sendForClient(TENANT, "+12175550100", "hi").reason())
                    .isEqualTo(SmsService.ALLOWANCE_SPENT_MSG);

            when(subs.canSendSms(TENANT)).thenReturn(true);
            assertThat(sms.sendForClient(TENANT, "+12175550100", "hi").reason())
                    .isEqualTo("SMS provider not configured");
        }

        @Test
        @DisplayName("a tenant-less send — a Service Admin broadcast, a pre-login code — is not metered")
        void tenantlessSendIsNotMetered() {
            sms.sendWithOutcome("+12175550100", "hi");
            verify(subs, never()).canSendSms(anyString());
            verify(subs, never()).recordSmsSent(anyString());
        }

        @Test
        @DisplayName("without a plan service at all, sends behave exactly as before")
        void noPlanServiceIsUnmetered() {
            SmsService bare = new SmsService("", "", "");
            assertThat(bare.sendForClient(TENANT, "+12175550100", "hi").reason())
                    .isEqualTo("SMS provider not configured");
        }
    }

    /* ── M7: a plan clients are on cannot be switched off ───────────────── */

    @Nested
    @DisplayName("deactivating a plan")
    class Deactivation {

        @Mock SubscriptionPlanRepository planRepo;
        @Mock SubscriptionService        subs;
        @Mock ServiceClientRepository    clientRepo;

        private SubscriptionPlanAdminController controller;
        private SubscriptionPlan trial;

        @BeforeEach
        void setUp() {
            controller = new SubscriptionPlanAdminController(planRepo, subs);
            controller.setClientRepo(clientRepo);

            trial = new SubscriptionPlan();
            trial.setId(3L);
            trial.setPlanCode("TRIAL");
            trial.setPlanName("Trial Plan");
            trial.setActive(true);
            trial.setFeaturesJson("{}");
            when(planRepo.findById(3L)).thenReturn(Optional.of(trial));
        }

        private MockHttpServletRequest serviceAdmin() {
            MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/api/serviceadmin/subscription-plans/3");
            MockHttpSession s = new MockHttpSession();
            s.setAttribute("serviceAdminId", 1);
            req.setSession(s);
            return req;
        }

        private void clientsOnTrial(int n) {
            java.util.List<ServiceClient> all = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                ServiceClient sc = new ServiceClient();
                sc.setClientId("TRIAL-" + i);
                sc.setSubscriptionType("TRIAL");
                all.add(sc);
            }
            when(clientRepo.findAllByDeleteFlagFalseOrderByIdDesc()).thenReturn(all);
        }

        private static Map<String, Object> body(Object active) {
            Map<String, Object> b = new HashMap<>();
            b.put("active", active);
            return b;
        }

        @Test
        @DisplayName("a plan with clients on it is refused, and the count is named")
        void refusedWhileClientsAreOnIt() {
            clientsOnTrial(4);
            ResponseEntity<?> res = controller.update(3L, body(false), serviceAdmin());

            assertThat(res.getStatusCode().value()).isEqualTo(400);
            assertThat(String.valueOf(res.getBody())).contains("4 active clients");
            assertThat(trial.isActive()).as("nothing was changed").isTrue();
            verify(planRepo, never()).save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("legacy subscription values map to the same plan, so they count too")
        void legacyValuesCount() {
            ServiceClient legacy = new ServiceClient();
            legacy.setClientId("CHR-old");
            legacy.setSubscriptionType("FULL");            // maps to PRO
            when(clientRepo.findAllByDeleteFlagFalseOrderByIdDesc()).thenReturn(List.of(legacy));

            SubscriptionPlan pro = new SubscriptionPlan();
            pro.setId(4L); pro.setPlanCode("PRO"); pro.setPlanName("Pro Plan"); pro.setActive(true);
            when(planRepo.findById(4L)).thenReturn(Optional.of(pro));

            assertThat(controller.update(4L, body(false), serviceAdmin()).getStatusCode().value())
                    .isEqualTo(400);
        }

        @Test
        @DisplayName("a plan nobody is on can be deactivated")
        void unusedPlanCanBeDeactivated() {
            clientsOnTrial(0);
            assertThat(controller.update(3L, body(false), serviceAdmin()).getStatusCode().value())
                    .isEqualTo(200);
            assertThat(trial.isActive()).isFalse();
        }

        @Test
        @DisplayName("editing a plan's other fields is unaffected")
        void ordinaryEditStillWorks() {
            clientsOnTrial(4);
            Map<String, Object> rename = new HashMap<>();
            rename.put("planName", "Trial (30 days)");
            assertThat(controller.update(3L, rename, serviceAdmin()).getStatusCode().value()).isEqualTo(200);
            assertThat(trial.getPlanName()).isEqualTo("Trial (30 days)");
        }

        @Test
        @DisplayName("re-activating is never blocked")
        void reactivationAllowed() {
            trial.setActive(false);
            clientsOnTrial(4);
            assertThat(controller.update(3L, body(true), serviceAdmin()).getStatusCode().value()).isEqualTo(200);
            assertThat(trial.isActive()).isTrue();
        }
    }
}
