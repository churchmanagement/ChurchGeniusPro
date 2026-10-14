package com.churchgeniuspro.worship;

import com.churchgeniuspro.controller.WorshipPlanningController;
import com.churchgeniuspro.hibernate.WorshipAssignment;
import com.churchgeniuspro.hibernate.WorshipAssignmentMember;
import com.churchgeniuspro.hibernate.WorshipSong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Manual Assignment and Automatic Assignment must never overwrite or modify each
 * other, even for the same group and date (user requirement, 2026-10-08). The row
 * key is (client, group, date, WORKFLOW); skipping keeps members; the "add dates"
 * helper creates empty rows only; dated song lists are per workflow.
 */
@DisplayName("Worship — Manual / Automatic assignment isolation")
class ManualAssignmentIsolationTest {

    private static final String OURS   = "CHURCH-A";
    private static final String THEIRS = "CHURCH-B";
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 11);   // a Sunday

    InMemoryWorshipRepos db;
    WorshipPlanningController controller;

    @BeforeEach
    void setUp() {
        db = new InMemoryWorshipRepos();
        db.group(1L, OURS, "Praise Team");
        db.group(2L, THEIRS, "Their Team");
        db.instrument(10L, 1L, "Drums");
        db.instrument(11L, 1L, "Vocals");
        controller = new WorshipPlanningController(db.groupRepo, db.instrumentRepo, db.groupMemberRepo,
                db.assignmentRepo, db.assignmentMemberRepo, db.songRepo);
    }

    private static MockHttpServletRequest staff(String clientId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("appClientId", clientId);
        s.setAttribute("username", "user@" + clientId);
        s.setAttribute("role", "Admin");
        req.setSession(s);
        return req;
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, Object> member(long instrumentId, String name) {
        return body("instrumentId", instrumentId, "memberName", name, "sortOrder", 0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detail(ResponseEntity<?> res) {
        assertThat(res.getStatusCode().is2xxSuccessful()).as(String.valueOf(res.getBody())).isTrue();
        return (Map<String, Object>) res.getBody();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(ResponseEntity<?> res) {
        assertThat(res.getStatusCode().is2xxSuccessful()).as(String.valueOf(res.getBody())).isTrue();
        return (List<Map<String, Object>>) res.getBody();
    }

    private static Set<String> names(List<WorshipAssignmentMember> ms) {
        return ms.stream().map(WorshipAssignmentMember::getMemberName).collect(Collectors.toSet());
    }

    private Map<String, Object> saveManual(String date, Map<String, Object>... members) {
        return detail(controller.saveAssignment(body("groupId", 1, "assignmentDate", date,
                "assignmentType", "manual", "members", List.of(members)), staff(OURS)));
    }

    private Map<String, Object> saveAuto(String date, Map<String, Object>... members) {
        return detail(controller.saveAssignment(body("groupId", 1, "assignmentDate", date,
                "assignmentType", "auto", "members", List.of(members)), staff(OURS)));
    }

    // ── 1. the key ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("same group + date: a manual save and an auto save are TWO rows that keep their own members")
    void manualAndAutoRowsAreIndependent() {
        Map<String, Object> manual = saveManual("2026-10-11", member(10, "Manual Drummer"));
        Map<String, Object> auto   = saveAuto("2026-10-11",   member(10, "Auto Drummer"));

        assertThat(auto.get("id")).isNotEqualTo(manual.get("id"));
        assertThat(db.active()).hasSize(2);

        long manualId = (Long) manual.get("id"), autoId = (Long) auto.get("id");
        assertThat(names(db.membersOf(manualId))).containsExactly("Manual Drummer");
        assertThat(names(db.membersOf(autoId))).containsExactly("Auto Drummer");
        assertThat(db.assignments.get(manualId).getAssignmentType()).isEqualTo("manual");
        assertThat(db.assignments.get(autoId).getAssignmentType()).isEqualTo("auto");
    }

    @Test
    @DisplayName("re-saving the manual row updates ONLY the manual row; the auto row's type and members are untouched")
    void manualResaveNeverTouchesAuto() {
        long autoId   = (Long) saveAuto("2026-10-11", member(10, "Auto Drummer"), member(11, "Auto Singer")).get("id");
        long manualId = (Long) saveManual("2026-10-11", member(10, "First")).get("id");

        long manualAgain = (Long) saveManual("2026-10-11", member(10, "Second"), member(11, "Third")).get("id");

        assertThat(manualAgain).isEqualTo(manualId);                         // upsert, not a duplicate
        assertThat(db.active()).hasSize(2);
        assertThat(names(db.membersOf(manualId))).containsExactlyInAnyOrder("Second", "Third");
        assertThat(names(db.membersOf(autoId))).containsExactlyInAnyOrder("Auto Drummer", "Auto Singer");
        assertThat(db.assignments.get(autoId).getAssignmentType()).isEqualTo("auto");
    }

    @Test
    @DisplayName("re-saving the auto row (what regenerate / auto-save do) never touches the manual row")
    void autoResaveNeverTouchesManual() {
        long manualId = (Long) saveManual("2026-10-11", member(10, "Manual Drummer")).get("id");
        long autoId   = (Long) saveAuto("2026-10-11", member(10, "Auto One")).get("id");

        long autoAgain = (Long) saveAuto("2026-10-11", member(10, "Auto Two")).get("id");

        assertThat(autoAgain).isEqualTo(autoId);
        assertThat(names(db.membersOf(autoId))).containsExactly("Auto Two");
        assertThat(names(db.membersOf(manualId))).containsExactly("Manual Drummer");
        assertThat(db.assignments.get(manualId).getAssignmentType()).isEqualTo("manual");
    }

    @Test
    @DisplayName("a save without assignmentType is 'manual' (the API's historical default) and never lands on the auto row")
    void missingTypeDefaultsToManual() {
        long autoId = (Long) saveAuto("2026-10-11", member(10, "Auto Drummer")).get("id");
        Map<String, Object> noType = detail(controller.saveAssignment(body("groupId", 1,
                "assignmentDate", "2026-10-11", "members", List.of(member(10, "Typed Nothing"))), staff(OURS)));
        assertThat(noType.get("assignmentType")).isEqualTo("manual");
        assertThat((Long) noType.get("id")).isNotEqualTo(autoId);
        assertThat(names(db.membersOf(autoId))).containsExactly("Auto Drummer");
    }

    @Test
    @DisplayName("an unknown assignmentType is rejected as a key value and stored as 'manual', never 'auto'")
    void unknownTypeIsNotAuto() {
        Map<String, Object> saved = detail(controller.saveAssignment(body("groupId", 1,
                "assignmentDate", "2026-10-11", "assignmentType", "AUTOMATIC-ish",
                "members", List.of(member(10, "X"))), staff(OURS)));
        assertThat(saved.get("assignmentType")).isEqualTo("manual");
    }

    // ── 2. reading ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET ?type= returns one workflow; omitted = both (today's behaviour); bad value = 400")
    void listFiltersByWorkflow() {
        saveManual("2026-10-11", member(10, "M"));
        saveAuto("2026-10-11", member(10, "A"));
        saveAuto("2026-10-18", member(10, "A2"));

        List<Map<String, Object>> all = list(controller.getAssignments("2026-10-01", "2026-10-31", null, false, staff(OURS)));
        assertThat(all).hasSize(3);

        List<Map<String, Object>> manual = list(controller.getAssignments("2026-10-01", "2026-10-31", "manual", false, staff(OURS)));
        assertThat(manual).hasSize(1);
        assertThat(manual.get(0).get("assignmentType")).isEqualTo("manual");

        List<Map<String, Object>> auto = list(controller.getAssignments("2026-10-01", "2026-10-31", "AUTO", false, staff(OURS)));
        assertThat(auto).hasSize(2).allMatch(a -> "auto".equals(a.get("assignmentType")));

        ResponseEntity<?> bad = controller.getAssignments("2026-10-01", "2026-10-31", "weird", false, staff(OURS));
        assertThat(bad.getStatusCode().value()).isEqualTo(400);

        // other tenant sees nothing
        assertThat(list(controller.getAssignments("2026-10-01", "2026-10-31", null, false, staff(THEIRS)))).isEmpty();
    }

    // ── 3. skip keeps members ────────────────────────────────────────────────

    @Test
    @DisplayName("skip hides the date and KEEPS its members; unskip restores them; the read-only views never see a skipped date")
    void skipPreservesMembers() {
        long manualId = (Long) saveManual("2026-10-11", member(10, "Keep Me"), member(11, "Me Too")).get("id");
        clearInvocations(db.assignmentMemberRepo);                       // the set-up save above is not under test

        Map<String, Object> skipped = detail(controller.setSkipped(manualId, body("skipped", true), staff(OURS)));
        assertThat(skipped.get("skipped")).isEqualTo(true);
        assertThat(names(db.membersOf(manualId))).containsExactlyInAnyOrder("Keep Me", "Me Too");
        verify(db.assignmentMemberRepo, never()).deleteByAssignmentId(anyLong());   // members never rewritten

        // default listing (Worship Planning / member portal) hides it; the Manual tab asks for it
        assertThat(list(controller.getAssignments("2026-10-01", "2026-10-31", null, false, staff(OURS)))).isEmpty();
        List<Map<String, Object>> withSkipped = list(controller.getAssignments("2026-10-01", "2026-10-31", "manual", true, staff(OURS)));
        assertThat(withSkipped).hasSize(1);
        assertThat(withSkipped.get(0).get("skipped")).isEqualTo(true);

        Map<String, Object> restored = detail(controller.setSkipped(manualId, body("skipped", false), staff(OURS)));
        assertThat(restored.get("skipped")).isEqualTo(false);
        assertThat(names(db.membersOf(manualId))).containsExactlyInAnyOrder("Keep Me", "Me Too");
        assertThat(list(controller.getAssignments("2026-10-01", "2026-10-31", null, false, staff(OURS)))).hasSize(1);
    }

    @Test
    @DisplayName("skip is refused for an auto row (400), another tenant's row (404) and a deleted row (404)")
    void skipIsManualOnlyAndTenantScoped() {
        long autoId   = (Long) saveAuto("2026-10-11", member(10, "A")).get("id");
        long manualId = (Long) saveManual("2026-10-18", member(10, "M")).get("id");

        assertThat(controller.setSkipped(autoId, body("skipped", true), staff(OURS)).getStatusCode().value()).isEqualTo(400);
        assertThat(db.assignments.get(autoId).isSkipped()).isFalse();

        assertThat(controller.setSkipped(manualId, body("skipped", true), staff(THEIRS)).getStatusCode().value()).isEqualTo(404);
        assertThat(db.assignments.get(manualId).isSkipped()).isFalse();

        controller.deleteAssignment(manualId, staff(OURS));
        assertThat(controller.setSkipped(manualId, body("skipped", true), staff(OURS)).getStatusCode().value()).isEqualTo(404);
    }

    // ── 4. add dates = empty rows only ───────────────────────────────────────

    @Test
    @DisplayName("manual-dates creates EMPTY manual rows on the chosen weekday, skips existing ones, never reads the roster")
    @SuppressWarnings("unchecked")
    void manualDatesCreateEmptyRowsOnly() {
        long existing = (Long) saveManual("2026-10-18", member(10, "Already Here")).get("id");
        long autoId   = (Long) saveAuto("2026-10-25", member(10, "Auto Row")).get("id");
        clearInvocations(db.assignmentMemberRepo);                       // the set-up saves above are not under test

        ResponseEntity<?> res = controller.addManualDates(body("groupId", 1, "dayOfWeek", 7,
                "startDate", "2026-10-07", "weeks", 4), staff(OURS));          // Wed → first Sunday = 10-11
        Map<String, Object> out = detail(res);
        List<Map<String, Object>> created = (List<Map<String, Object>>) out.get("created");
        List<String> existingDates = (List<String>) out.get("existing");

        assertThat(created).extracting(m -> m.get("assignmentDate"))
                .containsExactly("2026-10-11", "2026-10-25", "2026-11-01");
        assertThat(existingDates).containsExactly("2026-10-18");
        assertThat(created).allMatch(m -> "manual".equals(m.get("assignmentType")));
        assertThat(created).allMatch(m -> ((List<?>) m.get("slots")).isEmpty());        // nobody assigned
        assertThat(created).allMatch(m -> Boolean.FALSE.equals(m.get("skipped")));

        // nothing automatic happened: no roster read, no member rows written, no auto row touched
        verifyNoInteractions(db.groupMemberRepo);
        verify(db.assignmentMemberRepo, never()).save(any(WorshipAssignmentMember.class));
        assertThat(names(db.membersOf(existing))).containsExactly("Already Here");
        assertThat(names(db.membersOf(autoId))).containsExactly("Auto Row");
        assertThat(db.assignments.get(autoId).getAssignmentType()).isEqualTo("auto");
        // the auto row on 10-25 did not prevent a manual row on 10-25
        assertThat(db.active().stream().filter(a -> a.getAssignmentDate().equals(LocalDate.of(2026, 10, 25)))).hasSize(2);
    }

    @Test
    @DisplayName("manual-dates validates input and tenant")
    void manualDatesValidation() {
        assertThat(controller.addManualDates(body("groupId", 1, "dayOfWeek", 7, "startDate", "2026-10-07"), staff(OURS))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.addManualDates(body("groupId", 1, "dayOfWeek", 8, "startDate", "2026-10-07", "weeks", 2), staff(OURS))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.addManualDates(body("groupId", 1, "dayOfWeek", 7, "startDate", "2026-10-07", "weeks", 53), staff(OURS))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.addManualDates(body("groupId", 2, "dayOfWeek", 7, "startDate", "2026-10-07", "weeks", 2), staff(OURS))
                .getStatusCode().value()).isEqualTo(404);
        assertThat(db.active()).isEmpty();
    }

    // ── 5. songs per workflow ────────────────────────────────────────────────

    @Test
    @DisplayName("dated song lists are per workflow: manual songs never replace auto songs for the same group+date; default stays 'auto'")
    void songListsArePerWorkflow() {
        // Auto (today's callers send no scheduleType)
        list(controller.bulkSaveSongs(body("groupId", 1, "serviceDate", "2026-10-11",
                "songs", List.of(body("songTitle", "Auto Song"))), staff(OURS)));
        // Manual
        list(controller.bulkSaveSongs(body("groupId", 1, "serviceDate", "2026-10-11", "scheduleType", "manual",
                "songs", List.of(body("songTitle", "Manual Song"), body("songTitle", "Manual Two"))), staff(OURS)));

        List<Map<String, Object>> auto = list(controller.getSongs(1L, "2026-10-11", null, staff(OURS)));
        assertThat(auto).extracting(m -> m.get("songTitle")).containsExactly("Auto Song");
        List<Map<String, Object>> manual = list(controller.getSongs(1L, "2026-10-11", "manual", staff(OURS)));
        assertThat(manual).extracting(m -> m.get("songTitle")).containsExactly("Manual Song", "Manual Two");

        // single-song create defaults to auto, honours manual
        detail(controller.createSong(body("groupId", 1, "serviceDate", "2026-10-11", "songTitle", "Auto Added"), staff(OURS)));
        detail(controller.createSong(body("groupId", 1, "serviceDate", "2026-10-11", "songTitle", "Manual Added", "scheduleType", "manual"), staff(OURS)));
        assertThat(list(controller.getSongs(1L, "2026-10-11", "auto", staff(OURS)))).hasSize(2);
        assertThat(list(controller.getSongs(1L, "2026-10-11", "manual", staff(OURS)))).hasSize(3);

        // batch (member portal / Worship Planning) defaults to auto
        Map<?, ?> batch = (Map<?, ?>) controller.getSongsBatch(List.of(1L), "2026-10-01", "2026-10-31", null, staff(OURS)).getBody();
        @SuppressWarnings("unchecked")
        List<String> titles = (List<String>) batch.get("1|2026-10-11");
        assertThat(titles).containsExactly("Auto Song", "Auto Added");

        // every stored row carries its schedule
        assertThat(db.songs.values().stream().map(WorshipSong::getScheduleType).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("auto", "manual");
    }

    @Test
    @DisplayName("the song library (no date) is shared and ignores scheduleType")
    void libraryIsShared() {
        detail(controller.createSong(body("groupId", 1, "songTitle", "Library Song"), staff(OURS)));
        assertThat(list(controller.getSongs(1L, null, "manual", staff(OURS)))).extracting(m -> m.get("songTitle")).containsExactly("Library Song");
        assertThat(list(controller.getSongs(1L, "", null, staff(OURS)))).extracting(m -> m.get("songTitle")).containsExactly("Library Song");
    }

    // ── 6. entity defaults ───────────────────────────────────────────────────

    @Test
    @DisplayName("entity defaults: a new WorshipAssignment is manual + not skipped; a new WorshipSong is on the auto schedule")
    void entityDefaults() {
        assertThat(new WorshipAssignment().getAssignmentType()).isEqualTo("manual");
        assertThat(new WorshipAssignment().isSkipped()).isFalse();
        assertThat(new WorshipSong().getScheduleType()).isEqualTo("auto");
    }
}
