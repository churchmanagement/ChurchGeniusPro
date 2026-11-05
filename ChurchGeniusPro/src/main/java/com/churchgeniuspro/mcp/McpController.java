package com.churchgeniuspro.mcp;

import com.churchgeniuspro.ai.audit.AiAuditService;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.util.AppClock;
import com.churchgeniuspro.util.SessionUtil;
import com.churchgeniuspro.webfilter.ApiTokenFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * The MCP endpoint (AI Phase 5, 2026-11-05): JSON-RPC 2.0 over Streamable HTTP at {@code POST /mcp},
 * stateless (no server-initiated stream, no session id — every request carries the bearer token
 * and is complete in itself; the response is always a single JSON object). Methods:
 * {@code initialize}, {@code ping}, {@code tools/list}, {@code tools/call}; notifications are
 * acknowledged with 202; resources and prompts are not offered.
 *
 * <p>By the time a request reaches this class {@code ApiTokenFilter} has authenticated the token and
 * the request's session is the token owner's staff sign-in ({@link TokenSession}). Every tool call
 * resolves the owner's {@code AiAccess} the way the assistant does, runs the same service, redacts
 * contact details unless the token has the {@code contact} scope, and writes one audit row
 * (channel MCP, intent = tool name, outcome OK / DENIED / ERROR).
 */
