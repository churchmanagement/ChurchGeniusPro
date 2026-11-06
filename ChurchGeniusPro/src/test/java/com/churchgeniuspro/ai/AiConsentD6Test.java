package com.churchgeniuspro.ai;

import com.churchgeniuspro.ai.audit.AiAuditService;
import com.churchgeniuspro.ai.consent.AiConsentGate;
import com.churchgeniuspro.ai.consent.AiConsentNotices;
import com.churchgeniuspro.ai.consent.AiConsentNotices.Type;
import com.churchgeniuspro.ai.consent.AiConsentPaths;
import com.churchgeniuspro.ai.consent.AiConsentService;
import com.churchgeniuspro.ai.consent.AiVoiceTokens;
import com.churchgeniuspro.ai.consent.AppShellDetector;
import com.churchgeniuspro.controller.AiConsentController;
import com.churchgeniuspro.hibernate.AiConsent;
import com.churchgeniuspro.hibernate.SignUp;
import com.churchgeniuspro.repository.AiAuditLogRepository;
import com.churchgeniuspro.repository.AiConsentRepository;
import com.churchgeniuspro.repository.LoginRepository;
import com.churchgeniuspro.webfilter.AiGuardFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * App Store readiness D6 (approved 2026-11-06): AI data-sharing consent in the iOS / Android
 * app — notices pinned to their versions, server-side enforcement before any controller,
 * website unchanged, consent per INDIVIDUAL login, one-time voice tokens (replay, expiry,
 * mismatch), the people-free voice hint and withdrawal.
 */
@DisplayName("D6 — AI data-sharing consent")
class AiConsentD6Test {

    static final String CID = "CHR-D6";
    static final String APP_UA = "Mozilla/5.0 (iPhone) AppleWebKit/605.1.15 ChurchGeniusProApp/1.0 (ios)";
    static final String WEB_UA = "Mozilla/5.0 (iPhone) AppleWebKit/605.1.15 Version/18.0 Mobile Safari/604.1";

    /** In-memory ai_consent table. */
    Map<String, AiConsent> rows;
    AiConsentRepository repo;
    LoginRepository logins;
    AiConsentService service;
    AiVoiceTokens tokens;
    AiConsentGate gate;
    AiGuardFilter filter;
    AtomicLong now;

    @BeforeEach
    void setUp() throws Exception {
        rows = new HashMap<>();
        repo = mock(AiConsentRepository.class);
        when(repo.findByAppClientIdAndLoginIdAndNoticeType(anyString(), any(), anyString())).thenAnswer(i ->
                Optional.ofNullable(rows.get(i.getArgument(0) + "|" + i.getArgument(1) + "|" + i.getArgument(2))));
        when(repo.findByAppClientIdAndLoginId(anyString(), any())).thenAnswer(i -> {
            List<AiConsent> out = new ArrayList<>();
            rows.forEach((k, v) -> { if (k.startsWith(i.getArgument(0) + "|" + i.getArgument(1) + "|")) out.add(v); });
            return out;
        });
        when(repo.save(any())).thenAnswer(i -> { AiConsent c = i.getArgument(0);
            rows.put(c.getAppClientId() + "|" + c.getLoginId() + "|" + c.getNoticeType(), c); return c; });
        logins = mock(LoginRepository.class);
        service = new AiConsentService(repo, logins);
        now = new AtomicLong(1_000_000L);
        Constructor<AiVoiceTokens> ctor = AiVoiceTokens.class.getDeclaredConstructor(LongSupplier.class);
        ctor.setAccessible(true);
        tokens = ctor.newInstance((LongSupplier) now::get);
        gate = new AiConsentGate(service, tokens);
        filter = new AiGuardFilter(new AiAuditService(mock(AiAuditLogRepository.class), true), new AiRateLimiter(1000, 100000, true), true);
        filter.setConsentGate(gate);
    }

    // ── sessions ────────────────────────────────────────────────────────────────

