package com.churchgeniuspro.notifications;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.PublicSubmissionNotification;
import com.churchgeniuspro.hibernate.PublicSubmissionNotificationState;
import com.churchgeniuspro.hibernate.UserPermissions;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.PublicSubmissionNotificationRepository;
import com.churchgeniuspro.repository.PublicSubmissionNotificationStateRepository;
import com.churchgeniuspro.repository.UserPermissionsRepository;
import com.churchgeniuspro.service.PublicSubmissionNotificationService;
import com.churchgeniuspro.service.PublicSubmissionNotificationService.Type;
import com.churchgeniuspro.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Public-page submission notifications: who sees them (destination-page role guard +
 * BOTH viewUsers permissions + plan + active account), where they link, and that
 * nothing crosses churches.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Public submission notifications")
class PublicSubmissionNotificationTest {

    static final String CHURCH = "CHR-ours";
    static final String OTHER  = "CHR-theirs";

    @Mock PublicSubmissionNotificationRepository repo;
    @Mock PublicSubmissionNotificationStateRepository stateRepo;
    @Mock UserPermissionsRepository permsRepo;
    @Mock AppUserRepository userRepo;
    @Mock SubscriptionService subscriptions;

    PublicSubmissionNotificationService svc;
    final List<PublicSubmissionNotification> rows = new ArrayList<>();
    final List<PublicSubmissionNotificationState> states = new ArrayList<>();
    final Set<String> activeChurches = new HashSet<>(Set.of(CHURCH, OTHER));
    final AtomicLong ids = new AtomicLong();

