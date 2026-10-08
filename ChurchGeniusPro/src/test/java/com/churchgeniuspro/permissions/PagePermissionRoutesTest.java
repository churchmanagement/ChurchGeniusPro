package com.churchgeniuspro.permissions;

import com.churchgeniuspro.controller.CertificatesController;
import com.churchgeniuspro.controller.EmailSettingsController;
import com.churchgeniuspro.controller.HomeController;
import com.churchgeniuspro.controller.PrayerRequestController;
import com.churchgeniuspro.controller.ReminderController;
import com.churchgeniuspro.payroll.controller.PayrollPageController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 of the permission work: page routes are gated by the key the Permissions
 * screen saves today, legacy keys still deny through the alias table, church sessions
 * are exempt, and no {@code /api/**} handler gained a page-permission check.
 */
@DisplayName("Page routes honour the current permission keys")
class PagePermissionRoutesTest {

    static final String DENIED = "forward:/access-denied.html";

    static MockHttpServletRequest staff(String role, String privileges) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "u"); s.setAttribute("role", role);
        s.setAttribute("appUserId", 1); s.setAttribute("church", false);
        s.setAttribute("clientId", "CHR-1");
        if (privileges != null) s.setAttribute("privileges", privileges);
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
    }
    static MockHttpServletRequest church() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "c"); s.setAttribute("church", true); s.setAttribute("clientId", "CHR-1");
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
    }
    static MockHttpServletRequest member(String memberPrivileges) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "m"); s.setAttribute("role", "Member"); s.setAttribute("memberId", 5); s.setAttribute("clientId", "CHR-1");
        if (memberPrivileges != null) s.setAttribute("memberPrivileges", memberPrivileges);
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
    }
    static String off(String key) { return "{\"" + key + "\":false}"; }
    static String on(String key)  { return "{\"" + key + "\":true}"; }

    /** Runs a route with (role, privileges) and returns the view name. */
    interface Route { String call(MockHttpServletRequest r); }

    static void assertGated(Route route, String role, String currentKey, String legacyKey, String expectedForward) {
        // unchecked today → denied
        assertThat(route.call(staff(role, off(currentKey)))).as("current key false").isEqualTo(DENIED);
        // checked → page
        assertThat(route.call(staff(role, on(currentKey)))).as("current key true").isEqualTo(expectedForward);
        // never saved (older account) → page (opt-in denial preserved)
        assertThat(route.call(staff(role, "{}"))).as("missing key").isEqualTo(expectedForward);
        assertThat(route.call(staff(role, null))).as("no privileges at all").isEqualTo(expectedForward);
        // record saved by an older Permissions screen → still denied
        if (legacyKey != null) {
            assertThat(route.call(staff(role, off(legacyKey)))).as("legacy key false").isEqualTo(DENIED);
        }
    }

    @Nested @DisplayName("HomeController")
    class Home {
        final HomeController c = new HomeController();

        @ParameterizedTest(name = "{0} ← accounting.reports (legacy {1})")
        @CsvSource({
            "/income-report,       reports.income,             forward:/income-report.html",
            "/expense-report,      reports.expense,            forward:/expense-report.html",
            "/transactions-report, reports.daterange,          forward:/transactions-report.html",
            "/tax-report,          reports.taxreport,          forward:/tax-report.html",
            "/financial-report,    accountingReports.financial,forward:/financial-report.html",
            "/accountingReports,   accountingReports,          forward:/accountingReports.html",
        })
        void reports(String path, String legacy, String fwd) {
            Route r = switch (path) {
                case "/income-report"       -> c::incomeReport;
                case "/expense-report"      -> c::expenseReport;
                case "/transactions-report" -> c::transactionsReport;
                case "/tax-report"          -> c::taxReport;
                case "/financial-report"    -> c::financialReport;
                default                     -> c::accountingReports;
            };
            assertGated(r, "Accountant", "accounting.reports", legacy, fwd);
        }

        @Test void worshipPlanning() {
            assertGated(c::worshipPlanning, "User", "general.ministry.worship", "general.worshipplanning", "forward:/worshipPlanning.html");
            assertGated(c::memberWorship,   "User", "general.ministry.worship", "general.worshipplanning", "forward:/memberWorship.html");
        }
        @Test void sundaySchoolAndKids() {
            assertGated(c::sundaySchool, "User", "general.ministry.kids", "general.sundayschool", "forward:/sundaySchool.html");
            assertGated(c::kidsMinistry, "User", "general.ministry.kids", "general.kidsministry", "forward:/kidsMinistry.html");
            assertThat(c.kidsMinistryPageRedirect(staff("User", off("general.ministry.kids")))).isEqualTo(DENIED);
            assertThat(c.kidsMinistryPageRedirect(staff("User", "{}"))).isEqualTo("redirect:/ministry");
        }
        @Test void ministryHubStaffKeyUnchanged() {
            assertGated(c::ministryHub, "User", "general.ministry", null, "forward:/ministry.html");
        }
        @Test void ministryHubMemberUsesKidsKey() {
            assertThat(c.ministryHub(member(off("general.ministry.kids")))).isEqualTo(DENIED);
            assertThat(c.ministryHub(member(off("general.kidsministry")))).isEqualTo(DENIED);
            assertThat(c.ministryHub(member("{}"))).isEqualTo("forward:/ministry.html");
        }
        @Test void kidsMinistryMemberClassesKey() {
            assertThat(c.kidsMinistry(member(off("member.classes")))).isEqualTo(DENIED);
            assertThat(c.kidsMinistry(member(off("member.sundayschool")))).isEqualTo(DENIED);
            assertThat(c.kidsMinistry(member(on("member.classes")))).isEqualTo("forward:/kidsMinistry.html");
        }
        @Test void eventsAndVolunteersUnchanged() {
            assertGated(c::events,     "User", "general.events",     null, "forward:/events.html");
            assertGated(c::volunteers, "User", "general.volunteers", null, "forward:/volunteers.html");
        }
        @Test void churchExemptFromPermissionKeys() {
            // /events is reachable by church logins (requireAuth); a saved false is ignored for them.
            MockHttpServletRequest ch = church();
            ch.getSession().setAttribute("privileges", off("general.events"));
            assertThat(c.events(ch)).isEqualTo("forward:/events.html");
            // Role gates that already excluded church logins are unchanged.
            assertThat(c.worshipPlanning(church())).isEqualTo(DENIED);
        }
        @Test void roleGateStillFirst() {
            // A role that never had these pages is still denied even with the key on.
            assertThat(c.incomeReport(staff("User", on("accounting.reports")))).isEqualTo(DENIED);
            assertThat(c.worshipPlanning(new MockHttpServletRequest())).isEqualTo("redirect:/login");
        }
    }

    @Nested @DisplayName("ReminderController")
    class Reminders {
        final ReminderController c = new ReminderController();
        @Test void allFourPagesUseGeneralReminders() {
            assertGated(c::remindersHub,   "User", "general.reminders", "reminders",                 "forward:/reminders.html");
            assertGated(c::eventReminders, "User", "general.reminders", "reminders.event",           "forward:/eventReminders.html");
            assertGated(c::autoReminders,  "User", "general.reminders", "reminders.autoReminders",   "forward:/autoReminders.html");
            assertGated(c::oneReminders,   "User", "general.reminders", "reminders.onetime",         "forward:/oneReminders.html");
        }
        @Test void meetingRemindersUnchanged() {
            assertThat(c.meetingReminders(staff("User", off("general.reminders")))).isEqualTo("forward:/meetingReminders.html");
        }
    }

    @Nested @DisplayName("PrayerRequestController")
    class Prayer {
        final PrayerRequestController c = Mockito.mock(PrayerRequestController.class, Mockito.CALLS_REAL_METHODS);
        @Test void pageUsesMinistryPrayerKey() {
            assertGated(c::page, "User", "general.ministry.prayer", "general.prayer", "forward:/prayerRequest.html");
            assertThat(c.page(church())).isEqualTo(DENIED);   // requireAdminOrUser excludes church (unchanged)
        }
    }

    @Nested @DisplayName("EmailSettingsController")
    class EmailSettings {
        final EmailSettingsController c = new EmailSettingsController(null);
        @Test void pageGatedAfterAdminRole() {
            assertGated(c::page, "Admin", "general.emailsettings", "general.email.settings", "forward:/emailSettings.html");
            assertThat(c.page(staff("User", on("general.emailsettings")))).isEqualTo(DENIED);   // role gate unchanged
            assertThat(c.page(church())).isEqualTo(DENIED);                                      // requireAdmin denies church (unchanged)
        }
    }

    @Nested @DisplayName("PayrollPageController")
    class Payroll {
        final PayrollPageController c = new PayrollPageController();
        @Test void allFiveRoutesGatedByAccountingPayroll() {
            assertGated(c::home,      "Admin", "accounting.payroll", null, "forward:/payroll-home.html");
            assertGated(c::employees, "Admin", "accounting.payroll", null, "forward:/payroll-employees.html");
            assertGated(c::runs,      "Admin", "accounting.payroll", null, "forward:/payroll-run.html");
            assertGated(c::reports,   "Admin", "accounting.payroll", null, "forward:/payroll-reports.html");
            assertGated(c::activity,  "Admin", "accounting.payroll", null, "forward:/payroll-audit.html");
        }
        @Test void roleGateAndChurchUnchanged() {
            assertThat(c.home(staff("User", on("accounting.payroll")))).isEqualTo(DENIED);
            assertThat(c.home(church())).isEqualTo("forward:/payroll-home.html");
            assertThat(c.home(new MockHttpServletRequest())).isEqualTo("redirect:/login");
        }
    }

    @Nested @DisplayName("CertificatesController")
    class Certificates {
        final CertificatesController c = new CertificatesController();
        @Test void everyCertificateRouteGated() {
            List<Route> routes = List.of(c::certificates, c::baptismCertificate, c::appreciationCertificate,
                    c::dedicationCertificate, c::completionCertificate, c::sundaySchoolCertificate,
                    c::membershipCertificate, c::marriageCertificate, c::otherCertificate);
            for (Route r : routes) {
                assertThat(r.call(staff("User", off("more.certificates")))).isEqualTo(DENIED);
                assertThat(r.call(staff("User", off("general.certificates")))).isEqualTo(DENIED);
                assertThat(r.call(staff("User", "{}"))).startsWith("forward:/");
                assertThat(r.call(church())).isEqualTo(DENIED);   // requireAdminOrUser excludes church (unchanged)
            }
        }
    }

    // ── Source-level guard: page checks never reach an API handler ────────────

    @Test
    @DisplayName("requirePagePermission is only called from handlers that are not mapped under /api")
    void pageChecksStayOffApiHandlers() throws IOException {
        Pattern mapping = Pattern.compile("@(Get|Post|Put|Delete|Patch|Request)Mapping\\s*\\(([^)]*)\\)");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                if (p.endsWith("RoleGuard.java")) continue;
                String[] lines = Files.readAllLines(p).toArray(new String[0]);
                String lastMapping = null;
                for (int i = 0; i < lines.length; i++) {
                    Matcher m = mapping.matcher(lines[i]);
                    if (m.find()) lastMapping = m.group(2);
                    if (lines[i].contains("requirePagePermission(") || lines[i].contains("requireMemberPagePermission(")) {
                        if (lastMapping != null && lastMapping.contains("/api")) {
                            offenders.add(p + ":" + (i + 1) + " under " + lastMapping);
                        }
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }
}
