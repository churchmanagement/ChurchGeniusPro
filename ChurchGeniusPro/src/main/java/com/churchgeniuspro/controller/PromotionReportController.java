package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.PromotionReportService;
import com.churchgeniuspro.util.ErrorResponses;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Promotions phase 5.
 * <ul>
 *   <li>{@code GET /api/serviceadmin/promotions/report} and {@code …/report-csv} — Service Admin only;
 *       filters code, client, plan, frequency, status, from, to (all optional).</li>
 *   <li>{@code GET /api/subscription/promotion} — a church's own promotional pricing: the
 *       session's church only (no client id parameter), Admin or Church login.</li>
 * </ul>
 */
@RestController
public class PromotionReportController {

    private static final Logger log = LoggerFactory.getLogger(PromotionReportController.class);

    private final PromotionReportService reports;

    public PromotionReportController(PromotionReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/api/serviceadmin/promotions/report")
    public ResponseEntity<Map<String, Object>> report(@RequestParam Map<String, String> q, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Service Admin login required"));
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "success");
            out.putAll(reports.report(filter(q)));
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("status", "error",
                    "message", ErrorResponses.message(log, "The promotion report could not be loaded", e)));
        }
    }

    @GetMapping("/api/serviceadmin/promotions/report-csv")
    public ResponseEntity<byte[]> csv(@RequestParam Map<String, String> q, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return ResponseEntity.status(401).build();
        try {
            byte[] body = reports.csv(filter(q)).getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"promotion-report-" + LocalDate.now() + ".csv\"")
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                    .body(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            ErrorResponses.message(log, "The promotion report CSV could not be built", e);
            return ResponseEntity.status(500).build();
        }
    }

    @GetMapping("/api/subscription/promotion")
    public ResponseEntity<Map<String, Object>> own(HttpServletRequest request) {
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Login required"));
        if (RoleGuard.requireAdminOrChurch(request) != null) {
            return ResponseEntity.status(403).body(Map.of("status", "error", "message", "Admin or Church role required"));
        }
        try {
            return ResponseEntity.ok(reports.churchView(clientId));
        } catch (Exception e) {
            log.warn("Own promotion lookup failed: {}", e.getClass().getSimpleName());
            return ResponseEntity.ok(Map.of("active", false));
        }
    }

    private static PromotionReportService.Filter filter(Map<String, String> q) {
        return new PromotionReportService.Filter(q.get("code"), q.get("client"), q.get("plan"), q.get("frequency"),
                q.get("status"), date(q.get("from"), "From"), date(q.get("to"), "To"));
    }

    private static LocalDate date(String v, String label) {
        if (v == null || v.isBlank()) return null;
        try { return LocalDate.parse(v.trim()); }
        catch (DateTimeParseException e) { throw new IllegalArgumentException(label + " date is not valid."); }
    }

    private static boolean isServiceAdmin(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null && "ServiceAdmin".equals(s.getAttribute("role"));
    }
}
