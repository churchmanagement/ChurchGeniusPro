package com.churchgeniuspro.integration;

import com.churchgeniuspro.controller.SignupController;
import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.model.TrialRegistrationBO;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import com.churchgeniuspro.service.AppUserService;
import com.churchgeniuspro.service.TrialTenantProvisioner;
import com.churchgeniuspro.service.VerificationStore;
import com.churchgeniuspro.util.PasswordUtil;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The trial signup flow end to end on real PostgreSQL: provisioning creates the
 * SuperAdmin with a generated login, the invitation goes out, and the registrant
 * claims that login with their own credentials — one login, never two. Requires Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
                properties = { "app.base-url=http://localhost",            // the "it" profile does not set it
                               "management.health.mail.enabled=false" })   // JavaMailSender is mocked below
class TrialSignupClaimIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TrialTenantProvisioner provisioner;
    @Autowired AppUserService appUsers;
    @Autowired AppUserRepository appUserRepo;
    @Autowired LoginRepository logins;
    @Autowired SubscriptionPlanRepository plans;
    @Autowired SignupController signup;
    @Autowired VerificationStore codes;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JavaMailSender mailSender;

    @Test
    void provisionedSuperAdminLoginIsClaimedByTheInvitation() throws Exception {
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage((jakarta.mail.Session) null));
        if (plans.findAll().stream().noneMatch(p -> "TRIAL".equalsIgnoreCase(p.getPlanCode()))) {
            SubscriptionPlan p = new SubscriptionPlan(); p.setPlanCode("TRIAL"); p.setPlanName("Trial"); p.setActive(true);
            plans.save(p);
        }

        // 1. Provisioning creates the SuperAdmin together with a generated login.
        TrialRegistrationBO bo = new TrialRegistrationBO();
        bo.setChurchName("Claim Test Church"); bo.setFirstName("Pat"); bo.setLastName("Pastor");
        bo.setEmail("pat@claim.test"); bo.setPhone("913-555-0100");
        TrialTenantProvisioner.Provisioned p = provisioner.provision(bo, "TRIAL", 30);
        AppUser superAdmin = appUserRepo.findById(p.appUserId()).orElseThrow();
        List<SignUp> before = logins.findAllByClientId(superAdmin.getUserId());
        assertThat(before).hasSize(1);
        SignUp generated = before.get(0);
        assertThat(generated.getDemoPassword()).isNotBlank();
        assertThat(jdbc.queryForObject("select count(*) from demo_role_access where signup_id = ?", Long.class, generated.getId())).isEqualTo(1L);

        // 2. The invitation (what TrialRegistrationService sends) — the link's token.
        appUsers.sendEmail(superAdmin.getId());
        String token = appUserRepo.findById(superAdmin.getId()).orElseThrow().getInviteToken();
        assertThat(token).isNotBlank();

        // 3–6. The registrant verifies and chooses their own credentials: the login is claimed.
        String code = codes.generateAndStore(superAdmin.getUserId(), "user", "pat@claim.test");
        ResponseEntity<?> res = signup.verifyAndSignup(Map.of("clientId", token, "username", "pat.pastor",
                "password", "Str0ng!Passw0rd", "code", code));
        assertThat(res.getStatusCode().value()).as("%s", res.getBody()).isEqualTo(200);

        List<SignUp> after = logins.findAllByClientId(superAdmin.getUserId());
        assertThat(after).as("exactly one login remains").hasSize(1);          // 7
        SignUp claimed = after.get(0);
        assertThat(claimed.getId()).isEqualTo(generated.getId());               // the same row
        assertThat(claimed.getUsername()).isEqualTo("pat.pastor");              // 4
        assertThat(PasswordUtil.matches("Str0ng!Passw0rd", claimed.getPassword())).isTrue();
        assertThat(claimed.getDemoPassword()).isNull();                         // 5
        assertThat(claimed.getActive()).isTrue();
        assertThat(appUserRepo.findById(superAdmin.getId()).orElseThrow().getInviteToken()).isNull();   // 6
        assertThat(jdbc.queryForObject("select username from demo_role_access where signup_id = ?", String.class, generated.getId()))
                .isEqualTo("pat.pastor");

        // 8. The same link again is a used invitation.
        String code2 = codes.generateAndStore(superAdmin.getUserId(), "user", "pat@claim.test");
        ResponseEntity<?> again = signup.verifyAndSignup(Map.of("clientId", token, "username", "other.name",
                "password", "Str0ng!Passw0rd", "code", code2));
        assertThat(again.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(again.getBody())).contains("invalid, has been used, or has expired");
        assertThat(logins.findAllByClientId(superAdmin.getUserId())).hasSize(1);
    }
}
