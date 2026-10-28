package com.churchgeniuspro.integrity;

import com.churchgeniuspro.hibernate.KmCheckin;
import com.churchgeniuspro.hibernate.KmChild;
import com.churchgeniuspro.hibernate.KmChildSetup;
import com.churchgeniuspro.repository.KmCheckinRepository;
import com.churchgeniuspro.repository.KmChildRepository;
import com.churchgeniuspro.repository.KmChildSetupRepository;
import com.churchgeniuspro.repository.KmClassroomRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.KmPickupAlertService;
import com.churchgeniuspro.service.SmsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Data-integrity audit 2026-10-28, H1/H5: the overdue-pickup job used to mint the pickup
 * token on a second instance of the check-in and then {@code save()} the detached outer
 * instance — a merge that rewrote every column and set {@code pickup_token} back to NULL,
 * so every link in an alert was dead on arrival. It also stamped {@code last_alert_sent_at}
 * after sending, so two instances both alerted. Now: one atomic claim BEFORE the send,
 * one atomic token mint, never a merge of the loaded row.
 */
@DisplayName("KmPickupAlertService — claim before send, token never overwritten")
class PickupAlertClaimTest {

    static final String CLIENT = "CHR-km-1";

    KmChildSetupRepository setupRepo = mock(KmChildSetupRepository.class);
    KmCheckinRepository    checkinRepo = mock(KmCheckinRepository.class);
    KmChildRepository      childRepo = mock(KmChildRepository.class);
    KmClassroomRepository  classroomRepo = mock(KmClassroomRepository.class);
    EmailService           email = mock(EmailService.class);
    SmsService             sms = mock(SmsService.class);
    KmPickupAlertService   svc;
    KmCheckin              ci;

    @BeforeEach
    void setUp() {
        svc = new KmPickupAlertService(setupRepo, checkinRepo, childRepo, classroomRepo, email, sms);
        ReflectionTestUtils.setField(svc, "baseUrl", "https://example.org");
        ReflectionTestUtils.setField(svc, "reAlertMinutes", 15);

        KmChildSetup setup = new KmChildSetup();
        setup.setClientId(CLIENT);
        setup.setEmailAlertsEnabled(true);
        setup.setAlertRecipients("[{\"name\":\"Desk\",\"email\":\"desk@example.org\"}]");
        when(setupRepo.findAll()).thenReturn(List.of(setup));

        KmChild child = new KmChild();
        child.setId(7L); child.setClientId(CLIENT); child.setFirstName("Ada"); child.setLastName("Lovelace");
        when(childRepo.findById(7L)).thenReturn(Optional.of(child));

        ci = new KmCheckin();
        ci.setId(99L); ci.setClientId(CLIENT); ci.setChildId(7L);
        ci.setCheckinTime(LocalDateTime.now().minusHours(3));
        ci.setPickupDeadline(LocalDateTime.now().minusMinutes(40));      // overdue
        when(checkinRepo.findByClientIdAndCheckoutTimeIsNullOrderByCheckinTimeDesc(CLIENT)).thenReturn(List.of(ci));
        when(checkinRepo.findByIdAndClientId(99L, CLIENT)).thenReturn(Optional.of(ci));
        when(checkinRepo.setPickupTokenIfAbsent(eq(99L), eq(CLIENT), anyString())).thenReturn(1);
    }

    @Test
    @DisplayName("winner of the claim sends the alert with a minted token and never save()s the check-in")
    void claimWinnerSendsAndKeepsToken() {
        when(checkinRepo.claimOverdueAlert(eq(99L), eq(CLIENT), any(), any())).thenReturn(1);

        svc.sendOverdueAlerts();

        verify(email).sendOrgEmail(eq("desk@example.org"), anyString(), org.mockito.ArgumentMatchers.contains("/kidsPickup?t="), eq(CLIENT));
        verify(checkinRepo).setPickupTokenIfAbsent(eq(99L), eq(CLIENT), anyString());
        verify(checkinRepo, never()).save(any());                        // the merge that nulled the token
        assertThat(ci.getPickupToken()).isNotBlank();
        assertThat(ci.getLastAlertSentAt()).isNotNull();
    }

    @Test
    @DisplayName("when the claim returns 0 (other instance / already alerted / checked out) nothing is sent and nothing is written")
    void claimLoserIsSilent() {
        when(checkinRepo.claimOverdueAlert(anyLong(), anyString(), any(), any())).thenReturn(0);

        svc.sendOverdueAlerts();

        verify(email, never()).sendOrgEmail(anyString(), anyString(), anyString(), anyString());
        verify(checkinRepo, never()).setPickupTokenIfAbsent(anyLong(), anyString(), anyString());
        verify(checkinRepo, never()).save(any());
    }

    @Test
    @DisplayName("an existing token is reused: no mint, same link")
    void existingTokenReused() {
        ci.setPickupToken("tok-existing");
        when(checkinRepo.claimOverdueAlert(eq(99L), eq(CLIENT), any(), any())).thenReturn(1);

        svc.sendOverdueAlerts();

        verify(checkinRepo, never()).setPickupTokenIfAbsent(anyLong(), anyString(), anyString());
        verify(email).sendOrgEmail(eq("desk@example.org"), anyString(), org.mockito.ArgumentMatchers.contains("t=tok-existing"), eq(CLIENT));
        assertThat(ci.getPickupToken()).isEqualTo("tok-existing");
    }

    @Test
    @DisplayName("losing the token mint re-reads the winner's token instead of overwriting it")
    void loserOfMintReadsWinner() {
        when(checkinRepo.claimOverdueAlert(eq(99L), eq(CLIENT), any(), any())).thenReturn(1);
        when(checkinRepo.setPickupTokenIfAbsent(eq(99L), eq(CLIENT), anyString())).thenReturn(0);
        KmCheckin fresh = new KmCheckin();
        fresh.setId(99L); fresh.setClientId(CLIENT); fresh.setPickupToken("tok-winner");
        when(checkinRepo.findByIdAndClientId(99L, CLIENT)).thenReturn(Optional.of(ci), Optional.of(fresh));

        assertThat(svc.pickupUrl(CLIENT, 99L)).endsWith("/kidsPickup?t=tok-winner");
        verify(checkinRepo, never()).save(any());
    }
}
