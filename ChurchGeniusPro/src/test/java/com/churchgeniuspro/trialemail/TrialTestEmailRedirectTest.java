package com.churchgeniuspro.trialemail;

import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import com.churchgeniuspro.util.EmailActionScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Phase B at the central gate: a Trial/Demo tenant's congregation mail goes to its
 * verified test address — once per action — or nowhere; a paying church is untouched.
 */
@DisplayName("Trial/Demo test email — the central redirect")
class TrialTestEmailRedirectTest {

    static final String TRIAL = "CHR-TRIAL", PAID = "CHR-PAID", TEST = "tester@trial.test";

    MailCapture mail;
    SubscriptionService subs;
    TrialTestEmailService testEmails;
    EmailService email;

    @BeforeEach
    void setUp() {
        ServiceClientRepository clients = mock(ServiceClientRepository.class);
        ServiceClient trial = new ServiceClient(); trial.setClientId(TRIAL); trial.setSubscriptionType("TRIAL");
        ServiceClient paid  = new ServiceClient(); paid.setClientId(PAID);  paid.setSubscriptionType("FULL");
        when(clients.findByClientId(TRIAL)).thenReturn(Optional.of(trial));
        when(clients.findByClientId(PAID)).thenReturn(Optional.of(paid));

        mail = new MailCapture();
        UnsubscribeService unsub = mock(UnsubscribeService.class);
        subs = mock(SubscriptionService.class);
        when(subs.canSendEmail(anyString())).thenReturn(true);
        email = new EmailService(mail.sender, mock(EmailSettingsRepository.class), mock(ChurchLogoRepository.class),
                mock(PromiseVerseRepository.class), unsub, mock(ChurchRegistrationRepository.class), subs);
        email.setMessagingPolicy(new MessagingPolicy(clients));
        testEmails = mock(TrialTestEmailService.class);      // the verified-address lookup only
        email.setTrialTestEmails(testEmails);
    }

    void verified(String address) { when(testEmails.verifiedAddress(TRIAL)).thenReturn(address); }

    void sendTo(int n) {
        for (int i = 1; i <= n; i++) email.sendOrgEmail("member" + i + "@real.org", "Subject", "<p>Hello member " + i + "</p>", TRIAL);
    }

    @Nested @DisplayName("no verified test address")
    class NoAddress {
        @Test @DisplayName("1. blocked: nothing sent, zero usage, reported as BLOCKED")
        void blocked() {
            sendTo(3);
            assertThat(mail.sent).isEmpty();
            verify(subs, never()).recordEmailSent(anyString());
            assertThat(email.delivery(TRIAL).mode()).isEqualTo(EmailService.DeliveryMode.BLOCKED);
        }

        @Test @DisplayName("2. an unverified (pending) address is not used: the lookup returns only verified ones")
        void pendingNotUsed() {
            // The service returns null while an address is pending — pinned in TrialTestEmailServiceTest; here: null → blocked.
            verified(null);
            sendTo(2);
            assertThat(mail.sent).isEmpty();
            verify(subs, never()).recordEmailSent(anyString());
        }
    }

    @Nested @DisplayName("verified test address")
    class WithAddress {
        @BeforeEach void v() { verified(TEST); }

        @Test @DisplayName("9. real recipients never receive it; the test address does, with the notice and the original content")
        void redirected() throws Exception {
            try (EmailActionScope s = EmailActionScope.begin("a")) { sendTo(1); }
            assertThat(mail.sent).hasSize(1);
            assertThat(mail.allRecipients()).containsExactly(TEST);
            String body = MailCapture.text(mail.sent.get(0));
            assertThat(body).contains(TrialTestEmailService.NOTICE, "<p>Hello member 1</p>", "me***@rea***.org");
            assertThat(body).doesNotContain("member1@real.org");
            assertThat(mail.sent.get(0).getSubject()).isEqualTo("Subject");
            assertThat(email.delivery(TRIAL).mode()).isEqualTo(EmailService.DeliveryMode.TEST);
            assertThat(email.delivery(TRIAL).testEmail()).isEqualTo("te***@tri***.test");
        }

        @ParameterizedTest(name = "{0} recipients → one test email, one usage count")
        @ValueSource(ints = {1, 5, 50})
        @DisplayName("10–12, 15. one action → exactly one test email and one usage count")
        void onePerAction(int n) {
            try (EmailActionScope s = EmailActionScope.begin("meeting-notify:7")) {
                sendTo(n);
                assertThat(s.testEmailsSent()).isEqualTo(1);
                assertThat(s.simulated()).isEqualTo(n - 1);
            }
            assertThat(mail.sent).hasSize(1);
            verify(subs, times(1)).recordEmailSent(TRIAL);
        }

        @Test @DisplayName("13–14. two actions → two test emails to the same address")
        void twoActions() throws Exception {
            try (EmailActionScope s = EmailActionScope.begin("meeting-notify:7")) { sendTo(10); }
            try (EmailActionScope s = EmailActionScope.begin("event-reminder:3")) { sendTo(10); }
            assertThat(mail.sent).hasSize(2);
            assertThat(mail.allRecipients()).containsExactly(TEST, TEST);
            verify(subs, times(2)).recordEmailSent(TRIAL);
        }

