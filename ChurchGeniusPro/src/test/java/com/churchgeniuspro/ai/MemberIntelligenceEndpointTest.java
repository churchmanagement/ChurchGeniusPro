package com.churchgeniuspro.ai;

import com.churchgeniuspro.ai.intel.MemberIntelligenceService;
import com.churchgeniuspro.ai.intel.MinistryReportService;
import com.churchgeniuspro.ai.intel.MinistryReportService.Access;
import com.churchgeniuspro.controller.FamilyController;
import com.churchgeniuspro.controller.MinistryReportController;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.FamilyService;
import com.churchgeniuspro.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** AI Phase 2 — Family 360 endpoint and Ministry Report API: session, role, permission and finance gates. */
@DisplayName("AI Phase 2 — /api/families/{id}/engagement and /api/ministry-report gates")
class MemberIntelligenceEndpointTest {

    static final String CHURCH = "CHR-2";
    MemberIntelligenceService intel; MinistryReportService reports; FamilyController family; MockMvc mvc;

    @BeforeEach
    void setUp() {
        intel = mock(MemberIntelligenceService.class);
        when(intel.family(eq(CHURCH), eq(1), anyBoolean())).thenAnswer(i -> Map.of("familyId", 1, "finance", i.getArgument(2), "members", List.of(Map.of("memberId", 5, "name", "Ann Lee", "signal", "WATCH"))));
        when(intel.family(eq(CHURCH), eq(99), anyBoolean())).thenReturn(null);
        family = new FamilyController(mock(FamilyService.class), mock(FamilyMemberRepository.class), mock(SubscriptionService.class));
        ReflectionTestUtils.setField(family, "intelligence", intel);
        ReflectionTestUtils.setField(family, "accessPolicy", new AiAccessPolicy());
        reports = mock(MinistryReportService.class);
        when(reports.monthly(eq(CHURCH), anyInt(), anyInt(), any(), any())).thenAnswer(i -> {
            Access a = i.getArgument(3);
            Map<String, Object> cur = new java.util.LinkedHashMap<>();
            cur.put("attendance", Map.of("checkins", 10L, "days", 2L));
            if (a.finance()) cur.put("giving", Map.of("total", "1,000.00", "gifts", 3L, "givers", 2L, "funds", List.of(Map.of("fund", "=Tithe", "total", "1,000.00", "gifts", 3L))));
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("label", "October 2026"); m.put("previousLabel", "Sep 2026"); m.put("lastYearLabel", "Oct 2025");
            m.put("current", cur); m.put("previous", Map.of("attendance", Map.of("checkins", 8L, "days", 2L))); m.put("lastYear", Map.of());
            m.put("engagement", Map.of("counts", Map.of("ENGAGED", 4L, "AT_RISK", 1L), "previous", Map.of("ENGAGED", 3L)));
            m.put("access", Map.of("finance", a.finance()));
            return m;
        });
        mvc = MockMvcBuilders.standaloneSetup(new MinistryReportController(reports, new AiAccessPolicy())).build();
    }

