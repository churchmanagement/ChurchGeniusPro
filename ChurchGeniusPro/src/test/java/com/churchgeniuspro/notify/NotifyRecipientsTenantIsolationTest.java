package com.churchgeniuspro.notify;

import com.churchgeniuspro.controller.NotifyEmailController;
import com.churchgeniuspro.hibernate.ChurchEvent;
import com.churchgeniuspro.hibernate.EventRegistration;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.repository.ChurchEventRepository;
import com.churchgeniuspro.repository.EventRegistrationRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.service.NotifyEmailService;
import jakarta.mail.internet.MimeMessage;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/notify/recipients} had no session check at all and resolved
 * "members" / "church-guests" across every church, and "event-guests" read the
 * registrations of any event id. {@code POST /api/notify/send} did the same for
 * the actual send. Both now resolve strictly within the session tenant.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Notify email — recipients are tenant-bound")
class NotifyRecipientsTenantIsolationTest {

    private static final String OURS   = "CHR-ours";
    private static final String THEIRS = "CHR-theirs";

    @Mock FamilyMemberRepository      memberRepo;
    @Mock ChurchEventRepository       eventRepo;
    @Mock EventRegistrationRepository regRepo;
    @Mock JavaMailSender              mailSender;

    NotifyEmailController controller;

    @BeforeEach
    void setUp() {
        NotifyEmailService service = new NotifyEmailService(memberRepo, eventRepo, regRepo, mailSender);
        ReflectionTestUtils.setField(service, "fromAddress", "noreply@churchgeniuspro.test");
        controller = new NotifyEmailController(service);

        // Event 7 is ours, event 4242 belongs to THEIRS.
        when(eventRepo.findByIdAndAppClientIdAndDeleteFlagFalse(7, OURS)).thenReturn(Optional.of(event(7, OURS)));
        when(eventRepo.findByIdAndAppClientIdAndDeleteFlagFalse(4242, OURS)).thenReturn(Optional.empty());
        when(eventRepo.findByIdAndDeleteFlagFalse(anyInt())).thenAnswer(i -> Optional.of(event(i.getArgument(0), THEIRS)));
        when(regRepo.findByEventIdOrderByCreatedDateAsc(7)).thenReturn(List.of(reg("ours@x.org")));
        when(regRepo.findByEventIdOrderByCreatedDateAsc(4242)).thenReturn(List.of(reg("theirs@x.org")));
        // Member directory: the scoped query answers per tenant, the unscoped one leaks both.
        when(memberRepo.findByMemberTypeWithEmailByAppUser("Member", OURS)).thenReturn(List.of(member("m-ours@x.org")));
        when(memberRepo.findByMemberTypeWithEmailByAppUser("Guest", OURS)).thenReturn(List.of(member("g-ours@x.org")));
        when(memberRepo.findByMemberTypeWithEmail(anyString()))
                .thenReturn(List.of(member("m-ours@x.org"), member("m-theirs@x.org")));
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((jakarta.mail.Session) null));
    }

    private static ChurchEvent event(int id, String clientId) {
        ChurchEvent e = new ChurchEvent();
        e.setId(id); e.setAppClientId(clientId); e.setEventName("Picnic " + id);
        return e;
    }

    private static EventRegistration reg(String email) {
        EventRegistration r = new EventRegistration();
        r.setId(1); r.setFirstName("A"); r.setLastName("B"); r.setEmail(email);
        return r;
    }

    private static FamilyMember member(String email) {
        FamilyMember m = new FamilyMember();
        m.setId(1); m.setFirstName("A"); m.setLastName("B"); m.setEmail(email);
        return m;
    }

    private static MockHttpServletRequest staffOf(String clientId, String role) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("appClientId", clientId);
        s.setAttribute("clientId", clientId);
        s.setAttribute("username", "staff@" + clientId);
        s.setAttribute("role", role);
        req.setSession(s);
        return req;
    }

    private static List<String> emails(ResponseEntity<List<Map<String, Object>>> res) {
        return res.getBody().stream().map(r -> String.valueOf(r.get("email"))).toList();
    }

    // ── GET /api/notify/recipients ──────────────────────────────────────────

    @Test
    @DisplayName("members / church-guests previews only list this church's people")
    void memberPreviewIsTenantScoped() {
        assertThat(emails(controller.getRecipients("members", null, staffOf(OURS, "Admin"))))
                .containsExactly("m-ours@x.org");
        assertThat(emails(controller.getRecipients("church-guests", null, staffOf(OURS, "User"))))
                .containsExactly("g-ours@x.org");
        verify(memberRepo, never()).findByMemberTypeWithEmail(anyString());
    }

    @Test
    @DisplayName("event-guests preview refuses another church's event id")
    void eventPreviewIsTenantScoped() {
        assertThat(emails(controller.getRecipients("event-guests", 4242, staffOf(OURS, "Admin")))).isEmpty();
        verify(regRepo, never()).findByEventIdOrderByCreatedDateAsc(4242);

        assertThat(emails(controller.getRecipients("event-guests", 7, staffOf(OURS, "Admin"))))
                .containsExactly("ours@x.org");
    }

    @Test
    @DisplayName("preview now carries the page's role + permission gate; no session → 403")
    void previewRequiresRole() {
        assertThat(controller.getRecipients("members", null, new MockHttpServletRequest()).getStatusCode().value())
                .isEqualTo(403);
        assertThat(controller.getRecipients("members", null, staffOf(OURS, "Accountant")).getStatusCode().value())
                .isEqualTo(403);
        MockHttpServletRequest denied = staffOf(OURS, "Admin");
        denied.getSession().setAttribute("privileges", "{\"general.email\":false}");
        assertThat(controller.getRecipients("members", null, denied).getStatusCode().value()).isEqualTo(403);
        verify(memberRepo, never()).findByMemberTypeWithEmailByAppUser(anyString(), anyString());
    }

    // ── POST /api/notify/send ───────────────────────────────────────────────

    @Test
    @DisplayName("send to event-guests of another church's event is refused and nothing is mailed")
    void sendRefusesForeignEvent() {
        ResponseEntity<Map<String, Object>> res = controller.send(
                "event-guests", 4242, null, "Hi", "<p>x</p>", null, staffOf(OURS, "Admin"));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        verify(regRepo, never()).findByEventIdOrderByCreatedDateAsc(4242);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("send to members resolves only this church's directory")
    void sendMembersIsTenantScoped() {
        ResponseEntity<Map<String, Object>> res = controller.send(
                "members", null, null, "Hi", "<p>x</p>", null, staffOf(OURS, "Admin"));
        assertThat(res.getStatusCode().is2xxSuccessful()).as(String.valueOf(res.getBody())).isTrue();
        assertThat(res.getBody().get("message")).isEqualTo("Email sent to 1 recipient(s).");
        verify(memberRepo).findByMemberTypeWithEmailByAppUser("Member", OURS);
        verify(memberRepo, never()).findByMemberTypeWithEmail(anyString());
        verify(mailSender).send(any(MimeMessage.class));
    }
}