    static MockHttpSession staff(int loginId) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "pat" + loginId); s.setAttribute("role", "Admin"); s.setAttribute("appClientId", CID);
        s.setAttribute("clientId", "USR-" + loginId); s.setAttribute(AiConsentService.SESSION_LOGIN_ID, loginId);
        return s;
    }
    static MockHttpSession churchOwner(int loginId) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "owner"); s.setAttribute("church", true); s.setAttribute("role", "church");
        s.setAttribute("clientId", CID); s.setAttribute(AiConsentService.SESSION_LOGIN_ID, loginId);
        return s;
    }
    static MockHttpSession member(int loginId) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "mem" + loginId); s.setAttribute("role", "Member"); s.setAttribute("memberId", 50 + loginId);
        s.setAttribute("memberRole", "Head"); s.setAttribute("appClientId", CID); s.setAttribute("clientId", "MBR-" + loginId);
        s.setAttribute(AiConsentService.SESSION_LOGIN_ID, loginId);
        return s;
    }

    static MockHttpServletRequest req(String path, MockHttpSession s, String ua) {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", path);
        r.setRequestURI(path);
        if (ua != null) r.addHeader("User-Agent", ua);
        if (s != null) r.setSession(s);
        return r;
    }

    /** Runs the request through the filter: [status, reachedController, body]. */
    Object[] run(MockHttpServletRequest r) throws Exception {
        boolean[] reached = { false };
        String[] seenBody = { null };
        Object[] accepted = { null };
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(r, res, (rq, rs) -> {
            reached[0] = true;
            accepted[0] = rq.getAttribute(AiConsentGate.ACCEPTED_ATTR);
            try { seenBody[0] = new String(rq.getInputStream().readAllBytes(), StandardCharsets.UTF_8); } catch (Exception e) { seenBody[0] = "ERR"; }
        });
        return new Object[] { res.getStatus(), reached[0], res.getContentAsString(), seenBody[0], accepted[0] };
    }

    void agree(MockHttpSession s, Type t) {
        var w = service.login(req("/x", s, APP_UA)).orElseThrow();
        assertThat(service.agree(w, t, AiConsentNotices.current(t).version(), "IOS")).isTrue();
    }

    // ── notices ─────────────────────────────────────────────────────────────────

    @Test @DisplayName("the three notices carry exactly the approved wording, version 1, and the text is pinned to its version")
    void noticeTextPinned() {
        var v = AiConsentNotices.current(Type.VOICE);
        assertThat(v.title()).isEqualTo("AI Voice Entry – Data Sharing Notice");
        assertThat(v.paragraphs()).containsExactly(
                "To process your voice request, ChurchGeniusPro sends your audio recording to OpenAI for transcription and processing. It may also send a short list of your church's fund, expense category and payment method names to improve recognition.",
                "Your recording may contain names, financial information or other personal details.",
                "Do you want to continue?");
        var a = AiConsentNotices.current(Type.ASSISTANT);
        assertThat(a.title()).isEqualTo("AI Assistant – Data Sharing Notice");
        assertThat(String.join(" ", a.paragraphs())).isEqualTo(
                "To answer your question or draft text, ChurchGeniusPro sends what you type, including your recent messages in a conversation, to OpenAI for processing. This may include names, financial information or other personal details. Do you want to continue?");
        var sc = AiConsentNotices.current(Type.SCAN);
        assertThat(sc.title()).isEqualTo("Document Scan – Data Sharing Notice");
        assertThat(String.join(" ", sc.paragraphs())).isEqualTo(
                "To read this document, ChurchGeniusPro sends the photo or file to OpenAI for processing. It may contain names, account numbers, financial information or other personal details. Do you want to continue?");
        assertThat(AiConsentNotices.BUTTON_CANCEL).isEqualTo("Cancel");
        assertThat(AiConsentNotices.BUTTON_AGREE).isEqualTo("Agree & Continue");
        // Changing a notice's text without raising its version fails here: update BOTH the version and this pin.
        Map<Type, String> pinned = Map.of(
                Type.VOICE, "1:ff4f3db981085156e107497913238e7c02170e914707b0929d3952a452a1ec38",
                Type.ASSISTANT, "1:4aa6400edaec7d483d935da2eb900aaf93c5e71e39decebb0bea38cc5b598ba9",
                Type.SCAN, "1:62075c2f08bd4b0a384b4d2338917079dad1b80f294aac58e3929e1342d3ac3d");
        for (Type t : Type.values()) {
            assertThat(AiConsentNotices.current(t).version() + ":" + AiConsentNotices.fingerprint(t))
                    .as("notice %s changed — raise its version and update the pin", t).isEqualTo(pinned.get(t));
        }
    }

    @Test @DisplayName("coverage: every OpenAI-calling AI path needs a notice, each is seen by the AI filter, and the no-model paths need none")
    void coverage() {
        Map<String, Type> expected = new java.util.LinkedHashMap<>();
        expected.put("/api/voice/command", Type.VOICE);
        for (String p : List.of("/api/ai-search", "/api/ai-search/data", "/api/ai/write", "/api/ai/forms/draft", "/api/workflows/draft",
                "/api/ai-assist", "/api/help/assist", "/api/voice/extract")) expected.put(p, Type.ASSISTANT);
        for (String p : List.of("/api/income/check-scan", "/api/expense/check-scan", "/api/bank-import/scan", "/api/family/scan-extract",
                "/api/kids-ministry/child-scan-extract")) expected.put(p, Type.SCAN);
        assertThat(AiConsentPaths.all().keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((p, t) -> {
            assertThat(AiConsentPaths.requirementFor(p).type()).as(p).isEqualTo(t);
            assertThat(AiAuditService.channelOf(p)).as("AiGuardFilter must see %s", p).isNotNull();
        });
        assertThat(AiConsentPaths.requirementFor("/api/ai-assist").kind()).isEqualTo(AiConsentPaths.Kind.DUAL);
        assertThat(AiConsentPaths.requirementFor("/api/help/assist").kind()).isEqualTo(AiConsentPaths.Kind.DUAL);
        assertThat(AiConsentPaths.requirementFor("/api/voice/extract").kind()).isEqualTo(AiConsentPaths.Kind.DUAL);
        for (String none : List.of("/api/voice/status", "/api/voice/features", "/api/ai-search/confirm", "/api/ai-search/filter",
                "/api/ai-search/saved-lists", "/api/ai/actions/confirm", "/api/ai/forms/validate", "/api/income/check-parse",
                "/api/expense/check-parse", "/api/bank-import/scan-parse", "/api/ai/consent")) {
            assertThat(AiConsentPaths.requirementFor(none).kind()).as(none).isEqualTo(AiConsentPaths.Kind.NONE);
        }
    }

    // ── enforcement ─────────────────────────────────────────────────────────────

    @Test @DisplayName("website: every covered path goes straight to the controller — no consent lookup, no wrapping, behaviour unchanged")
    void websiteUnchanged() throws Exception {
        for (String p : AiConsentPaths.all().keySet()) {
            MockHttpServletRequest r = req(p, staff(1), WEB_UA);
            r.setContent("{\"query\":\"hello\"}".getBytes(StandardCharsets.UTF_8));
            Object[] out = run(r);
            assertThat(out[1]).as(p).isEqualTo(true);
            assertThat(out[0]).isEqualTo(200);
            assertThat(out[4]).as("no consent decision on the web").isNull();
        }
        verify(repo, never()).findByAppClientIdAndLoginIdAndNoticeType(anyString(), any(), anyString());
        assertThat(rows).isEmpty();
    }

    @Test @DisplayName("app without consent: every covered path is refused 403 AI_CONSENT_REQUIRED naming its notice, before the controller")
    void appRefusedWithoutConsent() throws Exception {
        for (var e : AiConsentPaths.all().entrySet()) {
            Object[] out = run(req(e.getKey(), staff(1), APP_UA));
            assertThat(out[1]).as(e.getKey()).isEqualTo(false);
            assertThat(out[0]).isEqualTo(403);
            assertThat((String) out[2]).contains("AI_CONSENT_REQUIRED").contains("\"notice\":\"" + e.getValue().type().name() + "\"")
                    .contains("\"version\":1");
        }
    }

    @Test @DisplayName("consent opens only its own notice type; a voice consent does not open the assistant or scans")
    void perType() throws Exception {
        agree(staff(1), Type.VOICE);
        MockHttpSession s = staff(1);
        assertThat(run(req("/api/voice/command", s, APP_UA))[1]).isEqualTo(true);
        assertThat(run(req("/api/ai/write", s, APP_UA))[0]).isEqualTo(403);
        assertThat(run(req("/api/income/check-scan", s, APP_UA))[0]).isEqualTo(403);
        agree(staff(1), Type.SCAN);
        assertThat(run(req("/api/income/check-scan", s, APP_UA))[1]).isEqualTo(true);
        assertThat(run(req("/api/ai/write", s, APP_UA))[0]).isEqualTo(403);
        agree(staff(1), Type.ASSISTANT);
        Object[] out = run(req("/api/ai/write", s, APP_UA));
        assertThat(out[1]).isEqualTo(true);
        assertThat(out[4]).isEqualTo("ASSISTANT");
    }

    @Test @DisplayName("app detection: the header alone counts; the session stays an app session after the marker disappears (sticky)")
    void appSignals() throws Exception {
        MockHttpServletRequest h = req("/api/ai/write", staff(1), null);
        h.addHeader(AppShellDetector.HEADER, "app");
        assertThat(run(h)[0]).isEqualTo(403);

        MockHttpSession s = staff(2);
        assertThat(run(req("/api/ai/write", s, APP_UA))[0]).isEqualTo(403);        // marks the session
        assertThat(s.getAttribute(AppShellDetector.SESSION_ATTR)).isEqualTo("IOS");
        Object[] later = run(req("/api/ai/write", s, WEB_UA));                         // marker gone mid-session
        assertThat(later[1]).isEqualTo(false);
        assertThat(later[0]).isEqualTo(403);

        MockHttpSession login = new MockHttpSession();
        AppShellDetector.markSession(req("/login", null, APP_UA), login);
        assertThat(login.getAttribute(AppShellDetector.SESSION_ATTR)).isEqualTo("IOS");
        MockHttpSession web = new MockHttpSession();
        AppShellDetector.markSession(req("/login", null, WEB_UA), web);
        assertThat(web.getAttribute(AppShellDetector.SESSION_ATTR)).isNull();
    }

    @Test @DisplayName("fails closed: no gate, or a consent store that throws, refuses the app request; the web is untouched")
    void failsClosed() throws Exception {
        AiGuardFilter noGate = new AiGuardFilter(new AiAuditService(mock(AiAuditLogRepository.class), true), new AiRateLimiter(1000, 100000, true), true);
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean[] reached = { false };
        noGate.doFilter(req("/api/ai/write", staff(1), APP_UA), res, (a, b) -> reached[0] = true);
        assertThat(reached[0]).isFalse();
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(res.getContentAsString()).contains(AiConsentGate.CODE_FAILED);
        MockHttpServletResponse web = new MockHttpServletResponse();
        noGate.doFilter(req("/api/ai/write", staff(1), WEB_UA), web, (a, b) -> reached[0] = true);
        assertThat(reached[0]).isTrue();

        when(repo.findByAppClientIdAndLoginIdAndNoticeType(anyString(), any(), anyString())).thenThrow(new RuntimeException("db down"));
        Object[] out = run(req("/api/ai/write", staff(1), APP_UA));
        assertThat(out[1]).isEqualTo(false);
        assertThat((String) out[2]).contains(AiConsentGate.CODE_FAILED);
    }

    @Test @DisplayName("a version change or a withdrawal asks again; agreeing to an old version is refused")
    void versionAndWithdraw() throws Exception {
        agree(staff(1), Type.ASSISTANT);
        rows.values().forEach(c -> c.setNoticeVersion(0));                    // stored answer is for an older text
        assertThat(run(req("/api/ai/write", staff(1), APP_UA))[0]).isEqualTo(403);
        var w = service.login(req("/x", staff(1), APP_UA)).orElseThrow();
        assertThat(service.agree(w, Type.ASSISTANT, 0, "IOS")).isFalse();
        assertThat(service.agree(w, Type.ASSISTANT, 1, "IOS")).isTrue();
        assertThat(run(req("/api/ai/write", staff(1), APP_UA))[1]).isEqualTo(true);
        assertThat(service.withdraw(w, List.of())).isEqualTo(1);
        assertThat(run(req("/api/ai/write", staff(1), APP_UA))[0]).isEqualTo(403);
        assertThat(rows.values().iterator().next().getWithdrawnAt()).isNotNull();
    }

    // ── individual consent ──────────────────────────────────────────────────────

    @Test @DisplayName("consent is per individual login: another staff login, the church owner, a member and another church are all asked separately")
    void perLogin() throws Exception {
        agree(staff(1), Type.ASSISTANT);
        assertThat(run(req("/api/ai/write", staff(1), APP_UA))[1]).isEqualTo(true);
        assertThat(run(req("/api/ai/write", staff(2), APP_UA))[0]).as("another staff login").isEqualTo(403);
        assertThat(run(req("/api/help/assist", churchOwner(3), APP_UA))[0]).as("the church owner login").isEqualTo(403);
        assertThat(run(req("/api/help/assist", member(4), APP_UA))[0]).as("a member login").isEqualTo(403);
        MockHttpSession other = staff(1); other.setAttribute("appClientId", "CHR-OTHER");
        assertThat(run(req("/api/ai/write", other, APP_UA))[0]).as("same login id, other church").isEqualTo(403);

        agree(churchOwner(3), Type.ASSISTANT);
        assertThat(rows.get(CID + "|3|ASSISTANT").getLoginKind()).isEqualTo("CHURCH");
        assertThat(run(req("/api/ai/write", staff(2), APP_UA))[0]).as("the owner's answer does not cover staff").isEqualTo(403);
        agree(member(4), Type.ASSISTANT);
        assertThat(rows.get(CID + "|4|ASSISTANT").getLoginKind()).isEqualTo("MEMBER");
        assertThat(rows.get(CID + "|1|ASSISTANT").getLoginKind()).isEqualTo("STAFF");
    }

    @Test @DisplayName("the login comes from the session only: the body cannot name one; old sessions resolve by their own login row or are refused; temp / NTAG / Kids sessions cannot consent")
    void loginResolution() {
        AiConsentController c = new AiConsentController(service);
        MockHttpServletRequest r = req("/api/ai/consent", staff(7), APP_UA);
        ResponseEntity<Map<String, Object>> ok = c.agree(new HashMap<>(Map.of("type", "SCAN", "version", 1, "loginId", 999)), r);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(rows).containsKey(CID + "|7|SCAN").doesNotContainKey(CID + "|999|SCAN");
        assertThat(rows.get(CID + "|7|SCAN").getSource()).isEqualTo("IOS");
        assertThat(c.agree(new HashMap<>(Map.of("type", "SCAN", "version", 2)), req("/x", staff(7), APP_UA)).getStatusCode().value()).isEqualTo(409);
        assertThat(c.agree(new HashMap<>(Map.of("type", "BOGUS", "version", 1)), req("/x", staff(7), APP_UA)).getStatusCode().value()).isEqualTo(400);

        // session opened before loginId existed: resolved by its own client id + username
        MockHttpSession old = staff(8); old.removeAttribute(AiConsentService.SESSION_LOGIN_ID);
        SignUp su = new SignUp(); su.setId(8); su.setUsername("PAT8"); su.setClientId("USR-8");
        when(logins.findByClientId("USR-8")).thenReturn(Optional.of(su));
        assertThat(service.login(req("/x", old, APP_UA)).orElseThrow().loginId()).isEqualTo(8);
        assertThat(old.getAttribute(AiConsentService.SESSION_LOGIN_ID)).isEqualTo(8);
        MockHttpSession wrongUser = staff(9); wrongUser.removeAttribute(AiConsentService.SESSION_LOGIN_ID);
        SignUp other = new SignUp(); other.setId(9); other.setUsername("someone-else"); other.setClientId("USR-9");
        when(logins.findByClientId("USR-9")).thenReturn(Optional.of(other));
        assertThat(service.login(req("/x", wrongUser, APP_UA))).isEmpty();
        MockHttpSession dup = staff(10); dup.removeAttribute(AiConsentService.SESSION_LOGIN_ID);
        when(logins.findByClientId("USR-10")).thenThrow(new org.springframework.dao.IncorrectResultSizeDataAccessException(1));
        assertThat(service.login(req("/x", dup, APP_UA))).isEmpty();

        MockHttpSession temp = staff(11); temp.setAttribute("tempAccessId", 3);
        MockHttpSession ntag = staff(12); ntag.setAttribute("ntagCredId", 3);
        MockHttpSession kid = member(13); kid.setAttribute("memberRole", "Child");
        for (MockHttpSession s : List.of(temp, ntag, kid)) assertThat(service.login(req("/x", s, APP_UA))).isEmpty();
        assertThat(c.agree(new HashMap<>(Map.of("type", "SCAN", "version", 1)), req("/x", kid, APP_UA)).getStatusCode().value()).isEqualTo(403);
    }

    @Test @DisplayName("Kids Portal sessions in the app get AI_CONSENT_UNAVAILABLE and never reach the controller")
    void kidsRefused() throws Exception {
        MockHttpSession kid = member(13); kid.setAttribute("memberRole", "Child");
        Object[] out = run(req("/api/help/assist", kid, APP_UA));
        assertThat(out[1]).isEqualTo(false);
        assertThat((String) out[2]).contains(AiConsentGate.CODE_UNAVAILABLE);
    }

    // ── voice tokens ────────────────────────────────────────────────────────────

    @Test @DisplayName("voice tokens: valid once; replay, expiry, other session, other login, other church, other operation and other text are refused")
    void voiceTokens() {
        String sid = "S1";
        String t = tokens.issue(sid, CID, 1, "home", "Where is the tax report?");
        assertThat(t).startsWith("vt_");
        assertThat(tokens.consume(t, sid, CID, 1, Set.of("home", "help"), "where is the TAX report")).isEqualTo(AiVoiceTokens.Result.OK);
        assertThat(tokens.consume(t, sid, CID, 1, Set.of("home"), "where is the tax report")).as("replay").isEqualTo(AiVoiceTokens.Result.REUSED);

        String e = tokens.issue(sid, CID, 1, "home", "hello");
        now.addAndGet(AiVoiceTokens.TTL_MILLIS + 1);
        assertThat(tokens.consume(e, sid, CID, 1, Set.of("home"), "hello")).isEqualTo(AiVoiceTokens.Result.EXPIRED);

        for (Object[] c : new Object[][] {
                { "S2", CID, 1, Set.of("home"), "hello" },          // other session
                { sid, CID, 2, Set.of("home"), "hello" },           // other login
                { sid, "CHR-X", 1, Set.of("home"), "hello" },       // other church
                { sid, CID, 1, Set.of("meeting"), "hello" },        // other operation
                { sid, CID, 1, Set.of("home"), "hello there" } }) { // other text
            String m = tokens.issue(sid, CID, 1, "home", "hello");
            @SuppressWarnings("unchecked") Set<String> ops = (Set<String>) c[3];
            assertThat(tokens.consume(m, (String) c[0], (String) c[1], (Integer) c[2], ops, (String) c[4])).isEqualTo(AiVoiceTokens.Result.MISMATCH);
            assertThat(tokens.consume(m, sid, CID, 1, Set.of("home"), "hello")).as("a mismatched token is burnt").isEqualTo(AiVoiceTokens.Result.REUSED);
        }
        assertThat(tokens.consume("vt_made_up", sid, CID, 1, Set.of("home"), "hello")).isEqualTo(AiVoiceTokens.Result.UNKNOWN);
        assertThat(tokens.issue(sid, CID, 1, "home", "  ...  ")).as("no token for empty speech").isNull();
    }

    @Test @DisplayName("voice-or-typed paths: the server decides — a token issued after Voice consent counts as voice once; a client voice flag or a matching transcript without the token needs the Assistant notice")
    void dualClassification() throws Exception {
        agree(staff(1), Type.VOICE);
        MockHttpSession s = staff(1);

        // the voice request itself: Voice consent passes and the controller may issue a token
        MockHttpServletRequest vc = req("/api/voice/command", s, APP_UA);
        boolean[] issued = { false };
        filter.doFilter(vc, new MockHttpServletResponse(), (rq, rs) -> issued[0] = Boolean.TRUE.equals(rq.getAttribute(AiVoiceTokens.ISSUE_ATTR)));
        assertThat(issued[0]).isTrue();
        String token = gate.issueVoiceToken(vc, "home", "Show me the tax report");
        assertThat(token).isNotNull();
        assertThat(gate.issueVoiceToken(vc, "income", "fifty dollars")).as("income has no follow-up request").isNull();
        MockHttpServletRequest webVc = req("/api/voice/command", s, WEB_UA);
        assertThat(gate.issueVoiceToken(webVc, "home", "x")).as("no token unless the filter allowed it").isNull();

        // the follow-up with the token: voice, body still readable by the controller
        MockHttpServletRequest fu = req("/api/ai-assist", s, APP_UA);
        String body = "{\"query\":\"Show me the tax report\",\"voice\":true}";
        fu.setContent(body.getBytes(StandardCharsets.UTF_8));
        fu.addHeader(AiVoiceTokens.HEADER, token);
        Object[] out = run(fu);
        assertThat(out[1]).isEqualTo(true);
        assertThat(out[4]).isEqualTo("VOICE");
        assertThat(out[3]).isEqualTo(body);

        // replaying the same token
        MockHttpServletRequest replay = req("/api/ai-assist", s, APP_UA);
        replay.setContent(body.getBytes(StandardCharsets.UTF_8));
        replay.addHeader(AiVoiceTokens.HEADER, token);
        Object[] r2 = run(replay);
        assertThat(r2[1]).isEqualTo(false);
        assertThat((String) r2[2]).contains(AiConsentGate.CODE_TOKEN).contains("\"reason\":\"reused\"");

        // the same words typed, with a client "voice" flag and no token: Assistant notice
        MockHttpServletRequest typed = req("/api/ai-assist", s, APP_UA);
        typed.setContent(body.getBytes(StandardCharsets.UTF_8));
        Object[] r3 = run(typed);
        assertThat(r3[1]).isEqualTo(false);
        assertThat((String) r3[2]).contains("\"notice\":\"ASSISTANT\"");

        // a token presented with different text, or for another operation, is refused
        String t2 = gate.issueVoiceToken(vc, "home", "open the calendar");
        MockHttpServletRequest edited = req("/api/help/assist", s, APP_UA);
        edited.setContent("{\"query\":\"show all giving records\"}".getBytes(StandardCharsets.UTF_8));
        edited.addHeader(AiVoiceTokens.HEADER, t2);
        assertThat((String) run(edited)[2]).contains("\"reason\":\"mismatch\"");
        String t3 = gate.issueVoiceToken(vc, "home", "create a bible study");
        MockHttpServletRequest wrongOp = req("/api/voice/extract", s, APP_UA);
        wrongOp.setContent("{\"text\":\"create a bible study\"}".getBytes(StandardCharsets.UTF_8));
        wrongOp.addHeader(AiVoiceTokens.HEADER, t3);
        assertThat((String) run(wrongOp)[2]).contains("\"reason\":\"mismatch\"");

        // a token of another session is refused
        String t4 = gate.issueVoiceToken(vc, "help", "how do I add a family");
        MockHttpServletRequest other = req("/api/help/assist", staff(1), APP_UA);
        other.setContent("{\"query\":\"how do I add a family\"}".getBytes(StandardCharsets.UTF_8));
        other.addHeader(AiVoiceTokens.HEADER, t4);
        assertThat((String) run(other)[2]).contains("\"reason\":\"mismatch\"");

        // with Assistant consent, typed text passes as ASSISTANT
        agree(staff(1), Type.ASSISTANT);
        MockHttpServletRequest typed2 = req("/api/ai-assist", s, APP_UA);
        typed2.setContent(body.getBytes(StandardCharsets.UTF_8));
        Object[] r4 = run(typed2);
        assertThat(r4[1]).isEqualTo(true);
        assertThat(r4[4]).isEqualTo("ASSISTANT");

        // voice consent withdrawn after the token was issued → the follow-up asks for Voice again
        String t5 = gate.issueVoiceToken(vc, "home", "open income");
        service.withdraw(service.login(req("/x", staff(1), APP_UA)).orElseThrow(), List.of(Type.VOICE));
        MockHttpServletRequest after = req("/api/ai-assist", s, APP_UA);
        after.setContent("{\"query\":\"open income\"}".getBytes(StandardCharsets.UTF_8));
        after.addHeader(AiVoiceTokens.HEADER, t5);
        assertThat((String) run(after)[2]).contains("\"notice\":\"VOICE\"");
    }

    // ── controller / status / withdraw ─────────────────────────────────────────

    @Test @DisplayName("status lists the three notices with this login's state; withdraw one or all; the website reports app=false")
    void controllerFlow() {
        AiConsentController c = new AiConsentController(service);
        MockHttpSession s = staff(5);
        c.agree(new HashMap<>(Map.of("type", "VOICE", "version", 1)), req("/x", s, APP_UA));
        c.agree(new HashMap<>(Map.of("type", "SCAN", "version", 1)), req("/x", s, APP_UA));
        Map<String, Object> st = c.status(req("/x", s, APP_UA)).getBody();
        assertThat(st).containsEntry("app", true).containsEntry("available", true);
        @SuppressWarnings("unchecked") List<Map<String, Object>> n = (List<Map<String, Object>>) st.get("notices");
        assertThat(n).extracting(m -> m.get("type")).containsExactly("VOICE", "ASSISTANT", "SCAN");
        assertThat(n).extracting(m -> m.get("agreed")).containsExactly(true, false, true);
        assertThat(n.get(0)).containsEntry("agree", "Agree & Continue").containsEntry("cancel", "Cancel");
        assertThat(c.withdraw(new HashMap<>(Map.of("type", "VOICE")), req("/x", s, APP_UA)).getBody()).containsEntry("withdrawn", 1);
        assertThat(c.withdraw(null, req("/x", s, APP_UA)).getBody()).containsEntry("withdrawn", 1);
        @SuppressWarnings("unchecked") List<Map<String, Object>> after = (List<Map<String, Object>>) c.status(req("/x", s, APP_UA)).getBody().get("notices");
        assertThat(after).extracting(m -> m.get("agreed")).containsExactly(false, false, false);
        assertThat(c.status(req("/x", staff(6), WEB_UA)).getBody()).containsEntry("app", false);
        assertThat(c.status(new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(401);
    }

    // ── voice hint, wiring ─────────────────────────────────────────────────────

    @Test @DisplayName("decision C: in the app the voice hint carries no people's names; the website hint is unchanged")
    void voiceHint() throws Exception {
        var members = mock(com.churchgeniuspro.repository.FamilyMemberRepository.class);
        var funds = mock(com.churchgeniuspro.repository.SubSourceRepository.class);
        var types = mock(com.churchgeniuspro.repository.TransactionTypeRepository.class);
        com.churchgeniuspro.hibernate.FamilyMember m = new com.churchgeniuspro.hibernate.FamilyMember();
        m.setFirstName("Sarah"); m.setLastName("Lee");
        when(members.findContributorsByAppUser(CID)).thenReturn(List.of(m));
        var vocab = new com.churchgeniuspro.service.VoiceVocabularyService(members, funds, types);
        assertThat(vocab.promptFor(CID, "income", "")).contains("Sarah Lee");
        assertThat(vocab.promptFor(CID, "income", "", true)).contains("Sarah Lee");
        String app = vocab.promptFor(CID, "income", "", false);
        assertThat(app).doesNotContain("Sarah").doesNotContain("Lee");
        String src = Files.readString(Path.of("src/main/java/com/churchgeniuspro/controller/VoiceCommandController.java"));
        assertThat(src).contains("boolean includePeople = !com.churchgeniuspro.ai.consent.AppShellDetector.isApp(request);")
                .contains("vocabulary.promptFor(clientId, context, expecting, includePeople)");
    }

    @Test @DisplayName("wiring: sign-in records the login id and the sticky flag; the filter config sets the gate; members may call /api/ai/consent; migration block; pages")
    void wiring() throws Exception {
        String login = Files.readString(Path.of("src/main/java/com/churchgeniuspro/controller/LoginController.java"));
        assertThat(login).contains("session.setAttribute(com.churchgeniuspro.ai.consent.AiConsentService.SESSION_LOGIN_ID, user.getId());");
        assertThat(login.split("AppShellDetector.markSession\\(request, ", -1).length - 1).isEqualTo(5);
        assertThat(Files.readString(Path.of("src/main/java/com/churchgeniuspro/webfilter/AiGuardFilterConfig.java"))).contains("filter.setConsentGate(consentGate);");
        assertThat(Files.readString(Path.of("src/main/java/com/churchgeniuspro/webfilter/MemberApiScopeFilter.java"))).contains("\"/api/ai/consent\"");
        String sql = Files.readString(Path.of("scripts/sql/migrate_production.sql"));
        assertThat(sql).contains("CREATE TABLE IF NOT EXISTS ai_consent").contains("CREATE UNIQUE INDEX IF NOT EXISTS ux_ai_consent_login_type ON ai_consent (app_client_id, login_id, notice_type)");

        Path st = Path.of("src/main/resources/static");
        String voice = Files.readString(st.resolve("voice-openai.js"));
        assertThat(voice.indexOf("window.CGP_aiConsent.ensure('VOICE')")).as("consent before the microphone")
                .isPositive().isLessThan(voice.indexOf("navigator.mediaDevices.getUserMedia({ audio: true })"));
        assertThat(voice).contains("window.CGP_aiConsent.rememberVoice(");
        String consent = Files.readString(st.resolve("ai-consent.js"));
        assertThat(consent).contains("if (isApp()) {").contains("install();");
        assertThat(consent.indexOf("if (!isApp()) return Promise.resolve(true);")).isPositive();
        assertThat(Files.readString(st.resolve("native-bridge.js")).indexOf("h.set('X-CGP-Client', 'app');"))
                .isGreaterThan(Files.readString(st.resolve("native-bridge.js")).indexOf("if (!isNative) return;"));
        assertThat(Files.readString(st.resolve("shell.js"))).contains("add('/ai-consent.js', 'ai-consent-script');");
        assertThat(Files.readString(st.resolve("memberHome.html"))).contains("<script src=\"/ai-consent.js\"></script>").contains("CGP_aiConsent.openSettings()");
        assertThat(Files.readString(st.resolve("aiActivity.html"))).contains("id=\"aiShareCard\"");
        for (String p : List.of("income.html", "expense.html", "family.html", "kidsMinistry.html")) {
            assertThat(Files.readString(st.resolve(p))).as(p).contains("data-ai-consent=\"SCAN\"");
        }
    }
}
