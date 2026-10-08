package com.churchgeniuspro.meetings;

import com.churchgeniuspro.controller.MeetingController;
import com.churchgeniuspro.controller.MeetingTemplateController;
import com.churchgeniuspro.controller.MeetingTypeController;
import com.churchgeniuspro.hibernate.Meeting;
import com.churchgeniuspro.hibernate.MeetingMessageTemplate;
import com.churchgeniuspro.hibernate.MeetingSkipDate;
import com.churchgeniuspro.hibernate.MeetingType;
import com.churchgeniuspro.model.MeetingBO;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import com.churchgeniuspro.repository.MeetingMessageTemplateRepository;
import com.churchgeniuspro.repository.MeetingRepository;
import com.churchgeniuspro.repository.MeetingSkipDateRepository;
import com.churchgeniuspro.repository.MeetingTypeRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.MeetingMessageTemplateService;
import com.churchgeniuspro.service.MeetingOccurrenceService;
import com.churchgeniuspro.service.MeetingService;
import com.churchgeniuspro.service.MeetingTypeService;
import com.churchgeniuspro.service.WhatsAppSenderService;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Meetings, meeting types and meeting message templates are tenant-bound.
 *
 * <p>Before: every {@code /api/meetings/{id}} handler loaded by bare id, the body's
 * {@code meetingTypeId} / location ids were stored unchecked, and the image endpoint
 * needed no session at all. Uses the real services over mocked repositories so the
 * scoped finder is exercised where it lives.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Meetings — tenant isolation")
class MeetingTenantIsolationTest {

    private static final String OURS   = "CHR-ours";
    private static final String THEIRS = "CHR-theirs";

    @Mock MeetingRepository                meetingRepo;
    @Mock MeetingTypeRepository            typeRepo;
    @Mock FamilyMemberRepository           familyMemberRepo;
    @Mock FamilyRepository                 familyRepo;
    @Mock EmailService                     emailService;
    @Mock WhatsAppSenderService            whatsApp;
    @Mock MeetingSkipDateRepository        skipRepo;
    @Mock MeetingMessageTemplateRepository tplRepo;

    MeetingController         controller;
    MeetingTypeController     typeController;
    MeetingTemplateController templateController;

    @BeforeEach
    void setUp() {
        MeetingService service = new MeetingService(meetingRepo, typeRepo, familyMemberRepo, familyRepo,
                emailService, whatsApp);
        controller         = new MeetingController(service, new MeetingOccurrenceService(meetingRepo, skipRepo));
        typeController     = new MeetingTypeController(new MeetingTypeService(typeRepo));
        templateController = new MeetingTemplateController(new MeetingMessageTemplateService(tplRepo));
        when(meetingRepo.save(any(Meeting.class))).thenAnswer(i -> i.getArgument(0));
        when(typeRepo.save(any(MeetingType.class))).thenAnswer(i -> i.getArgument(0));
        when(tplRepo.save(any(MeetingMessageTemplate.class))).thenAnswer(i -> i.getArgument(0));
        // The only finders the handlers may use: scoped to OUR tenant they see nothing
        // for id 9 (owned by THEIRS) and our own meeting for id 7.
        when(meetingRepo.findByIdAndAppClientIdAndDeleteFlagFalse(eq(9), anyString())).thenReturn(Optional.empty());
        when(meetingRepo.findByIdAndAppClientIdAndDeleteFlagFalse(7, OURS)).thenReturn(Optional.of(meeting(7, OURS)));
        when(meetingRepo.findById(9)).thenReturn(Optional.of(meeting(9, THEIRS)));
        when(meetingRepo.findById(7)).thenReturn(Optional.of(meeting(7, OURS)));
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private MockHttpServletRequest staffOf(String clientId, String role) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("clientId", clientId);
        s.setAttribute("appClientId", clientId);
        s.setAttribute("username", "staff@" + clientId);
        s.setAttribute("role", role);
        req.setSession(s);
        return req;
    }

    private MockHttpServletRequest churchOwnerOf(String clientId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("church", true);
        s.setAttribute("clientId", clientId);
        s.setAttribute("username", "owner@" + clientId);
        req.setSession(s);
        return req;
    }

    private static Meeting meeting(int id, String clientId) {
        Meeting m = new Meeting();
        m.setId(id);
        m.setAppClientId(clientId);
        m.setMeetingDate(LocalDate.of(2026, 7, 7));
        m.setOccurrence("Weekly");
        m.setWeekDays("2");
        m.setDeleteFlag(false);
        m.setImageData(new byte[]{1, 2, 3});
        m.setImageContentType("image/png");
        return m;
    }

