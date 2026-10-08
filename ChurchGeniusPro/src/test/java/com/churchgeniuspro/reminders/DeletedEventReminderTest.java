package com.churchgeniuspro.reminders;

import com.churchgeniuspro.hibernate.ChurchEvent;
import com.churchgeniuspro.hibernate.EventRegistration;
import com.churchgeniuspro.hibernate.EventReminder;
import com.churchgeniuspro.model.EventReminderBO;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A reminder whose event was deleted on /events must disappear from the Event
 * Reminders list and never send, while reminders for live events are untouched.
 *
 * <p>Walks the reported scenario end to end through the REAL services —
 * {@link ChurchEventService#delete} (soft delete), {@link ReminderService} (the
 * list endpoint) and {@link ReminderSchedulerService} (sending) — over in-memory
 * repositories that honour {@code delete_flag} the way the derived
 * {@code ...DeleteFlagFalse} queries do.
 */
class DeletedEventReminderTest {

    private static final String CLIENT = "CHR-200";
    private static final String OTHER  = "CHR-999";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Chicago"));

    private final Map<Integer, ChurchEvent> events = new LinkedHashMap<>();
    private final List<EventReminder> reminders = new ArrayList<>();

    private ChurchEventRepository eventRepo;
    private EventReminderRepository reminderRepo;
    private EventRegistrationRepository registrationRepo;
    private WhatsAppSenderService sender;
    private EmailService emailService;

    private ReminderService reminderService;
    private ChurchEventService eventService;
    private ReminderSchedulerService scheduler;

