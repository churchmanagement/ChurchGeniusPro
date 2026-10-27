package com.churchgeniuspro.ai;

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
import com.churchgeniuspro.repository.VolunteerProfileRepository;
import com.churchgeniuspro.repository.VolunteerRoleRepository;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.AiDataSearchService;
import com.churchgeniuspro.service.AiStructuredAnswerService;
import com.churchgeniuspro.service.MeetingService;
import com.churchgeniuspro.service.PledgeTotalsService;
import com.churchgeniuspro.service.ReminderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI Search — "deleted members", "deleted families", "inactive families", "inactive members"
 * and "unalert" (members with Disable Alerts ticked): each phrasing reaches exactly one
 * status query; the status filter is in the SQL (tenant-scoped, bounded); only sessions the
 * Families page admits get rows; everything else is refused before any repository call.
 */
@DisplayName("AI Search — members and families by status")
class AiPeopleByStatusTest {

    static final String CID = "CHR-1";
    static final LocalDate TODAY = LocalDate.of(2026, 10, 26);

    FamilyMemberRepository memberRepo;
    AiStructuredAnswerService svc;

    static AiAccess families() { return AiStructuredAnswerServiceTest.only("members"); }

    @BeforeEach
    void setUp() {
        memberRepo = mock(FamilyMemberRepository.class);
        IncomeRepository incomeRepo = mock(IncomeRepository.class); ExpenseRepository expenseRepo = mock(ExpenseRepository.class);
        svc = new AiStructuredAnswerService(incomeRepo, expenseRepo, memberRepo, mock(PrayerRequestRepository.class), mock(FollowUpRepository.class),
                mock(VolunteerAssignmentRepository.class), mock(VolunteerRoleRepository.class), mock(ChurchEventRepository.class), mock(ReminderService.class),
                mock(PublicScreenLinkRepository.class), mock(KmChildRepository.class), mock(KmClassroomRepository.class), mock(MeetingRepository.class),
                mock(MeetingSkipDateRepository.class), mock(MeetingService.class), mock(PledgeCampaignRepository.class),
                new PledgeTotalsService(mock(PledgeMemberRepository.class), incomeRepo), new com.churchgeniuspro.service.ReportQueryService(incomeRepo, expenseRepo));
        svc.maxRows = 2;
    }

    @SuppressWarnings("unchecked") static List<List<String>> rows(Map<String, Object> m) { return (List<List<String>>) m.get("rows"); }
    static String intent(Map<String, Object> m) { return m == null ? null : String.valueOf(m.get("intent")); }

    @Test @DisplayName("natural phrasings each reach exactly one status intent")
    void phrasings() {
        Map<String, String> cases = new java.util.LinkedHashMap<>();
        cases.put("show deleted members", "members.deleted");
        cases.put("list all deleted members", "members.deleted");
        cases.put("show me all the deleted family members", "members.deleted");
        cases.put("which people were removed", "members.deleted");
        cases.put("find all deleted families", "families.deleted");
        cases.put("list deleted households", "families.deleted");
        cases.put("show all inactive families", "families.inactive");
        cases.put("list the inactive families", "families.inactive");
        cases.put("show inactive members", "members.inactive");
        cases.put("list all unalert", "members.unalert");
        cases.put("show all unalert members", "members.unalert");
        cases.put("who has alerts disabled", "members.unalert");
        cases.put("list members with alerts turned off", "members.unalert");
        for (Map.Entry<String, String> c : cases.entrySet())
            assertThat(intent(svc.route(c.getKey(), families(), CID, TODAY))).as(c.getKey()).isEqualTo(c.getValue());
    }

    @Test @DisplayName("deleted members: only the deleted-members query runs; member vs family deletion labelled; bounded with an exact count")
    void deletedMembers() {
        when(memberRepo.aiCountDeletedMembers(CID)).thenReturn(3L);
        when(memberRepo.aiDeletedMembers(CID, 2)).thenReturn(List.of(
                new Object[] { "Anson", "Mathew", "Head", Boolean.TRUE },
                new Object[] { "Beji", "Saji", "Head of Household", Boolean.FALSE }));
        Map<String, Object> r = svc.route("show deleted members", families(), CID, TODAY);
        assertThat(r.get("denied")).isEqualTo(false);
        assertThat(rows(r)).containsExactly(List.of("Anson Mathew", "Head", "Family deleted"), List.of("Beji Saji", "Head of Household", "Member deleted"));
        assertThat(r.get("total")).isEqualTo(3L);
        assertThat(r.get("truncated")).isEqualTo(true);
        assertThat(String.valueOf(r.get("answer"))).contains("3 deleted members").contains("first 2");
        verify(memberRepo, never()).aiInactiveMembers(anyString(), anyInt());
        verify(memberRepo, never()).findAllWithFamilyByAppUserAll(anyString());   // never "load everything and filter"
        verify(memberRepo, never()).findAllWithFamilyByAppUser(anyString());
    }

