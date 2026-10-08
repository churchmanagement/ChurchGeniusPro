package com.churchgeniuspro.security;

import com.churchgeniuspro.controller.LoginController;
import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.ServiceClientRepository;
import com.churchgeniuspro.repository.UserPermissionsRepository;
import com.churchgeniuspro.service.LoginProtectionService;
import com.churchgeniuspro.service.SecurityAuditService;
import com.churchgeniuspro.util.PasswordUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Subscription expiry at the front door.
 *
 * <p>The staff and church paths were already gated by
 * {@code countValidChurchLogin}/{@code countValidNonChurchLogin}, both of which join to
 * {@code service_client} and require {@code end_date > current_date}. Member and child
 * portal logins were not: they have no {@code app_user} row, so that query returns 0 for
 * every member and the whole check was skipped for them. The effect was that when a
 * church's subscription lapsed the staff were cut off and the congregation was not.
 *
 * <p>These tests hold that gap closed, and — just as importantly — hold the fix from
 * over-reaching, because a check that locks members out on a missing linkage row would be
 * a far worse outage than the one it fixes.
 */
class SubscriptionExpiryLoginTest {

    private static final String PASSWORD = "CorrectHorse7";
    private static final String HASH     = PasswordUtil.encode(PASSWORD);

    private LoginRepository              loginRepo;
    private AppUserRepository            appUserRepo;
    private ServiceClientRepository      serviceClientRepo;
    private FamilyMemberRepository       familyMemberRepo;
    private LoginProtectionService       protection;
    private MockMvc                      mvc;

    @BeforeEach
    void setUp() {
        loginRepo         = mock(LoginRepository.class);
        appUserRepo       = mock(AppUserRepository.class);
        serviceClientRepo = mock(ServiceClientRepository.class);
        familyMemberRepo  = mock(FamilyMemberRepository.class);
        protection        = mock(LoginProtectionService.class);

        // Never blocked — these tests are about subscription state, not rate limiting.
        when(protection.check(any(), anyString()))
                .thenReturn(new LoginProtectionService.GuardResult(false, 0L, false));

        LoginController controller = new LoginController(
                loginRepo, appUserRepo, mock(ChurchRegistrationRepository.class),
                serviceClientRepo, mock(UserPermissionsRepository.class), familyMemberRepo,
                protection, mock(SecurityAuditService.class),
                mock(com.churchgeniuspro.service.DemoAccessService.class));

        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ── The gap that was open ────────────────────────────────────────────────

    @Test
    @DisplayName("a member portal login is refused once the church's subscription has expired")
    void expiredMemberIsRefused() throws Exception {
        givenMemberAccount("MBR-token-1", "CHR-100");
        givenSubscription("CHR-100", com.churchgeniuspro.util.AppClock.today().minusDays(1));

        mvc.perform(loginAs("member@example.org"))
           .andExpect(status().isForbidden())
           .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("expired")));

        // Recorded as a denial, not a failure: the password was correct, so this must not
        // move any brute-force counter or the member could throttle themselves out.
        verify(protection).recordDenied(any(), eq("member@example.org"),
                eq("SUBSCRIPTION_EXPIRED"), anyString(), eq("/login"));
        verify(protection, never()).recordFailure(any(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("the expiry date is in the message, so the admin knows what to renew")
    void messageNamesTheDate() throws Exception {
        LocalDate ended = com.churchgeniuspro.util.AppClock.today().minusDays(3);
        givenMemberAccount("MBR-token-1", "CHR-100");
        givenSubscription("CHR-100", ended);

        mvc.perform(loginAs("member@example.org"))
           .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(ended.toString())));
    }