    static MockHttpServletRequest req(String role, String privileges) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.getSession(true).setAttribute("username", "pat");
        r.getSession().setAttribute("role", role);
        r.getSession().setAttribute("appClientId", CHURCH);
        if (privileges != null) r.getSession().setAttribute("privileges", privileges);
        return r;
    }
    static MockHttpSession session(String role, String privileges) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "pat"); s.setAttribute("role", role); s.setAttribute("appClientId", CHURCH);
        if (privileges != null) s.setAttribute("privileges", privileges);
        return s;
    }

    @Test @DisplayName("Family 360: Admin sees signals + giving facts; Admin without Income sees signals without giving; Users 403; Accountant has no attendance rule → allowed:false; member/church sessions get nothing; other church 404")
    void family360() {
        ResponseEntity<Map<String, Object>> ok = family.engagement(1, req("Admin", null));
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody()).containsEntry("allowed", true).containsEntry("finance", true);
        ArgumentCaptor<Boolean> fin = ArgumentCaptor.forClass(Boolean.class);
        verify(intel).family(eq(CHURCH), eq(1), fin.capture());
        assertThat(fin.getValue()).isTrue();

        // an Admin whose Income permission is off: signals yes, giving facts no (Users cannot open the Family page at all)
        reset(intel);
        when(intel.family(eq(CHURCH), eq(1), anyBoolean())).thenAnswer(i -> Map.of("familyId", 1, "finance", i.getArgument(2), "members", List.of()));
        ResponseEntity<Map<String, Object>> noIncome = family.engagement(1, req("Admin", "{\"accounting.income\":false}"));
        assertThat(noIncome.getStatusCode().value()).isEqualTo(200);
        verify(intel).family(eq(CHURCH), eq(1), eq(false));
        assertThat(family.engagement(1, req("User", null)).getStatusCode().value()).isEqualTo(403);

        // Accountant: Families page yes, Attendance page rule no → allowed:false and the service is never asked
        reset(intel);
        ResponseEntity<Map<String, Object>> acct = family.engagement(1, req("Accountant", null));
        assertThat(acct.getStatusCode().value()).isEqualTo(200);
        assertThat(acct.getBody()).containsEntry("allowed", false);
        verifyNoInteractions(intel);

        // attendance permission switched off for an Admin → allowed:false
        ResponseEntity<Map<String, Object>> noAtt = family.engagement(1, req("Admin", "{\"general.attendance\":false}"));
        assertThat(noAtt.getBody()).containsEntry("allowed", false);

        // church-owner login: refused by the Family page guard itself (403) — never reaches the signal
        MockHttpServletRequest church = req("SuperAdmin", null); church.getSession().setAttribute("church", true);
        assertThat(family.engagement(1, church).getStatusCode().value()).isEqualTo(403);

        // Member Portal login without the Family grant: 403 from the page guard
        MockHttpServletRequest member = req("Member", null); member.getSession().setAttribute("memberId", 5);
        assertThat(family.engagement(1, member).getStatusCode().value()).isEqualTo(403);

        // another church's family id → 404 (service returns null)
        reset(intel); when(intel.family(eq(CHURCH), eq(99), anyBoolean())).thenReturn(null);
        assertThat(family.engagement(99, req("Admin", null)).getStatusCode().value()).isEqualTo(404);

        // no session → 401 path: the guard refuses first
        assertThat(family.engagement(1, new MockHttpServletRequest()).getStatusCode().value()).isIn(401, 403);
    }

    @Test @DisplayName("Ministry Report: 401 / 403 gates (member, kids, church, temp, permission off), default month, bad month 400, section access by role")
    void reportGates() throws Exception {
        mvc.perform(get("/api/ministry-report")).andExpect(status().isUnauthorized());
        MockHttpSession member = session("Member", null); member.setAttribute("memberId", 5);
        mvc.perform(get("/api/ministry-report").session(member)).andExpect(status().isForbidden());
        MockHttpSession church = session("SuperAdmin", null); church.setAttribute("church", true);
        mvc.perform(get("/api/ministry-report").session(church)).andExpect(status().isForbidden());
        MockHttpSession temp = session("User", null); temp.setAttribute("tempAccessId", 3);
        mvc.perform(get("/api/ministry-report").session(temp)).andExpect(status().isForbidden());
        mvc.perform(get("/api/ministry-report").session(session("Admin", "{\"more.ministryreport\":false}"))).andExpect(status().isForbidden());
        verifyNoInteractions(reports);

        // Admin: default = last completed month, finance on
        mvc.perform(get("/api/ministry-report").session(session("Admin", null))).andExpect(status().isOk()).andExpect(jsonPath("$.access.finance").value(true));
        ArgumentCaptor<Access> acc = ArgumentCaptor.forClass(Access.class);
        ArgumentCaptor<Integer> yr = ArgumentCaptor.forClass(Integer.class), mo = ArgumentCaptor.forClass(Integer.class);
        verify(reports).monthly(eq(CHURCH), yr.capture(), mo.capture(), acc.capture(), any());
        assertThat(acc.getValue()).isEqualTo(Access.all());
        LocalDate expected = LocalDate.now(java.time.ZoneId.of("America/Chicago")).minusMonths(1);
        assertThat(yr.getValue()).isEqualTo(expected.getYear());
        assertThat(mo.getValue()).isEqualTo(expected.getMonthValue());

        // User: attendance / volunteers / follow-ups / groups? (groups needs admin) — never finance
        reset(reports);
        when(reports.monthly(eq(CHURCH), anyInt(), anyInt(), any(), any())).thenReturn(Map.of("label", "x"));
        mvc.perform(get("/api/ministry-report?year=2026&month=9").session(session("User", null))).andExpect(status().isOk());
        verify(reports).monthly(eq(CHURCH), eq(2026), eq(9), acc.capture(), any());
        assertThat(acc.getValue().finance()).isFalse();
        assertThat(acc.getValue().attendance()).isTrue();
        assertThat(acc.getValue().groups()).isFalse();

        // Accountant: finance only (no attendance / groups / volunteers / follow-ups rule)
        reset(reports);
        when(reports.monthly(eq(CHURCH), anyInt(), anyInt(), any(), any())).thenReturn(Map.of("label", "x"));
        mvc.perform(get("/api/ministry-report?year=2026&month=9").session(session("Accountant", null))).andExpect(status().isOk());
        verify(reports).monthly(eq(CHURCH), eq(2026), eq(9), acc.capture(), any());
        assertThat(acc.getValue()).isEqualTo(new Access(false, true, false, false, false));

        // every section switched off → 403, nothing computed
        reset(reports);
        mvc.perform(get("/api/ministry-report?year=2026&month=9").session(session("User", "{\"general.attendance\":false,\"general.volunteers\":false,\"more.followups\":false}")))
           .andExpect(status().isForbidden());
        verifyNoInteractions(reports);

        // bad input
        MockHttpSession admin = session("Admin", null);
        mvc.perform(get("/api/ministry-report?year=2026").session(admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/ministry-report?year=2026&month=13").session(admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/ministry-report?year=1999&month=1").session(admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/ministry-report?year=2099&month=1").session(admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/ministry-report?year=abc&month=1").session(admin)).andExpect(status().isBadRequest());
    }

    @Test @DisplayName("CSV: same gates, BOM + comparison columns, formula guard on fund names, giving rows only for finance viewers")
    void csv() throws Exception {
        mvc.perform(get("/api/ministry-report/csv?year=2026&month=10")).andExpect(status().isUnauthorized());
        String body = mvc.perform(get("/api/ministry-report/csv?year=2026&month=10").session(session("Admin", null)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("ministry_report_2026-10.csv")))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).startsWith("﻿Ministry Report,October 2026");
        assertThat(body).contains("Section,Metric,October 2026,Sep 2026,Oct 2025");
        assertThat(body).contains("attendance,checkins,10,8,");
        assertThat(body).contains("giving by fund,\"'=Tithe\"");          // formula guard
        assertThat(body).contains("engagement,ENGAGED,4,3");
        assertThat(body).contains("engagement,AT_RISK,1,");

        String user = mvc.perform(get("/api/ministry-report/csv?year=2026&month=10").session(session("User", null)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(user).doesNotContain("giving");
    }
}
