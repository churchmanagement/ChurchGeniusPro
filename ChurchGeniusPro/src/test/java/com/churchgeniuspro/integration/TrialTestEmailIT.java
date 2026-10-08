package com.churchgeniuspro.integration;

import com.churchgeniuspro.controller.TrialTestEmailController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.MeetingService;
import com.churchgeniuspro.service.TrialTestEmailService;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase B end to end on real PostgreSQL: a Trial-plan church verifies a test address
 * through the controller, then Send Meeting Notification to two members produces ONE
 * test email to that address, zero to the members, and one usage count. Requires Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
                properties = { "app.base-url=http://localhost",            // the "it" profile does not set it
                               "management.health.mail.enabled=false" })   // JavaMailSender is mocked below
class TrialTestEmailIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TrialTestEmailController controller;
    @Autowired TrialTestEmailService testEmails;
    @Autowired MeetingService meetings;
    @Autowired ServiceClientRepository clients;
    @Autowired MeetingRepository meetingRepo;
    @Autowired MeetingTypeRepository typeRepo;
    @Autowired FamilyRepository familyRepo;
    @Autowired FamilyMemberRepository memberRepo;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JavaMailSender mailSender;

    static MockHttpServletRequest admin(String clientId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "admin@" + clientId.toLowerCase() + ".test");
        s.setAttribute("role", "Admin");
        s.setAttribute("appClientId", clientId);
        req.setSession(s);
        return req;
    }

    @Test
    void verifyThenOneTestEmailPerAction() throws Exception {
        List<MimeMessage> sent = new ArrayList<>();
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage((jakarta.mail.Session) null));
        doAnswer(i -> { sent.add(i.getArgument(0)); return null; }).when(mailSender).send(any(MimeMessage.class));

        String cid = "IT-TRIAL-" + UUID.randomUUID().toString().substring(0, 8);
        ServiceClient c = new ServiceClient();
        c.setClientId(cid); c.setChurchName("Trial IT Church"); c.setName("Pat"); c.setEmail(cid.toLowerCase() + "@church.test");
        c.setSubscriptionType("TRIAL"); c.setStatus("Active"); c.setDeleteFlag(false); c.setApproved(true);
        c.setStartDate(LocalDate.now()); c.setEndDate(LocalDate.now().plusDays(30)); c.setCreatedDate(LocalDateTime.now());
        clients.saveAndFlush(c);
        assertThat(testEmails.eligible(cid)).isTrue();

        // Two real members with addresses.
        Family f = new Family(); f.setAppClientId(cid); f.setDeleteFlag(false); f.setInactive(false);
        f = familyRepo.saveAndFlush(f);
        for (String e : List.of("one@real.test", "two@real.test")) {
            FamilyMember m = new FamilyMember(); m.setFamily(f); m.setFirstName(e); m.setLastName("M"); m.setEmail(e);
            m.setMemberType("Member"); m.setAppClientId(cid); m.setDeleteFlag(false); m.setInactive(false);
            memberRepo.saveAndFlush(m);
        }
        MeetingType t = new MeetingType(); t.setTypeName("Prayer"); t.setAppClientId(cid); t.setDeleteFlag(false);
        t = typeRepo.saveAndFlush(t);
        Meeting mtg = new Meeting(); mtg.setAppClientId(cid); mtg.setMeetingType(t); mtg.setMeetingDate(LocalDate.now().plusDays(2));
        mtg = meetingRepo.saveAndFlush(mtg);

        // 1. No verified address: blocked, nothing sent, no usage.
        Map<String, Object> r0 = meetings.sendManualNotification(mtg.getId(), List.of("Email"), List.of("Members"), cid);
        assertThat(r0).containsEntry("emailsSent", 0).containsEntry("emailsBlocked", 2);
        assertThat(sent).isEmpty();
        assertThat(jdbc.queryForObject("select coalesce(sum(emails_sent),0) from subscription_usage where client_id = ?", Long.class, cid)).isZero();

        // 2. Request a code (account mail, to the address being verified) and verify it.
        assertThat(controller.request(Map.of("email", "tester@trial.test"), admin(cid)).getStatusCode().value()).isEqualTo(200);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getAllRecipients()[0].toString()).isEqualTo("tester@trial.test");
        Matcher m = Pattern.compile(">(\\d{6})<").matcher(com.churchgeniuspro.trialemail.MailCaptureAccess.text(sent.get(0)));
        assertThat(m.find()).isTrue();
        String code = m.group(1);
        Map<String, Object> dbRow = jdbc.queryForMap("select verified_email, pending_email, pending_code_hash from trial_test_email where client_id = ?", cid);
        assertThat(dbRow.get("verified_email")).isNull();
        assertThat(dbRow.get("pending_email")).isEqualTo("tester@trial.test");
        assertThat(String.valueOf(dbRow.get("pending_code_hash"))).hasSize(64).isNotEqualTo(code);

        assertThat(controller.verify(Map.of("code", "000000".equals(code) ? "111111" : "000000"), admin(cid)).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.verify(Map.of("code", code), admin(cid)).getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select verified_email from trial_test_email where client_id = ?", String.class, cid)).isEqualTo("tester@trial.test");
        assertThat(controller.verify(Map.of("code", code), admin(cid)).getStatusCode().value()).as("single use").isEqualTo(400);
        sent.clear();

        // 3. The same notification now: one test email, two members simulated, one usage count.
        Map<String, Object> r1 = meetings.sendManualNotification(mtg.getId(), List.of("Email"), List.of("Members"), cid);
        assertThat(r1).containsEntry("emailsSent", 0).containsEntry("testEmailsSent", 1).containsEntry("simulated", 1)
                      .containsEntry("testEmail", "te***@tri***.test");
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getAllRecipients()).hasSize(1);
        assertThat(sent.get(0).getAllRecipients()[0].toString()).isEqualTo("tester@trial.test");
        String body = com.churchgeniuspro.trialemail.MailCaptureAccess.text(sent.get(0));
        assertThat(body).contains(TrialTestEmailService.NOTICE, "Prayer").doesNotContain("one@real.test", "two@real.test");
        assertThat(jdbc.queryForObject("select sum(emails_sent) from subscription_usage where client_id = ?", Long.class, cid)).isEqualTo(1L);

        // 4. Another church cannot read or change it.
        String other = "IT-TRIAL-" + UUID.randomUUID().toString().substring(0, 8);
        ServiceClient o = new ServiceClient();
        o.setClientId(other); o.setChurchName("Other"); o.setName("Sam"); o.setEmail(other.toLowerCase() + "@church.test");
        o.setSubscriptionType("TRIAL"); o.setStatus("Active"); o.setDeleteFlag(false); o.setApproved(true);
        o.setStartDate(LocalDate.now()); o.setEndDate(LocalDate.now().plusDays(30)); o.setCreatedDate(LocalDateTime.now());
        clients.saveAndFlush(o);
        @SuppressWarnings("unchecked") Map<String, Object> otherStatus = (Map<String, Object>) controller.status(admin(other)).getBody();
        assertThat(otherStatus).containsEntry("verifiedEmail", null);
        assertThat(testEmails.verifiedAddress(other)).isNull();
    }
}
