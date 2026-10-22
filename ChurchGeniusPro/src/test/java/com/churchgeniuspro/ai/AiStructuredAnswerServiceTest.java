package com.churchgeniuspro.ai;

import com.churchgeniuspro.hibernate.FollowUp;
import com.churchgeniuspro.hibernate.KmChild;
import com.churchgeniuspro.hibernate.KmClassroom;
import com.churchgeniuspro.hibernate.Meeting;
import com.churchgeniuspro.hibernate.MeetingType;
import com.churchgeniuspro.hibernate.PledgeCampaign;
import com.churchgeniuspro.hibernate.PrayerRequest;
import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.hibernate.VolunteerAssignment;
import com.churchgeniuspro.hibernate.VolunteerRole;
import com.churchgeniuspro.repository.ChurchEventRepository;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FollowUpRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.KmChildRepository;
import com.churchgeniuspro.repository.KmClassroomRepository;
import com.churchgeniuspro.repository.MeetingRepository;
import com.churchgeniuspro.repository.MeetingSkipDateRepository;
import com.churchgeniuspro.repository.PledgeCampaignRepository;
import com.churchgeniuspro.repository.PledgeMemberRepository;
import com.churchgeniuspro.repository.PrayerRequestRepository;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import com.churchgeniuspro.repository.VolunteerAssignmentRepository;
import com.churchgeniuspro.repository.VolunteerRoleRepository;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.AiDataSearchService;
import com.churchgeniuspro.service.AiStructuredAnswerService;
import com.churchgeniuspro.service.MeetingService;
import com.churchgeniuspro.service.PledgeTotalsService;
import com.churchgeniuspro.service.ReminderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI Search Phase B — each structured intent: allowed only by its AiAccess flag (no repository
 * is touched when denied), bounded by max-rows / max-range-days, tenant-scoped, and returning
 * only the fields the answer needs (no descriptions, tokens, medical notes or contacts).
 */
@DisplayName("AI Search — structured, bounded data answers")
class AiStructuredAnswerServiceTest {

    static final String CID = "CHR-1";
    static final LocalDate TODAY = LocalDate.of(2026, 10, 21);

    IncomeRepository incomeRepo; ExpenseRepository expenseRepo; FamilyMemberRepository memberRepo; PrayerRequestRepository prayerRepo;
    FollowUpRepository followUpRepo; VolunteerAssignmentRepository assignmentRepo; VolunteerRoleRepository roleRepo; ChurchEventRepository eventRepo;
    ReminderService reminderService; PublicScreenLinkRepository linkRepo; KmChildRepository kmChildRepo; KmClassroomRepository kmClassRepo;
    MeetingRepository meetingRepo; MeetingSkipDateRepository skipRepo; MeetingService meetingService; PledgeCampaignRepository campaignRepo; PledgeMemberRepository pledgeRepo;
    AiStructuredAnswerService svc;

    /** Everything allowed (an unrestricted Admin). */
    static AiAccess all() {
        return new AiAccess("Admin", "anson", false, false, false, true, true, true, true, true, true, true, true, true, true, true, true, true, true);
    }
    /** Nothing allowed, but not a Kids session (e.g. a User without any of the pages). */
    static AiAccess nothing() {
        return new AiAccess("User", "pat", false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false, false);
    }
    static AiAccess only(String flag) {
        boolean members = flag.equals("members"), inc = flag.equals("incomeRecords"), exp = flag.equals("expenseRecords"), rep = flag.equals("reports"),
                vol = flag.equals("volunteers"), mtg = flag.equals("meetings"), pr = flag.equals("prayer"), fu = flag.equals("followUps"), ev = flag.equals("events"),
                rem = flag.equals("reminders"), pl = flag.equals("pledges"), km = flag.equals("kidsMinistry"), pub = flag.equals("publicLinks");
        return new AiAccess("User", "pat", false, false, false, members, inc, exp, rep, vol, mtg, pr, fu, false, ev, rem, pl, km, pub);
    }

