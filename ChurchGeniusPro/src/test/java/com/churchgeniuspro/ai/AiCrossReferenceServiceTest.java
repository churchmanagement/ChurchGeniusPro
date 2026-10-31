package com.churchgeniuspro.ai;

import com.churchgeniuspro.repository.AiCrossReferenceRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.VolunteerProfileRepository;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.AiAccessPolicy.CrossAccess;
import com.churchgeniuspro.service.AiCrossReferenceService;
import com.churchgeniuspro.service.AiDataSearchService;
import com.churchgeniuspro.service.AiQueryLexicon;
import com.churchgeniuspro.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AI Search Phase 1 (approved 2026-10-31) — cross-referenced questions. Pins the approval's conditions:
 * every module a question reads must be allowed or the WHOLE question is refused naming what is missing;
 * the church is the session's; Follow-ups ownership; thresholds shown as definitions; bounded statements.
 */
@DisplayName("AI Search Phase 1 — cross-referenced questions")
class AiCrossReferenceServiceTest {

    static final String CID = "CHR-1";
    static final LocalDate TODAY = LocalDate.of(2026, 10, 31);

    AiCrossReferenceRepository repo;
    AiCrossReferenceService svc;

    @BeforeEach
    void setUp() {
        repo = mock(AiCrossReferenceRepository.class);
        svc = new AiCrossReferenceService(repo);
    }

    static AiAccess admin() { return access("Admin", true, true, true, true); }
    /** role, income records, volunteers, follow-ups, follow-ups (all) */
    static AiAccess access(String role, boolean income, boolean volunteers, boolean followUps, boolean followUpsAll) {
        return new AiAccess(role, "pat", false, false, false, true, income, true, true, volunteers,
                true, true, true, followUps, followUpsAll, true, true, true, true, true, Set.of());
    }
    static final CrossAccess BOTH = new CrossAccess(true, true);

    static String q(String s) {       // the same normalisation + synonym mapping the endpoint applies
        String n = s.toLowerCase().replace("’", "'").replace("'s ", " ").replace("'", "").replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        return AiQueryLexicon.canonical(n);
    }
    Map<String, Object> ask(String question, AiAccess a, CrossAccess x) { return svc.route(q(question), a, x, CID, TODAY); }

    static Object[] person(int id, String first, String last, LocalDate last_, long count, Object extra, String campus, long total) {
        return new Object[] { id, first, last, java.sql.Date.valueOf(last_), count, extra, campus, total };
    }

    /* ─────────────────── routing ─────────────────── */

    @Nested @DisplayName("routing")
    class Routing {
        @Test @DisplayName("each example question reaches its own question type")
        void examples() {
            assertThat(ask("Who came on Easter but hasn't attended since February?", admin(), BOTH).get("intent")).isEqualTo("crossref.lapsed");
            assertThat(ask("Regular attenders we haven't seen in a month", admin(), BOTH).get("intent")).isEqualTo("crossref.lapsed");
            assertThat(ask("Which regular attenders aren't in any group?", admin(), BOTH).get("intent")).isEqualTo("crossref.notInGroup");
            assertThat(ask("Regular attenders not in a small group", admin(), BOTH).get("intent")).isEqualTo("crossref.notInGroup");
            assertThat(ask("First-time visitors this month with no follow-up", admin(), BOTH).get("intent")).isEqualTo("crossref.visitors");
            assertThat(ask("Visitors who haven't been followed up", admin(), BOTH).get("intent")).isEqualTo("crossref.visitors");
            assertThat(ask("Members who gave last year but not this year", admin(), BOTH).get("intent")).isEqualTo("crossref.lapsedGivers");
            assertThat(ask("Who donated last month but not this month?", admin(), BOTH).get("intent")).isEqualTo("crossref.lapsedGivers");
            assertThat(ask("Regular attenders who aren't serving", admin(), BOTH).get("intent")).isEqualTo("crossref.notServing");
            assertThat(ask("Regular attenders who don't volunteer", admin(), BOTH).get("intent")).isEqualTo("crossref.notServing");
        }

        @Test @DisplayName("ordinary questions are left to the other intents (null)")
        void notOurs() {
            for (String s : new String[] { "upcoming events this week", "show deleted members", "who are the volunteers",
                    "income for March", "who gave last year", "regular expenses not paid", "events not cancelled",
                    "open follow ups", "list groups", "what is attendance", "how many members are not active" })
                assertThat(ask(s, admin(), BOTH)).as(s).isNull();
            verifyNoInteractions(repo);
        }
    }