    @Test @DisplayName("deleted / inactive families and unalert members map their rows; empty results say so")
    void familiesAndUnalert() {
        when(memberRepo.aiCountDeletedFamilies(CID)).thenReturn(1L);
        when(memberRepo.aiDeletedFamilies(CID, 2)).thenReturn(List.<Object[]>of(new Object[] { "Abymol Varghese", 1L }));
        Map<String, Object> d = svc.route("find all deleted families", families(), CID, TODAY);
        assertThat(rows(d)).containsExactly(List.of("Abymol Varghese", "1"));
        assertThat(d.get("columns")).isEqualTo(List.of("Family (primary member)", "Members"));
        verify(memberRepo, never()).aiInactiveFamilies(anyString(), anyInt());

        when(memberRepo.aiCountInactiveFamilies(CID)).thenReturn(0L);
        Map<String, Object> i = svc.route("show inactive families", families(), CID, TODAY);
        assertThat(String.valueOf(i.get("answer"))).isEqualTo("There are no inactive families.");
        verify(memberRepo, never()).aiInactiveFamilies(anyString(), anyInt());   // the count said none — no list query

        when(memberRepo.aiCountUnalertMembers(CID)).thenReturn(2L);
        when(memberRepo.aiUnalertMembers(CID, 2)).thenReturn(List.of(
                new Object[] { "Esther", "Thomas", "Adult", Boolean.FALSE }, new Object[] { "Evelyn", "Thomas", "Adult", Boolean.TRUE }));
        Map<String, Object> u = svc.route("list all unalert", families(), CID, TODAY);
        assertThat(rows(u)).containsExactly(List.of("Esther Thomas", "Adult", "Active"), List.of("Evelyn Thomas", "Adult", "Inactive"));
        assertThat(String.valueOf(u.get("answer"))).contains("alerts disabled");
        assertThat(u.get("truncated")).isEqualTo(false);
    }

    @Test @DisplayName("refused before any query: no Families permission, Member portal (even with Directory), church login")
    void deniedSessions() {
        AiAccess memberWithDirectory = new AiAccess("Member", "mbr", false, true, false, true, false, false, false, false,
                false, false, false, false, false, false, false, false, false, false, Set.of());
        AiAccess churchLogin = new AiAccess("church", "church", true, false, false, true, false, false, false, false,
                false, false, false, false, false, false, false, false, false, false, Set.of());
        for (AiAccess a : List.of(AiStructuredAnswerServiceTest.nothing(), memberWithDirectory, churchLogin))
            for (String q : List.of("show deleted members", "find all deleted families", "show inactive families", "list all unalert", "show inactive members")) {
                Map<String, Object> r = svc.route(q, a, CID, TODAY);
                assertThat(r.get("denied")).as(a.role() + ": " + q).isEqualTo(true);
                assertThat(String.valueOf(r.get("answer"))).isEqualTo(AiDataSearchService.DENIED_MSG);
                assertThat(r).doesNotContainKey("rows");
            }
        verifyNoInteractions(memberRepo);
    }

    @Test @DisplayName("how-to questions, ambiguous and unrelated questions are not captured")
    void notCaptured() {
        assertThat(svc.route("how do i restore a deleted family", families(), CID, TODAY)).isNull();
        assertThat(svc.route("how can i mark a family inactive", families(), CID, TODAY)).isNull();
        assertThat(intent(svc.route("show deleted families and members", families(), CID, TODAY))).isEqualTo("clarify");
        assertThat(intent(svc.route("show deleted and inactive families", families(), CID, TODAY))).isEqualTo("clarify");
        assertThat(intent(svc.route("show deleted meetings", families(), CID, TODAY))).as("goes on to the Meetings intent").isEqualTo("meetings");
        assertThat(svc.route("show deleted things and inactive stuff", families(), CID, TODAY)).isNull();
        when(memberRepo.aiCountActiveMembersByGender(CID)).thenReturn(List.<Object[]>of(new Object[] { "male", 2L }));
        assertThat(intent(svc.route("how many members do we have", families(), CID, TODAY))).isEqualTo("members");   // existing count intent unchanged
        verify(memberRepo, never()).aiCountDeletedMembers(anyString());
    }

