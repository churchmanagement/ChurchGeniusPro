package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ChurchEvent;
import com.churchgeniuspro.hibernate.EventRegistration;
import com.churchgeniuspro.repository.EmailSettingsRepository;
import com.churchgeniuspro.repository.SmsOptInRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Delivery rules for the event-registration confirmation text.
 *
 * <p>Three things had to be true for this message to reach anyone, and none of them were:
 * the number had to be in the format the provider accepts, the recipient had to have
 * consented, and the send had to count against the church's plan allowance. All three
 * failed silently — the registration succeeded either way and the only trace was a line
 * in a log nobody was reading.
 */
class EventRegistrationSmsTest {

    private static final String CHURCH = "CHR-100";

    private SmsService              smsService;
    private WhatsAppSenderService   smsSender;
    private SmsOptInRepository      optInRepo;
    private EmailSettingsRepository emailSettingsRepo;
    private ChurchEventService      svc;

    @BeforeEach
    void setUp() throws Exception {
        smsService        = mock(SmsService.class);
        smsSender         = mock(WhatsAppSenderService.class);
        optInRepo         = mock(SmsOptInRepository.class);
        emailSettingsRepo = mock(EmailSettingsRepository.class);

        when(smsService.isConfigured()).thenReturn(true);
        when(emailSettingsRepo.findByClientId(anyString())).thenReturn(Optional.empty());

        svc = newService();
        setRequireOptIn(true);
    }

    // ── Normalisation ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("The number handed to the provider")
    class Normalisation {

        @BeforeEach
        void optedIn() {
            when(optInRepo.isOptedIn(anyString(), anyString())).thenReturn(true);
        }

        @Test
        @DisplayName("a bare ten-digit number is sent as E.164, not as typed")
        void nationalNumberIsNormalised() {
            svc.sendRegistrationSms(registration("9133330704"), event());

            assertEquals("+19133330704", capturedRecipient());
        }

        @Test
        @DisplayName("a country code without the plus is fixed rather than rejected")
        void missingPlusIsFixed() {
            svc.sendRegistrationSms(registration("19139094249"), event());

            assertEquals("+19139094249", capturedRecipient());
        }

        @Test
        @DisplayName("the opt-in lookup uses the normalised number, or it would match nothing")
        void optInLookupIsNormalised() {
            // sms_opt_in.phone_number holds E.164 and is matched by exact string equality.
            // Looking it up with the raw "9133330704" finds no row, which reads as "not
            // opted in" — so every message would be suppressed for a reason that looks
            // like a consent decision and is actually a formatting bug.
            svc.sendRegistrationSms(registration("(913) 333-0704"), event());

            verify(optInRepo).isOptedIn("+19133330704", CHURCH);
        }

        @Test
        @DisplayName("an unusable number is dropped before the provider, and nothing is guessed")
        void unusableNumberIsDropped() {
            svc.sendRegistrationSms(registration("270917242"), event());

            verifyNoInteractions(smsSender);
            verifyNoInteractions(optInRepo);
        }
    }

    // ── Consent ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Consent")
    class Consent {

        @Test
        @DisplayName("no confirmed opt-in means no message")
        void withoutOptInNothingIsSent() {
            when(optInRepo.isOptedIn(anyString(), anyString())).thenReturn(false);

            svc.sendRegistrationSms(registration("9133330704"), event());

            verifyNoInteractions(smsSender);
        }

        @Test
        @DisplayName("a confirmed opt-in lets the message through")
        void withOptInItSends() {
            when(optInRepo.isOptedIn("+19133330704", CHURCH)).thenReturn(true);

            svc.sendRegistrationSms(registration("9133330704"), event());

            verify(smsSender).sendSingleSms(eq("+19133330704"), anyString(), eq(CHURCH));
        }

        @Test
        @DisplayName("consent is per church — another church's opt-in does not count")
        void optInIsScopedToTheChurch() {
            when(optInRepo.isOptedIn("+19133330704", "CHR-OTHER")).thenReturn(true);
            when(optInRepo.isOptedIn("+19133330704", CHURCH)).thenReturn(false);

            svc.sendRegistrationSms(registration("9133330704"), event());

            verifyNoInteractions(smsSender);
        }

