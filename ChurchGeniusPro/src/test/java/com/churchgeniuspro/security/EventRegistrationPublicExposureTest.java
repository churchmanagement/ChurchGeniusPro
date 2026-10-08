package com.churchgeniuspro.security;

import com.churchgeniuspro.controller.ChurchEventController;
import com.churchgeniuspro.hibernate.ChurchEvent;
import com.churchgeniuspro.hibernate.EventRegistration;
import com.churchgeniuspro.model.EventRegistrationBO;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
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
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import com.churchgeniuspro.util.PublicSendLimiter;

/**
 * Security regression for audit finding N2 — the public event-registration token must not
 * be an attendee-roster credential.
 *
 * <p>{@code GET /api/event-register/{token}/registrations} used to return every
 * registration's full record (email, phone, postal address, registration code, ids,
 * check-in state) to anyone holding the broadcast registration link. It now returns only
 * what the page's "Who else is attending?" card displays, honours the event's
 * {@code showRegistrants} switch, and the full roster remains available solely through the
 * session- and tenant-gated staff endpoint.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("N2 — public registration token is not a roster credential")
class EventRegistrationPublicExposureTest {

    private static final String TENANT_A = "CHR-A", TENANT_B = "CHR-B";
    private static final String TOKEN_A = "tok-event-A", TOKEN_B = "tok-event-B";

    /** Exactly the fields the public "Who else is attending?" card renders. */
    private static final Set<String> PUBLIC_FIELDS =
            Set.of("firstName", "lastName", "adults", "kids", "attending", "note");

    @Mock ChurchEventRepository eventRepo;
    @Mock ChurchEventDayRepository dayRepo;
    @Mock EventRegistrationRepository regRepo;
    @Mock EmailService emailService;
    @Mock EmailSettingsRepository emailSettingsRepo;
    @Mock SmsService smsService;
    @Mock FamilyMemberRepository memberRepo;
    @Mock EventEmailTemplateService eventEmailTemplateService;
    @Mock WhatsAppSenderService smsSender;
    @Mock SmsOptInRepository smsOptInRepo;
    @Mock ChurchEventImageRepository imageRepo;
    @Mock EventPublicTokenService publicTokens;
    @Mock ChurchLogoRepository logoRepo;
    @Mock ChurchRegistrationRepository churchRegRepo;

    ChurchEventService svc;
    ChurchEventController ctl;
    ChurchEvent evA, evB;

    private static ChurchEvent event(int id, String tenant) {
        ChurchEvent e = new ChurchEvent();
        e.setId(id);
        e.setAppClientId(tenant);
        e.setEventName("Event " + id);
        e.setShowRegistrants(true);
        return e;
    }

    private static EventRegistration reg(int id, int eventId, String tenant, String first, String last,
                                         String email, String phone, String code) {
        EventRegistration r = new EventRegistration();
        r.setId(id); r.setEventId(eventId); r.setClientId(tenant);
        r.setFirstName(first); r.setLastName(last);
        r.setEmail(email); r.setPhone(phone);
        r.setAddress1("12 Private Lane"); r.setCity("Wichita"); r.setState("KS"); r.setZipCode("67201");
        r.setRegistrationCode(code);
        r.setAdults(2); r.setKids(1); r.setAttending(true); r.setNote("bringing dessert");
        r.setAttendingDays("[1,2]"); r.setVegetarian(false); r.setSelectedFoodItems("[\"pasta\"]");
        return r;
    }

    @BeforeEach
    void setUp() {
        svc = new ChurchEventService(eventRepo, dayRepo, regRepo, emailService, emailSettingsRepo, smsService,
                memberRepo, eventEmailTemplateService, smsSender, smsOptInRepo, imageRepo, publicTokens);
        ctl = new ChurchEventController(svc, logoRepo, churchRegRepo, new PublicSendLimiter());

        evA = event(1, TENANT_A);
        evB = event(2, TENANT_B);
        when(publicTokens.resolve(TOKEN_A)).thenReturn(Optional.of(evA));
        when(publicTokens.resolve(TOKEN_B)).thenReturn(Optional.of(evB));
        when(publicTokens.resolve(anyString())).thenAnswer(i -> {
            String t = i.getArgument(0);
            return TOKEN_A.equals(t) ? Optional.of(evA) : TOKEN_B.equals(t) ? Optional.of(evB) : Optional.empty();
        });
        when(eventRepo.findByIdAndDeleteFlagFalse(1)).thenReturn(Optional.of(evA));
        when(eventRepo.findByIdAndDeleteFlagFalse(2)).thenReturn(Optional.of(evB));
        // Staff (tenant-scoped) lookups: an event is visible only to its own tenant.
        when(eventRepo.findByIdAndAppClientIdAndDeleteFlagFalse(1, TENANT_A)).thenReturn(Optional.of(evA));
        when(eventRepo.findByIdAndAppClientIdAndDeleteFlagFalse(2, TENANT_B)).thenReturn(Optional.of(evB));

        when(regRepo.findByEventIdOrderByCreatedDateAsc(1)).thenReturn(List.of(
                reg(11, 1, TENANT_A, "Alice", "Anderson", "alice@church-a.org", "316-555-0101", "REG-A-ALICE"),
                reg(12, 1, TENANT_A, "Bob",   "Brown",    "bob@church-a.org",   "316-555-0102", "REG-A-BOB")));
        when(regRepo.findByEventIdOrderByCreatedDateAsc(2)).thenReturn(List.of(
                reg(21, 2, TENANT_B, "Zed", "Zulu", "zed@church-b.org", "316-555-0201", "REG-B-ZED")));
        when(regRepo.save(any(EventRegistration.class))).thenAnswer(i -> i.getArgument(0));
    }

    /* ── 2 & 3: no roster PII, no registration codes ─────────────────────── */

    @Test
    @DisplayName("2/3. the public endpoint returns only display fields — no email, phone, address, code or ids")
    void publicRosterExposesOnlyDisplayFields() {
        ResponseEntity<List<Map<String, Object>>> res = ctl.getPublicRegistrations(TOKEN_A);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> body = res.getBody();
        assertThat(body).hasSize(2);
        for (Map<String, Object> row : body) {
            assertThat(row.keySet()).as("only what the 'Who is attending' card shows").isEqualTo(PUBLIC_FIELDS);
        }
        String dump = String.valueOf(body);
        assertThat(dump)
                .doesNotContain("alice@church-a.org").doesNotContain("bob@church-a.org")   // email
                .doesNotContain("316-555")                                                  // phone
                .doesNotContain("Private Lane").doesNotContain("67201")                     // address
                .doesNotContain("REG-A-")                                                   // registration code
                .doesNotContain("checkedIn").doesNotContain("eventId").doesNotContain("id=");
        // and the display fields are intact
        assertThat(body.get(0)).containsEntry("firstName", "Alice").containsEntry("adults", 2)
                .containsEntry("kids", 1).containsEntry("attending", true);
    }

    /* ── 4: cannot enumerate registrants when the church hides the list ─── */

    @Test
    @DisplayName("4. when the church turns the attendee list off, the token yields nothing at all")
    void hiddenListReturnsNothing() {
        evA.setShowRegistrants(false);
        ResponseEntity<List<Map<String, Object>>> res = ctl.getPublicRegistrations(TOKEN_A);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).isEmpty();
    }

    /* ── 5 & 6: tenant isolation and token/path manipulation ────────────── */

    @Test
    @DisplayName("5. tenant A's token only ever resolves tenant A's event — never tenant B's registrations")
    void tokenIsBoundToItsOwnTenantEvent() {
        String a = String.valueOf(ctl.getPublicRegistrations(TOKEN_A).getBody());
        String b = String.valueOf(ctl.getPublicRegistrations(TOKEN_B).getBody());
        assertThat(a).contains("Alice").doesNotContain("Zed");
        assertThat(b).contains("Zed").doesNotContain("Alice").doesNotContain("Bob");
    }

    @Test
    @DisplayName("5b. a staff session of tenant A cannot read tenant B's roster through the staff endpoint")
    void staffCannotCrossTenants() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/events/2/registrations");
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "staff-a");
        s.setAttribute("appClientId", TENANT_A);
        req.setSession(s);

        ResponseEntity<?> res = ctl.getRegistrations(2, req);   // event 2 belongs to tenant B

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody()).isNull();
    }

    @Test
    @DisplayName("6. an invalid, expired or tampered token fails safely (400) on both public reads")
    void invalidTokenFailsSafely() {
        assertThat(ctl.getPublicRegistrations("not-a-real-token").getStatusCode().value()).isEqualTo(400);
        assertThat(ctl.getPublicRegistrations("").getStatusCode().value()).isEqualTo(400);
        assertThat(ctl.lookupRegistrant("not-a-real-token", "alice@church-a.org", null, new MockHttpServletRequest())
                .getStatusCode().value()).isEqualTo(400);
        assertThatThrownBy(() -> svc.getRegistrationsByToken("../1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /* ── 7: staff still get the full roster, authenticated and tenant-scoped ── */

    @Test
    @DisplayName("7. an authenticated staff user of the owning tenant still receives the full roster")
    void staffRosterStillComplete() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/events/1/registrations");
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "staff-a");
        s.setAttribute("appClientId", TENANT_A);
        req.setSession(s);

        ResponseEntity<List<Map<String, Object>>> res = ctl.getRegistrations(1, req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).hasSize(2);
        assertThat(res.getBody().get(0)).containsEntry("email", "alice@church-a.org")
                .containsEntry("phone", "316-555-0101").containsEntry("registrationCode", "REG-A-ALICE")
                .containsEntry("address1", "12 Private Lane");
    }

    @Test
    @DisplayName("7b. the staff roster is refused without an authenticated session")
    void staffRosterRequiresSession() {
        MockHttpServletRequest anon = new MockHttpServletRequest("GET", "/api/events/1/registrations");
        assertThat(ctl.getRegistrations(1, anon).getStatusCode().value()).isEqualTo(401);
    }

    /* ── 1 & 8: legitimate public registration keeps working ────────────── */

    @Test
    @DisplayName("1. public registration with a valid token still creates a registration bound to the token's event and tenant")
    void publicRegistrationStillWorks() {
        EventRegistrationBO bo = new EventRegistrationBO();
        bo.setFirstName("Cara"); bo.setLastName("Clark"); bo.setAdults(1); bo.setKids(0); bo.setAttending(true);
        when(regRepo.findByEventIdAndEmailIgnoreCase(anyInt(), anyString())).thenReturn(Optional.empty());

        EventRegistration saved = svc.registerByToken(TOKEN_A, bo);

        assertThat(saved.getEventId()).isEqualTo(1);
        assertThat(saved.getClientId()).as("tenant is asserted from the event, never from the caller").isEqualTo(TENANT_A);
        assertThat(saved.getFirstName()).isEqualTo("Cara");
    }

    @Test
    @DisplayName("8. 'update my RSVP' prefill: the lookup returns the caller's OWN registration for THIS event only")
    void lookupReturnsOwnRsvpForThisEvent() {
        EventRegistration alice = reg(11, 1, TENANT_A, "Alice", "Anderson", "alice@church-a.org", "316-555-0101", "REG-A-ALICE");
        when(regRepo.findByEventIdAndEmailIgnoreCase(1, "alice@church-a.org")).thenReturn(Optional.of(alice));
        when(regRepo.findByEventIdAndEmailIgnoreCase(eq(1), eq("nobody@x.org"))).thenReturn(Optional.empty());
        when(regRepo.findFirstByClientIdAndEmailIgnoreCaseOrderByCreatedDateDesc(anyString(), anyString())).thenReturn(Optional.empty());
        when(memberRepo.findByPhoneOrEmailAndClient(anyString(), anyString())).thenReturn(List.of());

        Map<String, Object> own = svc.lookupRegistrant(TOKEN_A, "alice@church-a.org", null);
        assertThat(own).containsEntry("found", true).containsEntry("registeredForThisEvent", true)
                .containsEntry("firstName", "Alice").containsEntry("attending", true)
                .containsEntry("note", "bringing dessert").containsEntry("attendingDays", "[1,2]");
        assertThat(own).as("the lookup never exposes another person's data or a registration code")
                .doesNotContainKey("registrationCode").doesNotContainKey("address1");

        Map<String, Object> none = svc.lookupRegistrant(TOKEN_A, "nobody@x.org", null);
        assertThat(none).containsEntry("found", false).containsEntry("registeredForThisEvent", false);
    }
}
