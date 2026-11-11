package com.churchgeniuspro.ai.audit;

import com.churchgeniuspro.ai.llm.LlmGateway;
import com.churchgeniuspro.hibernate.AiAuditLog;
import com.churchgeniuspro.repository.AiAuditLogRepository;
import com.churchgeniuspro.util.LogSafe;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Writes {@code ai_audit_log} rows (AI Phase 0, 2026-11-04). One row per AI request, written by
 * {@code AiGuardFilter} after the response is complete; services only <em>annotate</em> the
 * request with {@link #intent(HttpServletRequest, Object)} and the gateway with token usage.
 *
 * <p>Never throws: an audit failure is logged and the user's request is unaffected
 * (the row is accountability, not a gate — the gates run before the request).
 */
@Service
public class AiAuditService {

    private static final Logger log = LoggerFactory.getLogger(AiAuditService.class);

    /** Request attribute a service sets to name the intent it answered (never the question). */
    public static final String INTENT_ATTR = "ai.intent";

    /** The AI entry points, by path prefix → channel. Order matters: longest prefix first. */
    public static final java.util.LinkedHashMap<String, String> CHANNELS = new java.util.LinkedHashMap<>();
    static {
        CHANNELS.put("/api/ai-search/confirm",            "CONFIRM");
        CHANNELS.put("/api/ai-search/data",               "DATA");
        CHANNELS.put("/api/ai-search/filter",             "DATA");
        CHANNELS.put("/api/ai-search/saved-lists",        "DATA");
        CHANNELS.put("/api/ai-search",                    "SEARCH");
        CHANNELS.put("/api/ai-assist",                    "ASSIST");
        CHANNELS.put("/api/voice",                        "VOICE");
        CHANNELS.put("/api/help/assist",                  "HELP");
        CHANNELS.put("/api/ai/write",                     "WRITE");     // AI Phase 3: drafts (never sends)
        CHANNELS.put("/api/ai/forms",                     "FORMS");     // AI Phase 3: describe-to-build
        CHANNELS.put("/api/ai/actions",                   "ACTIONS");
        CHANNELS.put("/api/ai/email",                     "EMAIL");     // 2026-11-11: the Send button of the Genius e-mail review card
        CHANNELS.put("/api/workflows/draft",              "WORKFLOWS"); // AI Phase 6: describe-to-build rule drafting (the only model call of the engine)   // AI Phase 4: confirm / reject / list (proposals come through /api/ai-search)
        CHANNELS.put("/api/expense/check-scan",           "VISION");
        CHANNELS.put("/api/expense/check-parse",          "VISION");
        CHANNELS.put("/api/income/check-scan",            "VISION");
        CHANNELS.put("/api/bank-import/scan",             "VISION");
        CHANNELS.put("/api/bank-import/scan-parse",       "VISION");
        CHANNELS.put("/api/family/scan-extract",          "VISION");
        CHANNELS.put("/api/kids-ministry/child-scan-extract", "VISION");
    }

    /** Channel for a request path, or null when the path is not an AI entry point. */
    public static String channelOf(String path) {
        if (path == null) return null;
        for (Map.Entry<String, String> e : CHANNELS.entrySet()) {
            String p = e.getKey();
            if (path.equals(p) || path.startsWith(p + "/")) return e.getValue();
        }
        return null;
    }

    /** Services call this to record what they decided the request was (an intent key, never text). */
    public static void intent(HttpServletRequest request, Object intent) {
        if (request == null || intent == null) return;
        String s = String.valueOf(intent).trim();
        if (!s.isEmpty()) request.setAttribute(INTENT_ATTR, s.length() > 80 ? s.substring(0, 80) : s);
    }

    /** Pulls the intent out of an answer map ({@code intent} or {@code kind}) when a service built one. */
    public static void intentFrom(HttpServletRequest request, Map<String, ?> answer) {
        if (answer == null) return;
        Object i = answer.get("intent");
        if (i == null) i = answer.get("kind");
        intent(request, i);
    }

    private final AiAuditLogRepository repo;
    private final boolean enabled;

    public AiAuditService(AiAuditLogRepository repo, @Value("${ai.audit.enabled:true}") boolean enabled) {
        this.repo = repo;
        this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }

    /** Builds and saves the row. Swallows every failure. */
    public void record(String clientId, String actor, String role, String channel, String path,
                       String intent, LlmGateway.Usage usage, int httpStatus, String outcome, long durationMs) {
        if (!enabled || repo == null || clientId == null || clientId.isBlank()) return;
        try {
            AiAuditLog row = new AiAuditLog();
            row.setClientId(cut(clientId, 100));
            row.setActor(cut(actor, 150));
            row.setRole(cut(role, 40));
            row.setChannel(cut(channel == null ? "OTHER" : channel, 20));
            row.setPath(cut(path, 160));
            row.setIntent(cut(intent, 80));
            if (usage != null) {
                row.setModel(cut(usage.model(), 60));
                row.setPromptTokens(usage.promptTokens());
                row.setCompletionTokens(usage.completionTokens());
            }
            row.setHttpStatus(httpStatus);
            row.setOutcome(cut(outcome, 20));
            row.setDurationMs((int) Math.min(Integer.MAX_VALUE, Math.max(0, durationMs)));
            repo.save(row);
        } catch (Exception e) {
            log.warn("[AiAudit] row not written for channel {}: {}", channel, LogSafe.exception(e));
        }
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
