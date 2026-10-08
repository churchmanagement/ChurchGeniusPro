package com.churchgeniuspro.security;

import com.churchgeniuspro.controller.AppUserController;
import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.UserPermissions;
import com.churchgeniuspro.model.AppUserBO;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.UserPermissionsRepository;
import com.churchgeniuspro.service.AppUserService;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.PasswordResetService;
import com.churchgeniuspro.service.WhatsAppSenderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Staff-user management is owner-only and tenant-bound.
 *
 * <p>Before: {@code POST /api/users} took {@code clientId} and {@code role} from the
 * body with no session check, so any session could create a SuperAdmin in any
 * church; every {@code /api/users/{id}} mutator loaded by bare id; and
 * {@code GET /api/users} returned every church's users to any non-owner session.
 * Uses the real service over mocked repositories so the tenant filter is exercised
 * where it lives, not stubbed away.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Staff user management — owner-only, tenant-bound")
class StaffUserManagementTest {

    private static final String OURS   = "CHR-ours";
    private static final String THEIRS = "CHR-theirs";

    @Mock AppUserRepository        userRepo;
    @Mock LoginRepository          loginRepo;
    @Mock EmailService             emailService;
    @Mock WhatsAppSenderService    whatsApp;
    @Mock UserPermissionsRepository permsRepo;
    @Mock FamilyMemberRepository   familyMemberRepo;
    @Mock PasswordResetService     passwordReset;

    AppUserController controller;

    @BeforeEach
    void setUp() {
        AppUserService service = new AppUserService(userRepo, loginRepo, emailService, whatsApp, permsRepo);
        ReflectionTestUtils.setField(service, "baseUrl", "https://churchgeniuspro.net");
        controller = new AppUserController(service, permsRepo, familyMemberRepo, loginRepo, emailService, passwordReset);
        when(userRepo.save(any(AppUser.class))).thenAnswer(i -> {
            AppUser u = i.getArgument(0);
            if (u.getId() == null) u.setId(101);   // the controller returns the id in a Map.of
            return u;
        });
    }

    private MockHttpServletRequest ownerOf(String clientId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("church", true);
        s.setAttribute("clientId", clientId);
        s.setAttribute("username", "owner@" + clientId);
        req.setSession(s);
        return req;
    }

    private MockHttpServletRequest staffOf(String clientId, String role, int appUserId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("clientId", clientId);
        s.setAttribute("appClientId", clientId);
        s.setAttribute("username", "staff@" + clientId);
        s.setAttribute("role", role);
        s.setAttribute("appUserId", appUserId);
        req.setSession(s);
        return req;
    }

    private MockHttpServletRequest memberOf(String clientId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("clientId", "MBRabc");
        s.setAttribute("appClientId", clientId);
        s.setAttribute("role", "Member");
        s.setAttribute("memberId", 55);
        req.setSession(s);
        return req;
    }

    private AppUserBO bo(String role, String clientId) {
        AppUserBO bo = new AppUserBO();
        bo.setFirstName("A"); bo.setLastName("B"); bo.setEmail("a@b.org");
        bo.setRole(role); bo.setClientId(clientId);
        return bo;
    }

    private AppUser user(int id, String clientId) {
        AppUser u = new AppUser();
        u.setId(id); u.setClientId(clientId); u.setEnabled(true);
        u.setEmail("u" + id + "@x.org"); u.setRole("User"); u.setUserId("USR" + id);
        return u;
    }

    @Nested
    @DisplayName("create")
    class Create {
        @Test void staffSessionCannotCreateUsers() {
            ResponseEntity<?> res = controller.create(bo("SuperAdmin", THEIRS), staffOf(OURS, "Admin", 1));
            assertThat(res.getStatusCode().value()).isEqualTo(403);
            verify(userRepo, never()).save(any());
        }
        @Test void memberSessionCannotCreateUsers() {
            ResponseEntity<?> res = controller.create(bo("SuperAdmin", THEIRS), memberOf(OURS));
            assertThat(res.getStatusCode().value()).isEqualTo(403);
            verify(userRepo, never()).save(any());
        }
        @Test void ownerCreatesOnlyInTheirOwnChurchWhateverTheBodySays() {
            ResponseEntity<?> res = controller.create(bo("Admin", THEIRS), ownerOf(OURS));
            assertThat(res.getStatusCode().is2xxSuccessful()).as("%s", res.getBody()).isTrue();
            ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
            verify(userRepo).save(saved.capture());
            assertThat(saved.getValue().getClientId()).isEqualTo(OURS);
        }
        @Test void roleMustBeAKnownStaffRole() {
            // "ServiceAdmin" as an app_user.role used to unlock four service-admin controllers.
            ResponseEntity<?> res = controller.create(bo("ServiceAdmin", OURS), ownerOf(OURS));
            assertThat(res.getStatusCode().value()).isEqualTo(400);
            verify(userRepo, never()).save(any());
        }
    }

