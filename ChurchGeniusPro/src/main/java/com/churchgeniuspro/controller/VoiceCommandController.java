package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.ChurchVoiceSettingService;
import com.churchgeniuspro.service.OpenAiUsageService;
import com.churchgeniuspro.service.OpenAiVoiceService;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI voice-command endpoint shared by the income, expense and home pages.
 *
 * <p>Flow: the browser records audio and POSTs it here with a {@code context}.
 * The church's voice quota is checked first; the clip is transcribed by OpenAI,
 * the duration is metered against the quota, and the transcript is normalized
 * into the page's canonical command string which the client then applies.
 */
@RestController
public class VoiceCommandController {

    private final OpenAiVoiceService voiceService;
    private final OpenAiUsageService usageService;
    private final ChurchVoiceSettingService voiceFeatures;
    private final ObjectMapper mapper = new ObjectMapper();

    public VoiceCommandController(OpenAiVoiceService voiceService,
                                  OpenAiUsageService usageService,
                                  ChurchVoiceSettingService voiceFeatures) {
        this.voiceService = voiceService;
        this.usageService = usageService;
        this.voiceFeatures = voiceFeatures;
    }

    /** Diagnostic: is OpenAI voice configured? (No secret is returned.) */
    @GetMapping("/api/voice/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(voiceService.status());
    }

    /**
     * The effective Voice feature flags for the signed-in user's church, used by
     * the frontend to hide disabled buttons. Returns all-true defaults when no
     * per-church configuration exists. Never 401s — returns a safe default so the
     * client can always reason about what to show.
     */
    @GetMapping("/api/voice/features")
    public ResponseEntity<Map<String, Object>> features(HttpServletRequest request) {
        String clientId = SessionUtil.getAppClientId(request);
        return ResponseEntity.ok(voiceFeatures.effectiveMap(clientId));
    }

    @PostMapping("/api/voice/command")
    public ResponseEntity<Map<String, Object>> command(
            @RequestParam("audio") MultipartFile audio,
            @RequestParam(value = "context", defaultValue = "income") String context,
            HttpServletRequest request) {

        Map<String, Object> resp = new LinkedHashMap<>();

        String clientId = SessionUtil.getAppClientId(request);
        if (clientId == null || clientId.isBlank()) {
            resp.put("error", "Not signed in.");
            return ResponseEntity.status(401).body(resp);
        }
        if (!voiceService.isEnabled()) {
            resp.put("error", "Voice service is not configured — the OpenAI API key is missing on the server. "
                    + "An administrator must set OPENAI_API_KEY (or openai.api.key) and restart the app.");
            resp.put("status", voiceService.status());
            return ResponseEntity.status(503).body(resp);
        }
        // Per-church Voice feature gate (mic / converse audio). Disabled by the
        // Service Admin → refuse so a cached page can't keep using a disabled feature.
        if (!voiceFeatures.audioEnabled(clientId)) {
            resp.put("error", "Voice features are disabled for this church.");
            return ResponseEntity.status(403).body(resp);
        }
        // Church-level quota gate (shared across all pages).
        if (!usageService.voiceAvailable(clientId)) {
            resp.put("error", "Voice limit reached or disabled. Please contact your administrator.");
            resp.put("status", usageService.statusMap(clientId));
            return ResponseEntity.status(403).body(resp);
        }
        if (audio == null || audio.isEmpty()) {
            resp.put("error", "No audio received.");
            return ResponseEntity.badRequest().body(resp);
        }

        OpenAiVoiceService.Transcription tr;
        try {
            tr = voiceService.transcribe(audio.getBytes(), audio.getOriginalFilename(), audio.getContentType());
        } catch (Exception e) {
            resp.put("error", "Could not read the audio.");
            return ResponseEntity.status(500).body(resp);
        }
        if (tr == null) {
            resp.put("error", "Transcription failed. Please try again.");
            return ResponseEntity.status(502).body(resp);
        }

        // Meter the metered audio seconds against the church quota.
        long seconds = Math.max(0, Math.round(tr.durationSeconds()));
        usageService.addVoiceSeconds(clientId, seconds);

        // Diagnostics for the debug panel.
        resp.put("transcript",      tr.text());
        resp.put("confidence",      tr.confidence());          // 0..1, or -1 if unknown
        resp.put("durationSeconds", seconds);
        resp.put("apiType",         "OpenAI Voice API");
        resp.put("transcribeModel", voiceService.transcribeModel());
        resp.put("model",           voiceService.commandModel());
        resp.put("endpoint",        voiceService.apiBase());

        String ctx = context == null ? "" : context.trim().toLowerCase();
        if (ctx.equals("home") || ctx.equals("nav")) {
            // Navigation: keep the short "go to <page>" command string.
            resp.put("command", voiceService.toCommand(tr.text(), context));
            resp.put("fields", java.util.Collections.emptyMap());
        } else {
            // Income / expense: reliable structured field extraction (JSON mode).
            Map<String, Object> ex = voiceService.extractFields(tr.text(), context);
            resp.put("fields", ex.get("fields"));
            resp.put("command", ex.get("command"));
        }
        resp.put("status", usageService.statusMap(clientId));
        return ResponseEntity.ok(resp);
    }

