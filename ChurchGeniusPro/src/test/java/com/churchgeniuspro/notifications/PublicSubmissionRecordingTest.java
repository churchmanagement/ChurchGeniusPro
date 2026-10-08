package com.churchgeniuspro.notifications;

import com.churchgeniuspro.hibernate.ConnectSubmission;
import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.PublicPrayerRequest;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.PublicEngagementService;
import com.churchgeniuspro.service.PublicLinkResolver;
import com.churchgeniuspro.service.PublicSubmissionNotificationService;
import com.churchgeniuspro.service.PublicSubmissionNotificationService.Type;
import com.churchgeniuspro.service.WhatsAppSenderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Prayer Request and Connect With Us forms record a notification for the right church. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Public forms record submission notifications")
class PublicSubmissionRecordingTest {

    @Mock FamilyMemberRepository memberRepo;
    @Mock FamilyRepository familyRepo;
    @Mock FollowUpRepository followUpRepo;
    @Mock PublicPrayerRequestRepository prayerRepo;
    @Mock PublicPrayerNoteRepository noteRepo;
    @Mock PrayerVolunteerRepository volunteerRepo;
    @Mock ChurchRegistrationRepository churchRepo;
    @Mock ChurchLogoRepository logoRepo;
    @Mock ConnectSubmissionRepository connectRepo;
    @Mock EmailService emailService;
    @Mock WhatsAppSenderService smsSender;
    @Mock PublicLinkResolver linkResolver;
    @Mock PublicSubmissionNotificationService notifications;

    PublicEngagementService svc;

    @BeforeEach
    void setUp() {
        svc = new PublicEngagementService(memberRepo, familyRepo, followUpRepo, prayerRepo, noteRepo,
                volunteerRepo, churchRepo, logoRepo, connectRepo, emailService, smsSender, linkResolver);
        svc.setSubmissionNotifications(notifications);
        when(linkResolver.resolveClientId("tok-prayer", "/publicPrayer")).thenReturn("CHR-ours");
        when(linkResolver.resolveClientId("tok-connect", "/connect")).thenReturn("CHR-ours");
        when(prayerRepo.save(any(PublicPrayerRequest.class))).thenAnswer(i -> {
            PublicPrayerRequest p = i.getArgument(0); if (p.getId() == null) p.setId(41L); return p; });
        when(connectRepo.save(any(ConnectSubmission.class))).thenAnswer(i -> {
            ConnectSubmission c = i.getArgument(0); if (c.getId() == null) c.setId(42L); return c; });
        when(familyRepo.save(any(Family.class))).thenAnswer(i -> i.getArgument(0));
        when(memberRepo.save(any(FamilyMember.class))).thenAnswer(i -> i.getArgument(0));
        when(churchRepo.findByClientIdAndDeleteFlagFalse(anyString())).thenReturn(java.util.Optional.empty());
    }

    @Test
    @DisplayName("Prayer Request → PRAYER notification for the link's church")
    void prayer() {
        Map<String, Object> b = new HashMap<>(Map.of("firstName", "Ada", "lastName", "Lovelace",
                "email", "ada@example.org", "requestText", "Please pray"));
        svc.prayer("tok-prayer", b, "WEBSITE");
        verify(notifications).record(eq("CHR-ours"), eq(Type.PRAYER), anyString(), org.mockito.ArgumentMatchers.contains("Ada Lovelace"), eq(41L));
    }

    @Test
    @DisplayName("Connect With Us → CONNECT notification for the link's church")
    void connect() {
        Map<String, Object> b = new HashMap<>(Map.of("firstName", "Grace", "lastName", "Hopper", "email", "g@example.org"));
        svc.connect("tok-connect", b);
        verify(notifications).record(eq("CHR-ours"), eq(Type.CONNECT), anyString(), org.mockito.ArgumentMatchers.contains("Grace Hopper"), eq(42L));
    }
}
