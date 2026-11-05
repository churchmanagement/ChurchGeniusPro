package com.churchgeniuspro.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The JSON-RPC 2.0 envelope the MCP transport uses (AI Phase 5). Pinned to MCP protocol
 * version {@value #PROTOCOL_VERSION}; the two earlier revisions are accepted on
 * {@code initialize} so older clients connect too. Batches (an array body) were removed from
 * the protocol in 2025-06-18 and are refused here as an invalid request.
 */
public final class McpJsonRpc {

    public static final String PROTOCOL_VERSION = "2025-06-18";
    public static final java.util.Set<String> SUPPORTED_VERSIONS = java.util.Set.of("2025-06-18", "2025-03-26", "2024-11-05");

    public static final int PARSE_ERROR = -32700, INVALID_REQUEST = -32600, METHOD_NOT_FOUND = -32601, INVALID_PARAMS = -32602, INTERNAL_ERROR = -32603;

    private McpJsonRpc() {}

    /** A parsed request. {@code id} is null for a notification. */
    public record Request(JsonNode id, String method, JsonNode params, boolean notification) {}

    /** Reads one request object; throws {@link IllegalArgumentException} with the right code message when it is not one. */
    public static Request parse(JsonNode body) {
        if (body == null || !body.isObject()) throw new IllegalArgumentException("A JSON-RPC request object is required.");
        JsonNode v = body.get("jsonrpc");
        if (v == null || !"2.0".equals(v.asText())) throw new IllegalArgumentException("jsonrpc must be \"2.0\".");
        JsonNode m = body.get("method");
        if (m == null || !m.isTextual() || m.asText().isBlank()) throw new IllegalArgumentException("method is required.");
        JsonNode id = body.get("id");
        boolean notification = id == null || id.isNull();
        if (!notification && !(id.isTextual() || id.isNumber())) throw new IllegalArgumentException("id must be a string or a number.");
        JsonNode params = body.get("params");
        if (params != null && !params.isNull() && !params.isObject() && !params.isArray()) throw new IllegalArgumentException("params must be an object.");
        return new Request(notification ? null : id, m.asText(), params == null || params.isNull() ? null : params, notification);
    }

    public static Map<String, Object> result(JsonNode id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", idValue(id));
        m.put("result", result);
        return m;
    }

    public static Map<String, Object> error(JsonNode id, int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", idValue(id));
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("code", code); e.put("message", message);
        m.put("error", e);
        return m;
    }

    private static Object idValue(JsonNode id) {
        if (id == null || id.isNull()) return null;
        if (id.isTextual()) return id.asText();
        if (id.isIntegralNumber()) return id.asLong();
        return id.asDouble();
    }
}
