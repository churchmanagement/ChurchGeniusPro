package com.churchgeniuspro.volunteers;

import com.churchgeniuspro.controller.VolunteerController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.SmsService;
import com.churchgeniuspro.service.WebPushService;
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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Volunteer roles / assignments / attendance: upsert-by-body-id handlers used to
 * load by bare id and stamp the session tenant on the row; attendance was keyed
 * on a bare assignmentId; familyMemberId came straight from the body. All of
 * those now go through tenant-scoped finders and 404 for another church's ids.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VolunteerTenantIsolationTest {

    private static final String OURS   = "CHURCH-A";
    private static final String THEIRS = "CHURCH-B";

    @Mock VolunteerRoleRepository        roleRepo;
    @Mock VolunteerProfileRepository     profileRepo;
    @Mock VolunteerProfileRoleRepository profileRoleRepo;
    @Mock VolunteerAssignmentRepository  assignmentRepo;
    @Mock VolunteerAttendanceRepository  attendanceRepo;
    @Mock FamilyMemberRepository         memberRepo;
    @Mock FamilyRepository               familyRepo;
    @Mock ChurchEventRepository          eventRepo;
    @Mock EmailService                   emailService;
    @Mock SmsService                     smsService;
    @Mock WebPushService                 pushService;

    VolunteerController controller;

    @BeforeEach
    void setUp() {
        controller = new VolunteerController(roleRepo, profileRepo, profileRoleRepo, assignmentRepo,
                attendanceRepo, memberRepo, familyRepo, eventRepo, emailService, smsService, pushService);
        when(roleRepo.findByIdAndAppClientId(anyLong(), anyString())).thenReturn(Optional.empty());
        when(assignmentRepo.findByIdAndAppClientId(anyLong(), anyString())).thenReturn(Optional.empty());
        when(attendanceRepo.findByAppClientIdAndAssignmentId(anyString(), anyLong())).thenReturn(Optional.empty());
        when(memberRepo.findByIdAndTenant(any(), anyString())).thenReturn(Optional.empty());
    }

    private MockHttpServletRequest adminOf(String clientId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("appClientId", clientId);
        s.setAttribute("username", "admin@" + clientId);
        s.setAttribute("role", "Admin");
        req.setSession(s);
        return req;
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private FamilyMember ourMember(int id) {
        FamilyMember m = new FamilyMember(); m.setId(id); m.setAppClientId(OURS); m.setFirstName("V" + id);
        return m;
    }

    // ── roles ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /roles with another church's id is 404 and the row keeps its owner")
    void saveRoleIsTenantScoped() {
        VolunteerRole theirs = new VolunteerRole(); theirs.setId(4L); theirs.setAppClientId(THEIRS);
        when(roleRepo.findById(4L)).thenReturn(Optional.of(theirs));

        ResponseEntity<?> res = controller.saveRole(body("id", 4, "roleName", "Usher"), adminOf(OURS));

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        verify(roleRepo, never()).save(any(VolunteerRole.class));
        assertThat(theirs.getAppClientId()).isEqualTo(THEIRS);
    }

    @Test
    @DisplayName("POST /roles updates a role this church owns")
    void saveRoleAllowsOwnTenant() {
        VolunteerRole ours = new VolunteerRole(); ours.setId(8L); ours.setAppClientId(OURS);
        when(roleRepo.findByIdAndAppClientId(8L, OURS)).thenReturn(Optional.of(ours));

        ResponseEntity<?> res = controller.saveRole(body("id", 8, "roleName", "Greeter"), adminOf(OURS));

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        verify(roleRepo).save(ours);
        assertThat(ours.getRoleName()).isEqualTo("Greeter");
    }

    // ── assignments ────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /assignments with another church's id is 404")
    void saveAssignmentIsTenantScoped() {
        VolunteerAssignment theirs = new VolunteerAssignment(); theirs.setId(12L); theirs.setAppClientId(THEIRS);
        when(assignmentRepo.findById(12L)).thenReturn(Optional.of(theirs));

        ResponseEntity<?> res = controller.saveAssignment(body("id", 12, "notes", "x"), adminOf(OURS));

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        verify(assignmentRepo, never()).save(any(VolunteerAssignment.class));
    }

    @Test
    @DisplayName("POST /assignments rejects familyMemberId / roleId / eventId from another church")
    void saveAssignmentValidatesForeignKeys() {
        // member 77 exists only in THEIRS: scoped finder is empty
        ResponseEntity<?> res = controller.saveAssignment(body("familyMemberId", 77), adminOf(OURS));
        assertThat(res.getStatusCode().value()).isEqualTo(404);

        when(memberRepo.findByIdAndTenant(5, OURS)).thenReturn(Optional.of(ourMember(5)));
        res = controller.saveAssignment(body("familyMemberId", 5, "roleId", 900), adminOf(OURS));
        assertThat(res.getStatusCode().value()).isEqualTo(404);

        when(eventRepo.findByIdAndAppClientIdAndDeleteFlagFalse(31, OURS)).thenReturn(Optional.empty());
        res = controller.saveAssignment(body("familyMemberId", 5, "eventId", 31), adminOf(OURS));
        assertThat(res.getStatusCode().value()).isEqualTo(404);

        verify(assignmentRepo, never()).save(any(VolunteerAssignment.class));
    }

    @Test
    @DisplayName("POST /assignments creates an assignment for this church's member")
    void saveAssignmentAllowsOwnTenant() {
        when(memberRepo.findByIdAndTenant(5, OURS)).thenReturn(Optional.of(ourMember(5)));
        VolunteerRole role = new VolunteerRole(); role.setId(2L); role.setAppClientId(OURS);
        when(roleRepo.findByIdAndAppClientId(2L, OURS)).thenReturn(Optional.of(role));
        when(assignmentRepo.save(any(VolunteerAssignment.class))).thenAnswer(i -> {
            VolunteerAssignment a = i.getArgument(0); a.setId(50L); return a; });

        ResponseEntity<?> res = controller.saveAssignment(
                body("familyMemberId", 5, "roleId", 2, "eventLabel", "Sunday"), adminOf(OURS));

        assertThat(res.getStatusCode().is2xxSuccessful()).as("%s", res.getBody()).isTrue();
        verify(assignmentRepo).save(any(VolunteerAssignment.class));
    }

    // ── profiles from member ───────────────────────────────────────────────

    @Test
    @DisplayName("POST /profiles/from-member cannot enrol another church's member")
    void addMemberAsVolunteerIsTenantScoped() {
        ResponseEntity<?> res = controller.addMemberAsVolunteer(body("familyMemberId", 77), adminOf(OURS));

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        verify(memberRepo, never()).findById(any());
        verify(profileRepo, never()).save(any(VolunteerProfile.class));
    }

    // ── attendance ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /attendance on another church's assignment is 404 and writes nothing")
    void markAttendanceIsTenantScoped() {
        VolunteerAssignment theirs = new VolunteerAssignment(); theirs.setId(12L); theirs.setAppClientId(THEIRS);
        when(assignmentRepo.findById(12L)).thenReturn(Optional.of(theirs));

        ResponseEntity<?> res = controller.markAttendance(body("assignmentId", 12), adminOf(OURS));

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        verify(attendanceRepo, never()).findByAssignmentId(anyLong());
        verify(attendanceRepo, never()).save(any(VolunteerAttendance.class));
        verify(assignmentRepo, never()).save(any(VolunteerAssignment.class));
        assertThat(theirs.getAssignmentStatus()).isEqualTo("pending");
    }

    @Test
    @DisplayName("POST /attendance marks this church's assignment confirmed")
    void markAttendanceAllowsOwnTenant() {
        VolunteerAssignment ours = new VolunteerAssignment(); ours.setId(13L); ours.setAppClientId(OURS);
        when(assignmentRepo.findByIdAndAppClientId(13L, OURS)).thenReturn(Optional.of(ours));

        ResponseEntity<?> res = controller.markAttendance(body("assignmentId", 13), adminOf(OURS));

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        verify(attendanceRepo).save(any(VolunteerAttendance.class));
        assertThat(ours.getAssignmentStatus()).isEqualTo("confirmed");
    }
}
