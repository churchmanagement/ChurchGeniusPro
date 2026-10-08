package com.churchgeniuspro.trialaccount;

import com.churchgeniuspro.controller.ServiceAdminTrialRequestController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import com.churchgeniuspro.webfilter.AccountStatusFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.*;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase C: Trial Requests gain Disable / Delete / Back to Pending, and a trial account
 * whose request is no longer Approved is refused on every protected request — the
 * session is ended — until it is approved again. Approving a request whose link was
 * already used restores the existing account instead of minting a new link.
 */
@DisplayName("Trial account status — Disable / Delete / Back to Pending and enforcement")
class TrialAccountStatusTest {

    static final String TENANT = "TRIAL-1757300000123";

    Map<Long, TrialRequest> rows = new HashMap<>();
    TrialRequestRepository repo;
    TrialRegistrationLinkRepository linkRepo;
    ServiceClientRepository clientRepo;
    AccountStatusService accountStatus;
    TrialRequestService service;
    ServiceClient tenant;
    TrialRequest approved;

    @BeforeEach
    void setUp() {
        repo = mock(TrialRequestRepository.class);
        when(repo.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(rows.get((Long) i.getArgument(0))));
        when(repo.save(any(TrialRequest.class))).thenAnswer(i -> { TrialRequest r = i.getArgument(0); rows.put(r.getId(), r); return r; });
        when(repo.findAllByOrderByCreatedAtDesc()).thenAnswer(i -> new ArrayList<>(rows.values()));
        when(repo.transition(anyLong(), anyString(), anyString(), any(), any())).thenAnswer(i -> {
            TrialRequest r = rows.get((Long) i.getArgument(0));
            if (r == null || !r.getStatus().equals(i.getArgument(1))) return 0;
            r.setStatus(i.getArgument(2)); r.setDecidedBy(i.getArgument(3)); r.setDecidedAt(i.getArgument(4));
            return 1;
        });
        when(repo.findFirstByTrialLinkId(77)).thenAnswer(i -> rows.values().stream().filter(r -> Integer.valueOf(77).equals(r.getTrialLinkId())).findFirst());

        TrialRegistrationLink used = new TrialRegistrationLink(); used.setId(77); used.setUsedClientId(TENANT); used.setUsedAt(LocalDateTime.now());
        linkRepo = mock(TrialRegistrationLinkRepository.class);
        when(linkRepo.findById(77)).thenReturn(Optional.of(used));
        when(linkRepo.findFirstByUsedClientId(TENANT)).thenReturn(Optional.of(used));

        tenant = new ServiceClient(); tenant.setClientId(TENANT); tenant.setStatus("Active"); tenant.setDeleteFlag(false);
        tenant.setEndDate(com.churchgeniuspro.util.AppClock.today().plusDays(30));
        clientRepo = mock(ServiceClientRepository.class);
        when(clientRepo.findByClientId(TENANT)).thenReturn(Optional.of(tenant));
        when(clientRepo.save(any(ServiceClient.class))).thenAnswer(i -> i.getArgument(0));

        DemoRoleAccessRepository accessRepo = mock(DemoRoleAccessRepository.class);
        when(accessRepo.findByUsername(anyString())).thenReturn(Optional.empty());
        accountStatus = new AccountStatusService(clientRepo, accessRepo);

        TrialPolicy policy = mock(TrialPolicy.class); when(policy.trialDays()).thenReturn(30);
        TrialRegistrationLinkService links = mock(TrialRegistrationLinkService.class);
        service = new TrialRequestService(repo, mock(VerificationStore.class), mock(EmailService.class),
                mock(PlatformSettingService.class), policy, links);
        service.setLinkRepo(linkRepo); service.setClientRepo(clientRepo); service.setAccountStatus(accountStatus);

        approved = new TrialRequest(); approved.setId(1L); approved.setReference("TR-1"); approved.setStatus(TrialRequest.APPROVED);
        approved.setEmail("pastor@grace.test"); approved.setChurchName("Grace"); approved.setFirstName("Pat"); approved.setLastName("Lee");
        approved.setTrialLinkId(77); approved.setCreatedAt(LocalDateTime.now());
        rows.put(1L, approved);
    }

