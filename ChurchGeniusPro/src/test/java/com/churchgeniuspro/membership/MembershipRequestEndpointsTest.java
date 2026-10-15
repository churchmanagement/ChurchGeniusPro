package com.churchgeniuspro.membership;

import com.churchgeniuspro.controller.MembershipFormController;
import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.MembershipFamily;
import com.churchgeniuspro.hibernate.MembershipFamilyMember;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.MembershipFamilyMemberRepository;
import com.churchgeniuspro.repository.MembershipFamilyRepository;
import com.churchgeniuspro.util.PublicSendLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Items 5–7 — the request endpoints: guards, tenant scoping, delete permission, status, list/getOne payload. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Membership request endpoints — approve / apply / status / delete / list")
class MembershipRequestEndpointsTest {

    static final String T = "CHURCH-A";
    @Mock MembershipFamilyRepository mfRepo;
    @Mock MembershipFamilyMemberRepository mfmRepo;
    @Mock FamilyRepository familyRepo;
    @Mock FamilyMemberRepository familyMemberRepo;
    @Mock LoginRepository loginRepository;
    @Mock PublicSendLimiter sendLimiter;
    @InjectMocks MembershipFormController controller;

    static String json(Map<String, Boolean> m) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m); } catch (Exception e) { throw new RuntimeException(e); }
    }
    static MockHttpServletRequest staff(String role, Map<String, Boolean> perms) {
        MockHttpServletRequest req = new MockHttpServletRequest(); MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "admin@x.org"); s.setAttribute("role", role); s.setAttribute("appClientId", T); s.setAttribute("clientId", "USR-1");
        if (perms != null) s.setAttribute("privileges", json(perms));
        req.setSession(s); return req;
    }
    static MockHttpServletRequest member(Map<String, Boolean> perms) {
        MockHttpServletRequest req = new MockHttpServletRequest(); MockHttpSession s = new MockHttpSession();
        s.setAttribute("role", "Member"); s.setAttribute("memberId", 40); s.setAttribute("appClientId", T); s.setAttribute("memberRef", "MBR-40");
        if (perms != null) s.setAttribute("memberPrivileges", json(perms));
        req.setSession(s); return req;
    }
    MembershipFamily request(int id, Integer family, String status) {
        MembershipFamily mf = new MembershipFamily(); mf.setId(id); mf.setAppClientId(T); mf.setExistingFamilyId(family); mf.setStatus(status);
        when(mfRepo.findByIdAndAppClientIdAndDeleteFlagFalse(id, T)).thenReturn(Optional.of(mf));
        when(mfRepo.save(any(MembershipFamily.class))).thenAnswer(i -> i.getArgument(0));
        return mf;
    }
    @SuppressWarnings("unchecked") static Map<String, Object> body(ResponseEntity<?> r) { return (Map<String, Object>) r.getBody(); }

    @Test
    @DisplayName("approve: Admin → APPROVED with the username as actor; family never read; member with admin.membership allowed; member without → 403; other church → 404")
    void approve() {
        MembershipFamily mf = request(5, 7, "PENDING_REVIEW");
        ResponseEntity<?> r = controller.approve(5, staff("Admin", null));
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        assertThat(body(r)).containsEntry("status", "APPROVED").containsEntry("isExisting", true);
        assertThat(mf.getStatusChangedBy()).isEqualTo("admin@x.org");
        verify(familyRepo, never()).findById(any()); verify(familyMemberRepo, never()).save(any());

        request(6, null, "PENDING_REVIEW");
        assertThat(controller.approve(6, member(Map.of("admin.membership", true))).getStatusCode().value()).isEqualTo(200);
        request(7, null, "PENDING_REVIEW");
        assertThat(controller.approve(7, member(Map.of("admin.membership", false))).getStatusCode().value()).isEqualTo(403);
        when(mfRepo.findByIdAndAppClientIdAndDeleteFlagFalse(8, T)).thenReturn(Optional.empty());
        assertThat(controller.approve(8, staff("Admin", null)).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.approve(5, new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("apply: counts only members that will be CREATED against the people limit, then applies; refusal keeps the previous status")
    void apply() {
        Family f = new Family(); f.setId(7); f.setAppClientId(T);
        FamilyMember head = new FamilyMember(); head.setId(1); head.setFamily(f); head.setRole("Head"); head.setFirstName("A"); head.setLastName("B");
        MembershipFamily mf = request(5, 7, "APPROVED");
        MembershipFamilyMember s1 = new MembershipFamilyMember(); s1.setId(10); s1.setRole("Head"); s1.setFirstName("A"); s1.setLastName("B"); s1.setPhone("1");
        MembershipFamilyMember s2 = new MembershipFamilyMember(); s2.setId(11); s2.setRole("Child"); s2.setFirstName("C"); s2.setLastName("B");
        when(mfmRepo.findByMembershipFamily_IdAndDeleteFlagFalse(5)).thenReturn(List.of(s1, s2));
        when(familyRepo.findById(7)).thenReturn(Optional.of(f));
        when(familyRepo.save(any(Family.class))).thenAnswer(i -> i.getArgument(0));
        when(familyMemberRepo.findActiveMembersByFamilyId(7)).thenReturn(List.of(head));
        when(familyMemberRepo.save(any(FamilyMember.class))).thenAnswer(i -> i.getArgument(0));
        when(familyMemberRepo.countActiveMembers(T)).thenReturn(10L);
        com.churchgeniuspro.service.SubscriptionService subs = org.mockito.Mockito.mock(com.churchgeniuspro.service.SubscriptionService.class);
        when(subs.checkPeopleLimit(T, 10L, 1)).thenReturn("Plan limit reached");   // 1 to create (C), not 2
        controller.setSubscriptionService(subs);

        ResponseEntity<?> blocked = controller.apply(5, staff("Admin", null));
        assertThat(blocked.getStatusCode().value()).isEqualTo(403);
        assertThat(mf.getStatus()).isEqualTo("APPROVED");
        verify(familyMemberRepo, never()).save(any());

        when(subs.checkPeopleLimit(T, 10L, 1)).thenReturn(null);
        ResponseEntity<?> ok = controller.apply(5, staff("Admin", null));
        assertThat(ok.getStatusCode().value()).as(String.valueOf(ok.getBody())).isEqualTo(200);
        assertThat(body(ok)).containsEntry("status", "UPDATED").containsEntry("created", 1).containsEntry("updated", 1).containsEntry("familyId", 7);
        assertThat(mf.getStatus()).isEqualTo("UPDATED");
    }

    @Test
    @DisplayName("status: back to Pending Review is allowed from Approved; UPDATED cannot be set by hand; body status is normalised")
    void status() {
        MembershipFamily mf = request(5, null, "APPROVED");
        ResponseEntity<?> r = controller.setStatus(5, Map.of("status", "pending review"), staff("Admin", null));
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        assertThat(mf.getStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(controller.setStatus(5, Map.of("status", "UPDATED"), staff("Admin", null)).getStatusCode().value()).isEqualTo(409);
        assertThat(controller.setStatus(5, Map.of(), staff("Admin", null)).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("delete: needs Admin/Church AND admin.membership.delete; soft-deletes with actor; never touches the family; tenant-scoped")
    void delete() {
        MembershipFamily mf = request(5, 7, "APPROVED");
        assertThat(controller.deleteRequest(5, staff("Admin", Map.of("admin.membership.delete", false))).getStatusCode().value()).isEqualTo(403);
        assertThat(mf.isDeleteFlag()).isFalse();
        assertThat(controller.deleteRequest(5, staff("User", null)).getStatusCode().value()).as("not an admin").isEqualTo(403);

        ResponseEntity<?> r = controller.deleteRequest(5, staff("Admin", Map.of("admin.membership.delete", true)));
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        assertThat(mf.isDeleteFlag()).isTrue(); assertThat(mf.getDeletedBy()).isEqualTo("admin@x.org"); assertThat(mf.getDeletedAt()).isNotNull();
        verify(familyRepo, never()).findById(any()); verify(familyRepo, never()).save(any()); verify(familyMemberRepo, never()).save(any());

        // a key never saved (null privileges) allows, as for every other permission; another church's id → 404
        request(6, null, "PENDING_REVIEW");
        assertThat(controller.deleteRequest(6, staff("Admin", null)).getStatusCode().value()).isEqualTo(200);
        when(mfRepo.findByIdAndAppClientIdAndDeleteFlagFalse(9, T)).thenReturn(Optional.empty());
        assertThat(controller.deleteRequest(9, staff("Admin", null)).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("getOne: a usable photo is sent as photoData; an unsupported one (HEIC) is flagged photoUnsupported and NOT sent as a photo")
    void unsupportedPhotoFlag() throws Exception {
        MembershipFamily mf = request(5, null, "PENDING_REVIEW");
        MembershipFamilyMember heic = new MembershipFamilyMember(); heic.setId(10); heic.setRole("Head"); heic.setFirstName("A"); heic.setLastName("B");
        heic.setPhotoData("data:image/heic;base64,AAAAGGZ0eXBoZWljAAAAAG1pZjFoZWljAAAB");
        MembershipFamilyMember ok = new MembershipFamilyMember(); ok.setId(11); ok.setRole("Spouse"); ok.setFirstName("C"); ok.setLastName("B");
        ok.setPhotoData(MembershipRequestServiceTest.png(80, 0x00FF00));
        MembershipFamilyMember none = new MembershipFamilyMember(); none.setId(12); none.setRole("Child"); none.setFirstName("D"); none.setLastName("B");
        when(mfmRepo.findByMembershipFamily_IdAndDeleteFlagFalse(5)).thenReturn(List.of(heic, ok, none));
        @SuppressWarnings("unchecked") List<Map<String, Object>> members = (List<Map<String, Object>>) body(controller.getOne(5, staff("Admin", null))).get("members");
        assertThat(members.get(0)).containsEntry("hasPhoto", false).containsEntry("photoUnsupported", "HEIC").doesNotContainKey("photoData");
        assertThat(members.get(1)).containsEntry("hasPhoto", true).doesNotContainKey("photoUnsupported");
        assertThat((String) members.get(1).get("photoData")).startsWith("data:image/png");
        assertThat(members.get(2)).containsEntry("hasPhoto", false).doesNotContainKey("photoUnsupported").doesNotContainKey("photoData");
    }

    @Test
    @DisplayName("list and getOne carry status, audit fields, memberCount and the preview block")
    void payload() {
        MembershipFamily mf = request(5, 7, "APPROVED"); mf.setStatusChangedBy("admin@x.org"); mf.setStatusChangedAt(new java.util.Date());
        MembershipFamilyMember s1 = new MembershipFamilyMember(); s1.setId(10); s1.setRole("Head"); s1.setFirstName("A"); s1.setLastName("B");
        when(mfRepo.findByAppClientIdAndDeleteFlagFalseOrderByCreatedDateDesc(T)).thenReturn(List.of(mf));
        when(mfmRepo.findByMembershipFamily_IdAndDeleteFlagFalse(5)).thenReturn(List.of(s1));
        Family f = new Family(); f.setId(7); f.setAppClientId(T);
        when(familyRepo.findById(7)).thenReturn(Optional.of(f));
        when(familyMemberRepo.findActiveMembersByFamilyId(7)).thenReturn(List.of());

        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) controller.list(staff("Admin", null)).getBody();
        assertThat(rows.get(0)).containsEntry("status", "APPROVED").containsEntry("statusLabel", "Approved").containsEntry("memberCount", 1)
                               .containsEntry("statusChangedBy", "admin@x.org").containsEntry("hasExistingFamily", true);
        Map<String, Object> one = body(controller.getOne(5, staff("Admin", null)));
        @SuppressWarnings("unchecked") Map<String, Object> preview = (Map<String, Object>) one.get("preview");
        assertThat(preview).containsEntry("isExisting", true).containsEntry("hasChanges", true);   // A B is new to an empty family
    }
}
