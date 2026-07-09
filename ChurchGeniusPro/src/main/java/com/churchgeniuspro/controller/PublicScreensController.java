package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
import com.churchgeniuspro.util.EncryptionUtil;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * Handles the Public Screens admin page and public-link REST API.
 *
 * <h3>Page routes</h3>
 * <ul>
 *   <li>{@code GET /publicScreens} → {@code publicScreens.html} (authenticated)</li>
 * </ul>
 *
 * <h3>Authenticated API routes</h3>
 * <ul>
 *   <li>{@code GET    /api/public-screens}        → list generated links for org</li>
 *   <li>{@code POST   /api/public-screens}        → generate a new public link</li>
 *   <li>{@code DELETE /api/public-screens/{id}}   → revoke a link</li>
 * </ul>
 */
@Controller
public class PublicScreensController {

    /** Pages that may NOT be made public. */
    private static final Set<String> EXCLUDED_PAGES = Set.of("/login", "/event-register");

    /** Pages that are handled as membership-form links (direct URL, not /pub/{token}). */
    static final String MEMBERSHIP_FORM_URL = "/membershipForm";

    /** Donation page — served at /donate/{token} instead of /pub/{token}. */
    static final String DONATION_PAGE_URL = "/donate";

    /** Member Signup page — served at /memberSignup?token=<pubToken>. */
    static final String MEMBER_SIGNUP_URL = "/memberSignup";

    /** SMS Opt-In page — served at /smsOptIn?token=<pubToken>. */
    static final String SMS_OPT_IN_URL = "/smsOptIn";

    /** Public Prayer Requests page — served at /viewPrayerRequest?cid={encryptedClientId}. */
    static final String PUBLIC_PRAYER_URL = "/viewPrayerRequest";

    /** Public Event Calendar page — served at /viewEventCalendar?cid={encryptedClientId}. */
    static final String PUBLIC_CALENDAR_URL = "/viewEventCalendar";

    /** Guess It game page — served at /guessIt?cid={encryptedClientId}. */
    static final String GUESS_IT_URL = "/guessIt";

    /** Public Kids Check-In — served at /kidsCheckin?cid={encryptedClientId}. */
    static final String KIDS_CHECKIN_URL = "/kidsCheckin";

    /** Public "Connect With Us" page — served at /connect?c={encryptedClientId}. */
    static final String CONNECT_URL = "/connect";

    /** Public Prayer Request form (website source) — served at /publicPrayer?c={encryptedClientId}&src=WEBSITE. */
    static final String PUBLIC_PRAYER_FORM_URL = "/publicPrayer";

    /**
     * Pages available for public link generation.
     * Only congregation/visitor-facing pages are listed here.
     * Financial data, member records, and admin-only tools are intentionally excluded.
     */
    public static final List<Map<String, String>> AVAILABLE_PAGES = List.of(
            page("Event Calendar",  PUBLIC_CALENDAR_URL),
            page("Events",          "/event"),
            page("Groups",          "/groups"),
            page("Meetings",        "/meetings"),
            page("Prayer Requests", PUBLIC_PRAYER_URL),
            page("Certificates",    "/certificates"),
            page("Membership Form", MEMBERSHIP_FORM_URL),
            page("Donation Page",   DONATION_PAGE_URL),
            page("Member Signup",             MEMBER_SIGNUP_URL),
            page("SMS Opt-In Form",           SMS_OPT_IN_URL),
            page("Midwest Region Meet RSVP",  "/midRegMeetRsvp"),
            page("Guess It",                  GUESS_IT_URL),
            page("Kids Check-In",             KIDS_CHECKIN_URL),
            page("Connect With Us",           CONNECT_URL),
            page("Prayer Request (Public)",   PUBLIC_PRAYER_FORM_URL)
    );

    private final PublicScreenLinkRepository linkRepo;

    @Value("${app.base-url}")
    private String baseUrl;

    public PublicScreensController(PublicScreenLinkRepository linkRepo) {
        this.linkRepo = linkRepo;
    }

    // ── Page route ────────────────────────────────────────────────────────

    @GetMapping("/publicScreens")
    public String publicScreensPage(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePermission(request, "more.publicscreens");
        if (deny != null) return deny;
        return "forward:/publicScreens.html";
    }

    // ── List available pages ──────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/public-screens/pages")
    public ResponseEntity<List<Map<String, String>>> getAvailablePages() {
        return ResponseEntity.ok(AVAILABLE_PAGES);
    }

