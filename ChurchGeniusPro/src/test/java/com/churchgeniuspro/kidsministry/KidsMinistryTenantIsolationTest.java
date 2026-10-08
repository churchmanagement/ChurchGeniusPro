package com.churchgeniuspro.kidsministry;

import com.churchgeniuspro.controller.KidsMinistryController;
import com.churchgeniuspro.controller.PublicKidsCheckinController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.EmailService;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Kids Ministry upsert-by-body-id handlers used to load {@code findById(id)} and
 * then stamp the session's clientId on whatever came back — re-parenting another
 * church's classroom, child or volunteer. They now load tenant-scoped and 404.
 * The public check-in guardian lookup is likewise scoped and must sit in the
 * child's own household.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KidsMinistryTenantIsolationTest {

    private static final String OURS   = "CHURCH-A";
    private static final String THEIRS = "CHURCH-B";

    @Mock com.churchgeniuspro.service.PublicLinkResolver links;
    @Mock KmChildRepository                   childRepo;
    @Mock KmClassroomRepository               classroomRepo;
    @Mock KmAuthorizedPickupRepository        pickupRepo;
    @Mock KmCheckinRepository                 checkinRepo;
    @Mock KmVolunteerRepository               volunteerRepo;
    @Mock KmVolunteerRoleRepository           kmVolRoleRepo;
    @Mock KmVolunteerRoleAssignmentRepository kmVolRoleAssignRepo;
    @Mock FamilyMemberRepository              familyMemberRepo;
    @Mock KmChildSetupRepository              setupRepo;
    @Mock EmailService                        emailService;
    @Mock SmsService                          smsService;
    @Mock SubscriptionService                 subscriptionService;

    KidsMinistryController controller;
    PublicKidsCheckinController publicController;

    @BeforeEach
    void setUp() {
        controller = new KidsMinistryController(childRepo, classroomRepo, pickupRepo, checkinRepo,
                volunteerRepo, kmVolRoleRepo, kmVolRoleAssignRepo, familyMemberRepo, setupRepo,
                emailService, smsService, subscriptionService, links);
        publicController = new PublicKidsCheckinController(childRepo, checkinRepo, familyMemberRepo, links, new com.churchgeniuspro.util.PublicSendLimiter());
        when(links.resolveClientId("tok-kids-ours", com.churchgeniuspro.service.PublicPagePolicy.KIDS_CHECKIN_URL)).thenReturn(OURS);
        // The scoped finders are the only ones the handlers may use: empty for OUR tenant.
        when(classroomRepo.findByIdAndClientId(anyLong(), anyString())).thenReturn(Optional.empty());
        when(childRepo.findByIdAndClientId(anyLong(), anyString())).thenReturn(Optional.empty());
        when(volunteerRepo.findByIdAndClientId(anyLong(), anyString())).thenReturn(Optional.empty());
        when(familyMemberRepo.findByIdAndTenant(any(), anyString())).thenReturn(Optional.empty());
    }

    private MockHttpServletRequest adminOf(String clientId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("appClientId", clientId);
        s.setAttribute("username", "admin@" + clientId);
        s.setAttribute("role", "Admin");
        req.setSession(s);
        return req;
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // ── classrooms ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /classrooms with another church's id is 404 and writes nothing")
    void saveClassroomIsTenantScoped() {
        KmClassroom theirs = new KmClassroom(); theirs.setId(5L); theirs.setClientId(THEIRS);
        when(classroomRepo.findById(5L)).thenReturn(Optional.of(theirs));

        ResponseEntity<?> res = controller.saveClassroom(body("id", 5, "className", "Hijack"), adminOf(OURS));

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        verify(classroomRepo, never()).save(any(KmClassroom.class));
        assertThat(theirs.getClientId()).isEqualTo(THEIRS);
    }

    @Test
    @DisplayName("POST /classrooms still updates a classroom this church owns")
    void saveClassroomAllowsOwnTenant() {
        KmClassroom ours = new KmClassroom(); ours.setId(7L); ours.setClientId(OURS);
        when(classroomRepo.findByIdAndClientId(7L, OURS)).thenReturn(Optional.of(ours));

        ResponseEntity<?> res = controller.saveClassroom(body("id", 7, "className", "Toddlers"), adminOf(OURS));

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        verify(classroomRepo).save(ours);
        assertThat(ours.getClassName()).isEqualTo("Toddlers");
    }

    // ── children ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /children with another church's id is 404; pickups are not touched")
    void saveChildIsTenantScoped() {
        KmChild theirs = new KmChild(); theirs.setId(9L); theirs.setClientId(THEIRS);
        when(childRepo.findById(9L)).thenReturn(Optional.of(theirs));

        ResponseEntity<?> res = controller.saveChild(
                body("id", 9, "firstName", "X", "authorizedPickups", List.of(body("personName", "Mallory"))),
                adminOf(OURS));

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        verify(childRepo, never()).save(any(KmChild.class));
        verify(pickupRepo, never()).deleteByChildId(anyLong());
        verify(pickupRepo, never()).deleteByClientIdAndChildId(anyString(), anyLong());
        verify(pickupRepo, never()).save(any(KmAuthorizedPickup.class));
    }

    @Test
    @DisplayName("POST /children rejects a classroom or family member from another church")
    void saveChildValidatesForeignKeys() {
        ResponseEntity<?> res = controller.saveChild(body("firstName", "A", "classroomId", 55), adminOf(OURS));
        assertThat(res.getStatusCode().value()).isEqualTo(400);

        res = controller.saveChild(body("firstName", "A", "familyMemberId", 66), adminOf(OURS));
        assertThat(res.getStatusCode().value()).isEqualTo(400);

        verify(childRepo, never()).save(any(KmChild.class));
    }

    @Test
    @DisplayName("POST /children saves a new child and replaces pickups tenant-scoped")
    void saveChildAllowsOwnTenant() {
        when(childRepo.save(any(KmChild.class))).thenAnswer(i -> { KmChild c = i.getArgument(0); c.setId(100L); return c; });

        ResponseEntity<?> res = controller.saveChild(
                body("firstName", "Ada", "lastName", "L", "authorizedPickups", List.of(body("personName", "Grandma"))),
                adminOf(OURS));

        assertThat(res.getStatusCode().is2xxSuccessful()).as("%s", res.getBody()).isTrue();
        verify(pickupRepo).deleteByClientIdAndChildId(OURS, 100L);
        verify(pickupRepo, never()).deleteByChildId(anyLong());
        verify(pickupRepo).save(any(KmAuthorizedPickup.class));
    }

    // ── volunteers ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /volunteers with another church's id is 404")
    void saveVolunteerIsTenantScoped() {
        KmVolunteer theirs = new KmVolunteer(); theirs.setId(3L); theirs.setClientId(THEIRS);
        when(volunteerRepo.findById(3L)).thenReturn(Optional.of(theirs));

        ResponseEntity<?> res = controller.saveVolunteer(body("id", 3, "firstName", "M"), adminOf(OURS));

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        verify(volunteerRepo, never()).save(any(KmVolunteer.class));
        verify(kmVolRoleAssignRepo, never()).save(any());
    }

    // ── public check-in: guardian must be in this church and this household ─

    private FamilyMember member(int id, int familyId) {
        Family f = new Family(); f.setId(familyId); f.setAppClientId(OURS);
        FamilyMember m = new FamilyMember(); m.setId(id); m.setFamily(f); m.setFirstName("G" + id);
        return m;
    }

    private KmChild ourChild(long id, Integer familyMemberId) {
        KmChild c = new KmChild(); c.setId(id); c.setClientId(OURS); c.setFamilyMemberId(familyMemberId);
        c.setFirstName("Kid"); c.setLastName("K");
        return c;
    }

    /** The kiosk's phone resolves child 1, linked to member 10 of family 500 whose designated guardian is member 11. */
    private void householdForPhone() {
        KmChild kid = ourChild(1L, 10);
        kid.setParentName("G11");                 // the registered parent → member 11 is the guardian
        when(childRepo.findByClientIdAndParentPhone(OURS, "9135550100")).thenReturn(List.of(kid));
        when(familyMemberRepo.findByIdAndTenant(10, OURS)).thenReturn(Optional.of(member(10, 500)));
        when(familyMemberRepo.findActiveMembersByFamilyId(500)).thenReturn(List.of(member(10, 500), member(11, 500)));
    }

    @Test
    @DisplayName("Public submit refuses a guardian id from another church")
    void publicSubmitGuardianIsTenantScoped() throws Exception {
        householdForPhone();
        // guardian 999 exists (in THEIRS) but is not in the household the phone resolves to
        ResponseEntity<?> res = publicController.submit(body("cid", "tok-kids-ours", "phone", "9135550100",
                "childId", 1, "guardianMemberId", 999));

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        verify(familyMemberRepo, never()).findById(any());
        verify(checkinRepo, never()).save(any(KmCheckin.class));
    }

    @Test
    @DisplayName("Public submit refuses a same-church guardian from a different household")
    void publicSubmitGuardianMustMatchChildFamily() throws Exception {
        householdForPhone();
        when(familyMemberRepo.findByIdAndTenant(20, OURS)).thenReturn(Optional.of(member(20, 501)));

        ResponseEntity<?> res = publicController.submit(body("cid", "tok-kids-ours", "phone", "9135550100",
                "childId", 1, "guardianMemberId", 20));

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        verify(checkinRepo, never()).save(any(KmCheckin.class));
    }

    @Test
    @DisplayName("Public submit checks in child + guardian from the same household")
    void publicSubmitAllowsHouseholdGuardian() throws Exception {
        householdForPhone();
        when(checkinRepo.save(any(KmCheckin.class))).thenAnswer(i -> i.getArgument(0));

        ResponseEntity<?> res = publicController.submit(body("cid", "tok-kids-ours", "phone", "9135550100",
                "childId", 1, "guardianMemberId", 11));

        assertThat(res.getStatusCode().is2xxSuccessful()).as("%s", res.getBody()).isTrue();
        verify(checkinRepo, times(2)).save(any(KmCheckin.class));
    }

    @Test
    @DisplayName("Public submit refuses a child the phone number does not resolve to")
    void publicSubmitChildMustMatchPhone() throws Exception {
        householdForPhone();
        when(childRepo.findById(2L)).thenReturn(Optional.of(ourChild(2L, 30)));   // exists, same church, other family

        ResponseEntity<?> res = publicController.submit(body("cid", "tok-kids-ours", "phone", "9135550100",
                "childId", 2, "guardianMemberId", null));

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        verify(checkinRepo, never()).save(any(KmCheckin.class));
    }
}
