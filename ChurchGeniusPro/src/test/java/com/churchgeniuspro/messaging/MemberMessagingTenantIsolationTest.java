package com.churchgeniuspro.messaging;

import com.churchgeniuspro.controller.EventCalendarController;
import com.churchgeniuspro.controller.MembershipFormController;
import com.churchgeniuspro.controller.PledgeController;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.MemberMessage;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import com.churchgeniuspro.util.PublicSendLimiter;

/**
 * Member-portal messaging is tenant-bound and thread-bound.
 *
 * <p>Before: {@code POST /api/member/messages} stored any {@code recipientId} from the
 * body without loading it, so a member could message (and reveal a name to) any
 * member of any church; {@code POST /messages/{rootId}/reply} let anyone reply into
 * any thread by id; and {@code POST /api/member/send-email} relayed mail to any raw
 * address or any member id under the church's sender identity.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemberMessagingTenantIsolationTest {

    private static final String OURS   = "CHURCH-A";
    private static final String THEIRS = "CHURCH-B";
    private static final int SELF = 10, OURS_OTHER = 20, THEIR_MEMBER = 500;

    @Mock PublicScreenLinkRepository        linkRepo;
    @Mock MembershipFamilyRepository        mfRepo;
    @Mock MembershipFamilyMemberRepository  mfmRepo;
    @Mock FamilyRepository                  familyRepo;
    @Mock FamilyMemberRepository            familyMemberRepo;
    @Mock ChurchRegistrationRepository      churchRegRepo;
    @Mock ChurchLogoRepository              logoRepo;
    @Mock VerificationStore                 verificationStore;
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

    @BeforeEach
    void setUp() {
        controller = new MembershipFormController(linkRepo, mfRepo, mfmRepo, familyRepo, familyMemberRepo,
                churchRegRepo, logoRepo, verificationStore, emailService, loginRepository, appUserRepository,
                subSourceRepo, incomeRepo, meetingRepo, churchEventRepo, churchEventDayRepo, memberMessageRepo,
                memberPrefRepo, whatsAppSenderService, groupRepo, groupMemberRepo, worshipGroupRepo,
                worshipGroupMemberRepo, worshipInstrumentRepo, worshipAssignmentRepo, worshipAssignmentMemberRepo,
                worshipSongRepo, eventCalendarController, pledgeCampaignRepo, pledgeMemberRepo, pledgeController,
                new PublicSendLimiter());
        // Phase B: the send-email handler asks how the tenant's mail is delivered; a paying church is NORMAL.
        org.mockito.Mockito.lenient().when(emailService.delivery(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new EmailService.Delivery(EmailService.DeliveryMode.NORMAL, null, null));

        FamilyMember self = member(SELF, OURS, "Ada", "self@example.org");
        when(familyMemberRepo.findById(SELF)).thenReturn(Optional.of(self));
        when(familyMemberRepo.findByIdAndTenant(SELF, OURS)).thenReturn(Optional.of(self));
        when(familyMemberRepo.findByIdAndTenant(OURS_OTHER, OURS))
                .thenReturn(Optional.of(member(OURS_OTHER, OURS, "Grace", "grace@example.org")));
        // The other church's member exists by bare id, but the scoped finder must not see it.
        when(familyMemberRepo.findById(THEIR_MEMBER))
                .thenReturn(Optional.of(member(THEIR_MEMBER, THEIRS, "Mallory", "mallory@example.org")));
        when(familyMemberRepo.findByIdAndTenant(THEIR_MEMBER, OURS)).thenReturn(Optional.empty());
        when(memberMessageRepo.save(any(MemberMessage.class))).thenAnswer(i -> {
            MemberMessage m = i.getArgument(0);
            if (m.getId() == null) m.setId(99L);
            return m;
        });
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static FamilyMember member(int id, String clientId, String first, String email) {
        FamilyMember fm = new FamilyMember();
        fm.setId(id);
        fm.setAppClientId(clientId);
        fm.setFirstName(first);
        fm.setLastName("Test");
        fm.setEmail(email);
        return fm;
    }

    private static MemberMessage root(long id, String clientId, int sender, int recipient) {
        MemberMessage m = new MemberMessage();
        m.setId(id);
        m.setAppClientId(clientId);
        m.setSenderMemberId(sender);
        m.setRecipientMemberId(recipient);
        m.setBody("hello");
        return m;
    }

    private static MockHttpServletRequest memberRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("username", "self@example.org");
        session.setAttribute("role", "Member");
        session.setAttribute("memberId", SELF);
        session.setAttribute("appClientId", OURS);
        req.setSession(session);
        return req;
    }

    // ── POST /api/member/messages ───────────────────────────────────────────

    @Test
    @DisplayName("A recipient id from another church is 404 and nothing is stored")
    void sendToForeignRecipientRefused() {
        ResponseEntity<?> res = controller.sendMessage(
                Map.of("recipientId", THEIR_MEMBER, "body", "hi"), memberRequest());

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        verify(memberMessageRepo, never()).save(any(MemberMessage.class));
    }

    @Test
    @DisplayName("A recipient in this church still receives the message")
    void sendToOwnChurchWorks() {
        ResponseEntity<?> res = controller.sendMessage(
                Map.of("recipientId", OURS_OTHER, "body", "hi"), memberRequest());

        assertThat(res.getStatusCode().is2xxSuccessful()).as("%s", res.getBody()).isTrue();
        verify(memberMessageRepo).save(argThat(m -> OURS.equals(m.getAppClientId())
                && m.getRecipientMemberId() == OURS_OTHER && m.getSenderMemberId() == SELF));
    }

    // ── POST /api/member/messages/{rootId}/reply ────────────────────────────

    @Test
    @DisplayName("Replying into another church's thread is 404 even if the id is known")
    void replyIntoForeignThreadRefused() {
        when(memberMessageRepo.findById(1L)).thenReturn(Optional.of(root(1L, THEIRS, THEIR_MEMBER, 501)));

        ResponseEntity<?> res = controller.replyMessage(1L, Map.of("body", "hi"), memberRequest());

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        verify(memberMessageRepo, never()).save(any(MemberMessage.class));
    }

    @Test
    @DisplayName("Replying into a same-church thread you are not part of is 404 too")
    void replyIntoSomeoneElsesThreadRefused() {
        when(memberMessageRepo.findById(2L)).thenReturn(Optional.of(root(2L, OURS, OURS_OTHER, 30)));

        ResponseEntity<?> res = controller.replyMessage(2L, Map.of("body", "hi"), memberRequest());

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        verify(memberMessageRepo, never()).save(any(MemberMessage.class));
    }

    @Test
    @DisplayName("A participant can still reply, and the reply goes to the other party")
    void replyAsParticipantWorks() {
        when(memberMessageRepo.findById(3L)).thenReturn(Optional.of(root(3L, OURS, OURS_OTHER, SELF)));

        ResponseEntity<?> res = controller.replyMessage(3L, Map.of("body", "hi"), memberRequest());

        assertThat(res.getStatusCode().is2xxSuccessful()).as("%s", res.getBody()).isTrue();
        verify(memberMessageRepo).save(argThat(m -> m.getParentId() == 3L
                && m.getRecipientMemberId() == OURS_OTHER && OURS.equals(m.getAppClientId())));
    }

    // ── POST /api/member/send-email ─────────────────────────────────────────

    @Test
    @DisplayName("Email to another church's member id is silently skipped; raw directEmail is no longer honoured")
    void sendEmailIsTenantScopedAndNoRawAddress() {
        ResponseEntity<?> foreign = controller.sendEmailToMembers(
                Map.of("subject", "s", "body", "b", "recipientIds", List.of(THEIR_MEMBER)), memberRequest());
        assertThat(foreign.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(((Map<?, ?>) foreign.getBody()).get("sent")).isEqualTo(0);

        ResponseEntity<?> raw = controller.sendEmailToMembers(
                Map.of("subject", "s", "body", "b", "directEmail", "victim@example.org"), memberRequest());
        assertThat(raw.getStatusCode().value()).isEqualTo(400);

        verify(emailService, never()).sendOrgEmail(anyString(), anyString(), anyString(), anyString());
        verify(familyMemberRepo, never()).findById(THEIR_MEMBER);
    }

    @Test
    @DisplayName("Email to a member of this church is still delivered")
    void sendEmailToOwnMemberWorks() {
        ResponseEntity<?> res = controller.sendEmailToMembers(
                Map.of("subject", "s", "body", "b", "recipientIds", List.of(OURS_OTHER)), memberRequest());

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(((Map<?, ?>) res.getBody()).get("sent")).isEqualTo(1);
        verify(emailService).sendOrgEmail(eq("grace@example.org"), anyString(), anyString(), eq(OURS));
    }
}
