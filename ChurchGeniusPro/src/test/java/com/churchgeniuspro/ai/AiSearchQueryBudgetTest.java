package com.churchgeniuspro.ai;

import com.churchgeniuspro.hibernate.KmChild;
import com.churchgeniuspro.hibernate.PledgeCampaign;
import com.churchgeniuspro.hibernate.VolunteerAssignment;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.AiStructuredAnswerService;
import com.churchgeniuspro.service.MeetingService;
import com.churchgeniuspro.service.PledgeTotalsService;
import com.churchgeniuspro.service.ReminderService;
import com.churchgeniuspro.service.ReportQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI Search Phase G — performance budget. Every structured intent is answered with a fixed, small
 * number of repository statements (each of which is itself bounded by LIMIT / aggregate), so a
 * question can never fan out with the size of the church's data. The budget is asserted by counting
 * repository invocations on mocks: ≤ 3 statements for a single-entity intent, and for the two
 * intents that resolve names afterwards (assigned volunteers, Kids Ministry children) at most two
 * batch lookups on top — never one lookup per row. Campaign totals cost 1 + 2 statements per campaign
 * and the campaign list is itself bounded by max-rows.
 */
@DisplayName("AI Search — statement budget per intent")
class AiSearchQueryBudgetTest {

    static final String CID = "CHR-1";
    static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    IncomeRepository incomeRepo; ExpenseRepository expenseRepo; FamilyMemberRepository memberRepo; PrayerRequestRepository prayerRepo;
    FollowUpRepository followUpRepo; VolunteerAssignmentRepository assignmentRepo; VolunteerRoleRepository roleRepo; ChurchEventRepository eventRepo;
    ReminderService reminderService; PublicScreenLinkRepository linkRepo; KmChildRepository kmChildRepo; KmClassroomRepository kmClassRepo;
    MeetingRepository meetingRepo; MeetingSkipDateRepository skipRepo; MeetingService meetingService; PledgeCampaignRepository campaignRepo; PledgeMemberRepository pledgeRepo;
    List<Object> mocks = new ArrayList<>();
    AiStructuredAnswerService svc;

    <T> T m(Class<T> c) { T x = mock(c); mocks.add(x); return x; }

    @BeforeEach
    void setUp() {
        incomeRepo = m(IncomeRepository.class); expenseRepo = m(ExpenseRepository.class); memberRepo = m(FamilyMemberRepository.class);
        prayerRepo = m(PrayerRequestRepository.class); followUpRepo = m(FollowUpRepository.class); assignmentRepo = m(VolunteerAssignmentRepository.class);
        roleRepo = m(VolunteerRoleRepository.class); eventRepo = m(ChurchEventRepository.class); reminderService = m(ReminderService.class);
        linkRepo = m(PublicScreenLinkRepository.class); kmChildRepo = m(KmChildRepository.class); kmClassRepo = m(KmClassroomRepository.class);
        meetingRepo = m(MeetingRepository.class); skipRepo = m(MeetingSkipDateRepository.class); meetingService = m(MeetingService.class);
        campaignRepo = m(PledgeCampaignRepository.class); pledgeRepo = m(PledgeMemberRepository.class);
        svc = new AiStructuredAnswerService(incomeRepo, expenseRepo, memberRepo, prayerRepo, followUpRepo, assignmentRepo, roleRepo, eventRepo, reminderService,
                linkRepo, kmChildRepo, kmClassRepo, meetingRepo, skipRepo, meetingService, campaignRepo, new PledgeTotalsService(pledgeRepo, incomeRepo),
                new ReportQueryService(incomeRepo, expenseRepo));
        svc.maxRows = 20; svc.maxRangeDays = 400;
    }

    static AiAccess all() {
        return new AiAccess("Admin", "anson", false, false, false, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, Set.of());
    }

    int statements() {
        int n = 0;
        for (Object x : mocks) for (Invocation i : Mockito.mockingDetails(x).getInvocations()) if (!i.isVerified()) n++;
        return n;
    }
    void reset() { for (Object x : mocks) Mockito.clearInvocations(x); }

