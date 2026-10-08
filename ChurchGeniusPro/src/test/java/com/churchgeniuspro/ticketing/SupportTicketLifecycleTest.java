package com.churchgeniuspro.ticketing;

import com.churchgeniuspro.controller.SupportTicketController;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.hibernate.SupportTicket;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.SupportTicketRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.service.SupportTicketService;
import com.churchgeniuspro.service.TestDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A ticket from submission to the Service Admin closing it — data, references, emails, read-only rules. */
@DisplayName("Ticketing — lifecycle")
class SupportTicketLifecycleTest {

    static final String CLIENT = "CHR-100", OTHER = "CHR-200";
    final List<SupportTicket> db = new ArrayList<>();
    final AtomicLong seq = new AtomicLong(0);
    SupportTicketRepository repo; EmailService email; SubscriptionService subs; ChurchRegistrationRepository churches;
    SupportTicketService svc;

    @BeforeEach
    void setUp() {
        repo = mock(SupportTicketRepository.class);
        when(repo.save(any(SupportTicket.class))).thenAnswer(i -> {
            SupportTicket t = i.getArgument(0);
            if (t.getId() == null) { t.setId(seq.incrementAndGet()); t.onCreate(); db.add(t); } else t.onUpdate();
            return t; });
        when(repo.existsByReference(anyString())).thenAnswer(i -> db.stream().anyMatch(t -> t.getReference().equals(i.getArgument(0))));
        when(repo.findByClientIdOrderByCreatedAtDesc(anyString())).thenAnswer(i -> db.stream().filter(t -> t.getClientId().equals(i.getArgument(0))).collect(Collectors.toList()));
        when(repo.findByIdAndClientId(anyLong(), anyString())).thenAnswer(i -> db.stream().filter(t -> t.getId().equals(i.getArgument(0)) && t.getClientId().equals(i.getArgument(1))).findFirst());
        when(repo.findById(anyLong())).thenAnswer(i -> db.stream().filter(t -> t.getId().equals(i.getArgument(0))).findFirst());
        when(repo.findAllByOrderByCreatedAtDesc()).thenAnswer(i -> new ArrayList<>(db));
        when(repo.findByStatusOrderByCreatedAtDesc(anyString())).thenAnswer(i -> db.stream().filter(t -> t.getStatus().equals(i.getArgument(0))).collect(Collectors.toList()));
        email = mock(EmailService.class);
        subs = mock(SubscriptionService.class);
        SubscriptionPlan pro = new SubscriptionPlan(); pro.setPlanCode("PRO"); pro.setPlanName("Pro");
        when(subs.getPlan(CLIENT)).thenReturn(pro);
        churches = mock(ChurchRegistrationRepository.class);
        ChurchRegistration cr = new ChurchRegistration(); cr.setChurchName("Grace Chapel");
        when(churches.findByClientIdAndDeleteFlagFalse(CLIENT)).thenReturn(Optional.of(cr));
        svc = new SupportTicketService(repo, churches, email, subs);
    }

    SupportTicketService.NewTicket form() {
        return new SupportTicketService.NewTicket("Cannot save an event", "Pat Lee", "Pat.Lee@grace.test",
                "Error: 'Failed to save event' after clicking Save.\nSteps: 1) Events 2) Add 3) Save", "High");
    }

