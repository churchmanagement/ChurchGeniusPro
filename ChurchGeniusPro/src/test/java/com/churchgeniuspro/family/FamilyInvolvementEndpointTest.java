package com.churchgeniuspro.family;

import com.churchgeniuspro.controller.FamilyController;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.FamilyInvolvementService;
import com.churchgeniuspro.service.FamilyInvolvementService.Access;
import com.churchgeniuspro.service.FamilyService;
import com.churchgeniuspro.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * GET /api/families/involvement and /api/families/{id}/involvement (2026-11-02): the View
 * Family page rule first, then each section by its own page's rule — a SuperAdmin sees
 * everything, an Accountant (who can open View Family) sees none of it, a Member Portal
 * login with the Family grant sees none of it, and a missing permission removes that
 * section only. Bad input is refused.
 */
@DisplayName("/api/families/…/involvement — access and input")
class FamilyInvolvementEndpointTest {

    static final String CHURCH = "CHR-inv";
    FamilyInvolvementService service;
    FamilyController controller;

    @BeforeEach
    void setUp() {
        service = mock(FamilyInvolvementService.class);
        when(service.counts(any(), any(), any())).thenReturn(Map.of());
        when(service.details(any(), any(), any())).thenReturn(Map.of("familyId", 1));
        controller = new FamilyController(mock(FamilyService.class), mock(FamilyMemberRepository.class), mock(SubscriptionService.class));
        ReflectionTestUtils.setField(controller, "involvementService", service);
        ReflectionTestUtils.setField(controller, "accessPolicy", new AiAccessPolicy());   // no plan limits in the test
    }

    private static MockHttpServletRequest staff(String role, Map<String, Object> privileges) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.getSession(true).setAttribute("username", role.toLowerCase() + "@x");
        r.getSession().setAttribute("role", role);
        r.getSession().setAttribute("appClientId", CHURCH);
        // stored the way the app stores it: the saved permissions as a JSON string
        if (privileges != null) r.getSession().setAttribute("privileges", toJson(privileges));
        return r;
    }

    private static String toJson(Map<String, Object> m) {
        StringBuilder b = new StringBuilder("{");
        m.forEach((k, v) -> b.append(b.length() > 1 ? "," : "").append('"').append(k).append("\":").append(v));
        return b.append('}').toString();
    }

    private Access accessFor(MockHttpServletRequest r) {
        controller.involvementCounts("1,2", r);
        ArgumentCaptor<Access> a = ArgumentCaptor.forClass(Access.class);
        verify(service, atLeastOnce()).counts(eq(CHURCH), eq(List.of(1, 2)), a.capture());
        reset(service);
        when(service.counts(any(), any(), any())).thenReturn(Map.of());
        return a.getValue();
    }

    @Test @DisplayName("SuperAdmin: every section; Admins see all follow-ups")
    void superAdmin() {
        Access a = accessFor(staff("SuperAdmin", null));
        assertThat(a.volunteers()).isTrue(); assertThat(a.kidsMinistry()).isTrue(); assertThat(a.prayer()).isTrue();
        assertThat(a.followUps()).isTrue(); assertThat(a.followUpsAll()).isTrue(); assertThat(a.groups()).isTrue();
    }

    @Test @DisplayName("Accountant can open View Family but sees no involvement")
    void accountant() {
        assertThat(accessFor(staff("Accountant", null)).any()).isFalse();
    }

    @Test @DisplayName("A permission switched off removes only that section")
    void perSectionPermission() {
        Access a = accessFor(staff("Admin", Map.of("admin.groups", false, "general.ministry.prayer", false)));
        assertThat(a.groups()).isFalse(); assertThat(a.prayer()).isFalse();
        assertThat(a.volunteers()).isTrue(); assertThat(a.followUps()).isTrue(); assertThat(a.kidsMinistry()).isTrue();
    }

    @Test @DisplayName("Member Portal login with the Family grant: page allowed, involvement none")
    void memberPortal() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.getSession(true).setAttribute("role", "Member");
        r.getSession().setAttribute("memberId", 7);
        r.getSession().setAttribute("appClientId", CHURCH);
        r.getSession().setAttribute("memberPrivileges", "{\"admin.family\":true}");
        ResponseEntity<Map<String, Object>> res = controller.involvementCounts("1,2", r);
        if (res.getStatusCode().value() == 200) {
            ArgumentCaptor<Access> a = ArgumentCaptor.forClass(Access.class);
            verify(service).counts(any(), any(), a.capture());
            assertThat(a.getValue().any()).isFalse();
        } else {
            assertThat(res.getStatusCode().value()).isEqualTo(403);
        }
    }

    @Test @DisplayName("No session / User role → 403; bad ids or more than 50 → 400; details 404 for another church")
    void refusals() {
        assertThat(controller.involvementCounts("1", new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.involvementCounts("1", staff("User", null)).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.involvementCounts("1,abc", staff("Admin", null)).getStatusCode().value()).isEqualTo(400);
        String many = String.join(",", java.util.stream.IntStream.rangeClosed(1, 51).mapToObj(String::valueOf).toList());
        assertThat(controller.involvementCounts(many, staff("Admin", null)).getStatusCode().value()).isEqualTo(400);
        when(service.details(eq(CHURCH), eq(99), any())).thenReturn(null);
        assertThat(controller.involvementDetails(99, staff("Admin", null)).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.involvementDetails(1, staff("Admin", null)).getStatusCode().value()).isEqualTo(200);
        assertThat(controller.involvementDetails(1, new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(403);
    }

    @Test @DisplayName("Body reports which sections are shown")
    @SuppressWarnings("unchecked")
    void bodyShape() {
        Map<String, Object> body = controller.involvementCounts("1", staff("Admin", Map.of("admin.groups", false))).getBody();
        assertThat((Map<String, Object>) body.get("allowed")).containsEntry("volunteers", true).containsEntry("followUps", true).containsEntry("groups", false);
        assertThat(body).containsKey("families");
    }
}
