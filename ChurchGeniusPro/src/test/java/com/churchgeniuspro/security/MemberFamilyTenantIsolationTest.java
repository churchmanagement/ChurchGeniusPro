package com.churchgeniuspro.security;

import com.churchgeniuspro.controller.EventCalendarController;
import com.churchgeniuspro.controller.MembershipFormController;
import com.churchgeniuspro.controller.PledgeController;
import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.VerificationStore;
import com.churchgeniuspro.service.WhatsAppSenderService;
import com.churchgeniuspro.util.PublicFormGuard;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Security regression for audit finding N1 — {@code GET /api/member/family} must never
 * change a session's tenant on the strength of an email/username match.
 *
 * <p>A member login is bound to its {@code family_member} row only by the unique
 * {@code MBR<uuid>} token ({@code signup.client_id == family_member.member_ref}), the
 * same rule {@code LoginController} applies. The endpoint used to fall back to matching
 * the login username against {@code family_member.email} across every tenant and then
 * rewrote the session's {@code memberId} / {@code appClientId} to whichever row it found.
 * These tests pin that the fallback is gone: the global email finder is never called, the
 * session tenant is never rewritten, no other tenant's data is returned, and nothing is
 * written to another tenant's rows — while legitimate resolution keeps working.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("N1 — /api/member/family tenant isolation")
class MemberFamilyTenantIsolationTest {

    private static final String TENANT_A = "CHR-A";
    private static final String TENANT_B = "CHR-B";
    private static final String ATTACKER_TOKEN = "MBR-attacker-token";
    private static final String VICTIM_EMAIL   = "victim@church-b.org";

    @Mock PublicScreenLinkRepository linkRepo;
    @Mock MembershipFamilyRepository mfRepo;
    @Mock MembershipFamilyMemberRepository mfmRepo;
    @Mock FamilyRepository familyRepo;
    @Mock FamilyMemberRepository familyMemberRepo;
    @Mock ChurchRegistrationRepository churchRegRepo;
    @Mock ChurchLogoRepository logoRepo;
    @Mock VerificationStore verificationStore;
    @Mock EmailService emailService;
    @Mock LoginRepository loginRepository;
    @Mock AppUserRepository appUserRepository;
    @Mock SubSourceRepository subSourceRepo;
    @Mock IncomeRepository incomeRepo;
    @Mock MeetingRepository meetingRepo;
    @Mock ChurchEventRepository churchEventRepo;
    @Mock ChurchEventDayRepository churchEventDayRepo;
    @Mock MemberMessageRepository memberMessageRepo;
    @Mock MemberPreferenceRepository memberPrefRepo;
    @Mock WhatsAppSenderService whatsAppSenderService;
    @Mock GroupRepository groupRepo;
    @Mock GroupMemberRepository groupMemberRepo;
    @Mock WorshipGroupRepository worshipGroupRepo;
    @Mock WorshipGroupMemberRepository worshipGroupMemberRepo;
    @Mock WorshipInstrumentRepository worshipInstrumentRepo;
    @Mock WorshipAssignmentRepository worshipAssignmentRepo;
    @Mock WorshipAssignmentMemberRepository worshipAssignmentMemberRepo;
    @Mock WorshipSongRepository worshipSongRepo;
    @Mock EventCalendarController eventCalendarController;
    @Mock PledgeCampaignRepository pledgeCampaignRepo;
    @Mock PledgeMemberRepository pledgeMemberRepo;
    @Mock PledgeController pledgeController;
    @Mock PublicFormGuard formGuard;

    @InjectMocks MembershipFormController controller;

    /* ── fixtures ──────────────────────────────────────────────────────────── */

    private static FamilyMember member(int id, String email, String tenant, String memberRef) {
        Family f = new Family();
        f.setId(id * 10);
        f.setAppClientId(tenant);
        FamilyMember m = new FamilyMember();
        m.setId(id);
        m.setFirstName("First" + id);
        m.setLastName("Last" + id);
        m.setEmail(email);
        m.setAppClientId(tenant);
        m.setMemberRef(memberRef);
        m.setFamily(f);
        return m;
    }

