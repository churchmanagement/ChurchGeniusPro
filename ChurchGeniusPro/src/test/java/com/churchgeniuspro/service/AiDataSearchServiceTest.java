package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.hibernate.VolunteerProfile;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.VolunteerProfileRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

/**
 * Unit tests for the natural-language AI data search: intent routing, date
 * parsing, name matching, and — most importantly — permission gating.
 */
class AiDataSearchServiceTest {

    private static final String CID = "CHR-test";

    private FamilyMemberRepository memberRepo;
    private IncomeRepository incomeRepo;
    private VolunteerProfileRepository volunteerRepo;
    private AiDataSearchService svc;

    private HttpServletRequest req;
    private HttpSession session;

    @BeforeEach
    void setUp() {
        memberRepo    = mock(FamilyMemberRepository.class);
        incomeRepo    = mock(IncomeRepository.class);
        volunteerRepo = mock(VolunteerProfileRepository.class);
        svc = new AiDataSearchService(memberRepo, incomeRepo, volunteerRepo);

        req     = mock(HttpServletRequest.class);
        session = mock(HttpSession.class);
        when(req.getSession(false)).thenReturn(session);
        when(memberRepo.findAllWithFamilyByAppUser(anyString())).thenReturn(List.of(
                member(1, "Anson", "Mathew", 5, 31, 1990, 6, 12, 2015),
                member(2, "Maria", "Joseph", 5, 3, null, null, null, null),
                member(3, "Peter", "Thomas", 11, 20, 1985, null, null, null)));
    }

    private void asRole(String role) {
        when(session.getAttribute("role")).thenReturn(role);
        when(session.getAttribute("username")).thenReturn("staff");
        when(session.getAttribute("church")).thenReturn(null);
        when(session.getAttribute("memberId")).thenReturn(null);
        when(session.getAttribute("privileges")).thenReturn(null);       // no restrictions
    }

    private void asMemberPortal() {
        when(session.getAttribute("role")).thenReturn("Member");
        when(session.getAttribute("username")).thenReturn(null);
        when(session.getAttribute("church")).thenReturn(null);
        when(session.getAttribute("memberId")).thenReturn(42);
        when(session.getAttribute("memberPrivileges")).thenReturn(null); // no restrictions
    }

    private static FamilyMember member(int id, String first, String last,
                                       Integer bm, Integer bd, Integer by,
                                       Integer am, Integer ad, Integer ay) {
        FamilyMember m = new FamilyMember();
        m.setId(id);
        m.setFirstName(first);
        m.setLastName(last);
        m.setBirthdayMonth(bm); m.setBirthdayDay(bd); m.setBirthdayYear(by);
        m.setAnniversaryMonth(am); m.setAnniversaryDay(ad); m.setAnniversaryYear(ay);
        return m;
    }

    private static Income income(Integer memberId, String fund, String amount, LocalDate date) {
        Income i = new Income();
        if (memberId != null) {
            FamilyMember m = new FamilyMember();
            m.setId(memberId);
            i.setMember(m);
        }
        SubSource ss = new SubSource();
        ss.setSourceName(fund);
        i.setSubSource(ss);
        i.setAmount(new BigDecimal(amount));
        i.setIncomeDate(date);
        return i;
    }

    /* ── intent routing ─────────────────────────────────────────────── */

    @Test
    void unrelatedQuestionIsNotHandled() {
        asRole("Admin");
        Map<String, Object> r = svc.answer(req, CID, "How do I add a family?");
        assertEquals(false, r.get("handled"));
    }

    @Test
    void personBirthdayLookup() {
        asRole("Admin");
        Map<String, Object> r = svc.answer(req, CID, "When is Anson's birthday?");
        assertEquals(true, r.get("handled"));
        assertEquals(false, r.get("denied"));
        String a = String.valueOf(r.get("answer"));
        assertTrue(a.contains("May 31"), a);
        assertTrue(a.contains("Anson Mathew"), a);
    }