    @BeforeEach
    void setUp() {
        incomeRepo = mock(IncomeRepository.class); expenseRepo = mock(ExpenseRepository.class); memberRepo = mock(FamilyMemberRepository.class);
        prayerRepo = mock(PrayerRequestRepository.class); followUpRepo = mock(FollowUpRepository.class); assignmentRepo = mock(VolunteerAssignmentRepository.class);
        roleRepo = mock(VolunteerRoleRepository.class); eventRepo = mock(ChurchEventRepository.class); reminderService = mock(ReminderService.class);
        linkRepo = mock(PublicScreenLinkRepository.class); kmChildRepo = mock(KmChildRepository.class); kmClassRepo = mock(KmClassroomRepository.class);
        meetingRepo = mock(MeetingRepository.class); skipRepo = mock(MeetingSkipDateRepository.class); meetingService = mock(MeetingService.class);
        campaignRepo = mock(PledgeCampaignRepository.class); pledgeRepo = mock(PledgeMemberRepository.class);
        svc = new AiStructuredAnswerService(incomeRepo, expenseRepo, memberRepo, prayerRepo, followUpRepo, assignmentRepo, roleRepo, eventRepo, reminderService,
                linkRepo, kmChildRepo, kmClassRepo, meetingRepo, skipRepo, meetingService, campaignRepo, new PledgeTotalsService(pledgeRepo, incomeRepo),
                new com.churchgeniuspro.service.ReportQueryService(incomeRepo, expenseRepo));
        svc.maxRows = 3; svc.maxRangeDays = 400;
    }

    static Object[] incRow(String d, String first, String last, String guest, String fund, String amount, String method, String ref) {
        return new Object[] { Date.valueOf(d), first, last, guest, fund, "Church Fund", new BigDecimal(amount), method, ref };
    }
    @SuppressWarnings("unchecked") static List<List<String>> rows(Map<String, Object> m) { return (List<List<String>>) m.get("rows"); }
    static String answer(Map<String, Object> m) { return String.valueOf(m.get("answer")); }

    /* ── contributors / income / expenses / transactions ─────────────────────── */

    @Test @DisplayName("contributors: Income page rule; guests included; donation rows labelled; bounded to max-rows with the exact count from the aggregate")
    void contributors() {
        List<Object[]> four = List.of(incRow("2026-10-05", "Anson", "Mathew", null, "Tithe", "100", "Cash", null),
                incRow("2026-10-04", null, null, "Walk-in", "Offering", "20", "Cash", null),
                incRow("2026-10-03", "Hanna", "Thomas", null, "Tithe", "50", "Card", "pi_abc"),
                incRow("2026-10-02", "Peter", "Thomas", null, "Tithe", "10", "Cash", null));
        when(incomeRepo.aiIncomeRows(eq(CID), any(), any(), eq(4))).thenReturn(four);
        when(incomeRepo.aiIncomeTotals(eq(CID), any(), any())).thenReturn(List.<Object[]>of(new Object[] { new BigDecimal("180"), 4L, new BigDecimal("50") }));
        Map<String, Object> r = svc.route("who are the contributors this month", all(), CID, TODAY);
        assertThat(r.get("intent")).isEqualTo("contributors");
        assertThat(rows(r)).hasSize(3);
        assertThat(r.get("truncated")).isEqualTo(true);
        assertThat(r.get("total")).isEqualTo(4L);
        assertThat(rows(r).get(1).get(1)).isEqualTo("Walk-in (guest)");
        assertThat(rows(r).get(2).get(4)).isEqualTo("Card (online donation)");
        assertThat(answer(r)).contains("4 contributions").contains("Showing the first 3");
        assertThat(((Map<?, ?>) r.get("period")).get("from")).isEqualTo("2026-10-01");
        assertThat(((Map<?, ?>) r.get("link")).get("url")).isEqualTo("/income");
        // needs the Income page, not just Reports
        Map<String, Object> d = svc.route("list the contributors this month", only("reports"), CID, TODAY);
        assertThat(d.get("denied")).isEqualTo(true);
        verify(incomeRepo, org.mockito.Mockito.times(1)).aiIncomeRows(anyString(), any(), any(), eq(4));   // only the allowed call ran
    }