    private static MeetingType type(int id, String clientId) {
        MeetingType t = new MeetingType();
        t.setId(id);
        t.setTypeName("Bible Study");
        t.setAppClientId(clientId);
        return t;
    }

    private static MeetingMessageTemplate template(int id, String clientId) {
        MeetingMessageTemplate t = new MeetingMessageTemplate();
        t.setId(id);
        t.setName("Weekly");
        t.setBody("Hello");
        t.setAppClientId(clientId);
        return t;
    }

    private static MeetingBO bo(Integer typeId) {
        MeetingBO bo = new MeetingBO();
        bo.setMeetingTypeId(typeId);
        bo.setMeetingDate("2026-10-01");
        bo.setOccurrence("One-time");
        return bo;
    }

    // ── /api/meetings/{id} ─────────────────────────────────────────────────

    @Test
    @DisplayName("Reading, editing, deleting and imaging another church's meeting is 'not found'")
    void singleMeetingHandlersAreTenantScoped() {
        MockHttpServletRequest req = staffOf(OURS, "Admin");

        assertThat(controller.getById(9, req).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.update(9, bo(null), req).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.delete(9, req).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.deleteImage(9, req).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.getImage(9, req).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.notify(9, Map.of("channels", List.of("Email"), "recipients", List.of("Members")), req)
                .getStatusCode().value()).isEqualTo(400);

        verify(meetingRepo, never()).save(any(Meeting.class));
        verify(meetingRepo, never()).findActiveById(anyInt());
        verify(emailService, never()).sendOrgEmail(anyString(), anyString(), anyString(), anyString());
        // and the lookup was always made for OUR tenant
        verify(meetingRepo, never()).findByIdAndAppClientIdAndDeleteFlagFalse(9, THEIRS);
    }

    @Test
    @DisplayName("Image bytes are not served without a session")
    void imageNeedsSession() {
        ResponseEntity<byte[]> res = controller.getImage(7, new MockHttpServletRequest());
        assertThat(res.getStatusCode().value()).isEqualTo(401);
        verify(meetingRepo, never()).findById(anyInt());
        verify(meetingRepo, never()).findByIdAndAppClientIdAndDeleteFlagFalse(anyInt(), any());
    }

    @Test
    @DisplayName("Our own meeting can still be read, edited and its image served")
    void ownMeetingStillWorks() {
        MockHttpServletRequest req = staffOf(OURS, "User");   // no stored privileges → allowed
        when(typeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(5, OURS)).thenReturn(Optional.of(type(5, OURS)));

        assertThat(controller.getById(7, req).getStatusCode().is2xxSuccessful()).isTrue();
        ResponseEntity<Map<String, Object>> upd = controller.update(7, bo(5), req);
        assertThat(upd.getStatusCode().is2xxSuccessful()).as("%s", upd.getBody()).isTrue();
        assertThat(controller.getImage(7, req).getStatusCode().is2xxSuccessful()).isTrue();
        verify(meetingRepo).save(any(Meeting.class));
    }

    @Test
    @DisplayName("A meetingTypeId from another church is rejected before anything is stored")
    void foreignMeetingTypeIsRejected() {
        MockHttpServletRequest req = staffOf(OURS, "Admin");
        when(typeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(55, OURS)).thenReturn(Optional.empty());
        when(typeRepo.findById(55)).thenReturn(Optional.of(type(55, THEIRS)));

        assertThat(controller.create(bo(55), req).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.update(7, bo(55), req).getStatusCode().value()).isEqualTo(400);
        verify(meetingRepo, never()).save(any(Meeting.class));
        verify(typeRepo, never()).findById(anyInt());
    }

    @Test
    @DisplayName("Location member / family ids must belong to this church")
    void foreignLocationIdsAreRejected() {
        MockHttpServletRequest req = staffOf(OURS, "Admin");
        MeetingBO byMember = bo(null);
        byMember.setLocationMemberId(4242);
        when(familyMemberRepo.findByIdAndTenant(4242, OURS)).thenReturn(Optional.empty());
        MeetingBO byFamily = bo(null);
        byFamily.setLocationFamilyId(77);
        when(familyRepo.findByIdAndAppClientIdAndDeleteFlagFalse(77, OURS)).thenReturn(Optional.empty());

        assertThat(controller.create(byMember, req).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.create(byFamily, req).getStatusCode().value()).isEqualTo(400);
        verify(meetingRepo, never()).save(any(Meeting.class));
    }

