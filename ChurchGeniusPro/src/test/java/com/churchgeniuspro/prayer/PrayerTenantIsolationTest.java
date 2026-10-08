package com.churchgeniuspro.prayer;

import com.churchgeniuspro.controller.PrayerRequestController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.PublicLinkResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Prayer: volunteer / assignee member ids used to be loaded by bare id, so a
 * request could be pointed at another church's member; the public-* endpoints
 * accepted any ciphertext that decrypted, ignoring revoked links. Both are pinned
 * here, plus the page guard now mirrored on the session-backed API.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PrayerTenantIsolationTest {

    private static final String OURS   = "CHURCH-A";

    @Mock PrayerSectionRepository          sectionRepo;
    @Mock PrayerRequestRepository          requestRepo;
    @Mock PrayerScheduleRepository         scheduleRepo;
    @Mock FamilyMemberRepository           memberRepo;
    @Mock JavaMailSender                   mailSender;
    @Mock EmailService                     emailService;
    @Mock ChurchRegistrationRepository     churchRepo;
    @Mock ChurchLogoRepository             logoRepo;
    @Mock PromiseVerseRepository           verseRepo;
    @Mock PrayerVolunteerRepository        volunteerRepo;
    @Mock PrayerNoteRepository             noteRepo;
    @Mock PrayerRequestVolunteerRepository reqVolRepo;
    @Mock PublicLinkResolver               linkResolver;

    PrayerRequestController controller;

    @BeforeEach
    void setUp() {
        controller = new PrayerRequestController(sectionRepo, requestRepo, scheduleRepo, memberRepo, mailSender,
                emailService, churchRepo, logoRepo, verseRepo, volunteerRepo, noteRepo, reqVolRepo, linkResolver);
        when(memberRepo.findByIdAndTenant(any(), anyString())).thenReturn(Optional.empty());
        when(requestRepo.save(any(PrayerRequest.class))).thenAnswer(i -> i.getArgument(0));
        when(volunteerRepo.save(any(PrayerVolunteer.class))).thenAnswer(i -> i.getArgument(0));
    }

    private MockHttpServletRequest staff(String clientId, String role) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("appClientId", clientId);
        s.setAttribute("username", "user@" + clientId);
        s.setAttribute("role", role);
        req.setSession(s);
        return req;
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private PrayerRequest ourRequest(long id) {
        PrayerRequest r = new PrayerRequest(); r.setId(id); r.setClientId(OURS); r.setStatus("New");
        r.setSectionId(1L); r.setTitle("Healing");
        return r;
    }

    private FamilyMember ourMember(int id) {
        FamilyMember m = new FamilyMember(); m.setId(id); m.setAppClientId(OURS); m.setFirstName("Ann"); m.setLastName("B");
        return m;
    }

    // ── volunteers ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /volunteers cannot link another church's member")
    void addVolunteerIsTenantScoped() {
        ResponseEntity<?> res = controller.addVolunteer(body("familyMemberId", 77), staff(OURS, "Admin"));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        verify(memberRepo, never()).findById(any());
        verify(volunteerRepo, never()).save(any(PrayerVolunteer.class));
    }

    @Test
    @DisplayName("POST /volunteers links this church's member")
    void addVolunteerAllowsOwnTenant() {
        when(memberRepo.findByIdAndTenant(5, OURS)).thenReturn(Optional.of(ourMember(5)));
        when(volunteerRepo.findByMember(OURS, 5)).thenReturn(Optional.empty());

        ResponseEntity<?> res = controller.addVolunteer(body("familyMemberId", 5), staff(OURS, "Admin"));

        assertThat(res.getStatusCode().is2xxSuccessful()).as("%s", res.getBody()).isTrue();
        verify(volunteerRepo).save(any(PrayerVolunteer.class));
    }

    // ── assignee on requests ───────────────────────────────────────────────

    @Test
    @DisplayName("Assignee ids on create / update / assign must belong to this church")
    void assigneeIsTenantScoped() {
        PrayerSection sec = new PrayerSection(); sec.setId(1L); sec.setClientId(OURS);
        when(sectionRepo.findById(1L)).thenReturn(Optional.of(sec));
        when(requestRepo.findById(9L)).thenReturn(Optional.of(ourRequest(9L)));

        ResponseEntity<?> res = controller.createRequest(
                body("title", "T", "sectionId", 1, "assignedVolunteerId", 77), staff(OURS, "Admin"));
        assertThat(res.getStatusCode().value()).isEqualTo(400);

        res = controller.updateRequest(9L, body("title", "T", "assignedVolunteerId", 77), staff(OURS, "Admin"));
        assertThat(res.getStatusCode().value()).isEqualTo(400);

        res = controller.assignRequest(9L, body("assignedVolunteerId", 77), staff(OURS, "Admin"));
        assertThat(res.getStatusCode().value()).isEqualTo(400);

        verify(requestRepo, never()).save(any(PrayerRequest.class));

        // and the name resolver never reaches another church's row either
        when(memberRepo.findByIdAndTenant(5, OURS)).thenReturn(Optional.of(ourMember(5)));
        res = controller.assignRequest(9L, body("assignedVolunteerId", 5), staff(OURS, "Admin"));
        assertThat(res.getStatusCode().is2xxSuccessful()).as("%s", res.getBody()).isTrue();
        verify(requestRepo).save(any(PrayerRequest.class));
        verify(memberRepo, never()).findById(any());
    }

    // ── public endpoints go through the link resolver ──────────────────────

    @Test
    @DisplayName("public-* endpoints refuse a revoked / unpublished link")
    void publicEndpointsHonourRevocation() {
        when(linkResolver.resolveClientId(anyString(), anyString())).thenReturn(null);

        assertThat(controller.publicChurchInfo("tok").getStatusCode().value()).isEqualTo(400);
        assertThat(controller.publicVerse("tok").getStatusCode().value()).isEqualTo(400);
        assertThat(controller.listPublicRequests("tok").getStatusCode().value()).isEqualTo(400);
        assertThat(controller.publicLogo("tok").getStatusCode().value()).isEqualTo(404);
        assertThat(controller.publicSubmit("tok", body("title", "Please")).getStatusCode().value()).isEqualTo(400);

        verify(linkResolver, atLeastOnce()).resolveClientId(eq("tok"), eq("/publicPrayer"));
        verify(requestRepo, never()).save(any(PrayerRequest.class));
        verify(requestRepo, never()).findByClientIdAndDeleteFlagFalseOrderByCreatedAtAsc(anyString());
    }

    @Test
    @DisplayName("public-* endpoints work for a live link")
    void publicEndpointsAllowLiveLink() {
        when(linkResolver.resolveClientId("tok", "/publicPrayer")).thenReturn(OURS);
        when(churchRepo.findByClientIdAndDeleteFlagFalse(OURS)).thenReturn(Optional.empty());

        ResponseEntity<?> res = controller.publicChurchInfo("tok");
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();

        res = controller.listPublicRequests("tok");
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        verify(requestRepo).findByClientIdAndDeleteFlagFalseOrderByCreatedAtAsc(OURS);
    }

    // ── page guard on the session API ──────────────────────────────────────

    @Test
    @DisplayName("Session API mirrors the /prayerRequest page guard")
    void apiMirrorsPageGuard() {
        assertThat(controller.listRequests(staff(OURS, "Accountant")).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.listRequests(new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.listRequests(staff(OURS, "User")).getStatusCode().is2xxSuccessful()).isTrue();
    }
}
