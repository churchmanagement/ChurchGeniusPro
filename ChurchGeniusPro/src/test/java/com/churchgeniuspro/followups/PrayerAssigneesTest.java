package com.churchgeniuspro.followups;

import com.churchgeniuspro.controller.PublicEngagementController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Follow-Ups → Prayer Requests → Manage → Assign Volunteer / Staff (2026-11-10). */
class PrayerAssigneesTest {
    static final String CID = "CHR-1";
    FamilyMemberRepository memberRepo = mock(FamilyMemberRepository.class);
    PublicPrayerRequestRepository prayerRepo = mock(PublicPrayerRequestRepository.class);
    PublicPrayerNoteRepository noteRepo = mock(PublicPrayerNoteRepository.class);
    PrayerVolunteerRepository volunteerRepo = mock(PrayerVolunteerRepository.class);
    PublicEngagementService svc;
    PublicPrayerRequest req;

    static FamilyMember member(int id, String first, String last) {
        FamilyMember m = new FamilyMember(); m.setId(id); m.setFirstName(first); m.setLastName(last); return m;
    }
    static PrayerVolunteer vol(int id, Integer memberId, String first, String last, boolean active) {
        PrayerVolunteer v = new PrayerVolunteer(); v.setId(id); v.setClientId(CID); v.setFamilyMemberId(memberId);
        v.setFirstName(first); v.setLastName(last); v.setActive(active); v.setRole("Intercessor"); return v;
    }

