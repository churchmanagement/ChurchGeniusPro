package com.churchgeniuspro.mobile;

import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.MemberMessage;
import com.churchgeniuspro.hibernate.PushSubscription;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.MemberAccountDeletionService;
import com.churchgeniuspro.service.PublicSubmissionNotificationService;
import com.churchgeniuspro.service.RememberMeService;
import com.churchgeniuspro.util.PasswordUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Mobile Phase 2, M4: deleting a member's own account removes the LOGIN and what exists
 * only for it, and leaves the church's membership record untouched.
 */
class MemberAccountDeletionTest {

    static final String CHURCH = "CHR-1", REF = "MBR-abc", PW = "Secret#123";

    LoginRepository logins; FamilyMemberRepository members; MemberMessageRepository messages;
    MemberBlockRepository blocks; PushSubscriptionRepository push; RememberMeService rememberMe;
    MemberPreferenceRepository prefs; UserFavoriteRepository favs; DashboardPreferenceRepository dash;
    TrialTipStateRepository tips; PublicSubmissionNotificationService notifications; EmailService email;
    MemberAccountDeletionService svc;

    SignUp login; FamilyMember member; MemberMessage sent, received; PushSubscription sub;

    @BeforeEach
    void setUp() {
        logins = mock(LoginRepository.class); members = mock(FamilyMemberRepository.class);
        messages = mock(MemberMessageRepository.class); blocks = mock(MemberBlockRepository.class);
        push = mock(PushSubscriptionRepository.class); rememberMe = mock(RememberMeService.class);
        prefs = mock(MemberPreferenceRepository.class); favs = mock(UserFavoriteRepository.class);
        dash = mock(DashboardPreferenceRepository.class); tips = mock(TrialTipStateRepository.class);
        notifications = mock(PublicSubmissionNotificationService.class); email = mock(EmailService.class);
        svc = new MemberAccountDeletionService(logins, members, messages, blocks, push, rememberMe,
                prefs, favs, dash, tips, notifications, email);

        login = new SignUp(); login.setId(42); login.setUsername("ann"); login.setClientId(REF);
        login.setPassword(PasswordUtil.encode(PW)); login.setActive(true); login.setDeleted(false); login.setLinkGroup("grp");
        when(logins.findByClientId(REF)).thenReturn(Optional.of(login));
        when(logins.save(any())).thenAnswer(i -> i.getArgument(0));

        member = new FamilyMember(); member.setId(7); member.setAppClientId(CHURCH); member.setFirstName("Ann");
        member.setLastName("Able"); member.setEmail("ann@example.org"); member.setMemberRef(REF);
        when(members.findByIdAndTenant(7, CHURCH)).thenReturn(Optional.of(member));
        when(members.save(any())).thenAnswer(i -> i.getArgument(0));

        sent = new MemberMessage(); sent.setId(1L); sent.setSenderMemberId(7); sent.setRecipientMemberId(8); sent.setSenderName("Ann Able"); sent.setBody("hi");
        received = new MemberMessage(); received.setId(2L); received.setSenderMemberId(8); received.setRecipientMemberId(7); received.setSenderName("Bob"); received.setBody("hello");
        when(messages.findSent(7)).thenReturn(List.of(sent));
        when(messages.findInbox(7)).thenReturn(List.of(received));
        when(messages.save(any())).thenAnswer(i -> i.getArgument(0));

        sub = new PushSubscription(); sub.setUserKey(REF); sub.setActive(true);
        when(push.findByUserKeyAndActiveTrue(REF)).thenReturn(List.of(sub));
        when(prefs.findByMemberId(7)).thenReturn(Optional.empty());
        when(favs.findByClientIdAndUserRefOrderBySortOrderAscIdAsc(anyString(), anyString())).thenReturn(List.of());
        when(dash.findByUsernameAndClientId(anyString(), anyString())).thenReturn(Optional.empty());
        when(tips.findByClientIdAndUsername(anyString(), anyString())).thenReturn(List.of());
    }

    @Test
    @DisplayName("the login is scrubbed; the church's membership record is only stamped")
    void deletesLoginKeepsRecord() {
        MemberAccountDeletionService.Outcome out = svc.deleteOwnAccount(REF, 7, CHURCH, "ann", PW);
        assertThat(out.ok()).isTrue();
        assertThat(out.memberEmail()).isEqualTo("ann@example.org");

        // login
        assertThat(login.getDeleted()).isTrue();
        assertThat(login.getActive()).isFalse();
        assertThat(login.getPassword()).isEmpty();
        assertThat(login.getUsername()).startsWith("deleted-42-").isNotEqualTo("ann");
        // audit 2026-10-28 H2: the member ref is released so a re-registration never shares the key
        assertThat(login.getClientId()).isEqualTo(REF + "#deleted-42").isNotEqualTo(REF);
        assertThat(login.getLinkGroup()).isNull();
        verify(rememberMe).revokeAllFor(42);
        assertThat(sub.isActive()).isFalse();
        verify(blocks).deleteAllInvolving(CHURCH, 7);

        // chat
        assertThat(sent.getSenderName()).isEqualTo("Former member");
        assertThat(sent.getBody()).as("other person's inbox keeps the text").isEqualTo("hi");
        assertThat(sent.isDeletedBySender()).isTrue();
        assertThat(received.isDeletedByRecipient()).isTrue();

        // the church's record: NOT deleted, NOT flagged, nothing but the stamp
        assertThat(member.isDeleteFlag()).isFalse();
        assertThat(member.getFirstName()).isEqualTo("Ann");
        assertThat(member.getEmail()).isEqualTo("ann@example.org");
        assertThat(member.getMemberRef()).isEqualTo(REF);
        assertThat(member.getPortalAccountDeletedAt()).isNotNull();
        verify(members, never()).delete(any());
        verify(members, never()).deleteById(any());

        verify(notifications).record(eq(CHURCH), eq(PublicSubmissionNotificationService.Type.ACCOUNT_DELETION), anyString(), contains("giving history"), eq(7L));
    }

    @Test
    @DisplayName("wrong password, non-member ref, unknown login, member of another church: nothing changes")
    void refusals() {
        assertThat(svc.deleteOwnAccount(REF, 7, CHURCH, "ann", "wrong").status()).isEqualTo(403);
        assertThat(svc.deleteOwnAccount("USR-1", 7, CHURCH, "ann", PW).status()).isEqualTo(403);
        assertThat(svc.deleteOwnAccount("MBR-nope", 7, CHURCH, "ann", PW).status()).isEqualTo(404);
        assertThat(svc.deleteOwnAccount(REF, 7, "CHR-OTHER", "ann", PW).status()).isEqualTo(404);
        assertThat(login.getDeleted()).isFalse();
        assertThat(login.getUsername()).isEqualTo("ann");
        assertThat(member.getPortalAccountDeletedAt()).isNull();
        verify(logins, never()).save(any());
        verifyNoInteractions(rememberMe, notifications);
    }

    @Test
    @DisplayName("confirmation email goes through account mail and never throws")
    void confirmation() {
        svc.sendConfirmation("ann@example.org", "<Ann>", CHURCH);
        verify(email).sendAccountEmail(eq("ann@example.org"), contains("deleted"), contains("&lt;Ann&gt;"), eq(CHURCH));
        doThrow(new RuntimeException("smtp down")).when(email).sendAccountEmail(anyString(), anyString(), anyString(), anyString());
        svc.sendConfirmation("ann@example.org", "Ann", CHURCH);   // must not propagate
        svc.sendConfirmation(null, "Ann", CHURCH);
    }
}
