package com.churchgeniuspro.ai;

import com.churchgeniuspro.ai.AiRateLimiter;
import com.churchgeniuspro.ai.audit.AiAuditService;
import com.churchgeniuspro.ai.llm.LlmGateway;
import com.churchgeniuspro.hibernate.AiAuditLog;
import com.churchgeniuspro.repository.AiAuditLogRepository;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.util.PermissionCatalog;
import com.churchgeniuspro.util.SubscriptionFeatureCatalog;
import com.churchgeniuspro.webfilter.AiGuardFilter;
import com.churchgeniuspro.webfilter.SubscriptionFeatureFilter;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AI Phase 0 (2026-11-04) — the foundation every later AI phase stands on:
 * one audit row per AI request, a rate limit, no AI for badge sessions, a plan gate that
 * fails closed, the four previously ungated AI endpoints under plan features, and a
 * server-side permission catalogue that cannot drift from the Permissions screen.
 */
@DisplayName("AI Phase 0 — audit, rate limit, access and plan hardening")
class AiPhase0FoundationTest {

    static final String CID = "CHR-1";

    static MockHttpServletRequest req(String path, MockHttpSession s) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", path);
        r.setRequestURI(path);
        if (s != null) r.setSession(s);
        return r;
    }
    static MockHttpSession staff() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "pat"); s.setAttribute("role", "Admin"); s.setAttribute("appClientId", CID);
        s.setAttribute("privileges", com.churchgeniuspro.permissions.TestPerms.ADMIN_WITH_ACCOUNTING);   // Admin granted Accounting (never-saved = no Accounting, 2026-11-06)
        return s;
    }
    static MockHttpSession temp() {
        MockHttpSession s = staff();
        s.setAttribute("username", "temp:7"); s.setAttribute("role", "User"); s.setAttribute("tempAccessId", 7);
        return s;
    }

    // ── AiGuardFilter: one audit row per request, temp sessions refused, 429 on the limit ──

    @Test @DisplayName("every AI entry point writes exactly one audit row carrying the service's intent and the gateway's tokens; a non-AI path writes none")
    void oneRowPerRequest() throws Exception {
        AiAuditLogRepository repo = mock(AiAuditLogRepository.class);
        AiAuditService audit = new AiAuditService(repo, true);
        AiGuardFilter f = new AiGuardFilter(audit, new AiRateLimiter(60, 5000, true), true);
        String[] paths = { "/api/ai-search", "/api/ai-search/data", "/api/ai-search/confirm", "/api/ai-assist",
                           "/api/voice/command", "/api/voice/extract", "/api/help/assist", "/api/expense/check-scan",
                           "/api/income/check-scan", "/api/bank-import/scan", "/api/family/scan-extract" };
        for (String p : paths) {
            MockHttpServletRequest r = req(p, staff());
            FilterChain chain = (rq, rs) -> {
                rq.setAttribute(AiAuditService.INTENT_ATTR, "crossref.notInGroup");
                rq.setAttribute(LlmGateway.USAGE_ATTR, new LlmGateway.Usage("chatJson", "gpt-4o-mini", 120, 30, 200, false));
            };
            f.doFilter(r, new MockHttpServletResponse(), chain);
        }
        f.doFilter(req("/api/families", staff()), new MockHttpServletResponse(), new MockFilterChain());
        ArgumentCaptor<AiAuditLog> cap = ArgumentCaptor.forClass(AiAuditLog.class);
        verify(repo, times(paths.length)).save(cap.capture());
        List<AiAuditLog> rows = cap.getAllValues();
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getClientId()).isEqualTo(CID);
            assertThat(row.getActor()).isEqualTo("pat");
            assertThat(row.getIntent()).isEqualTo("crossref.notInGroup");
            assertThat(row.getModel()).isEqualTo("gpt-4o-mini");
            assertThat(row.getPromptTokens()).isEqualTo(120);
            assertThat(row.getCompletionTokens()).isEqualTo(30);
            assertThat(row.getOutcome()).isEqualTo("OK");
            assertThat(row.getHttpStatus()).isEqualTo(200);
        });
        assertThat(rows).extracting(AiAuditLog::getChannel)
                .containsExactly("SEARCH", "DATA", "CONFIRM", "ASSIST", "VOICE", "VOICE", "HELP", "VISION", "VISION", "VISION", "VISION");
        assertThat(rows).extracting(AiAuditLog::getPath).contains("/api/voice/command", "/api/help/assist");
    }

    @Test @DisplayName("a 200 answer that was a refusal (denied=true) is audited as DENIED; a 500 as ERROR; an audit failure never breaks the request")
    void outcomes() throws Exception {
        AiAuditLogRepository repo = mock(AiAuditLogRepository.class);
        AiGuardFilter f = new AiGuardFilter(new AiAuditService(repo, true), new AiRateLimiter(60, 5000, true), true);
        f.doFilter(req("/api/ai-search/data", staff()), new MockHttpServletResponse(),
                (rq, rs) -> rq.setAttribute("ai.denied", Boolean.TRUE));
        MockHttpServletResponse boom = new MockHttpServletResponse();
        f.doFilter(req("/api/ai-search/data", staff()), boom, (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(500));
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));
        MockHttpServletResponse ok = new MockHttpServletResponse();
        f.doFilter(req("/api/ai-search/data", staff()), ok, new MockFilterChain());
        assertThat(ok.getStatus()).isEqualTo(200);
        ArgumentCaptor<AiAuditLog> cap = ArgumentCaptor.forClass(AiAuditLog.class);
        verify(repo, times(3)).save(cap.capture());
        assertThat(cap.getAllValues()).extracting(AiAuditLog::getOutcome).containsExactly("DENIED", "ERROR", "OK");
    }

    @Test @DisplayName("S1: a temporary-access (badge) session gets 403 AI_TEMP_ACCESS before the controller runs, and the refusal is audited")
    void tempAccessRefused() throws Exception {
        AiAuditLogRepository repo = mock(AiAuditLogRepository.class);
        AiGuardFilter f = new AiGuardFilter(new AiAuditService(repo, true), new AiRateLimiter(60, 5000, true), true);
        boolean[] reached = { false };
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req("/api/ai-search/data", temp()), res, (rq, rs) -> reached[0] = true);
        assertThat(reached[0]).isFalse();
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains(AiGuardFilter.CODE_TEMP_ACCESS);
        ArgumentCaptor<AiAuditLog> cap = ArgumentCaptor.forClass(AiAuditLog.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getOutcome()).isEqualTo("DENIED");
        assertThat(cap.getValue().getActor()).isEqualTo("temp:7");

        // WARN-only mode lets it through (first production week), still audited as OK
        AiGuardFilter warn = new AiGuardFilter(new AiAuditService(repo, true), new AiRateLimiter(60, 5000, true), false);
        reached[0] = false;
        warn.doFilter(req("/api/ai-search/data", temp()), new MockHttpServletResponse(), (rq, rs) -> reached[0] = true);
        assertThat(reached[0]).isTrue();
    }

    @Test @DisplayName("S4: the 61st call in a minute by one person gets 429 + Retry-After, is audited RATE_LIMITED; another person and the next minute are unaffected")
    void rateLimit() throws Exception {
        Instant t0 = Instant.parse("2026-11-04T15:00:10Z");
        Clock[] clock = { Clock.fixed(t0, ZoneOffset.UTC) };
        Clock moving = new Clock() {
            public ZoneOffset getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return clock[0].instant(); }
        };
        AiRateLimiter limiter = new AiRateLimiter(60, 5000, true, moving);
        AiAuditLogRepository repo = mock(AiAuditLogRepository.class);
        AiGuardFilter f = new AiGuardFilter(new AiAuditService(repo, true), limiter, true);
        for (int i = 0; i < 60; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            f.doFilter(req("/api/ai-search/data", staff()), res, new MockFilterChain());
            assertThat(res.getStatus()).as("call " + (i + 1)).isEqualTo(200);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req("/api/ai-search/data", staff()), res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isEqualTo("50");
        assertThat(res.getContentAsString()).contains(AiGuardFilter.CODE_RATE_LIMITED).contains("user-minute");

        MockHttpSession other = staff(); other.setAttribute("username", "sam");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        f.doFilter(req("/api/ai-search/data", other), res2, new MockFilterChain());
        assertThat(res2.getStatus()).as("another person has their own window").isEqualTo(200);

        clock[0] = Clock.fixed(t0.plusSeconds(60), ZoneOffset.UTC);
        MockHttpServletResponse res3 = new MockHttpServletResponse();
        f.doFilter(req("/api/ai-search/data", staff()), res3, new MockFilterChain());
        assertThat(res3.getStatus()).as("next minute").isEqualTo(200);

        ArgumentCaptor<AiAuditLog> cap = ArgumentCaptor.forClass(AiAuditLog.class);
        verify(repo, atLeast(62)).save(cap.capture());
        assertThat(cap.getAllValues()).extracting(AiAuditLog::getOutcome).contains("RATE_LIMITED");

        // per-tenant day cap, church calendar day
        AiRateLimiter day = new AiRateLimiter(0, 3, true, moving);
        assertThat(day.tryAcquire(CID, "a").allowed()).isTrue();
        assertThat(day.tryAcquire(CID, "b").allowed()).isTrue();
        assertThat(day.tryAcquire(CID, "c").allowed()).isTrue();
        AiRateLimiter.Decision d = day.tryAcquire(CID, "d");
        assertThat(d.allowed()).isFalse();
        assertThat(d.scope()).isEqualTo("tenant-day");
        assertThat(day.tryAcquire("CHR-2", "a").allowed()).as("another church").isTrue();
        // WARN-only: refused decision, but the filter lets the call through
        AiGuardFilter warn = new AiGuardFilter(new AiAuditService(repo, true), new AiRateLimiter(0, 1, false, moving), true);
        warn.doFilter(req("/api/ai-search/data", staff()), new MockHttpServletResponse(), new MockFilterChain());
        MockHttpServletResponse res4 = new MockHttpServletResponse();
        warn.doFilter(req("/api/ai-search/data", staff()), res4, new MockFilterChain());
        assertThat(res4.getStatus()).isEqualTo(200);
    }

    // ── AiAccessPolicy ───────────────────────────────────────────────────────

    @Test @DisplayName("AiAccessPolicy: a temporary-access session gets no module and no cross-reference module; a null tenant gets no plan feature (fail closed)")
    void policyHardening() {
        SubscriptionService subs = mock(SubscriptionService.class);
        when(subs.isFeatureEnabled(anyString(), anyString())).thenReturn(true);
        AiAccessPolicy policy = new AiAccessPolicy(subs);
        MockHttpServletRequest t = req("/api/ai-search/data", temp());
        AiAccessPolicy.AiAccess a = policy.resolve(t, CID);
        assertThat(a.members()).isFalse(); assertThat(a.anyFinance()).isFalse(); assertThat(a.volunteers()).isFalse();
        assertThat(a.pages()).isEmpty();
        AiAccessPolicy.CrossAccess c = policy.crossAccess(t, CID);
        assertThat(c.attendance()).isFalse(); assertThat(c.groups()).isFalse();

        MockHttpServletRequest s = req("/api/ai-search/data", staff());
        assertThat(policy.resolve(s, CID).incomeRecords()).as("same role with a real session and tenant").isTrue();
        AiAccessPolicy.AiAccess noTenant = policy.resolve(s, null);
        assertThat(noTenant.incomeRecords()).as("finance needs plan 'accounting' → unknown tenant → denied").isFalse();
        assertThat(noTenant.volunteers()).isFalse();
        assertThat(noTenant.members()).as("members has no plan feature; role + page rule still apply").isTrue();
        verify(subs, never()).isFeatureEnabled(isNull(), anyString());
    }

    // ── Plan gating ──────────────────────────────────────────────────────────

    @Test @DisplayName("the four previously ungated AI endpoints are now behind a plan feature; the existing mappings are unchanged")
    void catalogCoversEveryAiEndpoint() {
        assertThat(SubscriptionFeatureCatalog.keyForPath("/api/help/assist")).isEqualTo("aiConverse");
        assertThat(SubscriptionFeatureCatalog.keyForPath("/api/income/check-scan")).isEqualTo("scanCheck");
        assertThat(SubscriptionFeatureCatalog.keyForPath("/api/bank-import/scan")).isEqualTo("scanCheck");
        assertThat(SubscriptionFeatureCatalog.keyForPath("/api/bank-import/scan-parse")).isEqualTo("scanCheck");
        assertThat(SubscriptionFeatureCatalog.keyForPath("/api/family/scan-extract")).isEqualTo("scanCheck");
        assertThat(SubscriptionFeatureCatalog.keyForPath("/api/kids-ministry/child-scan-extract")).isEqualTo("scanCheck");
        assertThat(SubscriptionFeatureCatalog.keyForPath("/api/ai-search/data")).isEqualTo("aiSearch");
        assertThat(SubscriptionFeatureCatalog.keyForPath("/api/voice/command")).isEqualTo("aiVoice");
        assertThat(SubscriptionFeatureCatalog.keyForPath("/api/ai-assist")).isEqualTo("aiConverse");
        assertThat(SubscriptionFeatureCatalog.keyForPath("/api/expense/check-scan")).isEqualTo("scanCheck");
        assertThat(SubscriptionFeatureCatalog.keyForPath("/api/income")).as("plain income stays Accounting").isEqualTo("accounting");
        assertThat(SubscriptionFeatureCatalog.keyForPath("/api/help/search")).isNull();
        // every audited AI channel path is plan-gated
        for (String p : AiAuditService.CHANNELS.keySet())
            assertThat(SubscriptionFeatureCatalog.keyForPath(p)).as(p).isNotNull();
    }

    @Test @DisplayName("SubscriptionFeatureFilter: a plan lookup error for a known tenant refuses a gated API call (403 SUBSCRIPTION_CHECK_FAILED + ref) but lets a page through")
    void planFilterFailsClosedOnApi() throws Exception {
        SubscriptionService subs = mock(SubscriptionService.class);
        when(subs.isFeatureEnabled(anyString(), anyString())).thenThrow(new RuntimeException("db down"));
        SubscriptionFeatureFilter f = new SubscriptionFeatureFilter(subs);
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean[] reached = { false };
        f.doFilter(req("/api/ai-search/data", staff()), res, (rq, rs) -> reached[0] = true);
        assertThat(reached[0]).isFalse();
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains("SUBSCRIPTION_CHECK_FAILED").contains("\"ref\"");
        assertThat(res.getContentAsString()).doesNotContain("db down");

        MockHttpServletRequest page = new MockHttpServletRequest("GET", "/income"); page.setRequestURI("/income"); page.setSession(staff());
        reached[0] = false;
        f.doFilter(page, new MockHttpServletResponse(), (rq, rs) -> reached[0] = true);
        assertThat(reached[0]).as("pages fail open so the shell renders").isTrue();

        reset(subs); when(subs.isFeatureEnabled(anyString(), anyString())).thenReturn(false);
        MockHttpServletResponse off = new MockHttpServletResponse();
        f.doFilter(req("/api/help/assist", staff()), off, new MockFilterChain());
        assertThat(off.getStatus()).as("a FREE plan now refuses help assist").isEqualTo(403);
        assertThat(off.getContentAsString()).contains("SUBSCRIPTION_FEATURE_DISABLED").contains("aiConverse");
    }

    // ── LlmGateway ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Test @DisplayName("LlmGateway: one retry on 429/5xx with Retry-After honoured, usage read from the body, process counters, oversized body capped")
    void gateway() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> r429 = mock(HttpResponse.class), r200 = mock(HttpResponse.class);
        when(r429.statusCode()).thenReturn(429);
        when(r429.headers()).thenReturn(java.net.http.HttpHeaders.of(java.util.Map.of("Retry-After", List.of("0")), (a, b) -> true));
        when(r200.statusCode()).thenReturn(200);
        when(r200.body()).thenReturn("{\"model\":\"gpt-4o-mini-2024\",\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":5},\"choices\":[]}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(r429, r200);
        LlmGateway g = new LlmGateway(http);
        HttpRequest req = HttpRequest.newBuilder(java.net.URI.create("https://api.openai.com/v1/chat/completions")).GET().build();
        LlmGateway.Reply reply = g.send(req, "chatJson");
        assertThat(reply.ok()).isTrue();
        assertThat(reply.usage().model()).isEqualTo("gpt-4o-mini-2024");
        assertThat(reply.usage().promptTokens()).isEqualTo(11);
        assertThat(reply.usage().completionTokens()).isEqualTo(5);
        assertThat(reply.usage().retried()).isTrue();
        verify(http, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertThat(g.stats()).containsEntry("calls", 1L).containsEntry("retries", 1L).containsEntry("promptTokens", 11L);

        // a second failure is returned, not retried again
        HttpResponse<String> r500 = mock(HttpResponse.class);
        when(r500.statusCode()).thenReturn(500); when(r500.headers()).thenReturn(java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true));
        when(r500.body()).thenReturn("oops");
        reset(http); when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(r500, r500);
        LlmGateway.Reply bad = new LlmGateway(http).send(req, "x");
        assertThat(bad.ok()).isFalse(); assertThat(bad.statusCode()).isEqualTo(500);
        verify(http, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        // transport error once → retried; body cap
        HttpResponse<String> big = mock(HttpResponse.class);
        when(big.statusCode()).thenReturn(200); when(big.body()).thenReturn("x".repeat(LlmGateway.MAX_BODY_CHARS + 10));
        reset(http);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new java.io.IOException("reset")).thenReturn(big);
        LlmGateway.Reply capped = new LlmGateway(http).send(req, "x");
        assertThat(capped.body()).hasSize(LlmGateway.MAX_BODY_CHARS);
        assertThat(capped.usage().retried()).isTrue();
    }

    @Test @DisplayName("no AI service talks to OpenAI except through the gateway (ratchet)")
    void everyOpenAiCallGoesThroughTheGateway() throws Exception {
        Path main = Path.of("src/main/java/com/churchgeniuspro");
        try (var files = Files.walk(main)) {
            List<Path> offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().endsWith("LlmGateway.java"))
                    .filter(p -> {
                        try {
                            String src = Files.readString(p);
                            return src.contains("api.openai.com") || src.contains("openai.api.base")
                                    ? src.contains("HttpClient.newBuilder") || src.contains("http.send(")
                                    : false;
                        } catch (Exception e) { return false; }
                    }).toList();
            assertThat(offenders).as("OpenAI callers that bypass LlmGateway").isEmpty();
        }
    }

    // ── Permission catalogue ─────────────────────────────────────────────────

    @Test @DisplayName("PermissionCatalog and viewUsers.html PERM_TREE hold exactly the same keys (ratchet)")
    void permissionCatalogInSync() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/viewUsers.html"));
        int start = html.indexOf("const PERM_TREE = [");
        int end = html.indexOf("\n  ];", start);
        assertThat(start).isPositive(); assertThat(end).isGreaterThan(start);
        Matcher m = Pattern.compile("key:\\s*'([^']+)'").matcher(html.substring(start, end));
        java.util.Set<String> page = new java.util.LinkedHashSet<>();
        while (m.find()) page.add(m.group(1));
        assertThat(page).isNotEmpty();
        assertThat(PermissionCatalog.keys()).containsExactlyInAnyOrderElementsOf(page);
        assertThat(PermissionCatalog.isKnown("more.aiassistant")).isTrue();
        assertThat(PermissionCatalog.isKnown("ai.savedLists")).as("not yet — Phase 1 adds it to both").isFalse();
    }

    @Test @DisplayName("AiAuditService.intentFrom reads intent or kind, truncates, and never records free text keys it was not given")
    void intentHelper() {
        MockHttpServletRequest r = req("/api/ai-search/data", staff());
        AiAuditService.intentFrom(r, java.util.Map.of("answer", "secret member list", "intent", "peopleByStatus.deletedMembers"));
        assertThat(r.getAttribute(AiAuditService.INTENT_ATTR)).isEqualTo("peopleByStatus.deletedMembers");
        AiAuditService.intent(r, "x".repeat(200));
        assertThat(String.valueOf(r.getAttribute(AiAuditService.INTENT_ATTR))).hasSize(80);
        assertThat(AiAuditService.channelOf("/api/ai-search/data/extra")).isEqualTo("DATA");
        assertThat(AiAuditService.channelOf("/api/ai-searchx")).isNull();
        assertThat(AiAuditService.channelOf("/api/families")).isNull();
    }
}
