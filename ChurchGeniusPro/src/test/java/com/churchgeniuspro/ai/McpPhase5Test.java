package com.churchgeniuspro.ai;

import com.churchgeniuspro.ai.AiRateLimiter;
import com.churchgeniuspro.ai.action.AiActionService;
import com.churchgeniuspro.ai.action.AiToolRegistry;
import com.churchgeniuspro.ai.audit.AiAuditService;
import com.churchgeniuspro.ai.filter.PeopleFilterService;
import com.churchgeniuspro.ai.intel.MemberIntelligenceService;
import com.churchgeniuspro.ai.intel.MinistryReportService;
import com.churchgeniuspro.controller.ApiTokenController;
import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.McpTenantSetting;
import com.churchgeniuspro.hibernate.PersonalAccessToken;
import com.churchgeniuspro.hibernate.UserPermissions;
import com.churchgeniuspro.mcp.*;
import com.churchgeniuspro.repository.AiSavedPeopleListRepository;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.McpTenantSettingRepository;
import com.churchgeniuspro.repository.PersonalAccessTokenRepository;
import com.churchgeniuspro.repository.UserPermissionsRepository;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.AiDataSearchService;
import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.util.SubscriptionFeatureCatalog;
import com.churchgeniuspro.webfilter.ApiTokenFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** AI Phase 5 — external MCP integration: tokens, the bearer filter, the JSON-RPC endpoint, scopes, redaction, the token page API. */
@DisplayName("AI Phase 5 — MCP")
class McpPhase5Test {

    static final String CID = "CHR-A";
    static final ObjectMapper M = new ObjectMapper();

    Map<Long, PersonalAccessToken> store = new LinkedHashMap<>(); AtomicLong ids = new AtomicLong();
    PersonalAccessTokenRepository tokenRepo; McpTenantSettingRepository settingRepo; AppUserRepository users; UserPermissionsRepository perms; SubscriptionService subs;
    PersonalAccessTokenService svc; AppUser pat;
    Map<String, Boolean> features = new HashMap<>(); AtomicReference<Boolean> tenantOn = new AtomicReference<>(true);

    @BeforeEach
    void setUp() {
        tokenRepo = mock(PersonalAccessTokenRepository.class); settingRepo = mock(McpTenantSettingRepository.class);
        users = mock(AppUserRepository.class); perms = mock(UserPermissionsRepository.class); subs = mock(SubscriptionService.class);
        when(tokenRepo.save(any())).thenAnswer(i -> { PersonalAccessToken t = i.getArgument(0); if (t.getId() == null) t.setId(ids.incrementAndGet()); store.put(t.getId(), t); return t; });
        when(tokenRepo.findByTokenHash(anyString())).thenAnswer(i -> store.values().stream().filter(t -> i.getArgument(0).equals(t.getTokenHash())).findFirst());
        when(tokenRepo.findByIdAndAppClientId(anyLong(), anyString())).thenAnswer(i -> Optional.ofNullable(store.get((Long) i.getArgument(0))).filter(t -> i.getArgument(1).equals(t.getAppClientId())));
        when(tokenRepo.findByAppClientIdOrderByCreatedAtDesc(anyString())).thenAnswer(i -> store.values().stream().filter(t -> i.getArgument(0).equals(t.getAppClientId())).toList());
        when(tokenRepo.findByAppClientIdAndAppUserIdOrderByCreatedAtDesc(anyString(), anyInt())).thenAnswer(i -> store.values().stream().filter(t -> i.getArgument(0).equals(t.getAppClientId()) && i.getArgument(1).equals(t.getAppUserId())).toList());
        when(tokenRepo.countByAppClientIdAndAppUserIdAndRevokedAtIsNullAndExpiresAtAfter(anyString(), anyInt(), any())).thenAnswer(i ->
                store.values().stream().filter(t -> i.getArgument(0).equals(t.getAppClientId()) && i.getArgument(1).equals(t.getAppUserId()) && t.isLive(i.getArgument(2))).count());
        when(tokenRepo.revokeAll(anyString(), anyString(), any())).thenAnswer(i -> { int n = 0; for (PersonalAccessToken t : store.values()) if (i.getArgument(0).equals(t.getAppClientId()) && t.getRevokedAt() == null) { t.setRevokedAt(i.getArgument(2)); n++; } return n; });
        when(settingRepo.findByAppClientId(anyString())).thenAnswer(i -> { if (!CID.equals(i.getArgument(0))) return Optional.empty(); McpTenantSetting s = new McpTenantSetting(); s.setAppClientId(CID); s.setEnabled(tenantOn.get()); return Optional.of(s); });
        when(settingRepo.save(any())).thenAnswer(i -> { McpTenantSetting s = i.getArgument(0); tenantOn.set(s.isEnabled()); return s; });
        pat = new AppUser(); pat.setId(7); pat.setUserId("STF-7"); pat.setClientId(CID); pat.setRole("Admin"); pat.setEnabled(true); pat.setFirstName("Pat"); pat.setLastName("Lee");
        pat.setPrivileges(com.churchgeniuspro.permissions.TestPerms.ADMIN_WITH_ACCOUNTING);   // an Admin granted Accounting; a never-saved Admin has none (2026-11-06)
        when(users.findByIdAndClientId(7, CID)).thenReturn(Optional.of(pat));
        when(perms.findByAppUserId(7)).thenReturn(Optional.empty());
        features.put("mcp", true); features.put("aiActions", true); features.put("accounting", true);
        when(subs.isFeatureEnabled(anyString(), anyString())).thenAnswer(i -> features.getOrDefault(i.getArgument(1), true));
        svc = new PersonalAccessTokenService(tokenRepo, settingRepo, users, perms, subs, true);
    }

    String mint(String... scopes) { return svc.create(CID, pat, "pat", "Claude Desktop", List.of(scopes), null).secret(); }

    /* ───────────────────────── 1. token lifecycle ───────────────────────── */

