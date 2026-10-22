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
    com.churchgeniuspro.repository.FamilyMemberRepository memberRepo;
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
        memberRepo = mock(com.churchgeniuspro.repository.FamilyMemberRepository.class);
        when(memberRepo.findAllMembersWithOwnAddressByAppUser(MINE)).thenReturn(List.of(
                home(21, "Mark", "Brooks", "12 Oak St", "Dallas"), home(22, "Mark", "Lee", "9 Elm Ave", "Plano"),
                home(23, "Hanna", "Thomas", "5 Pine Rd", "Frisco")));
        svc = new AiMeetingCreateService(typeRepo, meetingRepo, meetingService, new AiAccessPolicy(), memberRepo);
    }

    static com.churchgeniuspro.hibernate.FamilyMember home(int id, String first, String last, String addr, String city) {
        com.churchgeniuspro.hibernate.FamilyMember m = new com.churchgeniuspro.hibernate.FamilyMember();
        m.setId(id); m.setFirstName(first); m.setLastName(last); m.setAddress1(addr); m.setCity(city); m.setState("44"); m.setCountry("USA"); m.setPinCode("75001");
        return m;
    }
    @SuppressWarnings("unchecked") static Map<String, String> summary(Map<String, Object> m) {
        return (Map<String, String>) ((Map<String, Object>) m.get("confirm")).get("summary");
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
        Map<String, Object> m = svc.handle(r, MINE, "Create a Bible Study meeting tomorrow at 7pm in the fellowship hall");
        assertThat(m.get("denied")).isEqualTo(true);
    }

    // ── Parse → confirm → create ──────────────────────────────────────────────

    @Test @DisplayName("a complete request is parsed, shown back for confirmation and NOT created until confirmed")
    void confirmBeforeCreate() {
        MockHttpServletRequest r = staff("Admin", null);
        LocalDate today = AppClock.today();
        Map<String, Object> m = svc.handle(r, MINE, "Create a meeting for Bible Study tomorrow at 7pm in Room 2 about the retreat");
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
        Map<String, Object> m = svc.handle(r, MINE, "Schedule a Prayer Meeting on 2026-12-01 from 6 to 7:30pm at the church");
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
        svc.handle(r, MINE, "Create a Bible Study meeting tomorrow at 7pm in the fellowship hall");
        assertThat(svc.confirm(same(r), MINE, "not-the-token").get("intent")).isEqualTo("meeting.expired");
        assertThat(svc.confirm(same(r), MINE, null).get("intent")).isEqualTo("meeting.expired");
        assertThat(svc.confirm(staff("Admin", null), MINE, "anything").get("intent")).isEqualTo("meeting.expired");   // another session
        verify(meetingService, never()).save(any(), anyString());
    }

    @Test @DisplayName("spoken confirmation: \"yes\" creates, \"no\" discards")
    void spokenYesNo() {
        MockHttpServletRequest r = staff("Admin", null);
        svc.handle(r, MINE, "Create a Bible Study meeting tomorrow at 7pm in the fellowship hall");
        assertThat(svc.handle(same(r), MINE, "Yes").get("intent")).isEqualTo("meeting.created");
        verify(meetingService, times(1)).save(any(), eq(MINE));

        MockHttpServletRequest r2 = staff("Admin", null);
        svc.handle(r2, MINE, "Create a Bible Study meeting tomorrow at 7pm in the fellowship hall");
        assertThat(svc.handle(same(r2), MINE, "no, cancel").get("intent")).isEqualTo("meeting.cancelled");
        assertThat(svc.handle(same(r2), MINE, "yes")).isNull();
        verify(meetingService, times(1)).save(any(), eq(MINE));
    }

    @Test @DisplayName("an identical meeting (type + date + start) already on the calendar is reported, not duplicated")
    void duplicateDetected() {
        when(meetingRepo.aiCountSameSlot(eq(MINE), eq(1), any(), eq("19:00"))).thenReturn(1L);
        MockHttpServletRequest r = staff("Admin", null);
        svc.handle(r, MINE, "Create a Bible Study meeting tomorrow at 7pm in the fellowship hall");
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
        assertThat(m.get("missing")).isEqualTo(List.of("category", "location"));   // date and time were given
        assertThat(m.get("asking")).isEqualTo("category");
        assertThat(String.valueOf(m.get("answer"))).contains("Bible Study", "Prayer Meeting", "Youth Bible Study");
        assertThat(m).doesNotContainKey("confirm");

        Map<String, Object> m2 = svc.handle(same(r), MINE, "Prayer Meeting");
        assertThat(m2.get("intent")).as("category given → the next missing thing, location").isEqualTo("meeting.ask");
        assertThat(m2.get("asking")).isEqualTo("location");
        Map<String, Object> m3 = svc.handle(same(r), MINE, "no location");
        assertThat(m3.get("intent")).isEqualTo("meeting.confirm");
        assertThat(summary(m3).get("Category")).isEqualTo("Prayer Meeting");
        assertThat(summary(m3).get("Location")).isEqualTo("Not set");
        verify(meetingService, never()).save(any(), anyString());
    }

    @Test @DisplayName("missing date → asks; an unrelated follow-up question drops the draft instead of hijacking it")
    void asksForDate() {
        MockHttpServletRequest r = staff("Admin", null);
        Map<String, Object> m = svc.handle(r, MINE, "Create a Bible Study meeting at 7pm");
        assertThat(m.get("intent")).isEqualTo("meeting.ask");
        assertThat(m.get("missing")).isEqualTo(List.of("date", "location"));
        assertThat(m.get("asking")).isEqualTo("date");
        assertThat(svc.handle(same(r), MINE, "what was the income last month")).isNull();   // moved on
        assertThat(svc.handle(same(r), MINE, "November 3")).isNull();                        // draft is gone
    }

    @Test @DisplayName("date reply completes the draft: \"next Monday\" resolves on the church calendar")
    void dateReply() {
        MockHttpServletRequest r = staff("Admin", null);
        svc.handle(r, MINE, "Create a Bible Study meeting at 7pm in the fellowship hall");
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
        Map<String, Object> m = svc.handle(r, MINE, "Create a Bible Study meeting tomorrow at 7 in the hall");
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
        Map<String, Object> m = svc.handle(r, MINE, "Create a Youth Bible Study meeting tomorrow at 5pm in the hall");
        @SuppressWarnings("unchecked") Map<String, String> sum = (Map<String, String>) ((Map<String, Object>) m.get("confirm")).get("summary");
        assertThat(sum.get("Category")).isEqualTo("Youth Bible Study");
        Map<String, Object> m2 = svc.handle(staff("Admin", null), MINE, "Create a bible study meeting tomorrow at 5pm in the hall");
        @SuppressWarnings("unchecked") Map<String, String> sum2 = (Map<String, String>) ((Map<String, Object>) m2.get("confirm")).get("summary");
        assertThat(sum2.get("Category")).isEqualTo("Bible Study");
    }

    @Test @DisplayName("time not given → it is asked; \"no time\" leaves it blank (the page allows it) and the meeting is created without one")
    void timeAskedThenSkipped() {
        MockHttpServletRequest r = staff("Admin", null);
        Map<String, Object> m = svc.handle(r, MINE, "Create a Bible Study meeting on December 5 in the fellowship hall");
        assertThat(m.get("intent")).isEqualTo("meeting.ask");
        assertThat(m.get("asking")).isEqualTo("time");
        assertThat(String.valueOf(m.get("answer"))).contains("What time does it start");
        Map<String, Object> c = svc.handle(same(r), MINE, "no time");
        assertThat(c.get("intent")).isEqualTo("meeting.confirm");
        assertThat(summary(c).get("Time")).isEqualTo("Not set");
        svc.handle(same(r), MINE, "yes");
        ArgumentCaptor<MeetingBO> bo = ArgumentCaptor.forClass(MeetingBO.class);
        verify(meetingService).save(bo.capture(), eq(MINE));
        assertThat(bo.getValue().getStartTime()).isNull();
        assertThat(bo.getValue().getMeetingDate()).endsWith("-12-05");
        assertThat(bo.getValue().getAddress1()).isEqualTo("the fellowship hall");
    }

    // ── The four questions, in order, never repeated ───────────────────────────

    @Test @DisplayName("\"Create a meeting\" alone → asks category, then date, then location, then time — one at a time")
    void asksAllFourInOrder() {
        MockHttpServletRequest r = staff("Admin", null);
        Map<String, Object> m = svc.handle(r, MINE, "Create a meeting");
        assertThat(m.get("asking")).isEqualTo("category");
        assertThat(m.get("missing")).isEqualTo(List.of("category", "date", "location", "time"));
        m = svc.handle(same(r), MINE, "Bible Study");
        assertThat(m.get("asking")).isEqualTo("date");
        assertThat(String.valueOf(m.get("answer"))).isEqualTo("What date is the Bible Study meeting?");
        m = svc.handle(same(r), MINE, "December 5");
        assertThat(m.get("asking")).isEqualTo("location");
        assertThat(String.valueOf(m.get("answer"))).contains("Where will it be held?");
        m = svc.handle(same(r), MINE, "the fellowship hall");
        assertThat(m.get("asking")).isEqualTo("time");
        m = svc.handle(same(r), MINE, "7 to 8:30 pm");
        assertThat(m.get("intent")).isEqualTo("meeting.confirm");
        assertThat(summary(m)).containsEntry("Category", "Bible Study").containsEntry("Location", "the fellowship hall")
                              .containsEntry("Time", "7:00 PM – 8:30 PM");
        verify(meetingService, never()).save(any(), anyString());
    }

    @Test @DisplayName("whatever is already given is never asked again — one sentence with all four goes straight to confirmation")
    void noDuplicateQuestions() {
        MockHttpServletRequest r = staff("Admin", null);
        Map<String, Object> m = svc.handle(r, MINE, "Create a Bible Study meeting on December 5 at 7pm at Hanna Thomas's house");
        assertThat(m.get("intent")).isEqualTo("meeting.confirm");
        // Date + location in one reply to the date question: the location is not asked afterwards.
        MockHttpServletRequest r2 = staff("Admin", null);
        svc.handle(r2, MINE, "Create a Prayer Meeting at 6pm");
        Map<String, Object> m2 = svc.handle(same(r2), MINE, "December 9 in the chapel");
        assertThat(m2.get("intent")).isEqualTo("meeting.confirm");
        assertThat(summary(m2)).containsEntry("Location", "the chapel").containsEntry("Time", "6:00 PM");
        // A time given while answering the location question is taken too.
        MockHttpServletRequest r3 = staff("Admin", null);
        svc.handle(r3, MINE, "Create a Bible Study meeting on December 5");
        Map<String, Object> m3 = svc.handle(same(r3), MINE, "at the chapel at 7:30pm");
        assertThat(m3.get("intent")).isEqualTo("meeting.confirm");
        assertThat(summary(m3)).containsEntry("Location", "the chapel").containsEntry("Time", "7:30 PM");
    }

    @Test @DisplayName("location = a member's home (the page's picker): the member id and their address are saved, like selectLocation()")
    void memberHomeLocation() {
        MockHttpServletRequest r = staff("Admin", null);
        Map<String, Object> m = svc.handle(r, MINE, "Create a Bible Study meeting on December 5 at 7pm at Hanna's house");
        assertThat(summary(m).get("Location")).isEqualTo("Hanna Thomas\u2019s home (5 Pine Rd, Frisco)");
        svc.handle(same(r), MINE, "yes");
        ArgumentCaptor<MeetingBO> bo = ArgumentCaptor.forClass(MeetingBO.class);
        verify(meetingService).save(bo.capture(), eq(MINE));
        assertThat(bo.getValue().getLocationMemberId()).isEqualTo(23);
        assertThat(bo.getValue().getAddress1()).isEqualTo("5 Pine Rd");
        assertThat(bo.getValue().getCity()).isEqualTo("Frisco");
        assertThat(bo.getValue().getPinCode()).isEqualTo("75001");
        verify(memberRepo).findAllMembersWithOwnAddressByAppUser(MINE);                // the caller's church only
    }

    @Test @DisplayName("two members match the location name → asked which one; the answer picks the right home")
    void ambiguousMemberHome() {
        MockHttpServletRequest r = staff("Admin", null);
        Map<String, Object> m = svc.handle(r, MINE, "Create a Bible Study meeting on December 5 at 7pm at Mark's house");
        assertThat(m.get("intent")).isEqualTo("meeting.ask");
        assertThat(m.get("asking")).isEqualTo("location");
        assertThat(String.valueOf(m.get("answer"))).contains("Mark Brooks", "Mark Lee");
        Map<String, Object> c = svc.handle(same(r), MINE, "Lee");
        assertThat(c.get("intent")).isEqualTo("meeting.confirm");
        assertThat(summary(c).get("Location")).startsWith("Mark Lee");
    }

    @Test @DisplayName("a home of someone without an address on file is said so, then asked again (no made-up location)")
    void unknownMemberHome() {
        MockHttpServletRequest r = staff("Admin", null);
        Map<String, Object> m = svc.handle(r, MINE, "Create a Bible Study meeting on December 5 at 7pm at Zed's house");
        assertThat(m.get("asking")).isEqualTo("location");
        assertThat(String.valueOf(m.get("answer"))).contains("could not find a member named \u201cZed\u201d");
        Map<String, Object> c = svc.handle(same(r), MINE, "100 Main Street, Dallas");
        assertThat(summary(c).get("Location")).isEqualTo("100 Main Street, Dallas");
    }

    @Test @DisplayName("answering the location question with \"no\" / \"not sure\" leaves it blank — it does not cancel the meeting")
    void locationSkipWords() {
        for (String reply : new String[] { "no", "not sure", "skip", "TBD", "no location" }) {
            MockHttpServletRequest r = staff("Admin", null);
            svc.handle(r, MINE, "Create a Bible Study meeting on December 5 at 7pm");
            Map<String, Object> c = svc.handle(same(r), MINE, reply);
            assertThat(c.get("intent")).as(reply).isEqualTo("meeting.confirm");
            assertThat(summary(c).get("Location")).as(reply).isEqualTo("Not set");
        }
        MockHttpServletRequest r = staff("Admin", null);
        svc.handle(r, MINE, "Create a Bible Study meeting on December 5 at 7pm");
        assertThat(svc.handle(same(r), MINE, "cancel").get("intent")).isEqualTo("meeting.cancelled");
    }

    @Test @DisplayName("\"in November\" or \"in the morning\" is never taken as a place")
    void periodWordsAreNotPlaces() {
        MockHttpServletRequest r = staff("Admin", null);
        Map<String, Object> m = svc.handle(r, MINE, "Create a Bible Study meeting in the evening on December 5");
        assertThat(m.get("asking")).isIn("location", "time");
        assertThat(String.valueOf(m)).doesNotContain("Location=the evening");
    }

    // ── After creation: a real link, or a real error ────────────────────────────

    @Test @DisplayName("created → link to THIS meeting on the Meetings page (/meetings?meetingId=<the saved id>)")
    void createdLinksToTheMeeting() {
        MockHttpServletRequest r = staff("Admin", null);
        svc.handle(r, MINE, "Create a Bible Study meeting tomorrow at 7pm in the fellowship hall");
        Map<String, Object> done = svc.handle(same(r), MINE, "yes");
        assertThat(done.get("intent")).isEqualTo("meeting.created");
        assertThat(done.get("meetingId")).isEqualTo(99);
        @SuppressWarnings("unchecked") Map<String, String> link = (Map<String, String>) done.get("link");
        assertThat(link).containsEntry("url", "/meetings?meetingId=99").containsEntry("label", "Open this meeting");
        assertThat(String.valueOf(done.get("answer"))).contains("Created a one-time Bible Study meeting").contains("the fellowship hall");
    }

    @Test @DisplayName("save fails → an error, no success words and no link")
    void failureHasNoSuccessOrLink() {
        when(meetingService.save(any(), anyString())).thenThrow(new IllegalArgumentException("Meeting type not found: 1"));
        MockHttpServletRequest r = staff("Admin", null);
        svc.handle(r, MINE, "Create a Bible Study meeting tomorrow at 7pm in the fellowship hall");
        Map<String, Object> e = svc.handle(same(r), MINE, "yes");
        assertThat(e.get("intent")).isEqualTo("meeting.error");
        assertThat(e).doesNotContainKeys("link", "created", "meetingId");
        assertThat(String.valueOf(e.get("answer"))).doesNotContain("Created").contains("Nothing was saved");

        org.mockito.Mockito.doThrow(new RuntimeException("db down")).when(meetingService).save(any(), anyString());
        MockHttpServletRequest r2 = staff("Admin", null);
        svc.handle(r2, MINE, "Create a Bible Study meeting tomorrow at 7pm in the fellowship hall");
        Map<String, Object> e2 = svc.handle(same(r2), MINE, "yes");
        assertThat(e2.get("intent")).isEqualTo("meeting.error");
        assertThat(e2).doesNotContainKeys("link", "created");
        assertThat(String.valueOf(e2.get("answer"))).doesNotContain("db down");                  // no internals

        org.mockito.Mockito.doReturn(new Meeting()).when(meetingService).save(any(), anyString());   // saved without an id
        MockHttpServletRequest r3 = staff("Admin", null);
        svc.handle(r3, MINE, "Create a Bible Study meeting tomorrow at 7pm in the fellowship hall");
        assertThat(svc.handle(same(r3), MINE, "yes")).doesNotContainKeys("link", "created");
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
        svc.handle(r, MINE, "Create a Bible Study meeting tomorrow at 7pm in the fellowship hall");
        when(typeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(1, MINE)).thenReturn(Optional.empty());
        assertThat(svc.handle(same(r), MINE, "yes").get("intent")).isEqualTo("meeting.error");
        verify(meetingService, never()).save(any(), anyString());
    }

    @Test @DisplayName("permission revoked between the question and the confirmation → denied, nothing created")
    void permissionRevokedBeforeConfirm() {
        MockHttpServletRequest r = staff("Admin", null);
        Map<String, Object> m = svc.handle(r, MINE, "Create a Bible Study meeting tomorrow at 7pm in the fellowship hall");
        @SuppressWarnings("unchecked") String token = String.valueOf(((Map<String, Object>) m.get("confirm")).get("token"));
        r.getSession().setAttribute("privileges", "{\"general.meetings.edit\":false}");
        assertThat(svc.confirm(same(r), MINE, token).get("denied")).isEqualTo(true);
        verify(meetingService, never()).save(any(), anyString());
    }
}
