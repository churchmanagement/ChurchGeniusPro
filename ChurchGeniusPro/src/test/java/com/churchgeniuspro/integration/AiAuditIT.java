package com.churchgeniuspro.integration;

import com.churchgeniuspro.ai.AiRateLimiter;
import com.churchgeniuspro.ai.audit.AiAuditService;
import com.churchgeniuspro.ai.llm.LlmGateway;
import com.churchgeniuspro.controller.AiActivityController;
import com.churchgeniuspro.repository.AiAuditLogRepository;
import com.churchgeniuspro.service.AuditTableRetentionService;
import com.churchgeniuspro.webfilter.AiGuardFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI Phase 0 on PostgreSQL: the audit table Hibernate creates matches the production
 * migration block (run twice), rows written through the real filter + service for two
 * churches never cross tenants on any read (page, keyset page, summary), the Service Admin
 * summary sees both, and the nightly purge removes only rows past the window.
 */
@EnabledIf("com.churchgeniuspro.integration.AiAuditIT#databaseAvailable")
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = { "app.base-url=http://localhost" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AI Phase 0 — ai_audit_log on PostgreSQL")
class AiAuditIT {

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

    @Autowired AiAuditLogRepository repo;
    @Autowired AiAuditService audit;
    @Autowired AiRateLimiter limiter;
    @Autowired LlmGateway gateway;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuditTableRetentionService retention;

    static final String A = "IT-AI-A-" + System.currentTimeMillis(), B = "IT-AI-B-" + System.currentTimeMillis();

    static MockHttpSession session(String clientId, String user, String role) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", user); s.setAttribute("role", role); s.setAttribute("appClientId", clientId); s.setAttribute("clientId", clientId);
        return s;
    }
    static MockHttpServletRequest req(String method, String path, MockHttpSession s) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path); r.setRequestURI(path); r.setSession(s); return r;
    }

    @SuppressWarnings("unchecked")
    @Test @DisplayName("filter writes rows per church; every read is tenant-scoped; Service Admin sees all; migration block idempotent; purge honours the window")
    void endToEnd() throws Exception {
        // the production migration block is safe before and after Hibernate created the table
        String sql = Files.readString(Path.of("scripts/sql/migrate_production.sql"));
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS ai_audit_log");
        assertThat(start).isPositive();
        String block = sql.substring(start);
        jdbc.execute(block); jdbc.execute(block);
        List<Map<String, Object>> cols = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'ai_audit_log'");
        assertThat(cols).extracting(c -> c.get("column_name")).contains("client_id", "actor", "channel", "intent", "model",
                "prompt_tokens", "completion_tokens", "http_status", "outcome", "duration_ms", "created_at");

        AiGuardFilter filter = new AiGuardFilter(audit, limiter, true);
        long before = repo.count();
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest r = req("POST", "/api/ai-search/data", session(A, "alice", "Admin"));
            filter.doFilter(r, new MockHttpServletResponse(), (rq, rs) -> {
                rq.setAttribute(AiAuditService.INTENT_ATTR, "crossref.lapsedGivers");
                rq.setAttribute(LlmGateway.USAGE_ATTR, new LlmGateway.Usage("chatJson", "gpt-4o-mini", 100, 20, 200, false));
            });
        }
        filter.doFilter(req("POST", "/api/voice/command", session(A, "alice", "Admin")), new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(req("POST", "/api/ai-search/data", session(B, "bob", "Admin")), new MockHttpServletResponse(),
                (rq, rs) -> rq.setAttribute("ai.denied", Boolean.TRUE));
        filter.doFilter(req("POST", "/api/help/assist", session(B, "temp:9", "User")), new MockHttpServletResponse(), new MockFilterChain());
        assertThat(repo.count() - before).isEqualTo(6);

        AiActivityController ctl = new AiActivityController(repo, limiter, gateway);
        ResponseEntity<?> pa = ctl.activity(10, null, null, req("GET", "/api/ai/activity", session(A, "alice", "Admin")));
        Map<String, Object> bodyA = (Map<String, Object>) pa.getBody();
        List<Map<String, Object>> rowsA = (List<Map<String, Object>>) bodyA.get("rows");
        assertThat(rowsA).hasSize(4);
        assertThat(rowsA).allSatisfy(row -> assertThat(row.get("actor")).isEqualTo("alice"));
        assertThat(rowsA).extracting(r -> r.get("channel")).containsExactly("VOICE", "DATA", "DATA", "DATA");
        assertThat(rowsA.get(1)).containsEntry("intent", "crossref.lapsedGivers").containsEntry("model", "gpt-4o-mini")
                .containsEntry("promptTokens", 100).containsEntry("outcome", "OK");
        assertThat(rowsA).noneSatisfy(row -> assertThat(String.valueOf(row)).contains("bob"));

        // keyset page: first 2, then the rest, no repeats
        Map<String, Object> p1 = (Map<String, Object>) ctl.activity(2, null, null, req("GET", "/api/ai/activity", session(A, "alice", "Admin"))).getBody();
        List<Map<String, Object>> r1 = (List<Map<String, Object>>) p1.get("rows");
        assertThat(r1).hasSize(2); assertThat(p1.get("hasMore")).isEqualTo(true);
        Map<String, Object> last = r1.get(1);
        Map<String, Object> p2 = (Map<String, Object>) ctl.activity(2, String.valueOf(last.get("createdAt")), ((Number) last.get("id")).longValue(),
                req("GET", "/api/ai/activity", session(A, "alice", "Admin"))).getBody();
        List<Map<String, Object>> r2 = (List<Map<String, Object>>) p2.get("rows");
        assertThat(r2).hasSize(2); assertThat(p2.get("hasMore")).isEqualTo(false);
        assertThat(r2).extracting(r -> r.get("id")).doesNotContainAnyElementsOf(r1.stream().map(r -> r.get("id")).toList());

        // church B sees only its own two rows, one of them a refusal
        Map<String, Object> bodyB = (Map<String, Object>) ctl.activity(10, null, null, req("GET", "/api/ai/activity", session(B, "bob", "Admin"))).getBody();
        List<Map<String, Object>> rowsB = (List<Map<String, Object>>) bodyB.get("rows");
        assertThat(rowsB).hasSize(2);
        assertThat(rowsB).extracting(r -> r.get("outcome")).containsExactlyInAnyOrder("DENIED", "OK");

        // summary per church
        Map<String, Object> sumA = (Map<String, Object>) ctl.summary(30, req("GET", "/api/ai/activity/summary", session(A, "alice", "Admin"))).getBody();
        assertThat(sumA).containsEntry("calls", 4L).containsEntry("tokens", 360L);
        Map<String, Object> sumB = (Map<String, Object>) ctl.summary(30, req("GET", "/api/ai/activity/summary", session(B, "bob", "Admin"))).getBody();
        assertThat(sumB).containsEntry("calls", 2L).containsEntry("tokens", 0L);

        // a User role (not admin) is refused; a church login allowed; no session 401
        assertThat(ctl.activity(10, null, null, req("GET", "/api/ai/activity", session(A, "u", "User"))).getStatusCode().value()).isEqualTo(403);
        MockHttpSession church = session(A, "church", "church"); church.setAttribute("church", Boolean.TRUE); church.removeAttribute("appClientId");
        assertThat(ctl.activity(10, null, null, req("GET", "/api/ai/activity", church)).getStatusCode().value()).isEqualTo(200);
        assertThat(ctl.activity(10, null, null, new MockHttpServletRequest("GET", "/api/ai/activity")).getStatusCode().value()).isEqualTo(401);

        // Service Admin cross-tenant summary
        MockHttpSession sa = new MockHttpSession(); sa.setAttribute("serviceAdminId", 1);
        Map<String, Object> all = (Map<String, Object>) ctl.serviceAdminSummary(30, req("GET", "/api/serviceadmin/ai/activity", sa)).getBody();
        List<Map<String, Object>> tenants = (List<Map<String, Object>>) all.get("tenants");
        assertThat(tenants).extracting(t -> t.get("clientId")).contains(A, B);
        assertThat(ctl.serviceAdminSummary(30, req("GET", "/api/serviceadmin/ai/activity", session(A, "alice", "Admin"))).getStatusCode().value()).isEqualTo(401);

        // retention: age church A's rows past the window; purge removes only those
        jdbc.update("UPDATE ai_audit_log SET created_at = created_at - INTERVAL '91 days' WHERE client_id = ?", A);
        Map<String, Integer> purged = retention.purgeNow();
        assertThat(purged.getOrDefault("ai_audit_log", 0)).isGreaterThanOrEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_audit_log WHERE client_id = ?", Long.class, A)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_audit_log WHERE client_id = ?", Long.class, B)).isEqualTo(2L);

        jdbc.update("DELETE FROM ai_audit_log WHERE client_id IN (?, ?)", A, B);
    }
}