    @Test @DisplayName("income total: Reports page rule; gross online donations reported separately; empty is explicit, not zero-by-silence")
    void incomeTotal() {
        when(incomeRepo.aiIncomeTotals(eq(CID), any(), any())).thenReturn(List.<Object[]>of(new Object[] { new BigDecimal("1250.50"), 7L, new BigDecimal("300") }));
        Map<String, Object> r = svc.route("how much is the total income this month", all(), CID, TODAY);
        assertThat(answer(r)).contains("$1,250.50").contains("7 transactions").contains("$300.00").contains("gross");
        assertThat(((Map<?, ?>) r.get("summary")).get("Online donations (gross)")).isEqualTo("$300.00");
        when(incomeRepo.aiIncomeTotals(eq(CID), any(), any())).thenReturn(List.<Object[]>of(new Object[] { BigDecimal.ZERO, 0L, BigDecimal.ZERO }));
        assertThat(answer(svc.route("total income last month", all(), CID, TODAY))).startsWith("No income was recorded for last month");
        assertThat(svc.route("total income this month", only("incomeRecords"), CID, TODAY).get("denied")).isEqualTo(true);
    }

    @Test @DisplayName("expenses: total needs Reports, a list needs the Expense page; processing fees shown inside the total, never netted")
    void expenses() {
        when(expenseRepo.aiExpenseTotals(eq(CID), any(), any())).thenReturn(List.<Object[]>of(new Object[] { new BigDecimal("400"), 3L, new BigDecimal("12.30") }));
        Map<String, Object> t = svc.route("how much is the expense this month", all(), CID, TODAY);
        assertThat(answer(t)).contains("$400.00").contains("$12.30").contains("processing fees");
        verify(expenseRepo, never()).aiExpenseRows(anyString(), any(), any(), anyInt());
        when(expenseRepo.aiExpenseRows(eq(CID), any(), any(), eq(4))).thenReturn(List.<Object[]>of(
                new Object[] { Date.valueOf("2026-10-09"), "General", "Utilities", new BigDecimal("387.70"), "Check", null },
                new Object[] { Date.valueOf("2026-10-08"), "General", "Processing Fees", new BigDecimal("12.30"), "Card", "pi_1:stripe-fee" }));
        Map<String, Object> l = svc.route("list the expenses this month", all(), CID, TODAY);
        assertThat(rows(l)).hasSize(2);
        assertThat(rows(l).get(1).get(4)).isEqualTo("Card (processing fee)");
        assertThat(svc.route("list the expenses this month", only("reports"), CID, TODAY).get("denied")).isEqualTo(true);
        assertThat(svc.route("how much is the expense this month", only("expenseRecords"), CID, TODAY).get("denied")).isEqualTo(true);
    }

    @Test @DisplayName("transactions: income, expenses and net stay separate; Reports page rule")
    void transactions() {
        when(incomeRepo.aiIncomeTotals(eq(CID), any(), any())).thenReturn(List.<Object[]>of(new Object[] { new BigDecimal("1000"), 2L, BigDecimal.ZERO }));
        when(expenseRepo.aiExpenseTotals(eq(CID), any(), any())).thenReturn(List.<Object[]>of(new Object[] { new BigDecimal("250"), 1L, BigDecimal.ZERO }));
        when(incomeRepo.aiIncomeRows(eq(CID), any(), any(), anyInt())).thenReturn(List.<Object[]>of(incRow("2026-10-05", "Anson", "Mathew", null, "Tithe", "1000", "Cash", null)));
        when(expenseRepo.aiExpenseRows(eq(CID), any(), any(), anyInt())).thenReturn(List.<Object[]>of(new Object[] { Date.valueOf("2026-10-09"), "General", "Rent", new BigDecimal("250"), "Check", null }));
        Map<String, Object> r = svc.route("show all transactions this month", all(), CID, TODAY);
        assertThat(answer(r)).contains("Income $1,000.00").contains("expenses $250.00").contains("net $750.00");
        assertThat(rows(r)).hasSize(2);
        assertThat(rows(r).get(0).get(0)).isEqualTo("2026-10-09");   // newest first
        assertThat(svc.route("show all transactions this month", only("incomeRecords"), CID, TODAY).get("denied")).isEqualTo(true);
    }

