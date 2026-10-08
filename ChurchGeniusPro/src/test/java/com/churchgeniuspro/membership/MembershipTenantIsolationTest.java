package com.churchgeniuspro.membership;

import com.churchgeniuspro.controller.MembershipFormController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.VerificationStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import com.churchgeniuspro.util.PublicSendLimiter;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The public membership form accepts {@code existingFamilyId} straight from an
 * anonymous request body. It used to be stored unchecked, and {@code approve()}
 * then loaded that family by id without comparing tenants — so a submission
 * naming another church's family id would, on approve, merge the submitted
 * people into that other church's records.
 *
 * <p>Since audit finding P1 the id is honoured only together with the one-time
 * {@code updateToken} that {@code verify-otp} issues for that family under the
 * link's tenant (see {@code MembershipFormUpdateProofTest} for the proof rules).
 * These tests pin what is left of the tenant story: a proof can only be minted for
 * this church's family, an id without one is refused rather than stored, a
 * soft-deleted family is dropped even with a proof, and approve refuses a
 * cross-tenant merge even if a stale row somehow carries one.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MembershipTenantIsolationTest {

    private static final String OURS   = "CHURCH-A";
    private static final String THEIRS = "CHURCH-B";

    @Mock PublicScreenLinkRepository        linkRepo;
    @Mock MembershipFamilyRepository        mfRepo;
    @Mock MembershipFamilyMemberRepository  mfmRepo;
    @Mock FamilyRepository                  familyRepo;
    @Mock FamilyMemberRepository            familyMemberRepo;
    @Mock VerificationStore                 verificationStore;
    @Mock PublicSendLimiter                 sendLimiter;

    @InjectMocks MembershipFormController controller;

    // ── fixtures ────────────────────────────────────────────────────────────

    private PublicScreenLink ourLink() {
        PublicScreenLink l = new PublicScreenLink();
        l.setAppClientId(OURS);
        l.setPageUrl("/membershipForm");
        l.setToken("tok-ours");
        l.setRevoked(false);
        return l;
    }

    private Family family(int id, String clientId, boolean deleted) {
        Family f = new Family();
        f.setId(id);
        f.setAppClientId(clientId);
        f.setDeleteFlag(deleted);
        return f;
    }

    /** The proof verify-otp issues for {@code familyId}, or null when it refuses. */
    private String proofFor(int familyId) {
        when(linkRepo.findByToken("tok-ours")).thenReturn(Optional.of(ourLink()));
        when(verificationStore.validate(String.valueOf(familyId), "member-lookup", "123456")).thenReturn(true);
        when(familyMemberRepo.findActiveMembersByFamilyId(anyInt())).thenReturn(List.of());
        ResponseEntity<Map<String, Object>> res = controller.verifyOtp(
                Map.of("cid", "tok-ours", "familyId", familyId, "code", "123456"));
        return res.getStatusCode().is2xxSuccessful() ? (String) res.getBody().get("updateToken") : null;
    }

    /** Runs submit() with the given existingFamilyId (+ optional proof) and returns the response. */
    private ResponseEntity<Map<String, Object>> submit(Object existingFamilyId, String updateToken) {
        when(linkRepo.findByToken("tok-ours")).thenReturn(Optional.of(ourLink()));
        when(mfRepo.save(any(MembershipFamily.class))).thenAnswer(i -> i.getArgument(0));
        when(mfmRepo.save(any(MembershipFamilyMember.class))).thenAnswer(i -> i.getArgument(0));

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("cid", "tok-ours");
        body.put("declarationAccepted", Boolean.TRUE);
        if (existingFamilyId != null) body.put("existingFamilyId", existingFamilyId);
        if (updateToken != null) body.put("updateToken", updateToken);
        body.put("members", List.of(Map.of("role", "Head", "firstName", "Ada", "lastName", "Lovelace")));
        return controller.submit(body, new MockHttpServletRequest());
    }

    /** Runs submit() expecting success and returns the persisted row. */
    private MembershipFamily submitWith(Object existingFamilyId, String updateToken) {
        ResponseEntity<Map<String, Object>> res = submit(existingFamilyId, updateToken);
        assertThat(res.getStatusCode().is2xxSuccessful())
                .as("submit should still succeed for the applicant: %s", res.getBody())
                .isTrue();
        ArgumentCaptor<MembershipFamily> cap = ArgumentCaptor.forClass(MembershipFamily.class);
        verify(mfRepo, atLeastOnce()).save(cap.capture());
        return cap.getAllValues().get(0);
    }

    // ── submit() ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("No proof can be minted for another church's family, and its id is refused, not stored")
    void foreignFamilyIdIsRefused() {
        when(familyRepo.findById(4242)).thenReturn(Optional.of(family(4242, THEIRS, false)));
        assertThat(proofFor(4242)).as("verify-otp must refuse the other church's family").isNull();

        ResponseEntity<Map<String, Object>> res = submit(4242, null);

        assertThat(res.getStatusCode().value()).isEqualTo(403);
        verify(mfRepo, never()).save(any(MembershipFamily.class));
    }

    @Test
    @DisplayName("A family id belonging to this church is kept when the proof for it is presented")
    void ownFamilyIdIsKept() {
        when(familyRepo.findById(77)).thenReturn(Optional.of(family(77, OURS, false)));
        String proof = proofFor(77);
        assertThat(proof).isNotNull();
        assertThat(submitWith(77, proof).getExistingFamilyId()).isEqualTo(77);
    }

    @Test
    @DisplayName("This church's family id without the proof is refused")
    void ownFamilyIdWithoutProofIsRefused() {
        when(familyRepo.findById(77)).thenReturn(Optional.of(family(77, OURS, false)));
        ResponseEntity<Map<String, Object>> res = submit(77, null);
        assertThat(res.getStatusCode().value()).isEqualTo(403);
        verify(mfRepo, never()).save(any(MembershipFamily.class));
    }

    @Test
    @DisplayName("An unknown family id is refused")
    void unknownFamilyIdIsRefused() {
        when(familyRepo.findById(anyInt())).thenReturn(Optional.empty());
        assertThat(proofFor(999999)).isNull();
        ResponseEntity<Map<String, Object>> res = submit(999999, null);
        assertThat(res.getStatusCode().value()).isEqualTo(403);
        verify(mfRepo, never()).save(any(MembershipFamily.class));
    }

    @Test
    @DisplayName("A soft-deleted family is ignored even with a proof — the applicant is recorded as a new family")
    void deletedFamilyIdIsDropped() {
        when(familyRepo.findById(12)).thenReturn(Optional.of(family(12, OURS, true)));
        String proof = proofFor(12);
        assertThat(proof).isNotNull();
        MembershipFamily saved = submitWith(12, proof);
        assertThat(saved.getExistingFamilyId()).isNull();
        assertThat(saved.getAppClientId()).isEqualTo(OURS);
        verify(mfmRepo, atLeastOnce()).save(any(MembershipFamilyMember.class));
    }

    @Test
    @DisplayName("A brand-new family needs no proof and is still accepted")
    void applicantIsStillAccepted() {
        MembershipFamily saved = submitWith(null, null);
        assertThat(saved.getExistingFamilyId()).isNull();
        assertThat(saved.getAppClientId()).isEqualTo(OURS);
        verify(mfmRepo, atLeastOnce()).save(any(MembershipFamilyMember.class));
    }

    // ── approve() ───────────────────────────────────────────────────────────

    private HttpServletRequest adminRequest() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(req.getSession(false)).thenReturn(session);
        when(session.getAttribute("username")).thenReturn("admin@example.org");
        when(session.getAttribute("role")).thenReturn("Admin");
        when(session.getAttribute("appClientId")).thenReturn(OURS);
        return req;
    }

    private MembershipFamily request(int id, String clientId, Integer existingFamilyId) {
        MembershipFamily mf = new MembershipFamily();
        mf.setId(id);
        mf.setAppClientId(clientId);
        mf.setExistingFamilyId(existingFamilyId);
        return mf;
    }

    @Test
    @DisplayName("Approve refuses to merge into a family owned by another church")
    void approveRefusesCrossTenantMerge() {
        when(mfRepo.findByIdAndAppClientIdAndDeleteFlagFalse(5, OURS)).thenReturn(Optional.of(request(5, OURS, 4242)));
        when(mfmRepo.findByMembershipFamily_IdAndDeleteFlagFalse(5)).thenReturn(List.of());
        when(familyRepo.findById(4242)).thenReturn(Optional.of(family(4242, THEIRS, false)));

        ResponseEntity<?> res = controller.approve(5, adminRequest());

        assertThat(res.getStatusCode().is4xxClientError()).isTrue();
        assertThat(String.valueOf(res.getBody())).contains("does not belong to your church");
        // and nothing was written to the other church's records
        verify(familyRepo, never()).save(any(Family.class));
        verify(familyMemberRepo, never()).save(any(FamilyMember.class));
    }

    @Test
    @DisplayName("Approve still merges into a family this church owns")
    void approveAllowsOwnTenantMerge() {
        when(mfRepo.findByIdAndAppClientIdAndDeleteFlagFalse(6, OURS)).thenReturn(Optional.of(request(6, OURS, 77)));
        when(mfmRepo.findByMembershipFamily_IdAndDeleteFlagFalse(6)).thenReturn(List.of());
        when(familyRepo.findById(77)).thenReturn(Optional.of(family(77, OURS, false)));
        when(familyMemberRepo.findActiveMembersByFamilyId(77)).thenReturn(List.of());
        when(familyRepo.save(any(Family.class))).thenAnswer(i -> i.getArgument(0));
        when(mfRepo.save(any(MembershipFamily.class))).thenAnswer(i -> i.getArgument(0));

        ResponseEntity<?> res = controller.approve(6, adminRequest());

        assertThat(res.getStatusCode().is2xxSuccessful())
                .as("a legitimate merge must keep working: %s", res.getBody())
                .isTrue();
        verify(familyRepo, atLeastOnce()).save(any(Family.class));
    }

    @Test
    @DisplayName("Approve cannot reach another church's application by id")
    void approveIsTenantScoped() {
        // The application exists (id 9, owned by THEIRS) but the scoped finder — the
        // only one the handler may use — returns empty for OUR tenant.
        when(mfRepo.findByIdAndAppClientIdAndDeleteFlagFalse(9, OURS)).thenReturn(Optional.empty());

        ResponseEntity<?> res = controller.approve(9, adminRequest());

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        verify(mfRepo, never()).findByIdAndDeleteFlagFalse(any());
        verify(familyRepo, never()).save(any(Family.class));
    }

    @Test
    @DisplayName("Viewing an application is tenant-scoped the same way")
    void getOneIsTenantScoped() {
        when(mfRepo.findByIdAndAppClientIdAndDeleteFlagFalse(9, OURS)).thenReturn(Optional.empty());

        ResponseEntity<?> res = controller.getOne(9, adminRequest());

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        verify(mfRepo, never()).findByIdAndDeleteFlagFalse(any());
    }
}
