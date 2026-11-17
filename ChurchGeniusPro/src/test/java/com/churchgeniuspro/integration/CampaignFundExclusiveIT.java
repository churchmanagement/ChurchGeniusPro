package com.churchgeniuspro.integration;

import com.churchgeniuspro.controller.PledgeController;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.IncomeService;
import com.churchgeniuspro.util.AppClock;
import org.junit.jupiter.api.*;
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

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-16 (items 1 + 8) — "Use this Fund exclusively for this Campaign" on PostgreSQL, two churches, through the
 * real Pledges controller and IncomeService. The user's example: target $1,000; Matthew Rosh pledges $500 and gives
 * $100; Danish Gosh (no pledge) gives $200 to the same fund → Total Collected $300 = 30%.
 */
@EnabledIf("com.churchgeniuspro.integration.CampaignFundExclusiveIT#databaseAvailable")
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = { "app.base-url=http://localhost" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Campaign-specific fund: totals, outside contributions, no double counting (PostgreSQL, two churches)")
class CampaignFundExclusiveIT {

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
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine"); POSTGRES.start();
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername); r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    static final String A = "CGP-CAMP-ITA-" + System.currentTimeMillis(), B = A.replace("ITA", "ITB");

    @Autowired PledgeController pledges;
    @Autowired IncomeService incomeService;
    @Autowired JdbcTemplate jdbc;
    @Autowired FamilyRepository familyRepo; @Autowired FamilyMemberRepository memberRepo;
    @Autowired MainSourceRepository mainRepo; @Autowired SubSourceRepository subRepo;
    @Autowired TransactionTypeRepository methodRepo;
    @Autowired com.churchgeniuspro.ai.action.AiPledgeTools.CreateCampaign aiCreateCampaign;

    Integer matthew, danish, third, building, tithe, check, campaign, plain, danishGift, matthewGift, matthewPledge;
    Integer buildingB, memberB, checkB;
    final LocalDate today = AppClock.today();

    FamilyMember member(String cid, String first, String last) {
        Family f = new Family(); f.setAppClientId(cid); f = familyRepo.save(f);
        FamilyMember m = new FamilyMember(); m.setFamily(f); m.setFirstName(first); m.setLastName(last); m.setRole("Head of Household"); m.setAppClientId(cid);
        return memberRepo.save(m);
    }
    SubSource fund(String cid, String main, String sub) {
        MainSource m = new MainSource(); m.setSourceName(main); m.setAppClientId(cid); m = mainRepo.save(m);
        SubSource s = new SubSource(); s.setSourceName(sub); s.setMainSource(m); s.setAppClientId(cid); return subRepo.save(s);
    }
    Integer method(String cid) { TransactionType t = new TransactionType(); t.setTypeName("Check"); t.setAppClientId(cid); return methodRepo.save(t).getId(); }

    @BeforeAll
    void seed() {
        matthew = member(A, "Matthew", "Rosh").getId(); danish = member(A, "Danish", "Gosh").getId(); third = member(A, "Third", "Giver").getId();
        building = fund(A, "New Building", "New Building Fund").getId(); tithe = fund(A, "General", "Tithe").getId(); check = method(A);
        memberB = member(B, "Other", "Church").getId(); buildingB = fund(B, "New Building", "New Building Fund").getId(); checkB = method(B);
    }

    MockHttpServletRequest staff(String cid) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.getSession(true).setAttribute("username", "grace.pastor"); r.getSession().setAttribute("role", "SuperAdmin");
        r.getSession().setAttribute("appClientId", cid); r.getSession().setAttribute("clientId", "USR-" + cid);
        return r;
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> totals(Integer campaignId, String cid) {
        List<Map<String, Object>> all = (List<Map<String, Object>>) pledges.listCampaigns(staff(cid)).getBody();
        return (Map<String, Object>) all.stream().filter(c -> campaignId.equals(c.get("id"))).findFirst().orElseThrow().get("totals");
    }
    static void money(Map<String, Object> t, String key, String expected) {
        assertThat((BigDecimal) t.get(key)).as(key).isEqualByComparingTo(expected);
    }
    Integer give(String cid, Integer memberId, String guest, Integer fundId, Integer methodId, String amount, LocalDate date) {
        return incomeService.createIncome(memberId, fundId, date, methodId, null, new BigDecimal(amount), null, guest, false, cid, "seed").getId();
    }
    @SuppressWarnings("unchecked")
    Integer newCampaign(String cid, String name, Integer fundId, boolean exclusive, String target) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name); body.put("subSourceId", fundId); body.put("fundExclusive", exclusive); body.put("targetAmount", target); body.put("status", "Active");
        ResponseEntity<?> r = pledges.createCampaign(body, staff(cid));
        assertThat(r.getStatusCode().value()).as(String.valueOf(r.getBody())).isEqualTo(200);
        return (Integer) ((Map<String, Object>) r.getBody()).get("id");
    }

    @Test @Order(1) @DisplayName("the user's example: $500 pledged, $100 from the pledger, $200 outside → $300 collected, 30% of $1,000")
    @SuppressWarnings("unchecked")
    void example() throws Exception {
        String v26 = Files.readString(Path.of("src/main/resources/db/migration/V26__pledge_campaign_fund_exclusive.sql"));
        jdbc.execute(v26); jdbc.execute(v26);

        campaign = newCampaign(A, "New Building 2026", building, true, "1000");
        ResponseEntity<?> p = pledges.addPledge(campaign, new LinkedHashMap<>(Map.of("familyMemberId", matthew, "pledgeAmount", "500")), staff(A));
        matthewPledge = (Integer) ((Map<String, Object>) p.getBody()).get("id");
        matthewGift = give(A, matthew, null, building, check, "100.00", today);
        danishGift  = give(A, danish,  null, building, check, "200.00", today);

        Map<String, Object> t = totals(campaign, A);
        assertThat(t).containsEntry("fundExclusive", true).containsEntry("pledgers", 1);
        money(t, "pledged", "500"); money(t, "collectedFromPledgers", "100"); money(t, "pending", "400");
        money(t, "outsideContributions", "200"); money(t, "totalCollected", "300"); money(t, "collected", "300");
        money(t, "percent", "30.0"); money(t, "remainingTarget", "700");

        List<Map<String, Object>> outside = (List<Map<String, Object>>) pledges.outsideContributions(campaign, staff(A)).getBody();
        assertThat(outside).hasSize(1);
        assertThat(outside.get(0)).containsEntry("contributor", "Danish Gosh").containsEntry("incomeId", danishGift);
        // the pledger's own row is still his $100
        List<Map<String, Object>> rows = (List<Map<String, Object>>) pledges.listPledges(campaign, staff(A)).getBody();
        assertThat((BigDecimal) rows.get(0).get("amountCollected")).isEqualByComparingTo("100");
    }

    @Test @Order(2) @DisplayName("what does NOT count: other funds, other churches, deleted income, gifts before the campaign started")
    void whatDoesNotCount() {
        give(A, danish, null, tithe, check, "999.00", today);                         // another fund
        give(B, memberB, null, buildingB, checkB, "5000.00", today);                  // another church, same fund name
        Integer gone = give(A, third, null, building, check, "77.00", today);
        incomeService.deleteIncome(gone, A);                          // deleted
        give(A, third, null, building, check, "40.00", today.minusDays(30));          // before the campaign was created
        Map<String, Object> t = totals(campaign, A);
        money(t, "totalCollected", "300"); money(t, "outsideContributions", "200"); money(t, "collectedFromPledgers", "100");
    }

    @Test @Order(3) @DisplayName("guests count as outside contributions; totals follow income edits, moves and deletes at once")
    @SuppressWarnings("unchecked")
    void recalculates() {
        Integer guest = give(A, null, "Walk-in Visitor", building, check, "50.00", today);
        money(totals(campaign, A), "outsideContributions", "250"); money(totals(campaign, A), "totalCollected", "350");
        assertThat((List<Map<String, Object>>) pledges.outsideContributions(campaign, staff(A)).getBody())
                .extracting(m -> m.get("contributor")).containsExactlyInAnyOrder("Danish Gosh", "Walk-in Visitor");

        incomeService.updateIncome(danishGift, danish, building, today, check, null, new BigDecimal("300.00"), null, null, false, A, "u");   // amount edited
        money(totals(campaign, A), "outsideContributions", "350"); money(totals(campaign, A), "totalCollected", "450");
        incomeService.updateIncome(guest, null, tithe, today, check, null, new BigDecimal("50.00"), null, "Walk-in Visitor", false, A, "u");  // moved to another fund
        money(totals(campaign, A), "outsideContributions", "300"); money(totals(campaign, A), "totalCollected", "400");
        incomeService.updateIncome(danishGift, matthew, building, today, check, null, new BigDecimal("300.00"), null, null, false, A, "u");   // reassigned to the pledger
        Map<String, Object> t = totals(campaign, A);
        money(t, "collectedFromPledgers", "400"); money(t, "outsideContributions", "0"); money(t, "totalCollected", "400"); money(t, "pending", "100");
        incomeService.updateIncome(danishGift, danish, building, today, check, null, new BigDecimal("200.00"), null, null, false, A, "u");    // back to the example
        money(totals(campaign, A), "totalCollected", "300");
    }

    @Test @Order(4) @DisplayName("no double counting: two pledges by one member; a second pledger's gifts move from outside to pledgers; over-payment never makes pending negative")
    @SuppressWarnings("unchecked")
    void noDoubleCounting() {
        ResponseEntity<?> second = pledges.addPledge(campaign, new LinkedHashMap<>(Map.of("familyMemberId", matthew, "pledgeAmount", "100")), staff(A));
        Integer secondId = (Integer) ((Map<String, Object>) second.getBody()).get("id");
        Map<String, Object> t = totals(campaign, A);
        money(t, "pledged", "600"); money(t, "collectedFromPledgers", "100"); money(t, "totalCollected", "300"); money(t, "outsideContributions", "200");
        pledges.deletePledge(secondId, staff(A));

        ResponseEntity<?> dp = pledges.addPledge(campaign, new LinkedHashMap<>(Map.of("familyMemberId", danish, "pledgeAmount", "150")), staff(A));
        t = totals(campaign, A);
        money(t, "pledged", "650"); money(t, "collectedFromPledgers", "300"); money(t, "outsideContributions", "0"); money(t, "totalCollected", "300");
        money(t, "pending", "350");
        pledges.deletePledge((Integer) ((Map<String, Object>) dp.getBody()).get("id"), staff(A));
        t = totals(campaign, A);
        money(t, "collectedFromPledgers", "100"); money(t, "outsideContributions", "200"); money(t, "totalCollected", "300");

        // every dollar is in exactly one bucket, and the sum equals the ledger
        BigDecimal ledger = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM income WHERE app_client_id = ? AND sub_source_id = ? AND delete_flag = false AND income_date >= ?",
                BigDecimal.class, A, building, java.sql.Date.valueOf(today));
        assertThat(((BigDecimal) t.get("collectedFromPledgers")).add((BigDecimal) t.get("outsideContributions"))).isEqualByComparingTo(ledger);
    }

    @Test @Order(5) @DisplayName("backward compatible: with the option off the same data is calculated as before — pledgers' gifts only")
    @SuppressWarnings("unchecked")
    void optionOff() {
        Map<String, Object> off = new LinkedHashMap<>(); off.put("fundExclusive", false);
        assertThat(pledges.updateCampaign(campaign, off, staff(A)).getStatusCode().value()).isEqualTo(200);
        Map<String, Object> t = totals(campaign, A);
        assertThat(t).containsEntry("fundExclusive", false).doesNotContainKeys("outsideContributions", "totalCollected", "collectedFromPledgers");
        money(t, "pledged", "500"); money(t, "collected", "100"); money(t, "pending", "400"); money(t, "percent", "10.0");
        assertThat((List<?>) pledges.outsideContributions(campaign, staff(A)).getBody()).isEmpty();
        Map<String, Object> on = new LinkedHashMap<>(); on.put("fundExclusive", true);
        assertThat(pledges.updateCampaign(campaign, on, staff(A)).getStatusCode().value()).isEqualTo(200);
        money(totals(campaign, A), "totalCollected", "300");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pledge_campaign WHERE client_id = ? AND fund_exclusive = true", Integer.class, B)).isZero();
    }

    @Test @Order(6) @DisplayName("one active campaign per fund: a second one is refused both ways, other edits are not held up, closing frees the fund")
    void onePerFund() {
        Map<String, Object> second = new LinkedHashMap<>(Map.of("name", "Second drive", "subSourceId", building, "status", "Active"));
        ResponseEntity<?> no = pledges.createCampaign(second, staff(A));
        assertThat(no.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(no.getBody())).contains("used exclusively by the active campaign").contains("New Building 2026");
        assertThat(aiCreateCampaign.resolve(new LinkedHashMap<>(Map.of("name", "AI drive", "fundId", building)), A, "u").refusal())
                .contains("used exclusively");

        // an ordinary campaign on another fund, then trying to make it exclusive on the taken fund
        plain = newCampaign(A, "Tithe drive", tithe, false, "100");
        Map<String, Object> grab = new LinkedHashMap<>(Map.of("subSourceId", building, "fundExclusive", true));
        assertThat(pledges.updateCampaign(plain, grab, staff(A)).getStatusCode().value()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT sub_source_id FROM pledge_campaign WHERE id = ?", Integer.class, plain)).as("refused change left it untouched").isEqualTo(tithe);
        // renaming the exclusive campaign is never blocked by the rule
        assertThat(pledges.updateCampaign(campaign, new LinkedHashMap<>(Map.of("name", "New Building 2026 (renamed)")), staff(A)).getStatusCode().value()).isEqualTo(200);
        // exclusive without a fund
        assertThat(pledges.createCampaign(new LinkedHashMap<>(Map.of("name", "No fund", "fundExclusive", true)), staff(A)).getStatusCode().value()).isEqualTo(409);
        // church B is free to use its own same-named fund exclusively
        newCampaign(B, "B building", buildingB, true, "100");
        money(totals(jdbc.queryForObject("SELECT id FROM pledge_campaign WHERE client_id = ?", Integer.class, B), B), "totalCollected", "5000");
        // closing the first campaign frees the fund
        assertThat(pledges.updateCampaign(campaign, new LinkedHashMap<>(Map.of("status", "Closed")), staff(A)).getStatusCode().value()).isEqualTo(200);
        assertThat(pledges.createCampaign(second, staff(A)).getStatusCode().value()).isEqualTo(200);
        // …and it cannot be re-opened while the new one is active on the fund
        assertThat(pledges.updateCampaign(campaign, new LinkedHashMap<>(Map.of("status", "Active")), staff(A)).getStatusCode().value()).isEqualTo(409);
        // church B cannot read church A's outside contributions
        assertThat(pledges.outsideContributions(campaign, staff(B)).getStatusCode().value()).isEqualTo(404);
    }
}
