package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.AccessAudit;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.TemporaryAccess;
import com.churchgeniuspro.repository.ChurchLogoRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.TemporaryAccessService;
import com.churchgeniuspro.service.TemporaryAccessService.CheckResult;
import com.churchgeniuspro.service.TemporaryAccessService.CreateResult;
import com.churchgeniuspro.service.TemporaryAccessService.Validation;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST endpoints for the Temporary Access feature.
 *
 * <p>Admin endpoints (gated to SuperAdmin/Admin) create, list, extend, revoke and
 * audit passes. The public {@code POST /api/temp-access/login} validates a scanned
 * barcode + 6-digit code and establishes a time-boxed session; {@code /session}
 * reports remaining time; {@code /logout} ends it and stamps the audit row.
 */
@RestController
@RequestMapping("/api/temp-access")
public class TemporaryAccessController {

    private final TemporaryAccessService    svc;
    private final EmailService              emailService;
    private final ChurchRegistrationRepository churchRepo;
    private final ChurchLogoRepository      logoRepo;

    public TemporaryAccessController(TemporaryAccessService svc,
                                     EmailService emailService,
                                     ChurchRegistrationRepository churchRepo,
                                     ChurchLogoRepository logoRepo) {
        this.svc          = svc;
        this.emailService = emailService;
        this.churchRepo   = churchRepo;
        this.logoRepo     = logoRepo;
    }

    // ══════════════════════════ Admin ══════════════════════════

    @GetMapping("/catalog")
    public ResponseEntity<?> catalog(HttpServletRequest request) {
        if (RoleGuard.requireAdminOrChurch(request) != null) return forbidden();
        return ResponseEntity.ok(svc.catalog());
    }

    /** Members + general volunteers (deduplicated) for the holder dropdown. */
    @GetMapping("/people")
    public ResponseEntity<?> people(HttpServletRequest request) {
        if (RoleGuard.requireAdminOrChurch(request) != null) return forbidden();
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(svc.peopleDirectory(clientId));
    }

    /** Church profile bits used to render the live badge preview. */
    @GetMapping("/church-info")
    public ResponseEntity<?> churchInfo(HttpServletRequest request) {
        if (RoleGuard.requireAdminOrChurch(request) != null) return forbidden();
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        ChurchRegistration ch = churchRepo.findByClientIdAndDeleteFlagFalse(clientId).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("churchName",    churchName(clientId));
        m.put("churchPhone",   ch != null ? ch.getPhone() : null);
        m.put("churchEmail",   ch != null ? ch.getEmail() : null);
        m.put("churchWebsite", churchWebsite(ch));
        m.put("hasLogo",       logoRepo.findByClientId(clientId)
                .map(l -> l.getLogoData() != null && l.getLogoData().length > 0).orElse(false));
        return ResponseEntity.ok(m);
    }

    @SuppressWarnings("unchecked")
    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        if (RoleGuard.requireAdminOrChurch(request) != null) return forbidden();
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();

        String holderName  = str(body.get("holderName"));
        String email       = str(body.get("email"));
        String phone       = str(body.get("phone"));
        String role        = str(body.get("role"));
        String holderPhoto = str(body.get("holderPhoto"));
        LocalDateTime start = parseDt(body.get("startDateTime"));
        LocalDateTime end   = parseDt(body.get("endDateTime"));
        List<String> pages  = new ArrayList<>();
        Object pj = body.get("pages");
        if (pj instanceof List<?> l) for (Object o : l) if (o != null) pages.add(String.valueOf(o));
        List<String> badgeFields = new ArrayList<>();
        Object bf = body.get("badgeFields");
        if (bf instanceof List<?> bl) for (Object o : bl) if (o != null) badgeFields.add(String.valueOf(o));

