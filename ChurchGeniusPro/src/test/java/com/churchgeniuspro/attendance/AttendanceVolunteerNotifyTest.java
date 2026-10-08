package com.churchgeniuspro.attendance;

import com.churchgeniuspro.controller.AttendanceController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Attendance → Email / SMS Volunteers (Phase 4 of the permission work): the list is
 * tenant-scoped and flags who was present this week, every recipient gets an honest
 * outcome, and the two endpoints honour the "Email or SMS Volunteers" checkbox.
 */
@DisplayName("Attendance → Email / SMS Volunteers")
class AttendanceVolunteerNotifyTest {

    static final String OURS = "CHR-ours";
    static final LocalDate WED = LocalDate.of(2026, 9, 30);          // Monday 28 … Sunday 4 Oct

    VolunteerProfileRepository volunteerRepo;
    FamilyMemberRepository     memberRepo;
    AttendanceRecordRepository recordRepo;
    EmailService               emailService;
    SmsService                 smsService;
    JavaMailSender             mailSender;
    UnsubscribeService         unsub;
    SubscriptionService        subs;
    AttendanceVolunteerNotifyService svc;

    static FamilyMember member(int id, String first, String last, String email, String phone) {
        FamilyMember m = new FamilyMember();
        m.setId(id); m.setFirstName(first); m.setLastName(last); m.setEmail(email); m.setPhone(phone);
        m.setAppClientId(OURS);
        return m;
    }
    static VolunteerProfile profile(int memberId) {
        VolunteerProfile v = new VolunteerProfile(); v.setFamilyMemberId(memberId); v.setAppClientId(OURS); v.setStatus("Active"); return v;
    }
    static AttendanceRecord present(int memberId, LocalDate d, String status) {
        AttendanceRecord a = new AttendanceRecord();
        a.setPersonType("MEMBER"); a.setFamilyMemberId(memberId); a.setAttendanceDate(d); a.setStatus(status); a.setClientId(OURS);
        return a;
    }

    @BeforeEach
    void setUp() {
        volunteerRepo = mock(VolunteerProfileRepository.class);
        memberRepo    = mock(FamilyMemberRepository.class);
        recordRepo    = mock(AttendanceRecordRepository.class);

        // Active members of OUR church only (the repository query is tenant-scoped).
        when(memberRepo.findAllWithFamilyByAppUser(OURS)).thenReturn(List.of(
                member(1, "Ann",  "Able",  "ann@x.org",  "+1 312 555 0101"),
                member(2, "Bob",  "Baker", null,         "+1 312 555 0102"),
                member(3, "Cara", "Cole",  "cara@x.org", null)));
        // Volunteer profiles: 1, 2, 3 and 9 (9 = inactive/deleted member, not in the list above)
        when(volunteerRepo.findByAppClientIdAndDeleteFlagFalseOrderByFamilyMemberIdAsc(OURS))
                .thenReturn(List.of(profile(1), profile(2), profile(3), profile(9)));
        // Ann present Tuesday, Bob absent, Cara late Sunday (counts as present)
        when(recordRepo.findByClientIdAndAttendanceDateBetweenAndDeleteFlagFalse(eq(OURS), any(), any()))
                .thenReturn(List.of(present(1, WED.minusDays(1), "PRESENT"),
                                    present(2, WED, "ABSENT"),
                                    present(3, WED.plusDays(4), "LATE")));

        mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenAnswer(i -> mock(MimeMessage.class));
        unsub = mock(UnsubscribeService.class);
        subs  = mock(SubscriptionService.class);
        when(subs.canSendEmail(anyString())).thenReturn(true);
        emailService = new EmailService(mailSender, mock(EmailSettingsRepository.class), mock(ChurchLogoRepository.class),
                mock(PromiseVerseRepository.class), unsub, mock(ChurchRegistrationRepository.class), subs);
        smsService = mock(SmsService.class);
        when(smsService.isConfigured()).thenReturn(true);
        when(smsService.sendForClient(eq(OURS), anyString(), anyString())).thenReturn(SmsService.SendOutcome.ok());

        svc = new AttendanceVolunteerNotifyService(volunteerRepo, memberRepo, recordRepo, emailService, smsService);
    }