    @BeforeEach
    void setUp() {
        svc = new PublicEngagementService(memberRepo, mock(FamilyRepository.class), mock(FollowUpRepository.class), prayerRepo,
                noteRepo, volunteerRepo, mock(ChurchRegistrationRepository.class), mock(ChurchLogoRepository.class),
                mock(ConnectSubmissionRepository.class), mock(EmailService.class), mock(WhatsAppSenderService.class), mock(PublicLinkResolver.class));
        req = new PublicPrayerRequest(); req.setId(7L); req.setClientId(CID); req.setStatus("New");
        when(prayerRepo.findByIdAndClientIdAndDeleteFlagFalse(7L, CID)).thenReturn(Optional.of(req));
        when(prayerRepo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test @DisplayName("the list = active Prayer Team first, then church members not already on the team")
    void listsTeamThenMembers() {
        when(volunteerRepo.findByClient(CID)).thenReturn(List.of(vol(3, 11, "Ann", "Lee", true), vol(4, null, "Old", "Helper", false)));
        when(memberRepo.findAllWithFamilyByAppUser(CID)).thenReturn(List.of(member(11, "Ann", "Lee"), member(12, "Bob", "Ray"), member(13, "", "")));
        List<Map<String, Object>> out = svc.assignees(CID);
        assertThat(out).extracting(m -> m.get("key")).containsExactly("v:3", "m:12");
        assertThat(out.get(0)).containsEntry("group", "Prayer Team").containsEntry("name", "Ann Lee").containsEntry("detail", "Intercessor");
        assertThat(out.get(1)).containsEntry("group", "Church Members").containsEntry("name", "Bob Ray");
    }

    @Test @DisplayName("a church with no Prayer Team still lists its members (the reported empty dropdown)")
    void noTeamStillMembers() {
        when(volunteerRepo.findByClient(CID)).thenReturn(List.of());
        when(memberRepo.findAllWithFamilyByAppUser(CID)).thenReturn(List.of(member(12, "Bob", "Ray")));
        assertThat(svc.assignees(CID)).hasSize(1);
        assertThat(svc.assignees(null)).isEmpty();   // never the unscoped query
        verify(memberRepo, never()).findAllWithFamilyByAppUser(null);
    }

    @Test @DisplayName("assigning a member saves name + member id, New → Assigned, a note, and returns the row")
    void assignMember() {
        when(memberRepo.findByIdAndTenant(12, CID)).thenReturn(Optional.of(member(12, "Bob", "Ray")));
        when(volunteerRepo.findByMember(CID, 12)).thenReturn(Optional.empty());
        Map<String, Object> row = svc.assignMember(CID, 7L, 12, "pastor");
        assertThat(req.getAssignedTo()).isEqualTo("Bob Ray");
        assertThat(req.getAssignedMemberId()).isEqualTo(12);
        assertThat(req.getAssignedVolunteerId()).isNull();
        assertThat(req.getStatus()).isEqualTo("Assigned");
        assertThat(row).containsEntry("assignedTo", "Bob Ray").containsEntry("assignedMemberId", 12).containsEntry("status", "Assigned");
        verify(noteRepo).save(argThat(n -> "Assigned to Bob Ray.".equals(n.getNoteText()) && "pastor".equals(n.getAuthor())));
    }

    @Test @DisplayName("a member on the Prayer Team is stored as that volunteer; a volunteer keeps working; unassign clears all")
    void volunteerAndUnassign() {
        when(memberRepo.findByIdAndTenant(11, CID)).thenReturn(Optional.of(member(11, "Ann", "Lee")));
        when(volunteerRepo.findByMember(CID, 11)).thenReturn(Optional.of(vol(3, 11, "Ann", "Lee", true)));
        svc.assignMember(CID, 7L, 11, "pastor");
        assertThat(req.getAssignedVolunteerId()).isEqualTo(3);
        assertThat(req.getAssignedMemberId()).isEqualTo(11);
        req.setStatus("InProgress");
        when(volunteerRepo.findByIdAndClientId(3, CID)).thenReturn(Optional.of(vol(3, 11, "Ann", "Lee", true)));
        svc.assign(CID, 7L, 3, "pastor");
        assertThat(req.getAssignedTo()).isEqualTo("Ann Lee");
        assertThat(req.getStatus()).as("only New moves to Assigned").isEqualTo("InProgress");
        Map<String, Object> row = svc.assign(CID, 7L, null, "pastor");
        assertThat(req.getAssignedTo()).isNull(); assertThat(req.getAssignedVolunteerId()).isNull(); assertThat(req.getAssignedMemberId()).isNull();
        assertThat(row.get("assignedTo")).isNull();
    }

    @Test @DisplayName("another church's member or an unknown volunteer is refused and nothing is saved")
    void otherChurchRefused() {
        when(memberRepo.findByIdAndTenant(99, CID)).thenReturn(Optional.empty());
        when(volunteerRepo.findByIdAndClientId(98, CID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.assignMember(CID, 7L, 99, "x")).hasMessageContaining("member was not found");
        assertThatThrownBy(() -> svc.assign(CID, 7L, 98, "x")).hasMessageContaining("volunteer was not found");
        verify(prayerRepo, never()).save(any()); verify(noteRepo, never()).save(any());
    }

    static MockHttpServletRequest staff(String role) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "pastor"); s.setAttribute("role", role); s.setAttribute("clientId", CID);
        s.setAttribute("appClientId", CID); s.setAttribute("church", false);
        MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
    }

    @Test @DisplayName("endpoints: Accountant refused (existing rule); assignees listed; assign by memberId / volunteerId / {} ; bad id 400")
    void endpoints() {
        PublicEngagementService s = mock(PublicEngagementService.class);
        PublicEngagementController c = new PublicEngagementController(s, null);
        // the page's existing guard: SuperAdmin / Admin / User (Member Portal logins only with
        // more.followups, enforced by MemberApiScopeFilter); church-owner and Accountant logins refused
        MockHttpServletRequest member = staff("Accountant");
        assertThat(c.assignees(member).getStatusCode().value()).isEqualTo(403);
        assertThat(c.assign(7L, new HashMap<>(Map.of("memberId", "12")), member).getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(s);

        MockHttpServletRequest sa = staff("SuperAdmin");
        when(s.assignees(any())).thenReturn(List.of(Map.of("key", "m:12")));
        assertThat(c.assignees(sa).getStatusCode().value()).isEqualTo(200);
        when(s.assignMember(any(), eq(7L), eq(12), any())).thenReturn(Map.of("assignedTo", "Bob Ray"));
        ResponseEntity<?> ok = c.assign(7L, new HashMap<>(Map.of("memberId", "12")), sa);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(((Map<?, ?>) ok.getBody()).get("request")).isEqualTo(Map.of("assignedTo", "Bob Ray"));
        when(s.assign(any(), eq(7L), eq(3), any())).thenReturn(Map.of());
        assertThat(c.assign(7L, new HashMap<>(Map.of("volunteerId", 3)), sa).getStatusCode().value()).isEqualTo(200);
        when(s.assign(any(), eq(7L), isNull(), any())).thenReturn(Map.of());
        assertThat(c.assign(7L, new HashMap<>(), sa).getStatusCode().value()).isEqualTo(200);
        verify(s).assign(any(), eq(7L), isNull(), any());
        assertThat(c.assign(7L, new HashMap<>(Map.of("memberId", "abc")), sa).getStatusCode().value()).isEqualTo(400);
        when(s.assignMember(any(), eq(7L), eq(99), any())).thenThrow(new IllegalArgumentException("That member was not found."));
        assertThat(c.assign(7L, new HashMap<>(Map.of("memberId", 99)), sa).getStatusCode().value()).isEqualTo(400);
    }

    @Test @DisplayName("page + migration wiring")
    void wiring() throws Exception {
        String page = Files.readString(Path.of("src/main/resources/static/followups.html"));
        assertThat(page).contains("/api/ppr/assignees").contains("mgAssignBox").contains("prayerAssign(").doesNotContain("PVOLS");
        assertThat(Files.readString(Path.of("scripts/sql/migrate_production.sql")))
                .contains("ALTER TABLE public_prayer_request ADD COLUMN IF NOT EXISTS assigned_member_id INTEGER;");
    }
}