    /* ─────────────────── permissions ─────────────────── */

    @Nested @DisplayName("permissions — the whole question or nothing")
    class Permissions {
        @Test @DisplayName("not-in-group needs Attendance AND Groups; either missing refuses everything and names it")
        void groupsBoth() {
            Map<String, Object> m = ask("regular attenders not in any group", admin(), new CrossAccess(true, false));
            assertThat(m.get("denied")).isEqualTo(true);
            assertThat(m.get("missing")).isEqualTo(List.of("Groups"));
            assertThat((String) m.get("answer")).contains("Groups (Admin or SuperAdmin role with the Groups permission");
            assertThat(m).doesNotContainKeys("rows", "columns", "total");
            m = ask("regular attenders not in any group", admin(), new CrossAccess(false, true));
            assertThat(m.get("missing")).isEqualTo(List.of("Attendance"));
            m = ask("regular attenders not in any group", admin(), CrossAccess.none());
            assertThat(m.get("missing")).isEqualTo(List.of("Attendance", "Groups"));
            verifyNoInteractions(repo);
        }

        @Test @DisplayName("not-serving needs Attendance AND Volunteers")
        void serving() {
            assertThat(ask("regular attenders not serving", access("Admin", true, false, true, true), BOTH).get("missing")).isEqualTo(List.of("Volunteers"));
            assertThat(ask("regular attenders not serving", admin(), new CrossAccess(false, true)).get("missing")).isEqualTo(List.of("Attendance"));
            verifyNoInteractions(repo);
        }

        @Test @DisplayName("lapsed attenders need Attendance; visitors need Follow-ups; lapsed givers need Income")
        void singles() {
            assertThat(ask("regulars we haven't seen", admin(), new CrossAccess(false, true)).get("missing")).isEqualTo(List.of("Attendance"));
            assertThat(ask("visitors with no follow up", access("User", true, true, false, false), BOTH).get("missing")).isEqualTo(List.of("Follow-ups"));
            assertThat(ask("members who gave last year but not this year", access("Admin", false, true, true, true), BOTH).get("missing")).isEqualTo(List.of("Income"));
            verifyNoInteractions(repo);
        }

        @Test @DisplayName("Member portal, church-owner and Kids sessions get nothing")
        void nonStaff() {
            AiAccess member = AiAccess.of("Member", false, true, false, true, false, false, false, false);
            AiAccess church = AiAccess.of("church", true, false, false, false, false, false, false, false);
            for (AiAccess a : List.of(member, church))
                for (String s : List.of("regular attenders not in any group", "regulars we haven't seen", "visitors with no follow up",
                        "members who gave last year but not this year", "regular attenders not serving"))
                    assertThat(ask(s, a, CrossAccess.none()).get("denied")).as(a.role() + ": " + s).isEqualTo(true);
            assertThat(ask("regulars we haven't seen", AiAccess.none("Child"), BOTH)).as("Kids: not even a denial is routed here").isNull();
            verifyNoInteractions(repo);
        }
    }

    /* ─────────────────── church isolation + follow-up ownership ─────────────────── */

    @Test @DisplayName("every query runs for the session's church only")
    void tenant() {
        ask("regular attenders not in any group", admin(), BOTH);
        ask("regulars we haven't seen", admin(), BOTH);
        ask("regular attenders not serving", admin(), BOTH);
        ask("members who gave last year but not this year", admin(), BOTH);
        ask("visitors with no follow up", admin(), BOTH);
        verify(repo).regularNotInGroup(eq(CID), any(), any(), anyInt(), anyInt());
        verify(repo).lapsedAttenders(eq(CID), any(), any(), anyInt(), any(), any(), anyInt());
        verify(repo).regularNotServing(eq(CID), any(), any(), anyInt(), any(), anyInt());
        verify(repo).lapsedGivers(eq(CID), any(), any(), any(), any(), anyInt());
        verify(repo).connectNotFollowedUp(eq(CID), any(), any(), anyBoolean(), any(), anyInt());
        verifyNoMoreInteractions(repo);
    }

