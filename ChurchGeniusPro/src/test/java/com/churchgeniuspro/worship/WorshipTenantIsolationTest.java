package com.churchgeniuspro.worship;

import com.churchgeniuspro.controller.WorshipPlanningController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * WorshipInstrument and WorshipGroupMember carry no clientId of their own, so the
 * instrument / member handlers used to accept any id. The tenant is now resolved
 * instrument → group → clientId; assignment and song writes validate groupId and
 * instrumentIds the same way; and every write handler carries the
 * {@code /worshipPlanning} page guard.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorshipTenantIsolationTest {

    private static final String OURS   = "CHURCH-A";
    private static final String THEIRS = "CHURCH-B";

    @Mock WorshipGroupRepository            groupRepo;
    @Mock WorshipInstrumentRepository       instrumentRepo;
    @Mock WorshipGroupMemberRepository      groupMemberRepo;
    @Mock WorshipAssignmentRepository       assignmentRepo;
    @Mock WorshipAssignmentMemberRepository assignmentMemberRepo;
    @Mock WorshipSongRepository             songRepo;

    WorshipPlanningController controller;

    // group 1 is OURS, group 2 is THEIRS; instrument 10 → group 1, instrument 20 → group 2
    @BeforeEach
    void setUp() {
        controller = new WorshipPlanningController(groupRepo, instrumentRepo, groupMemberRepo,
                assignmentRepo, assignmentMemberRepo, songRepo);
        WorshipGroup ours = new WorshipGroup(); ours.setId(1L); ours.setClientId(OURS);
        WorshipGroup theirs = new WorshipGroup(); theirs.setId(2L); theirs.setClientId(THEIRS);
        when(groupRepo.findByIdAndClientId(anyLong(), anyString())).thenReturn(Optional.empty());
        when(groupRepo.findByIdAndClientId(1L, OURS)).thenReturn(Optional.of(ours));
        when(groupRepo.findByIdAndClientId(2L, THEIRS)).thenReturn(Optional.of(theirs));
        when(groupRepo.findById(1L)).thenReturn(Optional.of(ours));
        when(groupRepo.findById(2L)).thenReturn(Optional.of(theirs));
        when(instrumentRepo.findById(10L)).thenReturn(Optional.of(instrument(10L, 1L)));
        when(instrumentRepo.findById(20L)).thenReturn(Optional.of(instrument(20L, 2L)));
    }

    private static WorshipInstrument instrument(long id, long groupId) {
        WorshipInstrument i = new WorshipInstrument(); i.setId(id); i.setGroupId(groupId); i.setInstrumentName("Keys");
        return i;
    }

    private MockHttpServletRequest staff(String clientId, String role) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("appClientId", clientId);
        s.setAttribute("username", "user@" + clientId);
        s.setAttribute("role", role);
        req.setSession(s);
        return req;
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ── instruments ────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT/DELETE /instruments resolve the tenant through the group")
    void instrumentWritesAreTenantScoped() {
        ResponseEntity<?> res = controller.updateInstrument(20L, body("instrumentName", "Hijack"), staff(OURS, "Admin"));
        assertThat(res.getStatusCode().value()).isEqualTo(404);

        res = controller.deleteInstrument(20L, staff(OURS, "Admin"));
        assertThat(res.getStatusCode().value()).isEqualTo(404);

        verify(instrumentRepo, never()).save(any(WorshipInstrument.class));

        // same-tenant instrument still updates
        res = controller.updateInstrument(10L, body("instrumentName", "Piano"), staff(OURS, "User"));
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        verify(instrumentRepo).save(any(WorshipInstrument.class));
    }

    // ── group members ──────────────────────────────────────────────────────

    @Test
    @DisplayName("POST/PUT/DELETE /members refuse instruments and members of another church")
    void memberWritesAreTenantScoped() {
        ResponseEntity<?> res = controller.createMember(body("instrumentId", 20, "memberName", "Mallory"), staff(OURS, "Admin"));
        assertThat(res.getStatusCode().value()).isEqualTo(404);

        WorshipGroupMember theirs = new WorshipGroupMember(); theirs.setId(200L); theirs.setInstrumentId(20L);
        when(groupMemberRepo.findById(200L)).thenReturn(Optional.of(theirs));
        res = controller.updateMember(200L, body("memberName", "X"), staff(OURS, "Admin"));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        res = controller.deleteMember(200L, staff(OURS, "Admin"));
        assertThat(res.getStatusCode().value()).isEqualTo(404);

        verify(groupMemberRepo, never()).save(any(WorshipGroupMember.class));
        assertThat(theirs.isDeleteFlag()).isFalse();

        res = controller.createMember(body("instrumentId", 10, "memberName", "Alice"), staff(OURS, "Admin"));
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        verify(groupMemberRepo).save(any(WorshipGroupMember.class));
    }

    // ── assignments (M17) ──────────────────────────────────────────────────

    @Test
    @DisplayName("POST /assignments validates groupId and every instrumentId before writing")
    void saveAssignmentValidatesGroupAndInstruments() {
        ResponseEntity<?> res = controller.saveAssignment(
                body("groupId", 2, "assignmentDate", "2026-09-13"), staff(OURS, "Admin"));
        assertThat(res.getStatusCode().value()).isEqualTo(404);

        res = controller.saveAssignment(body("groupId", 1, "assignmentDate", "2026-09-13",
                "members", List.of(body("instrumentId", 20, "memberName", "Mallory"))), staff(OURS, "Admin"));
        assertThat(res.getStatusCode().value()).isEqualTo(404);

        verify(assignmentRepo, never()).save(any(WorshipAssignment.class));
        verify(assignmentMemberRepo, never()).deleteByAssignmentId(anyLong());
        verify(assignmentMemberRepo, never()).save(any(WorshipAssignmentMember.class));

        when(assignmentRepo.findFirstByClientIdAndGroupIdAndAssignmentDateAndDeleteFlagFalse(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(assignmentRepo.save(any(WorshipAssignment.class))).thenAnswer(i -> {
            WorshipAssignment a = i.getArgument(0); a.setId(77L); return a; });
        res = controller.saveAssignment(body("groupId", 1, "assignmentDate", "2026-09-13",
                "members", List.of(body("instrumentId", 10, "memberName", "Alice"))), staff(OURS, "Admin"));
        assertThat(res.getStatusCode().is2xxSuccessful()).as("%s", res.getBody()).isTrue();
        verify(assignmentMemberRepo).save(any(WorshipAssignmentMember.class));
    }

    // ── songs (L7) ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Song writes validate groupId")
    void songWritesValidateGroup() {
        ResponseEntity<?> res = controller.createSong(body("groupId", 2, "songTitle", "Hymn"), staff(OURS, "Admin"));
        assertThat(res.getStatusCode().value()).isEqualTo(404);

        res = controller.bulkSaveSongs(body("groupId", 2, "serviceDate", "2026-09-13", "songs", List.of()), staff(OURS, "Admin"));
        assertThat(res.getStatusCode().value()).isEqualTo(404);

        verify(songRepo, never()).save(any(WorshipSong.class));
        verify(songRepo, never()).deleteByClientIdAndGroupIdAndServiceDate(any(), any(), any());

        res = controller.createSong(body("groupId", 1, "songTitle", "Hymn"), staff(OURS, "Admin"));
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        verify(songRepo).save(any(WorshipSong.class));
    }

    // ── guard (L7) ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("Write handlers carry the /worshipPlanning page guard")
    void writeHandlersMirrorPageGuard() {
        // Accountant is denied on the page, so it is denied here too.
        ResponseEntity<?> res = controller.createGroup(body("groupName", "Choir"), staff(OURS, "Accountant"));
        assertThat(res.getStatusCode().value()).isEqualTo(403);

        // explicit permission off → denied
        MockHttpServletRequest noPerm = staff(OURS, "Admin");
        noPerm.getSession().setAttribute("privileges", "{\"general.worshipplanning\":false}");
        res = controller.createGroup(body("groupName", "Choir"), noPerm);
        assertThat(res.getStatusCode().value()).isEqualTo(403);

        // no session at all → 401
        res = controller.createGroup(body("groupName", "Choir"), new MockHttpServletRequest());
        assertThat(res.getStatusCode().value()).isEqualTo(401);

        verify(groupRepo, never()).save(any(WorshipGroup.class));

        res = controller.createGroup(body("groupName", "Choir"), staff(OURS, "User"));
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        verify(groupRepo).save(any(WorshipGroup.class));
    }
}
