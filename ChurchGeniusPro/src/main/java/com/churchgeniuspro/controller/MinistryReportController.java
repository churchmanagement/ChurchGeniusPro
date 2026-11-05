package com.churchgeniuspro.controller;

import com.churchgeniuspro.ai.intel.MinistryReportService;
import com.churchgeniuspro.ai.intel.MinistryReportService.Access;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.util.AppClock;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * AI Phase 2 — More → Ministry Report: one month of the church's ministry facts (attendance, visitors, follow-ups,
 * giving for finance roles, groups, volunteers, engagement signals) with comparisons and a template narrative.
 * Staff sessions only (never Member / Kids / church-owner sessions); each section follows its own page's rule
 * through {@link AiAccessPolicy}, so a viewer sees only the modules they could open anyway. Page key
 * {@link #PERM_MINISTRY_REPORT}. No model is called; the API is not an AI channel.
 */
@Controller
public class MinistryReportController {

    public static final String PERM_MINISTRY_REPORT = "more.ministryreport";
    static final int MIN_YEAR = 2000;

    private final MinistryReportService reports;
    private final AiAccessPolicy access;

    public MinistryReportController(MinistryReportService reports, AiAccessPolicy access) {
        this.reports = reports;
        this.access = access;
    }

    /** More → Ministry Report page: staff roles, page key. */
    @GetMapping("/ministryReport")
    public String page(HttpServletRequest request) {
        if (!VoiceCommandController.isStaffSession(request) || Boolean.TRUE.equals(request.getSession(false).getAttribute("church"))) return RoleGuard.FORWARD_ACCESS_DENIED;
        String deny = RoleGuard.requirePagePermission(request, PERM_MINISTRY_REPORT);
        if (deny != null) return deny;
        return "forward:/ministryReport.html";
    }

    /** The church id when the session may read the report, else a ready error response. */
    private Object gate(HttpServletRequest req) {
        String clientId = SessionUtil.getAppClientId(req);
        if (clientId == null || clientId.isBlank() || req.getSession(false) == null || req.getSession(false).getAttribute("username") == null)
            return ResponseEntity.status(401).body(Map.of("error", "Not signed in."));
        if (!VoiceCommandController.isStaffSession(req) || RoleGuard.isKidsPortalSession(req.getSession(false))
                || Boolean.TRUE.equals(req.getSession(false).getAttribute("church")))
            return ResponseEntity.status(403).body(Map.of("error", "The Ministry Report is available to staff accounts."));
        if (RoleGuard.requirePagePermission(req, PERM_MINISTRY_REPORT) != null)
            return ResponseEntity.status(403).body(Map.of("error", "You do not have access to the Ministry Report."));
        return clientId;
    }

    /** What this viewer may see, by each page's own rule. */
    Access accessFor(HttpServletRequest req, String clientId) {
        AiAccessPolicy.AiAccess a = access.resolve(req, clientId);
        if (a.member() || a.church() || a.kids()) return new Access(false, false, false, false, false);
        AiAccessPolicy.CrossAccess x = access.crossAccess(req, clientId);
        return new Access(x.attendance(), a.incomeRecords(), x.groups(), a.volunteers(), a.followUps());
    }

    static YearMonth month(Integer year, Integer month, LocalDate today) {
        if (year == null && month == null) return YearMonth.from(today).minusMonths(1);   // default: last completed month
        if (year == null || month == null || month < 1 || month > 12 || year < MIN_YEAR || year > today.getYear() + 1)
            throw new IllegalArgumentException("year and month must be given together, e.g. ?year=2026&month=10");
        YearMonth ym = YearMonth.of(year, month);
        if (ym.isAfter(YearMonth.from(today))) throw new IllegalArgumentException("That month has not started yet.");
        return ym;
    }

    /** {@code GET /api/ministry-report?year=2026&month=10} (defaults to the last completed month). */
    @ResponseBody
    @GetMapping("/api/ministry-report")
    public ResponseEntity<?> report(@RequestParam(required = false) Integer year, @RequestParam(required = false) Integer month, HttpServletRequest req) {
        Object g = gate(req);
        if (g instanceof ResponseEntity<?> r) return r;
        LocalDate today = AppClock.today();
        YearMonth ym;
        try { ym = month(year, month, today); } catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
        Access a = accessFor(req, (String) g);
        if (!a.any()) return ResponseEntity.status(403).body(Map.of("error", "None of the report's sections is open to this account."));
        return ResponseEntity.ok(reports.monthly((String) g, ym.getYear(), ym.getMonthValue(), a, today));
    }

    /** CSV of the same figures (one row per metric; comparisons as columns). */
    @ResponseBody
    @GetMapping("/api/ministry-report/csv")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> csv(@RequestParam(required = false) Integer year, @RequestParam(required = false) Integer month, HttpServletRequest req) {
        Object g = gate(req);
        if (g instanceof ResponseEntity<?> r) return r;
        LocalDate today = AppClock.today();
        YearMonth ym;
        try { ym = month(year, month, today); } catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
        Access a = accessFor(req, (String) g);
        if (!a.any()) return ResponseEntity.status(403).body(Map.of("error", "None of the report's sections is open to this account."));
        Map<String, Object> rep = reports.monthly((String) g, ym.getYear(), ym.getMonthValue(), a, today);
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("Ministry Report,").append(csv((String) rep.get("label"))).append("\n");
        sb.append("Section,Metric,").append(csv((String) rep.get("label"))).append(',').append(csv((String) rep.get("previousLabel"))).append(',').append(csv((String) rep.get("lastYearLabel"))).append("\n");
        Map<String, Object> cur = (Map<String, Object>) rep.get("current"), prev = (Map<String, Object>) rep.get("previous"), ly = (Map<String, Object>) rep.get("lastYear");
        for (String section : List.of("attendance", "visitors", "followUps", "giving", "groups", "volunteers")) {
            Map<String, Object> c = (Map<String, Object>) cur.get(section);
            if (c == null) continue;
            Map<String, Object> p = (Map<String, Object>) prev.get(section), l = (Map<String, Object>) ly.get(section);
            for (Map.Entry<String, Object> e : c.entrySet()) {
                if (e.getValue() instanceof List) continue;   // funds breakdown below
                sb.append(csv(section)).append(',').append(csv(e.getKey())).append(',').append(csv(String.valueOf(e.getValue()))).append(',')
                  .append(csv(p == null ? "" : String.valueOf(p.get(e.getKey())))).append(',').append(csv(l == null ? "" : String.valueOf(l.get(e.getKey())))).append("\n");
            }
            if ("giving".equals(section) && c.get("funds") instanceof List<?> funds)
                for (Object fo : funds) { Map<String, Object> f = (Map<String, Object>) fo;
                    sb.append("giving by fund,").append(csv(String.valueOf(f.get("fund")))).append(',').append(csv(String.valueOf(f.get("total")))).append(",,\n"); }
        }
        if (rep.get("engagement") instanceof Map<?, ?> eng) {
            Map<String, Object> counts = (Map<String, Object>) eng.get("counts"), pc = (Map<String, Object>) eng.get("previous");
            for (Map.Entry<String, Object> e : counts.entrySet())
                sb.append("engagement,").append(csv(e.getKey())).append(',').append(e.getValue()).append(',').append(pc == null ? "" : pc.get(e.getKey())).append(",\n");
        }
        String name = "ministry_report_" + ym + ".csv";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** CSV cell: quoted when needed; a leading = + - @ gets a leading apostrophe so spreadsheets never run it. */
    static String csv(String s) {
        if (s == null) return "";
        String v = s;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("'")) v = "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }
}
