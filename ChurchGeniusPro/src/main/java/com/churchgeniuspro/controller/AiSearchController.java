package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.AiSearchService;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST endpoint for the home-page AI search bar.
 *
 * <p>POST /api/ai-search — accepts {@code { "query": "..." }} and returns an
 * intent-based structured response including an answer sentence, optional
 * navigation links, and optional data result rows.
 */
@RestController
public class AiSearchController {

    private final AiSearchService searchService;

    public AiSearchController(AiSearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping("/api/ai-search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("username") == null) {
            return ResponseEntity.status(401).build();
        }
        // AI Assistant permission (viewUsers → More → AI Assistant): enforced here as
        // well as on the /ai-assistant page, not only by hiding the menu item.
        if (RoleGuard.requireFeature(request, RoleGuard.PERM_AI_ASSISTANT) != null) {
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to the AI Assistant."));
        }

        String  role      = (String) session.getAttribute("role");
        boolean isChurch  = Boolean.TRUE.equals(session.getAttribute("church"));
        String  clientId  = RoleGuard.clientId(request);

        String query = body != null ? body.get("query") : null;
        Map<String, Object> result = searchService.search(query, role, isChurch, clientId);
        return ResponseEntity.ok(result);
    }
}