        @Test
        @DisplayName("with the gate switched off, submitting the form is treated as consent")
        void gateCanBeDisabled() {
            setRequireOptIn(false);
            when(optInRepo.isOptedIn(anyString(), anyString())).thenReturn(false);

            svc.sendRegistrationSms(registration("9133330704"), event());

            verify(smsSender).sendSingleSms(eq("+19133330704"), anyString(), eq(CHURCH));
            verifyNoInteractions(optInRepo);
        }
    }

    // ── Quota and message content ────────────────────────────────────────────

    @Nested
    @DisplayName("Routing and content")
    class RoutingAndContent {

        @BeforeEach
        void optedIn() {
            when(optInRepo.isOptedIn(anyString(), anyString())).thenReturn(true);
        }

        @Test
        @DisplayName("the send goes through the quota choke-point, not straight to Twilio")
        void quotaIsApplied() {
            svc.sendRegistrationSms(registration("9133330704"), event());

            // sendSingleSms is what checks canSendSms and records usage. Calling
            // SmsService.send directly skipped the plan's monthly allowance entirely.
            verify(smsSender).sendSingleSms(anyString(), anyString(), eq(CHURCH));
            verify(smsService, never()).send(anyString(), anyString());
        }

        @Test
        @DisplayName("the body still carries the opt-out instruction required for compliance")
        void bodyKeepsOptOut() {
            svc.sendRegistrationSms(registration("9133330704"), event());

            assertTrue(capturedBody().contains("Reply STOP to opt out"), capturedBody());
        }

        @Test
        @DisplayName("nothing is attempted when the provider is not configured")
        void unconfiguredProviderSendsNothing() {
            when(smsService.isConfigured()).thenReturn(false);

            svc.sendRegistrationSms(registration("9133330704"), event());

            verifyNoInteractions(smsSender);
        }
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private String capturedRecipient() {
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        verify(smsSender).sendSingleSms(to.capture(), anyString(), anyString());
        return to.getValue();
    }

    private String capturedBody() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(smsSender).sendSingleSms(anyString(), body.capture(), anyString());
        return body.getValue();
    }

    private void setRequireOptIn(boolean value) {
        ReflectionTestUtils.setField(svc, "requireSmsOptIn", value);
    }

    private static EventRegistration registration(String phone) {
        EventRegistration reg = new EventRegistration();
        reg.setId(1);
        reg.setFirstName("Pat");
        reg.setPhone(phone);
        reg.setAttending(Boolean.TRUE);
        return reg;
    }

    private static ChurchEvent event() {
        ChurchEvent ev = new ChurchEvent();
        ev.setId(42);
        ev.setAppClientId(CHURCH);
        ev.setEventName("Harvest Sunday");
        ev.setEventType("One Day");
        ev.setEventDate(LocalDate.now().plusDays(14));
        return ev;
    }

    /**
     * Builds the service with every collaborator mocked, pinning only the four this test
     * exercises. Reflective so an unrelated new dependency on the constructor does not
     * break a test that has nothing to do with it.
     */
    private ChurchEventService newService() throws Exception {
        Constructor<?> ctor = ChurchEventService.class.getDeclaredConstructors()[0];
        Parameter[] params = ctor.getParameters();
        List<Object> args = new ArrayList<>(params.length);
        for (Parameter p : params) {
            Class<?> t = p.getType();
            if (t == SmsService.class)                   args.add(smsService);
            else if (t == WhatsAppSenderService.class)   args.add(smsSender);
            else if (t == SmsOptInRepository.class)      args.add(optInRepo);
            else if (t == EmailSettingsRepository.class) args.add(emailSettingsRepo);
            else                                          args.add(mock(t));
        }
        ChurchEventService s = (ChurchEventService) ctor.newInstance(args.toArray());
        ReflectionTestUtils.setField(s, "baseUrl", "https://churchgeniuspro.net");
        return s;
    }
}
