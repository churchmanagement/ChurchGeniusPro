package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.ChurchEvent;
import com.churchgeniuspro.hibernate.EventRegistration;
import com.churchgeniuspro.hibernate.EventRegistrationReminderContact;
import com.churchgeniuspro.hibernate.EventRegistrationReminderLog;
import com.churchgeniuspro.repository.AutoReminderRepository;
import com.churchgeniuspro.repository.ChurchEventDayRepository;
import com.churchgeniuspro.repository.ChurchEventRepository;
import com.churchgeniuspro.repository.EventRegistrationReminderContactRepository;
import com.churchgeniuspro.repository.EventRegistrationReminderLogRepository;
import com.churchgeniuspro.repository.EventRegistrationRepository;
import com.churchgeniuspro.repository.ReminderSentLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static com.churchgeniuspro.hibernate.EventRegistrationReminderLog.CHANNEL_EMAIL;
import static com.churchgeniuspro.hibernate.EventRegistrationReminderLog.CHANNEL_SMS;
import static com.churchgeniuspro.hibernate.EventRegistrationReminderLog.STATUS_SENT;
import static com.churchgeniuspro.hibernate.EventRegistrationReminderLog.STATUS_SKIPPED;
import static com.churchgeniuspro.hibernate.EventRegistrationReminderLog.TYPE_INVITE;
import static com.churchgeniuspro.hibernate.EventRegistrationReminderLog.TYPE_REMINDER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EventRegistrationReminderService}: phone/email
 * normalization, registration matching, the send rules (email-only /
 * SMS-only / both / neither), duplicate prevention, and attempt logging.
 *
 * <p>All repositories and messaging services are Mockito mocks — no Spring
 * context, no database.
 */
class EventRegistrationReminderServiceTest {

    /** Must match the zone the service evaluates "today" in. */
    private static final ZoneId SCHED_ZONE = ZoneId.of("America/Chicago");
    private static final String CLIENT_ID  = "client-1";
    private static final int    EVENT_ID   = 42;
    private static final int    DAYS       = 5;

    private ChurchEventRepository                      eventRepo;
    private ChurchEventDayRepository                   eventDayRepo;
    private EventRegistrationRepository                registrationRepo;
    private EventRegistrationReminderContactRepository contactRepo;
    private EventRegistrationReminderLogRepository     logRepo;
    private ReminderSentLogRepository                  sentLogRepo;
    private AutoReminderRepository                     autoReminderRepo;
    private EmailService                               emailService;
    private SmsService                                 smsService;
    private WhatsAppSenderService                      whatsAppSender;

    private EventRegistrationReminderService service;
    private ChurchEvent event;

