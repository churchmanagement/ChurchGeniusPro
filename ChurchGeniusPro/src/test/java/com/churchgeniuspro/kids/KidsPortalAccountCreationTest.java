package com.churchgeniuspro.kids;

import com.churchgeniuspro.controller.EventCalendarController;
import com.churchgeniuspro.controller.MemberSignupController;
import com.churchgeniuspro.controller.MembershipFormController;
import com.churchgeniuspro.controller.PledgeController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.service.VerificationStore;
import com.churchgeniuspro.service.WhatsAppSenderService;
import com.churchgeniuspro.util.AppClock;
import com.churchgeniuspro.util.KidsPortalEligibility;
import com.churchgeniuspro.util.PublicSendLimiter;
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

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Kids Portal 13+ rule (2026-11-04): no login can be CREATED for an undated or under-13
 * child (Member Signup lookup / send-code / verify, Admin link-signup), and the Member
 * Portal cannot change the date of birth the church recorded for a child (B1).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Kids Portal 13+ — account creation and the church's record")
class KidsPortalAccountCreationTest {

    static final String CH = "CHURCH-A", TOKEN = "tok-signup", KID_REF = "MBR-kid";

    @Mock PublicScreenLinkRepository linkRepo;
    @Mock FamilyMemberRepository familyMemberRepository;
    @Mock LoginRepository loginRepository;
    @Mock ChurchRegistrationRepository churchRegistrationRepository;
    @Mock VerificationStore verificationStore;
    @Mock EmailService emailService;
    @Mock SubscriptionService subscriptionService;

    MemberSignupController signup;
    FamilyMember kid;
    Family fam;

    static void dob(FamilyMember m, Integer yearsAgo) {
        if (yearsAgo == null) { m.setBirthdayYear(null); m.setBirthdayMonth(null); m.setBirthdayDay(null); return; }
        LocalDate d = AppClock.today().minusYears(yearsAgo).minusDays(1);
        m.setBirthdayYear(d.getYear()); m.setBirthdayMonth(d.getMonthValue()); m.setBirthdayDay(d.getDayOfMonth());
    }

    @BeforeEach
    void setUp() {
        signup = new MemberSignupController(linkRepo, familyMemberRepository, loginRepository,
                churchRegistrationRepository, verificationStore, emailService, subscriptionService, new PublicSendLimiter());
        PublicScreenLink l = new PublicScreenLink();
        l.setToken(TOKEN); l.setAppClientId(CH); l.setPageUrl("/memberSignup"); l.setRevoked(false);
        when(linkRepo.findByToken(TOKEN)).thenReturn(Optional.of(l));
        fam = new Family(); fam.setId(1); fam.setAppClientId(CH);
        kid = new FamilyMember(); kid.setId(11); kid.setFamily(fam); kid.setAppClientId(CH);
        kid.setFirstName("Sam"); kid.setLastName("Lee"); kid.setRole("Child"); kid.setMemberRef(KID_REF); kid.setEmail("sam@example.org");
        when(familyMemberRepository.findByLastNameAndContact(anyString(), anyString(), eq(CH))).thenReturn(List.of());
        when(familyMemberRepository.findChildByNameAndFamilyContact("Sam", "Lee", "parent@example.org", CH)).thenReturn(List.of(kid));
        when(familyMemberRepository.findByMemberRef(KID_REF)).thenReturn(Optional.of(kid));
        when(familyMemberRepository.findHohEmailByFamilyMemberId(anyInt())).thenReturn("parent@example.org");
        when(loginRepository.findByClientId(anyString())).thenReturn(Optional.empty());
        when(loginRepository.existsByUsername(anyString())).thenReturn(false);
        when(verificationStore.generateAndStore(anyString(), anyString(), anyString())).thenReturn("123456");
        when(verificationStore.validate(anyString(), anyString(), anyString())).thenReturn(true);
        when(emailService.getChurchName(anyString())).thenReturn("Church A");
        when(subscriptionService.isFeatureEnabled(anyString(), anyString())).thenReturn(true);
    }

