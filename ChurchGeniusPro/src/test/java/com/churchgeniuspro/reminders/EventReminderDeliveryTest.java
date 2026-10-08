package com.churchgeniuspro.reminders;

import com.churchgeniuspro.hibernate.ChurchEvent;
import com.churchgeniuspro.hibernate.EventRegistration;
import com.churchgeniuspro.hibernate.EventReminder;
import com.churchgeniuspro.hibernate.EventRegistrationReminderLog;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Who actually receives an event reminder.
 *
 * <p>The reported symptom was that one send reached two phone numbers out of a
 * much longer list, and nobody could establish why. That turned out to be less a
 * single bug than a design in which several different failures were
 * indistinguishable from success:
 *
 * <ul>
 *   <li>the recipient loop had no exception handling, so the first recipient
 *       whose send threw — and each iteration made three database calls, any of
 *       which could — ended the send for everyone after them;</li>
 *   <li>the sent-log row was written <em>before</em> sending, so a run that
 *       stopped half way was never retried;</li>
 *   <li>a phone number the normaliser refused was dropped with no log line at
 *       any level;</li>
 *   <li>the provider's rejection reason was reduced to a boolean, so "this
 *       person replied STOP" and "your account is not registered to send" were
 *       both simply "did not send";</li>
 *   <li>and nothing recorded per recipient, so afterwards there was no way to
 *       tell a send that reached two people from one that reached forty.</li>
 * </ul>
 *
 * <p>These tests hold each of those closed. The delivery assertions are about
 * <em>everyone else</em> still receiving the message when one recipient fails,
 * which is the property that was actually violated.
 */
class EventReminderDeliveryTest {

    private static final String CLIENT = "CHR-100";

    private EventReminderRepository     reminderRepo;
    private ChurchEventRepository       eventRepo;
    private EventRegistrationRepository registrationRepo;
    private EmailService                emailService;
    private WhatsAppSenderService       sender;
    private WebPushService              webPush;
    private ReminderSentLogRepository   sentLogRepo;
    private AutoReminderRepository      autoReminderRepo;
    private EventRegistrationReminderLogRepository logRepo;

    private ReminderSchedulerService svc;

    private final List<EventRegistrationReminderLog> written = new ArrayList<>();

    /**
     * The scheduler's business time zone.
     *
     * <p>{@code runHalfHourlyReminders} computes its "today" as
     * {@code LocalDate.now(America/Chicago)}, deliberately: reminder windows are a
     * business rule, not a property of whatever machine happens to run the job.
     */
    private static final java.time.ZoneId SCHED_ZONE = java.time.ZoneId.of("America/Chicago");

    /**
     * The event's date, in the SCHEDULER's zone rather than the JVM's default.
     *
     * <p>This used to be a bare {@code LocalDate.now()}, which made the whole class
     * fail for five hours a day on any machine not set to America/Chicago. On a UTC
     * build agent, everything between 19:00 and midnight Chicago time is already
     * "tomorrow" by the default clock, so {@code eventDate.equals(today)} was false,
     * the same-day rule never fired, and every send-counting assertion saw zero —
     * eight failures that looked random and vanished by morning.
     */
    private static final LocalDate EVENT_DAY = LocalDate.now(SCHED_ZONE);

