package com.churchgeniuspro.ai;

import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.SubscriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AI Search Phase A — the one access rule set every AI endpoint uses. Each module is
 * gated exactly like its page: role guard + alias-aware page permission + plan feature;
 * Kids Portal sessions get nothing; Member-portal sessions need an explicit grant;
 * church-owner sessions are denied the modules whose pages deny them.
 */
@DisplayName("AI Search — AiAccessPolicy mirrors the page rules")
class AiAccessPolicyTest {

    static final String CID = "CHR-1";

    static MockHttpServletRequest staff(String role, String privilegesJson) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "staff"); s.setAttribute("role", role); s.setAttribute("appClientId", CID);
        if (privilegesJson != null) s.setAttribute("privileges", privilegesJson);
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
    }
    static MockHttpServletRequest member(String memberRole, String memberPrivilegesJson) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "mbr"); s.setAttribute("role", "Member"); s.setAttribute("memberId", 42);
        s.setAttribute("appClientId", CID); s.setAttribute("memberRole", memberRole);
        if (memberPrivilegesJson != null) s.setAttribute("memberPrivileges", memberPrivilegesJson);
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
    }
    static MockHttpServletRequest church() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "church"); s.setAttribute("role", "church"); s.setAttribute("church", Boolean.TRUE); s.setAttribute("clientId", CID);
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
    }

    final AiAccessPolicy policy = new AiAccessPolicy();

    @Test @DisplayName("staff roles without restrictions: Admin/Accountant/SuperAdmin see finance + members; User sees only volunteers")
    void staffDefaults() {
        for (String role : new String[] { "SuperAdmin", "Admin", "Accountant" }) {
            AiAccess a = policy.resolve(staff(role, null), CID);
            assertThat(a.kids()).isFalse();
            assertThat(a.members()).as(role + " members").isTrue();
            assertThat(a.incomeRecords()).as(role + " income").isTrue();
            assertThat(a.expenseRecords()).as(role + " expense").isTrue();
            assertThat(a.reports()).as(role + " reports").isTrue();
        }
        AiAccess u = policy.resolve(staff("User", null), CID);
        assertThat(u.members()).as("/viewfamily denies User").isFalse();
        assertThat(u.anyFinance()).isFalse();
        assertThat(u.volunteers()).isTrue();
        assertThat(policy.resolve(staff("Accountant", null), CID).volunteers()).as("/volunteers denies Accountant").isFalse();
        assertThat(policy.resolve(staff("Limited", null), CID).volunteers()).isFalse();
    }

    @Test @DisplayName("a permission switched off in viewUsers denies the matching module — current key or legacy alias")
    void permissionsRespected() {
        AiAccess a = policy.resolve(staff("Admin", "{\"accounting.reports\":false}"), CID);
        assertThat(a.reports()).isFalse();
        assertThat(a.incomeRecords()).as("income records are a different page").isTrue();
        assertThat(policy.resolve(staff("Admin", "{\"reports.income\":false}"), CID).reports()).as("legacy alias").isFalse();
        assertThat(policy.resolve(staff("Accountant", "{\"accounting.income\":false}"), CID).incomeRecords()).isFalse();
        assertThat(policy.resolve(staff("Accountant", "{\"accounting.expense\":false}"), CID).expenseRecords()).isFalse();
        assertThat(policy.resolve(staff("Admin", "{\"admin.family\":false}"), CID).members()).isFalse();
        assertThat(policy.resolve(staff("SuperAdmin", "{\"admin.family\":false}"), CID).members()).as("SuperAdmin does not bypass saved permissions").isFalse();
        assertThat(policy.resolve(staff("User", "{\"general.volunteers\":false}"), CID).volunteers()).isFalse();
    }

    @Test @DisplayName("Member portal: only the Directory grants member data, and only when explicitly true; never finance or volunteers")
    void memberPortal() {
        assertThat(policy.resolve(member("Spouse", null), CID).members()).as("no privileges → not granted").isFalse();
        assertThat(policy.resolve(member("Spouse", "{\"more.aiassistant\":true}"), CID).members()).as("My Profile only").isFalse();
        AiAccess dir = policy.resolve(member("Head", "{\"more.aiassistant\":true,\"member.directory\":true}"), CID);
        assertThat(dir.members()).isTrue();
        assertThat(dir.incomeRecords()).isFalse(); assertThat(dir.reports()).isFalse(); assertThat(dir.volunteers()).isFalse();
        assertThat(dir.member()).isTrue();
    }

    @Test @DisplayName("Kids Portal sessions (child / son / daughter / kid / minor) get nothing, whatever is granted")
    void kidsPortal() {
        for (String role : new String[] { "Child", "Son", "Daughter", "Kid", "kids", "Minor" }) {
            AiAccess a = policy.resolve(member(role, "{\"more.aiassistant\":true,\"member.directory\":true}"), CID);
            assertThat(a.kids()).as(role).isTrue();
            assertThat(a.members()).as(role).isFalse();
            assertThat(a.anyFinance()).as(role).isFalse();
            assertThat(a.volunteers()).as(role).isFalse();
        }
        assertThat(policy.resolve(member("Spouse", "{\"member.directory\":true}"), CID).kids()).isFalse();
    }

    @Test @DisplayName("church-owner session: no module data (its pages deny it); no session: nothing")
    void churchAndAnonymous() {
        AiAccess c = policy.resolve(church(), CID);
        assertThat(c.church()).isTrue();
        assertThat(c.members()).isFalse(); assertThat(c.anyFinance()).isFalse(); assertThat(c.volunteers()).isFalse();
        AiAccess none = policy.resolve(new MockHttpServletRequest(), CID);
        assertThat(none.kids()).isTrue();
        assertThat(none.members()).isFalse();
    }

    @Test @DisplayName("a module the church's plan does not include is denied even to an Admin; a failing plan lookup denies too")
    void planFeatures() {
        SubscriptionService subs = mock(SubscriptionService.class);
        when(subs.isFeatureEnabled(eq(CID), anyString())).thenReturn(true);
        when(subs.isFeatureEnabled(CID, "accounting")).thenReturn(false);
        AiAccessPolicy p = new AiAccessPolicy(subs);
        AiAccess a = p.resolve(staff("Admin", null), CID);
        assertThat(a.anyFinance()).isFalse();
        assertThat(a.members()).isTrue();
        assertThat(a.volunteers()).isTrue();
        when(subs.isFeatureEnabled(eq(CID), anyString())).thenThrow(new IllegalStateException("plan service down"));
        assertThat(p.resolve(staff("Admin", null), CID).anyFinance()).as("unclear → deny").isFalse();
    }
}