    static Map<String, String> body(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    ResponseEntity<Map<String, Object>> lookup() {
        return signup.lookup(body("appClientId", TOKEN, "firstName", "Sam", "lastName", "Lee",
                "contact", "parent@example.org"), new MockHttpServletRequest());
    }

    @Test @DisplayName("lookup refuses an undated or under-13 child BEFORE any code is created or e-mailed")
    void lookupRefuses() throws Exception {
        dob(kid, null);
        ResponseEntity<Map<String, Object>> r = lookup();
        assertThat(r.getStatusCode().value()).isEqualTo(403);
        assertThat(r.getBody()).containsEntry("status", "age_blocked").containsEntry("code", KidsPortalEligibility.CODE)
                .containsEntry("message", KidsPortalEligibility.MISSING_DOB_MESSAGE).doesNotContainKey("memberRef");
        dob(kid, 9);
        r = lookup();
        assertThat(r.getStatusCode().value()).isEqualTo(403);
        assertThat(r.getBody()).containsEntry("message", KidsPortalEligibility.UNDER_13_KIDS_MESSAGE);
        verifyNoInteractions(verificationStore);
        verify(emailService, never()).sendAccountEmailOrThrow(anyString(), anyString(), anyString(), anyString());
    }

    @Test @DisplayName("a 13+ child passes; if the record changes mid-flow, send-code and verify refuse and no login is saved")
    void laterStepsRecheck() throws Exception {
        dob(kid, 14);
        ResponseEntity<Map<String, Object>> r = lookup();
        assertThat(r.getStatusCode().value()).as("%s", r.getBody()).isEqualTo(200);
        String handle = String.valueOf(r.getBody().get("memberRef"));

        dob(kid, null);   // church office clears / a direct API caller skips the lookup
        ResponseEntity<Map<String, Object>> send = signup.sendCode(body("appClientId", TOKEN, "memberRef", handle,
                "username", "samlee", "password", "Str0ng!Passw0rd"), new MockHttpServletRequest());
        assertThat(send.getStatusCode().value()).isEqualTo(403);
        assertThat(send.getBody()).containsEntry("code", KidsPortalEligibility.CODE);
        verify(verificationStore, never()).generateAndStore(anyString(), anyString(), anyString());

        ResponseEntity<Map<String, Object>> ver = signup.verify(body("appClientId", TOKEN, "memberRef", handle,
                "username", "samlee", "password", "Str0ng!Passw0rd", "code", "123456"));
        assertThat(ver.getStatusCode().value()).isEqualTo(403);
        verify(loginRepository, never()).save(any(SignUp.class));
        verify(verificationStore).remove(KID_REF, "member-signup");
    }

    // ── Admin link-signup and Member Portal self-edit (MembershipFormController) ──

    @Mock MembershipFamilyRepository mfRepo; @Mock MembershipFamilyMemberRepository mfmRepo; @Mock FamilyRepository familyRepo;
    @Mock ChurchLogoRepository logoRepo; @Mock AppUserRepository appUserRepository; @Mock SubSourceRepository subSourceRepo;
    @Mock IncomeRepository incomeRepo; @Mock MeetingRepository meetingRepo; @Mock ChurchEventRepository churchEventRepo;
    @Mock ChurchEventDayRepository churchEventDayRepo; @Mock MemberMessageRepository memberMessageRepo;
    @Mock MemberPreferenceRepository memberPrefRepo; @Mock WhatsAppSenderService whatsAppSenderService;
    @Mock GroupRepository groupRepo; @Mock GroupMemberRepository groupMemberRepo; @Mock WorshipGroupRepository worshipGroupRepo;
    @Mock WorshipGroupMemberRepository worshipGroupMemberRepo; @Mock WorshipInstrumentRepository worshipInstrumentRepo;
    @Mock WorshipAssignmentRepository worshipAssignmentRepo; @Mock WorshipAssignmentMemberRepository worshipAssignmentMemberRepo;
    @Mock WorshipSongRepository worshipSongRepo; @Mock EventCalendarController eventCalendarController;
    @Mock PledgeCampaignRepository pledgeCampaignRepo; @Mock PledgeMemberRepository pledgeMemberRepo; @Mock PledgeController pledgeController;

    MembershipFormController forms() {
        return new MembershipFormController(linkRepo, mfRepo, mfmRepo, familyRepo, familyMemberRepository,
                churchRegistrationRepository, logoRepo, verificationStore, emailService, loginRepository, appUserRepository,
                subSourceRepo, incomeRepo, meetingRepo, churchEventRepo, churchEventDayRepo, memberMessageRepo,
                memberPrefRepo, whatsAppSenderService, groupRepo, groupMemberRepo, worshipGroupRepo,
                worshipGroupMemberRepo, worshipInstrumentRepo, worshipAssignmentRepo, worshipAssignmentMemberRepo,
                worshipSongRepo, eventCalendarController, pledgeCampaignRepo, pledgeMemberRepo, pledgeController,
                new PublicSendLimiter());
    }

    @Test @DisplayName("Admin link-signup refuses to give an undated or under-13 child a login; a 13+ child is linked")
    void adminLink() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "admin"); s.setAttribute("role", "Admin"); s.setAttribute("appClientId", CH);
        req.setSession(s);
        when(familyMemberRepository.findById(11)).thenReturn(Optional.of(kid));
        SignUp su = new SignUp(); su.setId(5); su.setClientId("MBR-new"); su.setActive(false);
        when(loginRepository.findById(5)).thenReturn(Optional.of(su));
        MembershipFamily mf = new MembershipFamily(); mf.setAppClientId(CH);
        when(mfRepo.findBySignupIdAndDeleteFlagFalse(5)).thenReturn(Optional.of(mf));
        when(familyMemberRepository.findByMemberRef("MBR-new")).thenReturn(Optional.empty());