    @Test @DisplayName("a period wider than max-range-days is refused with a request to narrow — no query runs, no figure is invented")
    void tooWideAsksToNarrow() {
        Map<String, Object> r = svc.route("how much tithe did we receive over the last ten years", all(), CID, TODAY);
        assertThat(r.get("intent")).isEqualTo("narrow");
        assertThat(answer(r)).contains("Which date range");
        verifyNoInteractions(incomeRepo);
    }

    @Test @DisplayName("\"last two months through yesterday\" resolves on the church calendar")
    void periodParsing() {
        AiDataSearchService.DateRange r = svc.period("list the donations for the last two months through yesterday", TODAY);
        assertThat(r.to).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(r.from).isEqualTo(LocalDate.of(2026, 8, 21));
        assertThat(svc.period("income this month", TODAY).from).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(svc.period("meetings in the next 30 days", TODAY).to).isEqualTo(LocalDate.of(2026, 11, 20));
    }

    /* ── period reports (Phase C) ──────────────────────────────────────────── */

    @Test @DisplayName("income report: Reports page rule; grouped by fund with the page's half-open range (end + 1 day); link opens the report pre-filled")
    void incomeReport() {
        when(incomeRepo.reportFinancialIncomeInRange(eq(Date.valueOf("2026-10-01")), eq(Date.valueOf("2026-11-01")), eq(CID))).thenReturn(List.<Object[]>of(
                new Object[] { "Tithes & Offerings", "Tithe", new BigDecimal("900") }, new Object[] { "Tithes & Offerings", "Offering", new BigDecimal("100.50") }));
        Map<String, Object> r = svc.route("create an income report for this month", all(), CID, TODAY);
        assertThat(r.get("intent")).isEqualTo("income-report");
        assertThat(answer(r)).contains("$1,000.50").contains("2 funds");
        assertThat(rows(r)).containsExactly(List.of("Tithes & Offerings", "Tithe", "$900.00"), List.of("Tithes & Offerings", "Offering", "$100.50"));
        assertThat(((Map<?, ?>) r.get("link")).get("url")).isEqualTo("/income-report?startDate=2026-10-01&endDate=2026-10-31");
        assertThat(svc.route("income report this month", only("incomeRecords"), CID, TODAY).get("denied")).as("a report needs the Reports page, not the Income page").isEqualTo(true);
        when(incomeRepo.reportFinancialIncomeInRange(any(), any(), eq(CID))).thenReturn(List.of());
        assertThat(answer(svc.route("income report for last month", all(), CID, TODAY))).startsWith("No income was recorded");
        assertThat(svc.route("income report for the last ten years", all(), CID, TODAY).get("intent")).isEqualTo("narrow");
    }

    @Test @DisplayName("financial report: income, expenses and net kept separate; a whole year links to the Financial Report page, any other period to Transactions")
    void financialReport() {
        when(incomeRepo.reportFinancialIncomeInRange(any(), any(), eq(CID))).thenReturn(List.<Object[]>of(new Object[] { "Tithes", "Tithe", new BigDecimal("5000") }));
        when(expenseRepo.reportFinancialExpenseWithPurposeInRange(any(), any(), eq(CID))).thenReturn(List.<Object[]>of(
                new Object[] { "General", "Rent", new BigDecimal("1200") }, new Object[] { "General", "Processing Fees", new BigDecimal("30") }));
        Map<String, Object> m = svc.route("show the financial report for this month", all(), CID, TODAY);
        assertThat(answer(m)).contains("income $5,000.00").contains("expenses $1,230.00").contains("net $3,770.00");
        assertThat(((Map<?, ?>) m.get("summary")).get("Net")).isEqualTo("$3,770.00");
        assertThat(rows(m).get(0)).containsExactly("Income", "Tithes", "Tithe", "$5,000.00");
        assertThat(((Map<?, ?>) m.get("link")).get("url")).isEqualTo("/transactions-report?startDate=2026-10-01&endDate=2026-10-31");
        Map<String, Object> y = svc.route("financial report for 2025", all(), CID, TODAY);
        assertThat(((Map<?, ?>) y.get("link")).get("url")).isEqualTo("/financial-report?year=2025");
        verify(expenseRepo).reportFinancialExpenseWithPurposeInRange(eq(Date.valueOf("2025-01-01")), eq(Date.valueOf("2026-01-01")), eq(CID));
        assertThat(svc.route("financial report this month", only("expenseRecords"), CID, TODAY).get("denied")).isEqualTo(true);
        Map<String, Object> e = svc.route("expense report for this month", all(), CID, TODAY);
        assertThat(e.get("intent")).isEqualTo("expense-report");
        assertThat(answer(e)).contains("$1,230.00").contains("2 purposes");
        assertThat(svc.route("tax report for 2025", all(), CID, TODAY)).as("the tax report is a navigation target, not a data answer").isNull();
    }

