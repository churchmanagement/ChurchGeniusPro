package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.AiDataSearchService;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Natural-language DATA answers for the AI Search bar (Type / Voice /
 * Converse all route their text through this endpoint first).
 *
 * <p>POST {@code /api/ai-search/data} with {@code {query}} →
 * {@code {handled, answer, denied, intent}}. {@code handled=false} means the
 * question didn't look like a data lookup and the client should continue with
 * its normal flow (navigation / how-to / help search). All data access is
 * permission-checked server-side in {@link AiDataSearchService} — a user
 * without access to a module receives a denial message and no data.
 */
@RestController
public class AiDataSearchController {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AiDataSearchController.class);

    private final AiDataSearchService service;
    private final com.churchgeniuspro.service.AiMeetingCreateService meetingCreate;   // Phase E; null in legacy tests

    @org.springframework.beans.factory.annotation.Autowired
    public AiDataSearchController(AiDataSearchService service, com.churchgeniuspro.service.AiMeetingCreateService meetingCreate) {
        this.service = service;
        this.meetingCreate = meetingCreate;
    }

    public AiDataSearchController(AiDataSearchService service) { this(service, null); }

    @PostMapping("/api/ai-search/data")
    public ResponseEntity<Map<String, Object>> data(@RequestBody Map<String, Object> body,
                                                    HttpServletRequest request) {
        Map<String, Object> resp = new LinkedHashMap<>();
        String clientId = SessionUtil.getAppClientId(request);
        if (clientId == null || clientId.isBlank()) {
            resp.put("error", "Not signed in.");
            return ResponseEntity.status(401).body(resp);
        }
        // AI Assistant permission (viewUsers → More → AI Assistant) — see AiSearchController.
        if (com.churchgeniuspro.util.RoleGuard.requireFeature(request, com.churchgeniuspro.util.RoleGuard.PERM_AI_ASSISTANT) != null) {
            resp.put("error", "You do not have access to the AI Assistant.");
            return ResponseEntity.status(403).body(resp);
        }
        String question = body == null ? "" : String.valueOf(body.getOrDefault("query", "")).trim();
        if (question.isBlank()) {
            resp.put("handled", false);
            return ResponseEntity.ok(resp);
        }
        try {
            // "Create a meeting …" and the follow-up answers / confirmation of a pending draft
            // (Phase E). Runs outside the read-only answer() transaction because it may write.
            if (meetingCreate != null) {
                Map<String, Object> created = meetingCreate.handle(request, clientId, question);
                if (created != null) return ResponseEntity.ok(created);
            }
            return ResponseEntity.ok(service.answer(request, clientId, question));
        } catch (Exception e) {
            LOG.error("AI data search failed for clientId={} ({} char question)", clientId, question == null ? 0 : question.length(), e);   // the question can name members / amounts
            resp.put("handled", false);          // fail open into the normal search flow
            return ResponseEntity.ok(resp);
        }
    }

    /**
     * POST {@code /api/ai-search/confirm} with {@code {token}} — the "Create meeting" button. The token
     * is the one-time value the server issued with the {@code meeting.confirm} answer; it is bound to
     * this session and consumed on first use, so a repeated click creates nothing (Phase E).
     */
    @PostMapping("/api/ai-search/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@RequestBody Map<String, Object> body,
                                                       HttpServletRequest request) {
        Map<String, Object> resp = new LinkedHashMap<>();
        String clientId = SessionUtil.getAppClientId(request);
        if (clientId == null || clientId.isBlank()) {
            resp.put("error", "Not signed in.");
            return ResponseEntity.status(401).body(resp);
        }
        if (com.churchgeniuspro.util.RoleGuard.requireFeature(request, com.churchgeniuspro.util.RoleGuard.PERM_AI_ASSISTANT) != null) {
            resp.put("error", "You do not have access to the AI Assistant.");
            return ResponseEntity.status(403).body(resp);
        }
        if (meetingCreate == null) { resp.put("handled", false); return ResponseEntity.ok(resp); }
        String token = body == null || body.get("token") == null ? null : String.valueOf(body.get("token"));
        try {
            return ResponseEntity.ok(meetingCreate.confirm(request, clientId, token));
        } catch (Exception e) {
            LOG.error("AI meeting confirm failed for clientId={}", clientId, e);
            resp.put("handled", true); resp.put("denied", false); resp.put("intent", "meeting.error");
            resp.put("answer", "The meeting could not be created. Please try again or use the Meetings page.");
            return ResponseEntity.ok(resp);
        }
    }
}
