package com.churchgeniuspro.trialemail;

import com.churchgeniuspro.hibernate.DemoRoleAccess;
import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import com.churchgeniuspro.util.EmailActionScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Trial/Demo expiry notices go to BOTH the address registered with the trial and the
 * verified test address (one test email per action). A paying church's notice is as
 * before: registered address only.
 */
@DisplayName("Trial/Demo expiry notices — both recipients")
class ExpiryNoticeBothRecipientsTest {

    static final String TRIAL = "TRIAL-1", PAID = "CHR-PAID";

    EmailService email;
    ServiceClientRepository clients;
    ReminderSentLogRepository sentLog;

    @BeforeEach
    void setUp() {
        email = mock(EmailService.class);
        clients = mock(ServiceClientRepository.class);
        sentLog = mock(ReminderSentLogRepository.class);
        ServiceClient trial = new ServiceClient(); trial.setClientId(TRIAL); trial.setChurchName("Trial Church");
        trial.setEmail("registrant@trial.test"); trial.setSubscriptionType("TRIAL"); trial.setEndDate(LocalDate.of(2026, 10, 16));
        ServiceClient paid = new ServiceClient(); paid.setClientId(PAID); paid.setChurchName("Paid Church");
        paid.setEmail("owner@paid.test"); paid.setSubscriptionType("STANDARD"); paid.setEndDate(LocalDate.of(2026, 10, 16));
        when(clients.findByClientId(TRIAL)).thenReturn(Optional.of(trial));
        when(clients.findByClientId(PAID)).thenReturn(Optional.of(paid));
        when(clients.findByEndDateAndStatusAndDeleteFlagFalse(LocalDate.of(2026, 10, 16), "Active")).thenReturn(List.of(trial, paid));
        when(clients.findByEndDateAndStatusAndDeleteFlagFalse(argThat(d -> !LocalDate.of(2026, 10, 16).equals(d)), anyString())).thenReturn(List.of());
        when(email.delivery(TRIAL)).thenReturn(new EmailService.Delivery(EmailService.DeliveryMode.TEST, MessagingPolicy.TRIAL_EMAIL_MSG, "te***@tri***.test"));
        when(email.delivery(PAID)).thenReturn(new EmailService.Delivery(EmailService.DeliveryMode.NORMAL, null, null));
        // The real EmailService redirects a blocked tenant's org mail to the test address once per action.
        doAnswer(i -> { EmailActionScope s = EmailActionScope.current();
                        if (s != null && s.claim(i.getArgument(3))) s.recordTestEmailSent(); return null; })
                .when(email).sendOrgEmail(anyString(), anyString(), anyString(), eq(TRIAL));
    }

    @Test
    @DisplayName("subscription expiry: trial → registered address AND test address; paying → registered only, as before")
    void subscriptionExpiry() {
        SubscriptionExpiryNotifier n = new SubscriptionExpiryNotifier(clients, sentLog, email);
        when(sentLog.save(any())).thenAnswer(i -> i.getArgument(0));

        n.run2(LocalDate.of(2026, 10, 6));

        // Registered addresses, tenant-less platform mail, exactly as before.
        verify(email).sendGenericEmail(eq("registrant@trial.test"), contains("expires in 10 days"), anyString());
        verify(email).sendGenericEmail(eq("owner@paid.test"), contains("expires in 10 days"), anyString());
        // The trial additionally gets its test copy through the redirect; the paying church does not.
        ArgumentCaptor<String> subj = ArgumentCaptor.forClass(String.class);
        verify(email, times(1)).sendOrgEmail(eq("registrant@trial.test"), subj.capture(), anyString(), eq(TRIAL));
        assertThat(subj.getValue()).contains("expires in 10 days");
        verify(email, never()).sendOrgEmail(anyString(), anyString(), anyString(), eq(PAID));
    }

    @Test
    @DisplayName("subscription expiry: a trial with no verified test address gets the registered copy only")
    void subscriptionExpiryNoTestAddress() {
        when(email.delivery(TRIAL)).thenReturn(new EmailService.Delivery(EmailService.DeliveryMode.BLOCKED, MessagingPolicy.TRIAL_EMAIL_MSG, null));
        SubscriptionExpiryNotifier n = new SubscriptionExpiryNotifier(clients, sentLog, email);
        n.run2(LocalDate.of(2026, 10, 6));
        verify(email).sendGenericEmail(eq("registrant@trial.test"), anyString(), anyString());
        verify(email, never()).sendOrgEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("demo expiry countdown: Service Admin, registered address (account mail) AND the test address")
    void demoExpiry() {
        DemoRoleAccessRepository access = mock(DemoRoleAccessRepository.class);
        DemoClientSettingsRepository settings = mock(DemoClientSettingsRepository.class);
        DemoReminderLogRepository logs = mock(DemoReminderLogRepository.class);
        DemoRoleAccess role = new DemoRoleAccess(); role.setId(5L); role.setClientId(TRIAL); role.setUsername("pastor");
        role.setRoleLabel("SuperAdmin"); role.setEndDate(LocalDate.of(2026, 10, 16));
        when(access.findCountdownCandidates(any())).thenReturn(List.of(role));
        when(settings.findById(TRIAL)).thenReturn(Optional.empty());
        when(logs.alreadySent(anyLong(), anyInt(), any())).thenReturn(false);
        when(logs.save(any())).thenAnswer(i -> i.getArgument(0));
        DemoReminderScheduler s = new DemoReminderScheduler(access, settings, logs, email);
        s.setClientRepo(clients);

        assertThat(s.sweep(LocalDate.of(2026, 10, 6))).isEqualTo(1);

        verify(email).sendGenericEmail(any(), contains("Demo trial ends in 10 days"), anyString());          // Service Admin, as before
        verify(email).sendAccountEmail(eq("registrant@trial.test"), contains("Demo trial ends in 10 days"), anyString(), eq(TRIAL));
        verify(email, times(1)).sendOrgEmail(eq("registrant@trial.test"), contains("Demo trial ends in 10 days"), anyString(), eq(TRIAL));
        ArgumentCaptor<com.churchgeniuspro.hibernate.DemoReminderLog> log = ArgumentCaptor.forClass(com.churchgeniuspro.hibernate.DemoReminderLog.class);
        verify(logs).save(log.capture());
        assertThat(log.getValue().getDelivery()).isEqualTo("SERVICE_ADMIN+REGISTERED+TEST_EMAIL");
    }
}
