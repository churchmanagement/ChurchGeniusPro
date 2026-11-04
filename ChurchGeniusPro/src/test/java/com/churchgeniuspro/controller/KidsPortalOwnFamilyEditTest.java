package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.model.FamilyBO;
import com.churchgeniuspro.model.FamilyMemberBO;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.service.FamilyService;
import com.churchgeniuspro.service.SubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Kids Portal 13+ rule (2026-11-04), B2: an "own family" save (Member Portal, no Family
 * edit right) cannot change a person's role or date of birth, and a child it adds is
 * saved without a date of birth. Family managers are unchanged.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Kids Portal 13+ — own-family edits keep the church's role and date of birth")
class KidsPortalOwnFamilyEditTest {

    static final String CHURCH = "CHR-ours";
    static final int FAMILY = 10, PARENT = 42, KID = 43;

    @Mock FamilyService familyService;
    @Mock FamilyMemberRepository memberRepo;
    @Mock SubscriptionService subscriptionService;
    @Mock AppUserRepository appUserRepo;
    FamilyController controller;

    @BeforeEach
    void setUp() {
        controller = new FamilyController(familyService, memberRepo, subscriptionService);
        ReflectionTestUtils.setField(controller, "appUserRepository", appUserRepo);
        Family own = new Family(); own.setId(FAMILY);
        FamilyMember parent = new FamilyMember(); parent.setId(PARENT); parent.setFamily(own); parent.setAppClientId(CHURCH);
        parent.setRole("Head of Household"); parent.setBirthdayYear(1980); parent.setBirthdayMonth(1); parent.setBirthdayDay(2);
        FamilyMember kid = new FamilyMember(); kid.setId(KID); kid.setFamily(own); kid.setAppClientId(CHURCH);
        kid.setRole("Child"); kid.setBirthdayYear(2017); kid.setBirthdayMonth(6); kid.setBirthdayDay(9);
        when(memberRepo.findByIdAndTenant(eq(PARENT), eq(CHURCH))).thenReturn(Optional.of(parent));
        when(memberRepo.findByIdAndTenant(eq(KID), eq(CHURCH))).thenReturn(Optional.of(kid));
        Family saved = new Family(); saved.setId(FAMILY); saved.setMembers(new ArrayList<>());
        when(familyService.update(anyInt(), any(), anyString())).thenReturn(saved);
    }

    static FamilyMemberBO bo(Integer id, String role, String y, String m, String d) {
        FamilyMemberBO b = new FamilyMemberBO(); b.setId(id); b.setFirstName("X"); b.setLastName("Y");
        b.setRole(role); b.setBirthdayYear(y); b.setBirthdayMonth(m); b.setBirthdayDay(d);
        return b;
    }

    static FamilyBO family() {
        FamilyBO f = new FamilyBO();
        f.setMembers(new ArrayList<>(List.of(
                bo(PARENT, "Head of Household", "1980", "1", "2"),
                bo(KID, "Adult", "2000", "1", "1"),                 // tries Child→Adult and an older date
                bo(null, "Child", "2005", "3", "3"),                // new child with a typed date
                bo(null, "Adult", "1999", "4", "4"))));             // new adult keeps what was typed
        return f;
    }

    static MockHttpServletRequest memberSession(String privileges) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("appClientId", CHURCH); s.setAttribute("username", "parent@example.com");
        s.setAttribute("role", "Member"); s.setAttribute("memberId", PARENT);
        if (privileges != null) s.setAttribute("memberPrivileges", privileges);
        req.setSession(s);
        return req;
    }

    FamilyBO saved() {
        ArgumentCaptor<FamilyBO> cap = ArgumentCaptor.forClass(FamilyBO.class);
        verify(familyService).update(eq(FAMILY), cap.capture(), eq(CHURCH));
        return cap.getValue();
    }

    @Test @DisplayName("member portal own family: existing role/date kept, new child saved undated, new adult unchanged")
    void ownFamily() {
        assertThat(controller.updateFamily(FAMILY, family(), memberSession(null)).getStatusCode().value()).isEqualTo(200);
        List<FamilyMemberBO> m = saved().getMembers();
        assertThat(m.get(1).getRole()).isEqualTo("Child");
        assertThat(m.get(1).getBirthdayYear()).isEqualTo("2017");
        assertThat(m.get(1).getBirthdayMonth()).isEqualTo("6");
        assertThat(m.get(2).getBirthdayYear()).isNull();
        assertThat(m.get(2).getBirthdayMonth()).isNull();
        assertThat(m.get(3).getBirthdayYear()).isEqualTo("1999");
    }

    @Test @DisplayName("a member the church granted Family edit, and staff family managers, can still change them")
    void managersUnchanged() {
        assertThat(controller.updateFamily(FAMILY, family(),
                memberSession("{\"admin.family\":true,\"admin.family.edit\":true}")).getStatusCode().value()).isEqualTo(200);
        assertThat(saved().getMembers().get(1).getRole()).isEqualTo("Adult");

        reset(familyService);
        Family saved = new Family(); saved.setId(FAMILY); saved.setMembers(new ArrayList<>());
        when(familyService.update(anyInt(), any(), anyString())).thenReturn(saved);
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("appClientId", CHURCH); s.setAttribute("clientId", CHURCH); s.setAttribute("username", "admin@example.com");
        s.setAttribute("role", "Admin"); s.setAttribute("appUserId", 7);
        req.setSession(s);
        when(appUserRepo.findById(7)).thenReturn(Optional.of(new AppUser()));
        assertThat(controller.updateFamily(FAMILY, family(), req).getStatusCode().value()).isEqualTo(200);
        assertThat(saved().getMembers().get(2).getBirthdayYear()).isEqualTo("2005");
    }
}
