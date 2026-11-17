package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.ReferenceDataService;
import com.churchgeniuspro.util.ErrorResponses;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Account Settings → Fund / Purpose / Transaction Type: deleted entries, restore, permanent delete (2026-11-16).
 * Same role rule as the pages' other APIs (SuperAdmin / Admin / Accountant); the church is always the session's.
 * Soft delete stays on the existing {@code DELETE /api/sources/{id}} etc.
 *
 * <ul>
 *   <li>{@code GET    /api/reference-data/deleted?list=fund|purpose|transaction-type}</li>
 *   <li>{@code GET    /api/reference-data/{kind}/{id}/usage} — what still refers to it</li>
 *   <li>{@code POST   /api/reference-data/{kind}/{id}/restore}</li>
 *   <li>{@code DELETE /api/reference-data/{kind}/{id}/permanent} — 409 with the reasons when anything refers to it</li>
 * </ul>
 * {@code kind}: main-source | sub-source | purpose | transaction-type.
 */
@RestController
public class ReferenceDataController {

    private static final Logger log = LoggerFactory.getLogger(ReferenceDataController.class);

    private final ReferenceDataService service;

    public ReferenceDataController(ReferenceDataService service) { this.service = service; }

    private static ResponseEntity<Map<String, Object>> refuse(HttpServletRequest request) {
        if (request.getSession(false) == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        if (RoleGuard.requireAccountantOrAdmin(request) != null) return ResponseEntity.status(403).body(Map.of("error", "Permission denied"));
        if (SessionUtil.getAppClientId(request) == null) return ResponseEntity.status(401).body(Map.of("error", "Please sign in."));
        return null;
    }

    @GetMapping("/api/reference-data/deleted")
    public ResponseEntity<Map<String, Object>> deleted(@RequestParam String list, HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> no = refuse(request);
        if (no != null) return no;
        try {
            return ResponseEntity.ok(service.deleted(list, SessionUtil.getAppClientId(request)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/api/reference-data/{kind}/{id}/usage")
    public ResponseEntity<Map<String, Object>> usage(@PathVariable String kind, @PathVariable Integer id, HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> no = refuse(request);
        if (no != null) return no;
        try {
            List<Map<String, Object>> uses = service.usage(kind, id, SessionUtil.getAppClientId(request));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("inUse", !uses.isEmpty());
            out.put("canPermanentlyDelete", uses.isEmpty());
            out.put("uses", uses);
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/api/reference-data/{kind}/{id}/restore")
    public ResponseEntity<Map<String, Object>> restore(@PathVariable String kind, @PathVariable Integer id, HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> no = refuse(request);
        if (no != null) return no;
        try {
            Map<String, Object> out = new LinkedHashMap<>(service.restore(kind, id, SessionUtil.getAppClientId(request), SessionUtil.getUsername(request)));
            out.put("success", true);
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            return ErrorResponses.internal(log, "Restoring a reference entry", e);
        }
    }

    @DeleteMapping("/api/reference-data/{kind}/{id}/permanent")
    public ResponseEntity<Map<String, Object>> permanent(@PathVariable String kind, @PathVariable Integer id, HttpServletRequest request) {
        ResponseEntity<Map<String, Object>> no = refuse(request);
        if (no != null) return no;
        try {
            Map<String, Object> out = new LinkedHashMap<>(service.permanentDelete(kind, id, SessionUtil.getAppClientId(request), SessionUtil.getUsername(request)));
            out.put("success", true);
            return ResponseEntity.ok(out);
        } catch (ReferenceDataService.InUseException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", e.getMessage());
            out.put("code", "IN_USE");
            out.put("uses", e.getUses());
            return ResponseEntity.status(409).body(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // A reference this check does not know about: the database refused, nothing was deleted.
            log.warn("Permanent delete of {} {} refused by the database: {}", kind, id, com.churchgeniuspro.util.LogSafe.exception(e));
            return ResponseEntity.status(409).body(Map.of("code", "IN_USE", "error",
                    "This entry is still referred to by other records, so it cannot be permanently deleted. Nothing was changed. Use Delete (soft delete) instead."));
        } catch (RuntimeException e) {
            return ErrorResponses.internal(log, "Permanently deleting a reference entry", e);
        }
    }
}
