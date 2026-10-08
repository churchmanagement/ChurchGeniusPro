package com.churchgeniuspro.family;

import com.churchgeniuspro.controller.FamilyController;
import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.model.FamilyBO;
import com.churchgeniuspro.model.FamilyMemberBO;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import com.churchgeniuspro.service.FamilyService;
import com.churchgeniuspro.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every {@code /api/families/{id}} handler used to resolve the family by bare id
 * ({@code findById} / {@code findByIdWithMembers}), so an Admin of church A could
 * read, edit, delete, restore or deactivate church B's families. The service now
 * only loads through the tenant-scoped finders, and GET / restore carry the same
 * role gate as the {@code /viewfamily} page.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Family API — tenant-bound")
class FamilyTenantIsolationTest {

    private static final String OURS   = "CHR-ours";
    private static final String THEIRS = "CHR-theirs";

    @Mock FamilyRepository       familyRepo;
    @Mock FamilyMemberRepository memberRepo;
    @Mock SubscriptionService    subscriptionService;

    FamilyController controller;

    @BeforeEach
    void setUp() {
        FamilyService service = new FamilyService(familyRepo, memberRepo);
        controller = new FamilyController(service, memberRepo, subscriptionService);
        when(familyRepo.save(any(Family.class))).thenAnswer(i -> i.getArgument(0));
        // The scoped finders are the only ones the service may use: they answer
        // for OUR family (77) and never for THEIR family (4242).
        when(familyRepo.findByIdAndAppClientId(77, OURS)).thenReturn(Optional.of(family(77, OURS)));
        when(familyRepo.findByIdAndAppClientIdWithMembers(77, OURS)).thenReturn(Optional.of(family(77, OURS)));
        when(familyRepo.findByIdAndAppClientId(4242, OURS)).thenReturn(Optional.empty());
        when(familyRepo.findByIdAndAppClientIdWithMembers(4242, OURS)).thenReturn(Optional.empty());
        // The unscoped finders still "know" the foreign row — the fix is not using them.
        when(familyRepo.findById(anyInt())).thenAnswer(i -> Optional.of(family(i.getArgument(0), THEIRS)));
        when(familyRepo.findByIdWithMembers(anyInt())).thenAnswer(i -> Optional.of(family(i.getArgument(0), THEIRS)));
    }

    private static Family family(int id, String clientId) {
        Family f = new Family();
        f.setId(id);
        f.setAppClientId(clientId);
        FamilyMember head = new FamilyMember();
        head.setId(id * 10);
        head.setRole("Head");
        head.setFirstName("Ada");
        head.setLastName("Lovelace");
        head.setFamily(f);
        f.getMembers().add(head);
        return f;
    }

    private static MockHttpServletRequest adminOf(String clientId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("appClientId", clientId);
        s.setAttribute("clientId", clientId);
        s.setAttribute("username", "admin@" + clientId);
        s.setAttribute("role", "Admin");
        req.setSession(s);
        return req;
    }

    private static FamilyBO bo() {
        FamilyMemberBO m = new FamilyMemberBO();
        m.setRole("Head"); m.setFirstName("Ada"); m.setLastName("Lovelace");
        FamilyBO bo = new FamilyBO();
        bo.setMembers(List.of(m));
        return bo;
    }

    // ── read ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /api/families/{id} cannot read another church's family")
    void getByIdIsTenantScoped() {
        ResponseEntity<Map<String, Object>> res = controller.getFamilyById(4242, adminOf(OURS));
        assertThat(res.getStatusCode().value()).isEqualTo(400);   // same "not found" as an unknown id
        verify(familyRepo, never()).findByIdWithMembers(any());

        ResponseEntity<Map<String, Object>> ok = controller.getFamilyById(77, adminOf(OURS));
        assertThat(ok.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(ok.getBody()).containsEntry("id", 77);
    }

    @Test
    @DisplayName("GET /api/families/{id} now carries the page's role gate")
    void getByIdRequiresRole() {
        MockHttpServletRequest user = adminOf(OURS);
        user.getSession().setAttribute("role", "User");
        assertThat(controller.getFamilyById(77, user).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.getFamilyById(77, new MockHttpServletRequest()).getStatusCode().value())
                .isEqualTo(403);
    }

    // ── mutate ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("update / delete / inactive / restore / member-delete refuse a foreign id and write nothing")
    void mutatorsAreTenantScoped() {
        MockHttpServletRequest req = adminOf(OURS);

        assertThat(controller.updateFamily(4242, bo(), req).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.deleteFamily(4242, req).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.setInactive(4242, true, req).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.restoreFamily(4242, req).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.deleteMember(4242, 42420, req).getStatusCode().value()).isEqualTo(400);

        verify(familyRepo, never()).save(any(Family.class));
        verify(memberRepo, never()).save(any(FamilyMember.class));
        verify(familyRepo, never()).findById(any());
    }

    @Test
    @DisplayName("bulk action skips nothing silently: a foreign id in the list aborts the batch")
    void bulkActionIsTenantScoped() {
        Map<String, Object> body = Map.of("ids", List.of(77, 4242), "action", "delete");
        ResponseEntity<Map<String, Object>> res = controller.bulkAction(body, adminOf(OURS));
        assertThat(res.getStatusCode().is2xxSuccessful()).isFalse();
        // 77 was processed before the foreign id threw; the foreign family was never touched
        verify(familyRepo, never()).findById(4242);
    }

    @Test
    @DisplayName("the same handlers keep working for the church's own family")
    void mutatorsWorkForOwnTenant() {
        MockHttpServletRequest req = adminOf(OURS);

        assertThat(controller.updateFamily(77, bo(), req).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(controller.deleteFamily(77, req).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(controller.setInactive(77, true, req).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(controller.restoreFamily(77, req).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(controller.deleteMember(77, 770, req).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(controller.bulkAction(Map.of("ids", List.of(77), "action", "inactive"), req)
                .getStatusCode().is2xxSuccessful()).isTrue();

        verify(familyRepo, never()).findById(any());
        verify(familyRepo, never()).findByIdWithMembers(any());
    }

    @Test
    @DisplayName("no session → 401, nothing looked up")
    void noSessionIsRejected() {
        MockHttpServletRequest anon = new MockHttpServletRequest();
        assertThat(controller.deleteFamily(77, anon).getStatusCode().value()).isIn(401, 403);
        verify(familyRepo, never()).findByIdAndAppClientId(anyInt(), anyString());
        verify(familyRepo, never()).findById(any());
    }
}
