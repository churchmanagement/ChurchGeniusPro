package com.churchgeniuspro.security;

import com.churchgeniuspro.controller.FamilyController;
import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.model.FamilyBO;
import com.churchgeniuspro.model.FamilyMemberBO;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.service.FamilyService;
import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.util.RoleGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Security fix 2026-10-29. Two confirmed gaps:
 * <ol>
 *   <li>Any Member Portal login could read the Kids Ministry children list (allergies,
 *       parent phones): the data endpoints used {@code requireAdminOrUser}, which lets
 *       member sessions through for page routes.</li>
 *   <li>Any Member Portal login could read and overwrite any family of its church: the
 *       family endpoints checked only a permission key, which a member's (staff-less)
 *       permission map always "allowed"; the family and member lists had no check at all.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Member Portal logins cannot reach other families or Kids Ministry data")
class MemberDataAccessTest {

    static final String CHURCH = "CHR-ours";
    static final int OWN_FAMILY = 10, OTHER_FAMILY = 20, SELF = 42;

    @Mock FamilyService familyService;
    @Mock FamilyMemberRepository memberRepo;
    @Mock SubscriptionService subscriptionService;
    @Mock AppUserRepository appUserRepo;

    FamilyController controller;

    @BeforeEach
    void setUp() {
        controller = new FamilyController(familyService, memberRepo, subscriptionService);
        ReflectionTestUtils.setField(controller, "appUserRepository", appUserRepo);

        Family own = new Family(); own.setId(OWN_FAMILY);
        FamilyMember self = new FamilyMember(); self.setId(SELF); self.setFamily(own); self.setAppClientId(CHURCH);
        when(memberRepo.findByIdAndTenant(eq(SELF), eq(CHURCH))).thenReturn(Optional.of(self));

        // Staff "User" login linked by e-mail to the same member (uses the Member Portal).
        AppUser staff = new AppUser(); staff.setId(7); staff.setEmail("linked@example.com"); staff.setClientId(CHURCH);
        when(appUserRepo.findById(7)).thenReturn(Optional.of(staff));
        when(memberRepo.findActiveByEmailAndTenant(eq("linked@example.com"), eq(CHURCH))).thenReturn(List.of(self));

        when(familyService.getById(anyInt(), anyString())).thenReturn(Map.of("id", 1));
        when(familyService.getFamilies(anyString(), anyBoolean(), anyBoolean(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(Map.of("items", List.of(), "total", 0));
        Family saved = new Family(); saved.setId(OWN_FAMILY); saved.setMembers(new ArrayList<>());
        when(familyService.update(anyInt(), any(), anyString())).thenReturn(saved);
        when(familyService.save(any(), anyString())).thenReturn(saved);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 99); row.put("firstName", "Sample"); row.put("lastName", "Person"); row.put("familyId", OTHER_FAMILY);
        row.put("email", "sample@example.com"); row.put("phone", "913-555-0100"); row.put("birthdayMonth", 5);
        row.put("deleted", false); row.put("status", "Active");
        when(familyService.getAllMembers(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyBoolean(), anyBoolean(), anyBoolean(), anyString())).thenReturn(List.of(row));
    }

    // ── sessions ──
    static MockHttpServletRequest member(String privileges) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("appClientId", CHURCH);
        s.setAttribute("username", "member@example.com");   // real member logins carry a username
        s.setAttribute("role", "Member");
        s.setAttribute("memberId", SELF);
        if (privileges != null) s.setAttribute("memberPrivileges", privileges);
        req.setSession(s);
        return req;
    }
    static MockHttpServletRequest staff(String role) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("appClientId", CHURCH);
        s.setAttribute("clientId", CHURCH);
        s.setAttribute("username", role.toLowerCase() + "@example.com");
        s.setAttribute("role", role);
        s.setAttribute("appUserId", 7);
        req.setSession(s);
        return req;
    }
    static FamilyBO body() {
        FamilyMemberBO m = new FamilyMemberBO(); m.setId(SELF); m.setFirstName("Sample"); m.setLastName("Person");
        FamilyBO bo = new FamilyBO(); bo.setMembers(List.of(m));
        return bo;
    }
    static int code(ResponseEntity<?> r) { return r.getStatusCode().value(); }

