package com.churchgeniuspro.ai.consent;

import com.churchgeniuspro.ai.consent.AiConsentNotices.Type;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * App Store readiness D6 — which notice each AI path needs inside the app. Only paths that
 * can send data to OpenAI are listed; the rest of the AI area (status checks, people filters,
 * Saved Lists, proposal confirm/reject, form validation, on-device OCR text parsing) runs on
 * ChurchGeniusPro's own data and needs no notice.
 *
 * <p>Three paths take text that may come from a voice transcript or from typing. For those the
 * SERVER decides: the request counts as voice only with a valid one-time token issued by
 * {@code /api/voice/command} for the same session and login, the same operation and the same
 * text ({@link AiVoiceTokens}); otherwise it needs the Assistant notice. A client flag never
 * decides it.
 */
public final class AiConsentPaths {

    private AiConsentPaths() {}

    /** NONE = no notice; FIXED = always {@code type}; DUAL = voice with a valid token, otherwise Assistant. */
    public enum Kind { NONE, FIXED, DUAL }

    public record Requirement(Kind kind, Type type, Set<String> voiceOps) {
        static final Requirement NONE = new Requirement(Kind.NONE, null, Set.of());
        static Requirement fixed(Type t) { return new Requirement(Kind.FIXED, t, Set.of()); }
        static Requirement dual(String... ops) { return new Requirement(Kind.DUAL, Type.ASSISTANT, Set.of(ops)); }
    }

    static final Map<String, Requirement> EXACT = new LinkedHashMap<>();
    static {
        // Voice — the audio upload (transcription + extraction + the church's fund/category/method names).
        EXACT.put("/api/voice/command", Requirement.fixed(Type.VOICE));
        // Assistant — typed questions and drafting.
        EXACT.put("/api/ai-search", Requirement.fixed(Type.ASSISTANT));
        EXACT.put("/api/ai-search/data", Requirement.fixed(Type.ASSISTANT));
        EXACT.put("/api/ai/write", Requirement.fixed(Type.ASSISTANT));
        EXACT.put("/api/ai/forms/draft", Requirement.fixed(Type.ASSISTANT));
        EXACT.put("/api/workflows/draft", Requirement.fixed(Type.ASSISTANT));
        // Voice or typed text — decided by the server (one-time voice token).
        EXACT.put("/api/ai-assist", Requirement.dual("home", "nav", "help"));
        EXACT.put("/api/help/assist", Requirement.dual("home", "help"));
        EXACT.put("/api/voice/extract", Requirement.dual("meeting"));
        // Document scan — the photo / file is sent for reading.
        EXACT.put("/api/income/check-scan", Requirement.fixed(Type.SCAN));
        EXACT.put("/api/expense/check-scan", Requirement.fixed(Type.SCAN));
        EXACT.put("/api/bank-import/scan", Requirement.fixed(Type.SCAN));
        EXACT.put("/api/family/scan-extract", Requirement.fixed(Type.SCAN));
        EXACT.put("/api/kids-ministry/child-scan-extract", Requirement.fixed(Type.SCAN));
    }

    public static Requirement requirementFor(String path) {
        if (path == null) return Requirement.NONE;
        String p = path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
        Requirement r = EXACT.get(p);
        return r == null ? Requirement.NONE : r;
    }

    /** Every path that needs a notice (for tests and the coverage ratchet). */
    public static Map<String, Requirement> all() { return java.util.Collections.unmodifiableMap(EXACT); }
}
