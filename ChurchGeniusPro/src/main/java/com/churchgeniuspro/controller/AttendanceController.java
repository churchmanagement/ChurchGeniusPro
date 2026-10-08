package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.AttendanceRecord;
import com.churchgeniuspro.hibernate.AttendanceServiceType;
import com.churchgeniuspro.service.AttendanceService;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** REST endpoints for the Attendance module. Gated to staff (Admin/User/SuperAdmin). */
@RestController
@RequestMapping("/api/attendance")
public class AttendanceController {

    private final AttendanceService svc;
    private final com.churchgeniuspro.service.AttendanceVolunteerNotifyService volunteerNotify;

    public AttendanceController(AttendanceService svc,
                                com.churchgeniuspro.service.AttendanceVolunteerNotifyService volunteerNotify) {
        this.svc = svc;
        this.volunteerNotify = volunteerNotify;
    }

    private String deny(HttpServletRequest req) { return RoleGuard.requireAdminOrUser(req); }
    /** True if the current user LACKS the given action permission (opt-in denial). */
    private boolean lacks(HttpServletRequest req, String permKey) {
        return RoleGuard.requirePermission(req, permKey) != null;
    }
    private ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("status", "error", "message", "Access denied."));
    }
    private String cid(HttpServletRequest req) { return SessionUtil.getAppClientId(req); }
    private String user(HttpServletRequest req) {
        var s = req.getSession(false);
        return s == null ? null : String.valueOf(s.getAttribute("username"));
    }

    // ── Volunteers: Email / SMS ────────────────────────────────────────────────
    // The one Attendance action behind the "Email or SMS Volunteers" checkbox
    // (general.attendance.emailsms). It is a NEW messaging action, not an existing
    // data API, so the checkbox is enforced here as well as on the button — the
    // same way the other Attendance actions enforce .edit / .delete.

    @GetMapping("/volunteers")
    public ResponseEntity<?> volunteers(HttpServletRequest req) {
        if (deny(req) != null) return forbidden();
        if (lacks(req, "general.attendance.emailsms")) return forbidden();
        return ResponseEntity.ok(volunteerNotify.listVolunteers(cid(req)));
    }

    @PostMapping("/volunteers/notify")
    public ResponseEntity<?> notifyVolunteers(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        if (deny(req) != null) return forbidden();
        if (lacks(req, "general.attendance.emailsms")) return forbidden();
        List<Integer> ids = new ArrayList<>();
        Object raw = b.get("memberIds");
        if (raw instanceof List<?> l) for (Object o : l) { if (o instanceof Number n) ids.add(n.intValue()); }
        boolean viaEmail = Boolean.TRUE.equals(b.get("email"));
        boolean viaSms   = Boolean.TRUE.equals(b.get("sms"));
        try {
            return ResponseEntity.ok(volunteerNotify.notify(cid(req), ids, viaEmail, viaSms,
                    str(b.get("subject")), str(b.get("body"))));
        } catch (IllegalArgumentException bad) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", bad.getMessage()));
        }
    }

    // ── Settings / reference ──────────────────────────────────────────────────
    @GetMapping("/service-types")
    public ResponseEntity<?> serviceTypes(HttpServletRequest req) {
        if (deny(req) != null) return forbidden();
        List<Map<String, Object>> out = new ArrayList<>();
        for (AttendanceServiceType t : svc.serviceTypes(cid(req))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId()); m.put("name", t.getName()); m.put("category", t.getCategory());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    @PostMapping("/service-types")
    public ResponseEntity<?> addType(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (deny(req) != null) return forbidden();
        String name = str(body.get("name"));
        if (name == null) return ResponseEntity.badRequest().body(Map.of("error", "name required"));
        AttendanceServiceType t = svc.addServiceType(cid(req), name, str(body.get("category")));
        return ResponseEntity.ok(Map.of("id", t.getId(), "name", t.getName(), "category", t.getCategory()));
    }

    @GetMapping("/statuses")
    public ResponseEntity<?> statuses(HttpServletRequest req) {
        if (deny(req) != null) return forbidden();
        return ResponseEntity.ok(AttendanceService.STATUSES);
    }

    // ── Dashboard + trends ──────────────────────────────────────────────────────
    @GetMapping("/dashboard")
    public ResponseEntity<?> dashboard(HttpServletRequest req) {
        if (deny(req) != null) return forbidden();
        return ResponseEntity.ok(svc.dashboard(cid(req)));
    }

    @GetMapping("/trends/{kind}")
    public ResponseEntity<?> trends(@PathVariable String kind, HttpServletRequest req) {
        if (deny(req) != null) return forbidden();
        String c = cid(req);
        switch (kind) {
            case "weekly":      return ResponseEntity.ok(svc.weeklyTrend(c));
            case "monthly":     return ResponseEntity.ok(svc.monthlyTrend(c));
            case "yearly":      return ResponseEntity.ok(svc.yearlyTrend(c));
            case "by-ministry": return ResponseEntity.ok(svc.byField(c, true));
            case "by-service":  return ResponseEntity.ok(svc.byField(c, false));
            default: return ResponseEntity.badRequest().body(Map.of("error", "unknown trend"));
        }
    }

    // ── Reports ─────────────────────────────────────────────────────────────────
    @GetMapping("/reports/members")
    public ResponseEntity<?> reportMembers(@RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to, HttpServletRequest req) {
        if (deny(req) != null) return forbidden();
        return ResponseEntity.ok(svc.memberReport(cid(req), parseDate(from), parseDate(to)));
    }

    @GetMapping("/reports/families")
    public ResponseEntity<?> reportFamilies(@RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to, HttpServletRequest req) {
        if (deny(req) != null) return forbidden();
        return ResponseEntity.ok(svc.familyReport(cid(req), parseDate(from), parseDate(to)));
    }

    @GetMapping("/reports/ministry")
    public ResponseEntity<?> reportMinistry(@RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to, HttpServletRequest req) {
        if (deny(req) != null) return forbidden();
        return ResponseEntity.ok(svc.byField(cid(req), true, parseDate(from), parseDate(to)));
    }

    @GetMapping("/reports/events")
    public ResponseEntity<?> reportEvents(@RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to, HttpServletRequest req) {
        if (deny(req) != null) return forbidden();
        return ResponseEntity.ok(svc.byField(cid(req), false, parseDate(from), parseDate(to)));
    }

    @GetMapping("/reports/visitors")
    public ResponseEntity<?> reportVisitors(HttpServletRequest req) {
        if (deny(req) != null) return forbidden();
        return ResponseEntity.ok(svc.visitorReport(cid(req)));
    }

    // ── Check-in helpers ─────────────────────────────────────────────────────────
    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam(required = false) String q, HttpServletRequest req) {
        if (deny(req) != null) return forbidden();
        return ResponseEntity.ok(svc.searchPeople(cid(req), q));
    }

    @GetMapping("/scan")
    public ResponseEntity<?> scan(@RequestParam String code, HttpServletRequest req) {
        if (deny(req) != null) return forbidden();
        return svc.resolveScan(cid(req), code)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.status(404).body(Map.of("error", "No member matches that code.")));
    }

    @GetMapping("/member-code/{memberId}")
    public ResponseEntity<?> memberCode(@PathVariable Integer memberId, HttpServletRequest req) {
        if (deny(req) != null) return forbidden();
        try {
            return ResponseEntity.ok(Map.of("memberId", memberId, "code", svc.memberCode(cid(req), memberId)));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/family/{familyId}/members")
    public ResponseEntity<?> familyMembers(@PathVariable Integer familyId, HttpServletRequest req) {
        if (deny(req) != null) return forbidden();
        try {
            return ResponseEntity.ok(svc.familyMembers(cid(req), familyId));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
        }
    }

    // ── Check-in actions ─────────────────────────────────────────────────────────
    @PostMapping("/check-in")
    public ResponseEntity<?> checkIn(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        if (deny(req) != null) return forbidden();
        if (lacks(req, "general.attendance.edit")) return forbidden();
        Integer memberId = intVal(b.get("memberId"));
        if (memberId == null) return ResponseEntity.badRequest().body(Map.of("error", "memberId required"));
        String service = str(b.get("serviceType"));
        if (service == null) return ResponseEntity.badRequest().body(Map.of("error", "serviceType required"));
        try {
            AttendanceRecord a = svc.checkInMember(cid(req), user(req), memberId, str(b.get("name")), service,
                    str(b.get("ministry")), str(b.get("campus")), str(b.get("status")), str(b.get("method")));
            return ResponseEntity.ok(svc.recordMap(a));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/family-check-in")
    public ResponseEntity<?> familyCheckIn(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        if (deny(req) != null) return forbidden();
        if (lacks(req, "general.attendance.edit")) return forbidden();
        Integer familyId = intVal(b.get("familyId"));
        String service = str(b.get("serviceType"));
        if (familyId == null || service == null) return ResponseEntity.badRequest().body(Map.of("error", "familyId and serviceType required"));
        List<Integer> ids = new ArrayList<>();
        Object mi = b.get("memberIds");
        if (mi instanceof List<?> l) for (Object o : l) { Integer v = intVal(o); if (v != null) ids.add(v); }
        try {
            List<Map<String, Object>> out = svc.familyCheckIn(cid(req), user(req), familyId, service,
                    str(b.get("ministry")), str(b.get("campus")), str(b.get("status")), ids)
                    .stream().map(svc::recordMap).collect(Collectors.toList());
            return ResponseEntity.ok(Map.of("checkedIn", out.size(), "records", out));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/visitor-check-in")
    public ResponseEntity<?> visitorCheckIn(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        if (deny(req) != null) return forbidden();
        if (lacks(req, "general.attendance.edit")) return forbidden();
        String service = str(b.get("serviceType"));
        if (str(b.get("name")) == null || service == null)
            return ResponseEntity.badRequest().body(Map.of("error", "name and serviceType required"));
        return ResponseEntity.ok(svc.checkInVisitor(cid(req), user(req), str(b.get("name")), str(b.get("phone")),
                str(b.get("email")), str(b.get("address")), str(b.get("invitedBy")), service,
                str(b.get("ministry")), str(b.get("campus")), str(b.get("status"))));
    }

    @PostMapping("/{id}/check-out")
    public ResponseEntity<?> checkOut(@PathVariable Long id, HttpServletRequest req) {
        if (deny(req) != null) return forbidden();
        if (lacks(req, "general.attendance.edit")) return forbidden();
        return svc.checkOut(id, cid(req)) ? ResponseEntity.ok(Map.of("status", "success"))
                : ResponseEntity.status(404).body(Map.of("error", "Not found."));
    }

    // ── Records grid ──────────────────────────────────────────────────────────────
    @GetMapping("/records")
    public ResponseEntity<?> records(@RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to,
                                     @RequestParam(required = false) Integer memberId,
                                     @RequestParam(required = false) Integer familyId,
                                     @RequestParam(required = false) String service,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String ministry,
                                     HttpServletRequest req) {
        if (deny(req) != null) return forbidden();
        List<Map<String, Object>> out = svc.queryRecords(cid(req), parseDate(from), parseDate(to),
                memberId, familyId, service, status, ministry).stream().map(svc::recordMap).collect(Collectors.toList());
        return ResponseEntity.ok(out);
    }

    @PutMapping("/records/{id}")
    public ResponseEntity<?> updateRecord(@PathVariable Long id, @RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (deny(req) != null) return forbidden();
        if (lacks(req, "general.attendance.edit")) return forbidden();
        return svc.updateRecord(id, cid(req), body)
                .<ResponseEntity<?>>map(a -> ResponseEntity.ok(svc.recordMap(a)))
                .orElse(ResponseEntity.status(404).body(Map.of("error", "Not found.")));
    }

    @DeleteMapping("/records/{id}")
    public ResponseEntity<?> deleteRecord(@PathVariable Long id, HttpServletRequest req) {
        if (deny(req) != null) return forbidden();
        if (lacks(req, "general.attendance.delete")) return forbidden();
        return svc.deleteRecord(id, cid(req)) ? ResponseEntity.ok(Map.of("status", "success"))
                : ResponseEntity.status(404).body(Map.of("error", "Not found."));
    }

    @GetMapping("/records/export")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "csv") String format,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) Integer memberId,
                                         @RequestParam(required = false) Integer familyId,
                                         @RequestParam(required = false) String service,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String ministry,
                                         HttpServletRequest req) {
        if (deny(req) != null) return ResponseEntity.status(403).build();
        List<AttendanceRecord> recs = svc.queryRecords(cid(req), parseDate(from), parseDate(to),
                memberId, familyId, service, status, ministry);
        String fname = "attendance-" + LocalDate.now();
        switch (format) {
            case "xlsx":
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                        .header("Content-Disposition", "attachment; filename=" + fname + ".xlsx")
                        .body(svc.exportXlsx(recs));
            case "pdf":
                return ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_PDF)
                        .header("Content-Disposition", "attachment; filename=" + fname + ".pdf")
                        .body(svc.exportPdf(recs, "Attendance Report"));
            default:
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("text/csv"))
                        .header("Content-Disposition", "attachment; filename=" + fname + ".csv")
                        .body(svc.exportCsv(recs));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────
    private static String str(Object o) { if (o == null) return null; String s = String.valueOf(o).trim(); return s.isEmpty() ? null : s; }
    private static Integer intVal(Object o) { try { return o == null ? null : Integer.valueOf(String.valueOf(o).trim()); } catch (Exception e) { return null; } }
    private static LocalDate parseDate(String s) { try { return (s == null || s.isBlank()) ? null : LocalDate.parse(s); } catch (Exception e) { return null; } }
}
