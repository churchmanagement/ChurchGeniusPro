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
        LocalDate today = LocalDate.now();
        LocalDate from = today.withDayOfMonth(1);
        when(incomeRepo.findRangeByAppClientId(CID, from, from.plusMonths(1).minusDays(1)))
                .thenReturn(List.of(
                        income(1, "Tithe", "200.00", from.plusDays(3)),
                        income(1, "Building Fund", "75.00", from.plusDays(4)),
                        income(3, "Tithe", "60.00", from.plusDays(5))));
        Map<String, Object> r = svc.answer(req, CID, "Did Anson give tithe this month?");
        String a = String.valueOf(r.get("answer"));
        assertTrue(a.startsWith("Yes"), a);
        assertTrue(a.contains("$200.00"), a);          // tithe only — Building Fund excluded
        assertFalse(a.contains("$275"), a);
    }

    @Test
    void personTitheNone() {
        asRole("Admin");
        when(incomeRepo.findRangeByAppClientId(anyString(), any(), any())).thenReturn(List.of());
        Map<String, Object> r = svc.answer(req, CID, "Did Maria give tithe this month?");
        assertTrue(String.valueOf(r.get("answer")).startsWith("No"), String.valueOf(r.get("answer")));
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
    void memberPortalAllowedBirthdays() {
        asMemberPortal();   // directory allowed by default (no memberPrivileges)
        Map<String, Object> r = svc.answer(req, CID, "When is Anson's birthday?");
        assertEquals(false, r.get("denied"));
        assertTrue(String.valueOf(r.get("answer")).contains("May 31"));
    }

    @Test
    void staffWithRevokedFamilyPermDenied() {
        asRole("User");
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