    @Test @DisplayName("tokens: minted once, hashed at rest, scopes closed, limits, every refusal at authenticate")
    void tokens() {
        PersonalAccessTokenService.Created c = svc.create(CID, pat, "pat", "  Claude Desktop ", List.of("contact", "GIVING"), null);
        assertThat(c.secret()).startsWith("cgp_").hasSizeGreaterThan(40);
        PersonalAccessToken t = c.token();
        assertThat(t.getTokenHash()).isEqualTo(PersonalAccessTokenService.sha256Hex(c.secret())).hasSize(64).isNotEqualTo(c.secret());
        assertThat(t.getTokenPrefix()).isEqualTo(c.secret().substring(0, 12));
        assertThat(t.getName()).isEqualTo("Claude Desktop");
        assertThat(PersonalAccessTokenService.scopesOf(t)).containsExactly("read", "contact", "giving");
        assertThat(t.getExpiresAt()).isAfter(LocalDateTime.now().plusDays(89)).isBefore(LocalDateTime.now().plusDays(91));
        Map<String, Object> view = PersonalAccessTokenService.view(t, LocalDateTime.now());
        assertThat(view.toString()).doesNotContain(t.getTokenHash()).doesNotContain(c.secret()).contains("ACTIVE");

        assertThatThrownBy(() -> svc.create(CID, pat, "pat", "x", List.of("admin"), null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown scope");
        assertThatThrownBy(() -> svc.create(CID, pat, "pat", "", List.of(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.create(CID, pat, "pat", "x", List.of(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.create(CID, pat, "pat", "x", List.of(), 366)).isInstanceOf(IllegalArgumentException.class);
        assertThat(PersonalAccessTokenService.normaliseScopes(null)).containsExactly("read");

        // authenticate: happy path + last-used
        PersonalAccessTokenService.Outcome ok = svc.authenticate(c.secret());
        assertThat(ok.refusal()).isNull();
        assertThat(ok.principal().user().getId()).isEqualTo(7);
        assertThat(ok.principal().scopes()).contains("giving").doesNotContain("actions");
        assertThat(t.getLastUsedAt()).isNotNull();

        assertThat(svc.authenticate(null).refusal().code()).isEqualTo("INVALID_TOKEN");
        assertThat(svc.authenticate("cgp_nope").refusal().code()).isEqualTo("INVALID_TOKEN");
        assertThat(svc.authenticate("Bearer " + c.secret()).refusal().code()).isEqualTo("INVALID_TOKEN");
        assertThat(svc.authenticate(c.secret() + "x").refusal().code()).isEqualTo("INVALID_TOKEN");
        // revoked → 401 on the very next request
        assertThat(svc.revoke(CID, t.getId(), 7, true, "pat")).isTrue();
        assertThat(svc.authenticate(c.secret()).refusal()).extracting(PersonalAccessTokenService.Refusal::status, PersonalAccessTokenService.Refusal::code).containsExactly(401, "TOKEN_REVOKED");
        // expired
        String s2 = mint(); store.values().stream().filter(x -> x.getTokenHash().equals(PersonalAccessTokenService.sha256Hex(s2))).findFirst().get().setExpiresAt(LocalDateTime.now().minusMinutes(1));
        assertThat(svc.authenticate(s2).refusal().code()).isEqualTo("TOKEN_EXPIRED");
        // the person: disabled / deleted / moved church / not staff
        String s3 = mint();
        pat.setEnabled(false); assertThat(svc.authenticate(s3).refusal().code()).isEqualTo("USER_INACTIVE"); pat.setEnabled(true);
        pat.setDeleteFlag(true); assertThat(svc.authenticate(s3).refusal().code()).isEqualTo("USER_INACTIVE"); pat.setDeleteFlag(false);
        when(users.findByIdAndClientId(7, CID)).thenReturn(Optional.empty()); assertThat(svc.authenticate(s3).refusal().code()).isEqualTo("USER_INACTIVE");
        when(users.findByIdAndClientId(7, CID)).thenReturn(Optional.of(pat));
        pat.setRole("Member"); assertThat(svc.authenticate(s3).refusal()).extracting(PersonalAccessTokenService.Refusal::status, PersonalAccessTokenService.Refusal::code).containsExactly(403, "STAFF_ONLY"); pat.setRole("Admin");
        // plan and church switch, checked on every request
        features.put("mcp", false); assertThat(svc.authenticate(s3).refusal().code()).isEqualTo("PLAN"); features.put("mcp", true);
        tenantOn.set(false); assertThat(svc.authenticate(s3).refusal().code()).isEqualTo("TENANT_OFF"); tenantOn.set(true);
        assertThat(svc.authenticate(s3).refusal()).isNull();
        // server kill switch
        PersonalAccessTokenService off = new PersonalAccessTokenService(tokenRepo, settingRepo, users, perms, subs, false);
        assertThat(off.authenticate(s3).refusal()).extracting(PersonalAccessTokenService.Refusal::status, PersonalAccessTokenService.Refusal::code).containsExactly(503, "MCP_DISABLED");
        // saved permission map is what the principal carries
        UserPermissions up = new UserPermissions(); up.setPermissions("{\"accounting.income\":false}");
        when(perms.findByAppUserId(7)).thenReturn(Optional.of(up));
        assertThat(svc.authenticate(s3).principal().privileges()).isEqualTo("{\"accounting.income\":false}");
        // cap of live tokens per person
        store.clear();
        for (int i = 0; i < PersonalAccessTokenService.MAX_LIVE_PER_USER; i++) mint();
        assertThatThrownBy(this::mint).isInstanceOf(IllegalStateException.class);
        // revoke: other person's token not touched by owner-only; admin revokes any; revokeAll
        PersonalAccessToken other = store.values().iterator().next(); other.setAppUserId(8);
        assertThat(svc.revoke(CID, other.getId(), 7, true, "pat")).isFalse();
        assertThat(svc.revoke("CHR-B", other.getId(), 7, false, "pat")).isFalse();
        assertThat(svc.revoke(CID, other.getId(), 7, false, "pat")).isTrue();
        assertThat(svc.revokeAll(CID, "pat")).isEqualTo(PersonalAccessTokenService.MAX_LIVE_PER_USER - 1);
        assertThat(store.values()).allMatch(PersonalAccessToken::isRevoked);
    }

    /* ───────────────────────── 2. the bearer filter ───────────────────────── */

    @Test @DisplayName("filter: 401 without / with a bad token, the wrapped request carries the owner's sign-in and scopes, per-token and shared rate limits → 429, refusals audited")
    void filter() throws Exception {
        AiAuditService audit = mock(AiAuditService.class);
        AiRateLimiter limiter = mock(AiRateLimiter.class);
        when(limiter.tryAcquire(anyString(), anyString())).thenReturn(AiRateLimiter.Decision.OK);
        Clock clock = Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"), ZoneOffset.UTC);
        ApiTokenFilter f = new ApiTokenFilter(svc, limiter, audit, 3, clock);

        MockHttpServletResponse res = new MockHttpServletResponse(); FilterChain chain = mock(FilterChain.class);
        f.doFilter(raw("/mcp"), res, chain);
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getHeader("WWW-Authenticate")).startsWith("Bearer");
        assertThat(res.getContentAsString()).contains("NO_TOKEN");
        verify(chain, never()).doFilter(any(), any());

        res = new MockHttpServletResponse();
        f.doFilter(raw("/mcp", "Bearer cgp_wrong-token-value"), res, chain);
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("INVALID_TOKEN");
        // a cookie session is never enough here
        MockHttpServletRequest withCookieSession = raw("/mcp"); withCookieSession.getSession(true).setAttribute("username", "pat");
        res = new MockHttpServletResponse(); f.doFilter(withCookieSession, res, chain);
        assertThat(res.getStatus()).isEqualTo(401);

        String secret = mint("contact");
        ArgumentCaptor<HttpServletRequest> wrapped = ArgumentCaptor.forClass(HttpServletRequest.class);
        res = new MockHttpServletResponse();
        f.doFilter(raw("/mcp", "bearer " + secret), res, chain);      // scheme is case-insensitive
        verify(chain).doFilter(wrapped.capture(), any());
        HttpSession s = wrapped.getValue().getSession(false);
        assertThat(TokenSession.isToken(s)).isTrue();
        assertThat(s.getAttribute("role")).isEqualTo("Admin");
        assertThat(s.getAttribute("appClientId")).isEqualTo(CID);
        assertThat(s.getAttribute("appUserId")).isEqualTo(7);
        assertThat(s.getAttribute("username")).isEqualTo("pat");
        assertThat(s.getAttribute("church")).isEqualTo(Boolean.FALSE);
        assertThat(TokenSession.scopes(s)).containsExactlyInAnyOrder("read", "contact");
        assertThat(wrapped.getValue().getSession()).isSameAs(s);
        // nothing persisted: the original request never got a container session
        // (the Mock request would have created one on getSession(true) only)

        // per-token window: 3 per minute → the 4th is 429 with Retry-After and an audit row
        for (int i = 0; i < 2; i++) f.doFilter(raw("/mcp", "Bearer " + secret), new MockHttpServletResponse(), chain);
        res = new MockHttpServletResponse(); f.doFilter(raw("/mcp", "Bearer " + secret), res, chain);
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isEqualTo("60");
        assertThat(res.getContentAsString()).contains("TOKEN_RATE_LIMITED");
        verify(audit).record(eq(CID), eq("pat"), eq("Admin"), eq("MCP"), eq("/mcp"), eq("rate.token"), isNull(), eq(429), eq("RATE_LIMITED"), anyLong());
        verify(chain, times(3)).doFilter(any(), any());
        // another token has its own window
        String second = mint();
        res = new MockHttpServletResponse(); f.doFilter(raw("/mcp", "Bearer " + second), res, chain);
        assertThat(res.getStatus()).isEqualTo(200);
        // shared limiter refusal
        when(limiter.tryAcquire(anyString(), anyString())).thenReturn(new AiRateLimiter.Decision(false, "tenant-day", 120));
        res = new MockHttpServletResponse(); f.doFilter(raw("/mcp", "Bearer " + second), res, chain);
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isEqualTo("120");
        assertThat(res.getContentAsString()).contains("AI_RATE_LIMITED");
        // a refused token never reaches the chain and is 401 even when the church switch flips mid-day
        when(limiter.tryAcquire(anyString(), anyString())).thenReturn(AiRateLimiter.Decision.OK);
        tenantOn.set(false);
        res = new MockHttpServletResponse(); f.doFilter(raw("/mcp", "Bearer " + second), res, chain);
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("TENANT_OFF");
        verify(chain, times(4)).doFilter(any(), any());
        // a broken check fails closed
        PersonalAccessTokenService broken = mock(PersonalAccessTokenService.class);
        when(broken.authenticate(anyString())).thenThrow(new IllegalStateException("db"));
        res = new MockHttpServletResponse(); new ApiTokenFilter(broken, limiter, audit, 3, clock).doFilter(raw("/mcp", "Bearer cgp_xxxxxxxxxxxxxxxxxxxx"), res, chain);
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(ApiTokenFilter.class.getMethod("doFilter", jakarta.servlet.ServletRequest.class, jakarta.servlet.ServletResponse.class, FilterChain.class)).isNotNull();
    }

    static MockHttpServletRequest raw(String path) { MockHttpServletRequest r = new MockHttpServletRequest("POST", path); r.setRequestURI(path); return r; }
    static MockHttpServletRequest raw(String path, String auth) { MockHttpServletRequest r = raw(path); r.addHeader("Authorization", auth); return r; }

    /* ───────────────────────── 3. the JSON-RPC endpoint ───────────────────────── */

    McpController controller(McpToolCatalog catalog, AiAuditService audit) {
        return new McpController(catalog, new AiAccessPolicy(), audit, "test");
    }

    /** A MockMvc that runs the real filter in front of the controller, like the container. */
    MockMvc mvc(McpController c, ApiTokenFilter f) { return MockMvcBuilders.standaloneSetup(c).addFilter(f, "/mcp").build(); }

    @Test @DisplayName("endpoint: initialize negotiates the version, ping, tools/list by scope, notifications 202, batch and bad bodies refused, unknown tool, GET 405")
    void endpoint() throws Exception {
        AiAuditService audit = mock(AiAuditService.class);
        AiDataSearchService data = mock(AiDataSearchService.class);
        McpToolCatalog catalog = new McpToolCatalog(data, null, null, null, null, mock(AiActionService.class), mock(AiToolRegistry.class), new AiAccessPolicy());
        ApiTokenFilter f = new ApiTokenFilter(svc, null, audit, 0);
        MockMvc mvc = mvc(controller(catalog, audit), f);
        String readOnly = mint(), withActions = mint("actions", "contact");

        // initialize
        MvcResult r = mvc.perform(rpc(readOnly, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}")).andExpect(status().isOk()).andReturn();
        Map<String, Object> init = body(r);
        Map<String, Object> result = (Map<String, Object>) init.get("result");
        assertThat(result.get("protocolVersion")).isEqualTo("2025-03-26");
        assertThat(((Map<String, Object>) result.get("serverInfo")).get("name")).isEqualTo("ChurchGeniusPro");
        assertThat(((Map<String, Object>) result.get("capabilities"))).containsKey("tools");
        assertThat(String.valueOf(result.get("instructions"))).contains("read-only").contains("withheld");
        r = mvc.perform(rpc(readOnly, "{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}")).andReturn();
        assertThat(((Map<String, Object>) body(r).get("result")).get("protocolVersion")).isEqualTo(McpJsonRpc.PROTOCOL_VERSION);
        assertThat(body(r).get("id")).isEqualTo("a");
        // notification → 202, empty
        mvc.perform(rpc(readOnly, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).andExpect(status().isAccepted()).andExpect(content().string(""));
        // ping
        r = mvc.perform(rpc(readOnly, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}")).andExpect(status().isOk()).andReturn();
        assertThat(body(r).get("result")).isEqualTo(Map.of());
        // tools/list: read-only token sees the six read tools, none of the action tools
        r = mvc.perform(rpc(readOnly, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}")).andReturn();
        List<Map<String, Object>> tools = (List<Map<String, Object>>) ((Map<String, Object>) body(r).get("result")).get("tools");
        assertThat(tools).extracting(t -> t.get("name")).containsExactly("ask_church_data", "search_people", "list_saved_lists", "run_saved_list", "ministry_report", "engagement_summary");
        assertThat(tools).allSatisfy(t -> { assertThat(t).containsKeys("title", "description", "inputSchema", "annotations"); assertThat(((Map<String, Object>) t.get("inputSchema")).get("type")).isEqualTo("object"); assertThat(((Map<String, Object>) t.get("annotations")).get("readOnlyHint")).isEqualTo(true); });
        r = mvc.perform(rpc(withActions, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}")).andReturn();
        tools = (List<Map<String, Object>>) ((Map<String, Object>) body(r).get("result")).get("tools");
        assertThat(tools).extracting(t -> t.get("name")).contains("list_actions", "propose_action", "confirm_action", "cancel_action").hasSize(10);
        assertThat(tools.stream().filter(t -> "propose_action".equals(t.get("name"))).findFirst().get()).satisfies(t -> assertThat(((Map<String, Object>) t.get("annotations")).get("readOnlyHint")).isEqualTo(false));
        // an action tool called with a read-only token = unknown tool
        r = mvc.perform(rpc(readOnly, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"propose_action\",\"arguments\":{\"tool\":\"x\",\"params\":{}}}}")).andExpect(status().isOk()).andReturn();
        assertThat(((Map<String, Object>) body(r).get("error")).get("code")).isEqualTo(McpJsonRpc.INVALID_PARAMS);
        r = mvc.perform(rpc(readOnly, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"drop_tables\"}}")).andReturn();
        assertThat(((Map<String, Object>) body(r).get("error")).get("message")).asString().contains("Unknown tool");
        verify(audit, atLeast(2)).record(eq(CID), eq("pat"), eq("Admin"), eq("MCP"), eq("/mcp"), eq("tool.unknown"), isNull(), eq(200), eq("DENIED"), anyLong());
        // method not found, resources
        r = mvc.perform(rpc(readOnly, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/list\"}")).andReturn();
        assertThat(((Map<String, Object>) body(r).get("error")).get("code")).isEqualTo(McpJsonRpc.METHOD_NOT_FOUND);
        r = mvc.perform(rpc(readOnly, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"nope\"}")).andReturn();
        assertThat(((Map<String, Object>) body(r).get("error")).get("code")).isEqualTo(McpJsonRpc.METHOD_NOT_FOUND);
        // batch, parse error, invalid request
        r = mvc.perform(rpc(readOnly, "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]")).andReturn();
        assertThat(((Map<String, Object>) body(r).get("error")).get("code")).isEqualTo(McpJsonRpc.INVALID_REQUEST);
        r = mvc.perform(rpc(readOnly, "{not json")).andReturn();
        assertThat(((Map<String, Object>) body(r).get("error")).get("code")).isEqualTo(McpJsonRpc.PARSE_ERROR);
        r = mvc.perform(rpc(readOnly, "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}")).andReturn();
        assertThat(((Map<String, Object>) body(r).get("error")).get("code")).isEqualTo(McpJsonRpc.INVALID_REQUEST);
        r = mvc.perform(rpc(readOnly, "{\"jsonrpc\":\"2.0\",\"id\":1}")).andReturn();
        assertThat(((Map<String, Object>) body(r).get("error")).get("code")).isEqualTo(McpJsonRpc.INVALID_REQUEST);
        // no token → the filter stops it; GET → 405; the controller alone never trusts a cookie session
        mvc.perform(post("/mcp").contentType("application/json").content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/mcp").header("Authorization", "Bearer " + readOnly)).andExpect(status().isMethodNotAllowed());
        MockMvc bare = MockMvcBuilders.standaloneSetup(controller(catalog, audit)).build();
        bare.perform(post("/mcp").sessionAttr("username", "pat").sessionAttr("role", "Admin").sessionAttr("appClientId", CID).contentType("application/json").content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).andExpect(status().isUnauthorized());
    }

    /* ───────────────────────── 4. tools: delegation, access, redaction ───────────────────────── */

    @Test @DisplayName("tools/call: ask delegates to the data search with the token owner's session, contact details redacted without the scope, giving scope narrows finance, report/engagement by access, actions gate")
    void toolsCall() throws Exception {
        AiAuditService audit = mock(AiAuditService.class);
        AiDataSearchService data = mock(AiDataSearchService.class);
        PeopleFilterService filters = mock(PeopleFilterService.class);
        MinistryReportService reports = mock(MinistryReportService.class);
        MemberIntelligenceService intel = mock(MemberIntelligenceService.class);
        AiActionService actions = mock(AiActionService.class);
        AiToolRegistry registry = mock(AiToolRegistry.class);
        AiAccessPolicy access = new AiAccessPolicy();
        McpToolCatalog catalog = new McpToolCatalog(data, filters, null, reports, intel, actions, registry, access);
        ApiTokenFilter f = new ApiTokenFilter(svc, null, audit, 0);
        MockMvc mvc = mvc(new McpController(catalog, access, audit, "test"), f);

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("intent", "filter.people"); answer.put("denied", false);
        answer.put("rows", List.of(Map.of("name", "Sarah Lee", "email", "sarah.lee@example.org", "phone", "(913) 555-0100", "address1", "12 Oak St", "note", "call sarah.lee@example.org or 913-555-0100")));
        when(data.answer(any(), eq(CID), eq("who gave this year"))).thenReturn(answer);

        String plain = mint(), contact = mint("contact"), giving = mint("giving");
        // ask → the data search gets a request whose session is the owner's staff sign-in
        ArgumentCaptor<HttpServletRequest> req = ArgumentCaptor.forClass(HttpServletRequest.class);
        MvcResult r = mvc.perform(rpc(plain, call("ask_church_data", "{\"question\":\"who gave this year\"}"))).andExpect(status().isOk()).andReturn();
        verify(data).answer(req.capture(), eq(CID), eq("who gave this year"));
        assertThat(req.getValue().getSession(false).getAttribute("username")).isEqualTo("pat");
        assertThat(TokenSession.isToken(req.getValue().getSession(false))).isTrue();
        Map<String, Object> result = (Map<String, Object>) body(r).get("result");
        assertThat(result.get("isError")).isEqualTo(false);
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content.get(0).get("type")).isEqualTo("text");
        Map<String, Object> structured = (Map<String, Object>) result.get("structuredContent");
        Map<String, Object> row = ((List<Map<String, Object>>) structured.get("rows")).get(0);
        assertThat(row.get("name")).isEqualTo("Sarah Lee");
        assertThat(row.get("email")).asString().doesNotContain("sarah.lee@").contains("***");
        assertThat(row.get("phone")).isEqualTo("***-***-0100");
        assertThat(row.get("address1")).isEqualTo(McpRedactor.ADDRESS_MASK);
        assertThat(row.get("note")).asString().doesNotContain("sarah.lee@example.org").doesNotContain("913-555-0100").contains("0100");
        assertThat(String.valueOf(content.get(0).get("text"))).doesNotContain("sarah.lee@example.org").doesNotContain("12 Oak St");
        verify(audit).record(eq(CID), eq("pat"), eq("Admin"), eq("MCP"), eq("/mcp"), eq("ask_church_data"), isNull(), eq(200), eq("OK"), anyLong());
        // with the contact scope the same answer is untouched
        r = mvc.perform(rpc(contact, call("ask_church_data", "{\"question\":\"who gave this year\"}"))).andReturn();
        row = ((List<Map<String, Object>>) ((Map<String, Object>) ((Map<String, Object>) body(r).get("result")).get("structuredContent")).get("rows")).get(0);
        assertThat(row.get("email")).isEqualTo("sarah.lee@example.org");
        assertThat(row.get("address1")).isEqualTo("12 Oak St");
        // validation
        r = mvc.perform(rpc(plain, call("ask_church_data", "{}"))).andReturn();
        assertThat(((Map<String, Object>) body(r).get("result")).get("isError")).isEqualTo(true);
        r = mvc.perform(rpc(plain, call("ask_church_data", "{\"question\":\"" + "x".repeat(501) + "\"}"))).andReturn();
        assertThat(((Map<String, Object>) body(r).get("result")).get("isError")).isEqualTo(true);
        // a denied answer from the service is isError:true, still a result (not a JSON-RPC error)
        when(data.answer(any(), eq(CID), eq("secret stuff"))).thenReturn(Map.of("intent", "crossref.denied", "denied", true, "answer", "no"));
        r = mvc.perform(rpc(plain, call("ask_church_data", "{\"question\":\"secret stuff\"}"))).andReturn();
        assertThat(((Map<String, Object>) body(r).get("result")).get("isError")).isEqualTo(true);
        verify(audit, times(3)).record(eq(CID), eq("pat"), eq("Admin"), eq("MCP"), eq("/mcp"), eq("ask_church_data"), isNull(), eq(200), eq("DENIED"), anyLong());   // two argument refusals + the denied answer
        // a service failure is a tool error, never a 500 or a stack trace
        when(data.answer(any(), eq(CID), eq("boom"))).thenThrow(new IllegalStateException("db gone"));
        r = mvc.perform(rpc(plain, call("ask_church_data", "{\"question\":\"boom\"}"))).andExpect(status().isOk()).andReturn();
        assertThat(((Map<String, Object>) body(r).get("result")).get("isError")).isEqualTo(true);
        assertThat(r.getResponse().getContentAsString()).doesNotContain("db gone");

        // search_people: predicates go through the closed PeopleFilter parser, the cap is bounded
        when(filters.run(any(), any(), any(), eq(CID), any(), anyInt())).thenReturn(Map.of("intent", "filter.people", "denied", false, "rows", List.of(), "total", 0));
        r = mvc.perform(rpc(plain, call("search_people", "{\"predicates\":[{\"kind\":\"Gender\",\"gender\":\"Female\"},{\"kind\":\"AgeBand\",\"min\":18,\"max\":30}],\"limit\":9999}"))).andReturn();
        assertThat(((Map<String, Object>) body(r).get("result")).get("isError")).isEqualTo(false);
        verify(filters).run(argThat(pf -> pf.predicates().size() == 2), any(), any(), eq(CID), any(), eq(500));
        r = mvc.perform(rpc(plain, call("search_people", "{\"predicates\":[{\"kind\":\"DropTable\"}]}"))).andReturn();
        assertThat(((Map<String, Object>) ((Map<String, Object>) body(r).get("result")).get("structuredContent")).get("error")).asString().contains("Invalid predicates");
        r = mvc.perform(rpc(plain, call("search_people", "{\"predicates\":[]}"))).andReturn();
        assertThat(((Map<String, Object>) body(r).get("result")).get("isError")).isEqualTo(true);

        // ministry_report: Access from the owner's AiAccess — Admin without the giving scope has finance=false, with it true
        when(reports.monthly(eq(CID), anyInt(), anyInt(), any(), any())).thenReturn(Map.of("month", "2026-10"));
        ArgumentCaptor<MinistryReportService.Access> acc = ArgumentCaptor.forClass(MinistryReportService.Access.class);
        r = mvc.perform(rpc(plain, call("ministry_report", "{\"year\":2026,\"month\":10}"))).andReturn();
        assertThat(((Map<String, Object>) body(r).get("result")).get("isError")).isEqualTo(false);
        verify(reports).monthly(eq(CID), eq(2026), eq(10), acc.capture(), any());
        assertThat(acc.getValue().finance()).isFalse();
        assertThat(acc.getValue().attendance()).isTrue();     // Admin + no saved permissions = attendance page open
        mvc.perform(rpc(giving, call("ministry_report", "{}"))).andReturn();       // default month, giving scope
        verify(reports, times(2)).monthly(eq(CID), anyInt(), anyInt(), acc.capture(), any());
        assertThat(acc.getValue().finance()).isTrue();
        r = mvc.perform(rpc(plain, call("ministry_report", "{\"year\":2099,\"month\":1}"))).andReturn();
        assertThat(((Map<String, Object>) body(r).get("result")).get("isError")).isEqualTo(true);
        r = mvc.perform(rpc(plain, call("ministry_report", "{\"year\":2026}"))).andReturn();
        assertThat(((Map<String, Object>) body(r).get("result")).get("isError")).isEqualTo(true);
        // engagement_summary
        when(intel.summary(eq(CID), any())).thenReturn(Map.of("snapshotDate", "2026-11-04", "counts", Map.of("ENGAGED", 10L)));
        r = mvc.perform(rpc(plain, call("engagement_summary", "{}"))).andReturn();
        Map<String, Object> eng = (Map<String, Object>) ((Map<String, Object>) body(r).get("result")).get("structuredContent");
        assertThat(eng).containsKeys("snapshotDate", "counts", "definitions");
        // an Accountant has no Attendance page → engagement refused, report without attendance
        pat.setRole("Accountant");
        r = mvc.perform(rpc(plain, call("engagement_summary", "{}"))).andReturn();
        assertThat(((Map<String, Object>) body(r).get("result")).get("isError")).isEqualTo(true);
        pat.setRole("Admin");

        // actions: scope AND plan feature; propose goes to the agent with source MCP and the owner's session; confirm/cancel pass the token
        when(actions.propose(any(), any(), any(), eq(CID), eq("add_group_member"), any(), eq("MCP"))).thenReturn(Map.of("intent", "action.confirm", "denied", false, "confirm", Map.of("token", "tok-1")));
        when(actions.confirm(any(), eq(CID), eq("tok-1"))).thenReturn(Map.of("intent", "action.done", "denied", false, "executed", true));
        when(actions.reject(any(), eq(CID), eq("tok-1"))).thenReturn(Map.of("intent", "action.cancelled", "denied", false));
        com.churchgeniuspro.ai.action.AiTool addGroup = mock(com.churchgeniuspro.ai.action.AiTool.class);
        when(addGroup.name()).thenReturn("add_group_member"); when(addGroup.label()).thenReturn("Add to group"); when(addGroup.description()).thenReturn("d"); when(addGroup.parameters()).thenReturn(Map.of("member", "name"));
        when(addGroup.allowed(any(), any(), any(), eq(CID))).thenReturn(true);
        when(registry.get("add_group_member")).thenReturn(addGroup);
        when(registry.all()).thenReturn(List.of(addGroup));
        String acts = mint("actions");
        r = mvc.perform(rpc(acts, call("list_actions", "{}"))).andReturn();
        assertThat(((Map<String, Object>) ((Map<String, Object>) body(r).get("result")).get("structuredContent")).get("count")).isEqualTo(1);
        r = mvc.perform(rpc(acts, call("propose_action", "{\"tool\":\"add_group_member\",\"params\":{\"member\":\"Sarah Lee\",\"group\":\"Welcome Team\"}}"))).andReturn();
        Map<String, Object> prop = (Map<String, Object>) ((Map<String, Object>) body(r).get("result")).get("structuredContent");
        assertThat(prop.get("intent")).isEqualTo("action.confirm");
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(actions).propose(any(), any(), any(), eq(CID), eq("add_group_member"), params.capture(), eq("MCP"));
        assertThat(params.getValue()).containsEntry("member", "Sarah Lee");
        r = mvc.perform(rpc(acts, call("confirm_action", "{\"token\":\"tok-1\"}"))).andReturn();
        assertThat(((Map<String, Object>) ((Map<String, Object>) body(r).get("result")).get("structuredContent")).get("intent")).isEqualTo("action.done");
        r = mvc.perform(rpc(acts, call("cancel_action", "{\"token\":\"tok-1\"}"))).andReturn();
        assertThat(((Map<String, Object>) ((Map<String, Object>) body(r).get("result")).get("structuredContent")).get("intent")).isEqualTo("action.cancelled");
        r = mvc.perform(rpc(acts, call("propose_action", "{\"tool\":\"drop_tables\",\"params\":{}}"))).andReturn();
        assertThat(((Map<String, Object>) body(r).get("result")).get("isError")).isEqualTo(true);
        verify(actions, times(1)).propose(any(), any(), any(), anyString(), anyString(), any(), anyString());
        // plan without aiActions → refused even with the scope (the catalog's AiAccessPolicy has no subscriptions → true; use one that says no)
        SubscriptionService noActions = mock(SubscriptionService.class);
        when(noActions.isFeatureEnabled(anyString(), anyString())).thenAnswer(i -> !"aiActions".equals(i.getArgument(1)));
        AiAccessPolicy gated = new AiAccessPolicy(noActions);
        McpToolCatalog gatedCatalog = new McpToolCatalog(data, filters, null, reports, intel, actions, registry, gated);
        MockMvc gatedMvc = mvc(new McpController(gatedCatalog, gated, audit, "test"), f);
        r = gatedMvc.perform(rpc(acts, call("list_actions", "{}"))).andReturn();
        assertThat(((Map<String, Object>) ((Map<String, Object>) body(r).get("result")).get("structuredContent")).get("error")).asString().contains("not included");
    }

    @Test @DisplayName("AiAccessPolicy: a token session without the giving scope has no finance access, with it the owner's own; plain sessions unchanged")
    void givingScope() {
        AiAccessPolicy policy = new AiAccessPolicy();
        PersonalAccessTokenService.Principal pNo = svc.authenticate(mint()).principal();
        PersonalAccessTokenService.Principal pYes = svc.authenticate(mint("giving")).principal();
        MockHttpServletRequest base = new MockHttpServletRequest();
        AiAccessPolicy.AiAccess no = policy.resolve(TokenSession.wrap(base, TokenSession.forPrincipal(base.getServletContext(), pNo)), CID);
        AiAccessPolicy.AiAccess yes = policy.resolve(TokenSession.wrap(base, TokenSession.forPrincipal(base.getServletContext(), pYes)), CID);
        assertThat(no.incomeRecords()).isFalse(); assertThat(no.expenseRecords()).isFalse(); assertThat(no.reports()).isFalse(); assertThat(no.pledges()).isFalse();
        assertThat(no.members()).isTrue(); assertThat(no.volunteers()).isTrue(); assertThat(no.followUps()).isTrue();   // non-finance access is the owner's
        assertThat(yes.incomeRecords()).isTrue(); assertThat(yes.reports()).isTrue(); assertThat(yes.pledges()).isTrue();
        MockHttpServletRequest plain = new MockHttpServletRequest();
        plain.getSession(true).setAttribute("username", "pat"); plain.getSession().setAttribute("role", "Admin"); plain.getSession().setAttribute("appClientId", CID);
        plain.getSession().setAttribute("privileges", com.churchgeniuspro.permissions.TestPerms.ADMIN_WITH_ACCOUNTING);   // Admin granted Accounting
        assertThat(policy.resolve(plain, CID).incomeRecords()).isTrue();
        assertThat(TokenSession.scopes(plain.getSession())).isNull();
    }

    @Test @DisplayName("redactor: contact keys masked whatever the nesting, inline addresses and numbers masked, everything else untouched")
    void redactor() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("Email", "a.person@example.org"); in.put("PHONE_NUMBER", "+1 913 555 0100"); in.put("Address", "1 Main St"); in.put("zipCode", "66062"); in.put("street2", "Apt 4");
        in.put("name", "Ann Person"); in.put("age", 42); in.put("total", 12.5); in.put("date", "2026-11-05"); in.put("id", 1234567890L);
        in.put("answer", "Reach Ann at a.person@example.org or (913) 555-0100; member since 2019-03-04, id 12345.");
        in.put("nested", List.of(Map.of("email", "b@x.org", "label", "B"), "c@x.org"));
        Map<String, Object> out = (Map<String, Object>) McpRedactor.redact(in);
        assertThat(out.get("Email")).asString().doesNotContain("a.person@").contains("***");
        assertThat(out.get("PHONE_NUMBER")).isEqualTo("***-***-0100");
        assertThat(out.get("Address")).isEqualTo(McpRedactor.ADDRESS_MASK);
        assertThat(out.get("zipCode")).isEqualTo(McpRedactor.ADDRESS_MASK);
        assertThat(out.get("street2")).isEqualTo(McpRedactor.ADDRESS_MASK);
        assertThat(out.get("name")).isEqualTo("Ann Person");
        assertThat(out.get("age")).isEqualTo(42); assertThat(out.get("total")).isEqualTo(12.5); assertThat(out.get("date")).isEqualTo("2026-11-05"); assertThat(out.get("id")).isEqualTo(1234567890L);
        assertThat(out.get("answer")).asString().doesNotContain("a.person@example.org").doesNotContain("555-0100;").contains("***-***-0100").contains("2019-03-04").contains("12345");
        List<Object> nested = (List<Object>) out.get("nested");
        assertThat(((Map<String, Object>) nested.get(0)).get("email")).asString().doesNotContain("b@x.org");
        assertThat(((Map<String, Object>) nested.get(0)).get("label")).isEqualTo("B");
        assertThat(nested.get(1)).asString().doesNotContain("c@x.org");
        assertThat(McpRedactor.redact(null)).isNull();
    }

    /* ───────────────────────── 5. the token page API ───────────────────────── */

    @Test @DisplayName("/api/tokens: gates (401, member/church/kids/temp/token sessions 403), plan + church switch, create once, actions scope admin-only, revoke own vs all, settings admin-only")
    void tokenApi() throws Exception {
        ApiTokenController c = new ApiTokenController(svc, users);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(c).build();
        mvc.perform(get("/api/tokens")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tokens").sessionAttr("username", "m").sessionAttr("role", "Member").sessionAttr("memberId", 3).sessionAttr("appClientId", CID)).andExpect(status().isForbidden());
        mvc.perform(get("/api/tokens").sessionAttr("username", "c").sessionAttr("role", "church").sessionAttr("church", true).sessionAttr("appClientId", CID).sessionAttr("clientId", CID)).andExpect(status().isForbidden());
        mvc.perform(get("/api/tokens").sessionAttr("username", "t").sessionAttr("role", "User").sessionAttr("appClientId", CID).sessionAttr("appUserId", 7).sessionAttr("tempAccessId", 5L)).andExpect(status().isForbidden());
        mvc.perform(get("/api/tokens").sessionAttr("username", "pat").sessionAttr("role", "Admin").sessionAttr("appClientId", CID).sessionAttr("appUserId", 7).sessionAttr("privileges", "{\"more.aiassistant\":false}")).andExpect(status().isForbidden());
        // a token-backed session cannot mint tokens
        MockHttpServletRequest base = new MockHttpServletRequest();
        TokenSession ts = TokenSession.forPrincipal(base.getServletContext(), svc.authenticate(mint()).principal());
        assertThat(ApiTokenController.class.getDeclaredMethod("gate", HttpServletRequest.class)).isNotNull();
        HttpServletRequest tokenReq = TokenSession.wrap(base, ts);
        assertThat(invokeGate(tokenReq).getStatusCode().value()).isEqualTo(403);
        store.clear();

        // staff User: own tokens only, no settings card, no actions scope
        var user = user("User", 9);
        AppUser u9 = new AppUser(); u9.setId(9); u9.setUserId("STF-9"); u9.setClientId(CID); u9.setRole("User"); u9.setEnabled(true);
        when(users.findByIdAndClientId(9, CID)).thenReturn(Optional.of(u9));
        MvcResult r = mvc.perform(get("/api/tokens").session(user)).andExpect(status().isOk()).andReturn();
        Map<String, Object> st = body(r);
        assertThat(st.get("admin")).isEqualTo(false); assertThat(st.get("planAllows")).isEqualTo(true); assertThat(st.get("tenantEnabled")).isEqualTo(true);
        assertThat(st).doesNotContainKey("all"); assertThat(String.valueOf(st.get("endpoint"))).endsWith("/mcp");
        mvc.perform(post("/api/tokens").session(user).contentType("application/json").content("{\"name\":\"x\",\"scopes\":[\"actions\"]}")).andExpect(status().isForbidden());
        r = mvc.perform(post("/api/tokens").session(user).contentType("application/json").content("{\"name\":\"My laptop\",\"scopes\":[\"contact\"],\"expiresDays\":30}")).andExpect(status().isOk()).andReturn();
        Map<String, Object> created = body(r);
        assertThat(String.valueOf(created.get("token"))).startsWith("cgp_");
        assertThat(created.get("scopes")).isEqualTo(List.of("read", "contact"));
        assertThat(created).containsKey("note");
        long userTokenId = ((Number) created.get("id")).longValue();
        // the list never carries the secret or the hash
        r = mvc.perform(get("/api/tokens").session(user)).andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContain(String.valueOf(created.get("token"))).contains("\"prefix\"");
        mvc.perform(post("/api/tokens").session(user).contentType("application/json").content("{\"name\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/tokens").session(user).contentType("application/json").content("{\"name\":\"x\",\"expiresDays\":\"abc\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/tokens").session(user).contentType("application/json").content("{\"name\":\"x\",\"scopes\":[\"root\"]}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/tokens/revoke-all").session(user)).andExpect(status().isForbidden());
        mvc.perform(put("/api/tokens/settings").session(user).contentType("application/json").content("{\"enabled\":false}")).andExpect(status().isForbidden());

        // admin: sees all, may hand out actions, revokes anyone's, settings
        var admin = user("Admin", 7);
        r = mvc.perform(get("/api/tokens").session(admin)).andReturn();
        assertThat(body(r).get("admin")).isEqualTo(true);
        assertThat((List<?>) body(r).get("all")).hasSize(1);
        assertThat((List<?>) body(r).get("mine")).isEmpty();
        r = mvc.perform(post("/api/tokens").session(admin).contentType("application/json").content("{\"name\":\"Ops bot\",\"scopes\":[\"actions\",\"giving\"]}")).andExpect(status().isOk()).andReturn();
        assertThat(body(r).get("scopes")).isEqualTo(List.of("read", "actions", "giving"));
        // the User cannot revoke the admin's token, the admin can revoke the User's
        long adminTokenId = ((Number) body(r).get("id")).longValue();
        mvc.perform(delete("/api/tokens/" + adminTokenId).session(user)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/tokens/" + userTokenId).session(admin)).andExpect(status().isOk());
        assertThat(store.get(userTokenId).isRevoked()).isTrue();
        mvc.perform(delete("/api/tokens/" + userTokenId).session(user)).andExpect(status().isOk());   // idempotent on an own token
        // church switch off → create refused with a code, existing tokens refused by authenticate; the page still loads
        mvc.perform(put("/api/tokens/settings").session(admin).contentType("application/json").content("{\"enabled\":false}")).andExpect(status().isOk());
        assertThat(tenantOn.get()).isFalse();
        r = mvc.perform(post("/api/tokens").session(admin).contentType("application/json").content("{\"name\":\"x\"}")).andExpect(status().isForbidden()).andReturn();
        assertThat(body(r).get("code")).isEqualTo("TENANT_OFF");
        r = mvc.perform(get("/api/tokens").session(admin)).andExpect(status().isOk()).andReturn();
        assertThat(body(r).get("tenantEnabled")).isEqualTo(false);
        // plan without mcp: switch cannot be turned on, create refused
        features.put("mcp", false);
        mvc.perform(put("/api/tokens/settings").session(admin).contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isForbidden());
        r = mvc.perform(post("/api/tokens").session(admin).contentType("application/json").content("{\"name\":\"x\"}")).andExpect(status().isForbidden()).andReturn();
        assertThat(body(r).get("code")).isEqualTo("PLAN");
        features.put("mcp", true); tenantOn.set(true);
        // revoke all
        r = mvc.perform(post("/api/tokens/revoke-all").session(admin)).andExpect(status().isOk()).andReturn();
        assertThat(((Number) body(r).get("revoked")).intValue()).isEqualTo(1);
        assertThat(store.values()).allMatch(PersonalAccessToken::isRevoked);
        // page route
        mvc.perform(get("/apiTokens").session(admin)).andExpect(forwardedUrl("/apiTokens.html"));
        mvc.perform(get("/apiTokens")).andExpect(forwardedUrl("/access-denied.html"));
    }

    private static org.springframework.http.ResponseEntity<Map<String, Object>> invokeGate(HttpServletRequest r) throws Exception {
        var m = ApiTokenController.class.getDeclaredMethod("gate", HttpServletRequest.class); m.setAccessible(true);
        return (org.springframework.http.ResponseEntity<Map<String, Object>>) m.invoke(null, r);
    }

    static org.springframework.mock.web.MockHttpSession user(String role, int appUserId) {
        org.springframework.mock.web.MockHttpSession s = new org.springframework.mock.web.MockHttpSession();
        s.setAttribute("username", "u" + appUserId); s.setAttribute("role", role); s.setAttribute("appClientId", CID); s.setAttribute("appUserId", appUserId); s.setAttribute("clientId", "STF-" + appUserId);
        return s;
    }

    /* ───────────────────────── 6. wiring / architecture ───────────────────────── */

    @Test @DisplayName("wiring: plan feature mcp gates /api/tokens and is denied on FREE/STANDARD, properties and migration present, /mcp outside every cookie filter, no send path in the mcp package, help + nav registered")
    void wiring() throws Exception {
        Path root = Path.of("src/main");
        assertThat(SubscriptionFeatureCatalog.FEATURES).anySatisfy(f -> { assertThat(f.key()).isEqualTo("mcp"); assertThat(f.pathPrefixes()).contains("/api/tokens"); });
        String seeder = Files.readString(root.resolve("java/com/churchgeniuspro/config/SubscriptionPlanSeeder.java"));
        assertThat(seeder).contains("denyAiFeaturesOutsideProAndTrial(repo);");   // every plan but PRO/TRIAL gets mcp:false once (2026-11-05)
        assertThat(SubscriptionFeatureCatalog.AI_PRO_ONLY_FEATURES).contains("mcp");
        assertThat(seeder.split("\\\\\"mcp\\\\\":false").length).isGreaterThanOrEqualTo(3);
        String props = Files.readString(root.resolve("resources/application.properties"));
        assertThat(props).contains("mcp.enabled=${MCP_ENABLED:true}").contains("mcp.rate.per-token-per-minute=");
        String mig = Files.readString(Path.of("scripts/sql/migrate_production.sql"));
        assertThat(mig).contains("CREATE TABLE IF NOT EXISTS personal_access_token").contains("CREATE TABLE IF NOT EXISTS mcp_tenant_setting").contains("idx_pat_hash");
        // /mcp is served by McpController and guarded by ApiTokenFilter only — never by the /api/* cookie filters
        String fc = Files.readString(root.resolve("java/com/churchgeniuspro/webfilter/ApiTokenFilterConfig.java"));
        assertThat(fc).contains("addUrlPatterns(\"/mcp\", \"/mcp/*\")");
        String ctl = Files.readString(root.resolve("java/com/churchgeniuspro/mcp/McpController.java"));
        assertThat(ctl).contains("@PostMapping(value = \"/mcp\"").doesNotContain("/api/");
        // no mail / SMS / push / WhatsApp anywhere in the MCP package (the plan: read tools, writes only as Phase 4 proposals)
        try (var files = Files.list(root.resolve("java/com/churchgeniuspro/mcp"))) {
            for (Path p : files.toList()) {
                String src = Files.readString(p).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", "");
                assertThat(src).as(p.toString()).doesNotContain("EmailService").doesNotContain("SmsService").doesNotContain("WebPushService").doesNotContain("WhatsApp");
            }
        }
        // the UI: nav item, page permission map, feature map, help card + KB, AI Activity label
        assertThat(Files.readString(root.resolve("resources/static/shell.js"))).contains("href: '/apiTokens'");
        String session = Files.readString(root.resolve("resources/static/session.js"));
        assertThat(session).contains("'/apiTokens': 'mcp'").contains("'/apiTokens':           'more.aiassistant'");
        String help = Files.readString(root.resolve("resources/static/helpCenter.html"));
        assertThat(help).contains("id=\"hc-ai-connections\"").contains("data-feature=\"mcp\"").contains("id: 'ai-connections'");
        assertThat(Files.readString(root.resolve("resources/static/aiActivity.html"))).contains("MCP:'External (MCP)'");
        String page = Files.readString(root.resolve("resources/static/apiTokens.html"));
        assertThat(page).contains("/cgp-api.js").contains("CGP_api('/api/tokens'").doesNotContain("alert(").doesNotContain("confirm(");
        // AiActionProposal.source accepts "MCP" (length 10)
        assertThat(Files.readString(root.resolve("java/com/churchgeniuspro/hibernate/AiActionProposal.java"))).contains("name = \"source\", length = 10");
    }

    /* ───────────────────────── helpers ───────────────────────── */

    static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rpc(String token, String json) {
        return post("/mcp").header("Authorization", "Bearer " + token).contentType("application/json").accept("application/json").content(json);
    }
    static String call(String tool, String argsJson) { return "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + argsJson + "}}"; }
    @SuppressWarnings("unchecked")
    static Map<String, Object> body(MvcResult r) throws Exception { return M.readValue(r.getResponse().getContentAsString(), LinkedHashMap.class); }
}