    @Test @DisplayName("single-entity intents: at most 3 statements each, whatever the data size")
    void singleEntityBudget() {
        Map<String, Integer> budget = Map.ofEntries(
            Map.entry("what was the income this month", 1),          // one aggregate
            Map.entry("who are the contributors this month", 2),     // rows (LIMIT) + aggregate
            Map.entry("total expenses this month", 1),
            Map.entry("list the expenses this month", 2),
            Map.entry("transactions this month", 2),                 // income + expense aggregates
            Map.entry("income report for last month", 1),
            Map.entry("expense report for last month", 1),
            Map.entry("financial report for 2026", 2),
            Map.entry("how many members do we have", 1),             // one GROUP BY
            Map.entry("children in family records", 2),              // count + names (LIMIT)
            Map.entry("pending prayer requests", 2),                 // count + page
            Map.entry("open follow-ups", 2),
            Map.entry("upcoming events", 1),                         // one LIMIT query
            Map.entry("active reminders", 1),                        // ReminderService once
            Map.entry("public links", 1));
        for (Map.Entry<String, Integer> e : budget.entrySet()) {
            reset();
            Map<String, Object> r = svc.route(e.getKey(), all(), CID, TODAY);
            assertThat(r).as(e.getKey()).isNotNull();
            assertThat(statements()).as("statements for \"" + e.getKey() + "\"").isLessThanOrEqualTo(e.getValue()).isLessThanOrEqualTo(3);
        }
    }

    @Test @DisplayName("assigned volunteers: 2 statements + at most 2 batch name lookups, not one per row")
    void volunteersBatchLookups() {
        List<VolunteerAssignment> rows = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            VolunteerAssignment a = new VolunteerAssignment(); a.setAppClientId(CID); a.setRoleId((long) (i % 5 + 1)); a.setFamilyMemberId(100 + i);
            a.setEventDate(TODAY.plusDays(i)); a.setEventLabel("Shift " + i); a.setAssignmentStatus("accepted"); rows.add(a);
        }
        when(assignmentRepo.aiCountUpcomingActive(eq(CID), any())).thenReturn(20L);
        when(assignmentRepo.aiFindUpcomingActive(eq(CID), any(), any(Pageable.class))).thenReturn(rows);
        Map<String, Object> r = svc.route("upcoming volunteer assignments", all(), CID, TODAY);
        assertThat(r.get("intent")).isEqualTo("volunteers");
        assertThat(statements()).as("20 rows → count + page + roles-in + members-in").isLessThanOrEqualTo(4);
    }

    @Test @DisplayName("Kids Ministry children: count + page + one classroom batch lookup")
    void kidsBatchLookup() {
        List<KmChild> kids = new ArrayList<>();
        for (int i = 1; i <= 20; i++) { KmChild k = new KmChild(); k.setClientId(CID); k.setFirstName("K" + i); k.setLastName("L"); k.setClassroomId((long) (i % 3 + 1)); kids.add(k); }
        when(kmChildRepo.countByClientIdAndDeleteFlagFalseAndInactiveFalse(CID)).thenReturn(20L);
        when(kmChildRepo.findByClientIdAndDeleteFlagFalseAndInactiveFalseOrderByLastNameAscFirstNameAsc(eq(CID), any(Pageable.class))).thenReturn(kids);
        Map<String, Object> r = svc.route("kids ministry children", all(), CID, TODAY);
        assertThat(r.get("intent")).isEqualTo("kids");
        assertThat(statements()).isLessThanOrEqualTo(3);
    }

    @Test @DisplayName("campaign totals: 1 list + 2 aggregates per campaign, and the list is bounded")
    void campaignsBudget() {
        List<PledgeCampaign> cs = new ArrayList<>();
        for (int i = 1; i <= 4; i++) { PledgeCampaign c = new PledgeCampaign(); c.setId(i); c.setClientId(CID); c.setName("C" + i); c.setSubSourceId(7); c.setStatus("Active"); cs.add(c); }
        when(campaignRepo.findByClientId(CID)).thenReturn(cs);
        when(pledgeRepo.aiCampaignPledgeTotals(eq(CID), any())).thenReturn(List.<Object[]>of(new Object[] { new BigDecimal("10"), 1L, BigDecimal.ZERO, BigDecimal.ZERO }));
        when(incomeRepo.aiCampaignMemberCollectedOpenEnded(eq(CID), any(), any(), any())).thenReturn(BigDecimal.ZERO);
        Map<String, Object> r = svc.route("campaign totals", all(), CID, TODAY);
        assertThat(r.get("intent")).isEqualTo("campaigns");
        assertThat(statements()).isLessThanOrEqualTo(1 + 2 * 4);
        // more campaigns than max-rows → still bounded by max-rows
        reset(); svc.maxRows = 2;
        svc.route("campaign totals", all(), CID, TODAY);
        assertThat(statements()).isLessThanOrEqualTo(1 + 2 * 2);
    }

    @Test @DisplayName("meetings: the recurrence engine reads the series list and the skip dates once each")
    void meetingsBudget() {
        Map<String, Object> r = svc.route("what meetings are coming up", all(), CID, TODAY);
        assertThat(r).isNotNull();
        assertThat(statements()).isLessThanOrEqualTo(3);
    }
}
