package com.churchgeniuspro.integration;

import com.churchgeniuspro.controller.DonationController;
import com.churchgeniuspro.controller.MemberContributionController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.membergive.FakeChurchStripe;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.ChurchStripeGateway;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Member Give / Contribute end to end on real PostgreSQL with Stripe replaced by an
 * in-memory fake: intent → charge → save → donation row + Income row for the member
 * and purpose; a retry adds nothing; Donation Review lists it under Member
 * Contributions only. Requires Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
                properties = { "app.base-url=http://localhost",            // the "it" profile does not set it
                               "management.health.mail.enabled=false" })   // JavaMailSender is mocked below
class MemberContributionIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MemberContributionController give;
    @Autowired DonationController donations;
    @Autowired ChurchStripeGateway gateway;
    @Autowired ServiceClientRepository clients;
    @Autowired StripeSettingsRepository stripeRepo;
    @Autowired FamilyRepository familyRepo;
    @Autowired FamilyMemberRepository memberRepo;
    @Autowired MainSourceRepository mainRepo;
    @Autowired SubSourceRepository subRepo;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JavaMailSender mailSender;

    static MockHttpServletRequest member(String cid, int memberId) {
        MockHttpServletRequest req = new MockHttpServletRequest(); MockHttpSession s = new MockHttpSession();
        s.setAttribute("role", "Member"); s.setAttribute("memberId", memberId); s.setAttribute("appClientId", cid); s.setAttribute("clientId", "MBR-" + memberId);
        req.setSession(s); req.setRemoteAddr("10.0.0.9"); return req;
    }
    static MockHttpServletRequest staff(String cid) {
        MockHttpServletRequest req = new MockHttpServletRequest(); MockHttpSession s = new MockHttpSession();
        s.setAttribute("role", "Accountant"); s.setAttribute("username", "acc@" + cid); s.setAttribute("appClientId", cid);
        req.setSession(s); return req;
    }
    @SuppressWarnings("unchecked") static Map<String, Object> body(ResponseEntity<?> r) { return (Map<String, Object>) r.getBody(); }

    @Test
    void memberPaysAndIsRecordedOnce() throws Exception {
        when(mailSender.createMimeMessage()).thenAnswer(i -> new MimeMessage((jakarta.mail.Session) null));
        FakeChurchStripe stripe = new FakeChurchStripe();
        gateway.setTransport(stripe);

        String cid = "IT-GIVE-" + UUID.randomUUID().toString().substring(0, 8);
        ServiceClient c = new ServiceClient(); c.setClientId(cid); c.setChurchName("Give IT Church"); c.setName("Pat");
        c.setEmail(cid.toLowerCase() + "@church.test"); c.setSubscriptionType("PRO"); c.setStatus("Active"); c.setDeleteFlag(false); c.setApproved(true);
        c.setStartDate(LocalDate.now()); c.setEndDate(LocalDate.now().plusYears(1)); c.setCreatedDate(LocalDateTime.now());
        clients.saveAndFlush(c);
        StripeSettings ss = new StripeSettings(); ss.setClientId(cid); ss.setPublishableKey("pk_test_x"); ss.setSecretKey("sk_test_x");
        stripeRepo.saveAndFlush(ss);
        Family f = new Family(); f.setAppClientId(cid); f.setDeleteFlag(false); f.setInactive(false); f = familyRepo.saveAndFlush(f);
        FamilyMember anson = new FamilyMember(); anson.setFamily(f); anson.setFirstName("Anson"); anson.setLastName("Mathew");
        anson.setEmail("anson@give.test"); anson.setMemberType("Member"); anson.setAppClientId(cid); anson.setDeleteFlag(false); anson.setInactive(false);
        anson = memberRepo.saveAndFlush(anson);
        MainSource main = new MainSource(); main.setSourceName("Church Fund"); main.setAppClientId(cid); main.setDeleteFlag(false); main = mainRepo.saveAndFlush(main);
        SubSource tithe = new SubSource(); tithe.setSourceName("Tithe"); tithe.setMainSource(main); tithe.setAppClientId(cid); tithe.setDeleteFlag(false); tithe = subRepo.saveAndFlush(tithe);

        // Intent for $10.00 → the card is charged → save.
        ResponseEntity<?> intent = give.createIntent(Map.of("amount", "10.00", "subSourceId", tithe.getId()), member(cid, anson.getId()));
        assertThat(intent.getStatusCode().value()).as("%s", intent.getBody()).isEqualTo(200);
        String pi = (String) body(intent).get("paymentIntentId");
        assertThat(stripe.creates.get(0).getFirst("amount")).isEqualTo("1000");
        assertThat(jdbc.queryForObject("select count(*) from donation where client_id = ?", Long.class, cid)).as("nothing before payment").isZero();

        // Not yet paid: refused, nothing recorded.
        assertThat(give.save(Map.of("paymentIntentId", pi), member(cid, anson.getId())).getStatusCode().value()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from donation where client_id = ?", Long.class, cid)).isZero();

        stripe.succeed(pi, "visa", "4242");
        ResponseEntity<?> saved = give.save(Map.of("paymentIntentId", pi, "note", "October tithe"), member(cid, anson.getId()));
        assertThat(saved.getStatusCode().value()).as("%s", saved.getBody()).isEqualTo(200);

        Map<String, Object> row = jdbc.queryForMap("select source, member_id, sub_source_id, amount, payment_method, status, stripe_payment_intent_id from donation where client_id = ?", cid);
        assertThat(row.get("source")).isEqualTo("MEMBER_PORTAL");
        assertThat(row.get("member_id")).isEqualTo(anson.getId());
        assertThat(row.get("sub_source_id")).isEqualTo(tithe.getId());
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("10.00");
        assertThat(row.get("payment_method")).isEqualTo("Visa •••• 4242");
        assertThat(row.get("stripe_payment_intent_id")).isEqualTo(pi);

        // Posted to Income for the member and the chosen purpose, keyed by the PaymentIntent.
        Map<String, Object> inc = jdbc.queryForMap("select member_id, sub_source_id, amount, import_ref from income where app_client_id = ? and delete_flag = false", cid);
        assertThat(inc.get("member_id")).isEqualTo(anson.getId());
        assertThat(inc.get("sub_source_id")).isEqualTo(tithe.getId());
        assertThat((BigDecimal) inc.get("amount")).isEqualByComparingTo("10.00");
        assertThat(inc.get("import_ref")).isEqualTo(pi);

        // Retry / refresh: still one donation, one income row.
        assertThat(give.save(Map.of("paymentIntentId", pi), member(cid, anson.getId())).getStatusCode().value()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select count(*) from donation where client_id = ?", Long.class, cid)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from income where app_client_id = ? and delete_flag = false", Long.class, cid)).isEqualTo(1L);

        // Donation Review: under Member Contributions, not under Donations.
        @SuppressWarnings("unchecked") List<Map<String, Object>> pub = (List<Map<String, Object>>) body(donations.getDonations(staff(cid))).get("rows");
        @SuppressWarnings("unchecked") List<Map<String, Object>> mem = (List<Map<String, Object>>) body(donations.getDonations("member", staff(cid))).get("rows");
        assertThat(pub).isEmpty();
        assertThat(mem).hasSize(1);
        assertThat(mem.get(0)).containsEntry("purpose", "Tithe").containsEntry("firstName", "Anson").containsEntry("paymentMethod", "Visa •••• 4242");
    }
}
