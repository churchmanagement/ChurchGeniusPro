package com.churchgeniuspro.ai.consent;

import com.churchgeniuspro.ai.consent.AiConsentNotices.Type;
import com.churchgeniuspro.ai.consent.AiConsentPaths.Kind;
import com.churchgeniuspro.ai.consent.AiConsentPaths.Requirement;
import com.churchgeniuspro.ai.consent.AiConsentService.LoginRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * App Store readiness D6 — the consent decision for one AI request from the app, called by
 * {@code AiGuardFilter} BEFORE the controller (so nothing reaches OpenAI without consent).
 * The website never calls this.
 */
@Component
public class AiConsentGate {

    public static final String CODE_REQUIRED = "AI_CONSENT_REQUIRED";
    public static final String CODE_UNAVAILABLE = "AI_CONSENT_UNAVAILABLE";
    public static final String CODE_TOKEN = "AI_VOICE_TOKEN_INVALID";
    public static final String CODE_FAILED = "AI_CONSENT_CHECK_FAILED";
    /** Request attribute: the notice type the request was accepted under (VOICE / ASSISTANT / SCAN). */
    public static final String ACCEPTED_ATTR = "cgp.aiConsentType";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AiConsentService consent;
    private final AiVoiceTokens tokens;

    public AiConsentGate(AiConsentService consent, AiVoiceTokens tokens) {
        this.consent = consent;
        this.tokens = tokens;
    }

    /** {@code request} is the one to continue with (a body-caching wrapper for the voice-or-typed paths). */
    public record Decision(boolean allowed, int status, String code, Type notice, String reason, HttpServletRequest request) {
        static Decision allow(HttpServletRequest r) { return new Decision(true, 200, null, null, null, r); }
        static Decision refuse(String code, Type notice, String reason) { return new Decision(false, 403, code, notice, reason, null); }

        public String json() {
            StringBuilder b = new StringBuilder("{\"error\":");
            b.append(quote(message())).append(",\"code\":\"").append(code).append('"');
            if (notice != null) b.append(",\"notice\":\"").append(notice.name()).append("\",\"version\":")
                    .append(AiConsentNotices.current(notice).version());
            if (reason != null) b.append(",\"reason\":\"").append(reason).append('"');
            return b.append('}').toString();
        }

        String message() {
            return switch (code) {
                case CODE_REQUIRED -> "Please review the data sharing notice to continue.";
                case CODE_UNAVAILABLE -> "AI features are not available for this sign-in in the app. Please sign in again.";
                case CODE_TOKEN -> "This voice request could not be confirmed. Please try again.";
                default -> "The AI data sharing check could not be completed. Please try again.";
            };
        }

        private static String quote(String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    public Decision check(HttpServletRequest request, Requirement req) throws java.io.IOException {
        if (req == null || req.kind() == Kind.NONE) return Decision.allow(request);
        Optional<LoginRef> who = consent.login(request);
        if (who.isEmpty()) return Decision.refuse(CODE_UNAVAILABLE, null, null);
        LoginRef w = who.get();

        if (req.kind() == Kind.FIXED) {
            if (!consent.hasConsent(w, req.type())) return Decision.refuse(CODE_REQUIRED, req.type(), null);
            request.setAttribute(ACCEPTED_ATTR, req.type().name());
            if (req.type() == Type.VOICE) request.setAttribute(AiVoiceTokens.ISSUE_ATTR, Boolean.TRUE);
            return Decision.allow(request);
        }

        // DUAL — voice only with a valid one-time token for this session/login/operation/text.
        String token = request.getHeader(AiVoiceTokens.HEADER);
        if (token == null || token.isBlank()) {
            if (!consent.hasConsent(w, Type.ASSISTANT)) return Decision.refuse(CODE_REQUIRED, Type.ASSISTANT, null);
            request.setAttribute(ACCEPTED_ATTR, Type.ASSISTANT.name());
            return Decision.allow(request);
        }
        CachedBodyRequest wrapped = CachedBodyRequest.of(request);
        if (wrapped == null) return Decision.refuse(CODE_TOKEN, null, "mismatch");
        String text = textOf(wrapped.text());
        AiVoiceTokens.Result r = tokens.consume(token.trim(), request.getSession(false).getId(), w.clientId(), w.loginId(),
                req.voiceOps(), text);
        if (r != AiVoiceTokens.Result.OK) return Decision.refuse(CODE_TOKEN, null, r.name().toLowerCase(Locale.ROOT));
        if (!consent.hasConsent(w, Type.VOICE)) return Decision.refuse(CODE_REQUIRED, Type.VOICE, null);   // withdrawn meanwhile
        wrapped.setAttribute(ACCEPTED_ATTR, Type.VOICE.name());
        return Decision.allow(wrapped);
    }

    /** The question text of the three voice-or-typed endpoints ({@code query}, {@code question} or {@code text}). */
    static String textOf(String body) {
        try {
            JsonNode n = JSON.readTree(body);
            for (String k : new String[] {"query", "question", "text"}) {
                JsonNode v = n == null ? null : n.get(k);
                if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText();
            }
        } catch (Exception ignored) { }
        return "";
    }

    /** Issues a voice token after a successful transcription (null when not allowed or not needed). */
    public String issueVoiceToken(HttpServletRequest request, String context, String transcript) {
        if (!Boolean.TRUE.equals(request.getAttribute(AiVoiceTokens.ISSUE_ATTR))) return null;
        String op = context == null ? "" : context.trim().toLowerCase(Locale.ROOT);
        boolean followUp = AiConsentPaths.all().values().stream().anyMatch(q -> q.voiceOps().contains(op));
        if (!followUp) return null;
        Optional<LoginRef> who = consent.login(request);
        if (who.isEmpty() || request.getSession(false) == null) return null;
        return tokens.issue(request.getSession(false).getId(), who.get().clientId(), who.get().loginId(), op, transcript);
    }
}
