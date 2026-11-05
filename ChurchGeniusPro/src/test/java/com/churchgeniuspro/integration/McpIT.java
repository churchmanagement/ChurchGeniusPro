package com.churchgeniuspro.integration;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.mcp.PersonalAccessTokenService;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.webfilter.ApiTokenFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI Phase 5 on PostgreSQL with two churches, through the REAL filter and controller (MockMvc on the full
 * context): a token from church A sees only A's people and saved lists, B's ids are refused, contact details
 * are masked without the scope, a revoked token is 401 on the next request, the church switch refuses every
 * token, an actions token proposes and confirms a real write, every call leaves an ai_audit_log row with
 * channel MCP, and the migration block runs twice.
 */
@EnabledIf("com.churchgeniuspro.integration.McpIT#databaseAvailable")
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = { "app.base-url=http://localhost", "ai.actions.llm-fallback=false", "mcp.rate.per-token-per-minute=0" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AI Phase 5 — MCP on PostgreSQL (two churches)")
class McpIT {

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

    static final String A = "CGP-MCP-ITA-" + System.currentTimeMillis(), B = A.replace("ITA", "ITB");
    static final ObjectMapper M = new ObjectMapper();

    @Autowired WebApplicationContext ctx;
    @Autowired FilterRegistrationBean<ApiTokenFilter> apiTokenFilter;
    @Autowired PersonalAccessTokenService tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired FamilyRepository familyRepo; @Autowired FamilyMemberRepository memberRepo; @Autowired GroupRepository groupRepo;
    @Autowired AppUserRepository userRepo; @Autowired AiSavedPeopleListRepository listRepo;

    MockMvc mvc;
    AppUser adminA, adminB; Integer sarahA, sarahB, welcomeA; Long listA, listB;
    String tokA, tokAContact, tokAActions, tokB;

    @BeforeAll
    void seed() {
        mvc = MockMvcBuilders.webAppContextSetup(ctx).addFilter(apiTokenFilter.getFilter(), "/mcp").build();
        for (String cid : List.of(A, B)) {
            Family f = new Family(); f.setAppClientId(cid); f = familyRepo.save(f);
            FamilyMember sarah = new FamilyMember(); sarah.setFamily(f); sarah.setFirstName("Sarah"); sarah.setLastName("Lee"); sarah.setRole("Adult"); sarah.setGender("Female");
            sarah.setEmail("sarah." + cid.toLowerCase() + "@example.test"); sarah.setPhone("(913) 555-0" + (cid.equals(A) ? "100" : "200")); sarah = memberRepo.save(sarah);
            Group g = new Group(); g.setAppClientId(cid); g.setGroupName("Welcome Team"); g = groupRepo.save(g);
            AppUser u = new AppUser(); u.setUserId("STF-" + cid); u.setClientId(cid); u.setRole("Admin"); u.setFirstName("Pat"); u.setLastName(cid); u.setEmail("pat." + cid.toLowerCase() + "@example.test"); u = userRepo.save(u);
            AiSavedPeopleList l = new AiSavedPeopleList(); l.setAppClientId(cid); l.setName("Adults"); l.setFilterJson("[{\"kind\":\"Role\",\"role\":\"Adult\"}]"); l.setCreatedBy("pat"); l = listRepo.save(l);
            tokens.setTenantEnabled(cid, true, "it");
            if (cid.equals(A)) { sarahA = sarah.getId(); welcomeA = g.getId(); adminA = u; listA = l.getId(); }
            else { sarahB = sarah.getId(); adminB = u; listB = l.getId(); }
        }
        Family fb = familyRepo.findAll().stream().filter(x -> B.equals(x.getAppClientId())).findFirst().get();
        FamilyMember john = new FamilyMember(); john.setFamily(fb); john.setFirstName("John"); john.setLastName("Moe"); john.setRole("Adult"); memberRepo.save(john);
        tokA = tokens.create(A, adminA, "pat-" + A, "Claude A", List.of(), null).secret();
        tokAContact = tokens.create(A, adminA, "pat-" + A, "Claude A contact", List.of("contact"), null).secret();
        tokAActions = tokens.create(A, adminA, "pat-" + A, "Ops A", List.of("actions"), null).secret();
        tokB = tokens.create(B, adminB, "pat-" + B, "Claude B", List.of("contact"), null).secret();
    }

    MockHttpServletRequestBuilder rpc(String token, String json) {
        MockHttpServletRequestBuilder b = post("/mcp").contentType("application/json").accept("application/json").content(json);
        return token == null ? b : b.header("Authorization", "Bearer " + token);
    }
    static String call(String tool, String args) { return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + args + "}}"; }
    @SuppressWarnings("unchecked") Map<String, Object> body(MvcResult r) throws Exception { return M.readValue(r.getResponse().getContentAsString(), LinkedHashMap.class); }
    @SuppressWarnings("unchecked") Map<String, Object> structured(MvcResult r) throws Exception { return (Map<String, Object>) ((Map<String, Object>) body(r).get("result")).get("structuredContent"); }

    @Test @DisplayName("tenant from the token: A's token sees A's people only, B's ids are not found, contact details masked without the scope")
    void tenancy() throws Exception {
        // tools/list through the real filter
        MvcResult r = mvc.perform(rpc(tokA, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getContentAsString()).contains("ask_church_data").doesNotContain("propose_action");
        // search_people: A has one adult (Sarah), B has two
        r = mvc.perform(rpc(tokA, call("search_people", "{\"predicates\":[{\"kind\":\"Role\",\"role\":\"Adult\"},{\"kind\":\"HasContact\",\"negated\":false,\"what\":\"email\"}]}"))).andExpect(status().isOk()).andReturn();
        Map<String, Object> s = structured(r);
        assertThat(((Map<String, Object>) body(r).get("result")).get("isError")).isEqualTo(false);
        assertThat(((Number) s.get("total")).intValue()).isEqualTo(1);
        String row = String.valueOf(((List<?>) s.get("rows")).get(0));
        assertThat(row).contains("Sarah").contains("***");
        assertThat(row).doesNotContain("sarah." + A.toLowerCase() + "@example.test").doesNotContain("555-0100");   // masked
        assertThat(((Number) ((List<?>) s.get("memberIds")).get(0)).intValue()).isEqualTo(sarahA);
        r = mvc.perform(rpc(tokAContact, call("search_people", "{\"predicates\":[{\"kind\":\"Role\",\"role\":\"Adult\"},{\"kind\":\"HasContact\",\"negated\":false,\"what\":\"email\"}]}"))).andReturn();
        assertThat(String.valueOf(((List<?>) structured(r).get("rows")).get(0))).contains("sarah." + A.toLowerCase() + "@example.test");
        r = mvc.perform(rpc(tokB, call("search_people", "{\"predicates\":[{\"kind\":\"Role\",\"role\":\"Adult\"}]}"))).andReturn();
        s = structured(r);
        assertThat(((Number) s.get("total")).intValue()).isEqualTo(2);
        assertThat(String.valueOf(s)).contains("John").doesNotContain(String.valueOf(sarahA) + "\"") ;
        assertThat(((List<?>) s.get("memberIds")).stream().map(x -> ((Number) x).intValue()).toList()).contains(sarahB).doesNotContain(sarahA);
        // ask_church_data goes through the whole assistant route for the token's church
        r = mvc.perform(rpc(tokB, call("ask_church_data", "{\"question\":\"list all women\"}"))).andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getContentAsString()).contains("Sarah").doesNotContain("\"" + sarahA + "\"");
        // saved lists: A sees A's list, B's id does not exist for A
        r = mvc.perform(rpc(tokA, call("list_saved_lists", "{}"))).andReturn();
        List<Map<String, Object>> lists = (List<Map<String, Object>>) structured(r).get("lists");
        assertThat(lists).extracting(l -> ((Number) l.get("id")).longValue()).containsExactly(listA);
        r = mvc.perform(rpc(tokA, call("run_saved_list", "{\"id\":" + listB + "}"))).andReturn();
        assertThat(((Map<String, Object>) body(r).get("result")).get("isError")).isEqualTo(true);
        assertThat(String.valueOf(structured(r).get("error"))).contains("not found");
        r = mvc.perform(rpc(tokA, call("run_saved_list", "{\"id\":" + listA + "}"))).andReturn();
        assertThat(((Number) structured(r).get("total")).intValue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_count FROM ai_saved_people_list WHERE id = ?", Integer.class, listA)).isEqualTo(1);
    }

    @Test @DisplayName("token lifecycle through the filter: no token 401, revoked 401 on the next request, church switch off 403 for every token, back on works")
    void lifecycle() throws Exception {
        mvc.perform(rpc(null, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).andExpect(status().isUnauthorized());
        mvc.perform(rpc("cgp_not-a-real-token-value-here", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).andExpect(status().isUnauthorized());
        String temp = tokens.create(A, adminA, "pat-" + A, "temp", List.of(), 1).secret();
        mvc.perform(rpc(temp, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).andExpect(status().isOk());
        Long id = jdbc.queryForObject("SELECT id FROM personal_access_token WHERE token_hash = ?", Long.class, PersonalAccessTokenService.sha256Hex(temp));
        assertThat(jdbc.queryForObject("SELECT last_used_at FROM personal_access_token WHERE id = ?", java.sql.Timestamp.class, id)).isNotNull();
        assertThat(tokens.revoke(A, id, adminA.getId(), true, "pat")).isTrue();
        MvcResult r = mvc.perform(rpc(temp, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).andExpect(status().isUnauthorized()).andReturn();
        assertThat(r.getResponse().getContentAsString()).contains("TOKEN_REVOKED");
        // the secret is nowhere in the table
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM personal_access_token WHERE token_hash = ? OR token_prefix = ?", Long.class, temp, temp)).isZero();
        // church switch
        tokens.setTenantEnabled(A, false, "it");
        r = mvc.perform(rpc(tokA, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).andExpect(status().isForbidden()).andReturn();
        assertThat(r.getResponse().getContentAsString()).contains("TENANT_OFF");
        mvc.perform(rpc(tokB, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).andExpect(status().isOk());   // B unaffected
        tokens.setTenantEnabled(A, true, "it");
        mvc.perform(rpc(tokA, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).andExpect(status().isOk());
        // B's admin cannot revoke A's token
        Long aId = jdbc.queryForObject("SELECT id FROM personal_access_token WHERE token_hash = ?", Long.class, PersonalAccessTokenService.sha256Hex(tokA));
        assertThat(tokens.revoke(B, aId, adminB.getId(), false, "pat-b")).isFalse();
        assertThat(jdbc.queryForObject("SELECT revoked_at FROM personal_access_token WHERE id = ?", java.sql.Timestamp.class, aId)).isNull();
    }

    @Test @DisplayName("actions scope: propose → confirm writes one group_member row in A through the agent; a read token cannot see the tool")
    void actions() throws Exception {
        MvcResult r = mvc.perform(rpc(tokA, call("propose_action", "{\"tool\":\"add_group_member\",\"params\":{}}"))).andExpect(status().isOk()).andReturn();
        assertThat(((Map<String, Object>) body(r).get("error")).get("message")).asString().contains("Unknown tool");
        r = mvc.perform(rpc(tokAActions, call("list_actions", "{}"))).andReturn();
        assertThat(String.valueOf(structured(r).get("actions"))).contains("add_group_member");
        r = mvc.perform(rpc(tokAActions, call("propose_action", "{\"tool\":\"add_group_member\",\"params\":{\"member\":\"Sarah Lee\",\"group\":\"Welcome Team\"}}"))).andReturn();
        Map<String, Object> prop = structured(r);
        assertThat(prop.get("intent")).isEqualTo("action.confirm");
        String token = String.valueOf(((Map<String, Object>) prop.get("confirm")).get("token"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM group_member WHERE app_client_id = ?", Long.class, A)).isZero();
        assertThat(jdbc.queryForObject("SELECT source FROM ai_action_proposal WHERE token = ?", String.class, token)).isEqualTo("MCP");
        // B's actions token cannot confirm A's proposal
        String tokBActions = tokens.create(B, adminB, "pat-" + B, "Ops B", List.of("actions"), null).secret();
        r = mvc.perform(rpc(tokBActions, call("confirm_action", "{\"token\":\"" + token + "\"}"))).andReturn();
        assertThat(((Map<String, Object>) body(r).get("result")).get("isError")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM group_member WHERE app_client_id = ?", Long.class, A)).isZero();
        r = mvc.perform(rpc(tokAActions, call("confirm_action", "{\"token\":\"" + token + "\"}"))).andReturn();
        assertThat(structured(r).get("intent")).isEqualTo("action.done");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT group_id, family_member_id FROM group_member WHERE app_client_id = ?", A);
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0).get("group_id")).intValue()).isEqualTo(welcomeA);
        assertThat(((Number) rows.get(0).get("family_member_id")).intValue()).isEqualTo(sarahA);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM group_member WHERE app_client_id = ?", Long.class, B)).isZero();
        // a second confirm of the same token does nothing
        r = mvc.perform(rpc(tokAActions, call("confirm_action", "{\"token\":\"" + token + "\"}"))).andReturn();
        assertThat(((Map<String, Object>) body(r).get("result")).get("isError")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM group_member WHERE app_client_id = ?", Long.class, A)).isEqualTo(1L);
    }

    @Test @DisplayName("audit: every call leaves one ai_audit_log row with channel MCP for the token's church and owner; migration block runs twice")
    void audit() throws Exception {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM ai_audit_log WHERE client_id = ? AND channel = 'MCP'", Long.class, A);
        mvc.perform(rpc(tokA, call("engagement_summary", "{}"))).andExpect(status().isOk());
        mvc.perform(rpc(tokA, call("no_such_tool", "{}"))).andExpect(status().isOk());
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT actor, role, intent, outcome FROM ai_audit_log WHERE client_id = ? AND channel = 'MCP' ORDER BY id DESC LIMIT 2", A);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(x -> { assertThat(x.get("actor")).isEqualTo("pat-" + A); assertThat(x.get("role")).isEqualTo("Admin"); });
        assertThat(rows).extracting(x -> x.get("intent")).containsExactlyInAnyOrder("engagement_summary", "tool.unknown");
        assertThat(rows).extracting(x -> x.get("outcome")).containsExactlyInAnyOrder("OK", "DENIED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_audit_log WHERE client_id = ? AND channel = 'MCP'", Long.class, A)).isEqualTo(before + 2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_audit_log WHERE client_id = ? AND channel = 'MCP' AND actor = ?", Long.class, B, "pat-" + A)).isZero();
        // migration block, twice
        String sql = Files.readString(Path.of("scripts/sql/migrate_production.sql"));
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS personal_access_token");
        assertThat(start).isPositive();
        String block = sql.substring(start);
        jdbc.execute(block); jdbc.execute(block);
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'personal_access_token'", String.class))
                .contains("token_hash", "token_prefix", "scopes", "expires_at", "revoked_at", "last_used_at", "app_user_id");
    }

    @AfterAll
    void cleanup() {
        for (String cid : List.of(A, B)) {
            jdbc.update("DELETE FROM ai_audit_log WHERE client_id = ?", cid);
            jdbc.update("DELETE FROM ai_action_proposal WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM personal_access_token WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM mcp_tenant_setting WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM ai_saved_people_list WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM group_member WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM app_group WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM family_member WHERE family_id IN (SELECT id FROM family WHERE app_client_id = ?)", cid);
            jdbc.update("DELETE FROM family WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM app_user WHERE client_id = ?", cid);
        }
    }
}
