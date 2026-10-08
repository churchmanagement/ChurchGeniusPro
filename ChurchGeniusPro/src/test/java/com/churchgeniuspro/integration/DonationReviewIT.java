package com.churchgeniuspro.integration;

import com.churchgeniuspro.controller.DonationController;
import com.churchgeniuspro.controller.DonationReviewController;
import com.churchgeniuspro.hibernate.Donation;
import com.churchgeniuspro.repository.DonationRepository;
import com.churchgeniuspro.service.PermissionRefresher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Donation Review status against real PostgreSQL: the 7-donation example end to end,
 * undo, tenant isolation of the conditional updates, and that marking Completed changes
 * only the review columns — never the donation's Stripe status, amount or anything the
 * Income posting reads. Requires Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
                properties = "app.base-url=http://localhost")   // the "it" profile does not set it
class DonationReviewIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired DonationRepository donations;
    @Autowired DonationController list;
    @Autowired DonationReviewController review;
    @Autowired JdbcTemplate jdbc;

    static MockHttpServletRequest staff(String clientId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "bookkeeper@" + clientId.toLowerCase() + ".test");
        s.setAttribute("role", "Accountant");
        s.setAttribute("appClientId", clientId);
        s.setAttribute(PermissionRefresher.CHECKED_AT, System.currentTimeMillis());
        req.setSession(s);
        return req;
    }

    Donation save(String clientId, String first, String last, String amount) {
        Donation d = new Donation();
        d.setClientId(clientId);
        d.setFirstName(first); d.setLastName(last);
        d.setAmount(new BigDecimal(amount));
        d.setCurrency("usd");
        d.setStatus("succeeded");
        d.setStripePaymentIntentId("pi_it_" + UUID.randomUUID().toString().replace("-", ""));
        return donations.saveAndFlush(d);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> body(ResponseEntity<?> r) { return (Map<String, Object>) r.getBody(); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> first(ResponseEntity<?> r, String key) {
        List<Map<String, Object>> l = (List<Map<String, Object>>) body(r).get(key);
        return l.isEmpty() ? null : l.get(0);
    }

    @Test
    void sevenDonationExampleEndToEnd() {
        String cid = "IT-DON-" + UUID.randomUUID().toString().substring(0, 8);
        save(cid, "Binny", "Varghese", "100.00");
        save(cid, "Binny", "Varghese", "250.00");
        save(cid, "Priya", "Paul", "25.00");
        save(cid, "Lynn", "Rajan", "50.00");
        Long a = save(cid, "Anson", "Mathew", "1.00").getId();
        Long b = save(cid, "ANSON", "MATHEW", "1.00").getId();
        Long c = save(cid, "Anson", "Mathew", "1.00").getId();

        ResponseEntity<?> before = list.getDonations(staff(cid));
        assertThat((BigDecimal) first(before, "totals").get("total")).isEqualByComparingTo("428.00");
        assertThat(first(before, "totals").get("count")).isEqualTo(7);
        assertThat(first(before, "completedTotals")).isNull();

        ResponseEntity<?> mark = review.setReviewStatus(
                Map.of("status", "COMPLETED", "ids", List.of(a, b, c)), staff(cid));
        assertThat(mark.getStatusCode().value()).isEqualTo(200);
        assertThat(body(mark)).containsEntry("updated", 3);

        ResponseEntity<?> after = list.getDonations(staff(cid));
        assertThat((BigDecimal) first(after, "totals").get("total")).isEqualByComparingTo("425.00");
        assertThat(first(after, "totals").get("count")).isEqualTo(4);
        assertThat((BigDecimal) first(after, "completedTotals").get("total")).isEqualByComparingTo("3.00");
        assertThat(first(after, "completedTotals").get("count")).isEqualTo(3);
        assertThat((List<?>) body(after).get("rows")).hasSize(7);

        // Only the review columns changed — Stripe status and amount are as they were.
        Map<String, Object> row = jdbc.queryForMap(
                "select status, amount, review_status, review_completed_at, review_completed_by from donation where id = ?", a);
        assertThat(row.get("status")).isEqualTo("succeeded");
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("1.00");
        assertThat(row.get("review_status")).isEqualTo("COMPLETED");
        assertThat(row.get("review_completed_at")).isNotNull();
        assertThat(row.get("review_completed_by")).isEqualTo("bookkeeper@" + cid.toLowerCase() + ".test");

        // Marking again is harmless — nothing new to change.
        assertThat(body(review.setReviewStatus(Map.of("status", "COMPLETED", "ids", List.of(a)), staff(cid))))
                .containsEntry("updated", 0);

        // Undo one: back to Pending, stamps cleared, totals move back.
        assertThat(body(review.setReviewStatus(Map.of("status", "PENDING", "ids", List.of(c)), staff(cid))))
                .containsEntry("updated", 1);
        Map<String, Object> undone = jdbc.queryForMap(
                "select review_status, review_completed_at, review_completed_by from donation where id = ?", c);
        assertThat(undone.values()).containsOnlyNulls();
        ResponseEntity<?> undo = list.getDonations(staff(cid));
        assertThat((BigDecimal) first(undo, "totals").get("total")).isEqualByComparingTo("426.00");
        assertThat(first(undo, "totals").get("count")).isEqualTo(5);
        assertThat(first(undo, "completedTotals").get("count")).isEqualTo(2);
    }

    @Test
    void anotherChurchsDonationCannotBeChanged() {
        String mine = "IT-DON-" + UUID.randomUUID().toString().substring(0, 8);
        String theirs = "IT-DON-" + UUID.randomUUID().toString().substring(0, 8);
        Long own = save(mine, "Pat", "One", "10.00").getId();
        Long other = save(theirs, "Sam", "Two", "20.00").getId();

        ResponseEntity<?> res = review.setReviewStatus(
                Map.of("status", "COMPLETED", "ids", List.of(own, other)), staff(mine));
        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select review_status from donation where id = ?", String.class, own)).isNull();
        assertThat(jdbc.queryForObject("select review_status from donation where id = ?", String.class, other)).isNull();

        // Even calling the repository directly with the other church's id changes nothing.
        assertThat(donations.markReviewCompleted(mine, List.of(other), java.time.LocalDateTime.now(), "x")).isZero();
        assertThat(jdbc.queryForObject("select review_status from donation where id = ?", String.class, other)).isNull();
    }
}