    /* ── campaigns ─────────────────────────────────────────────────────────── */

    @Test @DisplayName("campaigns: pledged / collected / pending kept apart, active by default, Pledges page rule")
    void campaigns() {
        PledgeCampaign c = new PledgeCampaign(); c.setId(5); c.setName("Roof"); c.setStatus("Active"); c.setSubSourceId(9); c.setTargetAmount(new BigDecimal("10000")); c.setCreatedDate(LocalDateTime.of(2026, 1, 1, 0, 0));
        PledgeCampaign closed = new PledgeCampaign(); closed.setId(6); closed.setName("Old"); closed.setStatus("Closed");
        when(campaignRepo.findByClientId(CID)).thenReturn(List.of(c, closed));
        when(pledgeRepo.aiCampaignPledgeTotals(CID, 5)).thenReturn(List.<Object[]>of(new Object[] { new BigDecimal("8000"), 4L, new BigDecimal("500"), new BigDecimal("700") }));
        when(incomeRepo.aiCampaignMemberCollectedOpenEnded(eq(CID), eq(5), eq(9), any())).thenReturn(new BigDecimal("2500"));   // no end date → open-ended query
        Map<String, Object> r = svc.route("what are the different campaigns and how much is the amount for each", all(), CID, TODAY);
        assertThat(rows(r)).hasSize(1);
        assertThat(rows(r).get(0)).containsExactly("Roof", "Active", "$10,000.00", "$8,000.00", "$3,000.00", "$5,000.00", "4");   // 2500 member + 500 guest
        assertThat(answer(r)).contains("pledged, collected so far, and still pending");
        assertThat(rows(svc.route("list all campaigns", all(), CID, TODAY))).hasSize(2);
        assertThat(svc.route("list the campaigns", only("reports"), CID, TODAY).get("denied")).isEqualTo(true);
        verify(campaignRepo, org.mockito.Mockito.times(2)).findByClientId(CID);   // the denied call never reached the repository
    }

    /* ── meetings / prayer / tasks / volunteers / events / reminders ───────── */

    @Test @DisplayName("meetings: occurrences from the recurrence engine, skipped dates removed, next 30 days, Meetings page rule")
    void meetings() {
        MeetingType t = new MeetingType(); t.setTypeName("Bible Study");
        Meeting weekly = new Meeting(); weekly.setId(1); weekly.setMeetingType(t); weekly.setOccurrence("Weekly"); weekly.setMeetingDate(LocalDate.of(2026, 1, 7)); weekly.setWeekDays("3"); weekly.setStartTime("19:00");
        Meeting once = new Meeting(); once.setId(2); once.setMeetingType(t); once.setOccurrence("One-time"); once.setMeetingDate(LocalDate.of(2026, 10, 25)); once.setStartTime("10:00");
        Meeting past = new Meeting(); past.setId(3); past.setMeetingType(t); past.setOccurrence("One-time"); past.setMeetingDate(LocalDate.of(2026, 9, 1));
        when(meetingRepo.findByAppClientIdAndDeleteFlagFalse(CID)).thenReturn(List.of(weekly, once, past));
        com.churchgeniuspro.hibernate.MeetingSkipDate skip = new com.churchgeniuspro.hibernate.MeetingSkipDate(); skip.setMeetingId(1); skip.setSkipDate(LocalDate.of(2026, 10, 28));
        when(skipRepo.findByMeetingIdIn(any())).thenReturn(List.of(skip));
        when(meetingService.locationNameFor(any())).thenReturn("Fellowship Hall");
        Map<String, Object> r = svc.route("list the current meetings", all(), CID, TODAY);
        List<List<String>> rows = rows(r);
        assertThat(rows.get(0).get(0)).isEqualTo("2026-10-21");          // Wednesday today
        assertThat(rows).extracting(x -> x.get(0)).doesNotContain("2026-10-28", "2026-09-01");
        assertThat(rows).extracting(x -> x.get(0)).contains("2026-10-25");
        assertThat(rows.get(0).get(3)).isEqualTo("Fellowship Hall");
        assertThat(svc.route("list the current meetings", only("events"), CID, TODAY).get("denied")).isEqualTo(true);
    }

