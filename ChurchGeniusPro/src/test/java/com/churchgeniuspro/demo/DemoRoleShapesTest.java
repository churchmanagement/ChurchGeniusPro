package com.churchgeniuspro.demo;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.service.TestDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A demo tenant holds three kinds of login, and {@code signup.client_id} means a
 * different thing in each: the tenant itself for a Church login, the
 * {@code app_user.user_id} for a staff login, and the {@code family_member.member_ref}
 * for a portal login. Add Role used to build only the staff shape, so Church,
 * Member Portal and Child Portal could not be created at all — and SuperAdmin,
 * a perfectly ordinary staff role, was simply missing from the list.
 *
 * <p>These tests pin the shape each role produces, because getting one wrong does
 * not fail loudly: the row still saves, it just never appears in Demo Role Access
 * or silently signs in as the wrong kind of account.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DemoRoleShapesTest {

    private static final String TENANT = "DEMO-1780113716790";

    @Mock private ChurchRegistrationRepository churchRegRepo;
    @Mock private AppUserRepository            appUserRepo;
    @Mock private LoginRepository              loginRepo;
    @Mock private FamilyRepository             familyRepo;
    @Mock private FamilyMemberRepository       familyMemberRepo;

    @InjectMocks private TestDataService svc;

    private final List<SignUp> savedSignups = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ChurchRegistration reg = new ChurchRegistration();
        reg.setId(42);
        reg.setClientId(TENANT);
        reg.setChurchName("Demo Church 1780113716790");
        when(churchRegRepo.findByClientIdAndDeleteFlagFalse(TENANT)).thenReturn(Optional.of(reg));

        // No tenant has a church login unless a test says so.
        when(loginRepo.findAllByClientId(anyString())).thenReturn(List.of());
        when(loginRepo.findActiveByUsername(anyString())).thenReturn(Optional.empty());

        when(familyRepo.save(any(Family.class))).thenAnswer(i -> i.getArgument(0));

        // Stand in for @PrePersist, which assigns member_ref, and for the id the
        // database would hand back — the portal login is keyed by both.
        AtomicInteger memberIds = new AtomicInteger(500);
        when(familyMemberRepo.save(any(FamilyMember.class))).thenAnswer(i -> {
            FamilyMember m = i.getArgument(0);
            if (m.getId() == null) m.setId(memberIds.incrementAndGet());
            if (m.getMemberRef() == null) m.setMemberRef("MBR" + UUID.randomUUID());
            return m;
        });

        when(appUserRepo.save(any(AppUser.class))).thenAnswer(i -> {
            AppUser u = i.getArgument(0);
            if (u.getUserId() == null) u.setUserId("USR" + UUID.randomUUID());
            return u;
        });

        AtomicInteger signupIds = new AtomicInteger(900);
        when(loginRepo.save(any(SignUp.class))).thenAnswer(i -> {
            SignUp s = i.getArgument(0);
            if (s.getId() == null) s.setId(signupIds.incrementAndGet());
            savedSignups.add(s);
            return s;
        });
    }

    private SignUp onlySignup() {
        assertThat(savedSignups).hasSize(1);
        return savedSignups.get(0);
    }

    /* ── the list the screen offers ─────────────────────────────────────── */

    @Test
    @DisplayName("all seven application roles are offered, SuperAdmin included")
    void everyRoleIsOffered() {
        assertThat(TestDataService.DEMO_ROLES).containsExactly(
                "Church", "SuperAdmin", "Admin", "Accountant", "User",
                "Member Portal", "Child Portal");
    }

    @Test
    @DisplayName("the dropdown on the Service Admin screen lists exactly those roles")
    void dropdownMatchesTheBackend() throws Exception {
        Path html = Path.of("src/main/resources/static/serviceadminhome.html");
        assertThat(html).as("Service Admin screen must exist").exists();
        String page = Files.readString(html, StandardCharsets.UTF_8);

        int start = page.indexOf("id=\"drAddRole\"");
        assertThat(start).as("the Add Role dropdown must still be there").isGreaterThan(0);
        String select = page.substring(start, page.indexOf("</select>", start));

        List<String> shown = new ArrayList<>();
        Matcher m = Pattern.compile("<option[^>]*>([^<]+)</option>").matcher(select);
        while (m.find()) shown.add(m.group(1).trim());

        // A role the backend can build but the screen never offers is invisible;
        // one the screen offers but the backend cannot build is a dead end. Both
        // are silent, so the two lists are pinned to each other here.
        assertThat(shown).isEqualTo(TestDataService.DEMO_ROLES);
    }

    @Test
    @DisplayName("role names are matched whatever the caller's casing or spacing")
    void rolesCanonicalise() {
        assertThat(TestDataService.canonicalDemoRole("superadmin")).isEqualTo("SuperAdmin");
        assertThat(TestDataService.canonicalDemoRole("  SUPER ADMIN ")).isEqualTo("SuperAdmin");
        assertThat(TestDataService.canonicalDemoRole("member portal")).isEqualTo("Member Portal");
        assertThat(TestDataService.canonicalDemoRole("MemberPortal")).isEqualTo("Member Portal");
        assertThat(TestDataService.canonicalDemoRole("child   portal")).isEqualTo("Child Portal");
        assertThat(TestDataService.canonicalDemoRole("church")).isEqualTo("Church");
        // An unknown role is passed through, so older free-text callers keep working
        assertThat(TestDataService.canonicalDemoRole("Volunteer Coordinator"))
                .isEqualTo("Volunteer Coordinator");
        assertThat(TestDataService.canonicalDemoRole(null)).isEqualTo("User");
        assertThat(TestDataService.canonicalDemoRole("   ")).isEqualTo("User");
    }

    /* ── staff roles ────────────────────────────────────────────────────── */

    @Test
    @DisplayName("SuperAdmin is created as a staff login keyed by app_user.user_id")
    void superAdminIsAStaffLogin() {
        Map<String, Object> out = svc.addDemoRole(TENANT, "SuperAdmin");

        ArgumentCaptor<AppUser> user = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepo).save(user.capture());
        assertThat(user.getValue().getRole()).isEqualTo("SuperAdmin");
        assertThat(user.getValue().getClientId()).isEqualTo(TENANT);

        SignUp su = onlySignup();
        assertThat(su.getChurch()).isFalse();
        assertThat(su.getClientId()).isEqualTo(user.getValue().getUserId());
        assertThat(out.get("role")).isEqualTo("SuperAdmin");
        assertThat(out.get("signupId")).isNotNull();
    }

    @Test
    @DisplayName("the other staff roles still build exactly as they did before")
    void otherStaffRolesUnchanged() {
        for (String role : List.of("Admin", "Accountant", "User")) {
            savedSignups.clear();
            Map<String, Object> out = svc.addDemoRole(TENANT, role);
            assertThat(out.get("role")).isEqualTo(role);
            assertThat(onlySignup().getChurch()).isFalse();
            assertThat(out.get("memberName")).asString().isNotBlank();
        }
    }

    /* ── Church ─────────────────────────────────────────────────────────── */

    @Test
    @DisplayName("Church is keyed by the tenant itself and has no app_user")
    void churchLoginShape() {
        Map<String, Object> out = svc.addDemoRole(TENANT, "Church");

        SignUp su = onlySignup();
        assertThat(su.getChurch()).isTrue();
        assertThat(su.getClientId()).isEqualTo(TENANT);
        assertThat(su.getChurchId()).isEqualTo(42);
        assertThat(su.getUsername()).startsWith("church");
        assertThat(out.get("role")).isEqualTo("Church");

        // A Church login manages the organisation account; giving it an app_user
        // or a member profile would make it show up as staff in the directory.
        verify(appUserRepo, never()).save(any(AppUser.class));
        verify(familyMemberRepo, never()).save(any(FamilyMember.class));
    }

    @Test
    @DisplayName("a tenant is refused a second Church login")
    void churchLoginIsOnePerTenant() {
        SignUp existing = new SignUp();
        existing.setClientId(TENANT);
        existing.setUsername("church_716790");
        existing.setChurch(true);
        existing.setDeleted(false);
        when(loginRepo.findAllByClientId(TENANT)).thenReturn(List.of(existing));

        // LoginRepository.findByClientId returns an Optional, so a second row on the
        // same client_id would make the church-registration endpoints throw instead
        // of answering. Refusing here is what keeps that lookup honest.
        assertThatThrownBy(() -> svc.addDemoRole(TENANT, "Church"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already has a Church login")
                .hasMessageContaining("church_716790");
        assertThat(savedSignups).isEmpty();
    }

    @Test
    @DisplayName("a deleted Church login does not block a replacement")
    void deletedChurchLoginDoesNotBlock() {
        SignUp gone = new SignUp();
        gone.setClientId(TENANT);
        gone.setUsername("church_old");
        gone.setChurch(true);
        gone.setDeleted(true);
        when(loginRepo.findAllByClientId(TENANT)).thenReturn(List.of(gone));

        assertThat(svc.addDemoRole(TENANT, "Church").get("role")).isEqualTo("Church");
    }

    @Test
    @DisplayName("a staff row sharing the tenant id is not mistaken for a Church login")
    void staffRowDoesNotBlockChurch() {
        SignUp staff = new SignUp();
        staff.setClientId(TENANT);
        staff.setUsername("daniel.manager_716790");
        staff.setChurch(false);
        staff.setDeleted(false);
        when(loginRepo.findAllByClientId(TENANT)).thenReturn(List.of(staff));

        assertThat(svc.addDemoRole(TENANT, "Church").get("role")).isEqualTo("Church");
    }

    /* ── portals ────────────────────────────────────────────────────────── */

    @Test
    @DisplayName("Member Portal is keyed by the member's member_ref, not by an app_user")
    void memberPortalShape() {
        Map<String, Object> out = svc.addDemoRole(TENANT, "Member Portal");

        ArgumentCaptor<FamilyMember> fm = ArgumentCaptor.forClass(FamilyMember.class);
        verify(familyMemberRepo).save(fm.capture());
        assertThat(fm.getValue().getRole()).isEqualTo("Head");
        assertThat(fm.getValue().getAppClientId()).isEqualTo(TENANT);

        SignUp su = onlySignup();
        assertThat(su.getChurch()).isFalse();
        assertThat(su.getClientId()).isEqualTo(fm.getValue().getMemberRef());
        assertThat(su.getClientId()).startsWith("MBR");
        assertThat(su.getUsername()).startsWith("member_");
        assertThat(out.get("role")).isEqualTo("Member Portal");
        verify(appUserRepo, never()).save(any(AppUser.class));
    }

    @Test
    @DisplayName("Child Portal puts the login on a member whose role is Child")
    void childPortalShape() {
        Map<String, Object> out = svc.addDemoRole(TENANT, "Child Portal");

        ArgumentCaptor<FamilyMember> fm = ArgumentCaptor.forClass(FamilyMember.class);
        verify(familyMemberRepo, org.mockito.Mockito.times(2)).save(fm.capture());
        List<FamilyMember> saved = fm.getAllValues();
        assertThat(saved.get(0).getRole()).isEqualTo("Head");     // the parent
        FamilyMember child = saved.get(1);
        // Demo Role Access derives the label from family_member.role; anything but
        // "Child" here and the row lists itself as a Member Portal login instead.
        assertThat(child.getRole()).isEqualTo("Child");
        assertThat(child.getFamily()).isSameAs(saved.get(0).getFamily());

        SignUp su = onlySignup();
        assertThat(su.getClientId()).isEqualTo(child.getMemberRef());
        assertThat(su.getUsername()).startsWith("child_");
        assertThat(out.get("role")).isEqualTo("Child Portal");
        assertThat(out.get("memberId")).isEqualTo(child.getId());
    }

    @Test
    @DisplayName("every role returns the signup id the access window is recorded against")
    void everyRoleReturnsASignupId() {
        for (String role : TestDataService.DEMO_ROLES) {
            savedSignups.clear();
            Map<String, Object> out = svc.addDemoRole(TENANT, role);
            // DemoRoleAdminController records the expiry window against this id, and
            // login enforcement looks it up by the same id. No id, no window, no expiry.
            assertThat(out.get("signupId")).as("%s must report its signup id", role).isNotNull();
            assertThat(out.get("username")).as("%s must report a username", role).isNotNull();
            assertThat(out.get("password")).as("%s must report a password", role).isNotNull();
        }
    }

    @Test
    @DisplayName("a real client is refused whatever the role")
    void nonDemoTenantRefused() {
        // Widened to demo AND self-service trial tenants when trial registration
        // was added; a real paying church is still refused, which is the point.
        for (String role : TestDataService.DEMO_ROLES) {
            assertThatThrownBy(() -> svc.addDemoRole("CGP-REAL-CHURCH", role))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Not a demo or trial tenant");
        }
    }

    @Test
    @DisplayName("a trial tenant is accepted by the same screen as a demo tenant")
    void trialTenantAccepted() {
        // Refused for a missing tenant, not for its prefix: the guard lets it
        // through and the lookup is what fails.
        assertThatThrownBy(() -> svc.addDemoRole(TestDataService.TRIAL_CLIENT_PREFIX + "123", "SuperAdmin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No such tenant");
    }
}
