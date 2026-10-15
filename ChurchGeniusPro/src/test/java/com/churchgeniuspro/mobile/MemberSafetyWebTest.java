package com.churchgeniuspro.mobile;

import com.churchgeniuspro.controller.ContentReportController;
import com.churchgeniuspro.controller.MemberSafetyController;
import com.churchgeniuspro.service.MemberAccountDeletionService;
import com.churchgeniuspro.service.MemberSafetyService;
import com.churchgeniuspro.service.RememberMeService;
import com.churchgeniuspro.service.SecurityAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Mobile Phase 2 endpoints: who may call them, and that the church always comes from the session. */
class MemberSafetyWebTest {

    MemberSafetyService safety; MemberAccountDeletionService deletion; RememberMeService rememberMe; SecurityAuditService audit;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        safety = mock(MemberSafetyService.class); deletion = mock(MemberAccountDeletionService.class);
        rememberMe = mock(RememberMeService.class); audit = mock(SecurityAuditService.class);
        mvc = MockMvcBuilders.standaloneSetup(new MemberSafetyController(safety, deletion, rememberMe, audit),
                                              new ContentReportController(safety)).build();
    }

    static MockHttpSession member(String role, Object memberId, String memberRole) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("role", role);
        s.setAttribute("clientId", "MBR-x");
        s.setAttribute("appClientId", "CHR-1");
        s.setAttribute("username", "ann");
        if (memberId != null) s.setAttribute("memberId", memberId);
        if (memberRole != null) s.setAttribute("memberRole", memberRole);
        return s;
    }
    static MockHttpSession staff(String role) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("role", role); s.setAttribute("clientId", "USR-1"); s.setAttribute("appClientId", "CHR-1"); s.setAttribute("username", "pastor");
        return s;
    }

    @Test
    @DisplayName("member endpoints: Member sessions only — staff, church, child, temp/NTAG and anonymous are refused")
    void memberEndpointsGuarded() throws Exception {
        mvc.perform(get("/api/member/blocks")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/member/blocks").session(staff("Admin"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/member/blocks").session(member("Member", 7, "Child"))).andExpect(status().isUnauthorized());
        MockHttpSession temp = member("Member", 7, "Head"); temp.setAttribute("tempAccessId", 5);
        mvc.perform(get("/api/member/blocks").session(temp)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/member/account/delete").session(member("Member", 7, "Child"))
                .contentType("application/json").content("{\"password\":\"x\",\"confirm\":true}")).andExpect(status().isForbidden());
        verifyNoInteractions(safety, deletion);

        when(safety.listBlocks("CHR-1", 7)).thenReturn(List.of());
        mvc.perform(get("/api/member/blocks").session(member("Member", 7, "Head"))).andExpect(status().isOk());
        verify(safety).listBlocks("CHR-1", 7);
    }

    @Test
    @DisplayName("block / unblock / report pass the session's church and member id, never the body's")
    void memberCallsScoped() throws Exception {
        MockHttpSession ann = member("Member", 7, "Head");
        when(safety.block("CHR-1", 7, 9)).thenReturn(Optional.of(Map.of("memberId", 9, "name", "Bob")));
        mvc.perform(post("/api/member/blocks/9").session(ann)).andExpect(status().isOk()).andExpect(jsonPath("$.blocked.name").value("Bob"));
        when(safety.block("CHR-1", 7, 99)).thenReturn(Optional.empty());
        mvc.perform(post("/api/member/blocks/99").session(ann)).andExpect(status().isNotFound());
        when(safety.unblock("CHR-1", 7, 9)).thenReturn(true);
        mvc.perform(delete("/api/member/blocks/9").session(ann)).andExpect(status().isOk()).andExpect(jsonPath("$.removed").value(true));

        when(safety.reportMessage("CHR-1", 7, 10L, "SPAM", "junk")).thenReturn(new MemberSafetyService.ReportOutcome(true, 200, null, 5L));
        mvc.perform(post("/api/member/messages/10/report").session(ann).contentType("application/json")
                .content("{\"reason\":\"SPAM\",\"details\":\"junk\",\"appClientId\":\"CHR-EVIL\"}"))
           .andExpect(status().isOk()).andExpect(jsonPath("$.reportId").value(5));
        when(safety.reportMessage("CHR-1", 7, 11L, "", null)).thenReturn(MemberSafetyService.ReportOutcome.fail(400, "Please choose a reason."));
        mvc.perform(post("/api/member/messages/11/report").session(ann).contentType("application/json").content("{}"))
           .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Please choose a reason."));
    }

    @Test
    @DisplayName("delete: needs the confirm flag, verifies through the service, then ends the session and clears the cookie")
    void deleteFlow() throws Exception {
        MockHttpSession ann = member("Member", 7, "Head");
        mvc.perform(post("/api/member/account/delete").session(ann).contentType("application/json").content("{\"password\":\"pw\"}"))
           .andExpect(status().isBadRequest());
        verifyNoInteractions(deletion);

        when(deletion.deleteOwnAccount("MBR-x", 7, "CHR-1", "ann", "wrong")).thenReturn(MemberAccountDeletionService.Outcome.fail(403, "The password you entered is not correct."));
        mvc.perform(post("/api/member/account/delete").session(ann).contentType("application/json").content("{\"password\":\"wrong\",\"confirm\":true}"))
           .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("The password you entered is not correct."));
        verify(rememberMe, never()).clearCookie(any());

        when(deletion.deleteOwnAccount("MBR-x", 7, "CHR-1", "ann", "right")).thenReturn(new MemberAccountDeletionService.Outcome(true, 200, null, "ann@example.org", "Ann Able"));
        mvc.perform(post("/api/member/account/delete").session(ann).contentType("application/json").content("{\"password\":\"right\",\"confirm\":true}"))
           .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(audit).recordLogout(any(), any());
        verify(rememberMe).clearCookie(any());
        verify(deletion, timeout(2000)).sendConfirmation("ann@example.org", "Ann Able", "CHR-1");   // sent off the request thread
        org.assertj.core.api.Assertions.assertThat(ann.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("staff review: Admin/User of the session's church only; members and church logins refused")
    void staffEndpointsGuarded() throws Exception {
        mvc.perform(get("/api/content-reports")).andExpect(status().isForbidden());
        mvc.perform(get("/api/content-reports").session(member("Member", 7, "Head"))).andExpect(status().isForbidden());
        when(safety.listReports("CHR-1", "OPEN")).thenReturn(List.of());
        when(safety.reportCounts("CHR-1")).thenReturn(Map.of("OPEN", 0L));
        mvc.perform(get("/api/content-reports?status=OPEN").session(staff("Admin"))).andExpect(status().isOk()).andExpect(jsonPath("$.counts.OPEN").value(0));
        mvc.perform(get("/api/content-reports?status=OPEN").session(staff("User"))).andExpect(status().isOk());

        when(safety.resolve("CHR-1", 5L, "DISMISS", "ok", "pastor")).thenReturn(Optional.of(Map.of("status", "DISMISSED")));
        mvc.perform(post("/api/content-reports/5/resolve").session(staff("Admin")).contentType("application/json")
                .content("{\"action\":\"DISMISS\",\"note\":\"ok\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.report.status").value("DISMISSED"));
        when(safety.resolve(eq("CHR-1"), eq(6L), anyString(), any(), any())).thenReturn(Optional.empty());
        mvc.perform(post("/api/content-reports/6/resolve").session(staff("Admin")).contentType("application/json").content("{\"action\":\"DISMISS\"}"))
           .andExpect(status().isNotFound());
    }
}
