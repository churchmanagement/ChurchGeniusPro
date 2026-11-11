package com.churchgeniuspro.dashboard;

import com.churchgeniuspro.controller.AdminDashboardController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.DashboardGlanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Home dashboard statistics (2026-11-10): shown only to SuperAdmin / Admin (and the church owner login) who
 * ALSO hold the existing Admin → Family permission — enforced by the server, not by the page; plus the
 * figures themselves (Groups, Attendance %, Events, Visitors, Birthdays / Anniversaries / Meetings).
 */
class DashboardStatsAccessTest {

    private static final String CID = "CHR-1";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);   // Tuesday; week = Mon 9 – Sun 15

    private FamilyMemberRepository memberRepo;
    private GroupRepository groupRepo;
    private ChurchEventRepository eventRepo;
    private FollowUpRepository followUpRepo;
    private AttendanceRecordRepository attendanceRepo;
    private MeetingRepository meetingRepo;
    private MeetingSkipDateRepository skipRepo;
    private DashboardGlanceService glance;
    private AdminDashboardController controller;

    @BeforeEach
    void setUp() {
        memberRepo = mock(FamilyMemberRepository.class);
        FamilyRepository familyRepo = mock(FamilyRepository.class);
        groupRepo = mock(GroupRepository.class); eventRepo = mock(ChurchEventRepository.class);
        followUpRepo = mock(FollowUpRepository.class); attendanceRepo = mock(AttendanceRecordRepository.class);
        meetingRepo = mock(MeetingRepository.class); skipRepo = mock(MeetingSkipDateRepository.class);
        when(memberRepo.findAllWithFamilyByAppUser(CID)).thenReturn(List.of(person(1, 1, "Member"), person(2, 1, "Member"), person(3, 2, "Guest")));
        when(familyRepo.findAllActiveForLocationByAppUser(CID)).thenReturn(List.of(new Family(), new Family()));
        when(groupRepo.findActiveByAppUser(CID)).thenReturn(List.of(new Group(), new Group(), new Group()));
        when(meetingRepo.findUpcomingFromDateByAppUser(any(), eq(CID))).thenReturn(List.of());
        glance = new DashboardGlanceService(groupRepo, eventRepo, followUpRepo, attendanceRepo, meetingRepo, skipRepo);
        controller = new AdminDashboardController(mock(ChurchRegistrationRepository.class), memberRepo, familyRepo, meetingRepo);
        ReflectionTestUtils.setField(controller, "glanceService", glance);
    }

    private static FamilyMember person(int id, int familyId, String type) {
        FamilyMember m = new FamilyMember(); m.setId(id); m.setMemberType(type);
        Family f = new Family(); f.setId(familyId); m.setFamily(f);
        return m;
    }

    private static MockHttpServletRequest req(String role, String privileges, boolean church) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "u"); s.setAttribute("role", role); s.setAttribute("appUserId", 1);
        s.setAttribute("church", church); s.setAttribute("clientId", CID); s.setAttribute("appClientId", CID);
        if (privileges != null) s.setAttribute("privileges", privileges);
        r.setSession(s);
        return r;
    }

    private Map<String, Object> call(MockHttpServletRequest r, int expectedStatus) {
        ResponseEntity<Map<String, Object>> res = controller.dashboard(CID, r);
        assertThat(res.getStatusCode().value()).isEqualTo(expectedStatus);
        return res.getBody();
    }

    private static void hasStats(Map<String, Object> body) {
        assertThat(body).containsEntry("statsAllowed", true).containsEntry("totalMembers", 2L).containsEntry("guests", 1L)
                .containsEntry("families", 2L).containsKeys("newMembers", "glance");
    }

    private static void hasNoStats(Map<String, Object> body) {
        assertThat(body).containsEntry("statsAllowed", false)
                .doesNotContainKeys("totalMembers", "newMembers", "guests", "families", "glance");
    }

    @Test
    @DisplayName("SuperAdmin, Admin with Family, and the church owner login get the statistics")
    void allowed() {
        hasStats(call(req("SuperAdmin", null, false), 200));                       // nothing saved
        hasStats(call(req("Admin", null, false), 200));                            // nothing saved → Admin defaults include Family
        hasStats(call(req("Admin", "{\"admin\":true,\"admin.family\":true}", false), 200));
        hasStats(call(req("SuperAdmin", "{\"admin.family\":true,\"admin.groups\":false}", false), 200));
        hasStats(call(req("Church", null, true), 200));                            // owner login: outside the permission map
    }

    @Test
    @DisplayName("Admin or SuperAdmin WITHOUT the Family permission: the figures are not in the response at all")
    void roleAloneIsNotEnough() {
        hasNoStats(call(req("Admin", "{\"admin\":true,\"admin.family\":false}", false), 200));
        hasNoStats(call(req("SuperAdmin", "{\"admin.family\":false}", false), 200));
        hasNoStats(call(req("Admin", "{\"admin\":false,\"admin.family\":true}", false), 200));   // Admin main section off closes its pages
        // the rest of the dashboard still answers for them, and no statistics lookup ran
        verify(groupRepo, never()).findActiveByAppUser(any());
        verify(followUpRepo, never()).findByClientIdAndLinkedTypeAndDeleteFlagFalseOrderByCreatedAtDesc(any(), any());
        assertThat(call(req("Admin", "{\"admin.family\":false}", false), 200)).containsKeys("upcomingMeetings", "membersByMonth");
    }

    @Test
    @DisplayName("Every other role is refused by the endpoint, whatever its saved permissions say")
    void otherRolesRefused() {
        for (String role : List.of("Accountant", "User", "Limited", "Member", "Child")) {
            assertThat(call(req(role, null, false), 403)).containsEntry("code", "PERMISSION_DENIED").doesNotContainKeys("totalMembers", "glance");
            assertThat(call(req(role, "{\"admin\":true,\"admin.family\":true}", false), 403)).doesNotContainKeys("totalMembers", "glance");
        }
        MockHttpServletRequest member = req("Member", "{\"admin.family\":true}", false);
        member.getSession().removeAttribute("username"); member.getSession().setAttribute("memberId", 5);
        member.getSession().setAttribute("memberPrivileges", "{\"admin.family\":true}");
        assertThat(call(member, 403)).doesNotContainKeys("totalMembers", "glance");
        // no session / no church on the session
        assertThat(controller.dashboard(CID, new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(401);
        verify(memberRepo, never()).findAllWithFamilyByAppUser(null);
    }

    @Test
    @DisplayName("The helper used by the page rule is role AND permission")
    void helper() {
        assertThat(AdminDashboardController.statsAllowed(req("Admin", null, false))).isTrue();
        assertThat(AdminDashboardController.statsAllowed(req("Admin", "{\"admin.family\":false}", false))).isFalse();
        assertThat(AdminDashboardController.statsAllowed(req("Accountant", "{\"admin.family\":true}", false))).isFalse();
        assertThat(AdminDashboardController.STATS_PERMISSION).isEqualTo("admin.family");
    }

    // ── figures ──────────────────────────────────────────────────────────────────────────────────

    private static FamilyMember dated(int id, int familyId, Integer bm, Integer bd, Integer am, Integer ad) {
        FamilyMember m = person(id, familyId, "Member");
        m.setBirthdayMonth(bm); m.setBirthdayDay(bd); m.setAnniversaryMonth(am); m.setAnniversaryDay(ad);
        return m;
    }

    private static AttendanceRecord att(Integer memberId, LocalDate d, String status) {
        AttendanceRecord a = new AttendanceRecord(); a.setClientId(CID); a.setFamilyMemberId(memberId); a.setAttendanceDate(d); a.setStatus(status);
        return a;
    }

    private static FollowUp fu(String status) { FollowUp f = new FollowUp(); f.setStatus(status); f.setLinkedType("VISITOR"); return f; }

    private static Meeting meeting(int id, LocalDate date, String occurrence, LocalDate end) {
        Meeting m = new Meeting(); m.setId(id); m.setMeetingDate(date); m.setOccurrence(occurrence); m.setEndDate(end);
        return m;
    }

    @Test
    @DisplayName("Figures: groups, attendance %, events, visitors = open visitor follow-ups, birthdays / anniversaries / meetings by window")
    @SuppressWarnings("unchecked")
    void figures() {
        List<FamilyMember> people = List.of(
                dated(1, 1, 11, 10, 11, 12),      // birthday today; anniversary Thu this week
                dated(2, 1, 11, 15, 11, 12),      // birthday Sunday this week; SAME family + date → one anniversary
                dated(3, 2, 11, 30, 6, 1),        // birthday later this month; anniversary in June
                dated(4, 3, 12, 1, 11, 9),        // birthday next month; anniversary Monday this week
                dated(5, 4, null, null, 11, null),// incomplete dates ignored
                dated(6, 5, 2, 29, 13, 40));      // invalid anniversary ignored
        when(attendanceRepo.findByClientIdAndAttendanceDateBetweenAndDeleteFlagFalse(CID, TODAY.minusDays(29), TODAY)).thenReturn(List.of(
                att(1, TODAY, "PRESENT"), att(1, TODAY.minusDays(7), "PRESENT"),   // same person twice = once
                att(2, TODAY.minusDays(3), "LATE"),
                att(3, TODAY.minusDays(3), "ABSENT"),                               // not present
                att(99, TODAY, "PRESENT"),                                          // not in the directory (deleted / inactive)
                att(null, TODAY, "PRESENT")));                                      // a visitor row
        List<Object[]> events = new ArrayList<>(); events.add(new Object[] {1}); events.add(new Object[] {2});
        when(eventRepo.aiUpcomingEvents(eq(CID), eq(java.sql.Date.valueOf(TODAY)), eq(java.sql.Date.valueOf(TODAY.plusDays(365))), anyInt())).thenReturn(events);
        when(followUpRepo.findByClientIdAndLinkedTypeAndDeleteFlagFalseOrderByCreatedAtDesc(CID, "VISITOR"))
                .thenReturn(List.of(fu("PENDING"), fu("MISSED"), fu("COMPLETED"), fu("PENDING")));
        when(meetingRepo.findAllActiveByAppUserOrderByDateAsc(CID)).thenReturn(List.of(
                meeting(1, TODAY, "One-time", null),                                    // today
                meeting(2, LocalDate.of(2026, 11, 20), "One-time", null),               // later this month
                meeting(3, LocalDate.of(2026, 11, 2), "Daily", LocalDate.of(2026, 11, 11)),   // 2–11 Nov: 10 days, 3 this week, 1 today
                meeting(4, LocalDate.of(2026, 12, 3), "One-time", null)));              // next month
        MeetingSkipDate skip = new MeetingSkipDate(); skip.setMeetingId(3); skip.setSkipDate(LocalDate.of(2026, 11, 9));
        when(skipRepo.findByMeetingIdIn(any())).thenReturn(List.of(skip));

        Map<String, Object> g = glance.glance(CID, people, TODAY);
        assertThat(g).containsEntry("groups", 3).containsEntry("events", 2).containsEntry("visitors", 3L);
        assertThat((Map<String, Object>) g.get("attendance")).containsEntry("percent", 33).containsEntry("attended", 2)
                .containsEntry("people", 6).containsEntry("days", 30);
        assertThat((Map<String, Object>) g.get("birthdays")).containsEntry("month", 3).containsEntry("week", 2).containsEntry("today", 1);
        assertThat((Map<String, Object>) g.get("anniversaries")).containsEntry("month", 2).containsEntry("week", 2).containsEntry("today", 0);
        // month: 1 + 1 + (10 − 1 skipped) = 11; week (9–15): today's one-time + daily on 10, 11 (9 skipped) = 3; today: 2
        assertThat((Map<String, Object>) g.get("meetings")).containsEntry("month", 11).containsEntry("week", 3).containsEntry("today", 2);
        assertThat((Map<String, Object>) g.get("windows")).containsEntry("weekFrom", "2026-11-09").containsEntry("weekTo", "2026-11-15")
                .containsEntry("monthFrom", "2026-11-01").containsEntry("monthTo", "2026-11-30");
        // counts only — nothing personal leaves the service
        assertThat(g.toString()).doesNotContain("birthdayYear").doesNotContain("name");
    }

    @Test
    @DisplayName("An empty church is all zeros; 29 Feb is observed on 28 Feb in a non-leap year; a blank church is refused")
    void edges() {
        Map<String, Object> g = glance.glance(CID, List.of(), TODAY);
        assertThat(g).containsEntry("groups", 3).containsEntry("events", 0).containsEntry("visitors", 0L);
        assertThat(g.get("attendance").toString()).contains("percent=0");
        assertThat(g.get("meetings").toString()).isEqualTo("{month=0, week=0, today=0}");
        Map<String, Object> feb = glance.glance(CID, List.of(dated(6, 5, 2, 29, null, null)), LocalDate.of(2027, 2, 28));
        assertThat(feb.get("birthdays").toString()).isEqualTo("{month=1, week=1, today=1}");
        Map<String, Object> leap = glance.glance(CID, List.of(dated(6, 5, 2, 29, null, null)), LocalDate.of(2028, 2, 28));
        assertThat(leap.get("birthdays").toString()).isEqualTo("{month=1, week=1, today=0}");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> glance.glance(" ", List.of(), TODAY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Page: the block follows the server's answer, the old greeting and AI Search bar are gone, cards are compact")
    void page() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/home.html"));
        assertThat(html).contains("setStatsAllowed(data && data.statsAllowed === true)", "s.sid !== STATS_SID || _statsAllowed",
                "if (data && data.statsAllowed === true) renderStatCards(data);");
        assertThat(html).doesNotContain("id=\"bannerGreeting\"", "id=\"acctGreeting\"", "'Good evening'", "Good morning");
        assertThat(html).contains("class=\"ai-search-wrap ga-engine-bar\"", "aria-hidden=\"true\"");
        for (String label : List.of("Members", "New Members", "Guests", "Families", "Groups", "Attendance", "Events", "Visitors", "Birthdays", "Anniversaries", "Meetings"))
            assertThat(html).contains("label:'" + label + "'");
        assertThat(html).contains("This month", "This week", "Today", ".stat-value { font-size: 20px;", "padding: 11px 10px 10px;");
        String engine = Files.readString(Path.of("src/main/resources/static/ai-assistant.js"));
        assertThat(engine).doesNotContain("Type a question, e.g.").contains("' ga-engine-bar'");
        String chat = Files.readString(Path.of("src/main/resources/static/ai-chat.js"));
        assertThat(chat).contains("id=\"gaHelp\"", ">Help</button>", "id=\"gaConv\"", "html.ga-on:not(.ga-open) .ga-fab{display:flex;}",
                "classList.add('ga-on')").doesNotContain("gaMHelp", "gaMConverse");
    }
}
