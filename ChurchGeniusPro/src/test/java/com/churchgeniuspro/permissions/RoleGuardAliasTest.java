package com.churchgeniuspro.permissions;

import com.churchgeniuspro.util.RoleGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Legacy permission-key aliases: honoured on PAGE checks, invisible to API checks.
 * Stored records are never rewritten; opt-in denial is unchanged.
 */
@DisplayName("Permission key aliases (page routes only)")
class RoleGuardAliasTest {

    static MockHttpServletRequest staff(String privileges) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "u"); s.setAttribute("role", "Admin");
        s.setAttribute("appUserId", 1); s.setAttribute("church", false);
        if (privileges != null) s.setAttribute("privileges", privileges);
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
    }
    static MockHttpServletRequest member(String memberPrivileges) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("role", "Member"); s.setAttribute("memberId", 5);
        if (memberPrivileges != null) s.setAttribute("memberPrivileges", memberPrivileges);
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
    }
    static MockHttpServletRequest church() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "c"); s.setAttribute("church", true); s.setAttribute("clientId", "CHR-x");
        s.setAttribute("privileges", "{\"accounting.reports\":false,\"reports.income\":false}");
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
    }

    @Nested
    @DisplayName("pagePermissionAllows")
    class Allows {
        @Test void noJsonAllowed()            { assertThat(RoleGuard.pagePermissionAllows(null, "accounting.reports")).isTrue(); }
        @Test void missingKeyAllowed()        { assertThat(RoleGuard.pagePermissionAllows("{}", "accounting.reports")).isTrue(); }
        @Test void canonicalFalseDenied()     { assertThat(RoleGuard.pagePermissionAllows("{\"accounting.reports\":false}", "accounting.reports")).isFalse(); }
        @Test void legacyFalseDenied()        { assertThat(RoleGuard.pagePermissionAllows("{\"reports.taxreport\":false}", "accounting.reports")).isFalse(); }
        @Test void oldestGenerationDenied()   { assertThat(RoleGuard.pagePermissionAllows("{\"accountingReports.income\":false}", "accounting.reports")).isFalse(); }
        @Test void legacyTrueAllowed()        { assertThat(RoleGuard.pagePermissionAllows("{\"reports.taxreport\":true}", "accounting.reports")).isTrue(); }
        @Test void unparseableAllowed()       { assertThat(RoleGuard.pagePermissionAllows("not json", "accounting.reports")).isTrue(); }
        @Test void keyWithoutAliasesUnchanged(){ assertThat(RoleGuard.pagePermissionAllows("{\"admin.family\":false}", "admin.family")).isFalse();
                                                assertThat(RoleGuard.pagePermissionAllows("{\"admin.family\":true}", "admin.family")).isTrue(); }

        @ParameterizedTest(name = "{1} false → {0} denied")
        @CsvSource({
            "general.ministry.kids,    general.kidsministry",
            "general.ministry.kids,    general.sundayschool",
            "general.ministry.worship, general.worshipplanning",
            "general.ministry.prayer,  general.prayer",
            "general.reminders,        reminders.event",
            "general.reminders,        reminders.oneTimeReminders",
            "general.emailsettings,    general.email.settings",
            "admin.membership,         admin.membershipRequests",
            "admin.unsubscribed,       admin.unsubscribedList",
            "admin.email,              admin.email.delete",
            "accounting.donation,      accounting.donationReview",
            "accounting.settings,      accountSettings",
            "more.certificates,        general.certificates",
            "more.publicscreens,       general.publicScreens",
            "member.classes,           member.sundayschool",
        })
        void everyAlias(String canonical, String legacy) {
            assertThat(RoleGuard.pagePermissionAllows("{\"" + legacy + "\":false}", canonical)).isFalse();
        }

        @Test
        @DisplayName("every alias target is a key the Permissions screen saves today")
        void aliasTargetsAreCurrentKeys() {
            assertThat(RoleGuard.PERMISSION_ALIASES.keySet()).allMatch(k ->
                    k.matches("^(favorites|admin|accounting|general|activity|more|member)(\\.[a-z]+)*$"));
        }
    }

    @Nested
    @DisplayName("page vs API")
    class PageVsApi {
        final String legacyOnly = "{\"reports.taxreport\":false}";

        @Test
        @DisplayName("a legacy false denies the PAGE check")
        void pageDenied() {
            assertThat(RoleGuard.requirePagePermission(staff(legacyOnly), "accounting.reports")).isEqualTo(RoleGuard.FORWARD_ACCESS_DENIED);
        }

        @Test
        @DisplayName("...but leaves the API check exactly as before (no alias there)")
        void apiUnchanged() {
            assertThat(RoleGuard.requirePermission(staff(legacyOnly), "accounting.reports")).isNull();
        }

        @Test
        @DisplayName("a canonical false denies both, as today")
        void canonicalBoth() {
            String json = "{\"accounting.reports\":false}";
            assertThat(RoleGuard.requirePagePermission(staff(json), "accounting.reports")).isEqualTo(RoleGuard.FORWARD_ACCESS_DENIED);
            assertThat(RoleGuard.requirePermission(staff(json), "accounting.reports")).isEqualTo(RoleGuard.FORWARD_ACCESS_DENIED);
        }

        @Test
        @DisplayName("Church sessions bypass both, whatever is stored")
        void churchBypass() {
            assertThat(RoleGuard.requirePagePermission(church(), "accounting.reports")).isNull();
            assertThat(RoleGuard.requirePermission(church(), "accounting.reports")).isNull();
        }

        @Test
        @DisplayName("no session → login; staff without saved JSON → allowed")
        void edges() {
            assertThat(RoleGuard.requirePagePermission(new MockHttpServletRequest(), "admin.family")).isEqualTo(RoleGuard.REDIRECT_LOGIN);
            assertThat(RoleGuard.requirePagePermission(staff(null), "admin.family")).isNull();
        }
    }

    @Nested
    @DisplayName("member portal")
    class MemberPortal {
        @Test
        @DisplayName("member.classes page check honours a legacy member.sundayschool:false")
        void classesAlias() {
            assertThat(RoleGuard.requireMemberPagePermission(member("{\"member.sundayschool\":false}"), "member.classes"))
                    .isEqualTo(RoleGuard.FORWARD_ACCESS_DENIED);
            assertThat(RoleGuard.requireMemberPermission(member("{\"member.sundayschool\":false}"), "member.classes")).isNull();
        }

        @Test
        @DisplayName("existing child-key-true rule still wins")
        void childTrueWins() {
            assertThat(RoleGuard.requireMemberPagePermission(member("{\"member.classes\":false,\"member.classes.view\":true}"), "member.classes")).isNull();
        }

        @Test
        @DisplayName("non-member sessions pass through")
        void staffPassesThrough() {
            assertThat(RoleGuard.requireMemberPagePermission(staff("{\"member.sundayschool\":false}"), "member.classes")).isNull();
        }
    }
}
