package com.churchgeniuspro.worship;

import com.churchgeniuspro.controller.WorshipPlanningController;
import com.churchgeniuspro.hibernate.WorshipGroupMember;
import com.churchgeniuspro.hibernate.WorshipInstrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Pins the Automatic Assignment contract the manual-assignment work must not
 * change: {@code POST /auto-assign} is a preview that writes nothing, keeps its
 * response shape and rotation semantics; the Automatic tab's save/list calls
 * (type {@code auto}, no query parameters) behave as before.
 */
@DisplayName("Worship — Automatic Assignment contract unchanged")
class AutoAssignmentUnchangedTest {

    private static final String OURS = "CHURCH-A";

    InMemoryWorshipRepos db;
    WorshipPlanningController controller;

    @BeforeEach
    void setUp() {
        db = new InMemoryWorshipRepos();
        db.group(1L, OURS, "Praise Team");
        WorshipInstrument drums = db.instrument(10L, 1L, "Drums");
        when(db.instrumentRepo.findByGroupIdAndDeleteFlagFalse(1L)).thenReturn(List.of(drums));
        // rotation orders 1, 2, 3 (+ one member without an order, manual-only)
        when(db.groupMemberRepo.findByInstrumentIdAndDeleteFlagFalseOrderByRotationOrderAsc(10L))
                .thenReturn(List.of(gm(1L, "One", 1), gm(2L, "Two", 2), gm(3L, "Three", 3), gm(4L, "NoOrder", null)));
        controller = new WorshipPlanningController(db.groupRepo, db.instrumentRepo, db.groupMemberRepo,
                db.assignmentRepo, db.assignmentMemberRepo, db.songRepo);
    }

    private static WorshipGroupMember gm(long id, String name, Integer order) {
        WorshipGroupMember m = new WorshipGroupMember();
        m.setId(id); m.setInstrumentId(10L); m.setMemberName(name); m.setRotationOrder(order);
        return m;
    }

    private static MockHttpServletRequest staff() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("appClientId", OURS);
        s.setAttribute("username", "user@" + OURS);
        s.setAttribute("role", "Admin");
        req.setSession(s);
        return req;
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    @DisplayName("/auto-assign is a preview: round-robin from startOrder, type 'auto', and NOTHING is written")
    @SuppressWarnings("unchecked")
    void autoAssignPreviewUnchanged() {
        ResponseEntity<?> res = controller.autoAssign(body("groupId", 1, "dayOfWeek", 7,
                "startDate", "2026-10-07", "weeks", 4, "startOrder", 2), staff());
        List<Map<String, Object>> rows = (List<Map<String, Object>>) res.getBody();

        assertThat(rows).extracting(r -> r.get("assignmentDate"))
                .containsExactly("2026-10-11", "2026-10-18", "2026-10-25", "2026-11-01");
        assertThat(rows).allMatch(r -> "auto".equals(r.get("assignmentType")));
        assertThat(rows).allMatch(r -> Long.valueOf(1L).equals(r.get("groupId")) && "Praise Team".equals(r.get("groupName")));
        List<String> drummers = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            List<Map<String, Object>> slots = (List<Map<String, Object>>) r.get("slots");
            assertThat(slots).hasSize(1);
            assertThat(slots.get(0).get("instrumentName")).isEqualTo("Drums");
            List<Map<String, Object>> members = (List<Map<String, Object>>) slots.get(0).get("members");
            assertThat(members).hasSize(1);
            drummers.add((String) members.get(0).get("memberName"));
        }
        assertThat(drummers).containsExactly("Two", "Three", "One", "Two");   // start at Order 2, wraps

        verifyNoInteractions(db.assignmentRepo, db.assignmentMemberRepo, db.songRepo);
        assertThat(db.assignments).isEmpty();
    }

    @Test
    @DisplayName("the Automatic tab's own calls: POST type 'auto' upserts one row per date; GET with no params lists it")
    void autoTabCallsUnchanged() {
        Map<String, Object> m = body("instrumentId", 10, "memberName", "Two", "sortOrder", 0);
        ResponseEntity<?> first = controller.saveAssignment(body("groupId", 1, "assignmentDate", "2026-10-11",
                "assignmentType", "auto", "members", List.of(m)), staff());
        ResponseEntity<?> again = controller.saveAssignment(body("groupId", 1, "assignmentDate", "2026-10-11",
                "assignmentType", "auto", "members", List.of(m)), staff());
        assertThat(((Map<?, ?>) again.getBody()).get("id")).isEqualTo(((Map<?, ?>) first.getBody()).get("id"));
        assertThat(db.active()).hasSize(1);
        assertThat(db.active().get(0).isSkipped()).isFalse();

        // GET /api/worship/assignments with no parameters (loadSavedAutoAssignments) — same call as before
        ResponseEntity<?> listed = controller.getAssignments(null, null, null, false, staff());
        assertThat(listed.getStatusCode().is2xxSuccessful()).isTrue();
        verify(db.assignmentRepo).findByClientIdAndAssignmentDateBetweenAndDeleteFlagFalse(eq(OURS), any(), any());
        verify(db.assignmentRepo, never()).findByClientIdAndAssignmentTypeAndAssignmentDateBetweenAndDeleteFlagFalse(any(), any(), any(), any());

        // DELETE still soft-deletes
        long id = (Long) ((Map<?, ?>) first.getBody()).get("id");
        assertThat(controller.deleteAssignment(id, staff()).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(db.assignments.get(id).isDeleteFlag()).isTrue();
        assertThat(db.active()).isEmpty();
    }

    @Test
    @DisplayName("after a soft delete, re-saving the same auto date (regenerate) creates a fresh row — the old one stays deleted")
    void regenerateAfterSoftDelete() {
        Map<String, Object> m = body("instrumentId", 10, "memberName", "Two", "sortOrder", 0);
        long oldId = (Long) ((Map<?, ?>) controller.saveAssignment(body("groupId", 1, "assignmentDate", "2026-10-11",
                "assignmentType", "auto", "members", List.of(m)), staff()).getBody()).get("id");
        controller.deleteAssignment(oldId, staff());
        long newId = (Long) ((Map<?, ?>) controller.saveAssignment(body("groupId", 1, "assignmentDate", "2026-10-11",
                "assignmentType", "auto", "members", List.of(m)), staff()).getBody()).get("id");
        assertThat(newId).isNotEqualTo(oldId);
        assertThat(db.assignments.get(oldId).isDeleteFlag()).isTrue();
        assertThat(db.active()).hasSize(1);
    }
}
