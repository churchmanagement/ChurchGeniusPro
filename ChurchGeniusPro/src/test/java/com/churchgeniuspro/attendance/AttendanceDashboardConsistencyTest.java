package com.churchgeniuspro.attendance;

import com.churchgeniuspro.hibernate.AttendanceRecord;
import com.churchgeniuspro.hibernate.AttendanceVisitor;
import com.churchgeniuspro.hibernate.VolunteerProfile;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.AttendanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Attendance dashboard (2026-11-10): the summary cards and the four charts must come from the same
 * attendance_record rows with the same Present/Late rule; they differ only by date window, and the
 * server now says which window each figure covers. Nothing may be hard-coded or zeroed to "match".
 */
class AttendanceDashboardConsistencyTest {

    private static final String CID = "CHURCH-A";
    /** Tuesday 10 Nov 2026 — the day the mismatch was reported. Week = Mon 9 – Sun 15; month = Nov. */
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    private final List<AttendanceRecord> rows = new ArrayList<>();
    private final List<AttendanceVisitor> visitors = new ArrayList<>();
    private AttendanceService svc;

    @BeforeEach
    void setUp() {
        AttendanceRecordRepository recordRepo = mock(AttendanceRecordRepository.class);
        when(recordRepo.findByClientIdAndAttendanceDateBetweenAndDeleteFlagFalse(anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenAnswer(inv -> {
                    String cid = inv.getArgument(0); LocalDate from = inv.getArgument(1), to = inv.getArgument(2);
                    return rows.stream().filter(r -> cid.equals(r.getClientId()) && !r.isDeleteFlag()
                            && !r.getAttendanceDate().isBefore(from) && !r.getAttendanceDate().isAfter(to)).collect(Collectors.toList());
                });
        AttendanceVisitorRepository visitorRepo = mock(AttendanceVisitorRepository.class);
        when(visitorRepo.findByClientIdAndDeleteFlagFalseOrderByNameAsc(anyString()))
                .thenAnswer(inv -> visitors.stream().filter(v -> inv.getArgument(0).equals(v.getClientId())).collect(Collectors.toList()));
        VolunteerProfileRepository volunteerRepo = mock(VolunteerProfileRepository.class);
        VolunteerProfile vp = new VolunteerProfile(); vp.setFamilyMemberId(1);
        when(volunteerRepo.findByAppClientIdAndDeleteFlagFalseOrderByFamilyMemberIdAsc(CID)).thenReturn(List.of(vp));
        svc = new AttendanceService(recordRepo, visitorRepo, mock(AttendanceServiceTypeRepository.class),
                mock(MemberAttendanceCodeRepository.class), mock(FamilyMemberRepository.class), volunteerRepo, mock(FamilyRepository.class));
    }

    private AttendanceRecord row(String cid, LocalDate date, String status, String service, String ministry, Integer memberId, String createdBy) {
        AttendanceRecord r = new AttendanceRecord();
        r.setClientId(cid); r.setAttendanceDate(date); r.setStatus(status); r.setServiceType(service); r.setMinistry(ministry);
        r.setPersonType(memberId == null ? "VISITOR" : "MEMBER"); r.setFamilyMemberId(memberId); r.setCreatedBy(createdBy);
        rows.add(r); return r;
    }

    /** The demo seeder's shape: Sunday Worship on the Sundays before the account was created, Bible Study on Wednesdays. */
    private void seedDemoLikeSeptemberOctober() {
        LocalDate sunday = LocalDate.of(2026, 10, 25);          // last demo Sunday
        for (int i = 0; i < 6; i++) {
            LocalDate d = sunday.minusWeeks(i);
            for (int m = 1; m <= 4; m++) row(CID, d, m == 4 ? "LATE" : "PRESENT", "Sunday Worship", "Worship", m, "demo");
            row(CID, d, "ABSENT", "Sunday Worship", "Worship", 9, "demo");   // never counted anywhere
        }
        LocalDate wed = LocalDate.of(2026, 10, 21);
        for (int i = 0; i < 4; i++) {
            LocalDate d = wed.minusWeeks(i);
            for (int m = 1; m <= 3; m++) row(CID, d, "PRESENT", "Bible Study", "Adults", m, "demo");
        }
        row("CHURCH-B", LocalDate.of(2026, 11, 9), "PRESENT", "Sunday Worship", "Worship", 1, "pastor"); // another church, this week
    }

    @Test
    @DisplayName("Cards 0 / charts non-zero is a window difference, not two data sets: the server names every window and counts the demo rows")
    void cardsAndChartsShareOneDataSetAndTheServerNamesTheWindows() {
        seedDemoLikeSeptemberOctober();

        Map<String, Object> d = svc.dashboard(CID, TODAY);
        assertThat(d).containsEntry("weekCount", 0L).containsEntry("monthCount", 0L)
                .containsEntry("membersPresent", 0).containsEntry("volunteersPresent", 0L);

        @SuppressWarnings("unchecked") Map<String, Map<String, Object>> w = (Map<String, Map<String, Object>>) d.get("windows");
        assertThat(w.get("week")).containsEntry("from", "2026-11-09").containsEntry("to", "2026-11-15").containsEntry("label", "This week");
        assertThat(w.get("month")).containsEntry("from", "2026-11-01").containsEntry("to", "2026-11-30").containsEntry("label", "This month");
        assertThat(w.get("weekly")).containsEntry("from", "2026-09-21").containsEntry("to", "2026-11-15").containsEntry("label", "Last 8 weeks");
        assertThat(w.get("monthly")).containsEntry("from", "2025-12-01").containsEntry("to", "2026-11-30").containsEntry("label", "Last 12 months");
        assertThat(w.get("byField")).containsEntry("from", "2026-08-10").containsEntry("to", "2026-11-10").containsEntry("label", "Last 3 months");
        assertThat(d).containsEntry("today", "2026-11-10").containsEntry("latestRecordDate", "2026-10-25");
        // 6 Sundays × 4 present + 4 Wednesdays × 3 present = 36 Present/Late rows, all demo; ABSENT and church B excluded.
        assertThat(d).containsEntry("recordsInCharts", 36).containsEntry("demoRecordsInCharts", 36L);

        // Weekly: 8 points, the current week 0, Sep/Oct weeks non-zero — only from the rows, same Present/Late rule.
        List<Map<String, Object>> weekly = svc.weeklyTrend(CID, TODAY);
        assertThat(weekly).hasSize(8);
        assertThat(weekly.get(7)).containsEntry("label", "Wk of Nov 9").containsEntry("value", 0L);
        assertThat(weekly.get(6)).containsEntry("label", "Wk of Nov 2").containsEntry("value", 0L);
        assertThat(weekly.get(5)).containsEntry("label", "Wk of Oct 26").containsEntry("value", 0L);
        assertThat(weekly.get(4)).containsEntry("label", "Wk of Oct 19").containsEntry("value", 7L);   // Wed 21 (3) + Sun 25 (4)
        assertThat(weekly.get(0)).containsEntry("label", "Wk of Sep 21").containsEntry("value", 4L);   // Sun 27 only (Wed 23 is before the 4 demo Wednesdays)

        // Monthly: 12 points, Nov 0, Oct and Sep from the rows; the current month equals the "This month" card.
        List<Map<String, Object>> monthly = svc.monthlyTrend(CID, TODAY);
        assertThat(monthly).hasSize(12);
        assertThat(monthly.get(11)).containsEntry("label", "Nov 26").containsEntry("value", d.get("monthCount"));
        assertThat(monthly.get(10)).containsEntry("label", "Oct 26").containsEntry("value", 25L);   // Sundays Oct 4/11/18/25 × 4 + Wednesdays Oct 7/14/21 × 3
        assertThat(monthly.get(9)).containsEntry("label", "Sep 26").containsEntry("value", 11L);   // Sundays Sep 20/27 × 4 + Wednesday Sep 30 × 3
        long sep = (Long) monthly.get(9).get("value"), oct = (Long) monthly.get(10).get("value");
        assertThat(sep + oct).isEqualTo(36L);

        // By service / by ministry: grouped by the rows' own fields, same window, same rule.
        assertThat(svc.byField(CID, false, TODAY)).extracting(p -> p.get("label") + "=" + p.get("value"))
                .containsExactly("Bible Study=12", "Sunday Worship=24");
        assertThat(svc.byField(CID, true, TODAY)).extracting(p -> p.get("label") + "=" + p.get("value"))
                .containsExactly("Adults=12", "Worship=24");
    }

    @Test
    @DisplayName("A record this week moves every card AND the current points of every chart at once")
    void aNewRecordMovesCardsAndCharts() {
        seedDemoLikeSeptemberOctober();
        row(CID, LocalDate.of(2026, 11, 9), "PRESENT", "Sunday Worship", null, 1, "pastor");
        row(CID, LocalDate.of(2026, 11, 9), "LATE", "Sunday Worship", "", 2, "pastor");
        row(CID, LocalDate.of(2026, 11, 9), "EXCUSED", "Sunday Worship", "Worship", 3, "pastor");

        Map<String, Object> d = svc.dashboard(CID, TODAY);
        assertThat(d).containsEntry("weekCount", 2L).containsEntry("monthCount", 2L)
                .containsEntry("membersPresent", 2).containsEntry("volunteersPresent", 1L)
                .containsEntry("recordsInCharts", 38).containsEntry("demoRecordsInCharts", 36L)
                .containsEntry("latestRecordDate", "2026-11-09");
        assertThat(svc.weeklyTrend(CID, TODAY).get(7)).containsEntry("value", 2L);
        assertThat(svc.monthlyTrend(CID, TODAY).get(11)).containsEntry("value", 2L);
        assertThat(svc.byField(CID, false, TODAY)).extracting(p -> p.get("label") + "=" + p.get("value"))
                .containsExactly("Bible Study=12", "Sunday Worship=26");
        assertThat(svc.byField(CID, true, TODAY)).extracting(p -> p.get("label") + "=" + p.get("value"))
                .containsExactly("(none)=2", "Adults=12", "Worship=24");
    }

    @Test
    @DisplayName("A church with no records: every card 0, every trend point 0 (never invented), grouped charts empty, no demo note")
    void emptyChurch() {
        Map<String, Object> d = svc.dashboard(CID, TODAY);
        assertThat(d).containsEntry("weekCount", 0L).containsEntry("monthCount", 0L).containsEntry("firstTimeVisitors", 0L)
                .containsEntry("returningVisitors", 0L).containsEntry("membersPresent", 0).containsEntry("volunteersPresent", 0L)
                .containsEntry("recordsInCharts", 0).containsEntry("demoRecordsInCharts", 0L).containsEntry("latestRecordDate", null);
        assertThat(d.get("windows")).isNotNull();
        assertThat(svc.weeklyTrend(CID, TODAY)).hasSize(8).allSatisfy(p -> assertThat(p).containsEntry("value", 0L));
        assertThat(svc.monthlyTrend(CID, TODAY)).hasSize(12).allSatisfy(p -> assertThat(p).containsEntry("value", 0L));
        assertThat(svc.byField(CID, false, TODAY)).isEmpty();
        assertThat(svc.byField(CID, true, TODAY)).isEmpty();
    }

    @Test
    @DisplayName("Visitor cards use the same month window; a Monday today still starts the week on that Monday")
    void visitorCardsAndWeekBoundary() {
        AttendanceVisitor first = new AttendanceVisitor(); first.setId(10L); first.setClientId(CID); first.setFirstVisitDate(LocalDate.of(2026, 11, 1)); first.setVisitCount(1);
        AttendanceVisitor back  = new AttendanceVisitor(); back.setId(11L);  back.setClientId(CID);  back.setFirstVisitDate(LocalDate.of(2026, 9, 6));  back.setVisitCount(3);
        AttendanceVisitor old   = new AttendanceVisitor(); old.setId(12L);   old.setClientId(CID);   old.setFirstVisitDate(LocalDate.of(2026, 10, 4)); old.setVisitCount(2);
        visitors.addAll(List.of(first, back, old));
        AttendanceRecord r = row(CID, LocalDate.of(2026, 11, 8), "PRESENT", "Sunday Worship", null, null, "pastor"); r.setVisitorId(11L);
        AttendanceRecord o = row(CID, LocalDate.of(2026, 10, 4), "PRESENT", "Sunday Worship", null, null, "pastor"); o.setVisitorId(12L);

        Map<String, Object> d = svc.dashboard(CID, TODAY);
        assertThat(d).containsEntry("firstTimeVisitors", 1L).containsEntry("returningVisitors", 1L)
                .containsEntry("monthCount", 1L).containsEntry("weekCount", 0L);   // Sun 8 Nov is last week

        Map<String, Object> monday = svc.dashboard(CID, LocalDate.of(2026, 11, 9));
        @SuppressWarnings("unchecked") Map<String, Map<String, Object>> w = (Map<String, Map<String, Object>>) monday.get("windows");
        assertThat(w.get("week")).containsEntry("from", "2026-11-09").containsEntry("to", "2026-11-15");
        assertThat(LocalDate.parse((String) w.get("weekly").get("from")).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
    }

    @Test
    @DisplayName("The page draws only what the server returns, labels every window, shows an empty state and refreshes after each write")
    void pageWiring() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/attendance.html"));
        String dash = html.substring(html.indexOf("/* ── Dashboard ──"), html.indexOf("/* ── Check-in context ── */"));
        assertThat(dash).contains("get('/api/attendance/dashboard')", "/api/attendance/trends/weekly", "/api/attendance/trends/monthly",
                "/api/attendance/trends/by-service", "/api/attendance/trends/by-ministry");
        assertThat(dash).doesNotContainPattern("data:\\s*\\[\\s*\\d");               // no literal series
        assertThat(dash).contains("DASH.windows", "fmtRange(w)", "No attendance recorded", "empty.hidden = !allZero", "renderDashNote",
                "demoRecordsInCharts", "visibilitychange", "setInterval", "function dashboardChanged()");
        assertThat(html).contains("id=\"winWeekly\"", "id=\"winMonthly\"", "id=\"winService\"", "id=\"winMinistry\"",
                "id=\"emptyWeekly\"", "id=\"emptyMonthly\"", "id=\"emptyService\"", "id=\"emptyMinistry\"", "id=\"dashNote\"", "id=\"dashUpdated\"");
        // every successful write refreshes the dashboard: member, family and visitor check-ins; visitor save/delete; record edit/delete
        assertThat(html.split("dashboardChanged\\(\\)").length - 1).isGreaterThanOrEqualTo(8);
        assertThat(html).contains("if (res.ok) dashboardChanged();");
        assertThat(html).contains("applyRecords(); dashboardChanged();");
        assertThat(html).contains("loadVisitors(); dashboardChanged();");
        // the service still has no literal figures
        String svcSrc = Files.readString(Path.of("src/main/java/com/churchgeniuspro/service/AttendanceService.java"));
        assertThat(svcSrc).contains("findByClientIdAndAttendanceDateBetweenAndDeleteFlagFalse").doesNotContain("Math.random");
    }
}