    @Test @DisplayName("end to end through AiDataSearchService with the real access policy: page rule decides; tenant comes from the caller")
    void endToEndPolicy() {
        AiDataSearchService data = new AiDataSearchService(memberRepo, mock(IncomeRepository.class), mock(VolunteerProfileRepository.class), new AiAccessPolicy(), svc);
        when(memberRepo.aiCountDeletedFamilies(CID)).thenReturn(0L);
        for (String role : List.of("SuperAdmin", "Admin", "Accountant")) {
            Map<String, Object> ok = data.answer(AiAccessPolicyTest.staff(role, null), CID, "Find all deleted families");
            assertThat(ok.get("denied")).as(role).isEqualTo(false);
            assertThat(ok.get("intent")).isEqualTo("families.deleted");
        }
        List<MockHttpServletRequest> refused = List.of(
                AiAccessPolicyTest.staff("User", null),                                  // /viewfamily role guard
                AiAccessPolicyTest.staff("Limited", null),
                AiAccessPolicyTest.staff("SuperAdmin", "{\"admin.family\":false}"),        // Family unticked in Users & Permissions
                AiAccessPolicyTest.member("Head", "{\"more.aiassistant\":true,\"member.directory\":true}"),
                AiAccessPolicyTest.member("Son", "{\"more.aiassistant\":true,\"member.directory\":true}"),   // Kids Portal
                AiAccessPolicyTest.church());
        for (MockHttpServletRequest req : refused) {
            Map<String, Object> r = data.answer(req, CID, "show deleted members");
            assertThat(r.get("denied")).as(String.valueOf(req.getSession().getAttribute("role"))).isEqualTo(true);
        }
        verify(memberRepo, never()).aiCountDeletedMembers(anyString());
        verify(memberRepo, never()).aiCountDeletedFamilies(eq("OTHER"));
    }

    @Test @DisplayName("SQL: every status query is tenant-scoped, filters by status in the database and is row-limited")
    void sqlShape() throws Exception {
        Map<String, List<String>> must = Map.of(
                "aiDeletedMembers",   List.of("f.app_client_id = :appClientId", "fm.delete_flag = true OR f.delete_flag = true", "LIMIT :limit"),
                "aiInactiveMembers",  List.of("f.app_client_id = :appClientId", "fm.delete_flag = false AND f.delete_flag = false", "fm.inactive = true OR f.inactive = true", "LIMIT :limit"),
                "aiUnalertMembers",   List.of("f.app_client_id = :appClientId", "fm.delete_flag = false AND f.delete_flag = false", "fm.disable_alerts = true", "LIMIT :limit"),
                "aiDeletedFamilies",  List.of("f.app_client_id = :appClientId", "f.delete_flag = true", "LIMIT :limit"),
                "aiInactiveFamilies", List.of("f.app_client_id = :appClientId", "f.delete_flag = false AND f.inactive = true", "LIMIT :limit"));
        for (Map.Entry<String, List<String>> e : must.entrySet()) {
            Method m = FamilyMemberRepository.class.getMethod(e.getKey(), String.class, int.class);
            String sql = m.getAnnotation(Query.class).value();
            for (String part : e.getValue()) assertThat(sql).as(e.getKey()).contains(part);
            assertThat(sql).as(e.getKey() + " selects no contact details").doesNotContain("email").doesNotContain("phone");
        }
        for (String count : List.of("aiCountDeletedMembers", "aiCountInactiveMembers", "aiCountUnalertMembers", "aiCountDeletedFamilies", "aiCountInactiveFamilies"))
            assertThat(FamilyMemberRepository.class.getMethod(count, String.class).getAnnotation(Query.class).value()).as(count).contains("app_client_id = :appClientId");
    }
}