    // ── List generated links ──────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/public-screens")
    public ResponseEntity<List<Map<String, Object>>> list(HttpServletRequest request) {
        String appClientId = SessionUtil.getAppClientId(request);
        List<PublicScreenLink> links = linkRepo.findByAppClientIdAndRevokedFalseOrderByCreatedDateDesc(appClientId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (PublicScreenLink l : links) {
            result.add(toLinkMap(l));
        }
        return ResponseEntity.ok(result);
    }

    // ── Generate public link ──────────────────────────────────────────────

    @ResponseBody
    @PostMapping("/api/public-screens")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody Map<String, Object> body,
                                                         HttpServletRequest request) {
        String appClientId = SessionUtil.getAppClientId(request);
        String pageUrl   = (String) body.get("pageUrl");
        String pageLabel = (String) body.get("pageLabel");
        Object expObj    = body.get("expirationDate");

        if (pageUrl == null || pageUrl.isBlank()) return bad("Page URL is required.");
        if (EXCLUDED_PAGES.stream().anyMatch(pageUrl::startsWith)) {
            return bad("This page cannot be made public.");
        }

        // Encrypt: appClientId | pageUrl
        String payload = (appClientId != null ? appClientId : "") + "|" + pageUrl;
        String token;
        try {
            token = EncryptionUtil.encrypt(payload);
        } catch (Exception e) {
            return bad("Failed to generate link token.");
        }

        LocalDate expiry = null;
        if (expObj instanceof String s && !s.isBlank()) {
            try { expiry = LocalDate.parse(s); } catch (Exception ignored) {}
        }

        // Declaration options — only meaningful for membership form
        Boolean showDecl = body.get("showDeclaration") instanceof Boolean b ? b : false;
        String  declText = body.get("declarationText") instanceof String s && !s.isBlank() ? s : null;

        // Upsert: if a record with this token already exists (e.g. previously revoked),
        // reactivate it instead of inserting a duplicate that would violate the unique constraint.
        Optional<PublicScreenLink> existing = linkRepo.findByToken(token);
        PublicScreenLink link = existing.orElseGet(PublicScreenLink::new);
        link.setPageLabel(pageLabel != null ? pageLabel : pageUrl);
        link.setPageUrl(pageUrl);
        link.setToken(token);
        link.setAppClientId(appClientId);
        link.setExpirationDate(expiry);
        link.setShowDeclaration(showDecl);
        link.setDeclarationText(declText);
        link.setRevoked(false);
        PublicScreenLink saved = linkRepo.save(link);

        Map<String, Object> m = toLinkMap(saved);
        m.put("publicUrl", buildLinkUrl(saved));
        return ResponseEntity.ok(m);
    }

    // ── Revoke link ───────────────────────────────────────────────────────

    @ResponseBody
    @DeleteMapping("/api/public-screens/{id}")
    public ResponseEntity<Map<String, Object>> revoke(@PathVariable Integer id) {
        Optional<PublicScreenLink> opt = linkRepo.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        PublicScreenLink l = opt.get();
        l.setRevoked(true);
        linkRepo.save(l);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("success", true);
        return ResponseEntity.ok(ok);
    }

    // ── Public access endpoint ────────────────────────────────────────────

