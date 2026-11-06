package com.churchgeniuspro.integration;

import com.churchgeniuspro.ai.AiRateLimiter;
import com.churchgeniuspro.ai.audit.AiAuditService;
import com.churchgeniuspro.ai.consent.AiConsentGate;
import com.churchgeniuspro.ai.consent.AiVoiceTokens;
import com.churchgeniuspro.controller.AiConsentController;
import com.churchgeniuspro.repository.AiConsentRepository;
import com.churchgeniuspro.webfilter.AiGuardFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * App Store readiness D6 on PostgreSQL: the ai_consent table Hibernate creates matches the
 * production migration block (run twice); consent recorded through the real controller,
 * service and AiGuardFilter is per church AND per individual login; withdrawal and version
 * checks hold; the unique index refuses a second row; one-time voice tokens work end to end;
 * website requests never touch the table.
 */
@EnabledIf("com.churchgeniuspro.integration.AiConsentIT#databaseAvailable")
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = { "app.base-url=http://localhost" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("D6 — ai_consent on PostgreSQL")
class AiConsentIT {

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

    @Autowired AiConsentRepository repo;
    @Autowired AiConsentController controller;
    @Autowired AiConsentGate gate;
    @Autowired AiAuditService audit;
    @Autowired AiRateLimiter limiter;
    @Autowired JdbcTemplate jdbc;

    static final String A = "IT-D6-A-" + System.currentTimeMillis(), B = "IT-D6-B-" + System.currentTimeMillis();
    static final String APP_UA = "Mozilla/5.0 (iPhone) ChurchGeniusProApp/1.0 (ios)";

    static MockHttpSession staff(String church, int loginId) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "u" + loginId); s.setAttribute("role", "Admin"); s.setAttribute("appClientId", church);
        s.setAttribute("clientId", "USR-" + loginId); s.setAttribute("loginId", loginId);
        return s;
    }
    static MockHttpServletRequest req(String path, MockHttpSession s, String ua) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", path); r.setRequestURI(path); r.setSession(s);
        if (ua != null) r.addHeader("User-Agent", ua);
        return r;
    }
    int status(AiGuardFilter f, MockHttpServletRequest r, boolean[] reached) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(r, res, (rq, rs) -> reached[0] = true);
        return res.getStatus();
    }

    @Test @DisplayName("migration block twice; per church and per login; withdrawal; unique index; voice tokens; website never touches the table")
    void endToEnd() throws Exception {
        String sql = Files.readString(Path.of("scripts/sql/migrate_production.sql"));
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS ai_consent");
        assertThat(start).isPositive();
        String block = sql.substring(start);
        jdbc.execute(block); jdbc.execute(block);
        List<Map<String, Object>> cols = jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'ai_consent'");
        assertThat(cols).extracting(c -> c.get("column_name")).containsExactlyInAnyOrder("id", "app_client_id", "login_id", "login_kind",
                "notice_type", "notice_version", "agreed_at", "withdrawn_at", "source", "updated_at");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'ai_consent' AND indexname = 'ux_ai_consent_login_type'", Integer.class)).isEqualTo(1);

        AiGuardFilter f = new AiGuardFilter(audit, limiter, true);
        f.setConsentGate(gate);
        boolean[] reached = { false };

        // website: straight through, no row
        long rowsBefore = repo.count();
        assertThat(status(f, req("/api/ai/write", staff(A, 1), "Mozilla/5.0 Safari"), reached)).isEqualTo(200);
        assertThat(reached[0]).isTrue();
        assertThat(repo.count()).isEqualTo(rowsBefore);

        // app: refused until A/login 1 agrees
        reached[0] = false;
        assertThat(status(f, req("/api/ai/write", staff(A, 1), APP_UA), reached)).isEqualTo(403);
        assertThat(reached[0]).isFalse();
        assertThat(controller.agree(new HashMap<>(Map.of("type", "ASSISTANT", "version", 1)), req("/api/ai/consent", staff(A, 1), APP_UA))
                .getStatusCode().value()).isEqualTo(200);
        assertThat(status(f, req("/api/ai/write", staff(A, 1), APP_UA), reached)).isEqualTo(200);
        assertThat(reached[0]).isTrue();

        // same login id in church B, and another login of church A: still refused
        assertThat(status(f, req("/api/ai/write", staff(B, 1), APP_UA), new boolean[1])).isEqualTo(403);
        assertThat(status(f, req("/api/ai/write", staff(A, 2), APP_UA), new boolean[1])).isEqualTo(403);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM ai_consent WHERE app_client_id = ? AND login_id = 1", A);
        assertThat(row).containsEntry("notice_type", "ASSISTANT").containsEntry("notice_version", 1).containsEntry("login_kind", "STAFF")
                .containsEntry("source", "IOS");
        assertThat(row.get("withdrawn_at")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_consent WHERE app_client_id = ?", Integer.class, B)).isZero();

        // the unique index refuses a second row for the same church / login / notice
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ai_consent (app_client_id, login_id, login_kind, notice_type, notice_version, updated_at) "
                + "VALUES (?, 1, 'STAFF', 'ASSISTANT', 1, now())", A)).isInstanceOf(DataIntegrityViolationException.class);

        // an older stored version asks again
        jdbc.update("UPDATE ai_consent SET notice_version = 0 WHERE app_client_id = ? AND login_id = 1", A);
        assertThat(status(f, req("/api/ai/write", staff(A, 1), APP_UA), new boolean[1])).isEqualTo(403);
        controller.agree(new HashMap<>(Map.of("type", "ASSISTANT", "version", 1)), req("/api/ai/consent", staff(A, 1), APP_UA));
        assertThat(status(f, req("/api/ai/write", staff(A, 1), APP_UA), new boolean[1])).isEqualTo(200);

        // withdraw: refused at once, the row is kept with withdrawn_at
        controller.withdraw(new HashMap<>(Map.of("type", "all")), req("/api/ai/consent/withdraw", staff(A, 1), APP_UA));
        assertThat(status(f, req("/api/ai/write", staff(A, 1), APP_UA), new boolean[1])).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT withdrawn_at IS NOT NULL FROM ai_consent WHERE app_client_id = ? AND login_id = 1 AND notice_type = 'ASSISTANT'",
                Boolean.class, A)).isTrue();

        // voice: consent, a token from the real voice path, one follow-up, then a replay
        controller.agree(new HashMap<>(Map.of("type", "VOICE", "version", 1)), req("/api/ai/consent", staff(A, 3), APP_UA));
        MockHttpSession s3 = staff(A, 3);
        MockHttpServletRequest vc = req("/api/voice/command", s3, APP_UA);
        f.doFilter(vc, new MockHttpServletResponse(), (rq, rs) -> { });
        String token = gate.issueVoiceToken(vc, "home", "where is the tax report");
        assertThat(token).isNotNull();
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest fu = req("/api/ai-assist", s3, APP_UA);
            fu.setContent("{\"query\":\"Where is the tax report?\"}".getBytes(StandardCharsets.UTF_8));
            fu.addHeader(AiVoiceTokens.HEADER, token);
            boolean[] r = { false };
            int st = status(f, fu, r);
            if (i == 0) { assertThat(st).isEqualTo(200); assertThat(r[0]).isTrue(); }
            else { assertThat(st).as("replay").isEqualTo(403); assertThat(r[0]).isFalse(); }
        }

        // the table never holds text: only ids, types, versions and times
        assertThat(String.valueOf(jdbc.queryForList("SELECT * FROM ai_consent WHERE app_client_id = ?", A))).doesNotContain("tax report");
        jdbc.update("DELETE FROM ai_consent WHERE app_client_id IN (?, ?)", A, B);
    }
}
