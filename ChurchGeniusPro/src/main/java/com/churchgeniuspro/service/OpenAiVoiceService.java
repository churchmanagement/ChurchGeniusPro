package com.churchgeniuspro.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAI-powered voice commands.
 *
 * <p>Two steps:
 * <ol>
 *   <li><b>Transcribe</b> the recorded audio with an OpenAI audio model
 *       ({@code whisper-1}, {@code response_format=verbose_json}) — this also
 *       returns the clip {@code duration} which is metered against the church's
 *       voice-minute quota.</li>
 *   <li><b>Normalize</b> the transcript into the application's canonical command
 *       string with a small chat model ({@code gpt-4o-mini}). The page modules
 *       already understand that command format, so the on-screen behaviour is
 *       unchanged — only the transcription + parsing source moved to OpenAI.</li>
 * </ol>
 *
 * <p>Disabled (no-op) unless {@code openai.api.key} is configured.
 */
@Service
public class OpenAiVoiceService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiVoiceService.class);

    @Value("${openai.api.key:}")                            private String apiKey;
    @Value("${openai.api.base:https://api.openai.com/v1}")  private String apiBase;
    @Value("${openai.voice.transcribe.model:whisper-1}")    private String transcribeModel;
    @Value("${openai.voice.command.model:gpt-4o-mini}")     private String commandModel;
    /** Force transcription to a single language (ISO-639-1). The app is English-only,
     *  so we pin Whisper to "en" to stop it returning Hindi/Malayalam/etc. */
    @Value("${openai.voice.language:en}")                   private String voiceLanguage;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public boolean isEnabled() { return apiKey != null && !apiKey.isBlank(); }

    /** Diagnostic snapshot (never returns the key itself). */
    public java.util.Map<String, Object> status() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("enabled", isEnabled());
        m.put("apiType", "OpenAI Voice API");
        m.put("keyPresent", apiKey != null && !apiKey.isBlank());
        m.put("keyLength", apiKey == null ? 0 : apiKey.trim().length());
        m.put("apiBase", apiBase);
        m.put("endpoint", apiBase + "/audio/transcriptions");
        m.put("transcribeModel", transcribeModel);
        m.put("commandModel", commandModel);
        return m;
    }

    @jakarta.annotation.PostConstruct
    void logStatus() {
        if (isEnabled()) {
            log.info("[Voice] OpenAI voice ENABLED (transcribe={}, command={}).", transcribeModel, commandModel);
        } else {
            log.warn("[Voice] OpenAI voice is DISABLED — openai.api.key is not set. "
                    + "Set the OPENAI_API_KEY environment variable (or openai.api.key in application-local.properties) "
                    + "to enable voice commands on the Income/Expense/Home pages.");
        }
    }

    public String commandModel()    { return commandModel; }
    public String transcribeModel() { return transcribeModel; }
    public String apiBase()         { return apiBase; }

    /** Transcription result: recognized text, audio duration, rough confidence (0..1, -1 if unknown). */
    public record Transcription(String text, double durationSeconds, double confidence) {}

    /**
     * Sends the recorded audio to OpenAI's transcription endpoint.
     * Returns {@code null} on error / when disabled.
     */
    public Transcription transcribe(byte[] audio, String filename, String contentType) {
        if (!isEnabled() || audio == null || audio.length == 0) return null;
        if (filename == null || filename.isBlank()) filename = "audio.webm";
        if (contentType == null || contentType.isBlank()) contentType = "application/octet-stream";

        String boundary = "----cgpvoice" + System.nanoTime();
        try {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writePart(body, boundary, "model", transcribeModel);
            writePart(body, boundary, "response_format", "verbose_json");
            if (voiceLanguage != null && !voiceLanguage.isBlank())
                writePart(body, boundary, "language", voiceLanguage.trim());   // English-only
            writeFilePart(body, boundary, "file", filename, contentType, audio);
            body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/audio/transcriptions"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[Voice] transcription failed status={} body={}", resp.statusCode(),
                        truncate(resp.body()));
                return null;
            }
            JsonNode json = mapper.readTree(resp.body());
            String text = json.path("text").asText("").trim();
            double dur  = json.path("duration").asDouble(0);
            // Rough confidence proxy from whisper segments: 1 - avg(no_speech_prob).
            double conf = -1;
            JsonNode segs = json.path("segments");
            if (segs.isArray() && segs.size() > 0) {
                double sum = 0; int n = 0;
                for (JsonNode s : segs) {
                    if (s.has("no_speech_prob")) { sum += (1.0 - s.path("no_speech_prob").asDouble(0)); n++; }
                }
                if (n > 0) conf = Math.max(0, Math.min(1, sum / n));
            }
            return new Transcription(text, dur, conf);
        } catch (Exception e) {
            log.warn("[Voice] transcription error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Converts a raw transcript into the application's canonical command string
     * for the given page {@code context} (income | expense | home). Falls back to
     * the raw transcript if the model is disabled or errors.
     */
    public String toCommand(String transcript, String context) {
        if (transcript == null) return "";
        if (!isEnabled()) return transcript;
        try {
            String sys = systemPrompt(context);
            ObjectNode root = mapper.createObjectNode();
            root.put("model", commandModel);
            root.put("temperature", 0);
            ArrayNode messages = root.putArray("messages");
            ObjectNode m0 = messages.addObject(); m0.put("role", "system");    m0.put("content", sys);
            ObjectNode m1 = messages.addObject(); m1.put("role", "user");
            m1.put("content", "Transcript: " + transcript);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/chat/completions"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root)))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[Voice] command model failed status={} body={}", resp.statusCode(),
                        truncate(resp.body()));
                return transcript;
            }
            JsonNode json = mapper.readTree(resp.body());
            String out = json.path("choices").path(0).path("message").path("content").asText("").trim();
            return out.isBlank() ? transcript : out;
        } catch (Exception e) {
            log.warn("[Voice] command model error: {}", e.getMessage());
            return transcript;
        }
    }

    /**
     * Extracts structured form fields from a transcript using JSON mode — far more
     * reliable than the string-command approach (no "echo the words" meta output).
     * Returns {@code {"fields": {field: value, ...}, "command": "<clear/reset>"|null}}.
     */
    public Map<String, Object> extractFields(String transcript, String context) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, String> fields = new LinkedHashMap<>();
        out.put("fields", fields);
        out.put("command", null);
        if (transcript == null || transcript.isBlank() || !isEnabled()) return out;

        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("model", commandModel);
            root.put("temperature", 0);
            root.putObject("response_format").put("type", "json_object");
            ArrayNode messages = root.putArray("messages");
            messages.addObject().put("role", "system").put("content", jsonPrompt(context));
            java.time.LocalDate today = java.time.LocalDate.now();
            String dateCtx = "Today is " + today + " (" + today.getDayOfWeek()
                    .getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US) + "). "
                    + "Resolve any relative or natural-language date (today, tomorrow, yesterday, "
                    + "this/last/next <weekday>, in N days) to a concrete M/D/YYYY date. ";
            messages.addObject().put("role", "user").put("content", dateCtx + "Transcript: " + transcript);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/chat/completions"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root)))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[Voice] extractFields failed status={} body={}", resp.statusCode(), truncate(resp.body()));
                return out;
            }
            String content = mapper.readTree(resp.body())
                    .path("choices").path(0).path("message").path("content").asText("").trim();
            if (content.isBlank()) return out;
            JsonNode j = mapper.readTree(content);

            // Map model keys → the page's filler keys. "reference" → "ref".
            String ctx0 = safeCtx(context);
            String[][] keymap;
            if ("expense".equals(ctx0)) {
                keymap = new String[][]{{"expense","expense"},{"fund","fund"},{"date","date"},{"method","method"},{"amount","amount"},{"reference","ref"},{"note","note"}};
            } else if ("meeting".equals(ctx0)) {
                keymap = new String[][]{{"category","category"},{"location","location"},{"date","date"},{"time","time"},{"occurrence","occurrence"},{"note","note"}};
            } else {
                keymap = new String[][]{{"contributor","contributor"},{"fund","fund"},{"date","date"},{"method","method"},{"amount","amount"},{"reference","ref"},{"note","note"}};
            }
            for (String[] km : keymap) {
                JsonNode v = j.get(km[0]);
                if (v != null && !v.isNull()) {
                    String sv = v.asText("").trim();
                    if (!sv.isEmpty() && !"null".equalsIgnoreCase(sv)) fields.put(km[1], sv);
                }
            }
            JsonNode cmd = j.get("command");
            if (cmd != null && !cmd.isNull()) {
                String sc = cmd.asText("").trim();
                if (!sc.isEmpty() && !"null".equalsIgnoreCase(sc)) out.put("command", sc);
            }
            return out;
        } catch (Exception e) {
            log.warn("[Voice] extractFields error: {}", e.getMessage());
            return out;
        }
    }

    /**
     * In-app "AI Search Assistant" answer (Type / Voice / Converse).
     *
     * <p>Given a user {@code question}, a {@code contextJson} describing the pages
     * and reports available to THIS user (role-filtered on the client), and an
     * optional short {@code history} of the current Converse dialogue, returns a
     * structured answer grounded ONLY in the provided context. The model is told
     * never to recommend a page that is not present in the context, so it cannot
     * suggest something the signed-in user can't access.
     *
     * <p>Returns an empty map when disabled / on error (the caller then falls back
     * to the static help search).
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> assist(String question, String contextJson, String history) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (question == null || question.isBlank() || !isEnabled()) return out;
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("model", commandModel);
            root.put("temperature", 0.2);
            root.putObject("response_format").put("type", "json_object");
            ArrayNode messages = root.putArray("messages");
            messages.addObject().put("role", "system").put("content", assistPrompt());

            StringBuilder u = new StringBuilder();
            if (contextJson != null && !contextJson.isBlank())
                u.append("CONTEXT (JSON — only these pages/reports are available to this user):\n")
                 .append(contextJson).append("\n\n");
            if (history != null && !history.isBlank())
                u.append("CONVERSATION SO FAR:\n").append(history).append("\n\n");
            u.append("QUESTION: ").append(question);
            messages.addObject().put("role", "user").put("content", u.toString());

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/chat/completions"))
                    .timeout(Duration.ofSeconds(40))
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root)))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[Assist] failed status={} body={}", resp.statusCode(), truncate(resp.body()));
                return out;
            }
            String content = mapper.readTree(resp.body())
                    .path("choices").path(0).path("message").path("content").asText("").trim();
            if (content.isBlank()) return out;
            JsonNode j = mapper.readTree(content);
            Map<String, Object> parsed = mapper.convertValue(j, Map.class);
            return (parsed == null) ? out : parsed;
        } catch (Exception e) {
            log.warn("[Assist] error: {}", e.getMessage());
            return out;
        }
    }

    /**
     * Generic JSON-mode chat helper for backend features (e.g. the ETL mapping
     * fallback). Sends {@code systemPrompt} + {@code userPrompt} with
     * {@code response_format=json_object} and returns the model's parsed JSON as a
     * {@link JsonNode}, or {@code null} when disabled / on error. The caller owns
     * the schema and must treat the result as a suggestion only.
     */
    public JsonNode chatJson(String systemPrompt, String userPrompt) {
        if (!isEnabled() || systemPrompt == null || userPrompt == null) return null;
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("model", commandModel);
            root.put("temperature", 0);
            root.putObject("response_format").put("type", "json_object");
            ArrayNode messages = root.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userPrompt);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/chat/completions"))
                    .timeout(Duration.ofSeconds(45))
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root)))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[chatJson] failed status={} body={}", resp.statusCode(), truncate(resp.body()));
                return null;
            }
            String content = mapper.readTree(resp.body())
                    .path("choices").path(0).path("message").path("content").asText("").trim();
            if (content.isBlank()) return null;
            return mapper.readTree(content);
        } catch (Exception e) {
            log.warn("[chatJson] error: {}", e.getMessage());
            return null;
        }
    }

    private String assistPrompt() {
        return "You are the in-app AI assistant for ChurchGenius Pro, a church-management web app. "
             + "Answer the user's question about using the app: where to find a feature, what a report contains, "
             + "how to perform a task, navigation paths, voice commands, and public-page links. "
             + "You are given CONTEXT as JSON with: role (the user's role), currentPage, "
             + "availablePages (array of {label,url,voiceCommand,category,description}) and "
             + "reports (array of {label,url,voiceCommand,contains,usedFor}). "
             + "CRITICAL RULES: (1) Only recommend pages or reports that appear in availablePages/reports. "
             + "If the feature the user needs is NOT in the provided context, say it is not available for their "
             + "current account and leave relevantPages empty. (2) Never invent URLs — copy them verbatim from context. "
             + "Reply ONLY as a JSON object with these keys: "
             + "answer (concise, friendly, actionable; mention the navigation path and the voice command when relevant), "
             + "intent (one of: navigation, report, howto, faq, unknown), "
             + "category (short label, e.g. Finance, Reports, Ministry, Members, Communications, Public, Help), "
             + "relevantPages (array of {label,url,voiceCommand} drawn ONLY from context; [] if none), "
             + "suggestedAction (\"open_page\" when there is a single best page to open, else \"none\"), "
             + "confidence (number 0..1), "
             + "source (one of: \"Navigation Guide\",\"Reports Guide\",\"Help Center\",\"Assistant\"), "
             + "followUpQuestion (a single clarifying question string when the request is ambiguous, else null). "
             + "For ambiguous Converse requests (e.g. \"I need to send reminders\", \"I need a donation report\", "
             + "\"someone wants to join the church\"), ask a focused followUpQuestion and set suggestedAction to \"none\" "
             + "until the user clarifies, then narrow to the single best page.";
    }

    private static String safeCtx(String c) { return c == null ? "" : c.trim().toLowerCase(); }

    private String jsonPrompt(String context) {
        if ("meeting".equals(safeCtx(context))) {
            return "You extract structured fields from a request to create a church MEETING. "
                 + "Reply ONLY with a JSON object with these keys: "
                 + "category (the meeting category/type, e.g. \"Bible Study\", \"Sunday Service\", \"Prayer Meeting\"), "
                 + "location (the place or person where it is held; strip a trailing \"'s house\"/\"house\" so "
                 + "\"Test3's house\" becomes \"Test3\"), date (as M/D/YYYY), time (the START time like \"4:00 PM\"), "
                 + "occurrence (one of \"One-time\",\"Daily\",\"Weekly\",\"Monthly\"; use \"One-time\" when not stated), "
                 + "note (any extra notes/agenda). "
                 + "Set a key to null when the user did not mention it. Never invent values. "
                 + "Example: \"Create a Bible Study at Test3's house on June 3rd at 4 pm\" → "
                 + "{\"category\":\"Bible Study\",\"location\":\"Test3\",\"date\":\"6/3/2026\",\"time\":\"4:00 PM\",\"occurrence\":\"One-time\",\"note\":null}.";
        }
        if ("expense".equals(safeCtx(context))) {
            return "You extract structured fields from a spoken church EXPENSE entry. "
                 + "Reply ONLY with a JSON object with these keys: "
                 + "expense (the expense category/purpose), fund (fund or source), date (as M/D/YYYY), "
                 + "method (payment method), amount (number as a string like \"100\" or \"100.50\"), "
                 + "reference (reference/check number), note (free text), and "
                 + "command (one of \"clear expense\",\"clear fund\",\"clear date\",\"clear method\",\"clear amount\",\"clear reference\",\"clear note\",\"reset form\" "
                 + "ONLY if the user explicitly asked to clear/reset, else null). "
                 + "Set a key to null when the user did not mention it. Never invent values.";
        }
        // income (default)
        return "You extract structured fields from a spoken church INCOME/contribution entry. "
             + "Reply ONLY with a JSON object with these keys: "
             + "contributor (the person's full name), fund (offering/fund name), date (as M/D/YYYY), "
             + "method (payment method), amount (number as a string like \"100\" or \"100.50\"), "
             + "reference (reference/check number), note (free text), and "
             + "command (one of \"clear contributor\",\"clear fund\",\"clear date\",\"clear method\",\"clear amount\",\"clear reference\",\"clear note\",\"reset form\" "
             + "ONLY if the user explicitly asked to clear/reset, else null). "
             + "Set a key to null when the user did not mention it. Never invent values. "
             + "Example: \"contributor Anson Mathew amount one hundred\" → {\"contributor\":\"Anson Mathew\",\"fund\":null,\"date\":null,\"method\":null,\"amount\":\"100\",\"reference\":null,\"note\":null,\"command\":null}.";
    }

    // ── Prompts ────────────────────────────────────────────────────────────────

    private String systemPrompt(String context) {
        String ctx = context == null ? "" : context.trim().toLowerCase();
        if ("expense".equals(ctx)) {
            return "You convert a spoken church-EXPENSE entry into a single normalized command line. "
                 + "Output ONLY the command line, nothing else. Use this comma-separated format, "
                 + "including ONLY the fields the user actually mentioned, each as 'Label value': "
                 + "Expense <purpose/category>, Fund <fund or source>, Date <M/D/YYYY>, "
                 + "Method <payment method>, Amount <number>, Reference <ref or check no>, Note <free text>. "
                 + "Amounts as digits (e.g. 100 or 100.50). Dates as numeric M/D/YYYY. "
                 + "If the user wants to clear/reset, output 'Clear <field>' or 'Reset Form'. "
                 + "If nothing actionable, echo the words.";
        }
        if ("home".equals(ctx) || "nav".equals(ctx)) {
            return "You convert a spoken navigation request into a short command of the form "
                 + "'go to <page>'. Output ONLY that command. Use the destination the user asked for "
                 + "(e.g. 'go to Income', 'go to Events', 'go to Prayer Ministry'). "
                 + "If no destination is clear, echo the words.";
        }
        // default: income
        return "You convert a spoken church-INCOME/contribution entry into a single normalized command line. "
             + "Output ONLY the command line, nothing else. Use this comma-separated format, "
             + "including ONLY the fields the user actually mentioned, each as 'Label value': "
             + "Contributor <full name>, Fund <fund/offering name>, Date <M/D/YYYY>, "
             + "Method <payment method>, Amount <number>, Reference <ref or check no>, Note <free text>. "
             + "Amounts as digits (e.g. 100 or 100.50). Dates as numeric M/D/YYYY. "
             + "If the user wants to clear/reset, output 'Clear <field>' or 'Reset Form'. "
             + "If nothing actionable, echo the words.";
    }

    // ── Multipart helpers ───────────────────────────────────────────────────────

    private static void writePart(ByteArrayOutputStream out, String boundary, String name, String value)
            throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFilePart(ByteArrayOutputStream out, String boundary, String name,
                                      String filename, String contentType, byte[] data) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
