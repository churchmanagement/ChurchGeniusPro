package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.ChurchEvent;
import com.churchgeniuspro.repository.ChurchEventRepository;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Handles the public event-register page URLs and server-renders Open Graph /
 * link-preview meta tags so that shared links (SMS, WhatsApp, social) show the
 * event image, title, and description.
 *
 * <ul>
 *   <li>{@code GET /event-register/{token}} — serves event-register.html with OG tags injected
 *       for the event the random link token resolves to (the short EVT- code is a staff
 *       search key and no longer opens a page — security audit P6)</li>
 * </ul>
 */
@Controller
public class EventRegisterPageController {

    private final ChurchEventRepository eventRepo;

    /** Cached page template (loaded once from the classpath). */
    private volatile String pageTemplate;

    private final com.churchgeniuspro.service.EventPublicTokenService publicTokens;

    public EventRegisterPageController(ChurchEventRepository eventRepo, com.churchgeniuspro.service.EventPublicTokenService publicTokens) {
        this.publicTokens = publicTokens;
        this.eventRepo = eventRepo;
    }

    /** Authenticated admin event check-in management page. */
    @GetMapping("/event-checkin-admin")
    public String adminCheckinPage(HttpServletRequest request) {
        String deny = RoleGuard.requireAuth(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "general.eventcheckin");
        if (deny != null) return deny;
        return "forward:/event-checkin-admin.html";
    }

    @GetMapping("/event-checkin/{registrationCode}")
    public String handleEventCheckin(@PathVariable String registrationCode) {
        return "forward:/event-checkin.html";
    }

    @ResponseBody
    @GetMapping(value = "/event-register/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> handleEventRegister(@PathVariable String token,
                                                      HttpServletRequest request) {
        // The short Event ID / Code is a staff search key, not a public address. This
        // page used to resolve "EVT-…" across every church and redirect to the event's
        // real link, which made staff-chosen codes an enumerable index of every
        // church's registration pages (security audit P6). Only the random link
        // token opens a page now.

        String html = template();
        if (html == null) {
            // Template unavailable — fall back to the static file.
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create("/event-register.html")).build();
        }

        // Inject Open Graph tags for a valid event; otherwise strip the placeholder.
        String ogTags = "";
        try {
            ChurchEvent ev = publicTokens.resolve(token).orElse(null);
            if (ev != null) ogTags = buildOgTags(ev, token, request);
        } catch (Exception ignored) { /* unknown/invalid token — serve without OG */ }

        html = html.replace("<!--OG_META-->", ogTags);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    // ── OG helpers ────────────────────────────────────────────────────────

    private String buildOgTags(ChurchEvent ev, String token, HttpServletRequest request) {
        String base = baseUrl(request);
        String pageUrl = base + "/event-register/" + token;
        String title = ev.getEventName() != null ? ev.getEventName() : "Event Registration";
        String desc = descriptionFor(ev);
        boolean hasImage = ev.isImagePresent();   // database audit P7
        String imageUrl = base + "/api/event-register/" + token + "/image";

        StringBuilder sb = new StringBuilder();
        sb.append(meta("og:type", "website"));
        sb.append(meta("og:site_name", "ChurchGeniusPro"));
        sb.append(meta("og:title", title));
        sb.append(meta("og:description", desc));
        sb.append(meta("og:url", pageUrl));
        sb.append("<meta name=\"twitter:card\" content=\"")
          .append(hasImage ? "summary_large_image" : "summary").append("\" />\n");
        sb.append(metaName("twitter:title", title));
        sb.append(metaName("twitter:description", desc));
        if (hasImage) {
            sb.append(meta("og:image", imageUrl));
            sb.append(metaName("twitter:image", imageUrl));
        }
        return sb.toString();
    }

    /** Plain-text description from the (possibly rich-HTML) note, else a sensible fallback. */
    private String descriptionFor(ChurchEvent ev) {
        String note = ev.getNote();
        if (note != null && !note.isBlank()) {
            String text = note.replaceAll("<[^>]+>", " ")   // strip tags
                              .replace("&nbsp;", " ")
                              .replace("&amp;", "&")
                              .replace("&lt;", "<").replace("&gt;", ">")
                              .replaceAll("\\s+", " ").trim();
            if (!text.isEmpty()) return truncate(text, 200);
        }
        String name = ev.getEventName() != null ? ev.getEventName() : "our event";
        return truncate("Register for " + name + ".", 200);
    }

    private String meta(String property, String content) {
        return "<meta property=\"" + property + "\" content=\"" + esc(content) + "\" />\n";
    }

    private String metaName(String name, String content) {
        return "<meta name=\"" + name + "\" content=\"" + esc(content) + "\" />\n";
    }

    /** The site's own address; request headers are not trusted for it (audit P15). */
    @org.springframework.beans.factory.annotation.Value("${app.base-url:}")
    private String appBaseUrl;

    private String baseUrl(HttpServletRequest request) {
        if (appBaseUrl != null && !appBaseUrl.isBlank()) return appBaseUrl.replaceAll("/+$", "");
        return request.getScheme() + "://" + request.getServerName();
    }

    private String template() {
        String t = pageTemplate;
        if (t != null) return t;
        try (var in = getClass().getResourceAsStream("/static/event-register.html")) {
            if (in == null) return null;
            t = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            pageTemplate = t;
            return t;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 1).trim() + "…" : s;
    }

    /** Escape a value for safe inclusion in an HTML attribute. */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