@RestController
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);
    public static final String SERVER_NAME = "ChurchGeniusPro";

    private final McpToolCatalog catalog;
    private final AiAccessPolicy access;
    private final AiAuditService audit;
    private final String version;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpController(McpToolCatalog catalog, AiAccessPolicy access, AiAuditService audit,
                         @Value("${mcp.server-version:1.0}") String version) {
        this.catalog = catalog; this.access = access; this.audit = audit; this.version = version;
    }

    /** No server→client stream: a GET must answer 405 so the client falls back to plain request/response. */
    @GetMapping("/mcp")
    public ResponseEntity<Map<String, Object>> get() {
        return ResponseEntity.status(405).header(HttpHeaders.ALLOW, "POST").body(Map.of("error", "This MCP server is stateless: send JSON-RPC requests with POST."));
    }

    /** Session end from a client that keeps one: there is none to end. */
    @DeleteMapping("/mcp")
    public ResponseEntity<Void> delete() { return ResponseEntity.status(405).header(HttpHeaders.ALLOW, "POST").build(); }

    @PostMapping(value = "/mcp", consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> post(@RequestBody(required = false) String body, HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        if (!TokenSession.isToken(s)) return ResponseEntity.status(401).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer").body(Map.of("error", "A personal access token is required."));
        JsonNode node;
        try { node = body == null || body.isBlank() ? null : mapper.readTree(body); }
        catch (Exception e) { return ResponseEntity.ok(McpJsonRpc.error(null, McpJsonRpc.PARSE_ERROR, "Parse error: the body is not valid JSON.")); }
        if (node != null && node.isArray()) return ResponseEntity.ok(McpJsonRpc.error(null, McpJsonRpc.INVALID_REQUEST, "Batch requests are not supported (MCP " + McpJsonRpc.PROTOCOL_VERSION + ")."));
        McpJsonRpc.Request rq;
        try { rq = McpJsonRpc.parse(node); }
        catch (IllegalArgumentException e) { return ResponseEntity.ok(McpJsonRpc.error(node == null ? null : node.get("id"), McpJsonRpc.INVALID_REQUEST, "Invalid request: " + e.getMessage())); }

        if (rq.notification()) return ResponseEntity.accepted().build();   // notifications/initialized, notifications/cancelled …

        String cid = SessionUtil.getAppClientId(req);
        switch (rq.method()) {
            case "initialize": return ResponseEntity.ok(McpJsonRpc.result(rq.id(), initialize(rq.params(), s)));
            case "ping":       return ResponseEntity.ok(McpJsonRpc.result(rq.id(), Map.of()));
            case "tools/list": return ResponseEntity.ok(McpJsonRpc.result(rq.id(), toolsList(s)));
            case "tools/call": return ResponseEntity.ok(toolsCall(rq, req, s, cid));
            case "resources/list": case "resources/templates/list": case "prompts/list":
                return ResponseEntity.ok(McpJsonRpc.error(rq.id(), McpJsonRpc.METHOD_NOT_FOUND, "This server offers tools only."));
            default:
                return ResponseEntity.ok(McpJsonRpc.error(rq.id(), McpJsonRpc.METHOD_NOT_FOUND, "Method not found: " + rq.method()));
        }
    }

    /* ───────────────────────── methods ───────────────────────── */

    private Map<String, Object> initialize(JsonNode params, HttpSession s) {
        String asked = params != null && params.hasNonNull("protocolVersion") ? params.get("protocolVersion").asText() : null;
        String negotiated = asked != null && McpJsonRpc.SUPPORTED_VERSIONS.contains(asked) ? asked : McpJsonRpc.PROTOCOL_VERSION;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", negotiated);
        out.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        out.put("serverInfo", Map.of("name", SERVER_NAME, "title", "ChurchGeniusPro church data", "version", version));
        Set<String> scopes = TokenSession.scopes(s);
        out.put("instructions", "Tools read ONE church's data with the permissions of the staff member who issued the token"
                + (scopes != null && !scopes.contains("contact") ? "; contact details (e-mail, phone, address) are withheld by this token's scopes" : "")
                + ". Start with ask_church_data for plain-English questions. Never state a person's age, birth year or years married. "
                + (scopes != null && scopes.contains("actions")
                   ? "Write actions are two steps: propose_action shows a summary; call confirm_action ONLY after the person explicitly agrees."
                   : "This token is read-only."));
        return out;
    }

    private Map<String, Object> toolsList(HttpSession s) {
        Set<String> scopes = TokenSession.scopes(s);
        List<Map<String, Object>> tools = new ArrayList<>();
        for (McpToolAdapter t : catalog.visible(scopes == null ? Set.of("read") : scopes)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name()); m.put("title", t.title()); m.put("description", t.description());
            m.put("inputSchema", t.inputSchema());
            Map<String, Object> ann = new LinkedHashMap<>();
            ann.put("readOnlyHint", !t.writes()); ann.put("destructiveHint", false); ann.put("idempotentHint", !t.writes()); ann.put("openWorldHint", false);
            m.put("annotations", ann);
            tools.add(m);
        }
        return Map.of("tools", tools);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toolsCall(McpJsonRpc.Request rq, HttpServletRequest req, HttpSession s, String cid) {
        long started = System.currentTimeMillis();
        JsonNode p = rq.params();
        String name = p != null && p.hasNonNull("name") ? p.get("name").asText() : null;
        Set<String> scopes = TokenSession.scopes(s);
        if (scopes == null) scopes = Set.of("read");
        McpToolAdapter tool = catalog.get(name);
        String actor = String.valueOf(s.getAttribute("username")), role = String.valueOf(s.getAttribute("role"));
        if (tool == null || (tool.requiredScope() != null && !scopes.contains(tool.requiredScope()))) {
            // An unknown tool and one outside the token's scopes look the same from outside — the list is the contract.
            audit.record(cid, actor, role, ApiTokenFilter.CHANNEL, "/mcp", "tool.unknown", null, 200, "DENIED", System.currentTimeMillis() - started);
            return McpJsonRpc.error(rq.id(), McpJsonRpc.INVALID_PARAMS, "Unknown tool: " + (name == null ? "(none)" : name));
        }
        Map<String, Object> args;
        try {
            JsonNode a = p.get("arguments");
            args = a == null || a.isNull() ? new LinkedHashMap<>() : mapper.convertValue(a, LinkedHashMap.class);
        } catch (IllegalArgumentException e) {
            return McpJsonRpc.error(rq.id(), McpJsonRpc.INVALID_PARAMS, "arguments must be an object.");
        }
        McpToolAdapter.Result r;
        try {
            AiAccessPolicy.AiAccess a = access.resolve(req, cid);
            McpToolAdapter.Ctx ctx = new McpToolAdapter.Ctx(req, s, cid, a, access.crossAccess(req, cid), scopes, AppClock.today());
            r = tool.call(ctx, args);
        } catch (IllegalArgumentException e) {
            r = McpToolAdapter.Result.fail(e.getMessage() == null ? "Invalid arguments." : e.getMessage());
        } catch (RuntimeException e) {
            log.warn("[mcp] tool {} failed: {}", tool.name(), e.getClass().getSimpleName());
            audit.record(cid, actor, role, ApiTokenFilter.CHANNEL, "/mcp", tool.name(), null, 200, "ERROR", System.currentTimeMillis() - started);
            return McpJsonRpc.result(rq.id(), toolResult(McpToolAdapter.Result.fail("The tool failed on the server. Nothing was changed."), scopes));
        }
        audit.record(cid, actor, role, ApiTokenFilter.CHANNEL, "/mcp", tool.name(), null, 200, r.error() ? "DENIED" : "OK", System.currentTimeMillis() - started);
        return McpJsonRpc.result(rq.id(), toolResult(r, scopes));
    }

    /** MCP tool result: text content (the JSON, for clients that read text) + structuredContent, redacted unless the token has {@code contact}. */
    @SuppressWarnings("unchecked")
    Map<String, Object> toolResult(McpToolAdapter.Result r, Set<String> scopes) {
        Object data = r.structured();
        if (!scopes.contains("contact")) data = McpRedactor.redact(data);
        Map<String, Object> structured = data instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of("value", data);
        String text;
        try { text = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(structured); }
        catch (Exception e) { text = String.valueOf(structured); }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", List.of(Map.of("type", "text", "text", text)));
        out.put("structuredContent", structured);
        out.put("isError", r.error());
        return out;
    }
}
