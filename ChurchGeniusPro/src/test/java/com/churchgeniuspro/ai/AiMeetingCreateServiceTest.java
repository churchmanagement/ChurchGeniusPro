package com.churchgeniuspro.ai;

import com.churchgeniuspro.hibernate.Meeting;
import com.churchgeniuspro.hibernate.MeetingType;
import com.churchgeniuspro.model.MeetingBO;
import com.churchgeniuspro.repository.MeetingRepository;
import com.churchgeniuspro.repository.MeetingTypeRepository;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.AiMeetingCreateService;
import com.churchgeniuspro.service.MeetingService;
import com.churchgeniuspro.util.AppClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI Search Phase E — "Create a meeting": authorization before anything else, the Meetings page's
 * required fields (category + date) asked for when missing, one-time only, nothing written until
 * the user confirms, the confirmation token single-use, duplicates reported instead of created.
 */
@DisplayName("AI Search — meeting creation (Phase E)")
class AiMeetingCreateServiceTest {

    static final String MINE = "CHR-MINE";

    MeetingTypeRepository typeRepo; MeetingRepository meetingRepo; MeetingService meetingService;
    AiMeetingCreateService svc;
    MeetingType bible, prayer, youth;

    @BeforeEach
    void setUp() {
        typeRepo = mock(MeetingTypeRepository.class); meetingRepo = mock(MeetingRepository.class); meetingService = mock(MeetingService.class);
        bible = type(1, "Bible Study"); prayer = type(2, "Prayer Meeting"); youth = type(3, "Youth Bible Study");
        when(typeRepo.findByAppClientIdAndDeleteFlagFalseOrderByTypeNameAsc(MINE)).thenReturn(List.of(bible, prayer, youth));
        when(typeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(anyInt(), eq(MINE))).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            return List.of(bible, prayer, youth).stream().filter(t -> t.getId() == id).findFirst();
        });
        when(meetingRepo.aiCountSameSlot(anyString(), anyInt(), any(), anyString())).thenReturn(0L);
        when(meetingService.save(any(), anyString())).thenAnswer(inv -> { Meeting m = new Meeting(); m.setId(99); return m; });
        svc = new AiMeetingCreateService(typeRepo, meetingRepo, meetingService, new AiAccessPolicy());
    }

    static MeetingType type(int id, String name) { MeetingType t = new MeetingType(); t.setId(id); t.setTypeName(name); t.setAppClientId(MINE); return t; }

    static MockHttpServletRequest staff(String role, String privs) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "staff"); s.setAttribute("role", role); s.setAttribute("appClientId", MINE); s.setAttribute("clientId", MINE);
        if (privs != null) s.setAttribute("privileges", privs);
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
    }
    static MockHttpServletRequest same(MockHttpServletRequest prev) { MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(prev.getSession()); return r; }
    static MockHttpServletRequest member() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "mbr"); s.setAttribute("role", "Member"); s.setAttribute("memberId", 7); s.setAttribute("appClientId", MINE);
        s.setAttribute("memberPrivileges", "{\"member.directory\":true}");
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
    }
    static MockHttpServletRequest kids() {
        MockHttpServletRequest r = member(); r.getSession().setAttribute("memberRole", "Child"); return r;
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    @Test @DisplayName("only an instruction to create is a create request — questions and how-tos are not")
    void detection() {
        assertThat(AiMeetingCreateService.isCreateRequest("Create a meeting for Bible Study on Nov 3 at 7pm")).isTrue();
        assertThat(AiMeetingCreateService.isCreateRequest("Schedule a Prayer Meeting tomorrow at 6:30 pm")).isTrue();
        assertThat(AiMeetingCreateService.isCreateRequest("I need to set up a new meeting")).isTrue();
        assertThat(AiMeetingCreateService.isCreateRequest("How do I create a meeting?")).isFalse();
        assertThat(AiMeetingCreateService.isCreateRequest("What meetings are coming up?")).isFalse();
        assertThat(AiMeetingCreateService.isCreateRequest("Show me the meetings this week")).isFalse();
        assertThat(AiMeetingCreateService.isCreateRequest("Go to the meetings page")).isFalse();
        assertThat(svc.handle(staff("Admin", null), MINE, "What was the income last month?")).isNull();
    }

    // ── Authorization ─────────────────────────────────────────────────────────

    @Test @DisplayName("Member portal, Kids Portal and a staff login without general.meetings.edit are denied before any lookup")
    void authorization() {
        for (MockHttpServletRequest r : List.of(member(), kids(), staff("User", "{\"general.meetings.edit\":false}"),
                                                 staff("User", "{\"general.meetings\":false}"))) {
            Map<String, Object> m = svc.handle(r, MINE, "Create a meeting for Bible Study on Nov 3 at 7pm");
            assertThat(m).isNotNull();
            assertThat(m.get("denied")).isEqualTo(true);
            assertThat(m.get("intent")).isEqualTo("meeting.denied");
            assertThat(m).doesNotContainKey("confirm");
        }
        verify(typeRepo, never()).findByAppClientIdAndDeleteFlagFalseOrderByTypeNameAsc(anyString());
        verify(meetingService, never()).save(any(), anyString());
    }

    @Test @DisplayName("a church login (no module data) is denied too")
    void churchDenied() {
        MockHttpServletRequest r = staff("Church", null); r.getSession().setAttribute("church", true);
        Map<String, Object> m = svc.handle(r, MINE, "Create a Bible Study meeting tomorrow at 7pm");
        assertThat(m.get("denied")).isEqualTo(true);
    }

    // ── Parse → confirm → create ──────────────────────────────────────────────

    @Test @DisplayName("a complete request is parsed, shown back for confirmation and NOT created until confirmed")
    void confirmBeforeCreate() {
        MockHttpServletRequest r = staff("Admin", null);
        LocalDate today = AppClock.today();
        Map<String, Object> m = svc.handle(r, MINE, "Create a meeting for Bible Study tomorrow at 7pm about the retreat");
        assertThat(m.get("intent")).isEqualTo("meeting.confirm");
        @SuppressWarnings("unchecked") Map<String, Object> c = (Map<String, Object>) m.get("confirm");
        assertThat(c.get("token")).isNotNull();
        @SuppressWarnings("unchecked") Map<String, String> sum = (Map<String, String>) c.get("summary");
        assertThat(sum.get("Category")).isEqualTo("Bible Study");
        assertThat(sum.get("Date")).isEqualTo(AiMeetingCreateService_fmt(today.plusDays(1)));
        assertThat(sum.get("Time")).isEqualTo("7:00 PM");
        assertThat(sum.get("Repeats")).isEqualTo("One-time");
        assertThat(sum.get("Note")).isEqualTo("the retreat");
        verify(meetingService, never()).save(any(), anyString());

        Map<String, Object> done = svc.confirm(same(r), MINE, String.valueOf(c.get("token")));
        assertThat(done.get("intent")).isEqualTo("meeting.created");
        assertThat(done.get("created")).isEqualTo(true);
        ArgumentCaptor<MeetingBO> bo = ArgumentCaptor.forClass(MeetingBO.class);
        verify(meetingService, times(1)).save(bo.capture(), eq(MINE));
        assertThat(bo.getValue().getMeetingTypeId()).isEqualTo(1);
        assertThat(bo.getValue().getMeetingDate()).isEqualTo(today.plusDays(1).toString());
        assertThat(bo.getValue().getStartTime()).isEqualTo("19:00");
        assertThat(bo.getValue().getEndTime()).isNull();
        assertThat(bo.getValue().getOccurrence()).isEqualTo("One-time");
        assertThat(bo.getValue().getNote()).isEqualTo("the retreat");
        assertThat(bo.getValue().getWeekDays()).isNull();
    }

    static String AiMeetingCreateService_fmt(LocalDate d) {
        return d.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", java.util.Locale.ENGLISH));
    }

    @Test @DisplayName("the confirmation token is single-use: a second click (or a repeated \"yes\") creates nothing")
    void tokenSingleUse() {
        MockHttpServletRequest r = staff("Admin", null);
        Map<String, Object> m = svc.handle(r, MINE, "Schedule a Prayer Meeting on 2026-12-01 from 6 to 7:30pm");
        @SuppressWarnings("unchecked") String token = String.valueOf(((Map<String, Object>) m.get("confirm")).get("token"));
        assertThat(svc.confirm(same(r), MINE, token).get("intent")).isEqualTo("meeting.created");
        assertThat(svc.confirm(same(r), MINE, token).get("intent")).isEqualTo("meeting.expired");
        assertThat(svc.handle(same(r), MINE, "yes")).isNull();           // no draft left — "yes" means nothing now
        verify(meetingService, times(1)).save(any(), eq(MINE));
        ArgumentCaptor<MeetingBO> bo = ArgumentCaptor.forClass(MeetingBO.class);
        verify(meetingService).save(bo.capture(), eq(MINE));
        assertThat(bo.getValue().getStartTime()).isEqualTo("18:00");
        assertThat(bo.getValue().getEndTime()).isEqualTo("19:30");
        assertThat(bo.getValue().getMeetingDate()).isEqualTo("2026-12-01");
    }

    @Test @DisplayName("a wrong, foreign or missing token creates nothing; the token is bound to the session that received it")
    void tokenBoundToSession() {
        MockHttpServletRequest r = staff("Admin", null);
        svc.handle(r, MINE, "Create a Bible Study meeting tomorrow at 7pm");
        assertThat(svc.confirm(same(r), MINE, "not-the-token").get("intent")).isEqualTo("meeting.expired");
        assertThat(svc.confirm(same(r), MINE, null).get("intent")).isEqualTo("meeting.expired");
        assertThat(svc.confirm(staff("Admin", null), MINE, "anything").get("intent")).isEqualTo("meeting.expired");   // another session
        verify(meetingService, never()).save(any(), anyString());
    }

    @Test @DisplayName("spoken confirmation: \"yes\" creates, \"no\" discards")
    void spokenYesNo() {
        MockHttpServletRequest r = staff("Admin", null);
        svc.handle(r, MINE, "Create a Bible Study meeting tomorrow at 7pm");
        assertThat(svc.handle(same(r), MINE, "Yes").get("intent")).isEqualTo("meeting.created");
        verify(meetingService, times(1)).save(any(), eq(MINE));

        MockHttpServletRequest r2 = staff("Admin", null);
        svc.handle(r2, MINE, "Create a Bible Study meeting tomorrow at 7pm");
        assertThat(svc.handle(same(r2), MINE, "no, cancel").get("intent")).isEqualTo("meeting.cancelled");
        assertThat(svc.handle(same(r2), MINE, "yes")).isNull();
        verify(meetingService, times(1)).save(any(), eq(MINE));
    }

    @Test @DisplayName("an identical meeting (type + date + start) already on the calendar is reported, not duplicated")
    void duplicateDetected() {
        when(meetingRepo.aiCountSameSlot(eq(MINE), eq(1), any(), eq("19:00"))).thenReturn(1L);
        MockHttpServletRequest r = staff("Admin", null);
        svc.handle(r, MINE, "Create a Bible Study meeting tomorrow at 7pm");
        Map<String, Object> m = svc.handle(same(r), MINE, "yes");
        assertThat(m.get("intent")).isEqualTo("meeting.duplicate");
        verify(meetingService, never()).save(any(), anyString());
    }

    // ── Required fields ───────────────────────────────────────────────────────

    @Test @DisplayName("missing category → asks and lists this church's categories; the reply completes the draft")
    void asksForCategory() {
        MockHttpServletRequest r = staff("Admin", null);
        Map<String, Object> m = svc.handle(r, MINE, "Create a meeting tomorrow at 7pm");
        assertThat(m.get("intent")).isEqualTo("meeting.ask");
        assertThat(m.get("missing")).isEqualTo(List.of("category"));
        assertThat(String.valueOf(m.get("answer"))).contains("Bible Study", "Prayer Meeting", "Youth Bible Study");
        assertThat(m).doesNotContainKey("confirm");

        Map<String, Object> m2 = svc.handle(same(r), MINE, "Prayer Meeting");
        assertThat(m2.get("intent")).isEqualTo("meeting.confirm");
        @SuppressWarnings("unchecked") Map<String, String> sum = (Map<String, String>) ((Map<String, Object>) m2.get("confirm")).get("summary");
        assertThat(sum.get("Category")).isEqualTo("Prayer Meeting");
        verify(meetingService, never()).save(any(), anyString());
    }

    @Test @DisplayName("missing date → asks; an unrelated follow-up question drops the draft instead of hijacking it")
    void asksForDate() {
        MockHttpServletRequest r = staff("Admin", null);
        Map<String, Object> m = svc.handle(r, MINE, "Create a Bible Study meeting at 7pm");
        assertThat(m.get("intent")).isEqualTo("meeting.ask");
        assertThat(m.get("missing")).isEqualTo(List.of("date"));
        assertThat(svc.handle(same(r), MINE, "what was the income last month")).isNull();   // moved on
        assertThat(svc.handle(same(r), MINE, "November 3")).isNull();                        // draft is gone
    }

    @Test @DisplayName("date reply completes the draft: \"next Monday\" resolves on the church calendar")
    void dateReply() {
        MockHttpServletRequest r = staff("Admin", null);
        svc.handle(r, MINE, "Create a Bible Study meeting at 7pm");
        Map<String, Object> m = svc.handle(same(r), MINE, "next Monday");
        assertThat(m.get("intent")).isEqualTo("meeting.confirm");
        LocalDate today = AppClock.today();
        LocalDate expected = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY));
        if (expected.equals(today)) expected = expected.plusWeeks(1);
        @SuppressWarnings("unchecked") Map<String, String> sum = (Map<String, String>) ((Map<String, Object>) m.get("confirm")).get("summary");
        assertThat(sum.get("Date")).isEqualTo(AiMeetingCreateService_fmt(expected));
    }

    @Test @DisplayName("an hour without AM/PM is not guessed — it is asked, and \"pm\" resolves it")
    void ambiguousHour() {
        MockHttpServletRequest r = staff("Admin", null);
        Map<String, Object> m = svc.handle(r, MINE, "Create a Bible Study meeting tomorrow at 7");
        assertThat(m.get("intent")).isEqualTo("meeting.ask");
        assertThat(m.get("missing")).isEqualTo(List.of("time"));
        Map<String, Object> m2 = svc.handle(same(r), MINE, "pm");
        assertThat(m2.get("intent")).isEqualTo("meeting.confirm");
        @SuppressWarnings("unchecked") Map<String, String> sum = (Map<String, String>) ((Map<String, Object>) m2.get("confirm")).get("summary");
        assertThat(sum.get("Time")).isEqualTo("7:00 PM");
    }

    @Test @DisplayName("an ambiguous category (\"Bible Study\" vs \"Youth Bible Study\") picks the exact longer match only when the text says so")
    void categoryMatching() {
        MockHttpServletRequest r = staff("Admin", null);
        Map<String, Object> m = svc.handle(r, MINE, "Create a Youth Bible Study meeting tomorrow at 5pm");
        @SuppressWarnings("unchecked") Map<String, String> sum = (Map<String, String>) ((Map<String, Object>) m.get("confirm")).get("summary");
        assertThat(sum.get("Category")).isEqualTo("Youth Bible Study");
        Map<String, Object> m2 = svc.handle(staff("Admin", null), MINE, "Create a bible study meeting tomorrow at 5pm");
        @SuppressWarnings("unchecked") Map<String, String> sum2 = (Map<String, String>) ((Map<String, Object>) m2.get("confirm")).get("summary");
        assertThat(sum2.get("Category")).isEqualTo("Bible Study");
    }

    @Test @DisplayName("no time given → the meeting is confirmed and created without a time (the page's rule)")
    void timeOptional() {
        MockHttpServletRequest r = staff("Admin", null);
        Map<String, Object> m = svc.handle(r, MINE, "Create a Bible Study meeting on December 5");
        assertThat(m.get("intent")).isEqualTo("meeting.confirm");
        @SuppressWarnings("unchecked") Map<String, String> sum = (Map<String, String>) ((Map<String, Object>) m.get("confirm")).get("summary");
        assertThat(sum.get("Time")).isEqualTo("not set");
        svc.handle(same(r), MINE, "yes");
        ArgumentCaptor<MeetingBO> bo = ArgumentCaptor.forClass(MeetingBO.class);
        verify(meetingService).save(bo.capture(), eq(MINE));
        assertThat(bo.getValue().getStartTime()).isNull();
        assertThat(bo.getValue().getMeetingDate()).endsWith("-12-05");
    }

    // ── One-time only ─────────────────────────────────────────────────────────

    @Test @DisplayName("recurring wording is refused — only one-time meetings are created here")
    void recurringRefused() {
        Map<String, Object> m = svc.handle(staff("Admin", null), MINE, "Create a Bible Study meeting every Tuesday at 7pm");
        assertThat(m.get("intent")).isEqualTo("meeting.unsupported");
        assertThat(m).doesNotContainKey("confirm");
        verify(meetingService, never()).save(any(), anyString());
    }

    @Test @DisplayName("no categories configured → told to add one first, nothing created")
    void noCategories() {
        when(typeRepo.findByAppClientIdAndDeleteFlagFalseOrderByTypeNameAsc(MINE)).thenReturn(List.of());
        Map<String, Object> m = svc.handle(staff("Admin", null), MINE, "Create a meeting tomorrow at 7pm");
        assertThat(m.get("intent")).isEqualTo("meeting.ask");
        assertThat(String.valueOf(m.get("answer"))).contains("Meeting Types");
        verify(meetingService, never()).save(any(), anyString());
    }

    @Test @DisplayName("the category is re-checked at creation time — a deleted category creates nothing")
    void typeDeletedBeforeConfirm() {
        MockHttpServletRequest r = staff("Admin", null);
        svc.handle(r, MINE, "Create a Bible Study meeting tomorrow at 7pm");
        when(typeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(1, MINE)).thenReturn(Optional.empty());
        assertThat(svc.handle(same(r), MINE, "yes").get("intent")).isEqualTo("meeting.error");
        verify(meetingService, never()).save(any(), anyString());
    }

    @Test @DisplayName("permission revoked between the question and the confirmation → denied, nothing created")
    void permissionRevokedBeforeConfirm() {
        MockHttpServletRequest r = staff("Admin", null);
        Map<String, Object> m = svc.handle(r, MINE, "Create a Bible Study meeting tomorrow at 7pm");
        @SuppressWarnings("unchecked") String token = String.valueOf(((Map<String, Object>) m.get("confirm")).get("token"));
        r.getSession().setAttribute("privileges", "{\"general.meetings.edit\":false}");
        assertThat(svc.confirm(same(r), MINE, token).get("denied")).isEqualTo(true);
        verify(meetingService, never()).save(any(), anyString());
    }
}
