package com.churchgeniuspro.permissions;

import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.util.RoleGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-06 — the side menu offers only pages the server would open: navAccess comes from the
 * same rules as the page routes (role guard + permission key + main-menu key), the Permissions
 * screen shows the saved states on the right checkboxes, Member Portal My Profile follows its
 * main-section and tab keys, and Member permissions load/save without the lazy-family 500.
 */
@DisplayName("Side menu matches what the server allows")
class MenuMatchesAccessTest {

    static final Path STATIC = Path.of("src/main/resources/static");
    static final Path MAIN = Path.of("src/main/java/com/churchgeniuspro");

    static MockHttpServletRequest staff(String role, String privileges) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "u"); s.setAttribute("role", role);
        s.setAttribute("appUserId", 1); s.setAttribute("church", false); s.setAttribute("clientId", "CHR-1");
        if (privileges != null) s.setAttribute("privileges", privileges);
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
    }

    static MockHttpServletRequest member(String memberPrivileges) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "m"); s.setAttribute("role", "Member"); s.setAttribute("memberId", 5);
        s.setAttribute("memberRole", "Head"); s.setAttribute("clientId", "CHR-1");
        if (memberPrivileges != null) s.setAttribute("memberPrivileges", memberPrivileges);
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
    }

    final AiAccessPolicy policy = new AiAccessPolicy();

    @Test @DisplayName("Accountant: pages its role guards refuse are not in the menu; its own pages are")
    void accountantNeverSaved() {
        Map<String, Boolean> nav = policy.navAccess(staff("Accountant", null), "CHR-1");
        for (String p : new String[]{"/attendance", "/certificates", "/publicScreens", "/followups",
                "/admin/songbook-access", "/reminders", "/notifyEmail", "/ministry", "/workflows"}) {
            assertThat(nav.get(p)).as(p).isFalse();
        }
        for (String p : new String[]{"/income", "/expense", "/payroll", "/viewfamily", "/meetings", "/events", "/helpCenter"}) {
            assertThat(nav.get(p)).as(p).isTrue();
        }
        assertThat(nav.get("/memberHome")).isTrue();    // MY PROFILE: the route admits staff unless 'member' is unticked
    }

    @Test @DisplayName("main section unticked hides its pages; one sub-page unticked hides only that page")
    void sectionAndSubsection() {
        Map<String, Boolean> adminOff = policy.navAccess(staff("Accountant", "{\"admin\":false,\"admin.family\":true}"), "CHR-1");
        assertThat(adminOff.get("/viewfamily")).isFalse();
        assertThat(adminOff.get("/income")).isTrue();
        Map<String, Boolean> meetingsOff = policy.navAccess(staff("User", "{\"general\":true,\"general.meetings\":false}"), "CHR-1");
        assertThat(meetingsOff.get("/meetings")).isFalse();
        assertThat(meetingsOff.get("/events")).isTrue();
        assertThat(meetingsOff.get("/attendance")).isTrue();
        // SuperAdmin with nothing saved: every staff page
        Map<String, Boolean> sa = policy.navAccess(staff("SuperAdmin", null), "CHR-1");
        assertThat(sa.entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).toList())
                .as("pages a never-saved SuperAdmin may not open: church-owner-only ones")
                .containsExactlyInAnyOrder("/viewusers", "/stripeIntegration", "/ntagAccess", "/temporaryAccess");
    }

    @Test @DisplayName("MY PROFILE (/memberHome tabs) follows the route rule: open for staff unless 'member' is unticked")
    void myProfileForStaff() {
        for (String role : new String[]{"SuperAdmin", "Admin", "Accountant", "User"}) {
            assertThat(policy.navAccess(staff(role, null), "CHR-1").get("/memberHome")).as(role + " never saved").isTrue();
        }
        assertThat(policy.navAccess(staff("SuperAdmin", "{\"member\":true,\"member.family\":true}"), "CHR-1").get("/memberHome")).isTrue();
        assertThat(policy.navAccess(staff("SuperAdmin", "{\"member\":false}"), "CHR-1").get("/memberHome"))
                .as("whole MY PROFILE unticked").isFalse();
        assertThat(policy.navAccess(staff("User", "{\"member\":false,\"general\":true}"), "CHR-1").get("/memberHome")).isFalse();
    }

    @Test @DisplayName("member, church and Kids Portal sessions get no staff menu map")
    void nonStaffSessions() {
        assertThat(policy.navAccess(member(null), "CHR-1")).isEmpty();
        MockHttpServletRequest church = staff("SuperAdmin", null);
        church.getSession().setAttribute("church", true);
        assertThat(policy.navAccess(church, "CHR-1")).isEmpty();
        assertThat(policy.navAccess(new MockHttpServletRequest(), "CHR-1")).isEmpty();
    }

    @Test @DisplayName("Member Portal: My Profile unticked refuses every tab on the server; defaults allow them")
    void memberProfileSection() {
        String off = "{\"member\":false,\"member.family\":true,\"member.directory\":true}";
        assertThat(RoleGuard.requireMemberPermission(member(off), "member.family")).isNotNull();
        assertThat(RoleGuard.requireMemberPermission(member(off), "member.directory")).isNotNull();
        String dirOff = "{\"member\":true,\"member.family\":true,\"member.directory\":false}";
        assertThat(RoleGuard.requireMemberPermission(member(dirOff), "member.directory")).isNotNull();
        assertThat(RoleGuard.requireMemberPermission(member(dirOff), "member.family")).isNull();
        assertThat(RoleGuard.requireMemberPermission(member(null), "member.family")).isNull();     // never saved = defaults
        assertThat(RoleGuard.requireMemberPermission(member(null), "favorites")).isNull();
    }

    @Test @DisplayName("wiring: session payload, menu, Permissions screen, member menu, member permissions lookup")
    void wiring() throws Exception {
        String session = Files.readString(MAIN.resolve("controller/SessionController.java"));
        assertThat(session).contains("res.put(\"navAccess\"").contains("accessPolicy.navAccess(request");
        String js = Files.readString(STATIC.resolve("session.js"));
        assertThat(js).contains("function applyNavAccess()").contains("window.CGP_NAV_ACCESS = (sessionData.navAccess");
        assertThat(js.indexOf("applyNavAccess();")).isGreaterThan(js.indexOf("function applySubscriptionNav()"));
        String users = Files.readString(STATIC.resolve("viewUsers.html"));
        int sync = users.indexOf("function syncPermCheckboxes()");
        assertThat(users.substring(sync, sync + 600)).contains("visiblePermTree().forEach((sec, si)");
        String memberHome = Files.readString(STATIC.resolve("memberHome.html"));
        assertThat(memberHome).contains("if (privMap.member === false) { membersItem.style.display = 'none'; return; }");
        String users2 = Files.readString(MAIN.resolve("controller/AppUserController.java"));
        assertThat(users2).doesNotContain("familyMemberRepository.findById(id)");
        assertThat(users2).contains(".findByIdAndTenant(id, (String) session.getAttribute(\"clientId\"))");
    }
}
