package com.churchgeniuspro.messaging;

import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The Trial block has to hold at the point of dispatch, not in the page that
 * usually calls it — otherwise a Trial client can reach real phones and inboxes
 * with a direct API request, which is exactly what was asked to be prevented.
 *
 * <p>So these tests do not go through any controller. They call <b>every public
 * send method</b> on the two services and assert that the mail sender is never
 * touched and that Twilio is never reached. A new overload that forgets the
 * guard fails here.
 */
class TrialSendBlockedTest {

    private static final String TRIAL_CLIENT = "CHR-TRIAL";
    private static final String PAID_CLIENT  = "CHR-PAID";
    private static final String TO_EMAIL     = "member@example.org";
    private static final String TO_PHONE     = "+15555550123";

    private JavaMailSender mailSender;
    private UnsubscribeService unsub;
    private SubscriptionService subs;
    private EmailService   emailService;
    private SmsService     smsService;
    private MessagingPolicy policy;

    @BeforeEach
    void setUp() {
        ServiceClientRepository clients = mock(ServiceClientRepository.class);
        ServiceClient trial = new ServiceClient();
        trial.setClientId(TRIAL_CLIENT);
        trial.setSubscriptionType("TRIAL");
        ServiceClient paid = new ServiceClient();
        paid.setClientId(PAID_CLIENT);
        paid.setSubscriptionType("FULL");
        when(clients.findByClientId(TRIAL_CLIENT)).thenReturn(Optional.of(trial));
        when(clients.findByClientId(PAID_CLIENT)).thenReturn(Optional.of(paid));
        policy = new MessagingPolicy(clients);

        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(i -> mock(MimeMessage.class));

        unsub = mock(UnsubscribeService.class);
        when(unsub.isUnsubscribed(anyString(), anyString())).thenReturn(false);
        subs = mock(SubscriptionService.class);
        when(subs.canSendEmail(anyString())).thenReturn(true);

        emailService = new EmailService(mailSender,
                mock(EmailSettingsRepository.class),
                mock(ChurchLogoRepository.class),
                mock(PromiseVerseRepository.class),
                unsub,
                mock(ChurchRegistrationRepository.class),
                subs);
        emailService.setMessagingPolicy(policy);

        // No Twilio credentials → not "configured"; the block must fire BEFORE
        // that check, which is what makes the returned reason meaningful.
        smsService = new SmsService("", "", "");
        smsService.setMessagingPolicy(policy);
    }

    // ── Email: no public method may reach the mail sender ───────────────────

