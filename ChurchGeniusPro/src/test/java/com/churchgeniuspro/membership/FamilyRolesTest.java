package com.churchgeniuspro.membership;

import com.churchgeniuspro.controller.MembershipFormController;
import com.churchgeniuspro.hibernate.MembershipFamily;
import com.churchgeniuspro.hibernate.MembershipFamilyMember;
import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import com.churchgeniuspro.repository.MembershipFamilyMemberRepository;
import com.churchgeniuspro.repository.MembershipFamilyRepository;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import com.churchgeniuspro.util.FamilyRoles;
import com.churchgeniuspro.util.PublicSendLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Item 1 — one role vocabulary: legacy values are mapped, never written again. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Family roles — canonical mapping, head detection, form lists")
class FamilyRolesTest {

    @Mock PublicScreenLinkRepository linkRepo;
    @Mock MembershipFamilyRepository mfRepo;
    @Mock MembershipFamilyMemberRepository mfmRepo;
    @Mock FamilyRepository familyRepo;
    @Mock FamilyMemberRepository familyMemberRepo;
    @Mock PublicSendLimiter sendLimiter;
    @InjectMocks MembershipFormController controller;

    @Test
    @DisplayName("every legacy and free-text value maps to one of the four canonical roles")
    void canonical() {
        assertThat(FamilyRoles.canonical("Head")).isEqualTo("Head of Household");
        assertThat(FamilyRoles.canonical("head of household")).isEqualTo("Head of Household");
        assertThat(FamilyRoles.canonical("Primary")).isEqualTo("Head of Household");
        assertThat(FamilyRoles.canonical("Wife")).isEqualTo("Spouse");
        assertThat(FamilyRoles.canonical("Husband")).isEqualTo("Spouse");
        assertThat(FamilyRoles.canonical(" spouse ")).isEqualTo("Spouse");
        assertThat(FamilyRoles.canonical("Son")).isEqualTo("Child");
        assertThat(FamilyRoles.canonical("Daughter")).isEqualTo("Child");
        assertThat(FamilyRoles.canonical("Child")).isEqualTo("Child");
        for (String adult : List.of("Adult", "Parent", "Sibling", "Other", "Father", "Mother", "Father-in-Law", "Mother-in-Law", "", "   ", "whatever"))
            assertThat(FamilyRoles.canonical(adult)).as(adult).isEqualTo("Adult");
        assertThat(FamilyRoles.canonical(null)).isEqualTo("Adult");
        assertThat(FamilyRoles.CANONICAL).containsExactly("Head of Household", "Spouse", "Adult", "Child");
    }

    @Test
    @DisplayName("isHead accepts both spellings; isSpouse accepts Wife/Husband")
    void predicates() {
        assertThat(FamilyRoles.isHead("Head")).isTrue();
        assertThat(FamilyRoles.isHead("Head of Household")).isTrue();
        assertThat(FamilyRoles.isHead("HEAD OF HOUSEHOLD ")).isTrue();
        assertThat(FamilyRoles.isHead("Spouse")).isFalse();
        assertThat(FamilyRoles.isHead(null)).isFalse();
        assertThat(FamilyRoles.isSpouse("Wife")).isTrue();
        assertThat(FamilyRoles.isSpouse("Head")).isFalse();
    }

    @Test
    @DisplayName("public submit stores canonical roles (Head → Head of Household, Parent → Adult) and includeContributions for the head")
    void submitNormalises() {
        PublicScreenLink link = new PublicScreenLink();
        link.setAppClientId("C"); link.setPageUrl("/membershipForm"); link.setToken("tok"); link.setRevoked(false);
        when(linkRepo.findByToken("tok")).thenReturn(Optional.of(link));
        when(mfRepo.save(any(MembershipFamily.class))).thenAnswer(i -> i.getArgument(0));
        when(mfmRepo.save(any(MembershipFamilyMember.class))).thenAnswer(i -> i.getArgument(0));

        Map<String, Object> body = Map.of("cid", "tok", "declarationAccepted", true, "members", List.of(
                Map.of("role", "Head", "firstName", "A", "lastName", "L"),
                Map.of("role", "Wife", "firstName", "B", "lastName", "L"),
                Map.of("role", "Parent", "firstName", "C", "lastName", "L"),
                Map.of("role", "Son", "firstName", "D", "lastName", "L")));
        var res = controller.submit(body, new MockHttpServletRequest());
        assertThat(res.getStatusCode().is2xxSuccessful()).as(String.valueOf(res.getBody())).isTrue();

        ArgumentCaptor<MembershipFamilyMember> cap = ArgumentCaptor.forClass(MembershipFamilyMember.class);
        verify(mfmRepo, times(4)).save(cap.capture());
        assertThat(cap.getAllValues()).extracting(MembershipFamilyMember::getRole)
                .containsExactly("Head of Household", "Spouse", "Adult", "Child");
        assertThat(cap.getAllValues().get(0).isIncludeContributions()).isTrue();
        assertThat(cap.getAllValues().get(1).isIncludeContributions()).isFalse();
    }

    @Test
    @DisplayName("membershipForm.html: both role lists are the Family page's four; the primary is 'Head of Household'; prefill maps legacy roles")
    void formLists() throws Exception {
        String form = Files.readString(Path.of("src/main/resources/static/membershipForm.html"));
        String family = Files.readString(Path.of("src/main/resources/static/family.html"));
        assertThat(family).contains("const ROLE_OPTIONS = ['Head of Household', 'Spouse', 'Adult', 'Child'];");
        assertThat(form).contains("const ROLE_OPTIONS = ['Head of Household', 'Spouse', 'Adult', 'Child'];")
                        .contains("const ROLES = ROLE_OPTIONS;")
                        .doesNotContain("['Head','Spouse','Child','Parent','Sibling','Other']")
                        .contains("{ role: 'Head of Household' }")
                        .contains("all.find(m => isHeadRole(m.role))")
                        .contains("role:             canonicalRole(m.role)")
                        .contains("document.getElementById('mRole').value       = canonicalRole(m.role);");
        // the inline member select offers exactly the four, in order
        int sel = form.indexOf("<select id=\"mRole\"");
        String select = form.substring(sel, form.indexOf("</select>", sel));
        assertThat(select).containsSubsequence("value=\"Head of Household\"", "value=\"Spouse\"", "value=\"Adult\"", "value=\"Child\"")
                          .doesNotContain("value=\"Parent\"", "value=\"Sibling\"", "value=\"Other\"");
    }
}
