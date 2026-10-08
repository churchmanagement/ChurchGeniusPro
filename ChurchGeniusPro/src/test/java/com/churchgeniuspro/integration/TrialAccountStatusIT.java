package com.churchgeniuspro.integration;

import com.churchgeniuspro.controller.LoginController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.model.TrialRegistrationBO;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.*;
import com.churchgeniuspro.webfilter.AccountStatusFilter;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Phase C end to end on real PostgreSQL: a trial request is approved and registered,
 * its SuperAdmin signs in; Disable refuses the next API call (session ended) and the
 * next sign-in; Back to Pending keeps it refused; Approve restores it. Requires Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
                properties = { "app.base-url=http://localhost",            // the "it" profile does not set it
                               "management.health.mail.enabled=false" })   // JavaMailSender is mocked below
class TrialAccountStatusIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TrialRequestService requests;
    @Autowired TrialRegistrationLinkService links;
    @Autowired TrialTenantProvisioner provisioner;
    @Autowired SubscriptionPlanRepository plans;
    @Autowired AppUserRepository appUserRepo;
    @Autowired LoginRepository logins;
    @Autowired ServiceClientRepository clients;
    @Autowired AccountStatusService accountStatus;
    @Autowired LoginController loginController;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JavaMailSender mailSender;

    MockHttpServletResponse api(MockHttpSession session) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/members");
        req.setSession(session);
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(200);
        new AccountStatusFilter(accountStatus).doFilter(req, res, chain);
        return res;
    }

    ResponseEntity<Map<String, Object>> signIn(String username, String password) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/login");
        req.setRemoteAddr("10.0.0." + (int) (Math.random() * 200 + 1));
        Map<String, Object> body = new HashMap<>(); body.put("username", username); body.put("password", password);
        return loginController.login(body, req, new MockHttpServletResponse());
    }

    @Test
    void disableRefusesSessionsAndSignIns_approveRestores() throws Exception {
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage((jakarta.mail.Session) null));
        if (plans.findAll().stream().noneMatch(p -> "TRIAL".equalsIgnoreCase(p.getPlanCode()))) {
            SubscriptionPlan p = new SubscriptionPlan(); p.setPlanCode("TRIAL"); p.setPlanName("Trial"); p.setActive(true);
            plans.save(p);
        }

        // Request → verify → approve (issues a registration link).
        TrialRequest tr = requests.submit(new TrialRequestService.Form("Pat", "Lee", "Status IT Church",
                "pat-" + UUID.randomUUID().toString().substring(0, 6) + "@status.test", "913-555-0100", "Pastor", null), "10.1.1.1");
        // The email-verification step is covered by TrialRequestFlowTest; mark it verified here.
        jdbc.update("update trial_request set status = 'VERIFIED', verified_at = now() where id = ?", tr.getId());
        TrialRequestService.Approval ap = requests.approve(tr.getId(), "serviceadmin");
        assertThat(ap.registrationUrl()).isNotNull();
        String token = ap.registrationUrl().substring(ap.registrationUrl().lastIndexOf('=') + 1);

        // The prospect registers through the link: a tenant with a SuperAdmin login.
        TrialRegistrationLinkService.Claim claim = links.claim(token);
        assertThat(claim.valid()).isTrue();
        TrialRegistrationBO bo = new TrialRegistrationBO();
        bo.setChurchName("Status IT Church"); bo.setFirstName("Pat"); bo.setLastName("Lee"); bo.setEmail(tr.getEmail());
        TrialTenantProvisioner.Provisioned p = provisioner.provision(bo, "TRIAL", 30);
        links.complete(claim, p.clientId());
        assertThat(requests.requestForTenant(p.clientId()).map(TrialRequest::getId)).contains(tr.getId());

        AppUser superAdmin = appUserRepo.findById(p.appUserId()).orElseThrow();
        SignUp login = logins.findAllByClientId(superAdmin.getUserId()).get(0);
        String username = login.getUsername(), password = login.getDemoPassword();

        // Approved: sign-in works, API works.
        assertThat(signIn(username, password).getStatusCode().value()).as("sign-in while approved").isEqualTo(200);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("appClientId", p.clientId()); session.setAttribute("username", username);
        assertThat(api(session).getStatus()).isEqualTo(200);

        // Disable: the very next API call is refused and the session ended; a new sign-in is refused.
        requests.disable(tr.getId(), "serviceadmin");
        assertThat(clients.findByClientId(p.clientId()).orElseThrow().getStatus()).isEqualTo("Disabled");
        MockHttpServletResponse refused = api(session);
        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(refused.getContentAsString()).contains("TRIAL_STATUS", "has been disabled");
        assertThat(session.isInvalid()).isTrue();
        assertThat(signIn(username, password).getStatusCode().value()).as("sign-in while disabled").isEqualTo(403);

        // Back to Pending: still refused.
        requests.backToPending(tr.getId(), "serviceadmin");
        assertThat(signIn(username, password).getStatusCode().value()).as("sign-in while pending").isEqualTo(403);
        MockHttpSession s2 = new MockHttpSession();
        s2.setAttribute("appClientId", p.clientId()); s2.setAttribute("username", username);
        assertThat(api(s2).getStatus()).isEqualTo(403);

        // Approve again: restores the SAME account, no new link; sign-in works.
        TrialRequestService.Approval again = requests.approve(tr.getId(), "serviceadmin");
        assertThat(again.registrationUrl()).isNull();
        assertThat(clients.findByClientId(p.clientId()).orElseThrow().getStatus()).isEqualTo("Active");
        assertThat(signIn(username, password).getStatusCode().value()).as("sign-in after re-approval").isEqualTo(200);
        MockHttpSession s3 = new MockHttpSession();
        s3.setAttribute("appClientId", p.clientId()); s3.setAttribute("username", username);
        assertThat(api(s3).getStatus()).isEqualTo(200);

        // Delete: soft — the request and tenant rows remain, the account is refused.
        requests.delete(tr.getId(), "serviceadmin");
        assertThat(jdbc.queryForObject("select status from trial_request where id = ?", String.class, tr.getId())).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("select count(*) from service_client where client_id = ?", Long.class, p.clientId())).isEqualTo(1L);
        assertThat(signIn(username, password).getStatusCode().value()).as("sign-in while deleted").isEqualTo(403);
    }
}
