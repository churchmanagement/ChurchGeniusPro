package com.churchgeniuspro.enhancements;

import com.churchgeniuspro.controller.FamilyController;
import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import com.churchgeniuspro.service.FamilyService;
import com.churchgeniuspro.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 2026-11-16 — View Family: confirmation before delete, Restore for deleted families (item 3). */
@DisplayName("Families — restore a deleted family with its members")
class FamilyRestoreTest {

    static final String CID = "CHR-1";
    FamilyRepository families;
    FamilyMemberRepository members;
    FamilyService service;
    FamilyController controller;
    Family fam;

    static FamilyMember member(int id, String role, boolean deleted, Family f) {
        FamilyMember m = new FamilyMember();
        m.setId(id); m.setRole(role); m.setFirstName("P" + id); m.setLastName("Gomez"); m.setDeleteFlag(deleted); m.setFamily(f);
        f.getMembers().add(m);
        return m;
    }
    static MockHttpServletRequest as(String role, String privileges) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("appClientId", CID); s.setAttribute("clientId", CID); s.setAttribute("username", "u"); s.setAttribute("role", role);
        if (privileges != null) s.setAttribute("privileges", privileges);
        r.setSession(s);
        return r;
    }

    @BeforeEach
    void setUp() {
        families = mock(FamilyRepository.class);
        members = mock(FamilyMemberRepository.class);
        service = new FamilyService(families, members);
        controller = new FamilyController(service, members, mock(SubscriptionService.class));
        fam = new Family(); fam.setId(5); fam.setAppClientId(CID); fam.setDeleteFlag(true);
        when(families.findByIdAndAppClientId(5, CID)).thenReturn(Optional.of(fam));
        when(families.findByIdAndAppClientId(5, "CHR-2")).thenReturn(Optional.empty());
        when(families.save(any(Family.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test @DisplayName("the family comes back with its members; a member deleted on their own stays deleted")
    void restoresFamilyAndItsMembers() {
        FamilyMember head = member(1, "Head", false, fam), spouse = member(2, "Spouse", false, fam),
                child = member(3, "Child", false, fam), gone = member(4, "Child", true, fam);

        Map<String, Object> out = service.restoreWithSummary(5, CID);

        assertThat(fam.isDeleteFlag()).isFalse();
        assertThat(out).containsEntry("membersRestored", 3).containsEntry("stillDeleted", 1)
                .containsEntry("primaryRestored", false).containsEntry("alreadyActive", false);
        assertThat(gone.isDeleteFlag()).as("deleted on their own before the family — left deleted").isTrue();
        assertThat(head.isDeleteFlag() || spouse.isDeleteFlag() || child.isDeleteFlag()).isFalse();
        assertThat(fam.getMembers()).as("family links untouched").allSatisfy(m -> assertThat(m.getFamily()).isSameAs(fam));
        verify(members, never()).save(any());                 // no member row was rewritten
        verify(members, never()).delete(any());
    }

    @Test @DisplayName("a family deleted by deleting its primary member gets that primary back, and nobody else")
    void restoresTheDeletedPrimary() {
        FamilyMember head = member(1, "Head of Household", true, fam);
        FamilyMember spouse = member(2, "Spouse", false, fam), gone = member(3, "Child", true, fam);

        Map<String, Object> out = service.restoreWithSummary(5, CID);

        assertThat(head.isDeleteFlag()).isFalse();
        assertThat(gone.isDeleteFlag()).isTrue();
        assertThat(spouse.isDeleteFlag()).isFalse();
        assertThat(out).containsEntry("primaryRestored", true).containsEntry("membersRestored", 2).containsEntry("stillDeleted", 1);
        verify(members, times(1)).save(head);
    }

    @Test @DisplayName("restoring an active family changes nothing and a deleted member of it stays deleted")
    void activeFamilyUnchanged() {
        fam.setDeleteFlag(false);
        FamilyMember head = member(1, "Head", true, fam);
        Map<String, Object> out = service.restoreWithSummary(5, CID);
        assertThat(out).containsEntry("alreadyActive", true).containsEntry("membersRestored", 0).containsEntry("primaryRestored", false);
        assertThat(head.isDeleteFlag()).isTrue();
        verify(members, never()).save(any());
    }

    @Test @DisplayName("API: another church's family is not found; roles and the Delete permission decide who may restore")
    void permissions() {
        member(1, "Head", false, fam);
        ResponseEntity<Map<String, Object>> ok = controller.restoreFamily(5, as("Admin", null));
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody()).containsEntry("success", true).containsEntry("membersRestored", 1);

        fam.setDeleteFlag(true);
        MockHttpServletRequest other = as("Admin", null);
        other.getSession().setAttribute("appClientId", "CHR-2"); other.getSession().setAttribute("clientId", "CHR-2");
        assertThat(controller.restoreFamily(5, other).getStatusCode().value()).isEqualTo(400);
        assertThat(fam.isDeleteFlag()).isTrue();

        assertThat(controller.restoreFamily(5, as("User", null)).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.restoreFamily(5, new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.restoreFamily(5, as("Admin", "{\"admin.family.delete\":false}")).getStatusCode().value())
                .as("Delete unticked → no Restore").isEqualTo(403);
        assertThat(controller.restoreFamily(5, as("Admin", "{\"admin.family\":false}")).getStatusCode().value()).isEqualTo(403);
        assertThat(fam.isDeleteFlag()).isTrue();
    }

    @Test @DisplayName("page: in-page confirmation instead of the browser box, Restore on deleted cards only, text-only messages")
    void page() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/viewFamily.html"));
        assertThat(html).contains("id=\"cfOverlay\"").contains("role=\"alertdialog\"")
                .contains("Are you sure you want to delete this family?")
                .contains("will be marked as deleted and hidden")
                .contains("Nothing is erased")
                .contains("f.deleted && canRestore").contains("askRestoreFamily(")
                .contains("window.CGP_PERMS['admin.family.delete'] === false")
                .contains("A member who was deleted on their own before the family was deleted stays deleted.");
        assertThat(html).doesNotContain("if (!confirm(`Are you sure you want to delete");
        // names reach the dialog as text, never as markup
        String dlg = html.substring(html.indexOf("function confirmDialog"), html.indexOf("async function restoreFamily"));
        assertThat(dlg).doesNotContain("innerHTML");
    }
}