    @Nested @DisplayName("listVolunteers")
    class Listing {
        @Test void onlyActiveMembersWithProfiles_andPresentFlagUsesDashboardRule() {
            List<Map<String, Object>> rows = svc.listVolunteers(OURS, WED);
            assertThat(rows).extracting(r -> r.get("memberId")).containsExactly(1, 2, 3);   // 9 dropped, sorted by name
            assertThat(rows.get(0)).containsEntry("name", "Ann Able").containsEntry("presentThisWeek", true);
            assertThat(rows.get(1)).containsEntry("name", "Bob Baker").containsEntry("presentThisWeek", false).containsEntry("email", null);
            assertThat(rows.get(2)).containsEntry("presentThisWeek", true).containsEntry("phone", null);
        }
        @Test void weekWindowIsMondayToSunday() {
            svc.listVolunteers(OURS, WED);
            verify(recordRepo).findByClientIdAndAttendanceDateBetweenAndDeleteFlagFalse(OURS, LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 4));
        }
    }

    @Nested @DisplayName("notify")
    class Notify {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results(Map<String, Object> out) { return (List<Map<String, Object>>) out.get("results"); }

        @Test void emailAndSms_perRecipientOutcomes() {
            Map<String, Object> out = svc.notify(OURS, List.of(1, 2, 3), true, true, "Thanks", "See you Sunday");
            assertThat(out).containsEntry("recipients", 3).containsEntry("sentEmail", 2).containsEntry("sentSms", 2);
            assertThat(results(out)).extracting(r -> r.get("name") + "/" + r.get("channel") + "/" + r.get("status"))
                    .containsExactly("Ann Able/email/sent", "Ann Able/sms/sent",
                                     "Bob Baker/email/skipped", "Bob Baker/sms/sent",
                                     "Cara Cole/email/sent", "Cara Cole/sms/skipped");
            assertThat(results(out).get(2)).containsEntry("reason", "No email address");
            assertThat(results(out).get(5)).containsEntry("reason", "No phone number");
            verify(mailSender, times(2)).send(any(MimeMessage.class));
            verify(subs, times(2)).recordEmailSent(OURS);
        }
        @Test void unsubscribedAndAllowance_areReportedNotSilent() {
            when(unsub.isUnsubscribed("ann@x.org", OURS)).thenReturn(true);
            Map<String, Object> out = svc.notify(OURS, List.of(1), true, false, "S", "B");
            assertThat(results(out).get(0)).containsEntry("status", "skipped").containsEntry("reason", "Recipient has unsubscribed");
            verify(mailSender, never()).send(any(MimeMessage.class));

            when(unsub.isUnsubscribed(anyString(), anyString())).thenReturn(false);
            when(subs.canSendEmail(OURS)).thenReturn(false);
            out = svc.notify(OURS, List.of(3), true, false, "S", "B");
            assertThat(results(out).get(0)).containsEntry("status", "skipped");
            assertThat(String.valueOf(results(out).get(0).get("reason"))).contains("allowance");
            assertThat(out).containsEntry("sentEmail", 0);
        }
        @Test void trialTenant_isBlockedWithTheStandardReason() {
            ServiceClientRepository clients = mock(ServiceClientRepository.class);
            ServiceClient trial = new ServiceClient(); trial.setClientId(OURS); trial.setSubscriptionType("TRIAL");
            when(clients.findByClientId(OURS)).thenReturn(Optional.of(trial));
            emailService.setMessagingPolicy(new MessagingPolicy(clients));
            Map<String, Object> out = svc.notify(OURS, List.of(1), true, false, "S", "B");
            assertThat(results(out).get(0)).containsEntry("status", "skipped").containsEntry("reason", MessagingPolicy.TRIAL_EMAIL_MSG);
            verify(mailSender, never()).send(any(MimeMessage.class));
        }
        @Test void mailServerFailure_isFailedNotSent() {
            doThrow(new org.springframework.mail.MailSendException("boom")).when(mailSender).send(any(MimeMessage.class));
            Map<String, Object> out = svc.notify(OURS, List.of(1), true, false, "S", "B");
            assertThat(results(out).get(0)).containsEntry("status", "failed");
            assertThat(out).containsEntry("sentEmail", 0);
            verify(subs, never()).recordEmailSent(any());
        }
        @Test void smsNotConfigured_orRefused_isReported() {
            when(smsService.isConfigured()).thenReturn(false);
            Map<String, Object> out = svc.notify(OURS, List.of(1), false, true, null, "B");
            assertThat(results(out).get(0)).containsEntry("status", "skipped").containsEntry("reason", "SMS is not configured");
            verify(smsService, never()).sendForClient(any(), any(), any());

            when(smsService.isConfigured()).thenReturn(true);
            when(smsService.sendForClient(eq(OURS), anyString(), anyString())).thenReturn(SmsService.SendOutcome.fail("Monthly SMS allowance used up"));
            out = svc.notify(OURS, List.of(1), false, true, null, "B");
            assertThat(results(out).get(0)).containsEntry("status", "failed").containsEntry("reason", "Monthly SMS allowance used up");
        }
        @Test void unknownOrForeignIds_areIgnored_andValidationIsStrict() {
            Map<String, Object> out = svc.notify(OURS, List.of(1, 9, 777), true, false, "S", "B");
            assertThat(out).containsEntry("recipients", 1);
            assertThatThrownBy(() -> svc.notify(OURS, List.of(9, 777), true, false, "S", "B")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> svc.notify(OURS, List.of(1), false, false, "S", "B")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> svc.notify(OURS, List.of(1), true, false, "S", "  ")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> svc.notify(OURS, List.of(), true, false, "S", "B")).isInstanceOf(IllegalArgumentException.class);
        }
        @Test void bodyIsHtmlEscaped_andSmsGetsPlainText() {
            svc.notify(OURS, List.of(1), false, true, null, "Hi <b>there</b>");
            verify(smsService).sendForClient(OURS, "+1 312 555 0101", "Hi <b>there</b>");
        }
    }

    @Nested @DisplayName("endpoints")
    class Endpoints {
        AttendanceController controller;
        MockHttpServletRequest req(String role, String privileges) {
            MockHttpSession s = new MockHttpSession();
            s.setAttribute("username", "u"); s.setAttribute("role", role); s.setAttribute("clientId", OURS);
            s.setAttribute("appClientId", OURS); s.setAttribute("appUserId", 1); s.setAttribute("church", false);
            if (privileges != null) s.setAttribute("privileges", privileges);
            MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s); return r;
        }
        @BeforeEach void wire() { controller = new AttendanceController(mock(AttendanceService.class), svc); }

        @Test void checkboxUnchecked_403_bothEndpoints() {
            String off = "{\"general.attendance.emailsms\":false}";
            assertThat(controller.volunteers(req("User", off)).getStatusCode().value()).isEqualTo(403);
            assertThat(controller.notifyVolunteers(Map.of("memberIds", List.of(1), "email", true, "body", "x"), req("User", off)).getStatusCode().value()).isEqualTo(403);
            verify(mailSender, never()).send(any(MimeMessage.class));
        }
        @Test void checkedOrNeverSaved_200() {
            assertThat(controller.volunteers(req("User", "{\"general.attendance.emailsms\":true}")).getStatusCode().value()).isEqualTo(200);
            assertThat(controller.volunteers(req("Admin", null)).getStatusCode().value()).isEqualTo(200);
            ResponseEntity<?> res = controller.notifyVolunteers(
                    Map.of("memberIds", List.of(1, 3), "email", true, "sms", false, "subject", "S", "body", "B"), req("Admin", "{}"));
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            verify(mailSender, times(2)).send(any(MimeMessage.class));
        }
        @Test void roleGateFirst_andBadRequestForEmptySelection() {
            assertThat(controller.volunteers(req("Accountant", null)).getStatusCode().value()).isEqualTo(403);
            assertThat(controller.notifyVolunteers(Map.of("email", true, "body", "x"), req("Admin", null)).getStatusCode().value()).isEqualTo(400);
        }
        @Test void otherAttendanceEndpoints_unchangedByTheNewKey() {
            // .emailsms off must not touch the dashboard (role-only) — permissions gate the new action only.
            assertThat(controller.dashboard(req("User", "{\"general.attendance.emailsms\":false}")).getStatusCode().value()).isEqualTo(200);
        }
    }
}
