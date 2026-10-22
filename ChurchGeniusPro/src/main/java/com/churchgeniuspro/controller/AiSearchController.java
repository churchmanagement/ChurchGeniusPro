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
    private final com.churchgeniuspro.service.AiAccessPolicy accessPolicy;

    public AiSearchController(AiSearchService searchService, com.churchgeniuspro.service.AiAccessPolicy accessPolicy) {
        this.searchService = searchService;
        this.accessPolicy = accessPolicy;
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

        // The tenant and the access rights come from the session only — a clientId or
        // role in the request body is ignored.
        String clientId = RoleGuard.clientId(request);
        if (clientId == null || clientId.isBlank()) return ResponseEntity.status(401).build();
        String query = body != null ? body.get("query") : null;
        Map<String, Object> result = searchService.search(query, accessPolicy.resolve(request, clientId), clientId);
        return ResponseEntity.ok(result);
    }

    /**
     * GET /api/ai-search/nav — the navigation catalogue with the server's verdict per page for
     * this session (Phase D). The search bar uses it so a link is offered only for a page the
     * login can actually open; a page it cannot open is still described, never hidden as if
     * the feature did not exist.
     */
    @org.springframework.web.bind.annotation.GetMapping("/api/ai-search/nav")
    public ResponseEntity<Map<String, Object>> nav(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || RoleGuard.clientId(request) == null) return ResponseEntity.status(401).build();
        if (RoleGuard.requireFeature(request, RoleGuard.PERM_AI_ASSISTANT) != null) {
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to the AI Assistant."));
        }
        com.churchgeniuspro.service.AiAccessPolicy.AiAccess a = accessPolicy.resolve(request, RoleGuard.clientId(request));
        java.util.List<Map<String, Object>> pages = new java.util.ArrayList<>();
        for (com.churchgeniuspro.service.AiNavigationCatalog.Page p : com.churchgeniuspro.service.AiNavigationCatalog.PAGES) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("label", p.label()); m.put("url", p.url()); m.put("category", p.category());
            m.put("description", p.description()); m.put("available", a.canOpen(p.url()));
            pages.add(m);
        }
        return ResponseEntity.ok(Map.of("pages", pages));
    }
}
