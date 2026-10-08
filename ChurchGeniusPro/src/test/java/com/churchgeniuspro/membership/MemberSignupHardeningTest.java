package com.churchgeniuspro.membership;

import com.churchgeniuspro.controller.MemberSignupController;
import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.service.VerificationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import com.churchgeniuspro.util.PublicSendLimiter;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Member Signup flow: the tenant comes from the validated link token on every
 * step, never from the plaintext {@code appClientId} the page used to echo, and
 * the member's MBR reference (which doubles as the SignUp {@code client_id}) is
 * replaced on the wire by an opaque handle that only this server can resolve.
 * The page (memberSignup.html) is unchanged: it carries the values it is given
 * under the same keys.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemberSignupHardeningTest {

    private static final String OURS   = "CHURCH-A";
    private static final String THEIRS = "CHURCH-B";
    private static final String OUR_TOKEN   = "tok-ours-signup";
    private static final String THEIR_TOKEN = "tok-theirs-signup";
    private static final String MBR = "MBR-real-ref";

    @Mock PublicScreenLinkRepository   linkRepo;
    @Mock FamilyMemberRepository       familyMemberRepository;
    @Mock LoginRepository              loginRepository;
    @Mock ChurchRegistrationRepository churchRegistrationRepository;
    @Mock VerificationStore            verificationStore;
    @Mock EmailService                 emailService;
    @Mock SubscriptionService          subscriptionService;

    MemberSignupController controller;
    FamilyMember ada;

    @BeforeEach
    void setUp() {
        controller = new MemberSignupController(linkRepo, familyMemberRepository, loginRepository,
                churchRegistrationRepository, verificationStore, emailService, subscriptionService,
                new PublicSendLimiter());
        when(linkRepo.findByToken(OUR_TOKEN)).thenReturn(Optional.of(link(OUR_TOKEN, OURS, "/memberSignup")));
        when(linkRepo.findByToken(THEIR_TOKEN)).thenReturn(Optional.of(link(THEIR_TOKEN, THEIRS, "/memberSignup")));
        when(linkRepo.findByToken("tok-form")).thenReturn(Optional.of(link("tok-form", OURS, "/membershipForm")));
        when(churchRegistrationRepository.findByClientIdAndDeleteFlagFalse(anyString())).thenReturn(Optional.empty());

        Family fam = new Family(); fam.setId(1); fam.setAppClientId(OURS);
        ada = new FamilyMember();
        ada.setId(10); ada.setFamily(fam); ada.setAppClientId(OURS);
        ada.setFirstName("Ada"); ada.setLastName("Lovelace"); ada.setEmail("ada@example.org");
        ada.setMemberRef(MBR);
        when(familyMemberRepository.findByLastNameAndContact("Lovelace", "ada@example.org", OURS)).thenReturn(List.of(ada));
        when(familyMemberRepository.findByMemberRef(MBR)).thenReturn(Optional.of(ada));
        when(loginRepository.findByClientId(anyString())).thenReturn(Optional.empty());
        when(loginRepository.existsByUsername(anyString())).thenReturn(false);
        when(verificationStore.generateAndStore(anyString(), anyString(), anyString())).thenReturn("123456");
        when(emailService.getChurchName(anyString())).thenReturn("Church A");
    }

    private static PublicScreenLink link(String token, String clientId, String pageUrl) {
        PublicScreenLink l = new PublicScreenLink();
        l.setToken(token); l.setAppClientId(clientId); l.setPageUrl(pageUrl); l.setRevoked(false);
        return l;
    }

    private static Map<String, String> body(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** Runs a successful lookup and returns the opaque handle the page will carry as memberRef. */
    private String lookupHandle() {
        ResponseEntity<Map<String, Object>> res = controller.lookup(
                body("appClientId", OUR_TOKEN, "firstName", "Ada", "lastName", "Lovelace", "contact", "ada@example.org"), new MockHttpServletRequest());
        assertThat(res.getStatusCode().is2xxSuccessful()).as("%s", res.getBody()).isTrue();
        return String.valueOf(res.getBody().get("memberRef"));
    }

    // ── validate ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("validate returns the link token, not the tenant id, and refuses a token for another page")
    void validateHidesTenantAndChecksPage() {
        ResponseEntity<Map<String, Object>> ok = controller.validate(OUR_TOKEN);
        assertThat(ok.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(ok.getBody().get("appClientId")).isEqualTo(OUR_TOKEN);
        assertThat(String.valueOf(ok.getBody())).doesNotContain(OURS);

        assertThat(controller.validate("tok-form").getStatusCode().value()).isEqualTo(400);
    }

    // ── lookup ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A plaintext appClientId in the body is no longer accepted")
    void lookupRejectsPlaintextTenant() {
        when(linkRepo.findByToken(OURS)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> res = controller.lookup(
                body("appClientId", OURS, "firstName", "Ada", "lastName", "Lovelace", "contact", "ada@example.org"), new MockHttpServletRequest());

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        verify(familyMemberRepository, never()).findByLastNameAndContact(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("lookup searches the link's church and never echoes the MBR reference")
    void lookupUsesLinkTenantAndHidesMemberRef() {
        String handle = lookupHandle();

        assertThat(handle).isNotBlank().isNotEqualTo(MBR);
        verify(familyMemberRepository).findByLastNameAndContact("Lovelace", "ada@example.org", OURS);
    }

    // ── send-code / verify ──────────────────────────────────────────────────

    @Test
    @DisplayName("A handle is bound to the church whose link issued it")
    void handleIsTenantBound() {
        String handle = lookupHandle();

        ResponseEntity<Map<String, Object>> res = controller.sendCode(
                body("appClientId", THEIR_TOKEN, "memberRef", handle, "username", "ada", "password", "Str0ng!Passw0rd"), new MockHttpServletRequest());

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        verify(verificationStore, never()).generateAndStore(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("A raw MBR reference (or any guessed value) is not a valid handle")
    void rawMemberRefIsNotAHandle() {
        ResponseEntity<Map<String, Object>> res = controller.sendCode(
                body("appClientId", OUR_TOKEN, "memberRef", MBR, "username", "ada", "password", "Str0ng!Passw0rd"), new MockHttpServletRequest());

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        verify(familyMemberRepository, never()).findByMemberRef(anyString());
        verify(verificationStore, never()).generateAndStore(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("The full same-church flow still works and spends the handle")
    void ownTenantFlowWorks() {
        String handle = lookupHandle();
        when(verificationStore.validate(MBR, "member-signup", "123456")).thenReturn(true);
        when(subscriptionService.isFeatureEnabled(OURS, "memberPortal")).thenReturn(true);
        when(loginRepository.save(any(SignUp.class))).thenAnswer(i -> { SignUp s = i.getArgument(0); s.setId(77); return s; });

        ResponseEntity<Map<String, Object>> sent = controller.sendCode(
                body("appClientId", OUR_TOKEN, "memberRef", handle, "username", "ada", "password", "Str0ng!Passw0rd"), new MockHttpServletRequest());
        assertThat(sent.getStatusCode().is2xxSuccessful()).as("%s", sent.getBody()).isTrue();
        verify(verificationStore).generateAndStore(eq(MBR), eq("member-signup"), eq("ada@example.org"));

        ResponseEntity<Map<String, Object>> done = controller.verify(
                body("appClientId", OUR_TOKEN, "memberRef", handle, "username", "ada",
                     "password", "Str0ng!Passw0rd", "code", "123456"));
        assertThat(done.getStatusCode().is2xxSuccessful()).as("%s", done.getBody()).isTrue();
        verify(loginRepository).save(argThat(s -> MBR.equals(s.getClientId()) && "ada".equals(s.getUsername())));

        // one-time: the same handle is dead afterwards
        ResponseEntity<Map<String, Object>> again = controller.sendCode(
                body("appClientId", OUR_TOKEN, "memberRef", handle, "username", "ada2", "password", "Str0ng!Passw0rd"), new MockHttpServletRequest());
        assertThat(again.getStatusCode().value()).isEqualTo(404);
    }
}
