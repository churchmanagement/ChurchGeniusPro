package com.churchgeniuspro.controller;

import com.churchgeniuspro.ai.action.AiActionService;
import com.churchgeniuspro.ai.action.AiToolRegistry;
import com.churchgeniuspro.ai.audit.AiAuditService;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI Phase 4 — the action agent's own endpoints: {@code POST /api/ai/actions/confirm {token}} (the card's button
 * or a spoken yes), {@code POST /api/ai/actions/reject {token}}, {@code GET /api/ai/actions} (recent proposals:
 * admins see the church's, others their own), {@code GET /api/ai/actions/tools} (the registry). Proposals are
 * CREATED through the AI Search route ({@code /api/ai-search/data}). Plan feature {@code aiActions}
 * (SubscriptionFeatureFilter on /api/ai/actions), AiGuardFilter rate limit + audit (channel ACTIONS), same
 * staff-only gate as the other AI endpoints.
 */
@RestController
public class AiActionController {

    private final AiActionService actions;
    private final AiToolRegistry registry;

    public AiActionController(AiActionService actions, AiToolRegistry registry) { this.actions = actions; this.registry = registry; }

    @PostMapping("/api/ai/actions/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> g = AiWritingController.gate(req, "AI actions");
        if (g != null) return g;
        String token = body == null || body.get("token") == null ? null : String.valueOf(body.get("token"));
        Map<String, Object> m = actions.confirm(req, SessionUtil.getAppClientId(req), token);
        return ResponseEntity.ok(AiDataSearchController.audited(req, m));
    }

    @PostMapping("/api/ai/actions/reject")
    public ResponseEntity<Map<String, Object>> reject(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> g = AiWritingController.gate(req, "AI actions");
        if (g != null) return g;
        String token = body == null || body.get("token") == null ? null : String.valueOf(body.get("token"));
        return ResponseEntity.ok(AiDataSearchController.audited(req, actions.reject(req, SessionUtil.getAppClientId(req), token)));
    }

    /** 2026-11-20: "several donations" conversation — optional so hand-built tests keep their constructor. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.ai.guided.AiGuidedBatchService batch;

    /**
     * {@code POST /api/ai/actions/batch-check} — the chat scanned a check photo with the existing check scan
     * ({@code /api/income/check-scan}) and hands the result to the open "several donations" conversation, which
     * answers with that check's review (or why it could not be used). Never saves: the entry is saved only by its
     * own Confirm &amp; Save. Same gate, plan feature, rate limit and audit as the other action endpoints.
     */
    @PostMapping("/api/ai/actions/batch-check")
    public ResponseEntity<Map<String, Object>> batchCheck(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> g = AiWritingController.gate(req, "AI actions");
        if (g != null) return g;
        if (batch == null) return ResponseEntity.status(503).body(Map.of("error", "This is not available right now."));
        Map<String, Object> m = batch.checkScanned(req, SessionUtil.getAppClientId(req), body);
        return ResponseEntity.ok(AiDataSearchController.audited(req, m));
    }

    @GetMapping("/api/ai/actions")
    public ResponseEntity<Map<String, Object>> recent(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> g = AiWritingController.gate(req, "AI actions");
        if (g != null) return g;
        AiAuditService.intent(req, "action.list");
        String role = String.valueOf(req.getSession(false).getAttribute("role"));
        boolean all = "SuperAdmin".equals(role) || "Admin".equals(role);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("all", all);
        out.put("enabled", actions.isEnabled());
        out.put("rows", actions.recent(SessionUtil.getAppClientId(req), String.valueOf(req.getSession(false).getAttribute("username")), all));
        return ResponseEntity.ok(out);
    }

    @GetMapping("/api/ai/actions/tools")
    public ResponseEntity<Map<String, Object>> tools(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> g = AiWritingController.gate(req, "AI actions");
        if (g != null) return g;
        AiAuditService.intent(req, "action.tools");
        return ResponseEntity.ok(Map.of("tools", registry.describe(), "enabled", actions.isEnabled()));
    }
}
