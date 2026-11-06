package com.churchgeniuspro.integration;

import com.churchgeniuspro.service.ChurchDeletionService;
import com.churchgeniuspro.service.StaffAccountDeletionService;
import com.churchgeniuspro.service.TestDataService;
import com.churchgeniuspro.util.AppClock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * App Store D7 on PostgreSQL with two provisioned churches: a staff login deletes itself; the
 * church requests deletion, the e-mailed link confirms it, the Service Admin dry run counts what
 * goes, and the deletion removes every church-owned row of church A only — ChurchGenius LLC's
 * billing rows for A and the request record itself survive, church B is untouched.
 */
@EnabledIf("com.churchgeniuspro.integration.ChurchDeletionIT#databaseAvailable")
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = { "app.base-url=http://localhost" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("D7 church + staff deletion — PostgreSQL")
class ChurchDeletionIT {

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
    @Autowired ChurchDeletionService churchDeletion;
    @Autowired StaffAccountDeletionService staffDeletion;
    @Autowired JdbcTemplate jdbc;

    String churchA, churchB;
    List<Map<String, Object>> credsA, credsB;

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> provision(String church, String email) {
        Map<String, Object> out = testData.provisionTenant(new TestDataService.TenantSpec(church, "Deletion Church " + church,
                List.of("SuperAdmin", "Admin"), 2, TestDataService.TenantSpec.Contact.of("Pat", "Trial", email, "913-555-0100")),
                null, AppClock.today().plusDays(30));
        return (List<Map<String, Object>>) out.get("credentials");
    }

    static Map<String, Object> cred(List<Map<String, Object>> creds, String role) {
        return creds.stream().filter(c -> role.equals(c.get("role"))).findFirst().orElseThrow();
    }

    @BeforeAll
    void setUp() {
        long t = System.currentTimeMillis();
        churchA = TestDataService.TRIAL_CLIENT_PREFIX + t;
        churchB = TestDataService.TRIAL_CLIENT_PREFIX + (t + 1);
        credsA = provision(churchA, "a" + t + "@delete.test");
        credsB = provision(churchB, "b" + t + "@delete.test");
        // ChurchGenius LLC's own billing rows for church A — must survive the church's deletion
        MinimalRows.insert(jdbc, "billing_invoice", Map.of("id", "(SELECT COALESCE(MAX(id),0)+1000 FROM billing_invoice)",
                "client_id", "'" + churchA + "'", "stripe_payment_intent_id", "'pi_it_" + t + "'"));
        MinimalRows.insert(jdbc, "client_charge", Map.of("id", "(SELECT COALESCE(MAX(id),0)+1000 FROM client_charge)",
                "client_id", "'" + churchA + "'"));
    }

    int count(String table, String col, String cid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + col + " = ?", Integer.class, cid);
    }

    static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test @DisplayName("staff login deletes itself; the church's only SuperAdmin cannot")
    void staffLogin() {
        Map<String, Object> admin = cred(credsB, "Admin");
        Map<String, Object> row = jdbc.queryForMap("SELECT u.id, u.user_id FROM app_user u JOIN signup s ON s.client_id = u.user_id "
                + "WHERE s.username = ?", admin.get("username"));
        int appUserId = ((Number) row.get("id")).intValue();
        String ref = (String) row.get("user_id");
        assertThat(staffDeletion.deleteOwnLogin(ref, appUserId, churchB, "wrong").code()).isEqualTo("BAD_PASSWORD");
        assertThat(staffDeletion.deleteOwnLogin(ref, appUserId, churchA, (String) admin.get("password")).ok()).isFalse();   // other church
        assertThat(staffDeletion.deleteOwnLogin(ref, appUserId, churchB, (String) admin.get("password")).ok()).isTrue();
        Map<String, Object> after = jdbc.queryForMap("SELECT delete_flag, enabled, email, phone FROM app_user WHERE id = ?", appUserId);
        assertThat(after.get("delete_flag")).isEqualTo(true);
        assertThat(after.get("enabled")).isEqualTo(false);
        assertThat((String) after.get("email")).endsWith("@deleted.invalid");
        assertThat(after.get("phone")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM signup WHERE username = ?", Integer.class, admin.get("username"))).isZero();

        Map<String, Object> sa = cred(credsB, "SuperAdmin");
        Map<String, Object> saRow = jdbc.queryForMap("SELECT u.id, u.user_id FROM app_user u JOIN signup s ON s.client_id = u.user_id "
                + "WHERE s.username = ?", sa.get("username"));
        assertThat(staffDeletion.deleteOwnLogin((String) saRow.get("user_id"), ((Number) saRow.get("id")).intValue(), churchB,
                (String) sa.get("password")).code()).isEqualTo("LAST_SUPERADMIN");
    }

    @Test @DisplayName("request → confirm → dry run → delete: church A gone, billing + request kept, church B untouched")
    void wholeChurch() throws Exception {
        Map<String, Object> sa = cred(credsA, "SuperAdmin");
        Integer loginId = jdbc.queryForObject("SELECT id FROM signup WHERE username = ?", Integer.class, sa.get("username"));
        String churchName = jdbc.queryForObject("SELECT church_name FROM church_registration WHERE client_id = ?", String.class, churchA);
        int familiesB = count("family", "app_client_id", churchB), usersB = count("app_user", "client_id", churchB);
        assertThat(count("family", "app_client_id", churchA)).isPositive();

        ChurchDeletionService.Outcome req = churchDeletion.request(churchA, loginId, (String) sa.get("username"), "SuperAdmin",
                (String) sa.get("password"), churchName.toUpperCase());
        assertThat(req.ok()).as(req.message()).isTrue();
        Long id = req.request().getId();
        // the e-mailed token is not readable here; replace its hash with a known one
        jdbc.update("UPDATE church_deletion_request SET token_hash = ? WHERE id = ?", sha256("known-token"), id);
        assertThat(churchDeletion.execute(id, "service-admin:it", churchA).code()).isEqualTo("NOT_VERIFIED");
        assertThat(churchDeletion.verify("known-token").ok()).isTrue();
        assertThat(churchDeletion.verify("known-token").ok()).isFalse();

        Map<String, Integer> dry = churchDeletion.dryRun(id);
        assertThat(dry).containsKeys("family", "family_member", "app_user", "signup", "church_registration");
        assertThat(dry).doesNotContainKeys("billing_invoice", "client_charge", "service_client", "church_deletion_request");

        assertThat(churchDeletion.execute(id, "service-admin:it", churchB).code()).isEqualTo("CONFIRM");
        ChurchDeletionService.Outcome done = churchDeletion.execute(id, "service-admin:it", churchA);
        assertThat(done.ok()).as(done.message()).isTrue();
        int total = dry.values().stream().mapToInt(Integer::intValue).sum();
        assertThat(done.request().getDeletedRows()).isEqualTo(total);

        for (String[] t : new String[][]{{"family", "app_client_id"}, {"family_member", "app_client_id"}, {"app_user", "client_id"},
                {"church_registration", "client_id"}, {"income", "app_client_id"}}) {
            assertThat(count(t[0], t[1], churchA)).as(t[0]).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM signup WHERE username = ?", Integer.class, sa.get("username"))).isZero();
        assertThat(count("billing_invoice", "client_id", churchA)).isEqualTo(1);
        assertThat(count("client_charge", "client_id", churchA)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT delete_flag FROM service_client WHERE client_id = ?", Boolean.class, churchA)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM church_deletion_request WHERE id = ?", String.class, id)).isEqualTo("DELETED");

        assertThat(count("family", "app_client_id", churchB)).isEqualTo(familiesB);
        assertThat(count("app_user", "client_id", churchB)).isGreaterThanOrEqualTo(usersB - 1);   // B's Admin may be deleted by the other test
        assertThat(count("church_registration", "client_id", churchB)).isEqualTo(1);
        assertThat(churchDeletion.execute(id, "service-admin:it", churchA).code()).isEqualTo("NOT_VERIFIED");
    }
}
