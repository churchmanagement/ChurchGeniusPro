package com.churchgeniuspro.demo;

import com.churchgeniuspro.controller.DemoRoleAdminController;
import com.churchgeniuspro.service.DemoAccessService;
import com.churchgeniuspro.service.DemoReminderScheduler;
import com.churchgeniuspro.service.TestDataService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Adding a role is only half the job: the row has to turn up in Demo Role Access
 * with the right label, or its expiry, status and delivery switches are
 * unreachable from the screen.
 *
 * <p>The listing query resolves each login's tenant three different ways because
 * {@code signup.client_id} holds three different things. A role whose shape is not
 * covered by one of those branches simply never appears — no error, no empty row,
 * nothing — which is why the branches are asserted here rather than left to a
 * manual look at the screen.
 */
class DemoRoleAccessListingTest {

    private static String listingSql() throws Exception {
        Field f = DemoAccessService.class.getDeclaredField("DEMO_LOGINS_SQL");
        f.setAccessible(true);
        return (String) f.get(null);
    }

    @Test
    @DisplayName("the listing resolves all three login shapes")
    void allThreeShapesAreResolved() throws Exception {
        String sql = listingSql();
        // Church: signup.client_id IS the tenant
        assertThat(sql).contains("'Church' AS role_label");
        // Staff: signup.client_id is app_user.user_id
        assertThat(sql).contains("JOIN app_user u ON u.user_id = s.client_id");
        // Portal: signup.client_id is family_member.member_ref
        assertThat(sql).contains("JOIN family_member fm ON fm.member_ref = s.client_id");
        assertThat(sql.split("UNION ALL", -1)).hasSize(3);
    }

    @Test
    @DisplayName("staff rows carry the real app_user role, so SuperAdmin reads as SuperAdmin")
    void staffRowsShowTheirOwnRole() throws Exception {
        // The label is read from app_user.role rather than hardcoded, which is what
        // lets a newly added SuperAdmin appear under its own name instead of "Staff".
        assertThat(listingSql()).contains("COALESCE(u.role, 'Staff')");
    }

    @Test
    @DisplayName("portal rows are split into Member Portal and Child Portal by the member's role")
    void portalRowsSplitByMemberRole() throws Exception {
        assertThat(listingSql()).contains(
                "CASE WHEN LOWER(fm.role) = 'child' THEN 'Child Portal' ELSE 'Member Portal' END");
    }

    @Test
    @DisplayName("every role the screen offers has a matching label in the listing")
    void everyOfferedRoleCanAppear() throws Exception {
        String sql = listingSql();
        // Staff roles arrive via app_user.role, so only the three special shapes
        // need a literal in the query.
        assertThat(sql).contains("'Church'");
        assertThat(sql).contains("'Member Portal'");
        assertThat(sql).contains("'Child Portal'");
    }

    /* ── the list the screen is served ──────────────────────────────────── */

    private static DemoRoleAdminController controller() {
        return new DemoRoleAdminController(mock(DemoAccessService.class),
                                           mock(TestDataService.class),
                                           mock(DemoReminderScheduler.class));
    }

    private static HttpServletRequest requestAs(String role) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(req.getSession(false)).thenReturn(role == null ? null : session);
        when(session.getAttribute("role")).thenReturn(role);
        return req;
    }

    @Test
    @DisplayName("the screen is served the roles this server can actually build")
    @SuppressWarnings("unchecked")
    void optionsEndpointServesTheRealList() {
        ResponseEntity<?> res = controller().options(requestAs("ServiceAdmin"));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = (Map<String, Object>) res.getBody();
        assertThat((List<String>) body.get("roles")).isEqualTo(TestDataService.DEMO_ROLES);
    }

    @Test
    @DisplayName("the role list is behind the same Service Admin guard as the rest")
    void optionsEndpointIsGuarded() {
        assertThat(controller().options(requestAs("Admin")).getStatusCode().value()).isEqualTo(401);
        assertThat(controller().options(requestAs(null)).getStatusCode().value()).isEqualTo(401);
    }
}
