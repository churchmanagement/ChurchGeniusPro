package com.churchgeniuspro.ai.llm;

import com.churchgeniuspro.util.LogSafe;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one door every OpenAI HTTP call goes through (AI Phase 0, 2026-11-04).
 *
 * <p>Before this class each AI feature ({@code OpenAiVoiceService}, {@code VisionCheckService})
 * owned its own {@link HttpClient} and its own send/parse/log code, so retries, response
 * caps and token accounting had to be done four times. The gateway does them once:
 * <ul>
 *   <li><b>one bounded retry</b> on HTTP 429 / 5xx and on a transport {@link IOException}
 *       (never on a timeout — the caller's deadline stands);</li>
 *   <li><b>response-size cap</b> ({@value #MAX_BODY_CHARS} chars) so a runaway body cannot
 *       exhaust memory or logs;</li>
 *   <li><b>token accounting</b>: the {@code usage} object of a chat completion is read once
 *       and published on the current web request (see {@link #USAGE_ATTR}) so the AI
 *       audit filter can write model + tokens to {@code ai_audit_log} without every
 *       service having to know about auditing; process-wide counters are kept too.</li>
 * </ul>
 *
 * <p>The gateway holds no API key and builds no prompt: callers still own the request
 * body (and therefore what data reaches the model). It is deliberately usable as a plain
 * object ({@code new LlmGateway()}) so the existing services keep their no-arg
 * constructors and unit tests.
 */
@Component
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    /** Request attribute carrying the {@link Usage} of the last model call made during this web request. */
    public static final String USAGE_ATTR = "ai.llm.usage";
    /** Longest response body the gateway will hand back (characters). */
    public static final int MAX_BODY_CHARS = 2_000_000;

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong calls = new AtomicLong(), retries = new AtomicLong(),
                             promptTokens = new AtomicLong(), completionTokens = new AtomicLong();

    @org.springframework.beans.factory.annotation.Autowired
    public LlmGateway() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    public LlmGateway(HttpClient http) { this.http = http; }

    /** What one model call cost: echoed model name and token counts (0 when the body carried none). */
    public record Usage(String purpose, String model, int promptTokens, int completionTokens, int httpStatus, boolean retried) {
        public int totalTokens() { return promptTokens + completionTokens; }
    }

    /** A gateway reply: status + (capped) body. {@code ok()} is any 2xx. */
    public record Reply(int statusCode, String body, Usage usage) {
        public boolean ok() { return statusCode / 100 == 2; }
    }

    /**
     * Sends {@code req} with one retry on 429 / 5xx / transport error, reads the body, records
     * usage. Throws only when both attempts fail with a transport error or on interruption,
     * so callers keep their existing {@code try/catch}.
     *
     * @param purpose a short, data-free label for the audit row (e.g. {@code voice.transcribe},
     *                {@code vision.check}, {@code help.assist})
     */
    public Reply send(HttpRequest req, String purpose) throws IOException, InterruptedException {
        calls.incrementAndGet();
        HttpResponse<String> resp;
        boolean retried = false;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException te) {
            throw te;                                  // the caller's deadline is final
        } catch (IOException first) {
            retried = true; retries.incrementAndGet();
            log.info("[LlmGateway] {} transport error, retrying once: {}", purpose, LogSafe.exception(first));
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        }
        if (resp.statusCode() == 429 || resp.statusCode() / 100 == 5) {
            retried = true; retries.incrementAndGet();
            log.info("[LlmGateway] {} got HTTP {}, retrying once", purpose, resp.statusCode());
            try { Thread.sleep(backoffMillis(resp)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ie; }
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        }
        String body = resp.body() == null ? "" : resp.body();
        if (body.length() > MAX_BODY_CHARS) {
            log.warn("[LlmGateway] {} response of {} chars capped to {}", purpose, body.length(), MAX_BODY_CHARS);
            body = body.substring(0, MAX_BODY_CHARS);
        }
        Usage usage = usageOf(purpose, resp.statusCode(), body, retried);
        publish(usage);
        return new Reply(resp.statusCode(), body, usage);
    }

    /** Honour {@code Retry-After} (seconds, capped at 2 s) else a short fixed pause. */
    static long backoffMillis(HttpResponse<?> resp) {
        try {
            String ra = resp.headers().firstValue("Retry-After").orElse(null);
            if (ra != null) return Math.min(2000, Math.max(0, Long.parseLong(ra.trim()) * 1000));
        } catch (NumberFormatException ignored) { }
        return 500;
    }

    private Usage usageOf(String purpose, int status, String body, boolean retried) {
        String model = null; int p = 0, c = 0;
        if (status / 100 == 2 && body.startsWith("{")) {
            try {
                JsonNode root = mapper.readTree(body);
                model = root.path("model").asText(null);
                JsonNode u = root.path("usage");
                p = u.path("prompt_tokens").asInt(0);
                c = u.path("completion_tokens").asInt(0);
            } catch (Exception ignored) { /* not JSON (e.g. transcription text) → no usage */ }
        }
        promptTokens.addAndGet(p); completionTokens.addAndGet(c);
        return new Usage(purpose, model, p, c, status, retried);
    }

    /** Make the usage visible to the AI audit filter of the current web request (no-op outside one). */
    static void publish(Usage usage) {
        try {
            RequestAttributes ra = RequestContextHolder.getRequestAttributes();
            if (ra == null) return;
            Object prev = ra.getAttribute(USAGE_ATTR, RequestAttributes.SCOPE_REQUEST);
            if (prev instanceof Usage pu) {         // several calls in one request (e.g. transcribe + command): sum them
                usage = new Usage(pu.purpose() + "+" + usage.purpose(),
                        usage.model() != null ? usage.model() : pu.model(),
                        pu.promptTokens() + usage.promptTokens(), pu.completionTokens() + usage.completionTokens(),
                        usage.httpStatus(), pu.retried() || usage.retried());
            }
            ra.setAttribute(USAGE_ATTR, usage, RequestAttributes.SCOPE_REQUEST);
        } catch (IllegalStateException ignored) { }
    }

    /** Process-wide counters for the Service Admin status card (never reset by tenants). */
    public java.util.Map<String, Object> stats() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("calls", calls.get()); m.put("retries", retries.get());
        m.put("promptTokens", promptTokens.get()); m.put("completionTokens", completionTokens.get());
        return m;
    }
}