    @Test
    void birthdaysAndAnniversariesInMay() {
        asRole("Admin");
        Map<String, Object> r = svc.answer(req, CID, "List the birthdays and anniversaries in May.");
        String a = String.valueOf(r.get("answer"));
        assertTrue(a.contains("Anson Mathew"), a);
        assertTrue(a.contains("Maria Joseph"), a);
        assertFalse(a.contains("Peter Thomas"), a);   // November birthday
        assertTrue(a.toLowerCase().contains("anniversar"), a);
    }

    @Test
    void incomeForMarch() {
        asRole("Accountant");
        int year = LocalDate.now().getYear();
        when(incomeRepo.findRangeByAppClientId(CID, LocalDate.of(year, 3, 1), LocalDate.of(year, 3, 31)))
                .thenReturn(List.of(
                        income(1, "Tithe", "100.00", LocalDate.of(year, 3, 2)),
                        income(null, "Offering", "50.50", LocalDate.of(year, 3, 9))));
        Map<String, Object> r = svc.answer(req, CID, "What was the income for March?");
        String a = String.valueOf(r.get("answer"));
        assertEquals(true, r.get("handled"));
        assertTrue(a.contains("$150.50"), a);
        assertTrue(a.contains("2 transactions"), a);
    }

    @Test
    void personTitheThisMonth() {
        asRole("Admin");
        LocalDate today = com.churchgeniuspro.util.AppClock.today();
        LocalDate from = today.withDayOfMonth(1), to = from.plusMonths(1).minusDays(1);
        // One aggregate + the newest rows for member 1 only, tithe funds only (titheOnly = true).
        when(incomeRepo.aiMemberGivingTotals(CID, 1, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to), true))
                .thenReturn(List.<Object[]>of(new Object[] { new BigDecimal("200.00"), 1L }));
        when(incomeRepo.aiMemberGivingRows(CID, 1, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to), true, 5))
                .thenReturn(List.<Object[]>of(new Object[] { java.sql.Date.valueOf(from.plusDays(3)), new BigDecimal("200.00"), "Tithe", null }));
        Map<String, Object> r = svc.answer(req, CID, "Did Anson give tithe this month?");
        String a = String.valueOf(r.get("answer"));
        assertTrue(a.startsWith("Yes"), a);
        assertTrue(a.contains("$200.00"), a);          // tithe only — the aggregate was asked for tithe funds
        assertFalse(a.contains("$275"), a);
        verify(incomeRepo, never()).findRangeByAppClientId(anyString(), any(), any());   // no full-period load any more
    }

    @Test
    void personTitheNone() {
        asRole("Admin");
        when(incomeRepo.aiMemberGivingTotals(anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(List.<Object[]>of(new Object[] { BigDecimal.ZERO, 0L }));
        Map<String, Object> r = svc.answer(req, CID, "Did Maria give tithe this month?");
        assertTrue(String.valueOf(r.get("answer")).startsWith("No"), String.valueOf(r.get("answer")));
    }

    /* ── synonyms and different ways of asking (one person's giving) ─────────────── */

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "What is Anson's contribution this month?",
        "How much has Anson donated this month?",
        "How much did Anson give this month?",
        "Show Anson's donations this month.",
        "Anson's offerings this month",
        "Has Anson given anything this month?",
        "What gifts did Anson Mathew make this month?",
        "contributions from Anson this month",
    })
    void everyWayOfAskingAnswersForThatPersonAndPeriod(String question) {
        asRole("Admin");
        LocalDate today = com.churchgeniuspro.util.AppClock.today();
        LocalDate from = today.withDayOfMonth(1), to = from.plusMonths(1).minusDays(1);
        when(incomeRepo.aiMemberGivingTotals(CID, 1, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to), false))
                .thenReturn(List.<Object[]>of(new Object[] { new BigDecimal("125.00"), 2L }));
        when(incomeRepo.aiMemberGivingRows(CID, 1, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to), false, 5))
                .thenReturn(List.<Object[]>of(new Object[] { java.sql.Date.valueOf(from), new BigDecimal("100.00"), "Tithe", "pi_1" },
                                              new Object[] { java.sql.Date.valueOf(from), new BigDecimal("25.00"), "Building", null }));
        Map<String, Object> r = svc.answer(req, CID, question);
        String a = String.valueOf(r.get("answer"));
        assertEquals("contributions", r.get("intent"), question);
        assertTrue(a.contains("Anson Mathew") && a.contains("$125.00") && a.contains("2 contributions") && a.contains("this month"), question + " → " + a);
        @SuppressWarnings("unchecked") Map<String, String> period = (Map<String, String>) r.get("period");
        assertEquals(from.toString(), period.get("from")); assertEquals(to.toString(), period.get("to"));
        verify(incomeRepo, never()).aiIncomeTotals(anyString(), any(), any());          // never the church-wide total
    }

    @Test
    void personAndExplicitPeriodAreBothExtracted() {
        asRole("Admin");
        int y = com.churchgeniuspro.util.AppClock.today().getYear();
        when(incomeRepo.aiMemberGivingTotals(CID, 3, java.sql.Date.valueOf(LocalDate.of(y, 9, 1)), java.sql.Date.valueOf(LocalDate.of(y, 9, 30)), false))
                .thenReturn(List.<Object[]>of(new Object[] { new BigDecimal("60.00"), 1L }));
        when(incomeRepo.aiMemberGivingRows(anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        Map<String, Object> r = svc.answer(req, CID, "How much did Peter Thomas give in September?");
        assertTrue(String.valueOf(r.get("answer")).contains("Peter Thomas gave $60.00 in September"), String.valueOf(r.get("answer")));
    }

    @Test
    void twoMembersWithTheSameFirstNameAreNotGuessed() {
        asRole("Admin");
        when(memberRepo.findAllWithFamilyByAppUser(anyString())).thenReturn(List.of(
                member(1, "Anson", "Mathew", null, null, null, null, null, null),
                member(7, "Anson", "Lee", null, null, null, null, null, null)));
        Map<String, Object> r = svc.answer(req, CID, "How much did Anson give this month?");
        assertEquals("clarify", r.get("intent"));
        assertTrue(String.valueOf(r.get("answer")).contains("Anson Mathew") && String.valueOf(r.get("answer")).contains("Anson Lee"));
        verify(incomeRepo, never()).aiMemberGivingTotals(anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void unknownPersonIsSaidSoNeverAnsweredWithTheChurchTotal() {
        asRole("Admin");
        Map<String, Object> r = svc.answer(req, CID, "How much did Zebulon give this month?");
        assertTrue(String.valueOf(r.get("answer")).contains("could not find an active member named “Zebulon”"), String.valueOf(r.get("answer")));
        verify(incomeRepo, never()).aiIncomeTotals(anyString(), any(), any());
        verify(incomeRepo, never()).findRangeByAppClientId(anyString(), any(), any());
    }

    @Test
    void onePersonsGivingNeedsTheIncomePageRuleEvenWithReports() {
        asRole("Accountant");
        when(session.getAttribute("privileges")).thenReturn("{\"accounting.income\":false}");   // Reports yes, Income records no
        for (String q : new String[] { "How much did Anson give this month?", "How much did Zebulon donate this month?" }) {
            Map<String, Object> r = svc.answer(req, CID, q);
            assertEquals(true, r.get("denied"), q);
        }
        verify(incomeRepo, never()).aiMemberGivingTotals(anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void memberPortalNeverGetsAnotherPersonsGiving() {
        asMemberPortal();
        Map<String, Object> r = svc.answer(req, CID, "How much did Anson give this month?");
        assertEquals(true, r.get("denied"));
        verify(incomeRepo, never()).aiMemberGivingTotals(anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void volunteersList() {
        asRole("Admin");
        VolunteerProfile p = new VolunteerProfile();
        p.setFamilyMemberId(1);
        p.setSkills("Music");
        when(volunteerRepo.findByAppClientIdAndDeleteFlagFalseOrderByFamilyMemberIdAsc(CID))
                .thenReturn(List.of(p));
        Map<String, Object> r = svc.answer(req, CID, "Who are the volunteers?");
        String a = String.valueOf(r.get("answer"));
        assertTrue(a.contains("Anson Mathew"), a);
        assertTrue(a.contains("Music"), a);
    }

    /* ── permission gating ──────────────────────────────────────────── */

    @Test
    void memberPortalDeniedFinance() {
        asMemberPortal();
        Map<String, Object> r = svc.answer(req, CID, "What was the income for March?");
        assertEquals(true, r.get("handled"));
        assertEquals(true, r.get("denied"));
        assertEquals(AiDataSearchService.DENIED_MSG, r.get("answer"));
    }

    @Test
    void memberPortalDeniedVolunteers() {
        asMemberPortal();
        Map<String, Object> r = svc.answer(req, CID, "Who are the volunteers?");
        assertEquals(true, r.get("denied"));
    }

    @Test
    void memberPortalAllowedBirthdaysOnlyWithDirectoryGranted() {
        asMemberPortal();   // no memberPrivileges → the Directory tab is not explicitly granted → denied
        Map<String, Object> r = svc.answer(req, CID, "When is Anson's birthday?");
        assertEquals(true, r.get("denied"));
        when(session.getAttribute("memberPrivileges")).thenReturn("{\"more.aiassistant\":true,\"member.directory\":true}");
        r = svc.answer(req, CID, "When is Anson's birthday?");
        assertEquals(false, r.get("denied"));
        assertTrue(String.valueOf(r.get("answer")).contains("May 31"));
    }

    @Test
    void kidsPortalSessionGetsNothing() {
        asMemberPortal();
        when(session.getAttribute("memberRole")).thenReturn("Daughter");
        when(session.getAttribute("memberPrivileges")).thenReturn("{\"more.aiassistant\":true,\"member.directory\":true}");
        Map<String, Object> r = svc.answer(req, CID, "When is Anson's birthday?");
        assertEquals(true, r.get("denied"));
        assertEquals(AiDataSearchService.DENIED_MSG, r.get("answer"));
    }

    @Test
    void userRoleDeniedMembers() {
        asRole("User");     // /viewfamily denies the User role → so does the AI
        Map<String, Object> r = svc.answer(req, CID, "List the birthdays in May");
        assertEquals(true, r.get("denied"));
        verify(memberRepo, never()).findAllWithFamilyByAppUser(anyString());
    }

    @Test
    void staffWithRevokedFamilyPermDenied() {
        asRole("Admin");
        when(session.getAttribute("privileges")).thenReturn("{\"admin.family\":false}");
        Map<String, Object> r = svc.answer(req, CID, "List the birthdays in May");
        assertEquals(true, r.get("denied"));
        assertEquals(AiDataSearchService.DENIED_MSG, r.get("answer"));
    }

    @Test
    void userRoleDeniedFinance() {
        asRole("User");     // finance requires SuperAdmin/Admin/Accountant
        Map<String, Object> r = svc.answer(req, CID, "What was the income for March?");
        assertEquals(true, r.get("denied"));
    }

    /* ── date parsing ───────────────────────────────────────────────── */

    @Test
    void parsePeriods() {
        LocalDate today = LocalDate.of(2026, 7, 16);
        AiDataSearchService.DateRange r = svc.parsePeriod("what was the income for march", today);
        assertEquals(LocalDate.of(2026, 3, 1), r.from);
        assertEquals(LocalDate.of(2026, 3, 31), r.to);

        r = svc.parsePeriod("income for march 2025", today);
        assertEquals(LocalDate.of(2025, 3, 1), r.from);

        r = svc.parsePeriod("income last month", today);
        assertEquals(LocalDate.of(2026, 6, 1), r.from);
        assertEquals(LocalDate.of(2026, 6, 30), r.to);

        r = svc.parsePeriod("income in 2025", today);
        assertEquals(LocalDate.of(2025, 1, 1), r.from);
        assertEquals(LocalDate.of(2025, 12, 31), r.to);

        r = svc.parsePeriod("birthdays next sunday", today);
        assertEquals(LocalDate.of(2026, 7, 19), r.from);   // Jul 16 2026 is a Thursday
        assertEquals(r.from, r.to);

        assertNull(svc.parsePeriod("no period here", today));
    }
}