    /**
     * AI Search Assistant (Type / Voice / Converse) on the Home page.
     *
     * <p>Body: {@code {query, context, history}} where {@code context} is the
     * role-filtered pages/reports the client already knows the user can access.
     * The model answers grounded ONLY in that context and returns structured JSON
     * (answer, intent, category, relevantPages, suggestedAction, confidence,
     * source, followUpQuestion). When OpenAI is not configured this returns 503 so
     * the client can fall back to the static help search.
     */
    @PostMapping("/api/ai-assist")
    public ResponseEntity<Map<String, Object>> aiAssist(@RequestBody Map<String, Object> body,
                                                        HttpServletRequest request) {
        Map<String, Object> resp = new LinkedHashMap<>();

        String clientId = SessionUtil.getAppClientId(request);
        if (clientId == null || clientId.isBlank()) {
            resp.put("error", "Not signed in.");
            return ResponseEntity.status(401).body(resp);
        }
        if (!voiceService.isEnabled()) {
            resp.put("error", "AI assistant is not configured — the OpenAI API key is missing on the server.");
            resp.put("intent", "error");
            return ResponseEntity.status(503).body(resp);
        }
        // Used by both Type (text search) and Converse — block only when both are off.
        if (!voiceFeatures.typeEnabled(clientId) && !voiceFeatures.converseEnabled(clientId)) {
            resp.put("error", "This feature is disabled for this church.");
            resp.put("intent", "error");
            return ResponseEntity.status(403).body(resp);
        }

        String question = str(body.get("query"));
        if (question.isBlank()) question = str(body.get("question"));
        if (question.isBlank()) {
            resp.put("error", "No question received.");
            return ResponseEntity.badRequest().body(resp);
        }

        String contextJson;
        Object ctx = body.get("context");
        try {
            contextJson = (ctx == null) ? "" : mapper.writeValueAsString(ctx);
        } catch (Exception e) {
            contextJson = String.valueOf(ctx);
        }
        String history = str(body.get("history"));

        Map<String, Object> ans = voiceService.assist(question, contextJson, history);
        if (ans == null || ans.isEmpty()) {
            resp.put("intent", "assist");
            resp.put("answer", "Sorry, I couldn't generate an answer. Please try rephrasing your question.");
            resp.put("confidence", 0);
            resp.put("source", "Assistant");
            return ResponseEntity.ok(resp);
        }
        resp.putAll(ans);
        resp.putIfAbsent("intent", "assist");
        resp.putIfAbsent("source", "Assistant");
        resp.put("model", voiceService.commandModel());
        return ResponseEntity.ok(resp);
    }

    /**
     * Text-mode field extraction (no audio) — used by the meeting assistant's
     * Type mode and any page that wants to parse a typed sentence into form
     * fields. Body: {@code {text, context}} where context is e.g. "meeting".
     */
    @PostMapping("/api/voice/extract")
    public ResponseEntity<Map<String, Object>> extract(@RequestBody Map<String, Object> body,
                                                       HttpServletRequest request) {
        Map<String, Object> resp = new LinkedHashMap<>();
        String clientId = SessionUtil.getAppClientId(request);
        if (clientId == null || clientId.isBlank()) {
            resp.put("error", "Not signed in.");
            return ResponseEntity.status(401).body(resp);
        }
        if (!voiceService.isEnabled()) {
            resp.put("error", "AI is not configured — the OpenAI API key is missing on the server.");
            return ResponseEntity.status(503).body(resp);
        }
        // Type-mode (text → form fields) is gated by the Voice Type feature.
        if (!voiceFeatures.typeEnabled(clientId)) {
            resp.put("error", "Type assist is disabled for this church.");
            return ResponseEntity.status(403).body(resp);
        }
        String text    = str(body.get("text"));
        String context = str(body.get("context"));
        if (text.isBlank()) {
            resp.put("error", "No text received.");
            return ResponseEntity.badRequest().body(resp);
        }
        Map<String, Object> ex = voiceService.extractFields(text, context);
        resp.put("fields",  ex.get("fields"));
        resp.put("command", ex.get("command"));
        return ResponseEntity.ok(resp);
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
}
