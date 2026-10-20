package com.churchgeniuspro.messaging;

import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.EmailSettings;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.EmailSettingsRepository;
import com.churchgeniuspro.repository.SmsOptInRepository;
import com.churchgeniuspro.service.SmsService;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.rest.api.v2010.account.MessageCreator;
import com.twilio.type.PhoneNumber;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Toll-free verification / TCPA rules enforced at the one place that hands a text to
 * Twilio: a church's texts go only to numbers that confirmed opt-in for that church, the
 * opt-in confirmation request itself is exempt, and every text carries the church name and
 * "Reply STOP". Twilio's SDK is mocked; no network, invented numbers only.
 */
@DisplayName("SMS — consent required, church name and STOP on every text")
class SmsConsentAndIdentityTest {

    static final String CLIENT = "CHR-test", PHONE = "+19135550123";
    SmsOptInRepository optIn; EmailSettingsRepository email; ChurchRegistrationRepository churches;
    SmsService sms;
    MockedStatic<Message> twilio;
    ArgumentCaptor<String> bodies;

    @BeforeEach
    void setUp() {
        optIn = mock(SmsOptInRepository.class); email = mock(EmailSettingsRepository.class); churches = mock(ChurchRegistrationRepository.class);
        sms = new SmsService("AC00000000000000000000000000000000", "test-token", "+18445245476");
        sms.setOptInRepo(optIn); sms.setEmailSettingsRepo(email); sms.setChurchRepo(churches);
        sms.setRequireOptIn(true); sms.setAddSenderIdentity(true);   // the post-switch-over settings
        when(email.findByClientId(CLIENT)).thenReturn(Optional.empty());
        ChurchRegistration c = new ChurchRegistration(); c.setChurchName("Grace Community Church");
        when(churches.findByClientIdAndDeleteFlagFalse(CLIENT)).thenReturn(Optional.of(c));

        twilio = mockStatic(Message.class);
        MessageCreator creator = mock(MessageCreator.class);
        Message msg = mock(Message.class); when(msg.getSid()).thenReturn("SM_test");
        when(creator.create()).thenReturn(msg);
        bodies = ArgumentCaptor.forClass(String.class);
        twilio.when(() -> Message.creator(any(PhoneNumber.class), any(PhoneNumber.class), bodies.capture())).thenReturn(creator);
    }

    @AfterEach
    void tearDown() { twilio.close(); }

    @Test
    @DisplayName("no confirmed opt-in for this church → not sent, reason says so")
    void refusedWithoutOptIn() {
        when(optIn.isOptedIn(PHONE, CLIENT)).thenReturn(false);
        SmsService.SendOutcome o = sms.sendForClient(CLIENT, "(913) 555-0123", "Volunteer schedule: you serve Sunday 9 AM.");
        assertThat(o.sent()).isFalse();
        assertThat(o.reason()).isEqualTo(SmsService.NOT_OPTED_IN_MSG);
        twilio.verifyNoInteractions();
    }

    @Test
    @DisplayName("confirmed opt-in (looked up by E.164) → sent, with church name prefixed and STOP appended")
    void sentWithIdentity() {
        when(optIn.isOptedIn(PHONE, CLIENT)).thenReturn(true);
        SmsService.SendOutcome o = sms.sendForClient(CLIENT, "(913) 555-0123", "Volunteer schedule: you serve Sunday 9 AM.");
        assertThat(o.sent()).isTrue();
        assertThat(bodies.getValue()).isEqualTo("Grace Community Church: Volunteer schedule: you serve Sunday 9 AM. Reply STOP to opt out.");
        verify(optIn).isOptedIn(PHONE, CLIENT);
    }

    @Test
    @DisplayName("a message that already names the church and says STOP is sent unchanged")
    void compliantUnchanged() {
        when(optIn.isOptedIn(PHONE, CLIENT)).thenReturn(true);
        String body = "Grace Community Church: Reminder - Service today at 10 AM. Reply STOP to opt out.";
        sms.sendForClient(CLIENT, PHONE, body);
        assertThat(bodies.getValue()).isEqualTo(body);
    }

