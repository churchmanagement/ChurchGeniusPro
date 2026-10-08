package com.churchgeniuspro.groups;

import com.churchgeniuspro.controller.GroupController;
import com.churchgeniuspro.controller.GroupMemberController;
import com.churchgeniuspro.hibernate.Group;
import com.churchgeniuspro.hibernate.GroupMember;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.GroupMemberRepository;
import com.churchgeniuspro.repository.GroupRepository;
import com.churchgeniuspro.service.GroupEmailService;
import com.churchgeniuspro.service.GroupMemberService;
import com.churchgeniuspro.service.GroupService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Groups and group members were resolved by bare id in {@code GroupService} /
 * {@code GroupMemberService.findOrThrow}, and {@code POST /api/groups/{groupId}/members}
 * attached new members to whatever group the path named. Both services now take
 * the session tenant and only use the scoped finders; the member handlers also
 * carry the {@code /groups} page's role gate.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Groups API — tenant-bound")
class GroupTenantIsolationTest {

    private static final String OURS   = "CHR-ours";
    private static final String THEIRS = "CHR-theirs";

    @Mock GroupRepository        groupRepo;
    @Mock GroupMemberRepository  memberRepo;
    @Mock FamilyMemberRepository familyMemberRepo;
    @Mock GroupEmailService      emailService;

    GroupController       groups;
    GroupMemberController members;

    @BeforeEach
    void setUp() {
        GroupService groupService = new GroupService(groupRepo, memberRepo);
        GroupMemberService memberService = new GroupMemberService(memberRepo, groupService, familyMemberRepo);
        groups  = new GroupController(groupService);
        members = new GroupMemberController(memberService, emailService, memberRepo);

        when(groupRepo.save(any(Group.class))).thenAnswer(i -> i.getArgument(0));
        when(memberRepo.save(any(GroupMember.class))).thenAnswer(i -> {
            GroupMember m = i.getArgument(0);
            if (m.getId() == null) m.setId(900);
            return m;
        });
        when(groupRepo.findByIdAndAppClientIdAndDeleteFlagFalse(7, OURS)).thenReturn(Optional.of(group(7, OURS)));
        when(groupRepo.findByIdAndAppClientIdAndDeleteFlagFalse(4242, OURS)).thenReturn(Optional.empty());
        when(memberRepo.findByIdAndAppClientIdAndDeleteFlagFalse(70, OURS)).thenReturn(Optional.of(member(70, group(7, OURS), OURS)));
        when(memberRepo.findByIdAndAppClientIdAndDeleteFlagFalse(4243, OURS)).thenReturn(Optional.empty());
        // unscoped finders still know the foreign rows — the fix is never asking them
        when(groupRepo.findById(anyInt())).thenAnswer(i -> Optional.of(group(i.getArgument(0), THEIRS)));
        when(memberRepo.findById(anyInt())).thenAnswer(i -> Optional.of(member(i.getArgument(0), group(4242, THEIRS), THEIRS)));
    }

    private static Group group(int id, String clientId) {
        Group g = new Group();
        g.setId(id); g.setGroupName("Choir " + id); g.setAppClientId(clientId);
        return g;
    }

    private static GroupMember member(int id, Group g, String clientId) {
        GroupMember m = new GroupMember();
        m.setId(id); m.setGroup(g); m.setAppClientId(clientId);
        m.setFirstName("Ada"); m.setLastName("Lovelace"); m.setEmail("ada@x.org");
        return m;
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

    private static final Map<String, String> NAME = Map.of("groupName", "Renamed");
    private static final Map<String, String> PERSON = Map.of("firstName", "Grace", "lastName", "Hopper", "email", "g@x.org");

    // ── groups ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT/DELETE /api/groups/{id} refuse another church's group and write nothing")
    void groupMutatorsAreTenantScoped() {
        MockHttpServletRequest req = adminOf(OURS);
        assertThat(groups.update(4242, NAME, req).getStatusCode().value()).isEqualTo(400);
        assertThat(groups.delete(4242, req).getStatusCode().value()).isEqualTo(400);
        verify(groupRepo, never()).save(any());
        verify(memberRepo, never()).softDeleteByGroup(any());
        verify(groupRepo, never()).findById(any());
    }

    @Test
    @DisplayName("PUT/DELETE /api/groups/{id} still work on the church's own group")
    void groupMutatorsWorkForOwnTenant() {
        MockHttpServletRequest req = adminOf(OURS);
        assertThat(groups.update(7, NAME, req).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(groups.delete(7, req).getStatusCode().is2xxSuccessful()).isTrue();
        verify(memberRepo).softDeleteByGroup(any(Group.class));
    }

    @Test
    @DisplayName("group mutators carry the page's Admin gate")
    void groupMutatorsRequireAdmin() {
        MockHttpServletRequest user = adminOf(OURS);
        user.getSession().setAttribute("role", "User");
        assertThat(groups.update(7, NAME, user).getStatusCode().value()).isEqualTo(403);
        assertThat(groups.delete(7, user).getStatusCode().value()).isEqualTo(403);
        verify(groupRepo, never()).save(any());
    }

    // ── group members ───────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /api/groups/{groupId}/members cannot attach a member to another church's group")
    void addMemberValidatesParentTenant() {
        ResponseEntity<Map<String, Object>> res = members.addMember(4242, PERSON, adminOf(OURS));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        verify(memberRepo, never()).save(any());
        verify(groupRepo, never()).findById(any());
    }

    @Test
    @DisplayName("GET /api/groups/{groupId}/members does not enumerate another church's group")
    void listMembersValidatesParentTenant() {
        assertThat(members.getMembers(4242, adminOf(OURS)).getStatusCode().value()).isEqualTo(400);
        verify(memberRepo, never()).findByGroupActiveByAppUser(anyInt(), any());
    }

    @Test
    @DisplayName("PUT/DELETE /api/group-members/{id} refuse another church's member")
    void memberMutatorsAreTenantScoped() {
        MockHttpServletRequest req = adminOf(OURS);
        assertThat(members.updateMember(4243, PERSON, req).getStatusCode().value()).isEqualTo(400);
        assertThat(members.removeMember(4243, req).getStatusCode().value()).isEqualTo(400);
        verify(memberRepo, never()).save(any());
        verify(memberRepo, never()).findById(any());
    }

    @Test
    @DisplayName("member handlers keep working within the church")
    void memberHandlersWorkForOwnTenant() {
        MockHttpServletRequest req = adminOf(OURS);
        when(memberRepo.findByGroupActiveByAppUser(7, OURS)).thenReturn(List.of(member(70, group(7, OURS), OURS)));

        assertThat(members.getMembers(7, req).getBody()).hasSize(1);
        assertThat(members.addMember(7, PERSON, req).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(members.updateMember(70, PERSON, req).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(members.removeMember(70, req).getStatusCode().is2xxSuccessful()).isTrue();
        verify(groupRepo, never()).findById(any());
        verify(memberRepo, never()).findById(any());
    }

    @Test
    @DisplayName("member handlers carry the page's role gate; no session → 403")
    void memberHandlersRequireRole() {
        MockHttpServletRequest anon = new MockHttpServletRequest();
        assertThat(members.addMember(7, PERSON, anon).getStatusCode().value()).isEqualTo(403);
        assertThat(members.removeMember(70, anon).getStatusCode().value()).isEqualTo(403);
        verify(memberRepo, never()).save(any());
    }
}