    /** One protected API call from a signed-in user of the tenant, through the real filter. */
    MockHttpServletResponse apiCall(MockHttpSession session) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/members");
        req.setSession(session);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(200);
        new AccountStatusFilter(accountStatus).doFilter(req, res, chain);
        return res;
    }

    MockHttpSession signedIn() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("appClientId", TENANT); s.setAttribute("username", "pastor");
        return s;
    }

    @Nested @DisplayName("transitions")
    class Transitions {

        @Test @DisplayName("Disable: request DISABLED, tenant Disabled, cache invalidated")
        void disable() {
            TrialRequestService.AccountAction a = service.disable(1L, "admin");
            assertThat(a.request().getStatus()).isEqualTo(TrialRequest.DISABLED);
            assertThat(a.tenantClientId()).isEqualTo(TENANT);
            assertThat(tenant.getStatus()).isEqualTo("Disabled");
            assertThatThrownBy(() -> service.disable(1L, "admin")).hasMessageContaining("already disabled");
        }

        @Test @DisplayName("Delete: request DELETED (soft), tenant Deleted; data kept")
        void delete() {
            TrialRequestService.AccountAction a = service.delete(1L, "admin");
            assertThat(a.request().getStatus()).isEqualTo(TrialRequest.DELETED);
            assertThat(tenant.getStatus()).isEqualTo("Deleted");
            assertThat(tenant.getDeleteFlag()).isFalse();
            assertThat(rows).containsKey(1L);
            assertThatThrownBy(() -> service.disable(1L, "admin")).hasMessageContaining("deleted");
        }

        @Test @DisplayName("Back to Pending: request VERIFIED (pending approval), tenant Pending")
        void backToPending() {
            TrialRequestService.AccountAction a = service.backToPending(1L, "admin");
            assertThat(a.request().getStatus()).isEqualTo(TrialRequest.VERIFIED);
            assertThat(tenant.getStatus()).isEqualTo("Pending");
            assertThatThrownBy(() -> service.backToPending(1L, "admin")).hasMessageContaining("already pending");
        }

        @Test @DisplayName("Back to Pending is also the way out of Disabled, Deleted and Rejected")
        void backToPendingFromEachState() {
            service.disable(1L, "admin");  service.backToPending(1L, "admin");
            assertThat(approved.getStatus()).isEqualTo(TrialRequest.VERIFIED);
            service.delete(1L, "admin");   service.backToPending(1L, "admin");
            assertThat(approved.getStatus()).isEqualTo(TrialRequest.VERIFIED);
            approved.setStatus(TrialRequest.REJECTED); approved.setRejectReason("x");
            service.backToPending(1L, "admin");
            assertThat(approved.getStatus()).isEqualTo(TrialRequest.VERIFIED);
            assertThat(approved.getRejectReason()).isNull();
        }

        @Test @DisplayName("an unverified request cannot be set to pending approval")
        void unverifiedCannotBePending() {
            approved.setStatus(TrialRequest.PENDING_VERIFICATION);
            assertThatThrownBy(() -> service.backToPending(1L, "admin")).hasMessageContaining("not been verified");
        }

        @Test @DisplayName("re-approving a request with an account restores it — no new link, no email")
        void reapproveRestores() {
            service.disable(1L, "admin"); service.backToPending(1L, "admin");
            assertThat(tenant.getStatus()).isEqualTo("Pending");

            TrialRequestService.Approval ap = service.approve(1L, "admin");

            assertThat(ap.request().getStatus()).isEqualTo(TrialRequest.APPROVED);
            assertThat(ap.registrationUrl()).isNull();
            assertThat(tenant.getStatus()).isEqualTo("Active");
        }

        @Test @DisplayName("a request nobody has registered from: the status changes, no tenant to touch")
        void noTenantYet() {
            TrialRequest fresh = new TrialRequest(); fresh.setId(2L); fresh.setReference("TR-2"); fresh.setStatus(TrialRequest.APPROVED);
            fresh.setTrialLinkId(78); rows.put(2L, fresh);
            when(linkRepo.findById(78)).thenReturn(Optional.of(new TrialRegistrationLink()));
            TrialRequestService.AccountAction a = service.disable(2L, "admin");
            assertThat(a.tenantClientId()).isNull();
            assertThat(a.message()).contains("No account has been registered");
            verify(clientRepo, never()).save(any());
        }

        @Test @DisplayName("the admin list carries the account and its status")
        void listShowsTenant() {
            service.disable(1L, "admin");
            Map<String, Object> row = service.listForAdmin().get(0);
            assertThat(row).containsEntry("status", "DISABLED").containsEntry("tenantClientId", TENANT).containsEntry("tenantStatus", "Disabled");
            assertThat(service.requestForTenant(TENANT)).isPresent();
        }
    }

    @Nested @DisplayName("enforcement on every protected request")
    class Enforcement {

        @Test @DisplayName("Approved: the signed-in user works")
        void approvedWorks() throws Exception {
            assertThat(apiCall(signedIn()).getStatus()).isEqualTo(200);
        }

        @Test @DisplayName("Pending, Disabled and Deleted: refused at once, session ended, refresh/new tab/API all refused")
        void refusedImmediately() throws Exception {
            for (String act : List.of("pending", "disable", "delete")) {
                tenant.setStatus("Active"); approved.setStatus(TrialRequest.APPROVED); accountStatus.invalidateTenant(TENANT);
                MockHttpSession session = signedIn();
                assertThat(apiCall(session).getStatus()).as("before " + act).isEqualTo(200);   // cached as allowed

                switch (act) {
                    case "pending" -> service.backToPending(1L, "admin");
                    case "disable" -> service.disable(1L, "admin");
                    default        -> service.delete(1L, "admin");
                }
                MockHttpServletResponse res = apiCall(session);                      // the very next request
                assertThat(res.getStatus()).as(act).isEqualTo(403);
                assertThat(res.getContentAsString()).contains("TRIAL_STATUS").contains(
                        act.equals("pending") ? "pending approval" : act.equals("disable") ? "has been disabled" : "has been removed");
                assertThat(session.isInvalid()).as("session ended on " + act).isTrue();
                // A new tab / another session for the same tenant is refused too.
                assertThat(apiCall(signedIn()).getStatus()).isEqualTo(403);
            }
        }

        @Test @DisplayName("approving again lets the next sign-in through")
        void reapproveRestoresAccess() throws Exception {
            service.disable(1L, "admin");
            assertThat(apiCall(signedIn()).getStatus()).isEqualTo(403);
            service.backToPending(1L, "admin");
            assertThat(apiCall(signedIn()).getStatus()).isEqualTo(403);
            service.approve(1L, "admin");
            assertThat(apiCall(signedIn()).getStatus()).isEqualTo(200);
        }

        @Test @DisplayName("a normal church (Active, non-trial status) is unaffected by the new check")
        void normalChurchUnaffected() throws Exception {
            tenant.setStatus("Active");
            assertThat(apiCall(signedIn()).getStatus()).isEqualTo(200);
            tenant.setStatus("Hold"); accountStatus.invalidateTenant(TENANT);
            MockHttpSession s = signedIn();
            MockHttpServletResponse res = apiCall(s);
            assertThat(res.getStatus()).isEqualTo(403);                 // as before (not Active)
            assertThat(res.getContentAsString()).contains("SUBSCRIPTION_EXPIRED").doesNotContain("TRIAL_STATUS");
            assertThat(s.isInvalid()).as("existing behaviour: session kept").isFalse();
        }
    }

    @Nested @DisplayName("the Service Admin endpoints")
    class Endpoints {
        ServiceAdminTrialRequestController controller;

        @BeforeEach void c() { controller = new ServiceAdminTrialRequestController(service, mock(PlatformSettingService.class)); }

        static MockHttpServletRequest as(String role) {
            MockHttpServletRequest req = new MockHttpServletRequest();
            MockHttpSession s = new MockHttpSession(); s.setAttribute("role", role); s.setAttribute("username", "x"); req.setSession(s);
            return req;
        }

        @Test @DisplayName("Service Admin only")
        void serviceAdminOnly() {
            for (String role : List.of("SuperAdmin", "Admin", "Member")) {
                assertThat(controller.disable(1L, as(role)).getStatusCode().value()).as(role).isEqualTo(401);
                assertThat(controller.delete(1L, as(role)).getStatusCode().value()).as(role).isEqualTo(401);
                assertThat(controller.backToPending(1L, as(role)).getStatusCode().value()).as(role).isEqualTo(401);
            }
            assertThat(approved.getStatus()).isEqualTo(TrialRequest.APPROVED);
        }

        @Test @DisplayName("each action answers with the new status and a clear message")
        void actions() {
            ResponseEntity<Map<String, Object>> r = controller.disable(1L, as("ServiceAdmin"));
            assertThat(r.getStatusCode().value()).isEqualTo(200);
            assertThat(r.getBody()).containsEntry("requestStatus", "DISABLED").containsEntry("tenantClientId", TENANT);
            assertThat(String.valueOf(r.getBody().get("message"))).contains("refused");
            assertThat(controller.backToPending(1L, as("ServiceAdmin")).getBody()).containsEntry("requestStatus", "VERIFIED");
            assertThat(controller.delete(1L, as("ServiceAdmin")).getBody()).containsEntry("requestStatus", "DELETED");
            assertThat(controller.delete(1L, as("ServiceAdmin")).getStatusCode().value()).isEqualTo(400);
        }
    }
}
