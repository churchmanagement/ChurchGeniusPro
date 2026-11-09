package com.churchgeniuspro.ai.action;

import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.AiAccessPolicy.CrossAccess;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;

/**
 * AI Phase 4 — one registered write action. A tool is a thin adapter over an existing service method with four
 * duties: say who may use it, validate the raw parameters against ITS schema and the TENANT (every id must
 * resolve inside {@code clientId}), describe the resolved action in one sentence, and execute it. Tools never
 * send e-mail / SMS / push (draft-only rule of the roadmap) and never read another church's rows.
 */
public interface AiTool {

    /** Stable key, e.g. {@code add_group_member}. */
    String name();

    /** Short label for cards and the activity page, e.g. "Add to group". */
    String label();

    /** One line for the LLM tool list and the Help Center. */
    String description();

    /** Parameter names → one-line description (the LLM tool schema and the clarifying questions). */
    Map<String, String> parameters();

    /**
     * Whether this session may use the tool — the same rule the feature's own page/API applies (role, page
     * permission, plan feature). Re-checked at confirm time.
     */
    boolean allowed(HttpServletRequest req, AiAccess access, CrossAccess cross, String clientId);

    /** Resolved parameters (ids + labels, all tenant-checked) or an {@link Ask} naming what is missing / ambiguous. */
    Resolution resolve(Map<String, Object> rawParams, String clientId, String username);

    /** The sentence shown on the confirm card, built from the RESOLVED params. */
    String summary(Map<String, Object> resolved);

    /**
     * 2026-11-08 — a GUIDED-ONLY tool (create_income / create_expense) is proposed only by the guided
     * conversation ({@code AiGuidedRecordService}), which asks for every required field and shows a review card.
     * It is never offered to the LLM tool pick, the MCP action list, or {@code AiActionService.propose}.
     */
    default boolean guidedOnly() { return false; }

    /** Executes with the resolved params; returns a small result map (ids, labels, link). Throws on failure. */
    Map<String, Object> execute(Map<String, Object> resolved, String clientId, String username);

    /** Either resolved params, a question to ask, or a refusal (e.g. duplicate). */
    record Resolution(Map<String, Object> resolved, Ask ask, String refusal) {
        public static Resolution ok(Map<String, Object> resolved) { return new Resolution(resolved, null, null); }
        public static Resolution ask(String field, String question, List<String> choices) { return new Resolution(null, new Ask(field, question, choices), null); }
        public static Resolution refuse(String why) { return new Resolution(null, null, why); }
        public boolean isOk() { return resolved != null; }
    }

    /** A clarifying question; {@code choices} may list candidates (ambiguous name). */
    record Ask(String field, String question, List<String> choices) {}
}
