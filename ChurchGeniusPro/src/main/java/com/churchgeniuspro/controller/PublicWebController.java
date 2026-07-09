package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.PublicPageVisit;
import com.churchgeniuspro.repository.PublicPageVisitRepository;
import com.churchgeniuspro.service.EmailService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Public (no-login) marketing website: the {@code /web/*} pages, the public
 * contact form, and the Service Admin visitor-statistics endpoint.
 *
 * <h3>Public page routes (anonymous)</h3>
 * <ul>
 *   <li>{@code GET /web/home | /web/features | /web/pricing | /web/help |
 *       /web/support | /web/info} → records a {@link PublicPageVisit}, then
 *       forwards to the static page under {@code static/web/}.</li>
 *   <li>{@code POST /api/web/contact} → emails the request to
 *       info@churchgeniuspro.com (whitelisted in {@code AuthFilter}).</li>
 * </ul>
 *
 * <h3>Admin route</h3>
 * <ul>
 *   <li>{@code GET /api/serviceadmin/public-page-stats} → per-page visitor
 *       totals for the Service Admin dashboard (session attribute
 *       {@code serviceAdminId} required — same guard as TestDataController).</li>
 * </ul>
 */
@Controller
public class PublicWebController {

    private static final Logger LOGGER = LoggerFactory.getLogger(PublicWebController.class);

    /** The only page keys served / tracked (anything else redirects home). */
    private static final Set<String> PAGES =
            Set.of("home", "features", "pricing", "compare", "help", "support", "info");

    /** Display order + labels for the stats endpoint. */
    private static final String[] PAGE_ORDER = {"home", "features", "pricing", "compare", "help", "support", "info"};

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** Where contact-form requests are delivered. */
    private static final String CONTACT_INBOX = "info@churchgeniuspro.com";

    private final PublicPageVisitRepository visitRepo;
    private final EmailService              emailService;

    public PublicWebController(PublicPageVisitRepository visitRepo, EmailService emailService) {
        this.visitRepo    = visitRepo;
        this.emailService = emailService;
    }

    // ── Public pages ──────────────────────────────────────────────────────

    /**
     * The {@code [a-z]+} constraint is essential: without it {@code {page}}
     * would also match the forward target {@code home.html} itself, and the
     * controller would forward to itself in an infinite loop (StackOverflow).
     * With the constraint, dotted paths like {@code /web/home.html} and
     * {@code /web/site.css} bypass this mapping and are served directly by
     * the static resource handler.
     */
    @GetMapping("/web/{page:[a-zA-Z]+}")
    public String page(@PathVariable String page, HttpServletRequest request) {
        String key = page.toLowerCase();
        if (!PAGES.contains(key)) {
            return "redirect:/web/home"; // unknown /web/* page → home
        }
        trackVisit(key, request);
        return "forward:/web/" + key + ".html";
    }

    /** {@code /web} and {@code /web/} land on the home page. */
    @GetMapping({"/web", "/web/"})
    public String webRoot(HttpServletRequest request) {
        trackVisit("home", request);
        return "forward:/web/home.html";
    }

    /** Never let tracking break a public page load. */
    private void trackVisit(String page, HttpServletRequest request) {
        try {
            PublicPageVisit v = new PublicPageVisit();
            v.setPage(page);
            String ua = request.getHeader("User-Agent");
            v.setUserAgent(ua != null && ua.length() > 1000 ? ua.substring(0, 1000) : ua);
            v.setIpAddress(clientIp(request));
            String ref = request.getHeader("Referer");
            v.setReferrer(ref != null && ref.length() > 500 ? ref.substring(0, 500) : ref);
            visitRepo.save(v);
        } catch (Exception e) {
            LOGGER.warn("Public page visit tracking failed for {} — {}", page, e.getMessage());
        }
    }

    private String clientIp(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            String first = fwd.split(",")[0].trim();
            return first.length() > 64 ? first.substring(0, 64) : first;
        }
        return request.getRemoteAddr();
    }

    // ── Public contact form ───────────────────────────────────────────────

    /**
     * Body: {@code {"name": "...", "phone": "...", "email": "required", "request": "..."}}.
     * Sends the request to {@link #CONTACT_INBOX}. The hidden {@code website}
     * field is a spam honeypot — bots that fill it get a fake success.
     */
    @ResponseBody
    @PostMapping("/api/web/contact")
    public ResponseEntity<Map<String, Object>> contact(@RequestBody Map<String, Object> body) {
        String name    = str(body.get("name"), 200);
        String phone   = str(body.get("phone"), 50);
        String email   = str(body.get("email"), 255);
        String message = str(body.get("request"), 5000);
        String honey   = str(body.get("website"), 100);

        if (honey != null) { // honeypot filled → almost certainly a bot
            return ResponseEntity.ok(Map.of("status", "success"));
        }
        if (email == null || !EMAIL_PATTERN.matcher(email).matches()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "A valid email address is required."));
        }

        StringBuilder html = new StringBuilder();
        html.append("<h2 style='color:#673147;'>New Website Contact Request</h2>")
            .append("<table style='font-size:14px;color:#333;border-collapse:collapse;'>")
            .append(row("Name",    name  != null ? name  : "—"))
            .append(row("Phone",   phone != null ? phone : "—"))
            .append(row("Email",   email))
            .append("</table>")
            .append("<p style='font-size:14px;color:#333;white-space:pre-wrap;'><strong>Request:</strong><br/>")
            .append(esc(message != null ? message : "—"))
            .append("</p>")
            .append("<p style='font-size:12px;color:#999;'>Sent from the public website contact form (/web/support).</p>");

        try {
            emailService.sendGenericEmail(CONTACT_INBOX,
                    "Website Contact Request" + (name != null ? " — " + name : ""),
                    html.toString());
            LOGGER.info("Public contact form submitted by {} ({})", name, email);
            return ResponseEntity.ok(Map.of("status", "success",
                    "message", "Thank you! Your request has been sent — we'll get back to you soon."));
        } catch (Exception e) {
            LOGGER.error("Public contact form send failed — {}", e.getMessage());
            return ResponseEntity.status(500)
                    .body(Map.of("status", "error", "message", "Could not send your request. Please email "
                            + CONTACT_INBOX + " directly."));
        }
    }

    // ── Service Admin: visitor statistics ─────────────────────────────────

    @ResponseBody
    @GetMapping("/api/serviceadmin/public-page-stats")
    public ResponseEntity<?> publicPageStats(HttpServletRequest request) {
        ResponseEntity<?> deny = requireServiceAdmin(request);
        if (deny != null) return deny;

        Map<String, long[]> counts = new LinkedHashMap<>();   // page -> [total, last30]
        Map<String, Date>   lastVisit = new LinkedHashMap<>();
        for (Object[] r : visitRepo.totalsByPage()) {
            counts.computeIfAbsent((String) r[0], k -> new long[2])[0] = ((Number) r[1]).longValue();
            lastVisit.put((String) r[0], (Date) r[2]);
        }
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -30);
        for (Object[] r : visitRepo.countsByPageSince(cal.getTime())) {
            counts.computeIfAbsent((String) r[0], k -> new long[2])[1] = ((Number) r[1]).longValue();
        }

        List<Map<String, Object>> pages = new ArrayList<>();
        long grandTotal = 0;
        for (String key : PAGE_ORDER) {
            long[] c = counts.getOrDefault(key, new long[2]);
            grandTotal += c[0];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("page",       key.substring(0, 1).toUpperCase() + key.substring(1));
            m.put("visitors",   c[0]);
            m.put("last30Days", c[1]);
            m.put("lastVisit",  lastVisit.get(key));
            pages.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pages", pages);
        out.put("total", grandTotal);
        return ResponseEntity.ok(out);
    }

    /** Same service-admin session guard pattern as TestDataController. */
    private ResponseEntity<?> requireServiceAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("serviceAdminId") == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "status", "error", "message", "Service admin login required."));
        }
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String str(Object o, int max) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String row(String label, String value) {
        return "<tr><td style='padding:4px 12px 4px 0;font-weight:600;'>" + esc(label)
                + ":</td><td style='padding:4px 0;'>" + esc(value) + "</td></tr>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