    @BeforeEach
    void setUp() throws Exception {
        reminderRepo     = mock(EventReminderRepository.class);
        eventRepo        = mock(ChurchEventRepository.class);
        registrationRepo = mock(EventRegistrationRepository.class);
        emailService     = mock(EmailService.class);
        sender           = mock(WhatsAppSenderService.class);
        webPush          = mock(WebPushService.class);
        sentLogRepo      = mock(ReminderSentLogRepository.class);
        autoReminderRepo = mock(AutoReminderRepository.class);
        logRepo          = mock(EventRegistrationReminderLogRepository.class);

        when(autoReminderRepo.findActiveClientIds()).thenReturn(List.of(CLIENT));
        when(sentLogRepo.existsByAppClientIdAndReminderTypeAndReferenceKeyAndSentDate(
                anyString(), anyString(), anyString(), any())).thenReturn(false);
        when(sentLogRepo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(logRepo.save(any(EventRegistrationReminderLog.class))).thenAnswer(i -> {
            written.add(i.getArgument(0));
            return i.getArgument(0);
        });
        // Default: every send succeeds. Individual tests override one recipient.
        when(sender.sendSmsWithOutcome(anyString(), anyString(), anyString()))
                .thenReturn(SmsService.SendOutcome.ok());

        svc = buildService();
        ReflectionTestUtils.setField(svc, "baseUrl", "https://example.org");
    }

    /**
     * Builds the scheduler with mocks for the dependencies this path uses and
     * nulls elsewhere, resolved by parameter type so the test does not have to
     * be edited every time an unrelated collaborator is added.
     */
    private ReminderSchedulerService buildService() throws Exception {
        Constructor<?> ctor = ReminderSchedulerService.class.getDeclaredConstructors()[0];
        Parameter[] params = ctor.getParameters();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Class<?> t = params[i].getType();
            if (t == EventReminderRepository.class)            args[i] = reminderRepo;
            else if (t == ChurchEventRepository.class)         args[i] = eventRepo;
            else if (t == EventRegistrationRepository.class)   args[i] = registrationRepo;
            else if (t == EmailService.class)                  args[i] = emailService;
            else if (t == WhatsAppSenderService.class)         args[i] = sender;
            else if (t == WebPushService.class)                args[i] = webPush;
            else if (t == ReminderSentLogRepository.class)     args[i] = sentLogRepo;
            else if (t == AutoReminderRepository.class)        args[i] = autoReminderRepo;
            else if (t == EventRegistrationReminderLogRepository.class) args[i] = logRepo;
            else args[i] = mock(t);
        }
        ctor.setAccessible(true);
        return (ReminderSchedulerService) ctor.newInstance(args);
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private ChurchEvent event() {
        ChurchEvent e = new ChurchEvent();
        e.setId(42);
        e.setAppClientId(CLIENT);
        e.setEventName("Musical Night");
        e.setEventType("One Day");
        e.setEventDate(EVENT_DAY);
        e.setStartTime("17:00");
        return e;
    }

    private EventReminder sameDayRule() {
        EventReminder r = new EventReminder();
        r.setId(1);
        r.setAppClientId(CLIENT);
        r.setEventId(42);
        r.setSameDay(true);
        r.setSendSms(true);
        r.setSendEmail(false);
        r.setSendWhatsApp(false);
        r.setDisabled(false);
        return r;
    }

    private EventRegistration reg(int id, String phone) {
        EventRegistration r = new EventRegistration();
        r.setId(id);
        r.setEventId(42);
        r.setPhone(phone);
        r.setAttending(true);
        return r;
    }

    private void wire(EventReminder rule, List<EventRegistration> people) {
        when(reminderRepo.findByDisabledFalse()).thenReturn(List.of(rule));
        when(eventRepo.findByIdAndDeleteFlagFalse(42)).thenReturn(Optional.of(event()));
        when(registrationRepo.findRemindableByEventId(42)).thenReturn(people);
    }

    private void run() {
        svc.runHalfHourlyReminders();
    }

    private List<String> smsRecipients() {
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeast(0)).sendSmsWithOutcome(to.capture(), anyString(), anyString());
        return to.getAllValues();
    }

    // ── the reported failure ───────────────────────────────────────────────

    @Test
    @DisplayName("one recipient throwing does not stop the recipients after them")
    void oneFailureDoesNotStopTheRest() {
        wire(sameDayRule(), List.of(
                reg(1, "+13463705928"),
                reg(2, "+19134569296"),
                reg(3, "+19135550143"),      // this one blows up
                reg(4, "+19135550144"),
                reg(5, "+19135550145")));

        when(sender.sendSmsWithOutcome(eq("+19135550143"), anyString(), anyString()))
                .thenThrow(new RuntimeException("transient database error"));

        run();

        List<String> got = smsRecipients();
        assertTrue(got.contains("+19135550144"), "recipient after the failure must still be attempted");
        assertTrue(got.contains("+19135550145"), "and so must the one after that");
        assertEquals(5, got.size(), "every registrant should be attempted exactly once");
    }

    @Test
    @DisplayName("a provider rejection for one person does not stop anyone else")
    void providerRejectionIsIsolated() {
        wire(sameDayRule(), List.of(
                reg(1, "+13463705928"),
                reg(2, "+19135550143"),
                reg(3, "+19135550144")));

        when(sender.sendSmsWithOutcome(eq("+19135550143"), anyString(), anyString()))
                .thenReturn(new SmsService.SendOutcome(false, 21610,
                        "Twilio 21610: recipient replied STOP and is blocked by the carrier"));

        run();

        assertEquals(3, smsRecipients().size(), "a blocked recipient must not cost the others their message");
        assertTrue(written.stream().anyMatch(l ->
                        "FAILED".equals(l.getStatus()) && l.getReason() != null
                                && l.getReason().contains("21610")),
                "the provider's reason must be recorded, not discarded");
    }

    @Test
    @DisplayName("an unusable phone number is skipped, recorded, and costs nobody else")
    void unusablePhoneIsRecordedNotSilent() {
        wire(sameDayRule(), List.of(
                reg(1, "+13463705928"),
                reg(2, "270917242"),          // nine digits — cannot be resolved
                reg(3, "+19135550144")));

        run();

        List<String> got = smsRecipients();
        assertEquals(2, got.size(), "the unusable number is not handed to the provider");
        assertTrue(got.contains("+13463705928") && got.contains("+19135550144"));

        assertTrue(written.stream().anyMatch(l ->
                        "SKIPPED".equals(l.getStatus())
                                && "Not a usable phone number".equals(l.getReason())),
                "the skip must be recorded with a reason someone can act on");
    }

    @Test
    @DisplayName("the same person registered twice is texted once")
    void duplicatePhoneCollapses() {
        wire(sameDayRule(), List.of(
                reg(1, "+19135550143"),
                reg(2, "(913) 555-0143"),     // same number, typed differently
                reg(3, "+19135550144")));

        run();

        assertEquals(2, smsRecipients().size(), "one number, one message");
    }

