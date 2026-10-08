package com.churchgeniuspro.trial;

import com.churchgeniuspro.controller.TrialDeletionController;
import com.churchgeniuspro.repository.DemoDeletionAuditRepository;
import com.churchgeniuspro.service.TestDataService;
import com.churchgeniuspro.service.TrialDeletionService;
import com.churchgeniuspro.service.TrialRegistrationLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The guards on the deletion endpoints.
 *
 * <p>Confirmation that lives only in the browser is no confirmation at all for an
 * endpoint anyone with a service-admin session can call directly, so the
 * type-the-Trial-ID step is enforced here as well as on the screen.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Trial deletion endpoints")
class TrialDeletionEndpointTest {

    private static final String TRIAL = TestDataService.TRIAL_CLIENT_PREFIX + "1788983547705";

    @Mock TrialDeletionService deletion;
    @Mock TrialRegistrationLinkService links;
    @Mock DemoDeletionAuditRepository auditRepo;

    private TrialDeletionController controller;

    @BeforeEach
    void setUp() {
        controller = new TrialDeletionController(deletion, links, auditRepo);
        when(deletion.softDeleteLinks(anyCollection(), anyString()))
                .thenReturn(new TrialDeletionService.Outcome("soft-delete", "links", 1, Map.of()));
        when(deletion.permanentDeleteLinks(anyCollection(), anyString()))
                .thenReturn(new TrialDeletionService.Outcome("permanent-delete", "links", 1, Map.of()));
        when(deletion.softDeleteAccount(anyString(), anyString()))
                .thenReturn(new TrialDeletionService.Outcome("soft-delete", TRIAL, 2, Map.of()));
        when(deletion.permanentDeleteAccount(anyString(), anyString()))
                .thenReturn(Map.of("total", 143, "deleted", Map.of("signup", 4), "churchName", "Grace Chapel"));
        when(deletion.softDeleteRoles(anyCollection(), anyString()))
                .thenReturn(new TrialDeletionService.Outcome("soft-delete", "1 role(s)", 3, Map.of()));
    }