    @Test @DisplayName("Follow-ups ownership: admins see every visitor's task, others only tasks assigned to them")
    void ownership() {
        ask("visitors with no follow up", access("Admin", true, true, true, true), BOTH);
        verify(repo).connectNotFollowedUp(eq(CID), any(), any(), eq(true), eq("pat"), anyInt());
        reset(repo);
        Map<String, Object> m = ask("visitors with no follow up", access("User", false, true, true, false), BOTH);
        verify(repo).connectNotFollowedUp(eq(CID), any(), any(), eq(false), eq("pat"), anyInt());
        assertThat((String) m.get("answer")).contains("only follow-ups assigned to you");
    }

    /* ─────────────────── definitions and windows ─────────────────── */

    @Test @DisplayName("defaults: regular = 3+ days in 12 weeks, lapsed = none in 4 weeks; shown with the answer")
    void defaults() {
        Map<String, Object> m = ask("regulars we haven't seen", admin(), BOTH);
        verify(repo).lapsedAttenders(CID, LocalDate.of(2026, 7, 12), LocalDate.of(2026, 10, 3), 3, LocalDate.of(2026, 10, 4), TODAY, 21);
        @SuppressWarnings("unchecked") List<String> defs = (List<String>) m.get("definitions");
        assertThat(defs.get(0)).contains("3 or more separate days").contains("12 weeks");
        assertThat(defs.get(1)).contains("since Oct 4, 2026");
        assertThat(m.get("sources")).isEqualTo(List.of("Attendance"));
        assertThat(svc.thresholds()).isEqualTo(AiCrossReferenceService.Thresholds.defaults());
    }

    @Test @DisplayName("thresholds are configurable (and the answer says so)")
    void configurable() {
        svc.regularMinDays = 4; svc.regularWindowWeeks = 8; svc.lapsedWeeks = 2;
        Map<String, Object> m = ask("regulars we haven't seen", admin(), BOTH);
        verify(repo).lapsedAttenders(CID, LocalDate.of(2026, 8, 23), LocalDate.of(2026, 10, 17), 4, LocalDate.of(2026, 10, 18), TODAY, 21);
        assertThat(((List<?>) m.get("definitions")).get(0).toString()).contains("4 or more separate days").contains("8 weeks");
    }

    @Test @DisplayName("Easter: last Easter BEFORE the 'since' month; otherwise since Easter itself")
    void easter() {
        ask("Who came last Easter but hasn't attended since February?", admin(), BOTH);
        verify(repo).lapsedAttenders(CID, LocalDate.of(2025, 4, 20), LocalDate.of(2025, 4, 20), 1, LocalDate.of(2026, 2, 1), TODAY, 21);
        reset(repo);
        ask("who came on Easter but hasn't attended since", admin(), BOTH);
        verify(repo).lapsedAttenders(CID, LocalDate.of(2026, 4, 5), LocalDate.of(2026, 4, 5), 1, LocalDate.of(2026, 4, 6), TODAY, 21);
        reset(repo);
        ask("who came at Christmas but hasn't attended in the last two months", admin(), BOTH);
        verify(repo).lapsedAttenders(CID, LocalDate.of(2025, 12, 24), LocalDate.of(2025, 12, 25), 1, LocalDate.of(2026, 9, 1), TODAY, 21);
    }

    @Test @DisplayName("Easter dates")
    void easterDates() throws Exception {
        Method e = AiCrossReferenceService.class.getDeclaredMethod("easter", int.class); e.setAccessible(true);
        assertThat(e.invoke(null, 2024)).isEqualTo(LocalDate.of(2024, 3, 31));
        assertThat(e.invoke(null, 2025)).isEqualTo(LocalDate.of(2025, 4, 20));
        assertThat(e.invoke(null, 2026)).isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(e.invoke(null, 2027)).isEqualTo(LocalDate.of(2027, 3, 28));
    }

    @Test @DisplayName("a window longer than max-range-days asks to narrow, without querying")
    void tooWide() {
        svc.maxRangeDays = 60;
        Map<String, Object> m = ask("who came on easter but hasnt attended since", admin(), BOTH);
        assertThat(m.get("intent")).isEqualTo("narrow");
        verifyNoInteractions(repo);
    }

