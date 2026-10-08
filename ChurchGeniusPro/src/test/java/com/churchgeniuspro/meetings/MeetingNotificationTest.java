package com.churchgeniuspro.meetings;

import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.Meeting;
import com.churchgeniuspro.hibernate.MeetingType;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import com.churchgeniuspro.repository.MeetingRepository;
import com.churchgeniuspro.repository.MeetingTypeRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.MeetingService;
import com.churchgeniuspro.service.MessagingPolicy;
import com.churchgeniuspro.service.WhatsAppSenderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Meetings → Send Meeting Notification. The send loop used to run inside a read-only
 * transaction: the first email of a month makes the usage counter INSERT the month's
 * row, PostgreSQL refuses it, and every later statement failed with "current
 * transaction is aborted". The loop now runs outside the transaction (the real
 * database proof is {@code MeetingNotificationIT}); this pins the counting and the
 * Trial/demo reporting.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Meetings — Send Meeting Notification")
class MeetingNotificationTest {

    private static final String CID = "CHR-1";

    @Mock MeetingRepository      meetingRepo;
    @Mock MeetingTypeRepository  typeRepo;
    @Mock FamilyMemberRepository familyMemberRepo;
    @Mock FamilyRepository       familyRepo;
    @Mock EmailService           emailService;
    @Mock WhatsAppSenderService  whatsApp;

    MeetingService service;

    @BeforeEach
    void setUp() {
        service = new MeetingService(meetingRepo, typeRepo, familyMemberRepo, familyRepo, emailService, whatsApp);
        MeetingType t = new MeetingType(); t.setTypeName("Prayer Meeting");
        Meeting m = new Meeting(); m.setId(7); m.setAppClientId(CID); m.setMeetingType(t); m.setMeetingDate(LocalDate.of(2026, 10, 12));
        when(meetingRepo.findByIdAndAppClientIdAndDeleteFlagFalse(7, CID)).thenReturn(Optional.of(m));
        when(familyMemberRepo.findByMemberTypeWithEmailByAppUser("Member", CID))
                .thenReturn(List.of(member("a@x.org"), member("B@x.org"), member("a@x.org")));
        when(familyMemberRepo.findByMemberTypeWithEmailByAppUser("Guest", CID))
                .thenReturn(List.of(member("g@x.org")));
        when(emailService.delivery(CID)).thenReturn(new EmailService.Delivery(EmailService.DeliveryMode.NORMAL, null, null));
    }

    private static FamilyMember member(String email) {
        FamilyMember fm = new FamilyMember(); fm.setEmail(email); return fm;
    }

    @Test
    @DisplayName("more than one recipient: each distinct address is emailed once and counted")
    void multipleRecipients() {
        Map<String, Object> r = service.sendManualNotification(7, List.of("Email"), List.of("Members", "Guests"), CID);

        assertThat(r).containsEntry("emailsSent", 3).containsEntry("emailsBlocked", 0).doesNotContainKey("blockReason");
        verify(emailService).sendOrgEmail(eq("a@x.org"), contains("Prayer Meeting"), anyString(), eq(CID));
        verify(emailService).sendOrgEmail(eq("b@x.org"), anyString(), anyString(), eq(CID));
        verify(emailService).sendOrgEmail(eq("g@x.org"), anyString(), anyString(), eq(CID));
        verify(emailService, times(3)).sendOrgEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Trial/demo: nothing is attempted and the result says blocked, not sent")
    void blockedTenantIsReportedNotCounted() {
        when(emailService.emailBlockReason(CID)).thenReturn(MessagingPolicy.TRIAL_EMAIL_MSG);
        when(emailService.delivery(CID)).thenReturn(new EmailService.Delivery(EmailService.DeliveryMode.BLOCKED, MessagingPolicy.TRIAL_EMAIL_MSG, null));

        Map<String, Object> r = service.sendManualNotification(7, List.of("Email"), List.of("Members"), CID);

        assertThat(r).containsEntry("emailsSent", 0).containsEntry("emailsBlocked", 2)
                     .containsEntry("blockReason", MessagingPolicy.TRIAL_EMAIL_MSG);
        verify(emailService, never()).sendOrgEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Trial/demo with a verified test address: one test email for the action, the rest simulated")
    void testAddressGetsOneCopy() {
        when(emailService.emailBlockReason(CID)).thenReturn(MessagingPolicy.TRIAL_EMAIL_MSG);
        when(emailService.delivery(CID)).thenReturn(new EmailService.Delivery(EmailService.DeliveryMode.TEST,
                MessagingPolicy.TRIAL_EMAIL_MSG, "te***@tri***.test"));
        // The real EmailService claims the action's one test email; the mock imitates that.
        doAnswer(i -> {
            var scope = com.churchgeniuspro.util.EmailActionScope.current();
            if (scope != null) { if (scope.claim(CID)) scope.recordTestEmailSent(); else scope.recordSimulated(); }
            return null;
        }).when(emailService).sendOrgEmail(anyString(), anyString(), anyString(), eq(CID));

        Map<String, Object> r = service.sendManualNotification(7, List.of("Email"), List.of("Members", "Guests"), CID);

        assertThat(r).containsEntry("emailsSent", 0).containsEntry("emailsBlocked", 0)
                     .containsEntry("testEmailsSent", 1).containsEntry("simulated", 2)
                     .containsEntry("testEmail", "te***@tri***.test");
        verify(emailService, times(3)).sendOrgEmail(anyString(), anyString(), anyString(), eq(CID));
    }

    @Test
    @DisplayName("the send loop is no longer inside a read-only transaction")
    void sendIsNotTransactional() throws Exception {
        Method m = MeetingService.class.getMethod("sendManualNotification", Integer.class, List.class, List.class, String.class);
        assertThat(m.getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNull();
    }

    @Test
    @DisplayName("the page shows a blocked count instead of a success message")
    void pageShowsBlocked() throws IOException {
        String s = Files.readString(Paths.get("src/main/resources/static/meeting.html"));
        assertThat(s).contains("data.emailsBlocked > 0", "data.blockReason", "data.testEmail", "data.simulated");
    }
}