    @Test @DisplayName("prayer: pending = not Answered/Closed; title and status only — the request text never leaves the page")
    void prayer() {
        PrayerRequest p = new PrayerRequest(); p.setTitle("Healing for Mary"); p.setDescription("private details about her illness"); p.setStatus("New");
        when(prayerRepo.aiCountOpen(CID)).thenReturn(1L);
        when(prayerRepo.aiFindOpen(eq(CID), any(Pageable.class))).thenReturn(List.of(p));
        Map<String, Object> r = svc.route("list the pending prayer requests", all(), CID, TODAY);
        assertThat(rows(r)).hasSize(1);
        assertThat(r.toString()).doesNotContain("illness").doesNotContain("private details");
        assertThat(rows(r).get(0).get(0)).isEqualTo("Healing for Mary");
        assertThat(svc.route("list the pending prayer requests", nothing(), CID, TODAY).get("denied")).isEqualTo(true);
        verify(prayerRepo).aiCountOpen(CID);
    }

    @Test @DisplayName("follow-up tasks: admins see all open items, others only their own (the page's rule)")
    void followUps() {
        FollowUp f = new FollowUp(); f.setTitle("Call the Thomas family"); f.setStatus("PENDING"); f.setPriority("HIGH"); f.setAssignedTo("pat");
        when(followUpRepo.aiCountByStatuses(eq(CID), any())).thenReturn(5L);
        when(followUpRepo.aiFindByStatuses(eq(CID), any(), any(Pageable.class))).thenReturn(List.of(f));
        when(followUpRepo.aiCountByAssigneeAndStatuses(eq(CID), eq("pat"), any())).thenReturn(1L);
        when(followUpRepo.aiFindByAssigneeAndStatuses(eq(CID), eq("pat"), any(), any(Pageable.class))).thenReturn(List.of(f));
        Map<String, Object> admin = svc.route("list follow-up tasks that are not completed", all(), CID, TODAY);
        assertThat(answer(admin)).startsWith("5 open follow-up tasks");
        Map<String, Object> mine = svc.route("list my open tasks", only("followUps"), CID, TODAY);
        assertThat(answer(mine)).contains("assigned to you");
        verify(followUpRepo, org.mockito.Mockito.times(1)).aiFindByStatuses(eq(CID), any(), any(Pageable.class));          // only the admin route
        verify(followUpRepo, org.mockito.Mockito.times(1)).aiFindByAssigneeAndStatuses(eq(CID), eq("pat"), any(), any(Pageable.class));   // the User's own items
    }

    @Test @DisplayName("assigned volunteers: not declined, upcoming; role and member names resolved from the same tenant")
    void assignedVolunteers() {
        VolunteerAssignment v = new VolunteerAssignment(); v.setEventLabel("Sunday Service"); v.setEventDate(LocalDate.of(2026, 10, 25)); v.setRoleId(7L); v.setFamilyMemberId(1); v.setAssignmentStatus("confirmed");
        when(assignmentRepo.aiCountUpcomingActive(CID, TODAY)).thenReturn(1L);
        when(assignmentRepo.aiFindUpcomingActive(eq(CID), eq(TODAY), any(Pageable.class))).thenReturn(List.of(v));
        VolunteerRole role = new VolunteerRole(); role.setId(7L); role.setRoleName("Usher");
        when(roleRepo.findByAppClientIdAndIdIn(eq(CID), any())).thenReturn(List.of(role));
        when(memberRepo.aiMemberNames(eq(CID), any())).thenReturn(List.<Object[]>of(new Object[] { 1, "Anson", "Mathew" }));
        Map<String, Object> r = svc.route("list the assigned volunteers", all(), CID, TODAY);
        assertThat(rows(r).get(0)).containsExactly("2026-10-25", "Sunday Service", "Usher", "Anson Mathew", "confirmed");
        assertThat(svc.route("list the assigned volunteers", only("members"), CID, TODAY).get("denied")).isEqualTo(true);
    }

