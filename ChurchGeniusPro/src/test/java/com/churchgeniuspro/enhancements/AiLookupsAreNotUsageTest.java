package com.churchgeniuspro.enhancements;

import com.churchgeniuspro.ai.AiRateLimiter;
import com.churchgeniuspro.ai.audit.AiAuditService;
import com.churchgeniuspro.hibernate.AiAuditLog;
import com.churchgeniuspro.repository.AiAuditLogRepository;
import com.churchgeniuspro.webfilter.AiGuardFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 2026-11-16 — AI Activity showed 100 "AI Search" and 100 "Voice" requests for a user who had not used the
 * assistant. Cause: every page asks {@code GET /api/ai-search/nav} and {@code GET /api/voice/features} while it
 * loads, and the AI guard logged (and rate-limited) everything under the AI addresses. Those set-up lookups are
 * not AI usage; real AI operations still are.
 */
@DisplayName("AI Activity — a page's own set-up lookups are not AI usage")
class AiLookupsAreNotUsageTest {

    static MockHttpServletRequest req(String method, String path, MockHttpSession s) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, path);
        r.setRequestURI(path);
        r.setSession(s);
        return r;
    }
    static MockHttpSession staff() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "grace.pastor"); s.setAttribute("role", "SuperAdmin"); s.setAttribute("appClientId", "CHR-1");
        return s;
    }

    @Test @DisplayName("opening 100 pages writes no activity rows and uses none of the rate limit")
    void pageLoadsAreNotLogged() throws Exception {
        AiAuditLogRepository repo = mock(AiAuditLogRepository.class);
        AiRateLimiter limiter = new AiRateLimiter(60, 5000, true);
        AiGuardFilter f = new AiGuardFilter(new AiAuditService(repo, true), limiter, true);
        int reached = 0;
        for (int i = 0; i < 100; i++) {
            for (String p : new String[] { "/api/ai-search/nav", "/api/voice/features", "/api/voice/status" }) {
                MockFilterChain chain = new MockFilterChain();
                MockHttpServletResponse res = new MockHttpServletResponse();
                f.doFilter(req("GET", p, staff()), res, chain);
                assertThat(res.getStatus()).as("%s is never rate limited", p).isEqualTo(200);
                if (chain.getRequest() != null) reached++;
            }
        }
        assertThat(reached).as("every lookup reached its controller").isEqualTo(300);
        verify(repo, never()).save(any());

        // the 60-a-minute allowance is untouched: 60 real requests still pass, the 61st is refused
        for (int i = 0; i < 60; i++) {
            MockHttpServletResponse res = new MockHttpServletResponse();
            f.doFilter(req("POST", "/api/ai-search/data", staff()), res, new MockFilterChain());
            assertThat(res.getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse over = new MockHttpServletResponse();
        f.doFilter(req("POST", "/api/ai-search/data", staff()), over, new MockFilterChain());
        assertThat(over.getStatus()).isEqualTo(429);
    }

    @Test @DisplayName("real AI operations are still logged once each, under the right user and church")
    void realUsageStillLogged() throws Exception {
        AiAuditLogRepository repo = mock(AiAuditLogRepository.class);
        AiGuardFilter f = new AiGuardFilter(new AiAuditService(repo, true), new AiRateLimiter(60, 5000, true), true);
        String[][] real = {
                { "POST", "/api/ai-search/data" }, { "POST", "/api/ai-search" }, { "POST", "/api/voice/command" },
                { "POST", "/api/voice/extract" }, { "POST", "/api/ai-assist" }, { "POST", "/api/ai-search/saved-lists" },
                { "POST", "/api/ai-search/saved-lists/7/run" }, { "GET", "/api/ai-search/saved-lists/7/export" },
                { "POST", "/api/ai/actions/confirm" } };
        for (String[] r : real) f.doFilter(req(r[0], r[1], staff()), new MockHttpServletResponse(), new MockFilterChain());
        ArgumentCaptor<AiAuditLog> cap = ArgumentCaptor.forClass(AiAuditLog.class);
        verify(repo, times(real.length)).save(cap.capture());
        assertThat(cap.getAllValues()).allSatisfy(row -> {
            assertThat(row.getActor()).isEqualTo("grace.pastor");
            assertThat(row.getClientId()).isEqualTo("CHR-1");
            assertThat(row.getOutcome()).isEqualTo("OK");
        });
    }

    @Test @DisplayName("only GET on the exact lookup paths is exempt")
    void exemptionIsNarrow() {
        for (String p : AiAuditService.LOOKUP_PATHS) {
            assertThat(AiAuditService.isLookup("GET", p)).as(p).isTrue();
            assertThat(AiAuditService.isLookup("POST", p)).as("POST " + p).isFalse();
            assertThat(AiAuditService.isLookup("DELETE", p)).as("DELETE " + p).isFalse();
            assertThat(AiAuditService.isLookup("GET", p + "/x")).as(p + "/x").isFalse();
            assertThat(AiAuditService.channelOf(p)).as("%s is still a guarded AI address", p).isNotNull();
        }
        assertThat(AiAuditService.isLookup("GET", "/api/voice/command")).isFalse();
        assertThat(AiAuditService.isLookup("GET", "/api/ai-search/data")).isFalse();
        assertThat(AiAuditService.isLookup("GET", null)).isFalse();
        assertThat(AiAuditService.isLookup(null, "/api/ai-search/nav")).isFalse();
    }

    @Test @DisplayName("a temporary-access session is still refused the lookups, without an activity row")
    void tempAccessStillRefused() throws Exception {
        AiAuditLogRepository repo = mock(AiAuditLogRepository.class);
        AiGuardFilter f = new AiGuardFilter(new AiAuditService(repo, true), new AiRateLimiter(60, 5000, true), true);
        MockHttpSession s = staff(); s.setAttribute("tempAccessId", 7);
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req("GET", "/api/ai-search/nav", s), res, chain);
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
        verify(repo, never()).save(any());
    }

    @Test @DisplayName("every lookup the pages make on load is on the exempt list (guards against a new one being logged)")
    void pagesOnlyAutoCallExemptLookups() throws Exception {
        Path st = Path.of("src/main/resources/static");
        // GET fetches (no method given) to AI addresses in the scripts every page loads
        Pattern get = Pattern.compile("fetch\\('(/api/(?:ai-search|voice|ai/actions)[^'?]*)'\\s*(?:\\)|,\\s*\\{\\s*(?:credentials|cache)[^}]*\\}\\s*\\))");
        for (String file : new String[] { "ai-assistant.js", "voice-flags.js", "voice-openai.js", "ai-chat.js" }) {
            Matcher m = get.matcher(Files.readString(st.resolve(file)));
            while (m.find()) {
                assertThat(AiAuditService.isLookup("GET", m.group(1)))
                        .as("%s calls GET %s on its own — it must be on AiAuditService.LOOKUP_PATHS or be a real AI operation", file, m.group(1))
                        .isTrue();
            }
        }
    }
}