    /** A member-portal session exactly as {@code LoginController} builds it (no {@code username}). */
    private static MockHttpSession memberSession(String mbrToken, String tenant, Integer memberId) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("clientId", mbrToken);
        s.setAttribute("role", "Member");
        if (tenant != null)   s.setAttribute("appClientId", tenant);
        if (memberId != null) s.setAttribute("memberId", memberId);
        return s;
    }

    private ResponseEntity<?> call(MockHttpSession session) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/member/family");
        req.setSession(session);
        return controller.getMemberFamily(req);
    }

    /** The attack precondition: the caller's own token resolves to nothing, but a member
     *  in ANOTHER tenant carries an email equal to the caller's login username. */
    private void attackerUnlinkedWithVictimInTenantB() {
        when(familyMemberRepo.findByMemberRef(ATTACKER_TOKEN)).thenReturn(Optional.empty());
        SignUp attackerLogin = new SignUp();
        attackerLogin.setClientId(ATTACKER_TOKEN);
        attackerLogin.setUsername(VICTIM_EMAIL);            // username chosen to collide
        when(loginRepository.findByClientId(ATTACKER_TOKEN)).thenReturn(Optional.of(attackerLogin));
        FamilyMember victim = member(99, VICTIM_EMAIL, TENANT_B, null);
        // If the old cross-tenant fallback were still present, this is what it would find.
        when(familyMemberRepo.findActiveByEmail(anyString())).thenReturn(List.of(victim));
    }

    private static void assertNoTenantSwitchAndNoBData(ResponseEntity<?> res, MockHttpSession session) {
        assertThat(res.getStatusCode().value()).as("must not succeed as another tenant").isNotEqualTo(200);
        assertThat(session.getAttribute("appClientId")).as("session tenant must be unchanged").isEqualTo(TENANT_A);
        assertThat(session.getAttribute("memberId")).as("no member identity may be adopted").isNull();
        assertThat(String.valueOf(res.getBody())).doesNotContain(TENANT_B).doesNotContain("First99");
    }

    /* ── Test 1: cross-tenant email collision ───────────────────────────── */

    @Test
    @DisplayName("1. a username colliding with another tenant's member email cannot switch the session to that tenant")
    void crossTenantEmailCollision() {
        attackerUnlinkedWithVictimInTenantB();
        MockHttpSession session = memberSession(ATTACKER_TOKEN, TENANT_A, null);

        ResponseEntity<?> res = call(session);

        assertNoTenantSwitchAndNoBData(res, session);
        verify(familyMemberRepo, never()).findActiveByEmail(anyString());   // no cross-tenant discovery
        verify(familyMemberRepo, never()).save(any());                        // nothing written to tenant B
    }

    /* ── Test 2: unlinked member ─────────────────────────────────────────── */

    @Test
    @DisplayName("2. an unlinked member fails safely (401) and triggers no search of other tenants")
    void unlinkedMemberFailsSafely() {
        when(familyMemberRepo.findByMemberRef(ATTACKER_TOKEN)).thenReturn(Optional.empty());
        MockHttpSession session = memberSession(ATTACKER_TOKEN, TENANT_A, null);

        ResponseEntity<?> res = call(session);

        assertThat(res.getStatusCode().value()).isEqualTo(401);
        assertThat(session.getAttribute("appClientId")).isEqualTo(TENANT_A);
        verify(familyMemberRepo).findByMemberRef(ATTACKER_TOKEN);            // the one trusted lookup
        verify(familyMemberRepo, never()).findActiveByEmail(anyString());
        verify(familyMemberRepo, never()).findActiveByEmailAndTenant(anyString(), anyString());
        verify(familyMemberRepo, never()).save(any());
    }

    /* ── Test 3: legitimate member ───────────────────────────────────────── */

    @Test
    @DisplayName("3a. a properly linked member (memberId in session) still gets their own tenant's family")
    void legitimateMemberById() {
        FamilyMember me = member(7, "me@church-a.org", TENANT_A, "MBR-me");
        when(familyMemberRepo.findById(7)).thenReturn(Optional.of(me));
        when(familyMemberRepo.findActiveMembersByFamilyId(70)).thenReturn(List.of(me));
        ChurchRegistration reg = new ChurchRegistration();
        reg.setClientId(TENANT_A);
        reg.setChurchName("Church A");
        when(churchRegRepo.findByClientIdAndDeleteFlagFalse(TENANT_A)).thenReturn(Optional.of(reg));

        ResponseEntity<?> res = call(memberSession("MBR-me", TENANT_A, 7));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) res.getBody();
        assertThat(body).containsEntry("selfId", 7).containsEntry("familyId", 70).containsEntry("churchName", "Church A");
        verify(familyMemberRepo, never()).findActiveByEmail(anyString());
    }

    @Test
    @DisplayName("3b. a linked member whose session lacks memberId is resolved by the trusted MBR token only")
    void legitimateMemberByTrustedToken() {
        FamilyMember me = member(8, "me@church-a.org", TENANT_A, "MBR-me");
        when(familyMemberRepo.findByMemberRef("MBR-me")).thenReturn(Optional.of(me));
        when(familyMemberRepo.findActiveMembersByFamilyId(80)).thenReturn(List.of(me));
        when(churchRegRepo.findByClientIdAndDeleteFlagFalse(TENANT_A)).thenReturn(Optional.empty());
        MockHttpSession session = memberSession("MBR-me", null, null);

        ResponseEntity<?> res = call(session);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(session.getAttribute("memberId")).isEqualTo(8);
        assertThat(session.getAttribute("appClientId")).as("tenant comes from the token-bound row").isEqualTo(TENANT_A);
        verify(familyMemberRepo, never()).findActiveByEmail(anyString());
    }

    /* ── Test 4: removed member (F5 scenario) ────────────────────────────── */

    @Test
    @DisplayName("4. a removed member (own row soft-deleted, login still active) cannot obtain another tenant's identity")
    void removedMemberCannotAdoptAnotherTenant() {
        // findByMemberRef excludes delete_flag=true rows, so a removed member resolves to nothing —
        // exactly the state that used to trigger the cross-tenant fallback.
        attackerUnlinkedWithVictimInTenantB();
        MockHttpSession session = memberSession(ATTACKER_TOKEN, TENANT_A, null);

        ResponseEntity<?> res = call(session);

        assertNoTenantSwitchAndNoBData(res, session);
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        verify(familyMemberRepo, never()).findActiveByEmail(anyString());
        verify(familyMemberRepo, never()).save(any());
    }

    /* ── Test 5: session integrity ───────────────────────────────────────── */

    @Test
    @DisplayName("5. the endpoint never rewrites appClientId on an email/username match — even when a match exists")
    void sessionTenantIsNeverRewrittenByEmailMatch() {
        attackerUnlinkedWithVictimInTenantB();
        MockHttpSession session = memberSession(ATTACKER_TOKEN, TENANT_A, null);
        Object before = session.getAttribute("appClientId");

        call(session);
        call(session);   // repeated calls must be idempotent too

        assertThat(session.getAttribute("appClientId")).isEqualTo(before).isEqualTo(TENANT_A);
        assertThat(session.getAttribute("memberRef")).isNull();
        verify(familyMemberRepo, never()).findActiveByEmail(anyString());
        verify(familyMemberRepo, never()).save(any());
    }

    /* ── staff path preserved ────────────────────────────────────────────── */

    @Test
    @DisplayName("staff: a linked staff user still resolves their member record — scoped to their own org, never a global scan")
    void staffResolutionIsTenantScoped() {
        AppUser staff = new AppUser();
        staff.setId(5);
        staff.setEmail("Staff@Church-A.org");
        staff.setClientId(TENANT_A);
        when(appUserRepository.findById(5)).thenReturn(Optional.of(staff));
        FamilyMember me = member(9, "staff@church-a.org", TENANT_A, null);
        when(familyMemberRepo.findActiveByEmailAndTenant("staff@church-a.org", TENANT_A)).thenReturn(List.of(me));
        when(familyMemberRepo.findActiveMembersByFamilyId(90)).thenReturn(List.of(me));
        when(churchRegRepo.findByClientIdAndDeleteFlagFalse(TENANT_A)).thenReturn(Optional.empty());

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "staffuser");
        session.setAttribute("appUserId", 5);
        session.setAttribute("appClientId", TENANT_A);

        ResponseEntity<?> res = call(session);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(session.getAttribute("memberId")).isEqualTo(9);
        assertThat(session.getAttribute("appClientId")).isEqualTo(TENANT_A);
        verify(familyMemberRepo).findActiveByEmailAndTenant(eq("staff@church-a.org"), eq(TENANT_A));
        verify(familyMemberRepo, never()).findActiveByEmail(anyString());
    }

    @Test
    @DisplayName("staff: no linked member → empty payload (existing behaviour), still no global scan")
    void staffWithoutMemberGetsEmptyPayload() {
        AppUser staff = new AppUser();
        staff.setId(6);
        staff.setEmail("nobody@church-a.org");
        staff.setClientId(TENANT_A);
        when(appUserRepository.findById(6)).thenReturn(Optional.of(staff));
        when(familyMemberRepo.findActiveByEmailAndTenant(anyString(), anyString())).thenReturn(List.of());

        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "staffuser");
        session.setAttribute("appUserId", 6);

        ResponseEntity<?> res = call(session);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) res.getBody();
        assertThat(body).containsEntry("member", null).containsEntry("family", null);
        verify(familyMemberRepo, never()).findActiveByEmail(anyString());
    }
}