    @BeforeEach
    void setUp() throws Exception {
        eventRepo = mock(ChurchEventRepository.class);
        when(eventRepo.save(any(ChurchEvent.class))).thenAnswer(i -> {
            ChurchEvent e = i.getArgument(0); events.put(e.getId(), e); return e; });
        when(eventRepo.findByIdAndDeleteFlagFalse(anyInt())).thenAnswer(i ->
                Optional.ofNullable(events.get((Integer) i.getArgument(0))).filter(e -> !e.isDeleteFlag()));
        when(eventRepo.findByIdAndAppClientIdAndDeleteFlagFalse(anyInt(), anyString())).thenAnswer(i ->
                Optional.ofNullable(events.get((Integer) i.getArgument(0)))
                        .filter(e -> !e.isDeleteFlag() && e.getAppClientId().equals(i.getArgument(1))));
        when(eventRepo.findByAppClientIdAndDeleteFlagFalseOrderByCreatedDateDesc(anyString())).thenAnswer(i ->
                events.values().stream()
                        .filter(e -> !e.isDeleteFlag() && e.getAppClientId().equals(i.getArgument(0)))
                        .collect(Collectors.toList()));

        reminderRepo = mock(EventReminderRepository.class);
        when(reminderRepo.save(any(EventReminder.class))).thenAnswer(i -> {
            EventReminder r = i.getArgument(0);
            if (r.getId() == null) { r.setId(reminders.size() + 1); reminders.add(r); }
            return r; });
        when(reminderRepo.findByAppClientIdOrderByCreatedDateDesc(anyString())).thenAnswer(i ->
                reminders.stream().filter(r -> r.getAppClientId().equals(i.getArgument(0)))
                        .collect(Collectors.toList()));
        when(reminderRepo.findByDisabledFalse()).thenAnswer(i ->
                reminders.stream().filter(r -> !Boolean.TRUE.equals(r.getDisabled()))
                        .collect(Collectors.toList()));

        registrationRepo = mock(EventRegistrationRepository.class);
        when(registrationRepo.findRemindableByEventId(42)).thenReturn(List.of(reg(42, "+19135550142")));
        when(registrationRepo.findRemindableByEventId(43)).thenReturn(List.of(reg(43, "+19135550143")));

        sender = mock(WhatsAppSenderService.class);
        when(sender.sendSmsWithOutcome(anyString(), anyString(), anyString()))
                .thenReturn(SmsService.SendOutcome.ok());
        emailService = mock(EmailService.class);

        AutoReminderRepository autoRepo = mock(AutoReminderRepository.class);
        when(autoRepo.findActiveClientIds()).thenReturn(List.of(CLIENT));
        ReminderSentLogRepository sentLog = mock(ReminderSentLogRepository.class);
        when(sentLog.existsByAppClientIdAndReminderTypeAndReferenceKeyAndSentDate(
                anyString(), anyString(), anyString(), any())).thenReturn(false);
        when(sentLog.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        EventRegistrationReminderLogRepository logRepo = mock(EventRegistrationReminderLogRepository.class);
        when(logRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        Map<Class<?>, Object> deps = new HashMap<>();
        deps.put(ChurchEventRepository.class, eventRepo);
        deps.put(EventReminderRepository.class, reminderRepo);
        deps.put(EventRegistrationRepository.class, registrationRepo);
        deps.put(WhatsAppSenderService.class, sender);
        deps.put(EmailService.class, emailService);
        deps.put(AutoReminderRepository.class, autoRepo);
        deps.put(ReminderSentLogRepository.class, sentLog);
        deps.put(EventRegistrationReminderLogRepository.class, logRepo);

        reminderService = build(ReminderService.class, deps);
        eventService    = build(ChurchEventService.class, deps);
        scheduler       = build(ReminderSchedulerService.class, deps);
        ReflectionTestUtils.setField(scheduler, "baseUrl", "https://example.org");
    }

    @SuppressWarnings("unchecked")
    private static <T> T build(Class<T> type, Map<Class<?>, Object> deps) throws Exception {
        Constructor<?> ctor = Arrays.stream(type.getDeclaredConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Object[] args = Arrays.stream(ctor.getParameterTypes())
                .map(t -> deps.containsKey(t) ? deps.get(t) : mock(t)).toArray();
        ctor.setAccessible(true);
        return (T) ctor.newInstance(args);
    }

    private static EventRegistration reg(int eventId, String phone) {
        EventRegistration r = new EventRegistration();
        r.setId(eventId * 10); r.setEventId(eventId); r.setPhone(phone); r.setAttending(true);
        return r;
    }

    /** Step 1 — create an event (one day, today, so a same-day reminder is due now). */
    private void createEvent(int id, String name, String client) {
        ChurchEvent e = new ChurchEvent();
        e.setId(id); e.setAppClientId(client); e.setEventName(name);
        e.setEventType("One Day"); e.setEventDate(TODAY); e.setStartTime("17:00");
        eventRepo.save(e);
    }

    /** Step 2 — configure a same-day SMS reminder through the real create path. */
    private Integer configureReminder(int eventId) {
        EventReminderBO bo = new EventReminderBO();
        bo.setEventId(eventId); bo.setSameDay(true); bo.setDisabled(false);
        bo.setSendEmail(false); bo.setSendSms(true); bo.setSendWhatsApp(false);
        return (Integer) reminderService.createEventReminder(bo, CLIENT).get("id");
    }

    private List<Integer> listedEventIds() {
        return reminderService.getAllEventReminders(CLIENT).stream()
                .map(m -> (Integer) m.get("eventId")).collect(Collectors.toList());
    }

    private List<String> smsSentTo() {
        ArgumentCaptor<String> to = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeast(0)).sendSmsWithOutcome(to.capture(), anyString(), anyString());
        return to.getAllValues();
    }

    @Test
    @DisplayName("scenario: delete an event → its reminder leaves the list and never sends; the other event still sends")
    void deletedEventScenario() {
        createEvent(42, "Fall Picnic", CLIENT);                 // 1
        createEvent(43, "Musical Night", CLIENT);                // (an active event that must keep working)
        Integer r42 = configureReminder(42);                     // 2
        Integer r43 = configureReminder(43);

        assertEquals(List.of(42, 43), listedEventIds(), "3: both reminders are listed while both events exist");
        Map<String, Object> listed42 = reminderService.getAllEventReminders(CLIENT).get(0);
        assertEquals("Fall Picnic", listed42.get("eventName"));

        eventService.delete(42, CLIENT);                         // 4 — the real /events delete
        assertTrue(events.get(42).isDeleteFlag(), "event delete is a soft delete");

        assertEquals(List.of(43), listedEventIds(), "5: the deleted event's reminder is no longer listed");

        scheduler.runHalfHourlyReminders();                      // 6 + 7
        List<String> sent = smsSentTo();
        assertFalse(sent.contains("+19135550142"), "6: nobody is reminded about the deleted event");
        assertEquals(List.of("+19135550143"), sent, "7: the active event's reminder still sends, exactly once");

        // The deleted event's reminder row is left exactly as it was (ignored, not erased or rewritten).
        EventReminder kept = reminders.stream().filter(r -> r.getId().equals(r42)).findFirst().orElseThrow();
        assertEquals(42, kept.getEventId());
        assertFalse(kept.getDisabled(), "the stored reminder is not modified");
        assertTrue(reminders.stream().anyMatch(r -> r.getId().equals(r43)));
        assertEquals(2, reminders.size(), "no reminder row was deleted");
        verify(reminderRepo, never()).delete(any());
        verify(reminderRepo, times(2)).save(any());   // only the two creates
    }

    @Test
    @DisplayName("reminders already pointing at deleted events (like #17 / #18) are hidden; live ones and their data are unchanged")
    void preExistingOrphansHidden() {
        createEvent(43, "Musical Night", CLIENT);
        Integer live = configureReminder(43);
        // Rows that exist today for events deleted before this fix.
        for (int orphanEventId : new int[]{17, 18}) {
            createEvent(orphanEventId, "Old event " + orphanEventId, CLIENT);
            configureReminder(orphanEventId);
            events.get(orphanEventId).setDeleteFlag(true);
        }
        List<Map<String, Object>> listed = reminderService.getAllEventReminders(CLIENT);
        assertEquals(1, listed.size());
        assertEquals(live, listed.get(0).get("id"));
        assertEquals("Musical Night", listed.get(0).get("eventName"));
        assertEquals(Boolean.TRUE, listed.get(0).get("sameDay"), "live reminder's fields come through unchanged");
    }

    @Test
    @DisplayName("a reminder pointing at another church's event id is not listed")
    void foreignEventNotListed() {
        createEvent(43, "Musical Night", CLIENT);
        configureReminder(43);
        createEvent(77, "Someone else's event", OTHER);
        EventReminder stray = new EventReminder();
        stray.setAppClientId(CLIENT); stray.setEventId(77); stray.setSameDay(true); stray.setDisabled(false);
        reminderRepo.save(stray);
        assertEquals(List.of(43), listedEventIds());
    }

    @Test
    @DisplayName("a reminder with no specific event is still listed")
    void noEventReminderStillListed() {
        EventReminder all = new EventReminder();
        all.setAppClientId(CLIENT); all.setEventId(null); all.setSameDay(true); all.setDisabled(false);
        reminderRepo.save(all);
        List<Map<String, Object>> listed = reminderService.getAllEventReminders(CLIENT);
        assertEquals(1, listed.size());
        assertNull(listed.get(0).get("eventId"));
    }
}
