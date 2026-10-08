package com.churchgeniuspro.kidsministry;

import com.churchgeniuspro.controller.KidsMinistryController;
import com.churchgeniuspro.hibernate.KmVolunteer;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.MessagingPolicy;
import com.churchgeniuspro.service.PublicLinkResolver;
import com.churchgeniuspro.service.SmsService;
import com.churchgeniuspro.service.SubscriptionService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * H3: the Kids Ministry volunteer broadcast must be sent AS the church.
 *
 * <p>It used to call the tenant-less {@code sendGenericEmail(to, subject, body)},
 * which skips the Trial/Demo block, the unsubscribe list and the monthly
 * allowance — so a trial or demo admin could mail real volunteers while every
 * other send path refused. Now it passes the tenant, and refuses up front with
 * the policy's own reason so the admin is told rather than shown "sent: 2".
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Kids Ministry volunteer broadcast — Trial/Demo gate (H3)")
class VolunteerBroadcastTrialGateTest {

    private static final String TRIAL  = "TRIAL-1757300000456";
    private static final String PAYING = "CHR-real-church";

    @Mock PublicLinkResolver links;
    @Mock KmChildRepository childRepo;
    @Mock KmClassroomRepository classroomRepo;
    @Mock KmAuthorizedPickupRepository pickupRepo;
    @Mock KmCheckinRepository checkinRepo;
    @Mock KmVolunteerRepository volunteerRepo;
    @Mock KmVolunteerRoleRepository kmVolRoleRepo;
    @Mock KmVolunteerRoleAssignmentRepository kmVolRoleAssignRepo;
    @Mock FamilyMemberRepository familyMemberRepo;
    @Mock KmChildSetupRepository setupRepo;
    @Mock EmailService emailService;
    @Mock SmsService smsService;
    @Mock SubscriptionService subscriptionService;
    @Mock MessagingPolicy messagingPolicy;

    private KidsMinistryController controller;

    @BeforeEach
    void setUp() {
        controller = new KidsMinistryController(childRepo, classroomRepo, pickupRepo, checkinRepo,
                volunteerRepo, kmVolRoleRepo, kmVolRoleAssignRepo, familyMemberRepo, setupRepo,
                emailService, smsService, subscriptionService, links);
        controller.setMessagingPolicy(messagingPolicy);

        for (String tenant : new String[] { TRIAL, PAYING }) {
            KmVolunteer v = new KmVolunteer();
            v.setClientId(tenant);
            v.setName("Vol One");
            v.setEmail("vol@example.org");
            v.setPhone("(217) 555-0100");
            when(volunteerRepo.findByClientIdAndDeleteFlagFalseOrderByNameAsc(tenant)).thenReturn(List.of(v));
        }
        when(messagingPolicy.emailBlockReason(TRIAL)).thenReturn(MessagingPolicy.TRIAL_EMAIL_MSG);
        when(messagingPolicy.smsBlockReason(TRIAL)).thenReturn(MessagingPolicy.TRIAL_SMS_MSG);
        when(messagingPolicy.emailBlockReason(PAYING)).thenReturn(null);
        // Phase B: the controller asks EmailService how the tenant's mail is delivered.
        when(emailService.delivery(TRIAL)).thenReturn(new EmailService.Delivery(EmailService.DeliveryMode.BLOCKED, MessagingPolicy.TRIAL_EMAIL_MSG, null));
        when(emailService.delivery(PAYING)).thenReturn(new EmailService.Delivery(EmailService.DeliveryMode.NORMAL, null, null));
        when(messagingPolicy.smsBlockReason(PAYING)).thenReturn(null);
        when(smsService.sendForClient(anyString(), anyString(), anyString())).thenReturn(SmsService.SendOutcome.ok());
    }

    private static MockHttpServletRequest adminOf(String clientId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("appClientId", clientId);
        s.setAttribute("username", "admin@" + clientId);
        s.setAttribute("role", "Admin");
        req.setSession(s);
        return req;
    }

    @Test
    @DisplayName("a trial admin's email broadcast is refused with the policy's reason and nothing is sent")
    void trialEmailRefused() {
        ResponseEntity<?> r = controller.sendEmail(
                Map.of("to", List.of("vol@example.org"), "subject", "Hi", "body", "Sunday"), adminOf(TRIAL));
        assertThat(r.getStatusCode().value()).isEqualTo(403);
        assertThat(String.valueOf(r.getBody())).contains(MessagingPolicy.TRIAL_EMAIL_MSG);
        verify(emailService, never()).sendGenericEmail(anyString(), anyString(), anyString());
        verify(emailService, never()).sendOrgEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a trial admin's SMS broadcast is refused the same way")
    void trialSmsRefused() {
        ResponseEntity<?> r = controller.sendSms(
                Map.of("to", List.of("(217) 555-0100"), "body", "Sunday"), adminOf(TRIAL));
        assertThat(r.getStatusCode().value()).isEqualTo(403);
        verify(smsService, never()).sendForClient(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a paying church's broadcast goes out AS the church (tenant-scoped send), never tenant-less")
    void payingChurchSendsWithTenant() {
        ResponseEntity<?> r = controller.sendEmail(
                Map.of("to", List.of("vol@example.org"), "subject", "Hi", "body", "Sunday"), adminOf(PAYING));
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        verify(emailService).sendOrgEmail(eq("vol@example.org"), eq("Hi"), anyString(), eq(PAYING));
        verify(emailService, never()).sendGenericEmail(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("without a policy bean the controller still sends tenant-scoped, so EmailService enforces the block")
    void noPolicyStillTenantScoped() {
        controller.setMessagingPolicy(null);
        // EmailService.delivery is the gate now; with it saying NORMAL the send must still carry the tenant.
        when(emailService.delivery(TRIAL)).thenReturn(new EmailService.Delivery(EmailService.DeliveryMode.NORMAL, null, null));
        controller.sendEmail(Map.of("to", List.of("vol@example.org"), "subject", "Hi", "body", "x"), adminOf(TRIAL));
        verify(emailService).sendOrgEmail(anyString(), anyString(), anyString(), eq(TRIAL));
        verify(emailService, never()).sendGenericEmail(anyString(), anyString(), anyString());
    }
}
