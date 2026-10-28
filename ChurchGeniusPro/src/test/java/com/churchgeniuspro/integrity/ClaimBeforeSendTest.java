package com.churchgeniuspro.integrity;

import com.churchgeniuspro.hibernate.DemoClientSettings;
import com.churchgeniuspro.hibernate.DemoReminderLog;
import com.churchgeniuspro.hibernate.DemoRoleAccess;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.PrayerRequest;
import com.churchgeniuspro.hibernate.PrayerSchedule;
import com.churchgeniuspro.hibernate.PushSubscription;
import com.churchgeniuspro.hibernate.ReminderSentLog;
import com.churchgeniuspro.repository.AutoReminderRepository;
import com.churchgeniuspro.repository.DemoClientSettingsRepository;
import com.churchgeniuspro.repository.DemoReminderLogRepository;
import com.churchgeniuspro.repository.DemoRoleAccessRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.PrayerRequestRepository;
import com.churchgeniuspro.repository.PrayerScheduleRepository;
import com.churchgeniuspro.repository.PrayerSectionRepository;
import com.churchgeniuspro.repository.PushNotificationLogRepository;
import com.churchgeniuspro.repository.PushSubscriptionRepository;
import com.churchgeniuspro.repository.ReminderSentLogRepository;
import com.churchgeniuspro.service.DemoReminderScheduler;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.NativePushService;
import com.churchgeniuspro.service.ReminderSchedulerService;
import com.churchgeniuspro.service.WebPushService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Data-integrity audit 2026-10-28, H4/H5: jobs that stamped "sent" AFTER sending, or that
 * deduplicated only their log row, sent twice (two instances / overlapping runs / every poll).
 * Each now claims its slot before the send and stays silent when the claim is lost.
 */
@DisplayName("Schedulers claim before they send")
class ClaimBeforeSendTest {

    static final String CLIENT = "CHR-claim-1";

    // ── Prayer digest ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("prayer digest (ReminderSchedulerService)")
    class PrayerDigest {

        ReminderSentLogRepository sentLog = mock(ReminderSentLogRepository.class);
        AutoReminderRepository autoReminders = mock(AutoReminderRepository.class);
        PrayerScheduleRepository schedules = mock(PrayerScheduleRepository.class);
        PrayerRequestRepository requests = mock(PrayerRequestRepository.class);
        PrayerSectionRepository sections = mock(PrayerSectionRepository.class);
        FamilyMemberRepository members = mock(FamilyMemberRepository.class);
        EmailService email = mock(EmailService.class);

        ReminderSchedulerService build() throws Exception {
            when(autoReminders.findActiveClientIds()).thenReturn(List.of(CLIENT));
            when(autoReminders.findByReminderTypeIdAndDisabledFalse(anyInt())).thenReturn(List.of());
            PrayerSchedule s = new PrayerSchedule();
            s.setClientId(CLIENT); s.setOccurrence("Daily"); s.setSendHour(null); s.setLastSentDate(null);
            when(schedules.findAll()).thenReturn(List.of(s));
            PrayerRequest r = new PrayerRequest();
            r.setClientId(CLIENT); r.setStatus("Active"); r.setTitle("Healing for Ada"); r.setSectionId(1L);
            r.setDescription("knee surgery"); r.setRequesterName("Ada");
            when(requests.findByClientIdAndDeleteFlagFalseOrderByCreatedAtAsc(CLIENT)).thenReturn(List.of(r));
            when(sections.findByClientIdAndDeleteFlagFalseOrderByCreatedAtAsc(CLIENT)).thenReturn(List.of());
            FamilyMember m = new FamilyMember(); m.setEmail("member@example.org"); m.setFirstName("Ada");
            when(members.findByMemberTypeWithEmailByAppUser("Member", CLIENT)).thenReturn(List.of(m));
            when(email.getChurchName(CLIENT)).thenReturn("Test Church");

            Constructor<?> ctor = ReminderSchedulerService.class.getDeclaredConstructors()[0];
            Parameter[] params = ctor.getParameters();
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                Class<?> t = params[i].getType();
                if (t == ReminderSentLogRepository.class)       args[i] = sentLog;
                else if (t == AutoReminderRepository.class)     args[i] = autoReminders;
                else if (t == PrayerScheduleRepository.class)   args[i] = schedules;
                else if (t == PrayerRequestRepository.class)    args[i] = requests;
                else if (t == PrayerSectionRepository.class)    args[i] = sections;
                else if (t == FamilyMemberRepository.class)     args[i] = members;
                else if (t == EmailService.class)               args[i] = email;
                else args[i] = mock(t);
            }
            ctor.setAccessible(true);
            ReminderSchedulerService svc = (ReminderSchedulerService) ctor.newInstance(args);
            ReflectionTestUtils.setField(svc, "baseUrl", "https://example.org");
            return svc;
        }