    @Nested @DisplayName("A Member Portal login (no Family grant)")
    class PlainMember {
        @Test void cannotReadAnotherFamily() {
            assertThat(code(controller.getFamilyById(OTHER_FAMILY, member(null)))).isEqualTo(403);
            verify(familyService, never()).getById(eq(OTHER_FAMILY), anyString());
        }
        @Test void canReadOwnFamily() {
            assertThat(code(controller.getFamilyById(OWN_FAMILY, member(null)))).isEqualTo(200);
        }
        @Test void cannotUpdateAnotherFamily() {
            assertThat(code(controller.updateFamily(OTHER_FAMILY, body(), member(null)))).isEqualTo(403);
            verify(familyService, never()).update(anyInt(), any(), anyString());
        }
        @Test void canUpdateOwnFamily() {
            assertThat(code(controller.updateFamily(OWN_FAMILY, body(), member(null)))).isEqualTo(200);
            verify(familyService).update(eq(OWN_FAMILY), any(), eq(CHURCH));
        }
        @Test void canRemoveSomeoneFromOwnFamilyOnly() {
            assertThat(code(controller.deleteMember(OWN_FAMILY, 43, member(null)))).isEqualTo(200);
            assertThat(code(controller.deleteMember(OTHER_FAMILY, 43, member(null)))).isEqualTo(403);
            verify(familyService, never()).softDeleteMember(eq(OTHER_FAMILY), anyInt(), anyString());
        }
        @Test void cannotCreateDeleteDeactivateOrBulkChange() {
            assertThat(code(controller.saveFamily(body(), member(null)))).isEqualTo(403);
            assertThat(code(controller.deleteFamily(OWN_FAMILY, member(null)))).isEqualTo(403);
            assertThat(code(controller.deleteFamily(OTHER_FAMILY, member(null)))).isEqualTo(403);
            assertThat(code(controller.setInactive(OTHER_FAMILY, true, member(null)))).isEqualTo(403);
            assertThat(code(controller.restoreFamily(OTHER_FAMILY, member(null)))).isEqualTo(403);
            assertThat(code(controller.bulkAction(Map.of("ids", List.of(OTHER_FAMILY), "action", "delete"), member(null)))).isEqualTo(403);
            verifyNoInteractions(familyService);
        }
        @Test void cannotListFamilies() {
            assertThat(code(controller.getFamilies("", false, false, "", 0, 10, member(null)))).isEqualTo(403);
        }
        @Test void memberListIsNamesOnly() {
            List<Map<String, Object>> rows = controller.getAllMembers("", "", "", "", "", true, true, false, member(null)).getBody();
            assertThat(rows).singleElement().satisfies(r -> {
                assertThat(r).containsEntry("firstName", "Sample").containsEntry("lastName", "Person")
                        .doesNotContainKeys("email", "phone", "birthdayMonth", "deleted", "status");
            });
            // deleted / inactive people are never requested for a member, whatever the query string says
            verify(familyService).getAllMembers(anyString(), anyString(), anyString(), eq(""), eq(""), eq(false), eq(false), anyBoolean(), eq(CHURCH));
        }
        @Test void neverSavedPermissionsDoNotOpenTheStaffFamiliesPages() {
            assertThat(controller.viewFamilyPage(member(null))).isEqualTo(RoleGuard.FORWARD_ACCESS_DENIED);
            assertThat(controller.familyPage(member(null))).isEqualTo(RoleGuard.FORWARD_ACCESS_DENIED);
        }
        @Test void familyTabSwitchedOffMeansNoOwnFamilyEditsEither() {
            String off = "{\"member.family\":false}";
            assertThat(code(controller.updateFamily(OWN_FAMILY, body(), member(off)))).isEqualTo(403);
            assertThat(code(controller.getFamilyById(OWN_FAMILY, member(off)))).isEqualTo(403);
        }
    }

