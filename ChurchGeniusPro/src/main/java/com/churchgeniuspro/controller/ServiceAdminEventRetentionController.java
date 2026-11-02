package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.EventRetentionService;
import com.churchgeniuspro.util.AppClock;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Service Admin view of the event retention rules (2026-11-02). Same protection as the
 * other {@code /api/serviceadmin/*} controllers: {@code ServiceAdminAuthFilter} requires a
 * Service Admin session, {@code CsrfOriginFilter} checks POST origins, and the role is
 * checked again here.
 *
 * <ul>
 *   <li>{@code GET  /api/serviceadmin/events/retention}     — dry run (nothing changes)</li>
 *   <li>{@code GET  /api/serviceadmin/events/retention/csv} — the same candidate list as CSV</li>
 *   <li>{@code POST /api/serviceadmin/events/{id}/restore}  — undo a RETENTION archive</li>
 * </ul>
 * There is deliberately no "run now" endpoint: archive and permanent delete run only from
 * the nightly job, and only when their switches are on.
 */
@RestController
public class ServiceAdminEventRetentionController {

    private final EventRetentionService retention;

    public ServiceAdminEventRetentionController(EventRetentionService retention) {
        this.retention = retention;
    }

    @GetMapping("/api/serviceadmin/events/retention")
    public ResponseEntity<?> dryRun(@RequestParam(defaultValue = "500") int limit, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return unauthorized();
        return ResponseEntity.ok(retention.dryRun(AppClock.today(), limit));
    }

    @GetMapping("/api/serviceadmin/events/retention/csv")
    public ResponseEntity<?> csv(HttpServletRequest req) {
        if (!isServiceAdmin(req)) return unauthorized();
        Map<String, Object> d = retention.dryRun(AppClock.today(), 5000);
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("Event retention dry run,").append(d.get("today")).append(" (").append(d.get("timeZone")).append(")\r\n");
        sb.append("Archive cutoff (last day before),").append(d.get("archiveCutoff")).append("\r\n");
        sb.append("Permanent delete cutoff (last day before),").append(d.get("deleteCutoff")).append("\r\n\r\n");
        sb.append("Event ID,Church,Event name,Last day,Current state,Action\r\n");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) d.get("events");
        for (Map<String, Object> e : events) {
            sb.append(e.get("id")).append(',').append(cell(e.get("church"))).append(',').append(cell(e.get("name")))
              .append(',').append(e.get("lastDay")).append(',').append(cell(e.get("state"))).append(',').append(e.get("action")).append("\r\n");
        }
        sb.append("\r\nRelated rows a permanent delete would remove\r\nTable,Rows\r\n");
        @SuppressWarnings("unchecked")
        Map<String, Object> rows = (Map<String, Object>) d.get("permanentDeleteRows");
        rows.forEach((t, n) -> sb.append(t).append(',').append(n).append("\r\n"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"event-retention-dry-run-" + d.get("today") + ".csv\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(sb.toString());
    }

    @PostMapping("/api/serviceadmin/events/{id}/restore")
    public ResponseEntity<?> restore(@PathVariable Integer id, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return unauthorized();
        if (!retention.restore(id)) {
            return ResponseEntity.status(404).body(Map.of("error", "No event with that ID was archived by the retention rule."));
        }
        return ResponseEntity.ok(Map.of("restored", true, "id", id));
    }

    /** CSV cell: quoted, and a leading = + - @ neutralised so a spreadsheet never runs it. */
    static String cell(Object v) {
        String s = v == null ? "" : String.valueOf(v);
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static boolean isServiceAdmin(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null && "ServiceAdmin".equals(s.getAttribute("role"));
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Service admin login required."));
    }
}