    @Test
    @DisplayName("the email display name is used when set")
    void displayNameFirst() {
        when(optIn.isOptedIn(PHONE, CLIENT)).thenReturn(true);
        EmailSettings es = new EmailSettings(); es.setDisplayName("Grace Church");
        when(email.findByClientId(CLIENT)).thenReturn(Optional.of(es));
        sms.sendForClient(CLIENT, PHONE, "Overdue pickup: Sam is 10m overdue.");
        assertThat(bodies.getValue()).isEqualTo("Grace Church: Overdue pickup: Sam is 10m overdue. Reply STOP to opt out.");
    }

    @Test
    @DisplayName("the opt-in confirmation request reaches a number that has not confirmed yet")
    void confirmationExempt() {
        when(optIn.isOptedIn(anyString(), anyString())).thenReturn(false);
        assertThat(sms.sendConfirmationRequest(PHONE, "Grace Community Church", CLIENT)).isTrue();
        assertThat(bodies.getValue()).startsWith("Grace Community Church: You have requested to receive SMS messages from us. Reply YES to confirm.");
        verify(optIn, never()).isOptedIn(anyString(), anyString());
    }

    @Test
    @DisplayName("sms.require-opt-in=false switches the consent check off (identity rules still apply)")
    void switchOff() {
        sms.setRequireOptIn(false);
        assertThat(sms.sendForClient(CLIENT, PHONE, "Hello").sent()).isTrue();
        assertThat(bodies.getValue()).isEqualTo("Grace Community Church: Hello. Reply STOP to opt out.");
        verify(optIn, never()).isOptedIn(anyString(), anyString());
    }

    @Test
    @DisplayName("DEFAULTS (both switches off, as deployed today): no opt-in lookup, message text exactly as the caller built it")
    void defaultsChangeNothing() {
        SmsService plain = new SmsService("AC00000000000000000000000000000000", "test-token", "+18445245476");
        plain.setOptInRepo(optIn); plain.setEmailSettingsRepo(email); plain.setChurchRepo(churches);
        when(optIn.isOptedIn(anyString(), anyString())).thenReturn(false);
        assertThat(plain.sendForClient(CLIENT, PHONE, "Volunteer schedule: you serve Sunday 9 AM.").sent()).isTrue();
        assertThat(bodies.getValue()).isEqualTo("Volunteer schedule: you serve Sunday 9 AM.");
        assertThat(plain.sendWithOutcome(PHONE, "Your trial ends in 3 days.").sent()).isTrue();
        assertThat(bodies.getValue()).isEqualTo("Your trial ends in 3 days.");
        verify(optIn, never()).isOptedIn(anyString(), anyString());
        verifyNoInteractions(email, churches);
    }

    @Test
    @DisplayName("the switches are independent: opt-in on, identity off → consent checked, text unchanged")
    void switchesIndependent() {
        sms.setAddSenderIdentity(false);
        when(optIn.isOptedIn(PHONE, CLIENT)).thenReturn(true);
        sms.sendForClient(CLIENT, PHONE, "Hello");
        assertThat(bodies.getValue()).isEqualTo("Hello");
        verify(optIn).isOptedIn(PHONE, CLIENT);
    }

    @Test
    @DisplayName("platform sends with no church carry the ChurchGeniusPro name and STOP")
    void platformSends() {
        assertThat(sms.sendWithOutcome(PHONE, "Your trial ends in 3 days.").sent()).isTrue();
        assertThat(bodies.getValue()).isEqualTo("ChurchGeniusPro: Your trial ends in 3 days. Reply STOP to opt out.");
        verify(optIn, never()).isOptedIn(anyString(), anyString());
    }
}