    @BeforeEach
    void setUp() throws Exception {
        eventRepo        = mock(ChurchEventRepository.class);
        eventDayRepo     = mock(ChurchEventDayRepository.class);
        registrationRepo = mock(EventRegistrationRepository.class);
        contactRepo      = mock(EventRegistrationReminderContactRepository.class);
        logRepo          = mock(EventRegistrationReminderLogRepository.class);
        sentLogRepo      = mock(ReminderSentLogRepository.class);
        autoReminderRepo = mock(AutoReminderRepository.class);
        emailService     = mock(EmailService.class);
        // Phase B: the service asks how the tenant's mail is delivered; these are paying churches.
        when(emailService.delivery(any())).thenReturn(new EmailService.Delivery(EmailService.DeliveryMode.NORMAL, null, null));
        smsService       = mock(SmsService.class);
        whatsAppSender   = mock(WhatsAppSenderService.class);

        service = new EventRegistrationReminderService(
                eventRepo, eventDayRepo, registrationRepo, contactRepo, logRepo,
                sentLogRepo, autoReminderRepo, emailService, smsService, whatsAppSender,
                mock(com.churchgeniuspro.repository.ChurchEventImageRepository.class),
                new com.churchgeniuspro.service.EventPublicTokenService(eventRepo));

        // @Value field — set directly since there is no Spring context.
        Field baseUrl = EventRegistrationReminderService.class.getDeclaredField("baseUrl");
        baseUrl.setAccessible(true);
        baseUrl.set(service, "http://localhost:8080");

        // A One-Day event whose reminder (5 days before) fires today.
        event = new ChurchEvent();
        event.setId(EVENT_ID);
        event.setEventName("Musical Night");
        event.setEventType("One Day");
        event.setEventDate(LocalDate.now(SCHED_ZONE).plusDays(DAYS));
        event.setStartTime("17:00");
        event.setEndTime("18:00");
        event.setAppClientId(CLIENT_ID);
        event.setRegistrationReminderEnabled(true);
        event.setRegistrationReminderDays(DAYS);
        event.setRegistrationLink("http://localhost:8080/event-register/tok123");

        // Default happy-path stubs.
        when(autoReminderRepo.findActiveClientIds()).thenReturn(List.of(CLIENT_ID));
        when(eventRepo.findByRegistrationReminderEnabledTrueAndDeleteFlagFalse())
                .thenReturn(List.of(event));
        when(sentLogRepo.existsByAppClientIdAndReminderTypeAndReferenceKeyAndSentDate(
                anyString(), anyString(), anyString(), any())).thenReturn(false);
        when(logRepo.existsByEventIdAndMessageTypeAndChannelAndRecipientNormAndStatus(
                any(), anyString(), anyString(), anyString(), anyString())).thenReturn(false);
        when(smsService.isConfigured()).thenReturn(true);
        when(emailService.getChurchName(CLIENT_ID)).thenReturn("Test Church");
        when(registrationRepo.findByEventIdOrderByCreatedDateAsc(EVENT_ID)).thenReturn(List.of());
        when(contactRepo.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of());
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static EventRegistration registration(String email, String phone) {
        EventRegistration r = new EventRegistration();
        r.setEventId(EVENT_ID);
        r.setEmail(email);
        r.setPhone(phone);
        return r;
    }

    private static EventRegistrationReminderContact contact(Integer id, String email, String phone) {
        EventRegistrationReminderContact c = new EventRegistrationReminderContact();
        c.setId(id);
        c.setEventId(EVENT_ID);
        c.setEmail(email);
        c.setPhone(phone);
        c.setAppClientId(CLIENT_ID);
        return c;
    }

    private List<EventRegistrationReminderLog> capturedLogs() {
        ArgumentCaptor<EventRegistrationReminderLog> captor =
                ArgumentCaptor.forClass(EventRegistrationReminderLog.class);
        verify(logRepo, org.mockito.Mockito.atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    // ── Normalization ─────────────────────────────────────────────────────

    @Nested
    class Normalization {

        @Test
        void equivalentPhoneFormats_normalizeToSameNumber() {
            String expected = "9133330704";
            assertEquals(expected, EventRegistrationReminderService.normalizePhone("9133330704"));
            assertEquals(expected, EventRegistrationReminderService.normalizePhone("(913) 333-0704"));
            assertEquals(expected, EventRegistrationReminderService.normalizePhone("+1 913-333-0704"));
            assertEquals(expected, EventRegistrationReminderService.normalizePhone("913-333-0704"));
            assertEquals(expected, EventRegistrationReminderService.normalizePhone("1 (913) 333.0704"));
        }

        @Test
        void invalidPhones_normalizeToNull() {
            assertNull(EventRegistrationReminderService.normalizePhone(null));
            assertNull(EventRegistrationReminderService.normalizePhone(""));
            assertNull(EventRegistrationReminderService.normalizePhone("12345"));
            assertNull(EventRegistrationReminderService.normalizePhone("not-a-phone"));
            assertNull(EventRegistrationReminderService.normalizePhone("+44 20 7946 0958")); // non-US length
        }

        @Test
        void htmlDescriptions_renderAsPlainText() {
            assertEquals("Testing Anson",
                    EventRegistrationReminderService.htmlToPlainText("<p>Testing Anson</p>"));
            assertEquals("Line 1\nLine 2",
                    EventRegistrationReminderService.htmlToPlainText("Line 1<br/>Line 2"));
            assertEquals("A & B",
                    EventRegistrationReminderService.htmlToPlainText("<div>A &amp; B</div>"));
            assertEquals("plain text stays",
                    EventRegistrationReminderService.htmlToPlainText("plain text stays"));
            assertEquals("", EventRegistrationReminderService.htmlToPlainText(null));
            assertEquals("", EventRegistrationReminderService.htmlToPlainText("   "));
        }

        @Test
        void emails_lowercasedTrimmed_invalidRejected() {
            assertEquals("contactanson@gmail.com",
                    EventRegistrationReminderService.normalizeEmail("  ContactAnson@Gmail.COM "));
            assertNull(EventRegistrationReminderService.normalizeEmail(null));
            assertNull(EventRegistrationReminderService.normalizeEmail("   "));
            assertNull(EventRegistrationReminderService.normalizeEmail("missing-at-sign.com"));
            assertNull(EventRegistrationReminderService.normalizeEmail("two@@ats.com@x"));
        }
    }

    // ── Send rules (the spec's worked example) ───────────────────────────

    @Nested
    class SendRules {

        @Test
        void specExample_registeredSkipped_emailOnlySendsEmail_phoneOnlySendsSms() {
            // informanson@gmail.com / 9133330704 is already registered.
            when(registrationRepo.findByEventIdOrderByCreatedDateAsc(EVENT_ID))
                    .thenReturn(List.of(registration("informanson@gmail.com", "9133330704")));
            when(contactRepo.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of(
                    contact(1, "informanson@gmail.com", "9133330704"),  // registered → nothing
                    contact(2, "contactanson@gmail.com", null),          // email only → email
                    contact(3, null, "2017398339")));                    // phone only → SMS

            service.runRegistrationReminders();

            // Entry 2: exactly one email, to contactanson only.
            verify(emailService).sendOrgEmail(
                    eq("contactanson@gmail.com"), anyString(), anyString(), eq(CLIENT_ID));
            verify(emailService, never()).sendOrgEmail(
                    eq("informanson@gmail.com"), anyString(), anyString(), anyString());

            // Entry 3: exactly one SMS, to 2017398339 only (10-digit form, SMS-only flags).
            verify(whatsAppSender).sendToPhoneMultiChannel(
                    eq("2017398339"), anyString(), eq(CLIENT_ID), eq(false), eq(true));
            verify(whatsAppSender, never()).sendToPhoneMultiChannel(
                    eq("9133330704"), anyString(), anyString(), anyBoolean(), anyBoolean());

            // Logs: entry 1 → 2 SKIPPED (email+phone); entries 2 & 3 → 1 SENT each.
            List<EventRegistrationReminderLog> logs = capturedLogs();
            assertEquals(4, logs.size());
            assertEquals(2, logs.stream().filter(l -> STATUS_SKIPPED.equals(l.getStatus())).count());
            assertEquals(2, logs.stream().filter(l -> STATUS_SENT.equals(l.getStatus())).count());
            assertTrue(logs.stream()
                    .filter(l -> STATUS_SKIPPED.equals(l.getStatus()))
                    .allMatch(l -> l.getReason() != null && l.getReason().contains("Already registered")));
        }

        @Test
        void contactWithBothChannels_unregistered_getsEmailAndSms() {
            when(contactRepo.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of(
                    contact(1, "new@person.com", "(201) 739-8339")));

            service.runRegistrationReminders();

            verify(emailService).sendOrgEmail(
                    eq("new@person.com"), anyString(), anyString(), eq(CLIENT_ID));
            verify(whatsAppSender).sendToPhoneMultiChannel(
                    eq("2017398339"), anyString(), eq(CLIENT_ID), eq(false), eq(true));
        }

        @Test
        void phoneRegisteredInDifferentFormat_blocksBothChannels() {
            // Registered with E.164; contact configured with dotted format + an email.
            when(registrationRepo.findByEventIdOrderByCreatedDateAsc(EVENT_ID))
                    .thenReturn(List.of(registration(null, "+1 913-333-0704")));
            when(contactRepo.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of(
                    contact(1, "someone@else.com", "913.333.0704")));

            service.runRegistrationReminders();

            verify(emailService, never()).sendOrgEmail(anyString(), anyString(), anyString(), anyString());
            verify(whatsAppSender, never()).sendToPhoneMultiChannel(
                    anyString(), anyString(), anyString(), anyBoolean(), anyBoolean());
            assertTrue(capturedLogs().stream().allMatch(l -> STATUS_SKIPPED.equals(l.getStatus())));
        }

        @Test
        void emailRegisteredCaseInsensitive_blocksEntry() {
            when(registrationRepo.findByEventIdOrderByCreatedDateAsc(EVENT_ID))
                    .thenReturn(List.of(registration("InformAnson@GMAIL.com", null)));
            when(contactRepo.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of(
                    contact(1, "informanson@gmail.com", null)));

            service.runRegistrationReminders();

            verify(emailService, never()).sendOrgEmail(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        void invalidEmailAndPhone_skippedWithReasons_nothingSent() {
            when(contactRepo.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of(
                    contact(1, "not-an-email", "12345")));

            service.runRegistrationReminders();

            verify(emailService, never()).sendOrgEmail(anyString(), anyString(), anyString(), anyString());
            verify(whatsAppSender, never()).sendToPhoneMultiChannel(
                    anyString(), anyString(), anyString(), anyBoolean(), anyBoolean());
            List<EventRegistrationReminderLog> logs = capturedLogs();
            assertEquals(2, logs.size());
            assertTrue(logs.stream().anyMatch(l -> "Invalid email address".equals(l.getReason())));
            assertTrue(logs.stream().anyMatch(l -> "Invalid phone number".equals(l.getReason())));
        }

        @Test
        void smsNotConfigured_logsFailed_emailStillSent() {
            when(smsService.isConfigured()).thenReturn(false);
            when(contactRepo.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of(
                    contact(1, "a@b.com", "2017398339")));

            service.runRegistrationReminders();

            verify(emailService).sendOrgEmail(eq("a@b.com"), anyString(), anyString(), eq(CLIENT_ID));
            verify(whatsAppSender, never()).sendToPhoneMultiChannel(
                    anyString(), anyString(), anyString(), anyBoolean(), anyBoolean());
            assertTrue(capturedLogs().stream().anyMatch(l ->
                    CHANNEL_SMS.equals(l.getChannel())
                    && "FAILED".equals(l.getStatus())
                    && l.getReason().contains("not configured")));
        }
    }

    // ── Duplicate prevention ──────────────────────────────────────────────

    @Nested
    class DuplicatePrevention {

        @Test
        void sameEmailOnTwoContactRows_sendsOnce_logsDuplicateSkip() {
            when(contactRepo.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of(
                    contact(1, "dup@x.com", null),
                    contact(2, "DUP@X.COM", null)));

            service.runRegistrationReminders();

            verify(emailService).sendOrgEmail(eq("dup@x.com"), anyString(), anyString(), eq(CLIENT_ID));
            verify(emailService, never()).sendOrgEmail(eq("DUP@X.COM"), anyString(), anyString(), anyString());
            assertTrue(capturedLogs().stream().anyMatch(l ->
                    STATUS_SKIPPED.equals(l.getStatus()) && l.getReason().contains("Duplicate")));
        }

        @Test
        void previouslySentToRecipient_skipsAsDuplicate() {
            when(logRepo.existsByEventIdAndMessageTypeAndChannelAndRecipientNormAndStatus(
                    EVENT_ID, TYPE_REMINDER, CHANNEL_EMAIL, "old@x.com", STATUS_SENT)).thenReturn(true);
            when(contactRepo.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of(
                    contact(1, "old@x.com", null)));

            service.runRegistrationReminders();

            verify(emailService, never()).sendOrgEmail(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        void scheduleAlreadyProcessedToday_doesNothing() {
            when(sentLogRepo.existsByAppClientIdAndReminderTypeAndReferenceKeyAndSentDate(
                    eq(CLIENT_ID), eq("EVENT_REG_REMINDER"), contains(String.valueOf(EVENT_ID)), any()))
                    .thenReturn(true);
            when(contactRepo.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of(
                    contact(1, "a@b.com", null)));

            service.runRegistrationReminders();

            verifyNoInteractions(emailService, whatsAppSender);
            verify(logRepo, never()).save(any());
        }
    }

    // ── Trigger-date and gating rules ────────────────────────────────────

    @Nested
    class Gating {

        @Test
        void notTheReminderDate_nothingHappens() {
            event.setEventDate(LocalDate.now(SCHED_ZONE).plusDays(DAYS + 1));
            when(contactRepo.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of(
                    contact(1, "a@b.com", null)));

            service.runRegistrationReminders();

            verifyNoInteractions(emailService, whatsAppSender);
        }

        @Test
        void inactiveClient_isSkipped() {
            when(autoReminderRepo.findActiveClientIds()).thenReturn(List.of("someone-else"));
            when(contactRepo.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of(
                    contact(1, "a@b.com", null)));

            service.runRegistrationReminders();

            verifyNoInteractions(emailService, whatsAppSender);
        }

        @Test
        void missingDaysBefore_isSkipped() {
            event.setRegistrationReminderDays(null);
            when(contactRepo.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of(
                    contact(1, "a@b.com", null)));

            service.runRegistrationReminders();

            verifyNoInteractions(emailService, whatsAppSender);
        }
    }

    // ── Invitations (Send Invite) ────────────────────────────────────────

    @Nested
    class Invitations {

        @Test
        void invite_bothChannels_sendsEmailAndSms_withInviteContent() {
            when(contactRepo.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of(
                    contact(1, "guest@x.com", "(201) 739-8339")));

            EventRegistrationReminderService.RunResult r = service.sendInvitations(event);

            assertTrue(r.processed());
            assertEquals(1, r.emailsSent);
            assertEquals(1, r.smsSent);

            ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> body    = ArgumentCaptor.forClass(String.class);
            verify(emailService).sendOrgEmail(eq("guest@x.com"), subject.capture(), body.capture(), eq(CLIENT_ID));
            assertEquals("You're Invited: Musical Night", subject.getValue());
            String html = body.getValue();
            assertTrue(html.contains("Test Church"),        "church name in header");
            assertTrue(html.contains("Musical Night"),      "event name");
            assertTrue(html.contains("You are invited to this event. We would love to have you join us!"),
                    "invitation message");
            assertTrue(html.contains("RSVP Now"),           "button label");
            assertTrue(html.contains("event-register/tok123"), "RSVP link");

            ArgumentCaptor<String> sms = ArgumentCaptor.forClass(String.class);
            verify(whatsAppSender).sendToPhoneMultiChannel(
                    eq("2017398339"), sms.capture(), eq(CLIENT_ID), eq(false), eq(true));
            String text = sms.getValue();
            assertTrue(text.startsWith("You have been invited to the event \"Musical Night\"."),
                    "invite message first");
            assertTrue(text.contains("Date: "),             "date");
            assertTrue(text.contains("& Time: 5:00 PM"),    "time");
            assertTrue(text.contains("Please RSVP here:"),  "RSVP call to action");
            assertTrue(text.contains("event-register/tok123"), "RSVP link");
            assertTrue(text.length() <= 250, "SMS body too long: " + text.length());
        }

        @Test
        void invite_emailOnlyAndPhoneOnly_useMatchingChannelOnly() {
            when(contactRepo.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of(
                    contact(1, "onlyemail@x.com", null),
                    contact(2, null, "9133330704")));

            EventRegistrationReminderService.RunResult r = service.sendInvitations(event);

            assertEquals(1, r.emailsSent);
            assertEquals(1, r.smsSent);
            verify(emailService).sendOrgEmail(eq("onlyemail@x.com"), anyString(), anyString(), eq(CLIENT_ID));
            verify(whatsAppSender).sendToPhoneMultiChannel(
                    eq("9133330704"), anyString(), eq(CLIENT_ID), eq(false), eq(true));
        }

        @Test
        void invite_duplicates_skipped_inRunAndAcrossRuns() {
            // Across runs: this email was already invited (INVITE-scoped SENT row).
            when(logRepo.existsByEventIdAndMessageTypeAndChannelAndRecipientNormAndStatus(
                    EVENT_ID, TYPE_INVITE, CHANNEL_EMAIL, "old@x.com", STATUS_SENT)).thenReturn(true);
            when(contactRepo.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of(
                    contact(1, "old@x.com", null),
                    contact(2, "new@x.com", null),
                    contact(3, "NEW@X.COM", null)));   // in-run duplicate of #2

            EventRegistrationReminderService.RunResult r = service.sendInvitations(event);

            assertEquals(1, r.emailsSent);
            assertEquals(2, r.skipped);
            verify(emailService).sendOrgEmail(eq("new@x.com"), anyString(), anyString(), eq(CLIENT_ID));
            verify(emailService, never()).sendOrgEmail(eq("old@x.com"), anyString(), anyString(), anyString());
        }

        @Test
        void invite_sentEvenIfAlreadyRegistered_registrationDoesNotBlockInvites() {
            when(registrationRepo.findByEventIdOrderByCreatedDateAsc(EVENT_ID))
                    .thenReturn(List.of(registration("guest@x.com", null)));
            when(contactRepo.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of(
                    contact(1, "guest@x.com", null)));

            EventRegistrationReminderService.RunResult r = service.sendInvitations(event);

            assertEquals(1, r.emailsSent);
        }

        @Test
        void invite_priorReminderSend_doesNotBlockInvite() {
            // A REMINDER was sent to this address; INVITE dedupe is scoped separately.
            when(logRepo.existsByEventIdAndMessageTypeAndChannelAndRecipientNormAndStatus(
                    EVENT_ID, TYPE_REMINDER, CHANNEL_EMAIL, "guest@x.com", STATUS_SENT)).thenReturn(true);
            when(contactRepo.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of(
                    contact(1, "guest@x.com", null)));

            EventRegistrationReminderService.RunResult r = service.sendInvitations(event);

            assertEquals(1, r.emailsSent);
        }

        @Test
        void invite_invalidContacts_loggedNotSent() {
            when(contactRepo.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of(
                    contact(1, "bad-email", "123")));

            EventRegistrationReminderService.RunResult r = service.sendInvitations(event);

            assertEquals(0, r.emailsSent);
            assertEquals(0, r.smsSent);
            assertEquals(2, r.skipped);
            verifyNoInteractions(whatsAppSender);
            List<EventRegistrationReminderLog> logs = capturedLogs();
            assertTrue(logs.stream().allMatch(l -> TYPE_INVITE.equals(l.getMessageType())));
            assertTrue(logs.stream().anyMatch(l -> "Invalid email address".equals(l.getReason())));
            assertTrue(logs.stream().anyMatch(l -> "Invalid phone number".equals(l.getReason())));
        }

        @Test
        void invite_noContacts_returnsSkipReason() {
            EventRegistrationReminderService.RunResult r = service.sendInvitations(event);
            assertFalse(r.processed());
            assertTrue(r.skipReason.contains("no contacts"));
        }
    }

    // ── Content sanity ───────────────────────────────────────────────────

    @Test
    void emailBody_usesRsvpWordingAndStripsDescriptionHtml() {
        event.setNote("<p>Testing Anson</p>");
        when(contactRepo.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of(
                contact(1, "a@b.com", null)));

        service.runRegistrationReminders();

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body    = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendOrgEmail(eq("a@b.com"), subject.capture(), body.capture(), eq(CLIENT_ID));

        assertTrue(subject.getValue().contains("RSVP for Musical Night"), "subject wording");
        String html = body.getValue();
        assertTrue(html.contains("Musical Night"),                    "event name in header");
        assertTrue(html.contains("RSVP Reminder"),                    "header subtitle");
        assertTrue(html.contains("We are waiting for your response"), "reminder message");
        assertTrue(html.contains("RSVP Now"),                         "button label");
        assertTrue(html.contains("5:00 PM"),                          "event time");
        assertTrue(html.contains("event-register/tok123"),            "registration link");
        assertTrue(html.contains("Testing Anson"),                    "description text");
        assertFalse(html.contains("&lt;p&gt;"),                       "no literal HTML tags from description");
        assertFalse(html.contains("Event:"),                          "event name not repeated in details");
        assertFalse(html.contains("Register Now"),                    "old button label gone");
    }

    @Test
    void smsBody_matchesRsvpFormat() {
        when(contactRepo.findByEventIdOrderByIdAsc(EVENT_ID)).thenReturn(List.of(
                contact(1, null, "2017398339")));

        service.runRegistrationReminders();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(whatsAppSender).sendToPhoneMultiChannel(
                eq("2017398339"), body.capture(), eq(CLIENT_ID), eq(false), eq(true));

        String sms = body.getValue();
        assertTrue(sms.startsWith("We are waiting for your response to the event \"Musical Night\"."),
                "RSVP message with event name first");
        assertTrue(sms.contains("Date: "),                            "date label");
        assertTrue(sms.contains("& Time: 5:00 PM"),                   "time label");
        assertTrue(sms.contains("Please RSVP here:\nhttp://localhost:8080/event-register/tok123"),
                "RSVP call to action with link");
        assertFalse(sms.contains("Register:"),                        "no Register: prefix");
        assertFalse(sms.contains("You haven't registered"),           "old message gone");
        // Church-name prefix + opt-out suffix are appended by WhatsAppSenderService;
        // the core body must leave room for them within a 320-char budget.
        assertTrue(sms.length() <= 250, "SMS body too long: " + sms.length());
    }
}