    @Test @DisplayName("giving periods: year (default), month, quarter")
    void givingPeriods() {
        ask("members who gave last year but not this year", admin(), BOTH);
        verify(repo).lapsedGivers(CID, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1), TODAY, 21);
        ask("who gave last month but not this month", admin(), BOTH);
        verify(repo).lapsedGivers(CID, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1), TODAY, 21);
        ask("who gave last quarter but not this quarter", admin(), BOTH);
        verify(repo).lapsedGivers(CID, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1), TODAY, 21);
    }

    /* ─────────────────── output ─────────────────── */

    @Test @DisplayName("not-in-group: unverified same-name matches are shown separately and counted; never treated as members")
    void unverified() {
        when(repo.regularNotInGroup(eq(CID), any(), any(), anyInt(), anyInt())).thenReturn(List.of(
                person(1, "Ann", "Lee", TODAY, 5, null, "North", 2),
                person(2, "Bob", "Ray", TODAY.minusDays(7), 3, "Youth Group", "South", 2)));
        when(repo.countNotInGroupWithNameMatch(eq(CID), any(), any(), anyInt())).thenReturn(1L);
        Map<String, Object> m = ask("regular attenders not in any group", admin(), BOTH);
        assertThat(m.get("total")).isEqualTo(2L);
        assertThat(m.get("columns")).isEqualTo(List.of("Name", "Last attended", "Days attended", "Possible match (unverified)"));
        assertThat(m.get("rows")).isEqualTo(List.of(List.of("Ann Lee", "Oct 31, 2026", "5", ""), List.of("Bob Ray", "Oct 24, 2026", "3", "Youth Group")));
        assertThat((String) m.get("answer")).startsWith("2 regular attenders are not confirmed in any group.").contains("1 of them has an unverified same-name match");
        assertThat(m.get("sources")).isEqualTo(List.of("Attendance", "Groups"));
        assertThat(m.get("summary")).isEqualTo(Map.of("Not in a group", "2", "Unverified name matches", "1"));
    }

    @Test @DisplayName("by campus: a Campus column, rows sorted by campus; capped at max-rows with the full total")
    void campusAndCap() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 21; i++) rows.add(person(i, "P" + i, "X", TODAY, 3, null, i % 2 == 0 ? "South" : "North", 40));
        when(repo.regularNotServing(eq(CID), any(), any(), anyInt(), any(), anyInt())).thenReturn(rows);
        Map<String, Object> m = ask("regular attenders not serving by campus", admin(), BOTH);
        assertThat(m.get("columns")).isEqualTo(List.of("Name", "Last attended", "Days attended", "Campus"));
        @SuppressWarnings("unchecked") List<List<String>> out = (List<List<String>>) m.get("rows");
        assertThat(out).hasSize(20);
        assertThat(out.get(0).get(3)).isEqualTo("North");
        assertThat(out.get(19).get(3)).isEqualTo("South");
        assertThat(m.get("truncated")).isEqualTo(true);
        assertThat(m.get("total")).isEqualTo(40L);
        assertThat((String) m.get("answer")).contains("40 regular attenders").contains("Showing the first 20.");
    }

    @Test @DisplayName("visitors: tasks open 14+ days are flagged; no task says so")
    void visitorsOutput() {
        when(repo.connectNotFollowedUp(eq(CID), any(), any(), anyBoolean(), any(), anyInt())).thenReturn(List.<Object[]>of(
                new Object[] { "Ann", "Lee", java.sql.Timestamp.valueOf("2026-10-10 10:00:00"), "New", null, null, null, 2L },
                new Object[] { "Bob", "Ray", java.sql.Timestamp.valueOf("2026-10-05 10:00:00"), "Assigned", "PENDING", "pat", java.sql.Timestamp.valueOf("2026-10-05 10:00:00"), 2L }));
        Map<String, Object> m = ask("first time visitors this month with no follow up", admin(), BOTH);
        assertThat(m.get("rows")).isEqualTo(List.of(
                List.of("Ann Lee", "Oct 10, 2026", "No follow-up task", "Unassigned"),
                List.of("Bob Ray", "Oct 5, 2026", "Open 26 days ⚠", "pat")));
        assertThat((String) m.get("answer")).contains("2 Connect With Us visitors haven't been followed up").contains("1 has a task open 14+ days");
        assertThat(((List<?>) m.get("definitions")).toString()).contains("Visitors who only checked in through Attendance aren't included");
    }

    @Test @DisplayName("statement budget: each question is at most two bounded statements")
    void budget() {
        when(repo.regularNotInGroup(any(), any(), any(), anyInt(), anyInt())).thenReturn(List.<Object[]>of(person(1, "A", "B", TODAY, 3, null, null, 1)));
        for (String s : List.of("regular attenders not in any group", "regulars we haven't seen", "visitors with no follow up",
                "members who gave last year but not this year", "regular attenders not serving")) {
            reset(repo);
            when(repo.regularNotInGroup(any(), any(), any(), anyInt(), anyInt())).thenReturn(List.<Object[]>of(person(1, "A", "B", TODAY, 3, null, null, 1)));
            ask(s, admin(), BOTH);
            assertThat(Mockito.mockingDetails(repo).getInvocations().size()).as(s).isLessThanOrEqualTo(2);
        }
    }

    /* ─────────────────── the endpoint service ─────────────────── */

    @Test @DisplayName("through AiDataSearchService: cross questions win over the single-module volunteer and giving intents")
    void viaDataSearch() {
        FamilyMemberRepository members = mock(FamilyMemberRepository.class);
        IncomeRepository income = mock(IncomeRepository.class);
        AiDataSearchService ds = new AiDataSearchService(members, income, mock(VolunteerProfileRepository.class), new AiAccessPolicy());
        ds.setCrossReference(svc);
        MockHttpServletRequest req = staff("Admin", null);
        assertThat(ds.answer(req, CID, "Regular attenders who aren't volunteering").get("intent")).isEqualTo("crossref.notServing");
        assertThat(ds.answer(req, CID, "Who gave last year but not this year?").get("intent")).isEqualTo("crossref.lapsedGivers");
        assertThat(ds.answer(staff("Accountant", null), CID, "Regular attenders not in any group").get("missing")).isEqualTo(List.of("Attendance", "Groups"));
        verifyNoInteractions(income);
    }

    /* ─────────────────── AiAccessPolicy.crossAccess mirrors the two pages ─────────────────── */

    static MockHttpServletRequest staff(String role, String privileges) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "pat"); s.setAttribute("role", role); s.setAttribute("appClientId", CID);
        if (privileges != null) s.setAttribute("privileges", privileges);
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
    }

    @Test @DisplayName("crossAccess: /attendance = SuperAdmin/Admin/User + general.attendance + plan; /groups = SuperAdmin/Admin + admin.groups + plan")
    void crossAccessRules() {
        AiAccessPolicy p = new AiAccessPolicy();
        assertThat(p.crossAccess(staff("SuperAdmin", null), CID)).isEqualTo(new CrossAccess(true, true));
        assertThat(p.crossAccess(staff("Admin", null), CID)).isEqualTo(new CrossAccess(true, true));
        assertThat(p.crossAccess(staff("User", null), CID)).isEqualTo(new CrossAccess(true, false));
        assertThat(p.crossAccess(staff("Accountant", null), CID)).isEqualTo(new CrossAccess(false, false));
        assertThat(p.crossAccess(staff("Admin", "{\"general.attendance\":false}"), CID)).isEqualTo(new CrossAccess(false, true));
        assertThat(p.crossAccess(staff("Admin", "{\"admin.groups\":false}"), CID)).isEqualTo(new CrossAccess(true, false));

        MockHttpSession m = new MockHttpSession();
        m.setAttribute("username", "mbr"); m.setAttribute("role", "Member"); m.setAttribute("memberId", 7);
        m.setAttribute("memberPrivileges", "{\"admin.groups\":true,\"general.attendance\":true}");
        MockHttpServletRequest mr = new MockHttpServletRequest(); mr.setSession(m);
        assertThat(p.crossAccess(mr, CID)).as("Member portal, even with grants").isEqualTo(CrossAccess.none());

        MockHttpSession c = new MockHttpSession();
        c.setAttribute("username", "church"); c.setAttribute("role", "church"); c.setAttribute("church", Boolean.TRUE);
        MockHttpServletRequest cr = new MockHttpServletRequest(); cr.setSession(c);
        assertThat(p.crossAccess(cr, CID)).isEqualTo(CrossAccess.none());
        assertThat(p.crossAccess(new MockHttpServletRequest(), CID)).as("no session").isEqualTo(CrossAccess.none());

        SubscriptionService subs = mock(SubscriptionService.class);
        when(subs.isFeatureEnabled(eq(CID), eq("attendance"))).thenReturn(false);
        when(subs.isFeatureEnabled(eq(CID), eq("groups"))).thenReturn(true);
        assertThat(new AiAccessPolicy(subs).crossAccess(staff("Admin", null), CID)).as("plan without Attendance").isEqualTo(new CrossAccess(false, true));
    }

    @SuppressWarnings("unused") private static final BigDecimal UNUSED = BigDecimal.ZERO;
    @SuppressWarnings("unused") private static final Date UNUSED_D = null;
}
