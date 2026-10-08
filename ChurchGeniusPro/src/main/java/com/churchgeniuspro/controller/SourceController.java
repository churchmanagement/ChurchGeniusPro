package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.MainSource;
import com.churchgeniuspro.hibernate.SubSource;
import com.churchgeniuspro.service.SourceService;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.Map;

/**
 * Handles requests for the Source admin page and REST API.
 *
 * <h3>Page routes</h3>
 * <ul>
 *   <li>{@code GET /source} → {@code source.html}</li>
 * </ul>
 *
 * <h3>API routes – Main Sources</h3>
 * <ul>
 *   <li>{@code GET    /api/sources}                      → list all main sources</li>
 *   <li>{@code POST   /api/sources}                      → create a main source</li>
 *   <li>{@code PUT    /api/sources/{id}}                 → update a main source</li>
 *   <li>{@code DELETE /api/sources/{id}}                 → soft-delete (cascades sub-sources)</li>
 * </ul>
 *
 * <h3>API routes – Sub Sources</h3>
 * <ul>
 *   <li>{@code GET    /api/sources/{sourceId}/sub-sources} → list sub-sources for a main source</li>
 *   <li>{@code POST   /api/sources/{sourceId}/sub-sources} → create a sub-source</li>
 *   <li>{@code PUT    /api/sub-sources/{id}}               → update a sub-source</li>
 *   <li>{@code PUT    /api/sub-sources/{id}/tax-deductible} → set whether it's a tax-deductible gift</li>
 *   <li>{@code DELETE /api/sub-sources/{id}}               → soft-delete a sub-source</li>
 * </ul>
 */
@Controller
public class SourceController {

    private final SourceService sourceService;

    public SourceController(SourceService sourceService) {
        this.sourceService = sourceService;
    }

    // ── Page routes ───────────────────────────────────────────────────────

    @GetMapping("/source")
    public String sourcePage(HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return deny;
        return "forward:/source.html";
    }

    /** /fund is the primary nav entry; forwards to the renamed Fund page. */
    @GetMapping("/fund")
    public String fundPage(HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "accounting.settings");
        if (deny != null) return deny;
        return "forward:/fund.html";
    }

    // ── Main Sources ──────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/sources")
    public ResponseEntity<List<Map<String, Object>>> getAllMainSources(HttpServletRequest request) {
        if (RoleGuard.requireAccountantOrAdmin(request) != null) return ResponseEntity.status(403).build();
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(sourceService.getAllMainSources(appClientId));
    }

    @ResponseBody
    @PostMapping("/api/sources")
    public ResponseEntity<Map<String, Object>> createMainSource(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        String name = body.get("sourceName");
        if (name == null || name.isBlank()) {
            return bad("Source name is required.");
        }
        try {
            MainSource saved = sourceService.createMainSource(name, appClientId);
            return ResponseEntity.ok(Map.of("id", saved.getId(), "success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    @ResponseBody
    @PutMapping("/api/sources/{id}")
    public ResponseEntity<Map<String, Object>> updateMainSource(
            @PathVariable Integer id,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        String name = body.get("sourceName");
        if (name == null || name.isBlank()) {
            return bad("Source name is required.");
        }
        try {
            MainSource saved = sourceService.updateMainSource(id, name, appClientId);
            return ResponseEntity.ok(Map.of("id", saved.getId(), "success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    @ResponseBody
    @DeleteMapping("/api/sources/{id}")
    public ResponseEntity<Map<String, Object>> deleteMainSource(@PathVariable Integer id,
                                                                HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        try {
            sourceService.deleteMainSource(id, appClientId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    // ── Sub Sources ───────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/sources/{sourceId}/sub-sources")
    public ResponseEntity<List<Map<String, Object>>> getSubSources(
            @PathVariable Integer sourceId,
            HttpServletRequest request) {
        if (RoleGuard.requireAccountantOrAdmin(request) != null) return ResponseEntity.status(403).build();
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).build();
        try {
            return ResponseEntity.ok(sourceService.getSubSources(sourceId, appClientId));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        }
    }

    @ResponseBody
    @PostMapping("/api/sources/{sourceId}/sub-sources")
    public ResponseEntity<Map<String, Object>> createSubSource(
            @PathVariable Integer sourceId,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        String name = body.get("sourceName");
        if (name == null || name.isBlank()) {
            return bad("Sub-source name is required.");
        }
        try {
            SubSource saved = sourceService.createSubSource(sourceId, name, appClientId);
            return ResponseEntity.ok(Map.of("id", saved.getId(), "success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    @ResponseBody
    @PutMapping("/api/sub-sources/{id}")
    public ResponseEntity<Map<String, Object>> updateSubSource(
            @PathVariable Integer id,
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        String name = body.get("sourceName");
        if (name == null || name.isBlank()) {
            return bad("Sub-source name is required.");
        }
        try {
            SubSource saved = sourceService.updateSubSource(id, name, appClientId);
            return ResponseEntity.ok(Map.of("id", saved.getId(), "success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    /**
     * Marks whether this sub-source's income counts as a tax-deductible gift
     * on the Year-End Tax Report and a member's own giving statement.
     * Financial audit H9.
     */
    @ResponseBody
    @PutMapping("/api/sub-sources/{id}/tax-deductible")
    public ResponseEntity<Map<String, Object>> setSubSourceTaxDeductible(
            @PathVariable Integer id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        Object raw = body.get("taxDeductible");
        boolean taxDeductible = Boolean.TRUE.equals(raw) || "true".equalsIgnoreCase(String.valueOf(raw));
        try {
            SubSource saved = sourceService.setTaxDeductible(id, taxDeductible, appClientId);
            return ResponseEntity.ok(Map.of(
                    "id", saved.getId(), "taxDeductible", saved.isTaxDeductible(), "success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    @ResponseBody
    @DeleteMapping("/api/sub-sources/{id}")
    public ResponseEntity<Map<String, Object>> deleteSubSource(@PathVariable Integer id,
                                                               HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        String appClientId = SessionUtil.getAppClientId(request);
        if (appClientId == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        try {
            sourceService.deleteSubSource(id, appClientId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (IllegalArgumentException ex) {
            return bad(ex.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