    private static MockHttpServletRequest admin() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/serviceadmin/x");
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("serviceAdminId", 1);
        s.setAttribute("serviceAdminUsername", "ops@churchgeniuspro.com");
        req.setSession(s);
        return req;
    }

    private static MockHttpServletRequest anonymous() {
        return new MockHttpServletRequest("POST", "/api/serviceadmin/x");
    }

    private static int status(ResponseEntity<?> r) { return r.getStatusCode().value(); }
    private static String body(ResponseEntity<?> r) { return String.valueOf(r.getBody()); }

    /* ── the permanent-account confirmation ─────────────────────────────── */

    @Nested
    @DisplayName("permanently deleting an account")
    class PermanentAccount {

        @Test
        @DisplayName("goes ahead when the Trial ID is typed correctly")
        void correctConfirmation() {
            ResponseEntity<?> r = controller.permanentDeleteAccount(TRIAL, Map.of("confirm", TRIAL), admin());

            assertThat(status(r)).isEqualTo(200);
            assertThat(body(r)).contains(TRIAL, "permanently deleted");
            verify(deletion).permanentDeleteAccount(eq(TRIAL), anyString());
        }

        @Test
        @DisplayName("is refused when the confirmation does not match, and nothing is deleted")
        void wrongConfirmation() {
            ResponseEntity<?> r = controller.permanentDeleteAccount(
                    TRIAL, Map.of("confirm", "TRIAL-something-else"), admin());

            assertThat(status(r)).isEqualTo(400);
            assertThat(body(r)).contains("Type the Trial ID");
            verify(deletion, never()).permanentDeleteAccount(anyString(), anyString());
        }

        @Test
        @DisplayName("is refused with no confirmation at all")
        void missingConfirmation() {
            assertThat(status(controller.permanentDeleteAccount(TRIAL, null, admin()))).isEqualTo(400);
            assertThat(status(controller.permanentDeleteAccount(TRIAL, Map.of(), admin()))).isEqualTo(400);
            verify(deletion, never()).permanentDeleteAccount(anyString(), anyString());
        }

        @Test
        @DisplayName("records an audit row naming the acting service admin")
        void auditRowWritten() {
            controller.permanentDeleteAccount(TRIAL, Map.of("confirm", TRIAL), admin());
            verify(auditRepo).save(argThat(a ->
                    TRIAL.equals(a.getClientId())
                 && "ops@churchgeniuspro.com".equals(a.getPerformedBy())
                 && a.getTotalDeleted() == 143));
        }

        @Test
        @DisplayName("soft delete needs no typed confirmation — it can be undone")
        void softDeleteNeedsNoTyping() {
            assertThat(status(controller.softDeleteAccount(TRIAL, admin()))).isEqualTo(200);
        }
    }

    /* ── selection handling ─────────────────────────────────────────────── */

    @Nested
    @DisplayName("selections")
    class Selections {

        @Test
        @DisplayName("an empty selection is refused rather than treated as 'all'")
        void emptySelectionRefused() {
            ResponseEntity<?> r = controller.softDeleteLinks(Map.of("ids", List.of()), admin());

            assertThat(status(r)).isEqualTo(400);
            assertThat(body(r)).contains("Select at least one");
            verify(deletion, never()).softDeleteAllLinks(anyString());
        }

        @Test
        @DisplayName("'all' is its own explicit flag")
        void allIsExplicit() {
            when(deletion.softDeleteAllLinks(anyString()))
                    .thenReturn(new TrialDeletionService.Outcome("soft-delete", "links", 5, Map.of()));

            assertThat(status(controller.softDeleteLinks(Map.of("all", true), admin()))).isEqualTo(200);
            verify(deletion).softDeleteAllLinks(anyString());
        }

        @Test
        @DisplayName("several ids go through in one call")
        void multipleIds() {
            controller.softDeleteLinks(Map.of("ids", List.of(1, 2, 3)), admin());
            verify(deletion).softDeleteLinks(eq(List.of(1, 2, 3)), anyString());
        }

        @Test
        @DisplayName("a junk id is ignored rather than failing the whole request")
        void junkIdsIgnored() {
            controller.softDeleteLinks(Map.of("ids", List.of("4", "abc", 5)), admin());
            verify(deletion).softDeleteLinks(eq(List.of(4, 5)), anyString());
        }

        @Test
        @DisplayName("an empty role selection is refused too")
        void emptyRoleSelection() {
            assertThat(status(controller.softDeleteRoles(Map.of("accessIds", List.of()), admin())))
                    .isEqualTo(400);
        }
    }

    /* ── the Church refusal reaches the screen ──────────────────────────── */

    @Test
    @DisplayName("the Church-login refusal is passed through as a 400 the admin can read")
    void churchRefusalSurfaces() {
        when(deletion.permanentDeleteRoles(anyCollection(), anyString()))
                .thenThrow(new IllegalArgumentException(
                        "The Church login cannot be deleted on its own — it is the trial account itself."));

        ResponseEntity<?> r = controller.permanentDeleteRoles(Map.of("accessIds", List.of(9)), admin());

        assertThat(status(r)).isEqualTo(400);
        assertThat(body(r)).contains("Church login cannot be deleted on its own");
    }

    /* ── who may call any of it ─────────────────────────────────────────── */

    @Test
    @DisplayName("every endpoint refuses a caller without a service admin session")
    void anonymousRefusedEverywhere() {
        assertThat(status(controller.softDeleteLinks(Map.of("ids", List.of(1)), anonymous()))).isEqualTo(401);
        assertThat(status(controller.permanentDeleteLinks(Map.of("ids", List.of(1)), anonymous()))).isEqualTo(401);
        assertThat(status(controller.restoreLinks(Map.of("ids", List.of(1)), anonymous()))).isEqualTo(401);
        assertThat(status(controller.listDeletedLinks(anonymous()))).isEqualTo(401);
        assertThat(status(controller.softDeleteAccount(TRIAL, anonymous()))).isEqualTo(401);
        assertThat(status(controller.restoreAccount(TRIAL, anonymous()))).isEqualTo(401);
        assertThat(status(controller.permanentDeleteAccount(TRIAL, Map.of("confirm", TRIAL), anonymous()))).isEqualTo(401);
        assertThat(status(controller.softDeleteRoles(Map.of("accessIds", List.of(1)), anonymous()))).isEqualTo(401);
        assertThat(status(controller.permanentDeleteRoles(Map.of("accessIds", List.of(1)), anonymous()))).isEqualTo(401);
        verifyNoInteractions(deletion);
    }

    @Test
    @DisplayName("a service admin's own username is what the audit records")
    void actorIsRecorded() {
        controller.softDeleteLinks(Map.of("ids", List.of(1)), admin());
        verify(deletion).softDeleteLinks(anyCollection(), eq("ops@churchgeniuspro.com"));
    }
}