    @Test
    @DisplayName("every attempt is recorded, so a partial send is visible afterwards")
    void everyAttemptIsRecorded() {
        wire(sameDayRule(), List.of(
                reg(1, "+13463705928"),
                reg(2, "+19134569296"),
                reg(3, "+19135550144")));

        run();

        assertEquals(3, written.size(), "one audit row per recipient");
        assertTrue(written.stream().allMatch(l -> "SAME_DAY".equals(l.getMessageType())),
                "rows must be tagged with the trigger that produced them");
        assertTrue(written.stream().allMatch(l -> "SMS".equals(l.getChannel())));
        assertTrue(written.stream().allMatch(l -> CLIENT.equals(l.getAppClientId())),
                "rows must carry the org so one church cannot read another's");
        assertTrue(written.stream().allMatch(l -> Integer.valueOf(42).equals(l.getEventId())));
    }

    @Test
    @DisplayName("a used-up monthly allowance is recorded against each recipient it cost")
    void exhaustedAllowanceIsRecorded() {
        wire(sameDayRule(), List.of(
                reg(1, "+13463705928"),
                reg(2, "+19134569296"),
                reg(3, "+19135550144"),
                reg(4, "+19135550145")));

        when(sender.sendSmsWithOutcome(anyString(), anyString(), anyString()))
                .thenReturn(SmsService.SendOutcome.ok())
                .thenReturn(SmsService.SendOutcome.ok())
                .thenReturn(SmsService.SendOutcome.fail(
                        "Monthly SMS allowance for this subscription plan is used up"))
                .thenReturn(SmsService.SendOutcome.fail(
                        "Monthly SMS allowance for this subscription plan is used up"));

        run();

        long blocked = written.stream()
                .filter(l -> "FAILED".equals(l.getStatus()))
                .filter(l -> l.getReason() != null && l.getReason().startsWith("Monthly SMS allowance"))
                .count();
        assertEquals(2, blocked,
                "the two people the allowance cost must be identifiable afterwards");
    }

    // ── channel and trigger correctness ────────────────────────────────────

    @Test
    @DisplayName("SMS is not sent when the rule has SMS switched off")
    void smsRespectsTheChannelSwitch() {
        EventReminder r = sameDayRule();
        r.setSendSms(false);
        wire(r, List.of(reg(1, "+13463705928")));

        run();

        verify(sender, never()).sendSmsWithOutcome(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a reminder that is not due today sends nothing")
    void wrongDaySendsNothing() {
        EventReminder r = sameDayRule();
        r.setSameDay(true);
        ChurchEvent future = event();
        future.setEventDate(EVENT_DAY.plusDays(9));
        when(reminderRepo.findByDisabledFalse()).thenReturn(List.of(r));
        when(eventRepo.findByIdAndDeleteFlagFalse(42)).thenReturn(Optional.of(future));
        when(registrationRepo.findRemindableByEventId(42)).thenReturn(List.of(reg(1, "+13463705928")));

        run();

        verify(sender, never()).sendSmsWithOutcome(anyString(), anyString(), anyString());
        assertTrue(written.isEmpty(), "nothing due means nothing recorded");
    }

    @Test
    @DisplayName("the before-days trigger fires on the right day and is tagged as itself")
    void beforeDaysTrigger() {
        EventReminder r = sameDayRule();
        r.setSameDay(false);
        r.setBeforeDays(3);
        ChurchEvent inThreeDays = event();
        inThreeDays.setEventDate(EVENT_DAY.plusDays(3));
        when(reminderRepo.findByDisabledFalse()).thenReturn(List.of(r));
        when(eventRepo.findByIdAndDeleteFlagFalse(42)).thenReturn(Optional.of(inThreeDays));
        when(registrationRepo.findRemindableByEventId(42)).thenReturn(List.of(reg(1, "+13463705928")));

        run();

        assertEquals(1, smsRecipients().size());
        assertEquals("BEFORE", written.get(0).getMessageType());
        assertEquals(Integer.valueOf(3), written.get(0).getDaysBefore());
    }

    // ── the job must not take its siblings down ────────────────────────────

    @Test
    @DisplayName("a failure inside event reminders does not cancel the rest of the tick")
    void aFailingJobDoesNotCancelTheOthers() {
        // The rule lookup itself explodes — the worst case, before any recipient.
        when(reminderRepo.findByDisabledFalse())
                .thenThrow(new RuntimeException("database unavailable"));

        assertDoesNotThrow(this::run,
                "the scheduled method must absorb a sub-job failure, or every later "
                + "job in the same tick is silently cancelled");
    }

    @Test
    @DisplayName("an audit-log write failure never costs a recipient their message")
    void auditFailureDoesNotBlockSending() {
        wire(sameDayRule(), List.of(reg(1, "+13463705928"), reg(2, "+19134569296")));
        when(logRepo.save(any(EventRegistrationReminderLog.class)))
                .thenThrow(new RuntimeException("log table unavailable"));

        run();

        assertEquals(2, smsRecipients().size(),
                "recording is a diagnostic aid; it must never gate delivery");
    }
}
