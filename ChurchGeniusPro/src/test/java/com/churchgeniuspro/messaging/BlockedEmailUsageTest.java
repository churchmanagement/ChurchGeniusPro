package com.churchgeniuspro.messaging;

import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The monthly email allowance counts mail that was actually handed to the mail
 * sender — nothing else. A Trial/demo message the tenant block drops is not sent
 * and must not be metered (it used to be). This is also what lets Phase B meter a
 * message redirected to a verified test address as exactly the one email it is,
 * while the suppressed copies for the other recipients cost nothing.
 */
@DisplayName("Email usage — only delivered mail counts")
class BlockedEmailUsageTest {

    private static final String TRIAL = "CHR-TRIAL";
    private static final String PAID  = "CHR-PAID";

    JavaMailSender mailSender;
    SubscriptionService subs;
    EmailService email;

    @BeforeEach
    void setUp() {
        ServiceClientRepository clients = mock(ServiceClientRepository.class);
        ServiceClient trial = new ServiceClient(); trial.setClientId(TRIAL); trial.setSubscriptionType("TRIAL");
        ServiceClient paid  = new ServiceClient(); paid.setClientId(PAID);  paid.setSubscriptionType("FULL");
        when(clients.findByClientId(TRIAL)).thenReturn(Optional.of(trial));
        when(clients.findByClientId(PAID)).thenReturn(Optional.of(paid));

        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(i -> mock(MimeMessage.class));
        UnsubscribeService unsub = mock(UnsubscribeService.class);
        when(unsub.isUnsubscribed(anyString(), anyString())).thenReturn(false);
        subs = mock(SubscriptionService.class);
        when(subs.canSendEmail(anyString())).thenReturn(true);

        email = new EmailService(mailSender, mock(EmailSettingsRepository.class), mock(ChurchLogoRepository.class),
                mock(PromiseVerseRepository.class), unsub, mock(ChurchRegistrationRepository.class), subs);
        email.setMessagingPolicy(new MessagingPolicy(clients));
    }

    @Test
    @DisplayName("Trial/demo with no verified test email: blocked, nothing sent, usage NOT incremented")
    void blockedTrialMailIsNotMetered() {
        assertThat(email.emailBlockReason(TRIAL)).isEqualTo(MessagingPolicy.TRIAL_EMAIL_MSG);

        email.sendOrgEmail("a@x.org", "s", "<p>b</p>", TRIAL);
        email.sendOrgEmail("b@x.org", "s", "<p>b</p>", TRIAL);
        email.sendGenericEmail("c@x.org", "s", "<p>b</p>", TRIAL);

        verify(mailSender, never()).send(any(MimeMessage.class));
        verify(subs, never()).recordEmailSent(anyString());
    }

    @Test
    @DisplayName("a paying church: each delivered email counts once, exactly as before")
    void deliveredMailIsMeteredOnce() {
        assertThat(email.emailBlockReason(PAID)).isNull();

        email.sendOrgEmail("a@x.org", "s", "<p>b</p>", PAID);
        email.sendOrgEmail("b@x.org", "s", "<p>b</p>", PAID);

        verify(mailSender, times(2)).send(any(MimeMessage.class));
        verify(subs, times(2)).recordEmailSent(PAID);
    }

    @Test
    @DisplayName("a delivery failure is not metered either")
    void failedDeliveryIsNotMetered() {
        doThrow(new org.springframework.mail.MailSendException("smtp down")).when(mailSender).send(any(MimeMessage.class));
        email.sendOrgEmail("a@x.org", "s", "<p>b</p>", PAID);
        verify(subs, never()).recordEmailSent(anyString());
    }
}