    @Test
    @DisplayName("Bulk delete only touches ids that belong to this church")
    void bulkDeleteIsTenantScoped() {
        MockHttpServletRequest req = staffOf(OURS, "Admin");
        when(meetingRepo.findByIdInAndAppClientIdAndDeleteFlagFalse(List.of(7, 9), OURS))
                .thenReturn(List.of(meeting(7, OURS)));
        when(meetingRepo.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        ResponseEntity<Map<String, Object>> res = controller.deleteBulk(List.of(7, 9), req);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(res.getBody().get("deleted")).isEqualTo(1);
        verify(meetingRepo, never()).findAllById(any());
    }

    @Test
    @DisplayName("Occurrence edits on another church's series are refused")
    void occurrencesAreTenantScoped() {
        MockHttpServletRequest req = staffOf(OURS, "Admin");
        Map<String, Object> body = Map.of("dates", List.of("2026-07-14"));

        assertThat(controller.occurrences(9, 5, req).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.deleteOccurrences(9, body, req).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.restoreOccurrences(9, body, req).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.deleteFutureOccurrences(9, Map.of("fromDate", "2026-07-14"), req)
                .getStatusCode().value()).isEqualTo(400);
        verify(skipRepo, never()).save(any(MeetingSkipDate.class));
        verify(skipRepo, never()).deleteByMeetingIdAndSkipDateIn(anyInt(), any());
        verify(meetingRepo, never()).save(any(Meeting.class));
    }

    @Test
    @DisplayName("Meeting write APIs carry the /meetings page role guard (church logins denied)")
    void writeApisUseThePageRoleGuard() {
        MockHttpServletRequest owner = churchOwnerOf(OURS);
        assertThat(controller.create(bo(null), owner).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.update(7, bo(null), owner).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.delete(7, owner).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.create(bo(null), new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(403);
        verify(meetingRepo, never()).save(any(Meeting.class));
    }

    // ── /api/meeting-types ─────────────────────────────────────────────────

    @Test
    @DisplayName("Meeting types are edited and deleted only within the session's church")
    void meetingTypesAreTenantScoped() {
        MockHttpServletRequest req = staffOf(OURS, "Admin");
        when(typeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(anyInt(), eq(OURS))).thenReturn(Optional.empty());
        when(typeRepo.findById(3)).thenReturn(Optional.of(type(3, THEIRS)));

        assertThat(typeController.update(3, Map.of("typeName", "Hijacked"), req).getStatusCode().value()).isEqualTo(400);
        assertThat(typeController.delete(3, req).getStatusCode().value()).isEqualTo(400);
        verify(typeRepo, never()).save(any(MeetingType.class));
        verify(typeRepo, never()).findById(anyInt());

        // positive: our own type
        when(typeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(4, OURS)).thenReturn(Optional.of(type(4, OURS)));
        assertThat(typeController.update(4, Map.of("typeName", "Prayer"), req).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(typeController.delete(4, req).getStatusCode().is2xxSuccessful()).isTrue();
        // no session at all → refused by the role guard
        assertThat(typeController.delete(4, new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(403);
    }

    // ── /api/meeting-templates ─────────────────────────────────────────────

    @Test
    @DisplayName("Message templates are edited, deleted and defaulted only within the session's church")
    void meetingTemplatesAreTenantScoped() {
        MockHttpServletRequest req = staffOf(OURS, "Admin");
        when(tplRepo.findByIdAndAppClientIdAndDeleteFlagFalse(anyInt(), eq(OURS))).thenReturn(Optional.empty());
        when(tplRepo.findByIdAndDeleteFlagFalse(3)).thenReturn(Optional.of(template(3, THEIRS)));
        Map<String, Object> body = Map.of("name", "Hijacked", "body", "x");

        assertThat(templateController.update(3, body, req).getStatusCode().value()).isEqualTo(400);
        assertThat(templateController.delete(3, req).getStatusCode().value()).isEqualTo(400);
        assertThat(templateController.setDefault(3, req).getStatusCode().value()).isEqualTo(400);
        verify(tplRepo, never()).save(any(MeetingMessageTemplate.class));
        verify(tplRepo, never()).findByIdAndDeleteFlagFalse(anyInt());

        // positive: our own template
        when(tplRepo.findByIdAndAppClientIdAndDeleteFlagFalse(4, OURS)).thenReturn(Optional.of(template(4, OURS)));
        assertThat(templateController.update(4, body, req).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(templateController.setDefault(4, req).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(templateController.delete(4, req).getStatusCode().is2xxSuccessful()).isTrue();
    }
}
