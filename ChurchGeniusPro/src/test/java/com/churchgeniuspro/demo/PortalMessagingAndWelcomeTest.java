package com.churchgeniuspro.demo;

import com.churchgeniuspro.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Messaging on a trial account, and the one email that must still get through.
 *
 * <p>The Member and Kids portals are logins INSIDE the trial tenant, so they send
 * as that tenant and {@code MessagingPolicy} already refuses them — there is no
 * second rule to write, and these tests exist to make sure nobody adds one that
 * disagrees. What must NOT be refused is the mail that makes the account usable:
 * the invitation link and the message listing the three portal sign-ins.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Trial portals — messaging and the welcome email")
class PortalMessagingAndWelcomeTest {

    private static final String TRIAL = TestDataService.TRIAL_CLIENT_PREFIX + "1757300000456";

    /* ── requirement: portals cannot email or text the congregation ─────── */

    @Nested
    @DisplayName("email and SMS from a portal")
    class Blocked {

        @Mock com.churchgeniuspro.repository.ServiceClientRepository clientRepo;
        @Mock DemoAccessService demoAccess;

        private MessagingPolicy policy;

        @BeforeEach
        void setUp() {
            policy = new MessagingPolicy(clientRepo);
            policy.setDemoAccess(demoAccess);
            com.churchgeniuspro.hibernate.ServiceClient sc = new com.churchgeniuspro.hibernate.ServiceClient();
            sc.setClientId(TRIAL);
            sc.setSubscriptionType("TRIAL");
            when(clientRepo.findByClientId(TRIAL)).thenReturn(java.util.Optional.of(sc));
            when(demoAccess.sendingAllowed(anyString(), anyBoolean())).thenReturn(false);
        }

        @Test
        @DisplayName("the tenant is refused both, so every login inside it is")
        void tenantWideBlock() {
            // There is no per-login messaging rule and deliberately so: a portal
            // login has no tenant of its own to be judged by.
            assertThat(policy.emailAllowed(TRIAL)).isFalse();
            assertThat(policy.smsAllowed(TRIAL)).isFalse();
            assertThat(policy.emailBlockReason(TRIAL)).isEqualTo(MessagingPolicy.TRIAL_EMAIL_MSG);
            assertThat(policy.smsBlockReason(TRIAL)).isEqualTo(MessagingPolicy.TRIAL_SMS_MSG);
        }

        @Test
        @DisplayName("an SMS sent on the tenant's behalf never reaches the provider")
        void smsRefusedAtTheChokePoint() {
            SmsService sms = new SmsService("", "", "");
            sms.setMessagingPolicy(policy);

            SmsService.SendOutcome out = sms.sendForClient(TRIAL, "+12175550100", "hi");

            assertThat(out.sent()).isFalse();
            assertThat(out.reason()).isEqualTo(MessagingPolicy.TRIAL_SMS_MSG);
        }
    }

    /* ── requirement: account mail must still be delivered ──────────────── */

    @Nested
    @DisplayName("the portal credentials email")
    class Welcome {

        @Mock EmailService emailService;
        @Mock DemoAccessService demoAccess;

        private PortalWelcomeEmail welcome;

        @BeforeEach
        void setUp() {
            PortalCredentialService portals = new PortalCredentialService(demoAccess);
            welcome = new PortalWelcomeEmail(emailService, portals);
            welcome.setBaseUrl("https://churchgeniuspro.net");

            List<Map<String, Object>> rows = new ArrayList<>();
            rows.add(login("SuperAdmin", "ada.okoye_1757", "StaffPass1", "Ada Okoye"));
            rows.add(login(TestDataService.ROLE_MEMBER_PORTAL, "member_grace_7", "MemberPass1", "Grace Hall"));
            rows.add(login(TestDataService.ROLE_CHILD_PORTAL, "child_lily_9", "KidPass1", "Lily Hall"));
            when(demoAccess.allDemoLogins()).thenReturn(rows);
        }

        private static Map<String, Object> login(String role, String user, String pwd, String name) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tenant", TRIAL);
            m.put("role_label", role);
            m.put("username", user);
            m.put("demo_password", pwd);
            m.put("member_name", name);
            return m;
        }

