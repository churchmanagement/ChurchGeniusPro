package com.churchgeniuspro.ai;

import com.churchgeniuspro.controller.AiDataSearchController;
import com.churchgeniuspro.controller.AiSearchController;
import com.churchgeniuspro.controller.HelpController;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.VolunteerProfileRepository;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.AiDataSearchService;
import com.churchgeniuspro.service.AiSearchService;
import com.churchgeniuspro.util.RoleGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI Search Phase A — the endpoints themselves: the tenant and the rights come from the
 * session only (a clientId in the body changes nothing), Kids Portal and non-opted-in
 * Member sessions are refused on every AI endpoint including the how-to fallback, and
 * the search bar is drawn only on the server's verdict.
 */
@DisplayName("AI Search — endpoint security")
class AiSearchEndpointSecurityTest {

    static final String MINE = "CHR-MINE", OTHER = "CHR-OTHER";

    IncomeRepository incomeRepo; ExpenseRepository expenseRepo; FamilyMemberRepository memberRepo; VolunteerProfileRepository volunteerRepo;
    AiSearchController search; AiDataSearchController data; HelpController help;

    @BeforeEach
    void setUp() {
        incomeRepo = mock(IncomeRepository.class); expenseRepo = mock(ExpenseRepository.class);
        memberRepo = mock(FamilyMemberRepository.class); volunteerRepo = mock(VolunteerProfileRepository.class);
        when(incomeRepo.searchIncome(anyString(), any(), any(), any(), any())).thenReturn(List.of());
        when(incomeRepo.findRangeByAppClientId(anyString(), any(), any())).thenReturn(List.of());
        when(memberRepo.findAllWithFamilyByAppUser(anyString())).thenReturn(List.of());
        AiAccessPolicy policy = new AiAccessPolicy();
        search = new AiSearchController(new AiSearchService(incomeRepo, expenseRepo, memberRepo), policy);
        data   = new AiDataSearchController(new AiDataSearchService(memberRepo, incomeRepo, volunteerRepo, policy));
        help   = new HelpController(mock(com.churchgeniuspro.service.HelpAssistantService.class));
    }

