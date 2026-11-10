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
    /** AI Phase 4 action agent; optional so hand-built tests keep their constructor. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.ai.action.AiActionService actions;
    /** 2026-11-08: guided "add an income / expense" conversation (between the meeting flow and the action agent). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.ai.guided.AiGuidedRecordService guided;
    /** 2026-11-08: guided "add a fund / purpose / transaction type" (Account Settings entries). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.ai.guided.AiGuidedSettingService settings;
    /** 2026-11-08: edit / delete requests are never carried out — answered with a link to the managing page. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.ai.guided.AiManageRedirectService manage;

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
            // A new "add an income / expense" or "create a meeting" request starts over: the other
            // conversation's open draft is dropped so an answer never lands in the wrong one.
            jakarta.servlet.http.HttpSession hs = request.getSession(false);
            // "Delete the Building Fund", "rename transaction type Zelle", "change the $50 expense": AI Search never edits
            // or deletes an existing record — the answer links to the page that manages it. Checked first so no other
            // route can read the request as a create or a data question; an open conversation's own correction
            // ("change the fund to …") is left to that conversation, and its draft is kept.
            if (manage != null) {
                Map<String, Object> mr = manage.handle(request, clientId, question,
                        com.churchgeniuspro.ai.guided.AiManageRedirectService.draftOpen(hs));
                if (mr != null) return ResponseEntity.ok(audited(request, mr));
            }
            if (settings != null && com.churchgeniuspro.ai.guided.AiGuidedSettingService.isStartRequest(question)) {
                if (hs != null) { hs.removeAttribute("aiMeetingDraft"); com.churchgeniuspro.ai.guided.AiGuidedRecordService.clear(hs); }
                Map<String, Object> st = settings.handle(request, clientId, question);
                if (st != null) return ResponseEntity.ok(audited(request, st));
            }
            if (guided != null && com.churchgeniuspro.ai.guided.AiGuidedRecordService.isStartRequest(question)) {
                if (hs != null) { hs.removeAttribute("aiMeetingDraft"); com.churchgeniuspro.ai.guided.AiGuidedSettingService.clear(hs); }
                Map<String, Object> g = guided.handle(request, clientId, question);
                if (g != null) return ResponseEntity.ok(audited(request, g));
            }
            if (meetingCreate != null) {
                if (hs != null && meetingCreate.isCreateRequest(question, clientId)) {
                    com.churchgeniuspro.ai.guided.AiGuidedRecordService.clear(hs);
                    com.churchgeniuspro.ai.guided.AiGuidedSettingService.clear(hs);
                }
                Map<String, Object> created = meetingCreate.handle(request, clientId, question);
                if (created != null) return ResponseEntity.ok(audited(request, created));
            }
            if (guided != null) {                                   // an answer to the open income / expense question
                Map<String, Object> g = guided.handle(request, clientId, question);
                if (g != null) return ResponseEntity.ok(audited(request, g));
            }
            if (settings != null) {                                 // an answer to the open fund / purpose / transaction type question
                Map<String, Object> st = settings.handle(request, clientId, question);
                if (st != null) return ResponseEntity.ok(audited(request, st));
            }
            // AI Phase 4: "add Sarah Lee to Welcome Team", "mark the Lees as followed up", a yes/no to an open
            // proposal, or an answer to a clarifying question. Proposes only — the write happens on confirm.
            if (actions != null) {
                Map<String, Object> proposed = actions.handle(request, clientId, question);
                if (proposed != null) return ResponseEntity.ok(audited(request, proposed));
            }
            return ResponseEntity.ok(audited(request, service.answer(request, clientId, question)));
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
            return ResponseEntity.ok(audited(request, meetingCreate.confirm(request, clientId, token)));
        } catch (Exception e) {
            LOG.error("AI meeting confirm failed for clientId={}", clientId, e);
            resp.put("handled", true); resp.put("denied", false); resp.put("intent", "meeting.error");
            resp.put("answer", "The meeting could not be created. Please try again or use the Meetings page.");
            return ResponseEntity.ok(resp);
        }
    }

    /** Annotates the request for the AI audit row: intent key and whether the answer was a refusal (never the text). */
    static Map<String, Object> audited(HttpServletRequest request, Map<String, Object> answer) {
        com.churchgeniuspro.ai.audit.AiAuditService.intentFrom(request, answer);
        if (answer != null && Boolean.TRUE.equals(answer.get("denied"))) request.setAttribute("ai.denied", Boolean.TRUE);
        withShortQuestion(answer);
        return answer;
    }

    /**
     * 2026-11-09 (Genius Assistant S1): a question that lists its choices in the sentence ("Which category is this
     * meeting? Baptism, Bible Study, …") also carries the question alone as {@code question}, so the chat shows the
     * short question above the choice cards. {@code answer} is unchanged (read aloud in Converse, older clients).
     */
    static void withShortQuestion(Map<String, Object> answer) {
        if (answer == null || answer.containsKey("question") || !(answer.get("choices") instanceof java.util.List<?> ch) || ch.isEmpty()) return;
        String text = String.valueOf(answer.getOrDefault("answer", ""));
        String first = String.valueOf(ch.get(0));
        int i = first.isBlank() ? -1 : text.indexOf(first);
        String head = (i > 0 ? text.substring(0, i) : text).trim();
        if (i > 0 && !(head.endsWith("?") || head.endsWith(":"))) head = text.trim();
        if (head.endsWith(":")) head = head.substring(0, head.length() - 1).trim();
        if (!head.isEmpty() && head.length() <= 400) answer.put("question", head);
    }
}
