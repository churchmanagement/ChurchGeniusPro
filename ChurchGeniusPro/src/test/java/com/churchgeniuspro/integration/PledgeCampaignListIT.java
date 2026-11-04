package com.churchgeniuspro.integration;

import com.churchgeniuspro.controller.PledgeController;
import com.churchgeniuspro.service.TestDataService;
import com.churchgeniuspro.util.AppClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** GET /api/pledges/campaigns on PostgreSQL for a provisioned demo church (2026-11-04 production 500). */
@EnabledIf("com.churchgeniuspro.integration.PledgeCampaignListIT#databaseAvailable")
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = { "app.base-url=http://localhost" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Pledge campaign list — PostgreSQL")
class PledgeCampaignListIT {

    static PostgreSQLContainer<?> POSTGRES;

    static boolean databaseAvailable() {
        if (System.getenv("CGP_IT_JDBC_URL") != null) return true;
        try { return DockerClientFactory.instance().isDockerAvailable(); } catch (Throwable t) { return false; }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        String url = System.getenv("CGP_IT_JDBC_URL");
        if (url != null) {
            r.add("spring.datasource.url", () -> url);
            r.add("spring.datasource.username", () -> System.getenv().getOrDefault("CGP_IT_JDBC_USER", "postgres"));
            r.add("spring.datasource.password", () -> System.getenv().getOrDefault("CGP_IT_JDBC_PASSWORD", ""));
            return;
        }
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
        POSTGRES.start();
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired TestDataService testData;
    @Autowired PledgeController pledges;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.churchgeniuspro.repository.IncomeRepository incomeRepo;

    @Test @DisplayName("A demo church's campaigns list loads")
    void listLoads() {
        String church = TestDataService.TRIAL_CLIENT_PREFIX + System.currentTimeMillis();
        testData.provisionTenant(new TestDataService.TenantSpec(church, "Pledge Test Church", List.of("SuperAdmin"), 1,
                TestDataService.TenantSpec.Contact.of("Pat", "Trial", "pat@pledge.test", "913-555-0100")), null, AppClock.today().plusDays(30));
        long n = jdbc.queryForObject("SELECT count(*) FROM pledge_campaign WHERE client_id = ?", Long.class, church);
        assertThat(n).isPositive();

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.getSession(true).setAttribute("username", "pat@pledge.test");
        req.getSession().setAttribute("role", "SuperAdmin");
        req.getSession().setAttribute("appClientId", church);
        ResponseEntity<?> res = pledges.listCampaigns(req);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) res.getBody()).hasSize((int) n);

        // both shapes: one campaign with an end date, the others open-ended
        jdbc.update("UPDATE pledge_campaign SET end_date = CURRENT_DATE + 30 WHERE id = (SELECT min(id) FROM pledge_campaign WHERE client_id = ?)", church);
        jdbc.update("UPDATE pledge_campaign SET end_date = NULL WHERE client_id = ? AND id <> (SELECT min(id) FROM pledge_campaign WHERE client_id = ?)", church, church);
        long open = jdbc.queryForObject("SELECT count(*) FROM pledge_campaign WHERE client_id = ? AND end_date IS NULL", Long.class, church);
        assertThat(open).isPositive();
        ResponseEntity<?> again = pledges.listCampaigns(req);
        assertThat(again.getStatusCode().value()).isEqualTo(200);

        // the open-ended and bounded queries agree with plain SQL for every member pledge
        List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                "SELECT p.family_member_id m, c.sub_source_id f, c.created_date::date s, c.end_date e FROM pledge_member p " +
                "JOIN pledge_campaign c ON c.id = p.campaign_id WHERE c.client_id = ? AND p.family_member_id IS NOT NULL " +
                "AND c.sub_source_id IS NOT NULL AND p.delete_flag = false", church);
        assertThat(rows).isNotEmpty();
        for (var r : rows) {
            Integer m = ((Number) r.get("m")).intValue(), f = ((Number) r.get("f")).intValue();
            java.time.LocalDate s = ((java.sql.Date) r.get("s")).toLocalDate();
            java.time.LocalDate e = r.get("e") == null ? null : ((java.sql.Date) r.get("e")).toLocalDate();
            java.math.BigDecimal expected = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(amount),0) FROM income WHERE delete_flag = false AND member_id = ? AND sub_source_id = ? " +
                    "AND app_client_id = ? AND income_date >= ?" + (e == null ? "" : " AND income_date <= ?"),
                    java.math.BigDecimal.class, e == null ? new Object[]{m, f, church, s} : new Object[]{m, f, church, s, e});
            java.math.BigDecimal got = incomeRepo.sumMemberSubSourceTotal(m, f, church, s, e);
            assertThat(got == null ? java.math.BigDecimal.ZERO : got).isEqualByComparingTo(expected);
        }
    }
}
