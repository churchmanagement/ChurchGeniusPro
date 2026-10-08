package com.churchgeniuspro.security;

import com.churchgeniuspro.controller.EventCalendarController;
import com.churchgeniuspro.controller.MembershipFormController;
import com.churchgeniuspro.controller.PledgeController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.VerificationStore;
import com.churchgeniuspro.service.WhatsAppSenderService;
import com.churchgeniuspro.util.PublicFormGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import com.churchgeniuspro.util.PublicSendLimiter;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Security regression for audit finding P1 — the public membership form.
 *
 * <p>{@code POST /api/membership-form/submit} used to honour any {@code existingFamilyId}
 * that belonged to the link's church, with nothing tying it to the code that
 * {@code verify-otp} had emailed to that family; and it could create an inactive login
 * and stamp it onto an existing member before any approval. Now an update is accepted
 * only with the one-time {@code updateToken} that {@code verify-otp} issued for that
 * family under that link, and the form never creates logins or touches
 * {@code family_member}.
 *
 * <p>Real {@link VerificationStore} and {@link PublicFormGuard} over mocked repositories,
 * same harness as {@code MembershipFormHardeningTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("P1 — membership-form updates need the verify-otp proof; the form never creates logins")
class MembershipFormUpdateProofTest {

    private static final String OURS   = "CHURCH-A";
    private static final String THEIRS = "CHURCH-B";
    private static final String OUR_LINK   = "tok-form-A";
    private static final String THEIR_LINK = "tok-form-B";
    private static final int    OUR_FAMILY = 77;

    @Mock PublicScreenLinkRepository        linkRepo;
    @Mock MembershipFamilyRepository        mfRepo;
    @Mock MembershipFamilyMemberRepository  mfmRepo;
    @Mock FamilyRepository                  familyRepo;
    @Mock FamilyMemberRepository            familyMemberRepo;
    @Mock ChurchRegistrationRepository      churchRegRepo;
    @Mock ChurchLogoRepository              logoRepo;
    @Mock VerificationCodeRepository        codeRepo;
    @Mock EmailService                      emailService;
    @Mock LoginRepository                   loginRepository;
    @Mock AppUserRepository                 appUserRepository;
    @Mock SubSourceRepository               subSourceRepo;
    @Mock IncomeRepository                  incomeRepo;
    @Mock MeetingRepository                 meetingRepo;
    @Mock ChurchEventRepository             churchEventRepo;
    @Mock ChurchEventDayRepository          churchEventDayRepo;
    @Mock MemberMessageRepository           memberMessageRepo;
    @Mock MemberPreferenceRepository        memberPrefRepo;
    @Mock WhatsAppSenderService             whatsAppSenderService;
    @Mock GroupRepository                   groupRepo;
    @Mock GroupMemberRepository             groupMemberRepo;
    @Mock WorshipGroupRepository            worshipGroupRepo;
    @Mock WorshipGroupMemberRepository      worshipGroupMemberRepo;
    @Mock WorshipInstrumentRepository       worshipInstrumentRepo;
    @Mock WorshipAssignmentRepository       worshipAssignmentRepo;
    @Mock WorshipAssignmentMemberRepository worshipAssignmentMemberRepo;
    @Mock WorshipSongRepository             worshipSongRepo;
    @Mock EventCalendarController           eventCalendarController;
    @Mock PledgeCampaignRepository          pledgeCampaignRepo;
    @Mock PledgeMemberRepository            pledgeMemberRepo;
    @Mock PledgeController                  pledgeController;

    MembershipFormController controller;

    /** Single stored code, so delete/find on the mock behave like the table. */
    private final AtomicReference<VerificationCode> stored = new AtomicReference<>();
    /** Every staging row the controller saved. */
    private final List<MembershipFamily> stagedFamilies = new ArrayList<>();
    /** A controllable clock for the proof's time-box. */
    private final AtomicLong now = new AtomicLong(1_700_000_000_000L);

    @BeforeEach
    void setUp() {
        when(codeRepo.findByClientIdAndType(anyString(), anyString()))
                .thenAnswer(i -> Optional.ofNullable(stored.get()));
        doAnswer(i -> { stored.set(null); return null; })
                .when(codeRepo).deleteByClientIdAndType(anyString(), anyString());
        when(codeRepo.save(any(VerificationCode.class))).thenAnswer(i -> { stored.set(i.getArgument(0)); return i.getArgument(0); });

        when(mfRepo.save(any(MembershipFamily.class))).thenAnswer(i -> {
            MembershipFamily mf = i.getArgument(0);
            if (mf.getId() == null) mf.setId(500 + stagedFamilies.size());
            stagedFamilies.add(mf);
            return mf;
        });
        when(mfmRepo.save(any(MembershipFamilyMember.class))).thenAnswer(i -> i.getArgument(0));

        when(linkRepo.findByToken(OUR_LINK)).thenReturn(Optional.of(link(OUR_LINK, OURS)));
        when(linkRepo.findByToken(THEIR_LINK)).thenReturn(Optional.of(link(THEIR_LINK, THEIRS)));
        when(familyRepo.findById(OUR_FAMILY)).thenReturn(Optional.of(family(OUR_FAMILY, OURS)));
        when(familyRepo.findById(78)).thenReturn(Optional.of(family(78, OURS)));
        when(familyMemberRepo.findActiveMembersByFamilyId(anyInt())).thenReturn(List.of());
        when(appUserRepository.findAdminsByClientId(anyString())).thenReturn(List.of(admin("pastor@church-a.org")));
        when(churchRegRepo.findByClientIdAndDeleteFlagFalse(anyString())).thenReturn(Optional.empty());

        controller = new MembershipFormController(linkRepo, mfRepo, mfmRepo, familyRepo, familyMemberRepo,
                churchRegRepo, logoRepo, new VerificationStore(codeRepo), emailService, loginRepository, appUserRepository,
                subSourceRepo, incomeRepo, meetingRepo, churchEventRepo, churchEventDayRepo, memberMessageRepo,
                memberPrefRepo, whatsAppSenderService, groupRepo, groupMemberRepo, worshipGroupRepo,
                worshipGroupMemberRepo, worshipInstrumentRepo, worshipAssignmentRepo, worshipAssignmentMemberRepo,
                worshipSongRepo, eventCalendarController, pledgeCampaignRepo, pledgeMemberRepo, pledgeController,
                new PublicSendLimiter());
        controller.setProofClock(now::get);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static PublicScreenLink link(String token, String clientId) {
        PublicScreenLink l = new PublicScreenLink();
        l.setAppClientId(clientId);
        l.setPageUrl("/membershipForm");
        l.setToken(token);
        l.setRevoked(false);
        return l;
    }

    private static Family family(int id, String clientId) {
        Family f = new Family();
        f.setId(id);
        f.setAppClientId(clientId);
        f.setDeleteFlag(false);
        return f;
    }

    private static AppUser admin(String email) {
        AppUser u = new AppUser();
        u.setEmail(email);
        u.setFirstName("Pat");
        return u;
    }

    private static VerificationCode code(String clientId, String type, String code) {
        VerificationCode vc = new VerificationCode();
        vc.setClientId(clientId);
        vc.setType(type);
        vc.setCode(code);
        vc.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        return vc;
    }

    /** Runs verify-otp for OUR_FAMILY with the right code and returns the proof it issued. */
    private String verifyOurFamily() {
        stored.set(code(String.valueOf(OUR_FAMILY), "member-lookup", "123456"));
        ResponseEntity<Map<String, Object>> res = controller.verifyOtp(
                Map.of("cid", OUR_LINK, "familyId", OUR_FAMILY, "code", "123456"));
        assertThat(res.getStatusCode().is2xxSuccessful()).as("%s", res.getBody()).isTrue();
        Object token = res.getBody().get("updateToken");
        assertThat(token).as("verify-otp issues the update proof").isInstanceOf(String.class);
        assertThat(((String) token).length()).isGreaterThanOrEqualTo(40);   // 32 random bytes, base64url
        return (String) token;
    }

    private static Map<String, Object> member(String role, String first, String last, String email) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("firstName", first);
        m.put("lastName", last);
        m.put("email", email);
        return m;
    }

    /** A submission body for the given link; {@code extra} entries override/add. */
    private static Map<String, Object> submission(String cid, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cid", cid);
        body.put("members", List.of(member("Head", "Alex", "Rivera", "alex@example.org")));
        body.put("declarationAccepted", true);
        body.putAll(extra);
        return body;
    }

    private static String err(ResponseEntity<?> res) {
        Object b = res.getBody();
        return b instanceof Map<?, ?> m ? String.valueOf(m.get("error")) : String.valueOf(b);
    }

    // ── 1. existingFamilyId without the proof ──────────────────────────────

    @Test
    @DisplayName("1. an update to an existing family with no proof is refused and nothing is staged")
    void updateWithoutProofIsRefused() {
        Map<String, Object> body = submission(OUR_LINK, Map.of("existingFamilyId", OUR_FAMILY));

        ResponseEntity<Map<String, Object>> res = controller.submit(body, new MockHttpServletRequest());

        assertThat(res.getStatusCode().value()).isEqualTo(403);
        assertThat(err(res)).isEqualTo(MembershipFormController.UPDATE_PROOF_REQUIRED_MSG);
        assertThat(stagedFamilies).as("no staging row is created without proof").isEmpty();
        verify(mfmRepo, never()).save(any());
        verify(emailService, never()).sendOrgEmail(anyString(), anyString(), anyString(), anyString());
    }

    // ── 2. proof for a different family ────────────────────────────────────

    @Test
    @DisplayName("2. a proof issued for family 77 does not unlock an update of family 78")
    void proofIsBoundToOneFamily() {
        String proof = verifyOurFamily();

        ResponseEntity<Map<String, Object>> res = controller.submit(
                submission(OUR_LINK, Map.of("existingFamilyId", 78, "updateToken", proof)), new MockHttpServletRequest());

        assertThat(res.getStatusCode().value()).isEqualTo(403);
        assertThat(stagedFamilies).isEmpty();
    }

    // ── 3. proof from another church's link ────────────────────────────────

    @Test
    @DisplayName("3. a proof issued under church A's link is refused when replayed through church B's link")
    void proofIsBoundToTheLinkTenant() {
        String proof = verifyOurFamily();

        ResponseEntity<Map<String, Object>> res = controller.submit(
                submission(THEIR_LINK, Map.of("existingFamilyId", OUR_FAMILY, "updateToken", proof)), new MockHttpServletRequest());

        assertThat(res.getStatusCode().value()).isEqualTo(403);
        assertThat(stagedFamilies).isEmpty();
    }

    // ── 4. the legitimate flow ──────────────────────────────────────────────

    @Test
    @DisplayName("4. lookup → code → submit still queues an update of the verified family, and the proof is spent")
    void verifiedUpdateStillWorksAndProofIsSpent() {
        String proof = verifyOurFamily();
        Map<String, Object> body = submission(OUR_LINK, Map.of("existingFamilyId", OUR_FAMILY, "updateToken", proof));

        ResponseEntity<Map<String, Object>> first = controller.submit(body, new MockHttpServletRequest());

        assertThat(first.getStatusCode().is2xxSuccessful()).as("%s", first.getBody()).isTrue();
        assertThat(stagedFamilies).hasSize(1);
        assertThat(stagedFamilies.get(0).getExistingFamilyId()).isEqualTo(OUR_FAMILY);
        assertThat(stagedFamilies.get(0).getAppClientId()).isEqualTo(OURS);
        verify(mfmRepo, times(1)).save(any(MembershipFamilyMember.class));
        verify(emailService).sendOrgEmail(eq("pastor@church-a.org"), anyString(), anyString(), eq(OURS));

        ResponseEntity<Map<String, Object>> replay = controller.submit(body, new MockHttpServletRequest());
        assertThat(replay.getStatusCode().value()).as("a proof is good for one submission").isEqualTo(403);
        assertThat(stagedFamilies).hasSize(1);
    }

    // ── 5. time-box ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("5. an expired proof is refused")
    void expiredProofIsRefused() {
        String proof = verifyOurFamily();
        now.addAndGet(MembershipFormController.UPDATE_PROOF_TTL_MS + 1);

        ResponseEntity<Map<String, Object>> res = controller.submit(
                submission(OUR_LINK, Map.of("existingFamilyId", OUR_FAMILY, "updateToken", proof)), new MockHttpServletRequest());

        assertThat(res.getStatusCode().value()).isEqualTo(403);
        assertThat(stagedFamilies).isEmpty();
    }

    @Test
    @DisplayName("5b. a proof used just inside its window is still accepted")
    void proofInsideWindowIsAccepted() {
        String proof = verifyOurFamily();
        now.addAndGet(MembershipFormController.UPDATE_PROOF_TTL_MS - 1);

        ResponseEntity<Map<String, Object>> res = controller.submit(
                submission(OUR_LINK, Map.of("existingFamilyId", OUR_FAMILY, "updateToken", proof)), new MockHttpServletRequest());

        assertThat(res.getStatusCode().is2xxSuccessful()).as("%s", res.getBody()).isTrue();
    }

    // ── 6. no proof without the right code ─────────────────────────────────

    @Test
    @DisplayName("6. a wrong code yields neither the family nor a proof")
    void wrongCodeIssuesNoProof() {
        stored.set(code(String.valueOf(OUR_FAMILY), "member-lookup", "123456"));

        ResponseEntity<Map<String, Object>> res = controller.verifyOtp(
                Map.of("cid", OUR_LINK, "familyId", OUR_FAMILY, "code", "000000"));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody()).doesNotContainKey("updateToken").doesNotContainKey("family");
    }

    // ── 7. the login-binding path is gone ──────────────────────────────────

    @Test
    @DisplayName("7. signup* fields are ignored: no login is created and no family member is touched")
    void anonymousSubmissionNeverCreatesLoginOrTouchesMembers() {
        // Everything the removed path needed: a verified signup-email code, a free
        // username, and a member of this church with no login yet.
        stored.set(code(OUR_LINK, "signup-email", "654321"));
        when(loginRepository.existsByUsername(anyString())).thenReturn(false);
        FamilyMember victim = new FamilyMember();
        victim.setId(4242);
        victim.setAppClientId(OURS);
        when(familyMemberRepo.findById(4242)).thenReturn(Optional.of(victim));
        when(loginRepository.save(any(SignUp.class))).thenAnswer(i -> { SignUp s = i.getArgument(0); s.setId(9); return s; });

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("signupUsername", "victim.login");
        extra.put("signupPassword", "Str0ng!Passw0rd");
        extra.put("signupEmailCode", "654321");
        extra.put("signupFamilyMemberId", 4242);

        ResponseEntity<Map<String, Object>> res = controller.submit(submission(OUR_LINK, extra), new MockHttpServletRequest());

        assertThat(res.getStatusCode().is2xxSuccessful()).as("%s", res.getBody()).isTrue();
        verify(loginRepository, never()).save(any(SignUp.class));
        verify(familyMemberRepo, never()).save(any(FamilyMember.class));
        verify(familyMemberRepo, never()).findById(anyInt());
        assertThat(victim.getMemberRef()).isNull();
        assertThat(res.getBody()).doesNotContainKey("signupCreated");
        assertThat(stagedFamilies).hasSize(1);
        assertThat(stagedFamilies.get(0).getSignupId()).isNull();
    }

    // ── 8. new-family submissions are unchanged ────────────────────────────

    @Test
    @DisplayName("8. a brand-new family submission needs no proof and still reaches the admins")
    void newFamilySubmissionStillWorks() {
        Map<String, Object> body = submission(OUR_LINK, Map.of());
        body.put("members", List.of(
                member("Head", "Alex", "Rivera", "alex@example.org"),
                member("Spouse", "Sam", "Rivera", null)));

        ResponseEntity<Map<String, Object>> res = controller.submit(body, new MockHttpServletRequest());

        assertThat(res.getStatusCode().is2xxSuccessful()).as("%s", res.getBody()).isTrue();
        assertThat(stagedFamilies).hasSize(1);
        assertThat(stagedFamilies.get(0).getExistingFamilyId()).isNull();
        ArgumentCaptor<MembershipFamilyMember> saved = ArgumentCaptor.forClass(MembershipFamilyMember.class);
        verify(mfmRepo, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(MembershipFamilyMember::getAppClientId).containsOnly(OURS);
        verify(emailService).sendOrgEmail(eq("pastor@church-a.org"), anyString(), anyString(), eq(OURS));
        verify(familyMemberRepo, never()).save(any(FamilyMember.class));
    }

    // ── 9. the orphaned login endpoints no longer exist ────────────────────

    @Test
    @DisplayName("9. check-username / send-signup-otp / verify-signup-otp are no longer mapped")
    void orphanedLoginEndpointsAreGone() {
        Set<String> mapped = new HashSet<>();
        for (Method m : MembershipFormController.class.getDeclaredMethods()) {
            GetMapping g = m.getAnnotation(GetMapping.class);
            if (g != null) mapped.addAll(Arrays.asList(g.value()));
            PostMapping p = m.getAnnotation(PostMapping.class);
            if (p != null) mapped.addAll(Arrays.asList(p.value()));
        }
        assertThat(mapped)
                .doesNotContain("/api/membership-form/check-username",
                                "/api/membership-form/send-signup-otp",
                                "/api/membership-form/verify-signup-otp")
                .contains("/api/membership-form/submit", "/api/membership-form/verify-otp");
    }
}