    @Test @DisplayName("events: future events incl. multi-day; truncated lists say 'more than' instead of inventing a total; Events page rule")
    void events() {
        when(eventRepo.aiUpcomingEvents(eq(CID), any(), any(), eq(4))).thenReturn(List.<Object[]>of(
                new Object[] { 1, "Harvest Festival", "Multiple Days", Date.valueOf("2026-11-02"), "09:00" },
                new Object[] { 2, "Youth Night", "One Day", Date.valueOf("2026-11-05"), "18:00" },
                new Object[] { 3, "Choir", "One Day", Date.valueOf("2026-11-06"), "" },
                new Object[] { 4, "Retreat", "Multiple Days", Date.valueOf("2026-11-20"), "" }));
        Map<String, Object> r = svc.route("list future events", all(), CID, TODAY);
        assertThat(rows(r)).hasSize(3);
        assertThat(answer(r)).startsWith("More than 3 events");
        assertThat(r.get("total")).isNull();
        assertThat(svc.route("list future events", only("meetings"), CID, TODAY).get("denied")).isEqualTo(true);
    }

    @Test @DisplayName("event reminders: active rules only, through ReminderService (deleted events already hidden)")
    void reminders() {
        when(reminderService.getAllEventReminders(CID)).thenReturn(List.of(
                Map.of("eventName", "Harvest Festival", "sameDay", true, "beforeDays", 2, "disabled", false, "sendEmail", true, "sendSms", true),
                Map.of("eventName", "", "disabled", true)));
        Map<String, Object> r = svc.route("list event reminders", all(), CID, TODAY);
        assertThat(rows(r)).hasSize(1);
        assertThat(rows(r).get(0)).containsExactly("Harvest Festival", "same day, 2 day(s) before", "email, SMS");
        assertThat(svc.route("list event reminders", only("events"), CID, TODAY).get("denied")).isEqualTo(true);
    }

    /* ── people ─────────────────────────────────────────────────────────────── */

    @Test @DisplayName("members by gender: database aggregate; null/blank gender reported as not specified, never guessed; Families page rule")
    void memberCounts() {
        when(memberRepo.aiCountActiveMembersByGender(CID)).thenReturn(List.<Object[]>of(new Object[] { "Male", 20L }, new Object[] { "Female", 25L }, new Object[] { "", 3L }));
        Map<String, Object> r = svc.route("how many members are in the church now? show males and females", all(), CID, TODAY);
        assertThat(answer(r)).isEqualTo("There are 48 active members: 20 male, 25 female, and 3 with no gender recorded.");
        Map<String, Object> d = svc.route("how many members are in the church", nothing(), CID, TODAY);
        assertThat(d.get("denied")).isEqualTo(true);
        verify(memberRepo).aiCountActiveMembersByGender(CID);
        verify(memberRepo, never()).countActiveMembers(anyString());
    }

