package com.churchgeniuspro.mcp;

import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.AiAccessPolicy.CrossAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One tool the MCP endpoint exposes (AI Phase 5): a thin adapter over an existing service.
 * The adapter never checks permissions itself beyond the token scope — it hands the request
 * (whose session is the token owner's staff sign-in) to the same service the page uses, so the
 * page's rules decide, and a refusal comes back as the same {@code denied} answer the assistant shows.
 */
public interface McpToolAdapter {

    /** Everything a call needs, resolved once per request by the controller. */
    record Ctx(HttpServletRequest req, HttpSession session, String cid, AiAccess access, CrossAccess cross, Set<String> scopes, LocalDate today) {
        public boolean scope(String s) { return scopes.contains(s); }
    }

    /** A call's outcome: {@code structured} is returned as structuredContent + its JSON as text; {@code error} marks isError. */
    record Result(Map<String, Object> structured, boolean error) {
        public static Result ok(Map<String, Object> m) { return new Result(m, false); }
        public static Result fail(String message) { Map<String, Object> m = new LinkedHashMap<>(); m.put("error", message); return new Result(m, true); }
    }

    String name();
    String title();
    String description();
    /** JSON Schema (draft 2020-12 subset) of the arguments. */
    Map<String, Object> inputSchema();
    /** The token scope the tool needs beyond {@code read} (null = none). */
    default String requiredScope() { return null; }
    /** Marks a write tool (annotations.readOnlyHint false). */
    default boolean writes() { return false; }

    Result call(Ctx ctx, Map<String, Object> args);

    /* ───────── small schema helpers ───────── */

    static Map<String, Object> schema(List<String> required, Object... nameTypeDescTriples) {
        Map<String, Object> props = new LinkedHashMap<>();
        for (int i = 0; i + 2 < nameTypeDescTriples.length; i += 3) {
            Map<String, Object> p = new LinkedHashMap<>();
            Object type = nameTypeDescTriples[i + 1];
            if (type instanceof Map<?, ?> m) { for (Map.Entry<?, ?> e : m.entrySet()) p.put(String.valueOf(e.getKey()), e.getValue()); }
            else p.put("type", type);
            p.put("description", nameTypeDescTriples[i + 2]);
            props.put(String.valueOf(nameTypeDescTriples[i]), p);
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("properties", props);
        if (required != null && !required.isEmpty()) s.put("required", required);
        s.put("additionalProperties", false);
        return s;
    }
}