    @BeforeEach
    void setUp() {
        svc = new PublicSubmissionNotificationService(repo, stateRepo, permsRepo, userRepo, subscriptions);
        when(repo.save(any(PublicSubmissionNotification.class))).thenAnswer(i -> {
            PublicSubmissionNotification n = i.getArgument(0);
            if (n.getId() == null) n.setId(ids.incrementAndGet());
            rows.add(n);
            return n;
        });
        when(repo.findRecent(anyString(), any(Instant.class), any(Pageable.class))).thenAnswer(i -> {
            String cid = i.getArgument(0);
            return rows.stream().filter(r -> r.getClientId().equals(cid))
                    .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt())).toList();
        });
        when(repo.findByIdAndClientId(any(), anyString())).thenAnswer(i -> rows.stream()
                .filter(r -> r.getId().equals(i.getArgument(0)) && r.getClientId().equals(i.getArgument(1))).findFirst());
        when(stateRepo.findByUserKeyAndNotificationIdIn(anyString(), any())).thenAnswer(i -> {
            String uk = i.getArgument(0); Collection<Long> in = i.getArgument(1);
            return states.stream().filter(s -> s.getUserKey().equals(uk) && in.contains(s.getNotificationId())).toList();
        });
        when(stateRepo.findByNotificationIdAndUserKey(any(), anyString())).thenAnswer(i -> states.stream()
                .filter(s -> s.getNotificationId().equals(i.getArgument(0)) && s.getUserKey().equals(i.getArgument(1))).findFirst());
        when(stateRepo.save(any(PublicSubmissionNotificationState.class))).thenAnswer(i -> {
            PublicSubmissionNotificationState s = i.getArgument(0);
            if (!states.contains(s)) states.add(s);
            return s;
        });
        when(subscriptions.isAccountActive(anyString())).thenAnswer(i -> activeChurches.contains(i.<String>getArgument(0)));
        when(subscriptions.isFeatureEnabled(anyString(), anyString())).thenReturn(true);
        when(userRepo.findById(anyInt())).thenReturn(Optional.empty());

        for (Type t : Type.values()) {
            svc.record(CHURCH, t, "New " + t, "from Ada", 1L);
            svc.record(OTHER,  t, "Theirs " + t, "from Bob", 2L);
        }
    }

    /* ── sessions ──────────────────────────────────────────────────────── */

    /** A staff user of CHURCH with the given role and saved permissions JSON (null = none saved). */
    MockHttpServletRequest staff(int appUserId, String role, String permsJson) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "u" + appUserId);
        s.setAttribute("role", role);
        s.setAttribute("clientId", "USR-" + appUserId);
        s.setAttribute("appClientId", CHURCH);
        s.setAttribute("appUserId", appUserId);
        s.setAttribute("church", false);
        if (permsJson != null) {
            UserPermissions p = new UserPermissions();
            p.setAppUserId(appUserId);
            p.setPermissions(permsJson);
            when(permsRepo.findByAppUserId(appUserId)).thenReturn(Optional.of(p));
            s.setAttribute("privileges", permsJson);
        }
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setSession(s);
        return r;
    }

    MockHttpServletRequest churchLogin() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "church");
        s.setAttribute("role", "Church");
        s.setAttribute("clientId", CHURCH);
        s.setAttribute("church", true);
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setSession(s);
        return r;
    }

    static String perms(Type t, boolean section, boolean page) {
        return "{\"" + t.sectionKey + "\":" + section + ",\"" + t.pageKey + "\":" + page + "}";
    }

    /** A role whose destination page admits it, per type. */
    static String roleFor(Type t) {
        return switch (t) {
            case PRAYER, CONNECT, MEMBERSHIP -> "Admin";
            case DONATION -> "Accountant";
        };
    }

    List<String> titles(MockHttpServletRequest r) {
        return svc.listFor(r).stream().map(m -> String.valueOf(m.get("title"))).toList();
    }

    /* ── 1-3: permissions ──────────────────────────────────────────────── */

    @Nested
    @DisplayName("permissions (both keys required)")
    class Permissions {

        @ParameterizedTest(name = "{0}: both permissions → shown")
        @EnumSource(Type.class)
        void bothPermissions(Type t) {
            assertThat(titles(staff(1, roleFor(t), perms(t, true, true)))).contains("New " + t);
        }

        @ParameterizedTest(name = "{0}: page permission missing → hidden")
        @EnumSource(Type.class)
        void pageMissing(Type t) {
            assertThat(titles(staff(2, roleFor(t), perms(t, true, false)))).doesNotContain("New " + t);
        }

        @ParameterizedTest(name = "{0}: section permission missing → hidden")
        @EnumSource(Type.class)
        void sectionMissing(Type t) {
            assertThat(titles(staff(3, roleFor(t), perms(t, false, true)))).doesNotContain("New " + t);
        }

        @ParameterizedTest(name = "{0}: both missing → hidden")
        @EnumSource(Type.class)
        void bothMissing(Type t) {
            assertThat(titles(staff(4, roleFor(t), perms(t, false, false)))).doesNotContain("New " + t);
        }

        @Test
        @DisplayName("removing a permission in viewUsers hides it at once — the saved permissions win over the session")
        void removalIsImmediate() {
            MockHttpServletRequest r = staff(5, "Accountant", perms(Type.DONATION, true, true));
            assertThat(titles(r)).contains("New DONATION");
            UserPermissions now = new UserPermissions();
            now.setAppUserId(5);
            now.setPermissions(perms(Type.DONATION, true, false));        // unchecked in viewUsers
            when(permsRepo.findByAppUserId(5)).thenReturn(Optional.of(now));
            assertThat(titles(r)).doesNotContain("New DONATION");          // session still says true
        }

        @Test
        @DisplayName("a role the destination page refuses never sees it (Accountant ↛ Follow-ups, User ↛ Donations)")
        void destinationRoleGuard() {
            String all = "{}";   // no saved restrictions
            assertThat(titles(staff(6, "Accountant", all))).contains("New DONATION")
                    .doesNotContain("New PRAYER", "New CONNECT", "New MEMBERSHIP");
            assertThat(titles(staff(7, "User", all))).contains("New PRAYER", "New CONNECT")
                    .doesNotContain("New DONATION", "New MEMBERSHIP");
        }

        @Test
        @DisplayName("SuperAdmin and Admin keep working with their permissions")
        void adminsUnaffected() {
            assertThat(titles(staff(8, "SuperAdmin", "{}")))
                    .contains("New PRAYER", "New CONNECT", "New MEMBERSHIP", "New DONATION");
            assertThat(titles(staff(9, "Admin", "{}")))
                    .contains("New PRAYER", "New CONNECT", "New MEMBERSHIP", "New DONATION");
        }

        @Test
        @DisplayName("the Church login sees only what its pages allow (Membership Requests)")
        void churchLoginSeesMembershipOnly() {
            assertThat(titles(churchLogin())).containsExactly("New MEMBERSHIP");
        }

        @Test
        @DisplayName("a member-portal session gets none of these")
        void memberPortal() {
            MockHttpSession s = new MockHttpSession();
            s.setAttribute("role", "Member"); s.setAttribute("memberId", 5);
            s.setAttribute("appClientId", CHURCH); s.setAttribute("clientId", "MBR-5");
            MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s);
            assertThat(svc.listFor(r)).isEmpty();
        }

        @Test
        @DisplayName("the plan must include the destination page (e.g. no Accounting → no donation notice)")
        void planFeature() {
            when(subscriptions.isFeatureEnabled(CHURCH, "accounting")).thenReturn(false);
            assertThat(titles(staff(10, "SuperAdmin", "{}"))).doesNotContain("New DONATION").contains("New PRAYER");
        }
    }

    /* ── 4: destinations ───────────────────────────────────────────────── */

    @Test
    @DisplayName("each type links to its own page, relative to the signed-in church")
    void destinations() {
        Map<String, String> urlByTitle = new java.util.HashMap<>();
        for (Map<String, Object> m : svc.listFor(staff(11, "SuperAdmin", "{}"))) {
            urlByTitle.put(String.valueOf(m.get("title")), String.valueOf(m.get("url")));
            assertThat(m.get("dismissible")).isEqualTo(true);
            assertThat(String.valueOf(m.get("id"))).startsWith("ps-");
        }
        assertThat(urlByTitle).containsEntry("New PRAYER",     "/followups?tab=prayer")
                              .containsEntry("New CONNECT",    "/followups?tab=connect")
                              .containsEntry("New MEMBERSHIP", "/membershipRequests")
                              .containsEntry("New DONATION",   "/donation-review");
        // Relative links only: the church is the session's, never a parameter to tamper with.
        assertThat(urlByTitle.values()).allMatch(u -> u.startsWith("/") && !u.contains("client"));
    }

    /* ── 5: cross-church ───────────────────────────────────────────────── */

    @Nested
    @DisplayName("tenant isolation")
    class Isolation {
        @Test
        @DisplayName("another church's submissions are never listed")
        void neverListed() {
            assertThat(titles(staff(12, "SuperAdmin", "{}"))).noneMatch(t -> t.startsWith("Theirs"));
        }

        @Test
        @DisplayName("dismissing another church's notification by id is refused")
        void foreignDismissRefused() {
            Long theirs = rows.stream().filter(r -> r.getClientId().equals(OTHER)).findFirst().orElseThrow().getId();
            assertThat(svc.dismiss(staff(13, "SuperAdmin", "{}"), theirs)).isFalse();
            assertThat(states).isEmpty();
        }

        @Test
        @DisplayName("dismissing a type the user may not view is refused")
        void unauthorisedTypeDismissRefused() {
            Long donation = rows.stream().filter(r -> r.getClientId().equals(CHURCH) && r.getType().equals("DONATION"))
                    .findFirst().orElseThrow().getId();
            assertThat(svc.dismiss(staff(14, "User", "{}"), donation)).isFalse();
        }
    }

    /* ── dismiss / read ────────────────────────────────────────────────── */

    @Test
    @DisplayName("a dismissed notification leaves that user's panel only")
    void dismissIsPerUser() {
        MockHttpServletRequest a = staff(15, "SuperAdmin", "{}");
        Long prayer = rows.stream().filter(r -> r.getClientId().equals(CHURCH) && r.getType().equals("PRAYER"))
                .findFirst().orElseThrow().getId();
        assertThat(svc.dismiss(a, prayer)).isTrue();
        assertThat(titles(a)).doesNotContain("New PRAYER");
        assertThat(titles(staff(16, "SuperAdmin", "{}"))).contains("New PRAYER");
        assertThat(rows).hasSize(8);   // nothing deleted
    }

    @Test
    @DisplayName("mark-all-read marks them read without removing them")
    void markRead() {
        MockHttpServletRequest a = staff(17, "SuperAdmin", "{}");
        assertThat(svc.listFor(a)).allMatch(m -> Boolean.FALSE.equals(m.get("read")));
        svc.markAllRead(a);
        assertThat(svc.listFor(a)).hasSize(4).allMatch(m -> Boolean.TRUE.equals(m.get("read")));
    }

    /* ── active account ────────────────────────────────────────────────── */

    @Nested
    @DisplayName("active account")
    class ActiveAccount {
        @Test void activeChurchSeesThem() { assertThat(svc.listFor(staff(18, "SuperAdmin", "{}"))).hasSize(4); }

        @Test
        @DisplayName("expired / inactive church sees none; rows are kept; renewal brings them back")
        void expiredThenRenewed() {
            activeChurches.remove(CHURCH);                     // expired or Hold/Inactive — same rule
            assertThat(svc.listFor(staff(19, "SuperAdmin", "{}"))).isEmpty();
            assertThat(rows).hasSize(8);
            activeChurches.add(CHURCH);                        // renewed
            assertThat(svc.listFor(staff(19, "SuperAdmin", "{}"))).hasSize(4);
        }
    }

    @Test
    @DisplayName("recording never throws — a notification problem never costs a visitor their submission")
    void recordNeverThrows() {
        when(repo.save(any(PublicSubmissionNotification.class))).thenThrow(new RuntimeException("db down"));
        svc.record(CHURCH, Type.PRAYER, "x", "y", 1L);
    }

    @Test
    @DisplayName("a staff session without saved permissions uses app_user.privileges, like sign-in does")
    void legacyPrivilegesFallback() {
        AppUser u = new AppUser();
        u.setPrivileges(perms(Type.DONATION, true, false));
        when(userRepo.findById(20)).thenReturn(Optional.of(u));
        assertThat(titles(staff(20, "Accountant", null))).doesNotContain("New DONATION");
    }
}