    @Nested
    @DisplayName("mutations by id")
    class ById {
        @Test void anotherChurchsUserIsNotFound() {
            when(userRepo.findByIdAndClientId(9, OURS)).thenReturn(Optional.empty());
            when(userRepo.findById(9)).thenReturn(Optional.of(user(9, THEIRS)));  // exists — but not ours

            assertThat(controller.update(9, bo("Admin", OURS), ownerOf(OURS)).getStatusCode().value()).isEqualTo(400);
            assertThat(controller.delete(9, ownerOf(OURS)).getStatusCode().value()).isEqualTo(400);
            assertThat(controller.toggle(9, ownerOf(OURS)).getStatusCode().value()).isEqualTo(400);
            assertThat(controller.updatePrivileges(9, Map.of("privileges", "{}"), ownerOf(OURS)).getStatusCode().value()).isEqualTo(400);
            verify(userRepo, never()).save(any());
        }
        @Test void ownUserIsUpdatable() {
            when(userRepo.findByIdAndClientId(3, OURS)).thenReturn(Optional.of(user(3, OURS)));
            ResponseEntity<?> res = controller.toggle(3, ownerOf(OURS));
            assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        }
        @Test void staffSessionCannotMutate() {
            assertThat(controller.delete(3, staffOf(OURS, "SuperAdmin", 3)).getStatusCode().value()).isEqualTo(403);
            verify(userRepo, never()).findById(any());
            verify(userRepo, never()).findByIdAndClientId(any(), anyString());
        }
        @Test void linkingRefusesAnIdFromAnotherChurch() {
            when(userRepo.findAllById(any())).thenReturn(java.util.List.of(user(1, OURS), user(2, THEIRS)));
            ResponseEntity<?> res = controller.linkUsers(Map.of("userIds", java.util.List.of(1, 2)), ownerOf(OURS));
            assertThat(res.getStatusCode().value()).isEqualTo(400);
            verify(userRepo, never()).save(any());
        }
    }

    @Nested
    @DisplayName("listing")
    class Listing {
        @Test void staffSessionsSeeOnlyTheirOwnChurch() {
            controller.getAll(staffOf(OURS, "Admin", 1));
            verify(userRepo).findByClientIdAndDeleteFlagFalseOrderByLastNameAscFirstNameAsc(OURS);
            verify(userRepo, never()).findByDeleteFlagFalseOrderByLastNameAscFirstNameAsc();
        }
        @Test void memberSessionsSeeOnlyTheirOwnChurch() {
            controller.getAll(memberOf(OURS));
            verify(userRepo).findByClientIdAndDeleteFlagFalseOrderByLastNameAscFirstNameAsc(OURS);
            verify(userRepo, never()).findByDeleteFlagFalseOrderByLastNameAscFirstNameAsc();
        }
        @Test void noSessionIs401() {
            assertThat(controller.getAll(new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(401);
        }
    }

    @Nested
    @DisplayName("permissions map")
    class Permissions {
        @Test void aUserMayReadTheirOwnMap() {
            when(permsRepo.findByAppUserId(7)).thenReturn(Optional.of(new UserPermissions()));
            assertThat(controller.getPermissions(7, staffOf(OURS, "User", 7)).getStatusCode().value()).isEqualTo(200);
        }
        @Test void aUserMayNotReadAnotherUsersMap() {
            assertThat(controller.getPermissions(8, staffOf(OURS, "User", 7)).getStatusCode().value()).isEqualTo(403);
        }
        @Test void aUserMayNotGrantThemselvesFullAccess() {
            // Posting "{}" to your own id used to refresh the session's privileges in place.
            ResponseEntity<?> res = controller.savePermissions(7, Map.of("permissions", "{}"), staffOf(OURS, "User", 7));
            assertThat(res.getStatusCode().value()).isEqualTo(403);
            verify(permsRepo, never()).save(any());
        }
        @Test void ownerMayNotWriteAnotherChurchsMap() {
            when(userRepo.findByIdAndClientId(8, OURS)).thenReturn(Optional.empty());
            ResponseEntity<?> res = controller.savePermissions(8, Map.of("permissions", "{}"), ownerOf(OURS));
            assertThat(res.getStatusCode().value()).isEqualTo(404);
            verify(permsRepo, never()).save(any());
        }
    }
}