    static MockHttpServletRequest staff(String role) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "staff"); s.setAttribute("role", role); s.setAttribute("appClientId", MINE); s.setAttribute("clientId", MINE);
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
    }
    static MockHttpServletRequest member(String memberRole, String privs) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "mbr"); s.setAttribute("role", "Member"); s.setAttribute("memberId", 7);
        s.setAttribute("appClientId", MINE); s.setAttribute("memberRole", memberRole);
        if (privs != null) s.setAttribute("memberPrivileges", privs);
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
    }

    @Test @DisplayName("a clientId (or role) in the request body is ignored — every query runs against the session's own church")
    void bodyClientIdIgnored() {
        Map<String, String> body = Map.of("query", "How much income this month?", "clientId", OTHER, "role", "SuperAdmin");
        ResponseEntity<Map<String, Object>> r = search.search(body, staff("Accountant"));
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        verify(incomeRepo).searchIncome(org.mockito.ArgumentMatchers.eq(MINE), any(), any(), any(), any());
        verify(incomeRepo, never()).searchIncome(org.mockito.ArgumentMatchers.eq(OTHER), any(), any(), any(), any());

        Map<String, Object> body2 = Map.of("query", "What was the income for March?", "clientId", OTHER);
        data.data(body2, staff("Admin"));
        verify(incomeRepo).findRangeByAppClientId(org.mockito.ArgumentMatchers.eq(MINE), any(), any());
        verify(incomeRepo, never()).findRangeByAppClientId(org.mockito.ArgumentMatchers.eq(OTHER), any(), any());
    }

    @Test @DisplayName("Kids Portal sessions are refused by every AI endpoint, including the how-to fallback")
    void kidsRefusedEverywhere() {
        for (String role : new String[] { "Daughter", "Kid", "Minor" }) {
            MockHttpServletRequest kid = member(role, "{\"more.aiassistant\":true,\"member.directory\":true}");
            assertThat(search.search(Map.of("query", "how many members"), kid).getStatusCode().value()).as(role).isEqualTo(403);
            assertThat(data.data(Map.of("query", "When is Anson's birthday?"), kid).getStatusCode().value()).as(role).isEqualTo(403);
            assertThat(help.helpSearch(Map.of("query", "how do I add a child"), kid).getStatusCode().value()).as(role).isEqualTo(403);
        }
        verify(memberRepo, never()).findAllWithFamilyByAppUser(anyString());
        verify(memberRepo, never()).countActiveMembers(anyString());
    }

    @Test @DisplayName("a Member without the AI opt-in is refused; with it, church-wide counts are still denied unless the Directory is granted")
    void memberPortalRules() {
        MockHttpServletRequest noOptIn = member("Spouse", "{\"member.family\":true}");
        assertThat(search.search(Map.of("query", "how many members are in the church"), noOptIn).getStatusCode().value()).isEqualTo(403);
        assertThat(help.helpSearch(Map.of("query", "tax report"), noOptIn).getStatusCode().value()).isEqualTo(403);

        MockHttpServletRequest myProfileOnly = member("Spouse", "{\"more.aiassistant\":true,\"member.family\":true}");
        ResponseEntity<Map<String, Object>> r = search.search(Map.of("query", "how many members are in the church now"), myProfileOnly);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody().get("intent")).isEqualTo("denied");
        assertThat(String.valueOf(r.getBody().get("answer"))).doesNotContainPattern("\\d+ (active )?member");
        verify(memberRepo, never()).countActiveMembers(anyString());
        assertThat(help.helpSearch(Map.of("query", "tax report"), myProfileOnly).getStatusCode().value()).as("how-to is part of the opted-in feature").isEqualTo(200);
    }

    @Test @DisplayName("staff: a User gets no member count and no finance; an Admin with the Families page gets the count")
    void staffRules() {
        when(memberRepo.countActiveMembers(MINE)).thenReturn(120L);
        ResponseEntity<Map<String, Object>> u = search.search(Map.of("query", "how many members are in the church"), staff("User"));
        assertThat(u.getBody().get("intent")).isEqualTo("denied");
        ResponseEntity<Map<String, Object>> a = search.search(Map.of("query", "how many members are in the church"), staff("Admin"));
        assertThat(a.getBody().get("intent")).isEqualTo("members_count");
        assertThat(String.valueOf(a.getBody().get("answer"))).contains("120");
        ResponseEntity<Map<String, Object>> e = search.search(Map.of("query", "show the expenses this month"), staff("User"));
        assertThat(e.getBody().get("intent")).isEqualTo("denied");
        verify(expenseRepo, never()).searchExpense(anyString(), any(), any(), any());
    }

    /* ── Phase D: navigation from the server catalogue ───────────────────────── */

    @SuppressWarnings("unchecked")
    static List<String> urls(ResponseEntity<Map<String, Object>> r) {
        List<String> out = new java.util.ArrayList<>();
        for (Map<String, Object> l : (List<Map<String, Object>>) r.getBody().get("links")) out.add(String.valueOf(l.get("url")));
        return out;
    }

    @Test @DisplayName("navigation links come from the server catalogue and only for pages the login can open; an unavailable feature is described, never hidden")
    void navigationFollowsPageRules() {
        assertThat(urls(search.search(Map.of("query", "go to the ntag page"), staff("Admin")))).contains("/ntagLandingAdmin");
        ResponseEntity<Map<String, Object>> u = search.search(Map.of("query", "go to the ntag page"), staff("User"));
        assertThat(urls(u)).doesNotContain("/ntagLandingAdmin", "/ntagAccess");
        assertThat(String.valueOf(u.getBody().get("answer"))).contains("not available to your current login").contains("NTag");
        assertThat(urls(search.search(Map.of("query", "go to private page access"), staff("Admin")))).contains("/private-access-settings");
        assertThat(urls(search.search(Map.of("query", "go to private page access"), staff("Accountant")))).doesNotContain("/private-access-settings");
        assertThat(urls(search.search(Map.of("query", "I need to add a new fund"), staff("Accountant")))).contains("/fund");
        assertThat(urls(search.search(Map.of("query", "I need to add a new expense category"), staff("Admin")))).contains("/purpose");
        assertThat(urls(search.search(Map.of("query", "I need to add a new expense."), staff("Admin"))).get(0)).as("scenario 23: a new expense (type) → Expense Categories first").isEqualTo("/purpose");
        assertThat(urls(search.search(Map.of("query", "how do I set the daily bible verse"), staff("Admin")))).contains("/dailyVerseSetup");
        assertThat(urls(search.search(Map.of("query", "how do I set the daily bible verse"), staff("User")))).doesNotContain("/dailyVerseSetup");
        assertThat(urls(search.search(Map.of("query", "I need to set the email display name"), staff("User")))).contains("/notifyEmail#emailSettings");
        assertThat(urls(search.search(Map.of("query", "open the financial report"), staff("Accountant")))).contains("/financial-report").doesNotContain("/tax-report");
        // a permission switched off removes the link but keeps the description
        ResponseEntity<Map<String, Object>> off = search.search(Map.of("query", "go to the income report"), staffWith("Accountant", "{\"accounting.reports\":false}"));
        assertThat(urls(off)).doesNotContain("/income-report");
        assertThat(String.valueOf(off.getBody().get("answer"))).contains("not available");
        // church-owner login: NTag landing yes, income no
        assertThat(urls(search.search(Map.of("query", "ntag landing page"), churchReq()))).contains("/ntagLandingAdmin");
        assertThat(urls(search.search(Map.of("query", "go to income"), churchReq()))).doesNotContain("/income");
    }

    @Test @DisplayName("\"add a new method\" is ambiguous → the assistant asks (transaction method vs something else) and offers Transaction Types")
    void methodIsClarified() {
        ResponseEntity<Map<String, Object>> r = search.search(Map.of("query", "I need to add a new method"), staff("Accountant"));
        assertThat(r.getBody().get("intent")).isEqualTo("clarify");
        assertThat(String.valueOf(r.getBody().get("answer"))).contains("payment / transaction method");
        assertThat(urls(r)).contains("/transactiontype");
        assertThat(search.search(Map.of("query", "add a new payment method"), staff("Accountant")).getBody().get("intent")).isEqualTo("navigation");
        assertThat(urls(search.search(Map.of("query", "add a new payment method"), staff("Accountant")))).contains("/transactiontype");
    }

    @Test @DisplayName("/api/ai-search/nav: every page with the server verdict; Kids refused; Member sees only granted portal tabs")
    @SuppressWarnings("unchecked")
    void navEndpoint() {
        ResponseEntity<Map<String, Object>> r = search.nav(staff("Admin"));
        List<Map<String, Object>> pages = (List<Map<String, Object>>) r.getBody().get("pages");
        Map<String, Boolean> avail = new java.util.HashMap<>();
        pages.forEach(p -> avail.put(String.valueOf(p.get("url")), (Boolean) p.get("available")));
        assertThat(avail.get("/viewfamily")).isTrue(); assertThat(avail.get("/dailyVerseSetup")).isTrue();
        assertThat(avail.get("/viewusers")).as("church-only page").isFalse(); assertThat(avail.get("/memberHome?tab=directory")).isFalse();
        assertThat(search.nav(member("Son", "{\"more.aiassistant\":true}")).getStatusCode().value()).isEqualTo(403);
        ResponseEntity<Map<String, Object>> m = search.nav(member("Head", "{\"more.aiassistant\":true,\"member.directory\":true}"));
        Map<String, Boolean> mAvail = new java.util.HashMap<>();
        ((List<Map<String, Object>>) m.getBody().get("pages")).forEach(p -> mAvail.put(String.valueOf(p.get("url")), (Boolean) p.get("available")));
        assertThat(mAvail.get("/memberHome?tab=directory")).isTrue(); assertThat(mAvail.get("/memberHome?tab=give")).isFalse();
        assertThat(mAvail.get("/viewfamily")).isFalse(); assertThat(mAvail.get("/income")).isFalse(); assertThat(mAvail.get("/ai-assistant")).isTrue();
    }

    static MockHttpServletRequest staffWith(String role, String privs) {
        MockHttpServletRequest r = staff(role); r.getSession().setAttribute("privileges", privs); return r;
    }
    static MockHttpServletRequest churchReq() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "church"); s.setAttribute("role", "church"); s.setAttribute("church", Boolean.TRUE); s.setAttribute("clientId", MINE);
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
    }

    @Test @DisplayName("the search bar is drawn only when /api/session says the server allows the assistant")
    void frontendDrawsOnlyOnServerVerdict() throws Exception {
        String js = Files.readString(Paths.get("src/main/resources/static/ai-assistant.js"));
        assertThat(js).contains("session.aiAssistant=(s.aiAssistant===true);")
                      .contains("if (session.aiAssistant!==true){ hideHomeBar(); return; }")
                      .contains("host.setAttribute('data-perm','more.aiassistant');");
        String ctrl = Files.readString(Paths.get("src/main/java/com/churchgeniuspro/controller/SessionController.java"));
        assertThat(ctrl).contains("res.put(\"aiAssistant\", com.churchgeniuspro.util.RoleGuard.featureAllowed(request, com.churchgeniuspro.util.RoleGuard.PERM_AI_ASSISTANT));");
        // and the rule the flag comes from
        assertThat(RoleGuard.featureAllowed(member("Son", "{\"more.aiassistant\":true}"), RoleGuard.PERM_AI_ASSISTANT)).isFalse();
        assertThat(RoleGuard.featureAllowed(member("Spouse", "{\"more.aiassistant\":true}"), RoleGuard.PERM_AI_ASSISTANT)).isTrue();
        assertThat(RoleGuard.featureAllowed(member("Spouse", null), RoleGuard.PERM_AI_ASSISTANT)).isFalse();
        assertThat(RoleGuard.featureAllowed(staff("User"), RoleGuard.PERM_AI_ASSISTANT)).isTrue();
    }
}
