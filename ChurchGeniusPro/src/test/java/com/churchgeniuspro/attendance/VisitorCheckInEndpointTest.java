package com.churchgeniuspro.attendance;

import com.churchgeniuspro.controller.AttendanceController;
import com.churchgeniuspro.service.AttendanceService;
import com.churchgeniuspro.service.AttendanceVolunteerNotifyService;
import com.churchgeniuspro.service.VisitorCheckInService;
import com.churchgeniuspro.service.VisitorCheckInService.CheckInException;
import com.churchgeniuspro.util.AppClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Visitor check-in (2026-11-04): permission gates, error mapping, pure rules, page wiring. */
@DisplayName("Visitor check-in — endpoints, rules and pages")
class VisitorCheckInEndpointTest {

    static final String OURS = "CH-1";
    AttendanceController controller;
    VisitorCheckInService visitors;

    MockHttpServletRequest req(String role, String privileges) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "u"); s.setAttribute("role", role); s.setAttribute("clientId", OURS);
        s.setAttribute("appClientId", OURS); s.setAttribute("appUserId", 1); s.setAttribute("church", false);
        if (privileges != null) s.setAttribute("privileges", privileges);
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
    }

    @BeforeEach void wire() {
        controller = new AttendanceController(mock(AttendanceService.class), mock(AttendanceVolunteerNotifyService.class));
        visitors = mock(VisitorCheckInService.class);
        controller.setVisitorCheckInService(visitors);
    }

    private static Map<String, Object> reg() {
        return Map.of("kind", "INDIVIDUAL", "targetId", 5, "people", List.of(Map.of("firstName", "A", "lastName", "B")));
    }

    @Test @DisplayName("Edit permission off → register / new service / edit refused; Delete permission off → delete refused")
    void permissionGates() {
        String noEdit = "{\"general.attendance.edit\":false}", noDel = "{\"general.attendance.delete\":false}";
        assertThat(controller.registerVisitors(reg(), req("User", noEdit)).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.createTarget(Map.of("kind", "service", "name", "X"), req("User", noEdit)).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.updateVisitor(1L, Map.of("firstName", "A", "lastName", "B"), req("User", noEdit)).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.deleteVisitor(1L, req("User", noDel)).getStatusCode().value()).isEqualTo(403);
        // members / accountants never reach the visitor list
        assertThat(controller.listVisitors(null, req("Accountant", null)).getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(visitors);
    }

    @Test @DisplayName("Register passes the session church and parsed date; service errors keep their status and code")
    void registerAndErrors() {
        when(visitors.register(eq(OURS), eq("u"), any())).thenReturn(Map.of("status", "success"));
        Map<String, Object> body = new java.util.HashMap<>(reg());
        body.put("date", "2026-11-01");
        assertThat(controller.registerVisitors(body, req("Admin", null)).getStatusCode().value()).isEqualTo(200);
        verify(visitors).register(eq(OURS), eq("u"), argThat(r -> r.date().equals(LocalDate.of(2026, 11, 1))
                && r.targetId() == 5L && r.people().size() == 1 && r.people().get(0).firstName().equals("A")));

        body.put("date", "11/01/2026");
        ResponseEntity<?> bad = controller.registerVisitors(body, req("Admin", null));
        assertThat(bad.getStatusCode().value()).isEqualTo(400);
        assertThat(((Map<?, ?>) bad.getBody()).get("code")).isEqualTo("BAD_DATE");

        when(visitors.createTarget(eq(OURS), any(), any(), any(), anyBoolean()))
                .thenThrow(new CheckInException(409, "EVENT_NAME_EXISTS", "exists", Map.of("suggestions", List.of(Map.of("id", 9)))));
        ResponseEntity<?> dup = controller.createTarget(Map.of("kind", "event", "name", "Gala", "eventDate", "2026-12-01"), req("Admin", null));
        assertThat(dup.getStatusCode().value()).isEqualTo(409);
        @SuppressWarnings("unchecked") Map<String, Object> db = (Map<String, Object>) dup.getBody();
        assertThat(db).containsEntry("code", "EVENT_NAME_EXISTS").containsKey("suggestions").containsEntry("error", "exists");
        assertThat(controller.visitorReports("nope", null, null, null, req("Admin", null)).getStatusCode().value()).isEqualTo(400);
    }

    @Test @DisplayName("Pure rules: name key, date window, check-out for other days, classification")
    void rules() {
        assertThat(VisitorCheckInService.normName("  Sunday   Worship ")).isEqualTo(VisitorCheckInService.normName("sundayworship"));
        LocalDate today = AppClock.today();
        LocalDateTime[] now = VisitorCheckInService.timesFor(today);
        assertThat(now[1]).isNull();
        LocalDateTime[] past = VisitorCheckInService.timesFor(today.minusDays(3));
        assertThat(past[0].toLocalDate()).isEqualTo(today.minusDays(3));
        assertThat(past[1]).isEqualTo(past[0]);
        assertThat(VisitorCheckInService.timesFor(today.plusDays(3))[1]).isNotNull();
        VisitorCheckInService.checkDateRange(today.plusYears(1));
        assertThatThrownBy(() -> VisitorCheckInService.checkDateRange(today.plusYears(3))).isInstanceOf(CheckInException.class);
        assertThatThrownBy(() -> VisitorCheckInService.checkDateRange(today.minusYears(11))).isInstanceOf(CheckInException.class);
        assertThat(VisitorCheckInService.classify(1)).isEqualTo("First Visit");
        assertThat(VisitorCheckInService.classify(2)).isEqualTo("Second Visit");
        assertThat(VisitorCheckInService.classify(5)).isEqualTo("Returning Visitor");
    }

    @Test @DisplayName("Pages: picker, date, registration, visitor list, reports, Follow-ups Visitors tab")
    void pages() throws Exception {
        String att = Files.readString(Path.of("src/main/resources/static/attendance.html"));
        assertThat(att).contains("id=\"tpPanel\"", "data-f=\"services\"", "data-f=\"today\"", "data-f=\"future\"", "data-f=\"past\"",
                "id=\"ciDate\"", "/api/attendance/targets", "/api/attendance/visitors/register",
                "data-k=\"FAMILY\"", "data-k=\"GROUP\"", "id=\"vrFollow\"", "data-tab=\"visitors\"",
                "/api/attendance/reports/v2/", "data-r=\"v2parties\"", "America/Chicago")
                .contains("data-perm=\"general.attendance.delete\" onclick=\"askDeleteVisitor(")
                // in-page confirmation, never a browser dialog, for visitor delete
                .doesNotContain("confirm('Delete this visitor");
        // the check-in method tabs no longer react to the report tabs
        assertThat(att).contains("#tab-checkin .method-tabs .mtab[data-m]");
        String fu = Files.readString(Path.of("src/main/resources/static/followups.html"));
        assertThat(fu).contains("id=\"fuTab_visitors\"", "id=\"fuPane_visitors\"", "/api/followups/visitors", "t === 'visitors'");
    }
}