        if (holderName == null)
            return ResponseEntity.badRequest().body(Map.of("error", "Holder name is required."));
        if (start == null || end == null || !end.isAfter(start))
            return ResponseEntity.badRequest().body(Map.of("error", "Valid startDateTime and endDateTime (end after start) are required."));
        if (pages.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "Select at least one permitted page."));

        String createdBy = String.valueOf(getSessionAttr(request, "username"));
        CreateResult r = svc.create(clientId, createdBy, holderName, email, phone, role, holderPhoto, badgeFields, start, end, pages);

        if (email != null && !email.isBlank()) {
            try { emailService.sendGenericEmail(email.trim(), "Your Temporary Access Code",
                    buildCodeEmail(r.access(), r.plainCode(), churchName(clientId))); }
            catch (Exception ignore) { /* email failures never block creation */ }
        }

        Map<String, Object> out = toMap(r.access());
        out.put("accessCode", r.plainCode());     // returned ONCE so the admin can print the badge
        out.put("churchName", churchName(clientId));
        return ResponseEntity.ok(out);
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        if (RoleGuard.requireAdminOrChurch(request) != null) return forbidden();
        String clientId = RoleGuard.clientId(request);
        List<Map<String, Object>> out = new ArrayList<>();
        for (TemporaryAccess a : svc.list(clientId)) out.add(toMap(a));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{id}/extend")
    public ResponseEntity<?> extend(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                    HttpServletRequest request) {
        if (RoleGuard.requireAdminOrChurch(request) != null) return forbidden();
        String clientId = RoleGuard.clientId(request);
        LocalDateTime newEnd = parseDt(body.get("endDateTime"));
        if (newEnd == null && body.get("addMinutes") != null) {
            Optional<TemporaryAccess> cur = svc.find(id, clientId);
            if (cur.isPresent()) {
                LocalDateTime base = cur.get().getEndDateTime();
                if (base == null || base.isBefore(LocalDateTime.now())) base = LocalDateTime.now();
                newEnd = base.plusMinutes(asLong(body.get("addMinutes")));
            }
        }
        if (newEnd == null) return ResponseEntity.badRequest().body(Map.of("error", "endDateTime or addMinutes required."));
        return svc.extend(id, clientId, newEnd)
                .<ResponseEntity<?>>map(a -> ResponseEntity.ok(toMap(a)))
                .orElse(ResponseEntity.status(404).body(Map.of("error", "Not found.")));
    }

    @PostMapping("/{id}/revoke")
    public ResponseEntity<?> revoke(@PathVariable Long id, HttpServletRequest request) {
        if (RoleGuard.requireAdminOrChurch(request) != null) return forbidden();
        String clientId = RoleGuard.clientId(request);
        return svc.revoke(id, clientId)
                .<ResponseEntity<?>>map(a -> ResponseEntity.ok(toMap(a)))
                .orElse(ResponseEntity.status(404).body(Map.of("error", "Not found.")));
    }

    /** Delete a pass (removes it from the Active &amp; Recent list). */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, HttpServletRequest request) {
        if (RoleGuard.requireAdminOrChurch(request) != null) return forbidden();
        String clientId = RoleGuard.clientId(request);
        return svc.softDelete(id, clientId)
                ? ResponseEntity.ok(Map.of("status", "success"))
                : ResponseEntity.status(404).body(Map.of("error", "Not found."));
    }

    @GetMapping("/{id}/audit")
    public ResponseEntity<?> audit(@PathVariable Long id, HttpServletRequest request) {
        if (RoleGuard.requireAdminOrChurch(request) != null) return forbidden();
        String clientId = RoleGuard.clientId(request);
        if (svc.find(id, clientId).isEmpty()) return ResponseEntity.status(404).build();
        List<Map<String, Object>> out = new ArrayList<>();
        for (AccessAudit a : svc.auditFor(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("loginTime",  a.getLoginTime()  == null ? null : a.getLoginTime().toString());
            m.put("logoutTime", a.getLogoutTime() == null ? null : a.getLogoutTime().toString());
            m.put("deviceInfo", a.getDeviceInfo());
            m.put("ipAddress",  a.getIpAddress());
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{id}/badge")
    public ResponseEntity<?> badge(@PathVariable Long id, HttpServletRequest request) {
        if (RoleGuard.requireAdminOrChurch(request) != null) return forbidden();
        String clientId = RoleGuard.clientId(request);
        return svc.find(id, clientId).<ResponseEntity<?>>map(a -> {
            Map<String, Object> m = toMap(a);
            m.put("holderPhoto", a.getHolderPhoto());      // included only on the single-badge fetch
            ChurchRegistration ch = churchRepo.findByClientIdAndDeleteFlagFalse(clientId).orElse(null);
            m.put("churchName",    churchName(clientId));
            m.put("churchPhone",   ch != null ? ch.getPhone()   : null);
            m.put("churchEmail",   ch != null ? ch.getEmail()   : null);
            m.put("churchWebsite", churchWebsite(ch));
            m.put("hasLogo",       logoRepo.findByClientId(clientId)
                    .map(l -> l.getLogoData() != null && l.getLogoData().length > 0).orElse(false));
            return ResponseEntity.ok(m);
        }).orElse(ResponseEntity.status(404).build());
    }

    /** Church logo bytes for the badge (authenticated admin); 404 when none. */
    @GetMapping("/logo")
    public ResponseEntity<byte[]> logo(HttpServletRequest request) {
        if (RoleGuard.requireAdminOrChurch(request) != null) return ResponseEntity.status(403).build();
        String clientId = RoleGuard.clientId(request);
        if (clientId == null) return ResponseEntity.status(401).build();
        return logoRepo.findByClientId(clientId)
                .filter(l -> l.getLogoData() != null && l.getLogoData().length > 0)
                .map(l -> ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(l.getLogoData()))
                .orElse(ResponseEntity.notFound().build());
    }

    // ══════════════════════════ Temporary login ══════════════════════════

    /** Public: validate the scanned barcode + code and start a time-boxed session. */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String barcode = str(body.get("barcode"));
        String code    = str(body.get("code"));
        Validation v = svc.validate(barcode, code);
        if (v.result() != CheckResult.OK) {
            // Deliberately generic for bad barcode/code; specific for window problems.
            String msg = switch (v.result()) {
                case NOT_STARTED -> "This access has not started yet.";
                case EXPIRED     -> "This access has expired.";
                case REVOKED     -> "This access has been revoked.";
                case SUBSCRIPTION_ENDED -> "This church's subscription has ended, so temporary access is not available. "
                                         + "Please contact the church administrator.";
                default          -> "Invalid badge or code.";
            };
            return ResponseEntity.status(401).body(Map.of("status", "error", "message", msg));
        }
        TemporaryAccess a = v.access();

        // Establish the session, mirroring a normal staff session enough that
        // AuthFilter + RoleGuard treat it as a low-privilege ("User") staff session,
        // while the granted-pages privileges JSON gates which pages are reachable.
        // Rotate: a pre-authentication session id must not survive login, and this
        // identity must never be layered onto a session that already holds another.
        HttpSession existing = request.getSession(false);
        if (existing != null) existing.invalidate();
        HttpSession session = request.getSession(true);
        AccessAudit audit = svc.recordLogin(a, request.getHeader("User-Agent"), clientIp(request));
        session.setAttribute("clientId",     a.getClientId());
        session.setAttribute("appClientId",  a.getClientId());
        session.setAttribute("username",     "temp:" + a.getId());
        session.setAttribute("role",         "User");
        session.setAttribute("church",       Boolean.FALSE);
        session.setAttribute("churchName",   churchName(a.getClientId()));   // real church name in the sidebar brand
        session.setAttribute("privileges",   svc.buildPrivilegesJson(a.getPermissions()));
        session.setAttribute("tempAccessId", a.getId());
        session.setAttribute("tempAuditId",  audit.getId());
        session.setAttribute("tempEndMillis",
                a.getEndDateTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "success");
        out.put("holderName", a.getHolderName());
        out.put("startPage", svc.firstRoute(a.getPermissions()));
        out.put("remainingSeconds", remainingSeconds(a.getEndDateTime()));
        return ResponseEntity.ok(out);
    }

    /** Remaining time + holder info for the in-page countdown banner. */
    @GetMapping("/session")
    public ResponseEntity<?> sessionInfo(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object idAttr = session == null ? null : session.getAttribute("tempAccessId");
        if (!(idAttr instanceof Long id)) return ResponseEntity.ok(Map.of("temporary", false));
        String clientId = String.valueOf(session.getAttribute("clientId"));
        Optional<TemporaryAccess> opt = svc.find(id, clientId);
        if (opt.isEmpty() || !svc.isLiveNow(opt.get())) {
            return ResponseEntity.ok(Map.of("temporary", true, "active", false, "remainingSeconds", 0));
        }
        TemporaryAccess a = opt.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("temporary", true);
        out.put("active", true);
        out.put("holderName", a.getHolderName());
        out.put("remainingSeconds", remainingSeconds(a.getEndDateTime()));
        out.put("warnSeconds", 15 * 60);          // warn the user at 15 minutes left
        out.put("startPage", svc.firstRoute(a.getPermissions()));
        out.put("permittedRoutes", svc.permittedRoutes(a.getPermissions()));   // for nav filtering
        out.put("permittedPages", svc.permittedPages(a.getPermissions()));     // for building the temp nav
        return ResponseEntity.ok(out);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            Object auditId = session.getAttribute("tempAuditId");
            if (auditId instanceof Long aid) svc.recordLogout(aid);
            session.invalidate();
        }
        return ResponseEntity.ok(Map.of("status", "success"));
    }

    // ══════════════════════════ helpers ══════════════════════════

    private Map<String, Object> toMap(TemporaryAccess a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("holderName", a.getHolderName());
        m.put("email", a.getEmail());
        m.put("phone", a.getPhone());
        m.put("role", a.getRole());
        m.put("badgeFields", a.getBadgeFields() == null || a.getBadgeFields().isBlank()
                ? List.of() : List.of(a.getBadgeFields().split(",")));
        m.put("startDateTime", a.getStartDateTime() == null ? null : a.getStartDateTime().toString());
        m.put("endDateTime",   a.getEndDateTime()   == null ? null : a.getEndDateTime().toString());
        m.put("barcodeValue", a.getBarcodeValue());
        m.put("status", svc.effectiveStatus(a));
        m.put("permissions", a.getPermissions() == null || a.getPermissions().isBlank()
                ? List.of() : List.of(a.getPermissions().split(",")));
        m.put("createdBy", a.getCreatedBy());
        m.put("createdDate", a.getCreatedDate() == null ? null : a.getCreatedDate().toString());
        m.put("remainingSeconds", remainingSeconds(a.getEndDateTime()));
        return m;
    }

    private long remainingSeconds(LocalDateTime end) {
        if (end == null) return 0;
        long s = Duration.between(LocalDateTime.now(), end).getSeconds();
        return Math.max(0, s);
    }

    private String churchName(String clientId) {
        if (clientId == null || clientId.isBlank()) return "ChurchGenius Pro";
        return churchRepo.findByClientIdAndDeleteFlagFalse(clientId)
                .map(ChurchRegistration::getChurchName)
                .filter(n -> n != null && !n.isBlank())
                .orElse("ChurchGenius Pro");
    }

    /**
     * Church website — included on the badge "when available". The church profile
     * has no dedicated website column today, so this is best-effort: if a note ever
     * carries a URL we surface it, otherwise null (the badge omits the line).
     */
    private String churchWebsite(ChurchRegistration ch) {
        if (ch == null || ch.getNote() == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(https?://\\S+|www\\.\\S+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(ch.getNote());
        return m.find() ? m.group(1) : null;
    }

    private String buildCodeEmail(TemporaryAccess a, String code, String church) {
        String esc = church == null ? "" : church.replace("&", "&amp;").replace("<", "&lt;");
        return "<div style=\"font-family:Arial,sans-serif;max-width:480px;margin:auto;\">"
             + "<h2 style=\"color:#673147;\">Temporary Access — " + esc + "</h2>"
             + "<p>You have been granted temporary access" + (a.getHolderName() != null ? (" (" + a.getHolderName() + ")") : "") + ".</p>"
             + "<p style=\"font-size:14px;color:#555;\">Scan your printed badge at the login screen, then enter this code:</p>"
             + "<div style=\"font-size:32px;font-weight:bold;letter-spacing:6px;background:#f5eef1;border:1px solid #e0d3da;"
             + "border-radius:8px;padding:14px;text-align:center;color:#673147;\">" + code + "</div>"
             + "<p style=\"font-size:13px;color:#777;margin-top:12px;\">Valid from <b>" + a.getStartDateTime()
             + "</b> to <b>" + a.getEndDateTime() + "</b>.</p>"
             + "<p style=\"font-size:12px;color:#999;\">If you did not expect this, please ignore this email.</p>"
             + "</div>";
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("status", "error", "message", "Access denied."));
    }

    private Object getSessionAttr(HttpServletRequest request, String key) {
        HttpSession s = request.getSession(false);
        return s == null ? null : s.getAttribute(key);
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static long asLong(Object o) {
        try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }

    private static LocalDateTime parseDt(Object o) {
        String s = str(o);
        if (s == null) return null;
        try { return LocalDateTime.parse(s); }            // ISO_LOCAL_DATE_TIME e.g. 2026-06-10T14:30
        catch (Exception e) {
            try { return LocalDateTime.parse(s.length() == 16 ? s + ":00" : s); }
            catch (Exception e2) { return null; }
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
