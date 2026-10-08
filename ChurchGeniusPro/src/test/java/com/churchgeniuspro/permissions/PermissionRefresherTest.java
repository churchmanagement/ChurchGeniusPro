package com.churchgeniuspro.permissions;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.UserPermissions;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.UserPermissionsRepository;
import com.churchgeniuspro.service.PermissionRefresher;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.webfilter.AuthFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A permission change saved in viewUsers reaches an already-signed-in user within
 * the 15 s throttle, without a new sign-in — and never grants anything on its own.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Permission refresh without re-login")
class PermissionRefresherTest {

    @Mock UserPermissionsRepository permsRepo;
    @Mock AppUserRepository userRepo;
    @Mock FamilyMemberRepository memberRepo;
    @Mock com.churchgeniuspro.repository.LoginRepository loginRepo;
    PermissionRefresher refresher;

    static final String OLD = "{\"admin.family\":true}";
    static final String NEW = "{\"admin.family\":false}";

    @BeforeEach
    void setUp() {
        refresher = new PermissionRefresher(permsRepo, userRepo, memberRepo);
        PermissionRefresher.setInstance(refresher);
    }

    @AfterEach
    void tearDown() { PermissionRefresher.setInstance(null); }

    MockHttpSession staff(String privileges) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "u"); s.setAttribute("role", "Admin");
        s.setAttribute("appUserId", 7); s.setAttribute("privileges", privileges);
        return s;
    }
    UserPermissions row(String json) { UserPermissions p = new UserPermissions(); p.setAppUserId(7); p.setPermissions(json); return p; }

    @Nested
    @DisplayName("staff sessions")
    class Staff {
        @Test
        @DisplayName("a newer saved map replaces the session copy")
        void replaced() {
            when(permsRepo.findByAppUserId(7)).thenReturn(Optional.of(row(NEW)));
            MockHttpSession s = staff(OLD);
            assertThat(PermissionRefresher.refreshIfStale(s)).isTrue();
            assertThat(s.getAttribute("privileges")).isEqualTo(NEW);
        }

        @Test
        @DisplayName("unchanged saved map leaves the session alone")
        void unchanged() {
            when(permsRepo.findByAppUserId(7)).thenReturn(Optional.of(row(OLD)));
            MockHttpSession s = staff(OLD);
            assertThat(PermissionRefresher.refreshIfStale(s)).isFalse();
            assertThat(s.getAttribute("privileges")).isEqualTo(OLD);
        }

        @Test
        @DisplayName("no user_permissions row → legacy app_user.privileges, as at sign-in")
        void legacyColumn() {
            when(permsRepo.findByAppUserId(7)).thenReturn(Optional.empty());
            AppUser u = new AppUser(); u.setPrivileges(NEW);
            when(userRepo.findById(7)).thenReturn(Optional.of(u));
            MockHttpSession s = staff(OLD);
            PermissionRefresher.refreshIfStale(s);
            assertThat(s.getAttribute("privileges")).isEqualTo(NEW);
        }

        @Test
        @DisplayName("throttled: the database is read once per 15 s per session")
        void throttled() {
            when(permsRepo.findByAppUserId(7)).thenReturn(Optional.of(row(OLD)));
            MockHttpSession s = staff(OLD);
            PermissionRefresher.refreshIfStale(s);
            PermissionRefresher.refreshIfStale(s);
            PermissionRefresher.refreshIfStale(s);
            verify(permsRepo, times(1)).findByAppUserId(7);
            // ...and again once the window has passed
            refresher.refresh(s, System.currentTimeMillis() + PermissionRefresher.THROTTLE_MS + 1);
            verify(permsRepo, times(2)).findByAppUserId(7);
        }

        @Test
        @DisplayName("a direct save marks the session fresh, so the throttle cannot undo it")
        void markFresh() {
            when(permsRepo.findByAppUserId(7)).thenReturn(Optional.of(row(OLD)));   // db not yet visible
            MockHttpSession s = staff(NEW);
            PermissionRefresher.markFresh(s);
            assertThat(PermissionRefresher.refreshIfStale(s)).isFalse();
            assertThat(s.getAttribute("privileges")).isEqualTo(NEW);
        }

        @Test
        @DisplayName("a database failure keeps the session copy (never grants, never clears)")
        void failureKeepsSession() {
            when(permsRepo.findByAppUserId(anyInt())).thenThrow(new RuntimeException("db down"));
            MockHttpSession s = staff(OLD);
            assertThat(PermissionRefresher.refreshIfStale(s)).isFalse();
            assertThat(s.getAttribute("privileges")).isEqualTo(OLD);
        }

        @Test
        @DisplayName("the guard sees the refreshed map on the very next check")
        void guardSeesIt() {
            when(permsRepo.findByAppUserId(7)).thenReturn(Optional.of(row(NEW)));
            MockHttpServletRequest req = new MockHttpServletRequest(); req.setSession(staff(OLD));
            assertThat(RoleGuard.requirePermission(req, "admin.family")).isEqualTo(RoleGuard.FORWARD_ACCESS_DENIED);
        }
    }

    @Test
    @DisplayName("member sessions refresh memberPrivileges from family_member")
    void member() {
        FamilyMember fm = new FamilyMember(); fm.setMemberPrivileges("{\"member.groups\":false}");
        when(memberRepo.findById(42)).thenReturn(Optional.of(fm));
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("role", "Member"); s.setAttribute("memberId", 42); s.setAttribute("memberPrivileges", "{}");
        assertThat(PermissionRefresher.refreshIfStale(s)).isTrue();
        assertThat(s.getAttribute("memberPrivileges")).isEqualTo("{\"member.groups\":false}");
    }

    @Test
    @DisplayName("church, temporary-access and NTag sessions are untouched")
    void nonStaffUntouched() {
        MockHttpSession church = new MockHttpSession();
        church.setAttribute("username", "c"); church.setAttribute("church", true); church.setAttribute("clientId", "CHR-x");
        assertThat(PermissionRefresher.refreshIfStale(church)).isFalse();
        verify(permsRepo, times(0)).findByAppUserId(anyInt());
    }

    @Test
    @DisplayName("without a Spring context (no instance) the call is a no-op")
    void noInstance() {
        PermissionRefresher.setInstance(null);
        MockHttpSession s = staff(OLD);
        assertThat(PermissionRefresher.refreshIfStale(s)).isFalse();
        assertThat(s.getAttribute("privileges")).isEqualTo(OLD);
    }

    @Test
    @DisplayName("AuthFilter refreshes the session on an ordinary API request")
    void authFilterHook() throws Exception {
        when(permsRepo.findByAppUserId(7)).thenReturn(Optional.of(row(NEW)));
        AppUser u = new AppUser(); u.setId(7); u.setEnabled(true); u.setDeleteFlag(false);
        when(userRepo.findById(7)).thenReturn(Optional.of(u));
        AuthFilter filter = new AuthFilter(userRepo, loginRepo);
        MockHttpSession s = staff(OLD); s.setAttribute("clientId", "USR-7");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/families");
        req.setSession(s);
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();          // let through as before
        assertThat(s.getAttribute("privileges")).isEqualTo(NEW);
    }
}