    @Test
    @DisplayName("No ordinary EmailService path sends for a Trial client")
    void everyEmailPathIsBlockedForTrial() throws Exception {
        // Everything a church would use to reach its congregation. Registration
        // mail is deliberately exempt and is covered by registrationMailIsExempt().
        emailService.sendGenericEmail(TO_EMAIL, "s", "<p>b</p>", TRIAL_CLIENT);
        emailService.sendOrgEmail(TO_EMAIL, "s", "<p>b</p>", TRIAL_CLIENT);
        emailService.sendOrgEmail(TO_EMAIL, "s", "<p>b</p>", TRIAL_CLIENT, new byte[]{1});

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("sendComposed refuses a Trial client rather than silently dropping the message")
    void composedIsBlockedForTrial() {
        assertThatThrownBy(() -> emailService.sendComposed(
                List.of(TO_EMAIL), List.of(), "s", "<p>b</p>", null, null, TRIAL_CLIENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(MessagingPolicy.TRIAL_EMAIL_MSG);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("A paying client still sends through every email path")
    void paidClientStillSends() throws Exception {
        emailService.sendSignupInvitation(TO_EMAIL, "Ada", PAID_CLIENT);
        emailService.sendGenericEmail(TO_EMAIL, "s", "<p>b</p>", PAID_CLIENT);
        emailService.sendOrgEmail(TO_EMAIL, "s", "<p>b</p>", PAID_CLIENT);
        emailService.sendComposed(List.of(TO_EMAIL), List.of(), "s", "<p>b</p>", null, null, PAID_CLIENT);

        verify(mailSender, times(4)).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("Tenant-less system mail (OTP, password reset) still goes out")
    void tenantlessMailStillSends() {
        emailService.sendGenericEmail(TO_EMAIL, "Your code", "<p>123456</p>");
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    // ── Registration mail is exempt, congregation mail is not ───────────────

    @Test
    @DisplayName("Registration mail still reaches a Trial client's signups")
    void registrationMailIsExempt() {
        // Client registration invitation
        emailService.sendSignupInvitation(TO_EMAIL, "Ada", TRIAL_CLIENT);
        // Member registration: sign-up verification code, then account-created welcome
        emailService.sendAccountEmail(TO_EMAIL, "Your Verification Code", "<p>123456</p>", TRIAL_CLIENT);
        emailService.sendAccountEmail(TO_EMAIL, "Account Created", "<p>welcome</p>", TRIAL_CLIENT);

        verify(mailSender, times(3)).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("The exemption does not leak: ordinary mail for the same client is still blocked")
    void exemptionDoesNotLeak() {
        emailService.sendAccountEmail(TO_EMAIL, "Your Verification Code", "<p>1</p>", TRIAL_CLIENT);
        verify(mailSender, times(1)).send(any(MimeMessage.class));

        // Same client, same service, ordinary congregation-facing paths — still refused.
        emailService.sendOrgEmail(TO_EMAIL, "Sunday bulletin", "<p>b</p>", TRIAL_CLIENT);
        emailService.sendGenericEmail(TO_EMAIL, "Sunday bulletin", "<p>b</p>", TRIAL_CLIENT);
        verifyNoMoreInteractions(ignoreStubs(mailSender));
    }

    @Test
    @DisplayName("Registration mail for a paying client behaves exactly as before")
    void registrationMailUnchangedForPaidClient() {
        emailService.sendAccountEmail(TO_EMAIL, "Your Verification Code", "<p>1</p>", PAID_CLIENT);
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("Password reset and username recovery are delivered for a Trial client")
    void accountRecoveryMailIsExempt() {
        // Self-service reset, admin-initiated reset, and username recovery all go
        // through the tenant-less account path.
        emailService.sendAccountEmail(TO_EMAIL, "Password Reset — St Anne's", "<p>link</p>");
        emailService.sendAccountEmail(TO_EMAIL, "Your Church Genius Pro Username(s)", "<p>names</p>");
        // A branded recovery message for a known tenant is exempt too.
        emailService.sendAccountEmail(TO_EMAIL, "Password Reset", "<p>link</p>", TRIAL_CLIENT);

        verify(mailSender, times(3)).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("Recovery mail is exempt on purpose, not by accident of overload choice")
    void recoveryExemptionIsDeliberate() {
        // sendGenericEmail with a tenant is ordinary mail and stays blocked; the
        // account path with the same tenant is delivered. If someone ever routes a
        // reset through the ordinary path, this is the test that notices.
        emailService.sendGenericEmail(TO_EMAIL, "Password Reset", "<p>link</p>", TRIAL_CLIENT);
        verify(mailSender, never()).send(any(MimeMessage.class));

        emailService.sendAccountEmail(TO_EMAIL, "Password Reset", "<p>link</p>", TRIAL_CLIENT);
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    // ── Account mail bypasses unsubscribe and the monthly allowance ─────────

    @Test
    @DisplayName("An unsubscribed recipient still receives account mail")
    void unsubscribedRecipientStillGetsAccountMail() {
        when(unsub.isUnsubscribed(anyString(), anyString())).thenReturn(true);

        // Ordinary church mail respects the opt-out …
        emailService.sendOrgEmail(TO_EMAIL, "Sunday bulletin", "<p>b</p>", PAID_CLIENT);
        verify(mailSender, never()).send(any(MimeMessage.class));

        // … a verification code or reset link is not something you can opt out of.
        emailService.sendAccountEmail(TO_EMAIL, "Your Verification Code", "<p>1</p>", PAID_CLIENT);
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("Account mail still goes out after the monthly allowance is spent")
    void exhaustedAllowanceStillSendsAccountMail() {
        when(subs.canSendEmail(anyString())).thenReturn(false);

        emailService.sendOrgEmail(TO_EMAIL, "Sunday bulletin", "<p>b</p>", PAID_CLIENT);
        verify(mailSender, never()).send(any(MimeMessage.class));

        emailService.sendAccountEmail(TO_EMAIL, "Password Reset", "<p>link</p>", PAID_CLIENT);
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("Account mail is not metered — resets must not eat the congregation allowance")
    void accountMailIsNotCountedAgainstTheAllowance() {
        emailService.sendAccountEmail(TO_EMAIL, "Password Reset", "<p>link</p>", PAID_CLIENT);
        verify(subs, never()).recordEmailSent(anyString());

        // Ordinary mail is still counted, so the meter keeps working.
        emailService.sendOrgEmail(TO_EMAIL, "Sunday bulletin", "<p>b</p>", PAID_CLIENT);
        verify(subs, times(1)).recordEmailSent(PAID_CLIENT);
    }

    @Test
    @DisplayName("Account mail carries no unsubscribe link, ordinary mail does")
    void accountMailHasNoUnsubscribeLink() throws Exception {
        // Real MimeMessages here, not mocks, so the assertion is about the bytes that
        // would actually be sent rather than about which helper methods were called.
        jakarta.mail.Session session = jakarta.mail.Session.getInstance(new java.util.Properties());
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage(session));
        java.util.List<String> wire = new java.util.ArrayList<>();
        doAnswer(i -> {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            ((MimeMessage) i.getArgument(0)).writeTo(out);
            wire.add(out.toString(java.nio.charset.StandardCharsets.UTF_8));
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        emailService.sendAccountEmail(TO_EMAIL, "Password Reset", "<p>link</p>", PAID_CLIENT);
        emailService.sendOrgEmail(TO_EMAIL, "Sunday bulletin", "<p>b</p>", PAID_CLIENT);

        assertThat(wire).hasSize(2);
        assertThat(unfold(wire.get(0)))
                .as("a password reset must not invite the reader to unsubscribe")
                .doesNotContain("/unsubscribe?");
        assertThat(unfold(wire.get(1)))
                .as("ordinary church mail keeps its unsubscribe link")
                .contains("/unsubscribe?");
    }

    /** Undo quoted-printable soft line breaks so URLs survive the comparison. */
    private static String unfold(String mime) {
        return mime.replace("=\r\n", "").replace("=\n", "");
    }

    // ── SMS: every public method funnels through the guarded doSend ──────────

    @Test
    @DisplayName("sendForClient is refused for a Trial client, with the reason")
    void smsForClientBlocked() {
        SmsService.SendOutcome out = smsService.sendForClient(TRIAL_CLIENT, TO_PHONE, "hi");
        assertThat(out.sent()).isFalse();
        assertThat(out.reason()).isEqualTo(MessagingPolicy.TRIAL_SMS_MSG);
    }

    @Test
    @DisplayName("The opt-in confirmation is refused for a Trial client too")
    void smsConfirmationBlocked() {
        assertThat(smsService.sendConfirmationRequest(TO_PHONE, "St Anne's", TRIAL_CLIENT)).isFalse();
    }

    @Test
    @DisplayName("A paying client is not stopped by the policy (it stops at 'not configured' instead)")
    void paidClientNotBlockedByPolicy() {
        SmsService.SendOutcome out = smsService.sendForClient(PAID_CLIENT, TO_PHONE, "hi");
        assertThat(out.reason())
                .as("must fail for want of Twilio config, not because of the plan")
                .isNotEqualTo(MessagingPolicy.TRIAL_SMS_MSG)
                .isEqualTo("SMS provider not configured");
    }

    @Test
    @DisplayName("Once upgraded out of Trial, the same client sends")
    void upgradeLiftsTheBlock() throws Exception {
        emailService.sendOrgEmail(TO_EMAIL, "s", "<p>b</p>", TRIAL_CLIENT);
        verify(mailSender, never()).send(any(MimeMessage.class));

        ServiceClientRepository clients = mock(ServiceClientRepository.class);
        ServiceClient upgraded = new ServiceClient();
        upgraded.setClientId(TRIAL_CLIENT);
        upgraded.setSubscriptionType("FULL");
        when(clients.findByClientId(TRIAL_CLIENT)).thenReturn(Optional.of(upgraded));
        MessagingPolicy fresh = new MessagingPolicy(clients);
        emailService.setMessagingPolicy(fresh);

        emailService.sendOrgEmail(TO_EMAIL, "s", "<p>b</p>", TRIAL_CLIENT);
        verify(mailSender, times(1)).send(any(MimeMessage.class));
    }
}
