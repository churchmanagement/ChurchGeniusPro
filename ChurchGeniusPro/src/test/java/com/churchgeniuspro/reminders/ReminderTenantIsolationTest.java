package com.churchgeniuspro.reminders;

import com.churchgeniuspro.controller.ReminderApiController;
import com.churchgeniuspro.hibernate.AutoReminder;
import com.churchgeniuspro.hibernate.ChurchEvent;
import com.churchgeniuspro.hibernate.EventReminder;
import com.churchgeniuspro.hibernate.Meeting;
import com.churchgeniuspro.hibernate.MeetingReminder;
import com.churchgeniuspro.hibernate.OneTimeReminder;
import com.churchgeniuspro.model.AutoReminderBO;
import com.churchgeniuspro.model.EventReminderBO;
import com.churchgeniuspro.model.MeetingReminderBO;
import com.churchgeniuspro.model.OneTimeReminderBO;
import com.churchgeniuspro.repository.AutoReminderRepository;
import com.churchgeniuspro.repository.AutoReminderTypesRepository;
import com.churchgeniuspro.repository.ChurchEventRepository;
import com.churchgeniuspro.repository.EventReminderRepository;
import com.churchgeniuspro.repository.MeetingReminderRepository;
import com.churchgeniuspro.repository.MeetingRepository;
import com.churchgeniuspro.repository.OneTimeReminderRepository;
import com.churchgeniuspro.service.ReminderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reminder CRUD is tenant-bound.
 *
 * <p>Before: every {@code PUT/DELETE /api/reminders/*&#47;{id}} loaded by bare id with no
 * session at all, and {@code eventId} / {@code meetingId} in the body were stored
 * unchecked, so a reminder could be pointed at (and mail out) another church's event.
 * Uses the real service over mocked repositories.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Reminders — tenant isolation")
class ReminderTenantIsolationTest {

    private static final String OURS   = "CHR-ours";
    private static final String THEIRS = "CHR-theirs";

    @Mock EventReminderRepository     eventReminderRepo;
    @Mock AutoReminderRepository      autoReminderRepo;
    @Mock AutoReminderTypesRepository autoTypesRepo;
    @Mock OneTimeReminderRepository   oneTimeReminderRepo;
    @Mock MeetingReminderRepository   meetingReminderRepo;
    @Mock ChurchEventRepository       churchEventRepo;
    @Mock MeetingRepository           meetingRepo;

    ReminderApiController controller;

    @BeforeEach
    void setUp() {
        controller = new ReminderApiController(new ReminderService(eventReminderRepo, autoReminderRepo,
                autoTypesRepo, oneTimeReminderRepo, meetingReminderRepo, churchEventRepo, meetingRepo));
        when(eventReminderRepo.save(any(EventReminder.class))).thenAnswer(i -> i.getArgument(0));
        when(autoReminderRepo.save(any(AutoReminder.class))).thenAnswer(i -> i.getArgument(0));
        when(oneTimeReminderRepo.save(any(OneTimeReminder.class))).thenAnswer(i -> i.getArgument(0));
        when(meetingReminderRepo.save(any(MeetingReminder.class))).thenAnswer(i -> i.getArgument(0));
        // id 9 of every kind belongs to THEIRS: the scoped finder sees nothing for OURS.
        when(eventReminderRepo.findByIdAndAppClientId(eq(9), anyString())).thenReturn(Optional.empty());
        when(autoReminderRepo.findByIdAndAppClientId(eq(9), anyString())).thenReturn(Optional.empty());
        when(oneTimeReminderRepo.findByIdAndAppClientId(eq(9), anyString())).thenReturn(Optional.empty());
        when(meetingReminderRepo.findByIdAndAppClientId(eq(9), anyString())).thenReturn(Optional.empty());
        // Our own rows.
        when(eventReminderRepo.findByIdAndAppClientId(7, OURS)).thenReturn(Optional.of(eventReminder(7, OURS)));
        when(autoReminderRepo.findByIdAndAppClientId(7, OURS)).thenReturn(Optional.of(autoReminder(7, OURS)));
        when(oneTimeReminderRepo.findByIdAndAppClientId(7, OURS)).thenReturn(Optional.of(oneTimeReminder(7, OURS)));
        when(meetingReminderRepo.findByIdAndAppClientId(7, OURS)).thenReturn(Optional.of(meetingReminder(7, OURS)));
        // Event 100 / meeting 200 are ours; 101 / 201 belong to THEIRS.
        when(churchEventRepo.findByIdAndAppClientIdAndDeleteFlagFalse(100, OURS)).thenReturn(Optional.of(event(100, OURS)));
        when(churchEventRepo.findByIdAndAppClientIdAndDeleteFlagFalse(eq(101), anyString())).thenReturn(Optional.empty());
        when(churchEventRepo.findByIdAndDeleteFlagFalse(101)).thenReturn(Optional.of(event(101, THEIRS)));
        when(meetingRepo.findByIdAndAppClientIdAndDeleteFlagFalse(200, OURS)).thenReturn(Optional.of(meeting(200, OURS)));
        when(meetingRepo.findByIdAndAppClientIdAndDeleteFlagFalse(eq(201), anyString())).thenReturn(Optional.empty());
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private MockHttpServletRequest staffOf(String clientId, String role) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("clientId", clientId);
        s.setAttribute("appClientId", clientId);
        s.setAttribute("username", "staff@" + clientId);
        s.setAttribute("role", role);
        req.setSession(s);
        return req;
    }

    private static EventReminder eventReminder(int id, String cid) {
        EventReminder r = new EventReminder(); r.setId(id); r.setAppClientId(cid); r.setEventId(100); return r;
    }
    private static AutoReminder autoReminder(int id, String cid) {
        AutoReminder r = new AutoReminder(); r.setId(id); r.setAppClientId(cid); r.setName("Birthdays"); return r;
    }
    private static OneTimeReminder oneTimeReminder(int id, String cid) {
        OneTimeReminder r = new OneTimeReminder(); r.setId(id); r.setAppClientId(cid); r.setName("Picnic"); return r;
    }
    private static MeetingReminder meetingReminder(int id, String cid) {
        MeetingReminder r = new MeetingReminder(); r.setId(id); r.setAppClientId(cid); r.setMeetingId(200); return r;
    }
    private static ChurchEvent event(int id, String cid) {
        ChurchEvent e = new ChurchEvent(); e.setId(id); e.setAppClientId(cid); e.setEventName("Event " + id); return e;
    }
    private static Meeting meeting(int id, String cid) {
        Meeting m = new Meeting(); m.setId(id); m.setAppClientId(cid); return m;
    }
    private static EventReminderBO eventBo(Integer eventId) {
        EventReminderBO bo = new EventReminderBO(); bo.setEventId(eventId); bo.setRecipients("Members"); return bo;
    }
    private static MeetingReminderBO meetingBo(Integer meetingId) {
        MeetingReminderBO bo = new MeetingReminderBO(); bo.setMeetingId(meetingId); bo.setRecipients("Members"); return bo;
    }
    private static AutoReminderBO autoBo() {
        AutoReminderBO bo = new AutoReminderBO(); bo.setName("Birthdays"); bo.setReminderTypeId(1); return bo;
    }
    private static OneTimeReminderBO oneTimeBo() {
        OneTimeReminderBO bo = new OneTimeReminderBO(); bo.setName("Picnic"); bo.setEventDate("2026-10-01"); return bo;
    }

    // ── event reminders ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Another church's event reminder cannot be edited or deleted")
    void eventReminderMutatorsAreTenantScoped() {
        MockHttpServletRequest req = staffOf(OURS, "Admin");
        assertThat(controller.updateEventReminder(9, eventBo(100), req).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.deleteEventReminder(9, req).getStatusCode().value()).isEqualTo(400);
        verify(eventReminderRepo, never()).save(any(EventReminder.class));
        verify(eventReminderRepo, never()).delete(any(EventReminder.class));
        verify(eventReminderRepo, never()).deleteById(anyInt());
        verify(eventReminderRepo, never()).findById(anyInt());
    }

    @Test
    @DisplayName("An eventId belonging to another church is refused on create and update")
    void foreignEventIdIsRejected() {
        MockHttpServletRequest req = staffOf(OURS, "Admin");
        assertThat(controller.createEventReminder(eventBo(101), req).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.updateEventReminder(7, eventBo(101), req).getStatusCode().value()).isEqualTo(400);
        verify(eventReminderRepo, never()).save(any(EventReminder.class));
        verify(churchEventRepo, never()).findByIdAndDeleteFlagFalse(anyInt());
    }

    @Test
    @DisplayName("Our own event reminder still saves, and the event name resolves within our tenant")
    void ownEventReminderStillWorks() {
        MockHttpServletRequest req = staffOf(OURS, "User");
        ResponseEntity<Map<String, Object>> created = controller.createEventReminder(eventBo(100), req);
        assertThat(created.getStatusCode().is2xxSuccessful()).as("%s", created.getBody()).isTrue();
        assertThat(created.getBody().get("eventName")).isEqualTo("Event 100");
        assertThat(controller.updateEventReminder(7, eventBo(100), req).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(controller.deleteEventReminder(7, req).getStatusCode().is2xxSuccessful()).isTrue();
        verify(eventReminderRepo).delete(any(EventReminder.class));
    }

    // ── auto / one-time reminders ───────────────────────────────────────────

    @Test
    @DisplayName("Auto and one-time reminders are edited and deleted only within the session's church")
    void autoAndOneTimeMutatorsAreTenantScoped() {
        MockHttpServletRequest req = staffOf(OURS, "Admin");
        assertThat(controller.updateAutoReminder(9, autoBo(), req).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.deleteAutoReminder(9, req).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.updateOneTimeReminder(9, oneTimeBo(), req).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.deleteOneTimeReminder(9, req).getStatusCode().value()).isEqualTo(400);
        verify(autoReminderRepo, never()).save(any(AutoReminder.class));
        verify(autoReminderRepo, never()).delete(any(AutoReminder.class));
        verify(oneTimeReminderRepo, never()).save(any(OneTimeReminder.class));
        verify(oneTimeReminderRepo, never()).delete(any(OneTimeReminder.class));

        // positive
        assertThat(controller.updateAutoReminder(7, autoBo(), req).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(controller.deleteAutoReminder(7, req).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(controller.updateOneTimeReminder(7, oneTimeBo(), req).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(controller.deleteOneTimeReminder(7, req).getStatusCode().is2xxSuccessful()).isTrue();
        verify(autoReminderRepo).delete(any(AutoReminder.class));
        verify(oneTimeReminderRepo).delete(any(OneTimeReminder.class));
    }

    // ── meeting reminders ───────────────────────────────────────────────────

    @Test
    @DisplayName("Meeting reminders: foreign row and foreign meetingId are both refused")
    void meetingReminderIsTenantScoped() {
        MockHttpServletRequest req = staffOf(OURS, "Admin");
        assertThat(controller.updateMeetingReminder(9, meetingBo(200), req).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.deleteMeetingReminder(9, req).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.createMeetingReminder(meetingBo(201), req).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.updateMeetingReminder(7, meetingBo(201), req).getStatusCode().value()).isEqualTo(400);
        verify(meetingReminderRepo, never()).save(any(MeetingReminder.class));
        verify(meetingReminderRepo, never()).delete(any(MeetingReminder.class));

        // positive
        assertThat(controller.createMeetingReminder(meetingBo(200), req).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(controller.updateMeetingReminder(7, meetingBo(200), req).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(controller.deleteMeetingReminder(7, req).getStatusCode().is2xxSuccessful()).isTrue();
        verify(meetingReminderRepo).delete(any(MeetingReminder.class));
    }

    // ── role guard ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Reminder mutators carry the reminders page guard (AdminOrUser)")
    void mutatorsUseThePageRoleGuard() {
        MockHttpServletRequest accountant = staffOf(OURS, "Accountant");
        assertThat(controller.createEventReminder(eventBo(100), accountant).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.updateAutoReminder(7, autoBo(), accountant).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.deleteOneTimeReminder(7, accountant).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.deleteMeetingReminder(7, accountant).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.deleteEventReminder(7, new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(403);
        verify(eventReminderRepo, never()).save(any(EventReminder.class));
        verify(autoReminderRepo, never()).save(any(AutoReminder.class));
        verify(oneTimeReminderRepo, never()).delete(any(OneTimeReminder.class));
        verify(meetingReminderRepo, never()).delete(any(MeetingReminder.class));
    }
}
