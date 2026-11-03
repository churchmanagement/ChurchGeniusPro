package com.churchgeniuspro.family;

import com.churchgeniuspro.controller.FollowUpController;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.FollowUp;
import com.churchgeniuspro.hibernate.Group;
import com.churchgeniuspro.hibernate.GroupMember;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.FamilyService;
import com.churchgeniuspro.service.GroupMemberService;
import com.churchgeniuspro.service.GroupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * View Family involvement (2026-11-02): new follow-ups and group memberships record WHICH
 * member they belong to when the person is picked from the member list — only for a live
 * member of the same church — and every older caller keeps working unchanged.
 */
@DisplayName("Member ids on new follow-ups and group memberships")
class MemberLinkRecordingTest {

    static final String CHURCH = "CHR-link";
    FollowUpRepository followUps;
    FamilyMemberRepository members;
    FollowUpController controller;

    @BeforeEach
    void setUp() {
        followUps = mock(FollowUpRepository.class);
        members = mock(FamilyMemberRepository.class);
        when(followUps.save(any(FollowUp.class))).thenAnswer(i -> i.getArgument(0));
        when(members.findByIdAndTenant(any(), any())).thenReturn(Optional.empty());
        when(members.findByIdAndTenant(5, CHURCH)).thenReturn(Optional.of(new FamilyMember()));   // Ann Lee, this church
        controller = new FollowUpController(followUps, mock(FamilyService.class), mock(FamilyRepository.class),
                members, mock(VolunteerProfileRepository.class));
    }

    private MockHttpServletRequest admin() {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.getSession(true).setAttribute("username", "admin@x");
        r.getSession().setAttribute("role", "Admin");
        r.getSession().setAttribute("appClientId", CHURCH);
        return r;
    }

    private Integer created(Map<String, Object> body) {
        ResponseEntity<?> res = controller.create(body, admin());
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) res.getBody();
        return (Integer) m.get("assignedMemberId");
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", "Call");
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test @DisplayName("Picked member of this church → id stored; other church / unknown / typed name → none")
    void createRules() {
        assertThat(created(body("assignedTo", "Ann Lee", "assignedMemberId", 5))).isEqualTo(5);
        assertThat(created(body("assignedTo", "Ann Lee", "assignedMemberId", "5"))).isEqualTo(5);
        assertThat(created(body("assignedTo", "Bob Other", "assignedMemberId", 9))).isNull();      // other church
        assertThat(created(body("assignedTo", "Ann Lee", "assignedMemberId", "abc"))).isNull();
        assertThat(created(body("assignedTo", "Ann Lee", "assignedMemberId", null))).isNull();     // typed name
        assertThat(created(body("assignedTo", "Ann Lee"))).isNull();                                 // old caller
        assertThat(created(body("assignedTo", "", "assignedMemberId", 5))).isNull();               // no assignee
    }

    @Test @DisplayName("Edit keeps the id while the name is unchanged and clears it when the name changes")
    void updateRules() {
        FollowUp f = new FollowUp();
        f.setId(1L); f.setClientId(CHURCH); f.setTitle("Call"); f.setAssignedTo("Ann Lee"); f.setAssignedMemberId(5);
        when(followUps.findByIdAndClientIdAndDeleteFlagFalse(1L, CHURCH)).thenReturn(Optional.of(f));

        controller.update(1L, body("assignedTo", "Ann Lee", "status", "PENDING"), admin());   // field absent, same name
        assertThat(f.getAssignedMemberId()).isEqualTo(5);
        controller.update(1L, body("assignedTo", "Someone Typed"), admin());                  // field absent, new name
        assertThat(f.getAssignedMemberId()).isNull();
        controller.update(1L, body("assignedTo", "Ann Lee", "assignedMemberId", 5), admin()); // picked again
        assertThat(f.getAssignedMemberId()).isEqualTo(5);
        controller.update(1L, body("assignedTo", "Ann Lee", "assignedMemberId", ""), admin()); // page cleared the pick
        assertThat(f.getAssignedMemberId()).isNull();
        assertThat(f.getAssignedTo()).isEqualTo("Ann Lee");                                     // name still saved
    }

    @Test @DisplayName("Group membership: picked member of this church is linked; others saved by name/email as before")
    void groupMembership() {
        GroupMemberRepository repo = mock(GroupMemberRepository.class);
        GroupRepository groupRepo = mock(GroupRepository.class);
        Group g = new Group(); g.setId(3); g.setAppClientId(CHURCH); g.setGroupName("Choir");
        GroupService groupService = mock(GroupService.class);
        when(groupService.findOrThrow(3, CHURCH)).thenReturn(g);
        when(repo.save(any(GroupMember.class))).thenAnswer(i -> i.getArgument(0));
        GroupMemberService svc = new GroupMemberService(repo, groupService, members);

        assertThat(svc.addMember(3, "Ann", "Lee", "ann@x.test", CHURCH, 5).getFamilyMemberId()).isEqualTo(5);
        assertThat(svc.addMember(3, "Bob", "Other", "", CHURCH, 9).getFamilyMemberId()).isNull();
        assertThat(svc.addMember(3, "Typed", "Person", "", CHURCH).getFamilyMemberId()).isNull();
        verifyNoInteractions(groupRepo);
    }

    @Test @DisplayName("Pages send the picked member's id; demo seeders link by id")
    void pagesAndSeeders() throws Exception {
        String fu = Files.readString(Path.of("src/main/resources/static/followups.html"));
        assertThat(fu).contains("assignedMemberId: document.getElementById('fAssignedMemberId').value || null")
                .contains("document.getElementById('fAssignedMemberId').value = '';   // typed text is not a picked member")
                .contains("selectAssignee(' + jsStr(a.name) + ',' + (parseInt(a.id, 10) || 'null') + ')");
        String gr = Files.readString(Path.of("src/main/resources/static/group.html"));
        assertThat(gr).contains("familyMemberId: m.id != null ? String(m.id) : null");
        assertThat(Files.readString(Path.of("src/main/java/com/churchgeniuspro/service/TestDataService.java")))
                .contains("gm.setFamilyMemberId(m.getId());");
        assertThat(Files.readString(Path.of("src/main/java/com/churchgeniuspro/service/TrialDemoDataSeeder.java")))
                .contains("f.setAssignedMemberId(assignedMemberId);")
                .contains("s.getAssignedTo() != null ? s.getAssignedMemberId() : null");
    }
}
