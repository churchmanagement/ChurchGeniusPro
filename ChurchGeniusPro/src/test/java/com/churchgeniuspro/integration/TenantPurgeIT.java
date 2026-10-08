package com.churchgeniuspro.integration;

import com.churchgeniuspro.service.TestDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database audit M7: proves the demo/trial purge against a real PostgreSQL carrying the
 * <b>full W3 foreign-key graph</b> (112 {@code ON DELETE RESTRICT} keys). This is the
 * end-to-end guarantee that {@code TestDataService.clearManagedTenant} — now built by
 * {@link com.churchgeniuspro.service.TenantPurgePlanner} — removes an entire tenant in
 * child-before-parent order without any RESTRICT key blocking it, and touches no other
 * tenant.
 *
 * <p>Two demo tenants are seeded across the awkward shapes (the two-level indirect worship
 * chain, the self-referential {@code member_message}, {@code signup}'s three overloaded
 * logins, and the excluded {@code demo_deletion_audit}); one is purged; the other must be
 * byte-for-byte intact. Requires Docker; runs on {@code verify}.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TenantPurgeIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate jdbc;
    @Autowired TestDataService testData;

    private static final List<String> SEED_TABLES = List.of(
        "service_client","church_registration","app_user","family_member","signup","demo_role_access",
        "church_event","church_event_day","worship_group","worship_instrument","worship_group_member",
        "member_message","user_permissions","demo_deletion_audit");

    @BeforeEach
    void setUp() throws Exception {
        // Add the W3 foreign keys so the purge must satisfy the real RESTRICT graph.
        String w3 = new String(new ClassPathResource("db/window/W3_add_foreign_keys.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        jdbc.execute(w3);

        // Relax NOT NULL only on the tables we hand-seed (FKs stay active, so the purge
        // order is still exercised), then seed two tenants across the awkward shapes.
        jdbc.queryForList(
            "SELECT 'ALTER TABLE '||quote_ident(table_name)||' ALTER COLUMN '||quote_ident(column_name)||' DROP NOT NULL' " +
            "FROM information_schema.columns WHERE table_schema='public' AND is_nullable='NO' " +
            "AND column_name <> 'id' AND table_name IN (" +
            "'service_client','church_registration','app_user','family_member','signup','demo_role_access'," +
            "'church_event','church_event_day','worship_group','worship_instrument','worship_group_member'," +
            "'member_message','user_permissions','demo_deletion_audit')", String.class)
            .forEach(jdbc::execute);

        exec("INSERT INTO service_client(id,client_id) VALUES (1,'DEMO-A'),(2,'DEMO-B')");
        exec("INSERT INTO church_registration(id,client_id,church_name) VALUES (1,'DEMO-A','A'),(2,'DEMO-B','B')");
        exec("INSERT INTO app_user(id,client_id,user_id) VALUES (1,'DEMO-A','USR-A'),(2,'DEMO-B','USR-B')");
        exec("INSERT INTO family_member(id,app_client_id,member_ref) VALUES (1,'DEMO-A','MBR-A'),(2,'DEMO-B','MBR-B')");
        exec("INSERT INTO signup(id,client_id,church) VALUES " +
             "(1,'DEMO-A',true),(2,'USR-A',false),(3,'MBR-A',false)," +
             "(101,'DEMO-B',true),(102,'USR-B',false),(103,'MBR-B',false)");
        exec("INSERT INTO demo_role_access(id,client_id,signup_id) VALUES (1,'DEMO-A',1),(101,'DEMO-B',101)");
        exec("INSERT INTO church_event(id,app_client_id) VALUES (1,'DEMO-A'),(101,'DEMO-B')");
        exec("INSERT INTO church_event_day(id,event_id) VALUES (1,1),(101,101)");
        exec("INSERT INTO worship_group(id,client_id) VALUES (1,'DEMO-A'),(101,'DEMO-B')");
        exec("INSERT INTO worship_instrument(id,group_id) VALUES (1,1),(101,101)");
        exec("INSERT INTO worship_group_member(id,instrument_id) VALUES (1,1),(101,101)");
        exec("INSERT INTO member_message(id,app_client_id,parent_id) VALUES (1,'DEMO-A',NULL),(2,'DEMO-A',1),(101,'DEMO-B',NULL),(102,'DEMO-B',101)");
        exec("INSERT INTO user_permissions(id,app_user_id) VALUES (1,1),(101,2)");
        exec("INSERT INTO demo_deletion_audit(id,client_id,app_client_id) VALUES (1,'DEMO-A','DEMO-A'),(101,'DEMO-B','DEMO-B')");
    }

    @Test
    @DisplayName("purging one tenant removes it entirely (past RESTRICT keys) and leaves the other untouched")
    void purgeRemovesTenantAndIsolatesOthers() {
        Map<String, Object> result = testData.clearManagedTenant("DEMO-A", "it");
        assertThat(result).containsEntry("clientId", "DEMO-A");

        // Tenant A is entirely gone — including indirect chains, self-ref, and all 3 signup shapes.
        assertThat(count("service_client",       "client_id='DEMO-A'")).isZero();
        assertThat(count("church_registration",  "client_id='DEMO-A'")).isZero();
        assertThat(count("app_user",             "client_id='DEMO-A'")).isZero();
        assertThat(count("family_member",        "app_client_id='DEMO-A'")).isZero();
        assertThat(count("signup",               "client_id IN ('DEMO-A','USR-A','MBR-A')")).isZero();
        assertThat(count("demo_role_access",     "client_id='DEMO-A'")).isZero();
        assertThat(count("church_event",         "app_client_id='DEMO-A'")).isZero();
        assertThat(count("church_event_day",     "id=1")).isZero();
        assertThat(count("worship_group",        "client_id='DEMO-A'")).isZero();
        assertThat(count("worship_instrument",   "id=1")).isZero();
        assertThat(count("worship_group_member", "id=1")).isZero();
        assertThat(count("member_message",       "app_client_id='DEMO-A'")).isZero();
        assertThat(count("user_permissions",     "id=1")).isZero();

        // Tenant B is completely intact.
        assertThat(count("service_client",       "client_id='DEMO-B'")).isEqualTo(1);
        assertThat(count("church_registration",  "client_id='DEMO-B'")).isEqualTo(1);
        assertThat(count("app_user",             "client_id='DEMO-B'")).isEqualTo(1);
        assertThat(count("family_member",        "app_client_id='DEMO-B'")).isEqualTo(1);
        assertThat(count("signup",               "client_id IN ('DEMO-B','USR-B','MBR-B')")).isEqualTo(3);
        assertThat(count("demo_role_access",     "client_id='DEMO-B'")).isEqualTo(1);
        assertThat(count("church_event",         "app_client_id='DEMO-B'")).isEqualTo(1);
        assertThat(count("church_event_day",     "id=101")).isEqualTo(1);
        assertThat(count("worship_group_member", "id=101")).isEqualTo(1);
        assertThat(count("member_message",       "app_client_id='DEMO-B'")).isEqualTo(2);
        assertThat(count("user_permissions",     "id=101")).isEqualTo(1);

        // The deletion's own audit record is deliberately kept for both tenants.
        assertThat(count("demo_deletion_audit",  "client_id='DEMO-A'")).isEqualTo(1);
        assertThat(count("demo_deletion_audit",  "client_id='DEMO-B'")).isEqualTo(1);

        // Backstop: no tenant-column table (bar the excluded audit) holds a DEMO-A row.
        List<Map<String, Object>> cols = jdbc.queryForList(
            "SELECT table_name, column_name FROM information_schema.columns " +
            "WHERE table_schema='public' AND column_name IN ('client_id','app_client_id') " +
            "AND table_name <> 'demo_deletion_audit'");
        for (Map<String, Object> c : cols) {
            String table = (String) c.get("table_name"), col = (String) c.get("column_name");
            assertThat(count(table, col + "='DEMO-A'"))
                .as("no DEMO-A rows should remain in %s.%s", table, col).isZero();
        }
    }

    private void exec(String sql) { jdbc.execute(sql); }

    private long count(String table, String where) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + where, Long.class);
        return n == null ? -1 : n;
    }
}
