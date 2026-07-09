package com.churchgeniuspro.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional document-AI step that reads checks / bank statements with OpenAI's
 * GPT-4o vision model. Handwriting (amount, date, memo) that Tesseract cannot
 * read is resolved here.
 *
 * <p><b>Disabled unless an API key is configured.</b> Set in application.properties:
 * <pre>
 *   openai.api.key=sk-...           (required to enable)
 *   openai.vision.model=gpt-4o      (optional, default gpt-4o)
 *   openai.api.base=https://api.openai.com/v1   (optional)
 * </pre>
 * When no key is set, {@link #isEnabled()} is false and the scanners fall back to
 * the in-browser Tesseract OCR.
 */
@Service
public class VisionCheckService {

    private static final Logger log = LoggerFactory.getLogger(VisionCheckService.class);

    @Value("${openai.api.key:}")        private String apiKey;
    @Value("${openai.vision.model:gpt-4o}") private String model;
    @Value("${openai.api.base:https://api.openai.com/v1}") private String apiBase;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public boolean isEnabled() { return apiKey != null && !apiKey.isBlank(); }

    public String model() { return model; }

    /** Diagnostic: how the key was detected (without exposing it). */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", isEnabled());
        m.put("model", model);
        m.put("apiBase", apiBase);
        m.put("keyPresent", apiKey != null && !apiKey.isBlank());
        m.put("keyLength", apiKey == null ? 0 : apiKey.trim().length());
        return m;
    }

    @jakarta.annotation.PostConstruct
    void logStatus() {
        if (isEnabled()) log.info("[Vision] OpenAI vision OCR ENABLED (model={}).", model);
        else log.warn("[Vision] OpenAI vision OCR is DISABLED — set openai.api.key (env OPENAI_API_KEY) to read handwriting; using Tesseract fallback for now.");
    }

    /**
     * Extract check fields. Returns a map with keys payorName, amount, date
     * (MM/DD/YYYY), checkNo, memo, bank — or {@code null} if disabled / on error.
     */
    public Map<String, String> extractCheck(byte[] imageBytes, String contentType) {
        if (!isEnabled()) return null;
        String sys = "You read U.S. bank checks and reply ONLY with minified JSON. Never invent values.";
        String user = "Read this check image and extract these fields. "
                + "payorName: the account holder's printed name (top-left). The 'Pay to the order of' line is the payee — IGNORE it; the contributor is the account holder. "
                + "amount: the dollar amount as a number like 100.00 (from the $ courtesy box or the written legal line). "
                + "date: the check date as MM/DD/YYYY. "
                + "checkNo: the check number (top-right, also the last group of the MICR line at the bottom). "
                + "memo: the text written on the 'For'/'Memo' line. "
                + "bank: the printed bank name. "
                + "Respond with JSON exactly: {\"payorName\":string|null,\"amount\":number|null,\"date\":string|null,\"checkNo\":string|null,\"memo\":string|null,\"bank\":string|null}.";
        JsonNode json = call(sys, user, imageBytes, contentType, 500);
        if (json == null) return null;
        Map<String, String> out = new LinkedHashMap<>();
        putStr(out, "payorName", json, "payorName");
        putStr(out, "amount",    json, "amount");
        putStr(out, "date",      json, "date");
        putStr(out, "checkNo",   json, "checkNo");
        putStr(out, "memo",      json, "memo");
        putStr(out, "bank",      json, "bank");
        if (log.isInfoEnabled()) log.info("[Vision] check fields: {}", out);
        return out;
    }

    /**
     * Extract a list of bank-statement transactions from an image/screenshot.
     * Each map: date (MM/DD/YYYY), description, amount (signed: negative=debit),
     * type (debit|credit), refNo, checkNo. Returns {@code null} if disabled / on error.
     */
    public List<Map<String, Object>> extractStatement(byte[] imageBytes, String contentType) {
        if (!isEnabled()) return null;
        String sys = "You read bank/credit-card statements and reply ONLY with minified JSON. Never invent rows.";
        String user = "Read every transaction in this statement image/screenshot. Return JSON: "
                + "{\"transactions\":[{\"date\":\"MM/DD/YYYY\",\"description\":string,"
                + "\"amount\":number (NEGATIVE for debits/withdrawals/payments, POSITIVE for credits/deposits),"
                + "\"type\":\"debit\"|\"credit\",\"refNo\":string|null,\"checkNo\":string|null}]}. "
                + "Include all rows. Omit running-balance columns. Use null for anything unreadable.";
        JsonNode json = call(sys, user, imageBytes, contentType, 2000);
        return parseTxns(json);
    }

    private static final String STMT_SYS = "You read bank/credit-card statements and reply ONLY with minified JSON. Never invent rows.";
    private static final String STMT_SCHEMA =
            "Return JSON: {\"transactions\":[{\"date\":\"MM/DD/YYYY\",\"description\":string,"
          + "\"amount\":number (NEGATIVE for debits/withdrawals/payments, POSITIVE for credits/deposits),"
          + "\"type\":\"debit\"|\"credit\",\"refNo\":string|null,\"checkNo\":string|null}]}. "
          + "Include all rows. Omit running-balance columns. Use null for anything unreadable.";

    /**
     * Extract transactions from already-extracted statement <b>text</b> (e.g. a PDF text
     * layer) using a text-only model call — cheaper than vision and handy when the
     * deterministic line parser can't read an unusual layout.
     */
    public List<Map<String, Object>> extractStatementFromText(String text) {
        if (!isEnabled() || text == null || text.isBlank()) return null;
        String body = text.length() > 12000 ? text.substring(0, 12000) : text;
        String user = "Extract every transaction from this bank statement text. " + STMT_SCHEMA
                + "\n\nSTATEMENT TEXT:\n" + body;
        JsonNode json = callText(STMT_SYS, user, 2500);
        return parseTxns(json);
    }

    private List<Map<String, Object>> parseTxns(JsonNode json) {
        if (json == null) return null;
        JsonNode txns = json.path("transactions");
        if (!txns.isArray()) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode t : txns) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", asStr(t, "date"));
            row.put("description", asStr(t, "description"));
            row.put("amount", t.path("amount").isNumber() ? t.path("amount").asDouble() : null);
            row.put("type", asStr(t, "type"));
            row.put("refNo", asStr(t, "refNo"));
            row.put("checkNo", asStr(t, "checkNo"));
            out.add(row);
        }
        if (log.isInfoEnabled()) log.info("[Vision] statement transactions: {}", out.size());
        return out;
    }

    /**
     * Extract a membership/registration form: the primary member plus any additional
     * household members. Returns {"primary": Map, "members": List&lt;Map&gt;} or null.
     */
    public Map<String, Object> extractMembershipForm(byte[] imageBytes, String contentType) {
        if (!isEnabled()) return null;
        String sys = "You read church membership/registration forms and reply ONLY with minified JSON. Never invent values.";
        String user = "Read this membership/registration form image. Extract the PRIMARY member and any ADDITIONAL family "
                + "members listed. Return JSON exactly: {\"primary\":{\"firstName\":string|null,\"lastName\":string|null,"
                + "\"otherName\":string|null,\"gender\":\"Male\"|\"Female\"|null,\"dateOfBirth\":\"MM/DD/YYYY\"|null,"
                + "\"maritalStatus\":string|null,\"weddingAnniversary\":\"MM/DD/YYYY\"|null,\"email\":string|null,"
                + "\"phone\":string|null,\"address1\":string|null,\"address2\":string|null,\"city\":string|null,"
                + "\"state\":string|null,\"pinCode\":string|null,"
                + "\"phonePrivate\":boolean,\"emailPrivate\":boolean,\"addressPrivate\":boolean},"
                + "\"members\":[{\"firstName\":string|null,"
                + "\"lastName\":string|null,\"otherName\":string|null,\"gender\":string|null,\"dateOfBirth\":\"MM/DD/YYYY\"|null,"
                + "\"phone\":string|null,\"email\":string|null,\"address1\":string|null}]}. "
                + "For phonePrivate/emailPrivate/addressPrivate, return true ONLY if the matching "
                + "\"Make it Private\" / \"Make ... Private\" checkbox is ticked, checked, crossed, or shaded; "
                + "otherwise false. Use null for anything not present.";
        JsonNode json = call(sys, user, imageBytes, contentType, 1500);
        if (json == null) return null;
        Map<String, String> primary = strMap(json.path("primary"), new String[]{
                "firstName","lastName","otherName","gender","dateOfBirth","maritalStatus","weddingAnniversary",
                "email","phone","address1","address2","city","state","pinCode" });
        // Privacy checkboxes → "true" string flags (consumed by the extractor / UI).
        JsonNode pn = json.path("primary");
        for (String pk : new String[]{ "phonePrivate", "emailPrivate", "addressPrivate" }) {
            if (pn.path(pk).asBoolean(false)) primary.put(pk, "true");
        }
        List<Map<String, String>> members = new ArrayList<>();
        JsonNode arr = json.path("members");
        if (arr.isArray()) for (JsonNode mn : arr) {
            Map<String, String> mm = strMap(mn, new String[]{
                    "firstName","lastName","otherName","gender","dateOfBirth","phone","email","address1" });
            if (mm.containsKey("firstName") || mm.containsKey("lastName")) members.add(mm);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("primary", primary);
        out.put("members", members);
        if (log.isInfoEnabled()) log.info("[Vision] membership form: primary fields={} members={}", primary.size(), members.size());
        return out;
    }

    /**
     * Read a Kids Ministry child-registration form image (photo / scanned page).
     * Returns {@code {children:[{firstName,lastName,dob,gender,grade,allergies,medicalNotes}],
     * shared:{parentName,parentPhone,parentEmail,emergencyContactName,emergencyContactPhone},
     * authorizedPickups:[{personName,relationship,phone}]}} or {@code null} when vision is
     * disabled or the call fails. Dates come back as MM/DD/YYYY (the extractor re-normalizes).
     */
    public Map<String, Object> extractChildForm(byte[] imageBytes, String contentType) {
        return extractChildFormDetailed(imageBytes, contentType).data;
    }

    /** A child-form extraction plus the token usage / status needed to meter and log the call. */
    public static class VisionExtraction {
        /** {children, shared, authorizedPickups} on success; {@code null} otherwise. */
        public Map<String, Object> data;
        public int promptTokens, completionTokens, totalTokens;
        public String model;
        public boolean enabled = true;   // false when no API key is configured
        public boolean success = false;  // true only when data was parsed
        public String error;             // null on success
    }

    /**
     * Like {@link #extractChildForm} but returns the OpenAI token usage, model, and
     * outcome alongside the parsed data so callers can record per-call usage/cost.
     * Never returns {@code null}: when Vision is disabled the result has
     * {@code enabled=false}; on a failed call {@code error} is populated.
     */
    public VisionExtraction extractChildFormDetailed(byte[] imageBytes, String contentType) {
        VisionExtraction ex = new VisionExtraction();
        ex.model = model;
        if (!isEnabled()) { ex.enabled = false; ex.error = "OpenAI Vision is not configured"; return ex; }

        String sys = "You read children's-ministry registration forms and reply ONLY with minified JSON. Never invent values.";
        String user = "Read this child registration form image. It may contain up to THREE children "
                + "(Child 1 / Child 2 / Child 3 sections) plus ONE shared Parent/Guardian section, ONE Emergency "
                + "Contact section, and ONE Authorized Pickup section. Return JSON exactly: "
                + "{\"children\":[{\"firstName\":string|null,\"lastName\":string|null,\"dob\":\"MM/DD/YYYY\"|null,"
                + "\"gender\":\"Male\"|\"Female\"|\"Other\"|null,\"grade\":string|null,\"allergies\":string|null,"
                + "\"medicalNotes\":string|null}],"
                + "\"shared\":{\"parentName\":string|null,\"parentPhone\":string|null,\"parentEmail\":string|null,"
                + "\"emergencyContactName\":string|null,\"emergencyContactPhone\":string|null},"
                + "\"authorizedPickups\":[{\"personName\":string|null,\"relationship\":string|null,\"phone\":string|null}]}. "
                + "Include a child only if it has at least a first or last name. Use null for anything not present.";

        CallResult cr = callDetailed(sys, user, imageBytes, contentType, 1800);
        ex.promptTokens     = cr.promptTokens;
        ex.completionTokens = cr.completionTokens;
        ex.totalTokens      = cr.totalTokens;
        if (cr.model != null) ex.model = cr.model;
        if (cr.error != null) { ex.error = cr.error; return ex; }
        if (cr.content == null) { ex.error = "empty response"; return ex; }

        JsonNode json = cr.content;
        List<Map<String, String>> children = new ArrayList<>();
        JsonNode carr = json.path("children");
        if (carr.isArray()) for (JsonNode cn : carr) {
            Map<String, String> cm = strMap(cn, new String[]{
                    "firstName","lastName","dob","gender","grade","allergies","medicalNotes" });
            if (cm.containsKey("firstName") || cm.containsKey("lastName")) children.add(cm);
        }
        Map<String, String> shared = strMap(json.path("shared"), new String[]{
                "parentName","parentPhone","parentEmail","emergencyContactName","emergencyContactPhone" });
        List<Map<String, String>> pickups = new ArrayList<>();
        JsonNode parr = json.path("authorizedPickups");
        if (parr.isArray()) for (JsonNode pn : parr) {
            Map<String, String> pm = strMap(pn, new String[]{ "personName","relationship","phone" });
            if (pm.containsKey("personName")) pickups.add(pm);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("children", children);
        out.put("shared", shared);
        out.put("authorizedPickups", pickups);
        ex.data = out;
        ex.success = true;
        if (log.isInfoEnabled()) log.info("[Vision] child form: children={} sharedFields={} pickups={} tokens(p/c/t)={}/{}/{}",
                children.size(), shared.size(), pickups.size(), ex.promptTokens, ex.completionTokens, ex.totalTokens);
        return ex;
    }

    private Map<String, String> strMap(JsonNode node, String[] keys) {
        Map<String, String> m = new LinkedHashMap<>();
        if (node == null || node.isMissingNode()) return m;
        for (String k : keys) {
            String v = asStr(node, k);
            if (v != null && !v.isBlank() && !"null".equalsIgnoreCase(v)) m.put(k, v.trim());
        }
        return m;
    }

    // ── OpenAI call ──

    private JsonNode call(String systemPrompt, String userPrompt, byte[] imageBytes, String contentType, int maxTokens) {
        return callDetailed(systemPrompt, userPrompt, imageBytes, contentType, maxTokens).content;
    }

    /** A vision call's parsed content plus the OpenAI token-usage accounting. */
    static class CallResult {
        JsonNode content;                 // parsed JSON content, or null on error/empty
        int promptTokens, completionTokens, totalTokens;
        String model;                     // model echoed back by OpenAI
        String error;                     // null on success
    }

    /**
     * Same image chat-completion call as {@link #call}, but returns the token usage
     * and any error alongside the parsed content so callers can meter/log the call.
     */
    private CallResult callDetailed(String systemPrompt, String userPrompt, byte[] imageBytes, String contentType, int maxTokens) {
        CallResult cr = new CallResult();
        cr.model = model;
        try {
            String mime = (contentType != null && contentType.startsWith("image")) ? contentType : "image/jpeg";
            String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(imageBytes);

            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", 0);
            body.put("max_tokens", maxTokens);
            body.putObject("response_format").put("type", "json_object");
            ArrayNode messages = body.putArray("messages");
            ObjectNode sys = messages.addObject();
            sys.put("role", "system"); sys.put("content", systemPrompt);
            ObjectNode usr = messages.addObject();
            usr.put("role", "user");
            ArrayNode content = usr.putArray("content");
            content.addObject().put("type", "text").put("text", userPrompt);
            ObjectNode imgPart = content.addObject();
            imgPart.put("type", "image_url");
            imgPart.putObject("image_url").put("url", dataUrl);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/chat/completions"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[Vision] OpenAI returned HTTP {}: {}", resp.statusCode(), truncate(resp.body(), 300));
                cr.error = "OpenAI HTTP " + resp.statusCode();
                return cr;
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode u = root.path("usage");
            cr.promptTokens     = u.path("prompt_tokens").asInt(0);
            cr.completionTokens = u.path("completion_tokens").asInt(0);
            cr.totalTokens      = u.path("total_tokens").asInt(0);
            cr.model            = root.path("model").asText(model);
            String contentStr = root.path("choices").path(0).path("message").path("content").asText("");
            if (contentStr.isBlank()) { cr.error = "empty response"; return cr; }
            cr.content = mapper.readTree(contentStr);
            return cr;
        } catch (Exception e) {
            log.warn("[Vision] call failed: {}", e.toString());
            cr.error = e.toString();
            return cr;
        }
    }

    /** Text-only chat completion (no image) returning the parsed JSON content. */
    private JsonNode callText(String systemPrompt, String userPrompt, int maxTokens) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", 0);
            body.put("max_tokens", maxTokens);
            body.putObject("response_format").put("type", "json_object");
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userPrompt);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/chat/completions"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[Vision] OpenAI (text) returned HTTP {}: {}", resp.statusCode(), truncate(resp.body(), 300));
                return null;
            }
            JsonNode root = mapper.readTree(resp.body());
            String contentStr = root.path("choices").path(0).path("message").path("content").asText("");
            if (contentStr.isBlank()) return null;
            return mapper.readTree(contentStr);
        } catch (Exception e) {
            log.warn("[Vision] text call failed: {}", e.toString());
            return null;
        }
    }

    private void putStr(Map<String, String> out, String key, JsonNode json, String field) {
        String v = asStr(json, field);
        if (v != null && !v.isBlank() && !"null".equalsIgnoreCase(v)) out.put(key, v.trim());
    }

    private static String asStr(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        if (v.isNumber()) return v.asText();
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String truncate(String s, int n) { return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s); }
}
