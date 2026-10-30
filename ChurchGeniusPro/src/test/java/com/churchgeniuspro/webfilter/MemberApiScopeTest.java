package com.churchgeniuspro.webfilter;

import com.churchgeniuspro.controller.GuessItController;
import com.churchgeniuspro.controller.GuessItGroupController;
import com.churchgeniuspro.controller.MembershipFormController;
import com.churchgeniuspro.controller.NotesController;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Security audit 2026-10-29 — Member Portal logins and staff pages/APIs.
 *
 * <p>Findings fixed: staff endpoints guarded only by guards that admit Member Portal sessions
 * (requireAdminOrUser / requireStaffOrMember / requireAuth) or by a permission key, which a
 * member whose permissions were never saved always "had"; Guess It host controls (including
 * every game's correct answers) open to members; the member Directory ignoring the church's
 * Directory switch; staff pages without a permission check open to members.
 */
@DisplayName("Member Portal logins: API scope, never-saved permissions, staff-only features")
class MemberApiScopeTest {

    static MockHttpSession member(String privileges) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "member@example.com");
        s.setAttribute("role", "Member");
        s.setAttribute("memberId", 42);
        s.setAttribute("clientId", "MBR-1");
        s.setAttribute("appClientId", "CHR-1");
        if (privileges != null) s.setAttribute("memberPrivileges", privileges);
        return s;
    }
    static MockHttpSession staff(String role) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", role.toLowerCase() + "@example.com");
        s.setAttribute("role", role);
        s.setAttribute("clientId", "CHR-1");
        s.setAttribute("appClientId", "CHR-1");
        return s;
    }
    static MockHttpServletRequest req(MockHttpSession s, String method, String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        if (s != null) r.setSession(s);
        return r;
    }
    /** Runs the filter; returns the HTTP status (200 = passed on to the controller). */
    static int run(MockHttpSession s, String method, String uri) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        new MemberApiScopeFilter().doFilter(req(s, method, uri), res, chain);
        return res.getStatus();
    }

    @Nested @DisplayName("API scope filter")
    class Filter {
        @Test void filterIsRegisteredOnTheApiRightAfterSignIn() throws IOException {
            String cfg = Files.readString(Paths.get("src/main/java/com/churchgeniuspro/webfilter/FilterConfig.java"));
            int at = cfg.indexOf("new MemberApiScopeFilter()");
            assertThat(at).isPositive();
            String reg = cfg.substring(at, cfg.indexOf("return registration;", at));
            assertThat(reg).contains("addUrlPatterns(\"/api/*\")", "setOrder(2)");
        }
        @Test void plainMemberCannotReachStaffApis() throws Exception {
            for (String uri : List.of("/api/kids-ministry/children", "/api/prayer/requests", "/api/notify/send",
                    "/api/public-screens", "/api/certificates/records", "/api/followups", "/api/notes",
                    "/api/connect-admin", "/api/ppr", "/api/attendance/dashboard", "/api/meetings",
                    "/api/events", "/api/reminders/auto", "/api/members/with-logins", "/api/members/7/permissions",
                    "/api/membership-requests", "/api/groups", "/api/income", "/api/users", "/api/demo/portal-credentials",
                    "/api/voice/extract"))
                assertThat(run(member(null), "GET", uri)).as(uri).isEqualTo(403);
        }
        @Test void plainMemberKeepsThePortalsOwnApis() throws Exception {
            for (String uri : List.of("/api/session", "/api/member/family", "/api/member/directory", "/api/members",
                    "/api/members/my/event-volunteers", "/api/volunteers/my/assignments", "/api/families/10",
                    "/api/events/5/volunteers/7/member-respond", "/api/guess-it/member/active", "/api/songbook/books",
                    "/api/worship/assignments", "/api/ss/classes/3/files", "/api/push/notifications", "/api/help/articles",
                    "/api/tickets", "/api/logo/image", "/api/favorites", "/api/demo/product-tour"))
                assertThat(run(member(null), "GET", uri)).as(uri).isEqualTo(200);
            // public endpoints stay public for a signed-in member
            assertThat(run(member(null), "POST", "/api/event-register/abc")).isEqualTo(200);
            assertThat(run(member(null), "GET", "/api/public/donation/x")).isEqualTo(200);
        }
        @Test void segmentMatchingAndEncodedTraversalCannotSneakThrough() throws Exception {
            assertThat(run(member(null), "GET", "/api/member/../kids-ministry/children")).isEqualTo(403);
            assertThat(run(member(null), "GET", "/api/member/%2e%2e/kids-ministry/children")).isEqualTo(403);
            assertThat(run(member(null), "GET", "/api/members-export")).isEqualTo(403);
            assertThat(run(member(null), "GET", "/api/events/5/volunteers")).isEqualTo(403);
        }
        @Test void grantedMemberReachesOnlyTheGrantedArea() throws Exception {
            MockHttpSession s = member("{\"member\":true,\"general.events\":true,\"general.ministry.prayer\":false}");
            assertThat(run(s, "GET", "/api/events/3")).isEqualTo(200);
            assertThat(run(s, "GET", "/api/event-calendar/events")).isEqualTo(200);
            assertThat(run(s, "GET", "/api/prayer/requests")).isEqualTo(403);
            assertThat(run(s, "GET", "/api/kids-ministry/children")).isEqualTo(403);
        }
        @Test void staffChurchAndTemporarySessionsAreUntouched() throws Exception {
            for (String role : List.of("SuperAdmin", "Admin", "Accountant", "User", "Limited"))
                assertThat(run(staff(role), "GET", "/api/kids-ministry/children")).as(role).isEqualTo(200);
            MockHttpSession church = staff("SuperAdmin"); church.setAttribute("church", true);
            assertThat(run(church, "GET", "/api/users")).isEqualTo(200);
            MockHttpSession temp = staff("User"); temp.setAttribute("tempAccessId", 9);
            assertThat(run(temp, "GET", "/api/kids-ministry/children")).isEqualTo(200);
            assertThat(run(null, "GET", "/api/kids-ministry/children")).isEqualTo(200);   // AuthFilter answers 401
        }

        /**
         * Ratchet: every /api path written in a Member Portal page or shared script must be
         * allowed for a plain member — otherwise the filter would silently break that page.
         * A path listed in KNOWN_STAFF_ONLY is a staff call inside a shared script that a
         * member never makes (or handles a refusal gracefully).
         */
        @Test void everyApiCallInTheMemberPagesIsAllowed() throws IOException {
            Set<String> KNOWN_STAFF_ONLY = Set.of(
                    "/api/users",            // session.js: staff permissions (members have no appUserId)
                    "/api/public-screens",   // ai-assistant.js: lists public links for staff; r.ok checked
                    "/api/guess-it/admin");  // guessIt.html host panel — staff only, hidden for members
            List<String> files = List.of("memberHome.html", "memberWorship.html", "worshipPlanning.html", "songbook.html",
                    "guessIt.html", "helpCenter.html", "tickets.html", "ai-assistant.html", "ai-assistant.js", "devices.js",
                    "shell.js", "native-bridge.js", "cgp-api.js", "trial-tips.js", "product-tour.js", "pwa.js");
            Pattern p = Pattern.compile("['\"`](/api/[A-Za-z0-9_\\-/]*)");
            List<String> blocked = new ArrayList<>();
            for (String f : files) {
                Path path = Paths.get("src/main/resources/static", f);
                if (!Files.exists(path)) continue;
                Matcher m = p.matcher(Files.readString(path));
                while (m.find()) {
                    String raw = m.group(1);
                    // a literal ending in "/" is the start of a path built from pieces ('/api/x/' + id)
                    String uri = raw.replaceAll("/+$", "");
                    if (uri.equals("/api")) continue;
                    String probe = raw.endsWith("/") ? uri + "/1" : uri;
                    if (KNOWN_STAFF_ONLY.stream().anyMatch(k -> MemberApiScopeFilter.under(probe, k))) continue;
                    if (AuthFilter.isPublicPath(probe) || MemberApiScopeFilter.memberArea(probe)) continue;
                    // dynamic member-respond path is built from pieces: covered by its pattern test above
                    if (probe.startsWith("/api/events/")) continue;
                    blocked.add(f + " → " + uri);
                }
            }
            assertThat(blocked).as("member page calls the filter would refuse").isEmpty();
        }
    }

    @Nested @DisplayName("Never-saved Member Portal permissions = the saved defaults")
    class NeverSaved {
        @Test void staffKeysAreOffMyProfileAndFavoritesOn() {
            MockHttpServletRequest r = req(member(null), "GET", "/x");
            for (String key : List.of("general.events", "general.ministry.worship", "more.certificates", "more.publicscreens",
                    "general.email", "general.ministry.prayer", "admin.family", "more.helpcenter", "general.worshipplanning", "general.sundayschool"))
                assertThat(RoleGuard.requirePermission(r, key)).as(key).isEqualTo(RoleGuard.FORWARD_ACCESS_DENIED);
            assertThat(RoleGuard.requirePermission(r, "member")).isNull();
            assertThat(RoleGuard.requirePermission(r, "favorites")).isNull();
            assertThat(RoleGuard.requireMemberPermission(r, "member.directory")).isNull();
            assertThat(RoleGuard.requireMemberPermission(r, "admin.groups")).isEqualTo(RoleGuard.FORWARD_ACCESS_DENIED);
        }
        @Test void savedPermissionsBehaveAsBefore() {
            MockHttpServletRequest r = req(member("{\"general.events\":true,\"member.directory\":false}"), "GET", "/x");
            assertThat(RoleGuard.requirePermission(r, "general.events")).isNull();
            assertThat(RoleGuard.requireMemberPermission(r, "member.directory")).isEqualTo(RoleGuard.FORWARD_ACCESS_DENIED);
        }
        @Test void staffWithNoSavedPermissionsStillHaveFullAccess() {
            assertThat(RoleGuard.requirePermission(req(staff("User"), "GET", "/x"), "general.events")).isNull();
        }
    }

    @Nested @DisplayName("Staff-only features")
    class StaffOnly {
        @Test void guessItHostControlsRefuseMembersAndTheChurchLogin() {
            for (Class<?> c : List.of(GuessItController.class, GuessItGroupController.class)) {
                Object controller = mock(c, CALLS_REAL_METHODS);
                assertThat((String) ReflectionTestUtils.invokeMethod(controller, "requireAdmin", req(member(null), "GET", "/x"))).as(c.getSimpleName()).isNull();
                MockHttpSession church = staff("SuperAdmin"); church.setAttribute("church", true);
                assertThat((String) ReflectionTestUtils.invokeMethod(controller, "requireAdmin", req(church, "GET", "/x"))).isNull();
                assertThat((String) ReflectionTestUtils.invokeMethod(controller, "requireAdmin", req(staff("Admin"), "GET", "/x"))).isEqualTo("CHR-1");
            }
        }
        @Test void directoryHonoursTheChurchsDirectorySwitch() {
            ResponseEntity<?> off = ReflectionTestUtils.invokeMethod(MembershipFormController.class, "directoryDeny",
                    req(member("{\"member.directory\":false}"), "GET", "/api/member/directory"));
            assertThat(off).isNotNull();
            assertThat(off.getStatusCode().value()).isEqualTo(403);
            assertThat((Object) ReflectionTestUtils.invokeMethod(MembershipFormController.class, "directoryDeny",
                    req(member(null), "GET", "/api/member/directory"))).isNull();
            assertThat((Object) ReflectionTestUtils.invokeMethod(MembershipFormController.class, "directoryDeny",
                    req(staff("Admin"), "GET", "/api/member/directory"))).isNull();
        }
        @Test void staffOnlyPagesShowAccessDeniedToMembers() {
            NotesController notes = new NotesController(null);
            assertThat(notes.notesPage(req(member(null), "GET", "/notes"))).isEqualTo(RoleGuard.FORWARD_ACCESS_DENIED);
            assertThat(notes.notesPage(req(staff("User"), "GET", "/notes"))).isEqualTo("forward:/notes.html");
            assertThat(RoleGuard.requireStaffAdminOrUser(req(member(null), "GET", "/publicPrayerAdmin"))).isEqualTo(RoleGuard.FORWARD_ACCESS_DENIED);
        }
    }
}