    /**
     * Accessed by the public. Decrypts the token to extract the appClientId
     * and target page, then sets a read-only public session and forwards
     * to the target page.
     */
    @GetMapping("/pub/{token}")
    public String publicAccess(@PathVariable String token, HttpServletRequest request,
                                jakarta.servlet.http.HttpServletResponse response) {
        Optional<PublicScreenLink> opt = linkRepo.findByToken(token);
        if (opt.isEmpty()) {
            return "redirect:/login";
        }
        PublicScreenLink link = opt.get();
        if (link.isRevoked()) return "redirect:/login";
        if (link.getExpirationDate() != null && link.getExpirationDate().isBefore(LocalDate.now())) {
            return "redirect:/login";
        }

        // Decrypt token to verify payload
        try {
            String payload     = EncryptionUtil.decrypt(token);
            String[] parts     = payload.split("\\|", 2);
            String appClientId = parts.length > 0 ? parts[0] : "";
            String pageUrl     = parts.length > 1 ? parts[1] : "/home";

            // Store in session for public read-only access
            jakarta.servlet.http.HttpSession session = request.getSession(true);
            session.setAttribute("publicView",    true);
            session.setAttribute("appClientId",   appClientId);
            // Don't set username — this keeps auth APIs returning 401 for writes

            return "redirect:" + pageUrl;
        } catch (Exception e) {
            return "redirect:/login";
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Map<String, Object> toLinkMap(PublicScreenLink l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",              l.getId());
        m.put("pageLabel",       l.getPageLabel());
        m.put("pageUrl",         l.getPageUrl());
        m.put("token",           l.getToken());
        m.put("expirationDate",  l.getExpirationDate() != null ? l.getExpirationDate().toString() : null);
        m.put("createdDate",     l.getCreatedDate()    != null ? l.getCreatedDate().toString()    : null);
        m.put("publicUrl",       buildLinkUrl(l));
        m.put("showDeclaration", Boolean.TRUE.equals(l.getShowDeclaration()));
        m.put("declarationText", l.getDeclarationText());
        return m;
    }

    /** Returns the correct public URL for the link type. */
    private static final String MID_REG_MEET_RSVP_URL = "/midRegMeetRsvp";

    private String buildLinkUrl(PublicScreenLink l) {
        if (MEMBERSHIP_FORM_URL.equals(l.getPageUrl())) {
            return baseUrl + MEMBERSHIP_FORM_URL + "?cid=" + l.getToken();
        }
        if (DONATION_PAGE_URL.equals(l.getPageUrl())) {
            return baseUrl + DONATION_PAGE_URL + "/" + l.getToken();
        }
        if (MEMBER_SIGNUP_URL.equals(l.getPageUrl())) {
            return baseUrl + MEMBER_SIGNUP_URL + "?token=" + l.getToken();
        }
        if (SMS_OPT_IN_URL.equals(l.getPageUrl())) {
            return baseUrl + SMS_OPT_IN_URL + "?token=" + l.getToken();
        }
        if (MID_REG_MEET_RSVP_URL.equals(l.getPageUrl()) && l.getAppClientId() != null) {
            try {
                String encryptedCid = com.churchgeniuspro.util.EncryptionUtil.encrypt(l.getAppClientId());
                return baseUrl + MID_REG_MEET_RSVP_URL + "?cid=" + java.net.URLEncoder.encode(encryptedCid, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                // fallback: return link without cid (invalid link, but won't expose plain clientId)
                return baseUrl + MID_REG_MEET_RSVP_URL;
            }
        }
        if (PUBLIC_PRAYER_URL.equals(l.getPageUrl()) && l.getAppClientId() != null) {
            try {
                String encryptedCid = com.churchgeniuspro.util.EncryptionUtil.encrypt(l.getAppClientId());
                return baseUrl + PUBLIC_PRAYER_URL + "?cid=" + java.net.URLEncoder.encode(encryptedCid, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return baseUrl + PUBLIC_PRAYER_URL;
            }
        }
        if (PUBLIC_CALENDAR_URL.equals(l.getPageUrl()) && l.getAppClientId() != null) {
            try {
                String encryptedCid = com.churchgeniuspro.util.EncryptionUtil.encrypt(l.getAppClientId());
                return baseUrl + PUBLIC_CALENDAR_URL + "?cid=" + java.net.URLEncoder.encode(encryptedCid, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return baseUrl + PUBLIC_CALENDAR_URL;
            }
        }
        if (GUESS_IT_URL.equals(l.getPageUrl()) && l.getAppClientId() != null) {
            try {
                String encryptedCid = com.churchgeniuspro.util.EncryptionUtil.encrypt(l.getAppClientId());
                return baseUrl + GUESS_IT_URL + "?cid=" + java.net.URLEncoder.encode(encryptedCid, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return baseUrl + GUESS_IT_URL;
            }
        }
        if (KIDS_CHECKIN_URL.equals(l.getPageUrl()) && l.getAppClientId() != null) {
            try {
                String encryptedCid = com.churchgeniuspro.util.EncryptionUtil.encrypt(l.getAppClientId());
                return baseUrl + KIDS_CHECKIN_URL + "?cid=" + java.net.URLEncoder.encode(encryptedCid, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return baseUrl + KIDS_CHECKIN_URL;
            }
        }
        if (CONNECT_URL.equals(l.getPageUrl()) && l.getAppClientId() != null) {
            try {
                String encryptedCid = com.churchgeniuspro.util.EncryptionUtil.encrypt(l.getAppClientId());
                return baseUrl + CONNECT_URL + "?c=" + java.net.URLEncoder.encode(encryptedCid, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return baseUrl + CONNECT_URL;
            }
        }
        if (PUBLIC_PRAYER_FORM_URL.equals(l.getPageUrl()) && l.getAppClientId() != null) {
            try {
                String encryptedCid = com.churchgeniuspro.util.EncryptionUtil.encrypt(l.getAppClientId());
                return baseUrl + PUBLIC_PRAYER_FORM_URL + "?c=" + java.net.URLEncoder.encode(encryptedCid, java.nio.charset.StandardCharsets.UTF_8) + "&src=WEBSITE";
            } catch (Exception e) {
                return baseUrl + PUBLIC_PRAYER_FORM_URL;
            }
        }
        return buildPublicUrl(l.getToken());
    }

    private String buildPublicUrl(String token) {
        return baseUrl + "/pub/" + token;
    }

    private ResponseEntity<Map<String, Object>> bad(String msg) {
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("error", msg);
        return ResponseEntity.badRequest().body(r);
    }
    /** Creates a simple label→url map for the available-pages list. */
    private static Map<String, String> page(String label, String url) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("label", label);
        m.put("url",   url);
        return m;
    }


}