    @Test
    @DisplayName("expiry today already blocks — same boundary as the login SQL")
    void expiringTodayBlocks() throws Exception {
        givenMemberAccount("MBR-token-1", "CHR-100");
        givenSubscription("CHR-100", com.churchgeniuspro.util.AppClock.today());

        mvc.perform(loginAs("member@example.org")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a member on a live subscription still signs in")
    void liveSubscriptionStillWorks() throws Exception {
        givenMemberAccount("MBR-token-1", "CHR-100");
        givenSubscription("CHR-100", com.churchgeniuspro.util.AppClock.today().plusDays(30));

        mvc.perform(loginAs("member@example.org"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("success"));
    }

    // ── The fix must not create a new outage ─────────────────────────────────

    @Test
    @DisplayName("a member whose family linkage is missing is NOT locked out")
    void unresolvableMemberFailsOpen() throws Exception {
        // No family_member row for the token and no email match. Before this change the
        // member could sign in; a check that "fails closed" here would lock out every
        // member whose member_ref was never backfilled — a much bigger outage than the
        // one being fixed. So: unknown org means no opinion, and the login proceeds.
        givenSignup("MBR-token-1", "member@example.org", false);
        when(familyMemberRepo.findByMemberRef(anyString())).thenReturn(Optional.empty());
        when(familyMemberRepo.findActiveByEmail(anyString())).thenReturn(List.of());

        mvc.perform(loginAs("member@example.org")).andExpect(status().isOk());
        verifyNoInteractions(serviceClientRepo);
    }

    @Test
    @DisplayName("a member whose church has no service_client row is NOT locked out")
    void missingSubscriptionRowFailsOpen() throws Exception {
        givenMemberAccount("MBR-token-1", "CHR-100");
        when(serviceClientRepo.findByClientId("CHR-100")).thenReturn(Optional.empty());

        mvc.perform(loginAs("member@example.org")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a subscription with no end date is open-ended, not expired")
    void nullEndDateFailsOpen() throws Exception {
        givenMemberAccount("MBR-token-1", "CHR-100");
        givenSubscription("CHR-100", null);

        mvc.perform(loginAs("member@example.org")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a lookup that throws does not cost the member their sign-in")
    void lookupFailureFailsOpen() throws Exception {
        givenSignup("MBR-token-1", "member@example.org", false);
        when(familyMemberRepo.findByMemberRef(anyString()))
                .thenThrow(new RuntimeException("connection reset"));

        mvc.perform(loginAs("member@example.org")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a suspended-but-in-date subscription does not block members")
    void statusAloneDoesNotBlockMembers() throws Exception {
        // service_client.status has never been part of a member's sign-in. Starting to
        // enforce it here would lock people out over a field nobody set with members in
        // mind. Expiry is a date; that is all this check looks at.
        givenMemberAccount("MBR-token-1", "CHR-100");
        ServiceClient sc = subscription("CHR-100", com.churchgeniuspro.util.AppClock.today().plusDays(30));
        sc.setStatus("Suspended");
        when(serviceClientRepo.findByClientId("CHR-100")).thenReturn(Optional.of(sc));

        mvc.perform(loginAs("member@example.org")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("expiry is only reached with the right password — a wrong one still 401s")
    void wrongPasswordStillGenericallyFails() throws Exception {
        givenMemberAccount("MBR-token-1", "CHR-100");
        givenSubscription("CHR-100", com.churchgeniuspro.util.AppClock.today().minusDays(1));
        when(protection.recordFailure(any(), anyString(), anyString(), any(), anyString()))
                .thenReturn(null);

        // The expiry message must not become an oracle: someone probing usernames should
        // not be able to learn that an account exists by seeing a subscription message.
        mvc.perform(post("/login").contentType("application/json")
                        .content("{\"username\":\"member@example.org\",\"password\":\"wrong\"}"))
           .andExpect(status().isUnauthorized())
           .andExpect(jsonPath("$.message").value(
                   org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("expired"))));
    }

    // ── Staff: the message improves, the decision does not change ────────────

    @Test
    @DisplayName("an expired staff login now says so instead of 'access is restricted'")
    void staffGetsTheExpiryMessage() throws Exception {
        givenSignup("USR-1", "staff@example.org", false);
        com.churchgeniuspro.hibernate.AppUser staff = new com.churchgeniuspro.hibernate.AppUser();
        staff.setUserId("USR-1");
        staff.setClientId("CHR-100");
        staff.setEnabled(true);
        staff.setDeleteFlag(false);
        when(appUserRepo.findByUserIdAndDeleteFlagFalse("USR-1")).thenReturn(Optional.of(staff));
        when(loginRepo.countValidNonChurchLogin(anyString())).thenReturn(0);   // gate already refuses
        givenSubscription("CHR-100", com.churchgeniuspro.util.AppClock.today().minusDays(2));

        mvc.perform(loginAs("staff@example.org"))
           .andExpect(status().isForbidden())
           .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("expired")));
    }

    @Test
    @DisplayName("a staff refusal for any other reason keeps the generic message")
    void staffOtherReasonsStayGeneric() throws Exception {
        givenSignup("USR-1", "staff@example.org", false);
        when(appUserRepo.findByUserIdAndDeleteFlagFalse("USR-1")).thenReturn(Optional.empty());
        when(loginRepo.countValidNonChurchLogin(anyString())).thenReturn(0);

        // No app_user row → orgClientId unknown → nothing to attribute to expiry, and the
        // response stays as vague as it was, which is what we want for an unresolved account.
        mvc.perform(loginAs("staff@example.org"))
           .andExpect(status().isForbidden())
           .andExpect(jsonPath("$.message").value("Account access is restricted. Please contact your administrator."));
        verify(protection).recordDenied(any(), anyString(), eq("ACCESS_RESTRICTED"), any(), eq("/login"));
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginAs(String username) {
        return post("/login").contentType("application/json")
                .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");
    }

    private void givenSignup(String clientId, String username, boolean church) {
        SignUp s = new SignUp();
        s.setUsername(username);
        s.setPassword(HASH);
        s.setClientId(clientId);
        s.setChurch(church);
        s.setActive(true);
        s.setDeleted(false);
        when(loginRepo.findByUsernameAndDeletedFalse(username)).thenReturn(Optional.of(s));
    }

    /** A member portal signup whose family belongs to {@code orgClientId}. */
    private void givenMemberAccount(String memberRef, String orgClientId) {
        givenSignup(memberRef, "member@example.org", false);
        Family family = new Family();
        family.setAppClientId(orgClientId);
        FamilyMember fm = new FamilyMember();
        fm.setMemberRef(memberRef);
        fm.setFamily(family);
        fm.setFirstName("Pat");
        fm.setLastName("Member");
        when(familyMemberRepo.findByMemberRef(memberRef)).thenReturn(Optional.of(fm));
    }

    private void givenSubscription(String clientId, LocalDate endDate) {
        when(serviceClientRepo.findByClientId(clientId))
                .thenReturn(Optional.of(subscription(clientId, endDate)));
    }

    private static ServiceClient subscription(String clientId, LocalDate endDate) {
        ServiceClient sc = new ServiceClient();
        sc.setClientId(clientId);
        sc.setSubscriptionType("PRO");
        sc.setStartDate(com.churchgeniuspro.util.AppClock.today().minusDays(365));
        sc.setEndDate(endDate);
        sc.setStatus("Active");
        sc.setDeleteFlag(false);
        return sc;
    }
}