    @Test @DisplayName("children (decision 3): Kids Ministry and family records are separate sources with separate rules; ambiguous → ask; no fallback")
    void children() {
        KmChild k = new KmChild(); k.setFirstName("Eli"); k.setLastName("Mathew"); k.setDob(LocalDate.of(2019, 5, 1)); k.setAllergies("peanuts"); k.setMedicalNotes("asthma"); k.setParentPhone("555-0100"); k.setClassroomId(3L);
        KmClassroom room = new KmClassroom(); room.setId(3L); room.setClientId(CID); room.setClassName("Sparks");
        when(kmChildRepo.countByClientIdAndDeleteFlagFalseAndInactiveFalse(CID)).thenReturn(1L);
        when(kmChildRepo.findByClientIdAndDeleteFlagFalseAndInactiveFalseOrderByLastNameAscFirstNameAsc(eq(CID), any(Pageable.class))).thenReturn(List.of(k));
        when(kmClassRepo.findByClientIdAndIdIn(eq(CID), any())).thenReturn(List.of(room));
        Map<String, Object> km = svc.route("list the kids ministry children", only("kidsMinistry"), CID, TODAY);
        assertThat(rows(km).get(0)).containsExactly("Eli Mathew", "Sparks");
        assertThat(km.toString()).doesNotContain("peanuts").doesNotContain("asthma").doesNotContain("555-0100").doesNotContain("2019");
        // Kids Ministry allowed does NOT unlock the family source, and vice versa
        assertThat(svc.route("list the children in family records", only("kidsMinistry"), CID, TODAY).get("denied")).isEqualTo(true);
        verify(memberRepo, never()).aiChildRoleMemberNames(anyString(), anyInt());
        assertThat(svc.route("list the kids ministry children", only("members"), CID, TODAY).get("denied")).isEqualTo(true);
        when(memberRepo.countChildRoleMembers(CID)).thenReturn(2L);
        when(memberRepo.aiChildRoleMemberNames(CID, 3)).thenReturn(List.<Object[]>of(new Object[] { "Ava", "Brooks" }, new Object[] { "Noah", "Chen" }));
        Map<String, Object> fam = svc.route("list the children in the family records", only("members"), CID, TODAY);
        assertThat(rows(fam)).containsExactly(List.of("Ava Brooks"), List.of("Noah Chen"));
        Map<String, Object> amb = svc.route("list the kids", all(), CID, TODAY);
        assertThat(amb.get("intent")).isEqualTo("clarify");
        assertThat(answer(amb)).contains("Kids Ministry").contains("family records");
        verify(kmChildRepo, never()).countByClientIdAndDeleteFlagFalseAndInactiveFalse(eq("other"));
    }

    @Test @DisplayName("public links: not revoked and not expired; label, page and expiry only — the token never appears")
    void publicLinks() {
        PublicScreenLink live = new PublicScreenLink(); live.setPageLabel("Upcoming Events"); live.setPageUrl("/upcomingEvents"); live.setToken("SECRET-TOKEN-123"); live.setExpirationDate(LocalDate.of(2027, 1, 1));
        PublicScreenLink expired = new PublicScreenLink(); expired.setPageLabel("Old"); expired.setPageUrl("/connect"); expired.setToken("SECRET-OLD"); expired.setExpirationDate(LocalDate.of(2026, 1, 1));
        when(linkRepo.findByAppClientIdAndRevokedFalseOrderByCreatedDateDesc(CID)).thenReturn(List.of(live, expired));
        Map<String, Object> r = svc.route("show all public links", all(), CID, TODAY);
        assertThat(rows(r)).hasSize(1);
        assertThat(rows(r).get(0)).containsExactly("Upcoming Events", "/upcomingEvents", "2027-01-01");
        assertThat(r.toString()).doesNotContain("SECRET");
        assertThat(svc.route("list the generated public links", only("events"), CID, TODAY).get("denied")).isEqualTo(true);
    }

    @Test @DisplayName("denied answers touch no repository at all, and unrelated questions are not handled here")
    void deniedIsCheap() {
        for (String q : new String[] { "who are the contributors this month", "total income this month", "list the expenses", "show transactions", "list campaigns",
                "list the current meetings", "pending prayer requests", "open tasks", "assigned volunteers", "future events", "event reminders", "how many members", "public links" })
            assertThat(svc.route(q, nothing(), CID, TODAY).get("denied")).as(q).isEqualTo(true);
        verifyNoInteractions(incomeRepo, expenseRepo, memberRepo, prayerRepo, followUpRepo, assignmentRepo, eventRepo, reminderService, linkRepo, kmChildRepo, meetingRepo, campaignRepo);
        assertThat(svc.route("how do i add a family", all(), CID, TODAY)).isNull();
        assertThat(svc.route("anything", AiAccess.none("Member"), CID, TODAY)).isNull();
    }
}
