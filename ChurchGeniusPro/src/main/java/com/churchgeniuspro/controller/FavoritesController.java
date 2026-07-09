package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.UserFavorite;
import com.churchgeniuspro.repository.UserFavoriteRepository;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-account sidebar Favorites.
 *
 * <p>Each authenticated account (staff / member / church) keeps its own list of
 * favorited nav items, scoped to the tenant ({@code clientId}). Favorites are a
 * pure convenience shortcut layer — they never grant access; the underlying page
 * guards still apply, and the frontend only renders favorites that map to a nav
 * item the user can currently see.
 *
 * <ul>
 *   <li>{@code GET    /api/favorites}            — list the current account's favorites.</li>
 *   <li>{@code POST   /api/favorites}            — add one (idempotent).</li>
 *   <li>{@code DELETE /api/favorites?pageId=…}   — remove one.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/favorites")
public class FavoritesController {

    private final UserFavoriteRepository repo;

    public FavoritesController(UserFavoriteRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        String clientId = SessionUtil.getAppClientId(request);
        String userRef  = userRef(request);
        if (clientId == null || userRef == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        if (favoritesDenied(request)) return ResponseEntity.status(403).body(Map.of("error", "Favorites access denied"));
        return ResponseEntity.ok(toDtos(repo.findByClientIdAndUserRefOrderBySortOrderAscIdAsc(clientId, userRef)));
    }

    @PostMapping
    public ResponseEntity<?> add(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        String clientId = SessionUtil.getAppClientId(request);
        String userRef  = userRef(request);
        if (clientId == null || userRef == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        if (favoritesDenied(request)) return ResponseEntity.status(403).body(Map.of("error", "Favorites access denied"));

        String pageId = str(body.get("pageId"));
        if (pageId == null || pageId.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "pageId is required"));

        if (!repo.existsByClientIdAndUserRefAndPageId(clientId, userRef, pageId)) {
            UserFavorite f = new UserFavorite();
            f.setClientId(clientId);
            f.setUserRef(userRef);
            f.setPageId(pageId);
            f.setLabel(str(body.get("label")));
            f.setHref(str(body.get("href")));
            f.setIcon(str(body.get("icon")));
            f.setPerm(str(body.get("perm")));
            f.setSortOrder((int) repo.countByClientIdAndUserRef(clientId, userRef));
            repo.save(f);
        }
        return ResponseEntity.ok(toDtos(repo.findByClientIdAndUserRefOrderBySortOrderAscIdAsc(clientId, userRef)));
    }

    @DeleteMapping
    public ResponseEntity<?> remove(HttpServletRequest request, @RequestParam("pageId") String pageId) {
        String clientId = SessionUtil.getAppClientId(request);
        String userRef  = userRef(request);
        if (clientId == null || userRef == null) return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        if (favoritesDenied(request)) return ResponseEntity.status(403).body(Map.of("error", "Favorites access denied"));
        repo.deleteByClientIdAndUserRefAndPageId(clientId, userRef, pageId);
        return ResponseEntity.ok(toDtos(repo.findByClientIdAndUserRefOrderBySortOrderAscIdAsc(clientId, userRef)));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * True when the current account has the {@code favorites} permission explicitly
     * turned off. Works for both staff ("privileges") and member-portal
     * ("memberPrivileges") sessions; church/no-saved-perms accounts are allowed
     * (opt-in denial), matching {@link RoleGuard#requirePermission}.
     */
    private boolean favoritesDenied(HttpServletRequest request) {
        return RoleGuard.requirePermission(request, "favorites") != null;
    }

    private List<Map<String, Object>> toDtos(List<UserFavorite> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserFavorite f : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pageId", f.getPageId());
            m.put("label", f.getLabel());
            m.put("href", f.getHref());
            m.put("icon", f.getIcon());
            m.put("perm", f.getPerm());
            out.add(m);
        }
        return out;
    }

    /**
     * Stable per-account key within the tenant. Members are keyed by memberId,
     * church accounts by their clientId, and staff by username.
     */
    private String userRef(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;

        Object role     = session.getAttribute("role");
        Object memberId = session.getAttribute("memberId");
        if ("Member".equals(role) && memberId != null) return "member:" + memberId;

        Object church = session.getAttribute("church");
        boolean isChurch = Boolean.TRUE.equals(church) || "true".equalsIgnoreCase(String.valueOf(church));
        if (isChurch) {
            Object cid = session.getAttribute("clientId");
            if (cid instanceof String s && !s.isBlank()) return "church:" + s;
        }

        Object username = session.getAttribute("username");
        if (username instanceof String s && !s.isBlank()) return "user:" + s;

        return null;
    }

    private String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