        void runDigest(ReminderSchedulerService svc) {
            ReflectionTestUtils.invokeMethod(svc, "runPrayerRequestReminders", LocalDate.now());
        }

        @Test
        @DisplayName("the claim (reminder_sent_log PRAYER_DIGEST) is written BEFORE any e-mail goes out, then the schedule is stamped")
        void claimsThenSends() throws Exception {
            when(sentLog.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
            ReminderSchedulerService svc = build();

            runDigest(svc);

            ArgumentCaptor<ReminderSentLog> claim = ArgumentCaptor.forClass(ReminderSentLog.class);
            org.mockito.InOrder order = org.mockito.Mockito.inOrder(sentLog, email);
            order.verify(sentLog).saveAndFlush(claim.capture());
            order.verify(email).sendOrgEmail(eq("member@example.org"), anyString(), anyString(), eq(CLIENT));
            assertThat(claim.getValue().getReminderType()).isEqualTo("PRAYER_DIGEST");
            assertThat(claim.getValue().getAppClientId()).isEqualTo(CLIENT);
            verify(schedules).save(any(PrayerSchedule.class));
        }

        @Test
        @DisplayName("a lost claim (unique-key violation: another instance already sent today) sends nothing and stamps nothing")
        void lostClaimIsSilent() throws Exception {
            when(sentLog.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_reminder_sent"));
            ReminderSchedulerService svc = build();

            runDigest(svc);

            verify(email, never()).sendOrgEmail(anyString(), anyString(), anyString(), anyString());
            verify(schedules, never()).save(any());
        }
    }

    // ── Demo expiry reminders ───────────────────────────────────────────────

    @Nested
    @DisplayName("demo expiry reminders (DemoReminderScheduler)")
    class DemoExpiry {

        DemoRoleAccessRepository access = mock(DemoRoleAccessRepository.class);
        DemoClientSettingsRepository settings = mock(DemoClientSettingsRepository.class);
        DemoReminderLogRepository logs = mock(DemoReminderLogRepository.class);
        EmailService email = mock(EmailService.class);

        DemoReminderScheduler build() {
            DemoRoleAccess role = new DemoRoleAccess();
            role.setId(5L); role.setClientId("DEMO-1"); role.setUsername("pastor"); role.setRoleLabel("SuperAdmin");
            role.setEndDate(LocalDate.of(2026, 10, 16));
            when(access.findCountdownCandidates(any())).thenReturn(List.of(role));
            when(settings.findById("DEMO-1")).thenReturn(Optional.<DemoClientSettings>empty());
            when(logs.alreadySent(anyLong(), anyInt(), any())).thenReturn(false);
            return new DemoReminderScheduler(access, settings, logs, email);
        }

        @Test
        @DisplayName("the log row is claimed (CLAIMED) before the e-mail and updated with the delivery afterwards")
        void claimsThenSends() {
            java.util.List<String> deliveryAtClaim = new java.util.ArrayList<>();
            when(logs.saveAndFlush(any())).thenAnswer(i -> {
                deliveryAtClaim.add(((DemoReminderLog) i.getArgument(0)).getDelivery());   // snapshot: the row is mutated later
                return i.getArgument(0);
            });
            when(logs.save(any())).thenAnswer(i -> i.getArgument(0));
            DemoReminderScheduler s = build();

            assertThat(s.sweep(LocalDate.of(2026, 10, 6))).isEqualTo(1);

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(logs, email);
            order.verify(logs).saveAndFlush(any());
            order.verify(email).sendGenericEmail(any(), anyString(), anyString());
            assertThat(deliveryAtClaim).containsExactly("CLAIMED");
            ArgumentCaptor<DemoReminderLog> done = ArgumentCaptor.forClass(DemoReminderLog.class);
            verify(logs).save(done.capture());
            assertThat(done.getValue().getDelivery()).startsWith("SERVICE_ADMIN");
        }

        @Test
        @DisplayName("a lost claim sends nothing")
        void lostClaimIsSilent() {
            when(logs.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_demo_reminder_once"));
            DemoReminderScheduler s = build();

            assertThat(s.sweep(LocalDate.of(2026, 10, 6))).isZero();

            verify(email, never()).sendGenericEmail(any(), anyString(), anyString());
            verify(logs, never()).save(any());
        }
    }

    // ── Unread-message push ─────────────────────────────────────────────────

    @Nested
    @DisplayName("unread-message push (WebPushService.logAndSendToUserOnce)")
    class UnreadPush {

        PushSubscriptionRepository subs = mock(PushSubscriptionRepository.class);
        PushNotificationLogRepository logs = mock(PushNotificationLogRepository.class);
        NativePushService fcm = mock(NativePushService.class);

        WebPushService build() {
            WebPushService svc = new WebPushService(subs, logs, fcm);
            svc.init();
            when(fcm.isEnabled()).thenReturn(true);
            PushSubscription s = new PushSubscription();
            s.setId(1L); s.setPlatform("android"); s.setEndpoint("fcm-token"); s.setP256dh("-"); s.setAuth("-");
            s.setUserKey("MBR-1"); s.setUserType("member"); s.setAppClientId(CLIENT);
            when(subs.findByUserKeyAndActiveTrue("MBR-1")).thenReturn(List.of(s));
            when(fcm.send(any(), any(), any(), any(), any())).thenReturn(NativePushService.Result.SENT);
            return svc;
        }

        @Test
        @DisplayName("first call logs and pushes; while the tag is in the 23-hour log window nothing is pushed again")
        void onceWithinWindow() {
            WebPushService svc = build();
            when(logs.countRecentByTag(eq("MBR-1"), eq("cgp-unread-messages"), any())).thenReturn(0L, 1L, 1L);

            assertThat(svc.logAndSendToUserOnce("MBR-1", CLIENT, "member", "📩", "1 unread", "/memberHome", "cgp-unread-messages")).isTrue();
            assertThat(svc.logAndSendToUserOnce("MBR-1", CLIENT, "member", "📩", "1 unread", "/memberHome", "cgp-unread-messages")).isFalse();
            assertThat(svc.logAndSendToUserOnce("MBR-1", CLIENT, "member", "📩", "1 unread", "/memberHome", "cgp-unread-messages")).isFalse();

            verify(fcm, times(1)).send(any(), any(), any(), any(), any());
            verify(logs, times(1)).save(any());
        }

        @Test
        @DisplayName("the plain logAndSendToUser (interactive callers) is unchanged: it always pushes")
        void plainVariantUnchanged() {
            WebPushService svc = build();
            when(logs.countRecentByTag(anyString(), anyString(), any())).thenReturn(1L);

            svc.logAndSendToUser("MBR-1", CLIENT, "member", "t", "b", "/memberHome", "cgp-vol-respond");

            verify(fcm, times(1)).send(any(), any(), any(), any(), any());
        }
    }
}
