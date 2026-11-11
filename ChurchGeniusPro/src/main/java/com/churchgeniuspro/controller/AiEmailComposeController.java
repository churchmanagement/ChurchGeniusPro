package com.churchgeniuspro.controller;

import com.churchgeniuspro.ai.guided.AiGuidedEmailService;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 2026-11-11 — the Send button of the Genius Assistant e-mail review card.
 *
 * <p>{@code POST /api/ai/email/send} (multipart: {@code token} + optional {@code files}). The request carries NO
 * subject, text or recipients: those are the draft kept on this session by {@link AiGuidedEmailService}, which
 * re-checks the Email page's own rule, consumes the one-time token and sends through the Email page's own
 * service. A wrong, used or expired token sends nothing.
 */
@RestController
public class AiEmailComposeController {

    private final AiGuidedEmailService email;

    public AiEmailComposeController(AiGuidedEmailService email) { this.email = email; }

    @PostMapping(value = "/api/ai/email/send", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> send(@RequestParam String token,
                                                    @RequestParam(value = "files", required = false) List<MultipartFile> files,
                                                    HttpServletRequest request) {
        Map<String, Object> resp = new LinkedHashMap<>();
        String clientId = SessionUtil.getAppClientId(request);
        if (clientId == null || clientId.isBlank()) { resp.put("error", "Not signed in."); return ResponseEntity.status(401).body(resp); }
        if (RoleGuard.requireFeature(request, RoleGuard.PERM_AI_ASSISTANT) != null) {
            resp.put("error", "You do not have access to the AI Assistant."); return ResponseEntity.status(403).body(resp);
        }
        if (!AiGuidedEmailService.mayCompose(request)) { resp.put("error", "Permission denied"); return ResponseEntity.status(403).body(resp); }
        return ResponseEntity.ok(AiDataSearchController.audited(request, email.send(request, clientId, token, files)));
    }
}
