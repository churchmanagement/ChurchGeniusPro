package com.churchgeniuspro.membership;

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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import com.churchgeniuspro.util.PublicSendLimiter;

/**
 * Public membership-form hardening:
 * <ul>
 *   <li>only a token minted for {@code /membershipForm} unlocks the form endpoints;</li>
 *   <li>{@code verify-otp} refuses a family id from another church before touching
 *       the code or returning any member data;</li>
 *   <li>a stored OTP is discarded after {@link VerificationStore#MAX_ATTEMPTS} wrong guesses;</li>
 *   <li>{@code lookup} is rate-limited per IP;</li>
 *   <li>{@code /api/member/give} only accepts a purpose (sub-source) of the member's church.</li>
 * </ul>
 * Real {@link VerificationStore} and {@link PublicFormGuard} over mocked repositories.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MembershipFormHardeningTest {

    private static final String OURS   = "CHURCH-A";
    private static final String THEIRS = "CHURCH-B";

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

    VerificationStore verificationStore;
    MembershipFormController controller;

    /** Single stored code, so delete/find on the mock behave like the table. */
    private final AtomicReference<VerificationCode> stored = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        when(codeRepo.findByClientIdAndType(anyString(), anyString()))
                .thenAnswer(i -> Optional.ofNullable(stored.get()));
        doAnswer(i -> { stored.set(null); return null; })
                .when(codeRepo).deleteByClientIdAndType(anyString(), anyString());
        when(codeRepo.save(any(VerificationCode.class))).thenAnswer(i -> { stored.set(i.getArgument(0)); return i.getArgument(0); });

        verificationStore = new VerificationStore(codeRepo);
        controller = new MembershipFormController(linkRepo, mfRepo, mfmRepo, familyRepo, familyMemberRepo,
                churchRegRepo, logoRepo, verificationStore, emailService, loginRepository, appUserRepository,
                subSourceRepo, incomeRepo, meetingRepo, churchEventRepo, churchEventDayRepo, memberMessageRepo,
                memberPrefRepo, whatsAppSenderService, groupRepo, groupMemberRepo, worshipGroupRepo,
                worshipGroupMemberRepo, worshipInstrumentRepo, worshipAssignmentRepo, worshipAssignmentMemberRepo,
                worshipSongRepo, eventCalendarController, pledgeCampaignRepo, pledgeMemberRepo, pledgeController,
                new PublicSendLimiter());
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static PublicScreenLink link(String token, String clientId, String pageUrl) {
        PublicScreenLink l = new PublicScreenLink();
        l.setAppClientId(clientId);
        l.setPageUrl(pageUrl);
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

    private static VerificationCode code(String clientId, String type, String code) {
        VerificationCode vc = new VerificationCode();
        vc.setClientId(clientId);
        vc.setType(type);
        vc.setCode(code);
        vc.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        return vc;
    }

    private static MockHttpServletRequest memberRequest(String clientId, int memberId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "member@example.org");
        session.setAttribute("role", "Member");
        session.setAttribute("memberId", memberId);
        session.setAttribute("appClientId", clientId);
        req.setSession(session);
        return req;
    }

    private static String err(ResponseEntity<?> res) {
        return String.valueOf(((Map<?, ?>) res.getBody()).get("error"));
    }

    // ── token page rule ─────────────────────────────────────────────────────

    @Test
    @DisplayName("A Member Signup token for the same church does not unlock the membership form")
    void tokenForOtherPageIsRefused() {
        when(linkRepo.findByToken("tok-signup")).thenReturn(Optional.of(link("tok-signup", OURS, "/memberSignup")));
        when(linkRepo.findByToken("tok-form")).thenReturn(Optional.of(link("tok-form", OURS, "/membershipForm")));
        when(churchRegRepo.findByClientIdAndDeleteFlagFalse(OURS)).thenReturn(Optional.empty());
        when(logoRepo.findByClientId(OURS)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> refused = controller.getConfig("tok-signup");
        assertThat(refused.getStatusCode().value()).isEqualTo(400);
        assertThat(err(refused)).contains("Invalid or expired link");

        ResponseEntity<Map<String, Object>> ok = controller.getConfig("tok-form");
        assertThat(ok.getStatusCode().is2xxSuccessful()).isTrue();
    }

    // ── verify-otp tenant check ─────────────────────────────────────────────

    @Test
    @DisplayName("verify-otp refuses a family of another church before checking the code or listing members")
    void verifyOtpIsTenantScoped() {
        when(linkRepo.findByToken("tok-form")).thenReturn(Optional.of(link("tok-form", OURS, "/membershipForm")));
        when(familyRepo.findById(4242)).thenReturn(Optional.of(family(4242, THEIRS)));
        stored.set(code("4242", "member-lookup", "123456"));   // the other church's live code

        ResponseEntity<Map<String, Object>> res = controller.verifyOtp(
                Map.of("cid", "tok-form", "familyId", 4242, "code", "123456"));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(err(res)).isEqualTo("Family not found.");
        verify(codeRepo, never()).findByClientIdAndType(anyString(), anyString());
        verify(familyMemberRepo, never()).findActiveMembersByFamilyId(anyInt());
        assertThat(stored.get()).as("the other church's code must be left untouched").isNotNull();
    }

    @Test
    @DisplayName("verify-otp still returns this church's family for a correct code")
    void verifyOtpOwnTenantWorks() {
        when(linkRepo.findByToken("tok-form")).thenReturn(Optional.of(link("tok-form", OURS, "/membershipForm")));
        when(familyRepo.findById(77)).thenReturn(Optional.of(family(77, OURS)));
        when(familyMemberRepo.findActiveMembersByFamilyId(77)).thenReturn(List.of());
        stored.set(code("77", "member-lookup", "123456"));

        ResponseEntity<Map<String, Object>> res = controller.verifyOtp(
                Map.of("cid", "tok-form", "familyId", 77, "code", "123456"));

        assertThat(res.getStatusCode().is2xxSuccessful()).as("%s", res.getBody()).isTrue();
        assertThat(((Map<?, ?>) res.getBody().get("family")).get("id")).isEqualTo(77);
        assertThat(stored.get()).as("code is consumed on success").isNull();
    }

    // ── attempt limit ───────────────────────────────────────────────────────

    @Test
    @DisplayName("After MAX_ATTEMPTS wrong codes the stored OTP is deleted, so even the right code no longer works")
    void otpIsDiscardedAfterTooManyWrongGuesses() {
        stored.set(code("77", "member-lookup", "123456"));

        for (int i = 0; i < VerificationStore.MAX_ATTEMPTS; i++) {
            assertThat(verificationStore.validate("77", "member-lookup", "000000")).isFalse();
        }
        verify(codeRepo, atLeastOnce()).deleteByClientIdAndType("77", "member-lookup");
        assertThat(stored.get()).isNull();
        assertThat(verificationStore.validate("77", "member-lookup", "123456")).isFalse();
    }

    @Test
    @DisplayName("Fewer wrong guesses than the limit do not burn the code; a correct one resets the counter")
    void fewWrongGuessesKeepTheCode() {
        stored.set(code("77", "member-lookup", "123456"));
        for (int i = 0; i < VerificationStore.MAX_ATTEMPTS - 1; i++) {
            assertThat(verificationStore.validate("77", "member-lookup", "999999")).isFalse();
        }
        assertThat(stored.get()).isNotNull();
        assertThat(verificationStore.validate("77", "member-lookup", "123456")).isTrue();
        // counter cleared: the full budget is available again
        for (int i = 0; i < VerificationStore.MAX_ATTEMPTS - 1; i++) {
            assertThat(verificationStore.validate("77", "member-lookup", "999999")).isFalse();
        }
        assertThat(stored.get()).isNotNull();
    }

    // ── lookup rate limit ───────────────────────────────────────────────────

    @Test
    @DisplayName("lookup is throttled per IP")
    void lookupIsRateLimited() {
        when(linkRepo.findByToken("tok-form")).thenReturn(Optional.of(link("tok-form", OURS, "/membershipForm")));
        when(familyMemberRepo.findByPhoneOrEmailAndClient(anyString(), anyString())).thenReturn(List.of());
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("203.0.113.9");

        for (int i = 0; i < PublicFormGuard.MAX_PER_WINDOW; i++) {
            assertThat(controller.lookup("tok-form", "someone@example.org", req).getStatusCode().value()).isEqualTo(200);
        }
        ResponseEntity<Map<String, Object>> blocked = controller.lookup("tok-form", "someone@example.org", req);
        assertThat(blocked.getStatusCode().value()).isEqualTo(429);
        verify(familyMemberRepo, times(PublicFormGuard.MAX_PER_WINDOW)).findByPhoneOrEmailAndClient(anyString(), anyString());
    }

    // ── /api/member/give (Phase D: no longer records anything) ──────────────
    // A contribution is now a real Stripe payment through MemberContributionController;
    // purpose scoping and the member/tenant checks are tested in MemberContributionTest.

    @Test
    @DisplayName("the old no-payment give endpoint is gone: 410, nothing recorded, for own and foreign purposes alike")
    void legacyGiveRecordsNothing() {
        FamilyMember self = new FamilyMember(); self.setId(10); self.setAppClientId(OURS);
        when(familyMemberRepo.findById(10)).thenReturn(Optional.of(self));
        SubSource ss = new SubSource(); ss.setId(7); ss.setAppClientId(OURS);
        when(subSourceRepo.findByIdAndAppClientIdAndDeleteFlagFalse(7, OURS)).thenReturn(Optional.of(ss));

        for (int purpose : new int[]{7, 555}) {
            ResponseEntity<?> res = controller.memberGive(
                    Map.of("subSourceId", purpose, "amount", "25.00", "incomeDate", "2026-02-14"), memberRequest(OURS, 10));
            assertThat(res.getStatusCode().value()).isEqualTo(410);
            assertThat(err(res)).contains("online payments");
        }
        verify(incomeRepo, never()).save(any(Income.class));
        verify(pledgeController, never()).applyIncomeToPledge(any(), any(), any(), any(), any());
        assertThat(controller.memberGive(Map.of("subSourceId", 7, "amount", "1"), new org.springframework.mock.web.MockHttpServletRequest())
                .getStatusCode().value()).isEqualTo(401);
    }

    // ── review screen: every submitted field is returned for the admin to verify ──

    @Test
    @DisplayName("request detail returns the full family address/phone/email and every member field that was submitted")
    void reviewDetailReturnsEverythingSubmitted() {
        MembershipFamily mf = new MembershipFamily();
        mf.setId(5); mf.setAppClientId(OURS); mf.setDeclarationAccepted(true);
        when(mfRepo.findByIdAndAppClientIdAndDeleteFlagFalse(5, OURS)).thenReturn(Optional.of(mf));

        MembershipFamilyMember head = new MembershipFamilyMember();
        head.setId(51); head.setRole("Head"); head.setFirstName("Ben"); head.setLastName("Bennett"); head.setOtherName("Benny");
        head.setGender("Male"); head.setPhone("2015550100"); head.setEmail("ben@bennett.test");
        head.setBirthdayMonth(3); head.setBirthdayDay(14); head.setBirthdayYear(1980);
        head.setAddress1("12 Oak St"); head.setAddress2("Apt 4"); head.setCity("Dallas"); head.setState("TX"); head.setCountry("USA"); head.setPinCode("75001");
        head.setPhonePrivate(true); head.setEmailPrivate(false); head.setAddressPrivate(false);
        head.setPhotoData("data:image/png;base64,AAAA");
        MembershipFamilyMember spouse = new MembershipFamilyMember();
        spouse.setId(52); spouse.setRole("Spouse"); spouse.setFirstName("Ann"); spouse.setLastName("Bennett"); spouse.setGender("Female");
        spouse.setEmail("ann@bennett.test"); spouse.setBirthdayMonth(7); spouse.setBirthdayDay(2); spouse.setBirthdayYear(1982);
        spouse.setAnniversaryMonth(6); spouse.setAnniversaryDay(21); spouse.setAnniversaryYear(2005);
        spouse.setSameAsFamilyAddress(false); spouse.setAddress1("9 Elm Rd"); spouse.setCity("Plano"); spouse.setState("TX"); spouse.setCountry("USA"); spouse.setPinCode("75023");
        when(mfmRepo.findByMembershipFamily_IdAndDeleteFlagFalse(5)).thenReturn(List.of(head, spouse));

        MockHttpServletRequest admin = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "admin@church.test"); session.setAttribute("role", "Admin"); session.setAttribute("appClientId", OURS);
        admin.setSession(session);

        ResponseEntity<?> res = controller.getOne(5, admin);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked") Map<String, Object> d = (Map<String, Object>) res.getBody();
        assertThat(d).containsEntry("familyName", "Bennett Family")
                .containsEntry("familyAddress1", "12 Oak St").containsEntry("familyAddress2", "Apt 4").containsEntry("familyCity", "Dallas")
                .containsEntry("familyState", "TX").containsEntry("familyCountry", "USA").containsEntry("familyPinCode", "75001")
                .containsEntry("familyPhone", "2015550100").containsEntry("familyEmail", "ben@bennett.test")
                .containsEntry("declarationAccepted", true);
        @SuppressWarnings("unchecked") List<Map<String, Object>> members = (List<Map<String, Object>>) d.get("members");
        assertThat(members).hasSize(2);
        Map<String, Object> h = members.get(0), sp = members.get(1);
        assertThat(h).containsEntry("firstName", "Ben").containsEntry("lastName", "Bennett").containsEntry("otherName", "Benny")
                .containsEntry("gender", "Male").containsEntry("phone", "2015550100").containsEntry("email", "ben@bennett.test")
                .containsEntry("birthdayMonth", 3).containsEntry("birthdayDay", 14).containsEntry("birthdayYear", 1980)
                .containsEntry("address2", "Apt 4").containsEntry("sameAsFamilyAddress", true)
                .containsEntry("phonePrivate", true).containsEntry("emailPrivate", false).containsEntry("addressPrivate", false)
                .containsEntry("hasPhoto", true).containsEntry("photoData", "data:image/png;base64,AAAA");
        assertThat(sp).containsEntry("role", "Spouse").containsEntry("gender", "Female")
                .containsEntry("anniversaryMonth", 6).containsEntry("anniversaryDay", 21).containsEntry("anniversaryYear", 2005)
                .containsEntry("sameAsFamilyAddress", false).containsEntry("address1", "9 Elm Rd").containsEntry("city", "Plano").containsEntry("pinCode", "75023")
                .containsEntry("hasPhoto", false);
        assertThat(sp).doesNotContainKey("photoData");
        for (String k : List.of("role", "gender", "firstName", "middleName", "lastName", "phone", "email", "memberType",
                "birthdayMonth", "birthdayDay", "birthdayYear", "anniversaryMonth", "anniversaryDay", "anniversaryYear",
                "address1", "address2", "city", "state", "country", "pinCode", "sameAsFamilyAddress", "otherName", "comments",
                "phonePrivate", "emailPrivate", "addressPrivate", "hasPhoto")) {
            assertThat(h).as("member field " + k + " is returned").containsKey(k);
        }
    }

    @Test
    @DisplayName("another church's admin cannot read the request (404)")
    void reviewDetailTenantScoped() {
        when(mfRepo.findByIdAndAppClientIdAndDeleteFlagFalse(5, "CHURCH-B")).thenReturn(Optional.empty());
        MockHttpServletRequest admin = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "x@b.test"); session.setAttribute("role", "Admin"); session.setAttribute("appClientId", "CHURCH-B");
        admin.setSession(session);
        assertThat(controller.getOne(5, admin).getStatusCode().value()).isEqualTo(404);
    }
}
