package com.churchgeniuspro.integration;

import com.churchgeniuspro.controller.LoginController;
import com.churchgeniuspro.service.FamilyService;
import com.churchgeniuspro.service.TestDataService;
import com.churchgeniuspro.util.AppClock;
import com.churchgeniuspro.util.KidsPortalEligibility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kids Portal 13+ rule (2026-11-04) on PostgreSQL with two provisioned churches: the
 * demo Kids Portal logins are 13+ and sign in; after the church record is changed the
 * same login is refused with the right reason and its stored sign-ins are revoked; the
 * family page reports why; another church's identical change has no effect here.
 */
@EnabledIf("com.churchgeniuspro.integration.KidsPortalAgeIT#databaseAvailable")
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = { "app.base-url=http://localhost" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Kids Portal 13+ — PostgreSQL")
class KidsPortalAgeIT {

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
    @Autowired LoginController login;
    @Autowired FamilyService families;
    @Autowired JdbcTemplate jdbc;

    String churchA, churchB;
    Map<String, Object> kidA, kidB;

    @SuppressWarnings("unchecked")
    Map<String, Object> provision(String church, String email) {
        Map<String, Object> out = testData.provisionTenant(new TestDataService.TenantSpec(church, "Kids Age Church", List.of("SuperAdmin"), 2,
                TestDataService.TenantSpec.Contact.of("Pat", "Trial", email, "913-555-0100")), null, AppClock.today().plusDays(30));
        List<Map<String, Object>> creds = (List<Map<String, Object>>) out.get("credentials");
        return creds.stream().filter(c -> TestDataService.ROLE_CHILD_PORTAL.equals(c.get("role"))).findFirst().orElseThrow();
    }

    @BeforeAll
    void setUp() {
        long t = System.currentTimeMillis();
        churchA = TestDataService.TRIAL_CLIENT_PREFIX + t;
        churchB = TestDataService.TRIAL_CLIENT_PREFIX + (t + 1);
        kidA = provision(churchA, "a" + t + "@kids.test");
        kidB = provision(churchB, "b" + t + "@kids.test");
    }

    @SuppressWarnings("unchecked")
    ResponseEntity<Map<String, Object>> signIn(Map<String, Object> cred, boolean remember) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/login");
        req.setRemoteAddr("10.9.8.7");
        return login.login(Map.of("username", cred.get("username"), "password", cred.get("password"), "rememberMe", remember),
                req, new MockHttpServletResponse());
    }

    int memberId(Map<String, Object> cred) { return ((Number) cred.get("memberId")).intValue(); }

    void setDob(Map<String, Object> cred, Integer y, Integer m, Integer d) {
        jdbc.update("UPDATE family_member SET birthday_year = ?, birthday_month = ?, birthday_day = ? WHERE id = ?", y, m, d, memberId(cred));
    }

    long liveTokens(Map<String, Object> cred) {
        return jdbc.queryForObject("SELECT count(*) FROM remember_me_token t JOIN signup s ON s.id = t.signup_id "
                + "WHERE s.username = ? AND t.revoked_at IS NULL", Long.class, cred.get("username"));
    }

    @Test @DisplayName("demo Kids Portal children are 13+, sign in, then are refused once the record says otherwise")
    void endToEnd() {
        Integer y = jdbc.queryForObject("SELECT birthday_year FROM family_member WHERE id = ?", Integer.class, memberId(kidA));
        Integer mo = jdbc.queryForObject("SELECT birthday_month FROM family_member WHERE id = ?", Integer.class, memberId(kidA));
        Integer d = jdbc.queryForObject("SELECT birthday_day FROM family_member WHERE id = ?", Integer.class, memberId(kidA));
        assertThat(KidsPortalEligibility.check("Child", y, mo, d, AppClock.today())).isEqualTo(KidsPortalEligibility.Status.OK);

        ResponseEntity<Map<String, Object>> ok = signIn(kidA, true);
        assertThat(ok.getStatusCode().value()).as("%s", ok.getBody()).isEqualTo(200);
        assertThat(liveTokens(kidA)).isEqualTo(1);

        // church office clears the year → refused for a missing date; the remembered sign-in is revoked
        setDob(kidA, null, mo, d);
        ResponseEntity<Map<String, Object>> noDob = signIn(kidA, true);
        assertThat(noDob.getStatusCode().value()).isEqualTo(403);
        assertThat(noDob.getBody()).containsEntry("message", KidsPortalEligibility.MISSING_DOB_MESSAGE);
        assertThat(liveTokens(kidA)).isZero();

        // a date showing the child is 12 → refused as under 13; family page shows why
        LocalDate twelve = AppClock.today().minusYears(12);
        setDob(kidA, twelve.getYear(), twelve.getMonthValue(), twelve.getDayOfMonth());
        assertThat(signIn(kidA, false).getBody()).containsEntry("message", KidsPortalEligibility.UNDER_13_KIDS_MESSAGE);
        Integer familyId = jdbc.queryForObject("SELECT family_id FROM family_member WHERE id = ?", Integer.class, memberId(kidA));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) families.getById(familyId, churchA).get("members");
        assertThat(rows).filteredOn(r -> Integer.valueOf(memberId(kidA)).equals(r.get("id")))
                .singleElement().satisfies(r -> assertThat(r.get("kidsPortalStatus")).isEqualTo("UNDER_13"));
        assertThat(rows).filteredOn(r -> !Integer.valueOf(memberId(kidA)).equals(r.get("id")))
                .allSatisfy(r -> assertThat(r.get("kidsPortalStatus")).isNull());

        // the other church's Kids Portal child is untouched by church A's change
        assertThat(signIn(kidB, false).getStatusCode().value()).isEqualTo(200);

        // 13th birthday today → allowed again
        LocalDate thirteen = AppClock.today().minusYears(13);
        setDob(kidA, thirteen.getYear(), thirteen.getMonthValue(), thirteen.getDayOfMonth());
        assertThat(signIn(kidA, false).getStatusCode().value()).isEqualTo(200);
    }
}