        dob(kid, 10);
        ResponseEntity<?> r = forms().linkSignup(Map.of("signupId", 5, "familyMemberId", 11), req);
        assertThat(r.getStatusCode().value()).isEqualTo(403);
        assertThat(String.valueOf(r.getBody())).contains(KidsPortalEligibility.CODE);
        verify(loginRepository, never()).save(any(SignUp.class));
        verify(familyMemberRepository, never()).save(any(FamilyMember.class));

        dob(kid, 16);
        assertThat(forms().linkSignup(Map.of("signupId", 5, "familyMemberId", 11), req).getStatusCode().value()).isEqualTo(200);
    }

    MockHttpServletRequest memberSession(int memberId, String role) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("role", "Member"); s.setAttribute("memberId", memberId); s.setAttribute("memberRole", role);
        req.setSession(s);
        return req;
    }

    @Test @DisplayName("B1: the Member Portal cannot change a child's or under-13's date of birth; adults still can; unchanged values pass")
    void memberSelfEdit() {
        FamilyMember parent = new FamilyMember(); parent.setId(12); parent.setFamily(fam); parent.setRole("Head of Household");
        dob(parent, 40); dob(kid, 10);
        when(familyMemberRepository.findById(12)).thenReturn(Optional.of(parent));
        when(familyMemberRepository.findById(11)).thenReturn(Optional.of(kid));
        MembershipFormController c = forms();

        Map<String, Object> older = new HashMap<>(Map.of("birthdayYear", kid.getBirthdayYear() - 5));
        ResponseEntity<?> r = c.updateMember(11, older, memberSession(12, "Head of Household"));
        assertThat(r.getStatusCode().value()).isEqualTo(403);
        assertThat(String.valueOf(r.getBody())).contains("BIRTH_DATE_LOCKED");
        assertThat(kid.getBirthdayYear()).isEqualTo(AppClock.today().minusYears(10).minusDays(1).getYear());
        verify(familyMemberRepository, never()).save(kid);

        // the same values sent back (a whole-form save) are fine, and names still change
        Map<String, Object> same = new HashMap<>(Map.of("birthdayYear", kid.getBirthdayYear(),
                "birthdayMonth", kid.getBirthdayMonth(), "birthdayDay", kid.getBirthdayDay(), "firstName", "Samuel"));
        assertThat(c.updateMember(11, same, memberSession(12, "Head of Household")).getStatusCode().value()).isEqualTo(200);
        assertThat(kid.getFirstName()).isEqualTo("Samuel");

        // an adult can still correct their own date of birth from a normal member session
        Map<String, Object> adult = new HashMap<>(Map.of("birthdayYear", parent.getBirthdayYear() - 1));
        assertThat(c.updateMember(12, adult, memberSession(12, "Head of Household")).getStatusCode().value()).isEqualTo(200);

        // a Kids Portal session cannot change anyone's date of birth
        FamilyMember teen = new FamilyMember(); teen.setId(13); teen.setFamily(fam); teen.setRole("Child"); dob(teen, 14);
        when(familyMemberRepository.findById(13)).thenReturn(Optional.of(teen));
        Map<String, Object> parentDob = new HashMap<>(Map.of("birthdayYear", 1990));
        assertThat(c.updateMember(12, parentDob, memberSession(13, "Child")).getStatusCode().value()).isEqualTo(403);
    }
}