        @Test @DisplayName("a sub-key inside one scope separates actions (two events in one scheduler job)")
        void subKeys() {
            try (EmailActionScope s = EmailActionScope.begin("scheduler:event reminders")) {
                s.sub("event:1"); sendTo(5);
                s.sub("event:2"); sendTo(5);
                assertThat(s.testEmailsSent()).isEqualTo(2);
                assertThat(s.simulated()).isEqualTo(8);
            }
            assertThat(mail.sent).hasSize(2);
        }

        @Test @DisplayName("a send with no scope open is an action of its own")
        void noScope() {
            sendTo(2);
            assertThat(mail.sent).hasSize(2);
            verify(subs, times(2)).recordEmailSent(TRIAL);
        }

        @Test @DisplayName("8. after the address is replaced, the old one no longer receives anything")
        void replaced() throws Exception {
            try (EmailActionScope s = EmailActionScope.begin("a")) { sendTo(1); }
            verified("new@trial.test");
            try (EmailActionScope s = EmailActionScope.begin("b")) { sendTo(1); }
            assertThat(mail.allRecipients()).containsExactly(TEST, "new@trial.test");
        }

        @Test @DisplayName("sendOrgEmailOrThrow redirects instead of refusing, once per action")
        void orThrowRedirects() throws Exception {
            try (EmailActionScope s = EmailActionScope.begin("attendance")) {
                email.sendOrgEmailOrThrow("a@real.org", "S", "<p>b</p>", TRIAL);
                email.sendOrgEmailOrThrow("b@real.org", "S", "<p>b</p>", TRIAL);
                assertThat(s.testEmailsSent()).isEqualTo(1);
            }
            assertThat(mail.allRecipients()).containsExactly(TEST);
            verify(subs, times(1)).recordEmailSent(TRIAL);
        }

        @Test @DisplayName("sendComposed (group / notify mail) goes once to the test address, BCC list dropped")
        void composedRedirects() throws Exception {
            email.sendComposed(List.of("x@real.org"), List.of("y@real.org", "z@real.org"), "S", "<p>b</p>", null, null, TRIAL);
            assertThat(mail.sent).hasSize(1);
            assertThat(mail.allRecipients()).containsExactly(TEST);
            assertThat(MailCapture.text(mail.sent.get(0))).contains(TrialTestEmailService.NOTICE, "3 recipients");
        }

        @Test @DisplayName("account mail (codes, resets) is untouched: it still goes to the real address")
        void accountMailUntouched() throws Exception {
            email.sendAccountEmailOrThrow("owner@real.org", "Code", "<p>123456</p>", TRIAL);
            assertThat(mail.allRecipients()).containsExactly("owner@real.org");
            assertThat(MailCapture.text(mail.sent.get(0))).doesNotContain(TrialTestEmailService.NOTICE);
        }
    }

    @Nested @DisplayName("a paying church")
    class Paid {
        @Test @DisplayName("17. is never redirected, whatever the test-address service says")
        void unchanged() throws Exception {
            when(testEmails.verifiedAddress(anyString())).thenReturn(TEST);
            try (EmailActionScope s = EmailActionScope.begin("meeting-notify:1")) {
                for (int i = 1; i <= 3; i++) email.sendOrgEmail("m" + i + "@real.org", "S", "<p>b</p>", PAID);
                assertThat(s.testEmailsSent()).isZero();
                assertThat(s.simulated()).isZero();
            }
            assertThat(mail.allRecipients()).containsExactly("m1@real.org", "m2@real.org", "m3@real.org");
            for (jakarta.mail.internet.MimeMessage m : mail.sent) assertThat(MailCapture.text(m)).doesNotContain(TrialTestEmailService.NOTICE);
            verify(subs, times(3)).recordEmailSent(PAID);
            assertThat(email.delivery(PAID).mode()).isEqualTo(EmailService.DeliveryMode.NORMAL);
            verify(testEmails, never()).verifiedAddress(PAID);
        }

        @Test @DisplayName("sendComposed and sendOrgEmailOrThrow are unchanged for a paying church")
        void composedUnchanged() throws Exception {
            email.sendComposed(List.of("x@real.org"), List.of("y@real.org"), "S", "<p>b</p>", null, null, PAID);
            email.sendOrgEmailOrThrow("a@real.org", "S", "<p>b</p>", PAID);
            assertThat(mail.allRecipients()).containsExactly("x@real.org", "y@real.org", "a@real.org");
        }
    }

    @Test @DisplayName("tenant-less platform mail is unaffected")
    void tenantless() throws Exception {
        when(testEmails.verifiedAddress(anyString())).thenReturn(TEST);
        email.sendGenericEmail("admin@real.org", "S", "<p>b</p>");
        assertThat(mail.allRecipients()).containsExactly("admin@real.org");
    }
}