        @Test
        @DisplayName("goes out as ACCOUNT mail, which the trial block deliberately exempts")
        void sentAsAccountMail() throws Exception {
            assertThat(welcome.send(TRIAL, "Grace Chapel", "ada@x.org", "Ada", "2026-10-11")).isTrue();

            // sendAccountEmail is the exemption the invitation link already uses;
            // sendOrgEmail would be silently dropped by MessagingPolicy.
            verify(emailService).sendAccountEmail(eq("ada@x.org"), anyString(), anyString(), eq(TRIAL));
            verify(emailService, never()).sendOrgEmail(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("names all three portals and carries each one's sign-in")
        void listsEveryPortal() throws Exception {
            welcome.send(TRIAL, "Grace Chapel", "ada@x.org", "Ada", "2026-10-11");

            ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
            verify(emailService).sendAccountEmail(anyString(), anyString(), html.capture(), anyString());
            String body = html.getValue();

            assertThat(body).contains("Staff Portal", "Member Portal", "Kids Portal");
            assertThat(body).contains("ada.okoye_1757", "StaffPass1");
            assertThat(body).contains("member_grace_7", "MemberPass1");
            assertThat(body).contains("child_lily_9", "KidPass1");
            // ...and says what each one is for, so the labels are not bare.
            assertThat(body).contains("church staff", "church member", "child sees");
        }

        @Test
        @DisplayName("tells them when the trial ends and where to sign in")
        void carriesTheEssentials() throws Exception {
            welcome.send(TRIAL, "Grace Chapel", "ada@x.org", "Ada", "2026-10-11");

            ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
            verify(emailService).sendAccountEmail(anyString(), anyString(), html.capture(), anyString());
            assertThat(html.getValue()).contains("2026-10-11", "churchgeniuspro.net");
        }

        @Test
        @DisplayName("a church name with an ampersand cannot rewrite the message")
        void contentIsEscaped() throws Exception {
            welcome.send(TRIAL, "Faith & <b>Hope</b>", "ada@x.org", "Ada", "2026-10-11");

            ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
            verify(emailService).sendAccountEmail(anyString(), anyString(), html.capture(), anyString());
            assertThat(html.getValue()).contains("Faith &amp; &lt;b&gt;Hope&lt;/b&gt;");
        }

        @Test
        @DisplayName("a real church is never sent one")
        void notSentForARealChurch() {
            assertThat(welcome.send("CHR-real", "Real Church", "a@x.org", "A", "2026-10-11")).isFalse();
            verifyNoInteractions(emailService);
        }

        @Test
        @DisplayName("the staff card warns that an invitation email may already have arrived")
        void staffCardCarriesTheInvitationNote() throws Exception {
            welcome.send(TRIAL, "Grace Chapel", "ada@x.org", "Ada", "2026-10-11");

            ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
            verify(emailService).sendAccountEmail(anyString(), anyString(), html.capture(), anyString());
            String body = html.getValue();

            assertThat(body).contains(PortalWelcomeEmail.STAFF_NOTE);
            // Once only, and on the staff card: it sits between that card's heading
            // and its credentials, before the Member Portal card begins.
            assertThat(body.indexOf(PortalWelcomeEmail.STAFF_NOTE))
                    .isGreaterThan(body.indexOf("Staff Portal"))
                    .isLessThan(body.indexOf("ada.okoye_1757"));
            assertThat(body.indexOf(PortalWelcomeEmail.STAFF_NOTE))
                    .isEqualTo(body.lastIndexOf(PortalWelcomeEmail.STAFF_NOTE));
            assertThat(body.lastIndexOf(PortalWelcomeEmail.STAFF_NOTE))
                    .isLessThan(body.indexOf("Member Portal"));
            // The default staff credentials still follow it.
            assertThat(body).contains("ada.okoye_1757", "StaffPass1");
        }

        @Test
        @DisplayName("a tenant with no recoverable staff password is not promised one")
        void noteDropsTheFallbackSentenceWithoutAPassword() throws Exception {
            List<Map<String, Object>> rows = new ArrayList<>();
            Map<String, Object> staff = login("SuperAdmin", "ada.okoye_1757", "", "Ada Okoye");
            rows.add(staff);
            rows.add(login(TestDataService.ROLE_MEMBER_PORTAL, "member_grace_7", "MemberPass1", "Grace Hall"));
            when(demoAccess.allDemoLogins()).thenReturn(rows);

            welcome.send(TRIAL, "Grace Chapel", "ada@x.org", "Ada", "2026-10-11");

            ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
            verify(emailService).sendAccountEmail(anyString(), anyString(), html.capture(), anyString());
            String body = html.getValue();

            assertThat(body).contains(PortalWelcomeEmail.STAFF_NOTE);
            assertThat(body).doesNotContain("The default sign-in below works as well");
            assertThat(body).contains("(set during sign-up)");
        }

        @Test
        @DisplayName("a failed send is reported, not thrown — the tenant exists either way")
        void failureIsNotFatal() throws Exception {
            doThrow(new RuntimeException("smtp down"))
                    .when(emailService).sendAccountEmail(anyString(), anyString(), anyString(), anyString());

            assertThat(welcome.send(TRIAL, "Grace Chapel", "ada@x.org", "Ada", "2026-10-11")).isFalse();
        }
    }
}