    @Nested @DisplayName("A Member Portal login the church granted Family access")
    class GrantedMember {
        final String granted = "{\"admin.family\":true,\"admin.family.edit\":true,\"admin.family.delete\":false}";
        @Test void managesFamiliesWithinItsPermissions() {
            assertThat(code(controller.getFamilies("", false, false, "", 0, 10, member(granted)))).isEqualTo(200);
            assertThat(code(controller.getFamilyById(OTHER_FAMILY, member(granted)))).isEqualTo(200);
            assertThat(code(controller.updateFamily(OTHER_FAMILY, body(), member(granted)))).isEqualTo(200);
            assertThat(code(controller.deleteFamily(OTHER_FAMILY, member(granted)))).isEqualTo(403);   // delete not granted
            assertThat(controller.viewFamilyPage(member(granted))).isEqualTo("forward:/viewFamily.html");
            assertThat(controller.getAllMembers("", "", "", "", "", false, false, false, member(granted)).getBody())
                    .singleElement().satisfies(r -> assertThat(r).containsKey("email"));
        }
    }

    @Nested @DisplayName("Staff logins")
    class Staff {
        @Test void familyManagersKeepFullAccess() {
            for (String role : List.of("SuperAdmin", "Admin", "Accountant")) {
                assertThat(code(controller.updateFamily(OTHER_FAMILY, body(), staff(role)))).as(role).isEqualTo(200);
                assertThat(code(controller.saveFamily(body(), staff(role)))).as(role).isEqualTo(200);
                assertThat(code(controller.getFamilies("", false, false, "", 0, 10, staff(role)))).as(role).isEqualTo(200);
            }
        }
        @Test void userRoleCannotChangeOtherFamiliesButCanItsOwnFromThePortal() {
            assertThat(code(controller.updateFamily(OTHER_FAMILY, body(), staff("User")))).isEqualTo(403);
            assertThat(code(controller.saveFamily(body(), staff("User")))).isEqualTo(403);
            assertThat(code(controller.updateFamily(OWN_FAMILY, body(), staff("User")))).isEqualTo(200);   // linked by e-mail
            // still reads the list (Prayer Requests and Kids Ministry pages use it)
            assertThat(code(controller.getFamilies("", false, false, "", 0, 10, staff("User")))).isEqualTo(200);
        }
        @Test void savedPermissionsStillApplyToManagers() {
            MockHttpServletRequest req = staff("Admin");
            req.getSession().setAttribute("privileges", "{\"admin.family.edit\":false}");
            assertThat(code(controller.updateFamily(OTHER_FAMILY, body(), req))).isEqualTo(403);
        }
    }

    @Nested @DisplayName("Kids Ministry data")
    class Kids {
        @Test void staffOnlyGuardRefusesMemberAndKidsPortalSessions() {
            assertThat(RoleGuard.requireStaffAdminOrUser(member(null))).isEqualTo(RoleGuard.FORWARD_ACCESS_DENIED);
            MockHttpServletRequest kid = member(null);
            kid.getSession().setAttribute("memberRole", "Child");
            assertThat(RoleGuard.requireStaffAdminOrUser(kid)).isEqualTo(RoleGuard.FORWARD_ACCESS_DENIED);
            for (String role : List.of("SuperAdmin", "Admin", "User"))
                assertThat(RoleGuard.requireStaffAdminOrUser(staff(role))).as(role).isNull();
            assertThat(RoleGuard.requireStaffAdminOrUser(staff("Accountant"))).isEqualTo(RoleGuard.FORWARD_ACCESS_DENIED);
            // the page guard itself is unchanged (members still get Access Denied pages, not a bounce)
            assertThat(RoleGuard.requireAdminOrUser(member(null))).isNull();
        }
        @Test void everyKidsMinistryEndpointUsesTheStaffOnlyGuard() throws IOException {
            String src = Files.readString(Paths.get("src/main/java/com/churchgeniuspro/controller/KidsMinistryController.java"));
            assertThat(src).doesNotContain("RoleGuard.requireAdminOrUser(").contains("RoleGuard.requireStaffAdminOrUser(");
        }
    }
}