    @Nested @DisplayName("submission")
    class Submission {
        @Test void requiredFieldsAndValues() {
            assertThat(SupportTicketService.validate(null)).isNotNull();
            assertThat(SupportTicketService.validate(new SupportTicketService.NewTicket("", "n", "a@b.co", "d", "Low"))).isEqualTo("Subject is required.");
            assertThat(SupportTicketService.validate(new SupportTicketService.NewTicket("s", " ", "a@b.co", "d", "Low"))).isEqualTo("Name is required.");
            assertThat(SupportTicketService.validate(new SupportTicketService.NewTicket("s", "n", null, "d", "Low"))).isEqualTo("Email is required.");
            assertThat(SupportTicketService.validate(new SupportTicketService.NewTicket("s", "n", "not-an-email", "d", "Low"))).isEqualTo("Please enter a valid email address.");
            assertThat(SupportTicketService.validate(new SupportTicketService.NewTicket("s", "n", "a@b.co", "", "Low"))).isEqualTo("Error Description is required.");
            assertThat(SupportTicketService.validate(new SupportTicketService.NewTicket("s", "n", "a@b.co", "d", null))).isEqualTo("Urgency is required.");
            assertThat(SupportTicketService.validate(new SupportTicketService.NewTicket("s", "n", "a@b.co", "d", "Critical"))).isEqualTo("Urgency must be Low, Medium or High.");
            assertThat(SupportTicketService.validate(form())).isNull();
            assertThatThrownBy(() -> svc.submit(CLIENT, "staff1", "Admin", new SupportTicketService.NewTicket("s", "n", "a@b.co", "d", null)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(db).isEmpty();
        }

        @Test void persistsEverythingWithAReference() {
            SupportTicketService.Submitted r = svc.submit(CLIENT, "staff1", "Admin", form());
            SupportTicket t = r.ticket();
            assertThat(db).containsExactly(t);
            assertThat(t.getReference()).matches("TKT-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}");
            assertThat(t.getClientId()).isEqualTo(CLIENT);
            assertThat(t.getChurchName()).isEqualTo("Grace Chapel");
            assertThat(t.getSubject()).isEqualTo("Cannot save an event");
            assertThat(t.getSubmitterName()).isEqualTo("Pat Lee");
            assertThat(t.getSubmitterEmail()).isEqualTo("pat.lee@grace.test");
            assertThat(t.getDescription()).contains("Steps: 1) Events");
            assertThat(t.getUrgency()).isEqualTo("High");
            assertThat(t.getStatus()).isEqualTo("Open");
            assertThat(t.getClientPackage()).isEqualTo("Pro");
            assertThat(t.getSubmittedBy()).isEqualTo("staff1");
            assertThat(t.getSubmittedRole()).isEqualTo("Admin");
            assertThat(t.getCreatedAt()).isNotNull();
        }

        @Test void referencesAreUniqueAndRetriedOnCollision() {
            Set<String> refs = new HashSet<>();
            for (int i = 0; i < 200; i++) refs.add(svc.submit(CLIENT, "u", "User", form()).ticket().getReference());
            assertThat(refs).hasSize(200);
            // a collision is retried, never reused
            String taken = db.get(0).getReference();
            when(repo.existsByReference(anyString())).thenReturn(true, false);
            assertThat(svc.newReference()).isNotEqualTo(taken);
        }

        @Test void supportEmailCarriesEveryRequiredField() throws Exception {
            SupportTicket t = svc.submit(CLIENT, "staff1", "Admin", form()).ticket();
            ArgumentCaptor<List<String>> to = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(email, times(2)).sendComposed(to.capture(), isNull(), subject.capture(), body.capture(), isNull(), eq("ChurchGeniusPro Support"));
            int i = to.getAllValues().indexOf(List.of("support@churchgeniuspro.com"));
            assertThat(i).isGreaterThanOrEqualTo(0);
            String html = body.getAllValues().get(i);
            assertThat(subject.getAllValues().get(i)).contains(t.getReference()).contains("High").contains("Cannot save an event").contains("Grace Chapel");
            for (String label : List.of("Reference Number", "Church Name", "Subject", "Submitter Name", "Submitter Email",
                                        "Complete Error Description", "Urgency", "Current Client Package", "Logged-in Username", "Client ID")) {
                assertThat(html).as(label).contains(label);
            }
            assertThat(html).contains(t.getReference()).contains("Grace Chapel").contains("Pat Lee").contains("pat.lee@grace.test")
                            .contains("Steps: 1) Events").contains("High").contains("Pro").contains("staff1").contains(CLIENT);
            // the description is escaped, never interpreted as markup
            SupportTicket evil = svc.submit(CLIENT, "u", "User", new SupportTicketService.NewTicket("s", "n", "a@b.co", "<script>alert(1)</script> & done", "Low")).ticket();
            assertThat(svc.supportEmailHtml(evil)).contains("&lt;script&gt;").contains("&amp; done").doesNotContain("<script>");
        }

        @Test void submitterGetsAConfirmationWithTheReference() throws Exception {
            SupportTicket t = svc.submit(CLIENT, "staff1", "Admin", form()).ticket();
            ArgumentCaptor<List<String>> to = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(email, times(2)).sendComposed(to.capture(), isNull(), subject.capture(), body.capture(), isNull(), anyString());
            int i = to.getAllValues().indexOf(List.of("pat.lee@grace.test"));
            assertThat(i).isGreaterThanOrEqualTo(0);
            assertThat(subject.getAllValues().get(i)).contains(t.getReference());
            assertThat(body.getAllValues().get(i)).contains("received").contains("contact you shortly").contains(t.getReference());
        }

        @Test void aFailedEmailNeverLosesTheTicket() throws Exception {
            doThrow(new IllegalStateException("SMTP down")).when(email).sendComposed(any(), any(), any(), any(), any(), any());
            SupportTicketService.Submitted r = svc.submit(CLIENT, "staff1", "Admin", form());
            assertThat(db).hasSize(1);
            assertThat(r.supportEmailSent()).isFalse(); assertThat(r.confirmationEmailSent()).isFalse();
            assertThat(r.emailProblem()).contains("could not").contains("pat.lee@grace.test");
        }

        @Test void trialAccountIsReportedAsTrialPackage() {
            String trial = TestDataService.TRIAL_CLIENT_PREFIX + "1790000000001";
            assertThat(svc.clientPackage(trial)).isEqualTo("Trial");
            SubscriptionPlan tp = new SubscriptionPlan(); tp.setPlanCode("TRIAL"); tp.setPlanName("Trial");
            when(subs.getPlan("CHR-T")).thenReturn(tp);
            assertThat(svc.clientPackage("CHR-T")).isEqualTo("Trial");
            SubscriptionPlan std = new SubscriptionPlan(); std.setPlanCode("STANDARD"); std.setPlanName("Standard");
            when(subs.getPlan("CHR-S")).thenReturn(std);
            assertThat(svc.clientPackage("CHR-S")).isEqualTo("Standard");
            SubscriptionPlan free = new SubscriptionPlan(); free.setPlanCode("FREE"); free.setPlanName("Free");
            when(subs.getPlan("CHR-F")).thenReturn(free);
            assertThat(svc.clientPackage("CHR-F")).isEqualTo("Free");
            SupportTicket t = svc.submit(trial, "trial-admin", "SuperAdmin", form()).ticket();
            assertThat(t.getClientPackage()).isEqualTo("Trial");
        }
    }

    @Nested @DisplayName("the church's view")
    class ChurchView {
        @Test void listAndDetailAreTenantScopedAndShowTheRequiredColumns() {
            SupportTicket mine = svc.submit(CLIENT, "u", "User", form()).ticket();
            svc.submit(OTHER, "x", "User", form());
            List<Map<String, Object>> list = svc.listForClient(CLIENT);
            assertThat(list).hasSize(1);
            Map<String, Object> row = list.get(0);
            assertThat(row).containsKeys("reference", "subject", "name", "email", "shortDescription", "status");
            assertThat(row.get("reference")).isEqualTo(mine.getReference());
            assertThat(row.get("status")).isEqualTo("Open");
            assertThat((String) row.get("shortDescription")).isNotEqualTo(mine.getDescription()).endsWith("…");
            assertThat(svc.detailForClient(CLIENT, mine.getId())).isPresent();
            assertThat(svc.detailForClient(CLIENT, mine.getId()).get().get("description")).isEqualTo(mine.getDescription());
            assertThat(svc.detailForClient(OTHER, mine.getId())).as("another church's id resolves to nothing").isEmpty();
        }

        @Test void thereIsNoEditOrDeleteForTheChurch() {
            // No PUT/DELETE handler exists under /api/tickets — the only writes are POST (create)
            // and the Service Admin status change. Checked on the controller itself so a
            // future mapping cannot be added without this failing.
            for (Method m : SupportTicketController.class.getMethods()) {
                assertThat(m.isAnnotationPresent(PutMapping.class)).as(m.getName()).isFalse();
                assertThat(m.isAnnotationPresent(DeleteMapping.class)).as(m.getName()).isFalse();
                RequestMapping rm = m.getAnnotation(RequestMapping.class);
                if (rm != null) assertThat(Arrays.toString(rm.method())).doesNotContain("PUT").doesNotContain("DELETE");
            }
            // and the service exposes no update/delete of a church ticket's content
            assertThat(Arrays.stream(SupportTicketService.class.getMethods()).map(Method::getName))
                    .noneMatch(n -> n.startsWith("delete") || n.startsWith("update") || n.startsWith("edit"));
        }
    }

    @Nested @DisplayName("Service Admin")
    class Admin {
        @Test void seesEveryClientWithTheRequiredColumns() {
            svc.submit(CLIENT, "u", "User", form());
            svc.submit(OTHER, "x", "User", form());
            List<Map<String, Object>> all = svc.listAll("All");
            assertThat(all).hasSize(2);
            assertThat(all.get(0)).containsKeys("clientId", "churchName", "reference", "subject", "shortDescription", "status");
            assertThat(all.stream().map(m -> m.get("clientId"))).containsExactlyInAnyOrder(CLIENT, OTHER);
            assertThat(svc.listAll("Open")).hasSize(2);
            assertThat(svc.listAll("Closed")).isEmpty();
        }

        @Test void openToClosedAndBackWithClientEmail() throws Exception {
            SupportTicket t = svc.submit(CLIENT, "u", "User", form()).ticket();
            reset(email);
            SupportTicket closed = svc.setStatus(t.getId(), "Closed", "admin@cgp");
            assertThat(closed.getStatus()).isEqualTo("Closed");
            assertThat(closed.getStatusChangedBy()).isEqualTo("admin@cgp");
            assertThat(closed.getStatusChangedAt()).isNotNull();
            Map<String, String> draft = svc.defaultStatusEmail(closed);
            assertThat(draft.get("to")).isEqualTo("pat.lee@grace.test");
            assertThat(draft.get("subject")).contains(t.getReference()).contains("resolved");
            assertThat(draft.get("body")).contains("Pat Lee").contains("Closed");
            svc.sendStatusEmail(closed, null, null);
            ArgumentCaptor<List<String>> to = ArgumentCaptor.forClass(List.class);
            ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
            verify(email).sendComposed(to.capture(), isNull(), contains("resolved"), html.capture(), isNull(), eq("ChurchGeniusPro Support"));
            assertThat(to.getValue()).containsExactly("pat.lee@grace.test");
            assertThat(html.getValue()).contains(t.getReference()).contains("Closed");

            SupportTicket reopened = svc.setStatus(t.getId(), "open", "admin@cgp");
            assertThat(reopened.getStatus()).isEqualTo("Open");
            assertThat(svc.defaultStatusEmail(reopened).get("subject")).contains("reopened");
            assertThat(svc.listAll("Open")).hasSize(1);
            assertThatThrownBy(() -> svc.setStatus(t.getId(), "Pending", "a")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> svc.setStatus(999L, "Closed", "a")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test void statusEndpointEmailsByDefaultAndCanBeSwitchedOff() throws Exception {
            SupportTicket t = svc.submit(CLIENT, "u", "User", form()).ticket();
            reset(email);
            Constructor<?> c = SupportTicketController.class.getDeclaredConstructors()[0];
            Object[] a = Arrays.stream(c.getParameterTypes()).map(x -> x == SupportTicketService.class ? svc : mock(x)).toArray();
            SupportTicketController ctl = (SupportTicketController) c.newInstance(a);
            MockHttpSession s = new MockHttpSession(); s.setAttribute("serviceAdminId", 1); s.setAttribute("serviceAdminUsername", "root");
            MockHttpServletRequest r = new MockHttpServletRequest(); r.setSession(s);

            ResponseEntity<?> res = ctl.adminSetStatus(t.getId(), Map.of("status", "Closed"), r);   // sendEmail omitted → on
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            Map<?, ?> body = (Map<?, ?>) res.getBody();
            assertThat(body.get("emailSent")).isEqualTo(true);
            assertThat(body.get("emailTo")).isEqualTo("pat.lee@grace.test");
            verify(email, times(1)).sendComposed(eq(List.of("pat.lee@grace.test")), isNull(), anyString(), anyString(), isNull(), anyString());

            res = ctl.adminSetStatus(t.getId(), Map.of("status", "Open", "sendEmail", false), r);
            assertThat(((Map<?, ?>) res.getBody()).get("emailSent")).isEqualTo(false);
            verifyNoMoreInteractions(email);
            assertThat(db.get(0).getStatus()).isEqualTo("Open");
            assertThat(db.get(0).getStatusChangedBy()).isEqualTo("root");

            // a custom subject/body is used as typed
            ctl.adminSetStatus(t.getId(), Map.of("status", "Closed", "subject", "All sorted", "body", "Fixed it for you."), r);
            verify(email).sendComposed(eq(List.of("pat.lee@grace.test")), isNull(), eq("All sorted"), contains("Fixed it for you."), isNull(), anyString());

            // a failed email does not undo the status change
            doThrow(new IllegalStateException("SMTP down")).when(email).sendComposed(any(), any(), any(), any(), any(), any());
            res = ctl.adminSetStatus(t.getId(), Map.of("status", "Open"), r);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(((Map<?, ?>) res.getBody()).get("emailError").toString()).contains("could not be sent");
            assertThat(db.get(0).getStatus()).isEqualTo("Open");
        }
    }
}
