package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.PublicScreenLink;
import com.churchgeniuspro.repository.PublicScreenLinkRepository;
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
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PublicScreensController.class);

    // Page identities and the publish rules live in PublicPagePolicy so every link
    // minter (this page, NTAG landing, reminders, kids QR) applies the same gate.
    static final String MEMBERSHIP_FORM_URL    = com.churchgeniuspro.service.PublicPagePolicy.MEMBERSHIP_FORM_URL;
    static final String DONATION_PAGE_URL      = com.churchgeniuspro.service.PublicPagePolicy.DONATION_PAGE_URL;
    static final String MEMBER_SIGNUP_URL      = com.churchgeniuspro.service.PublicPagePolicy.MEMBER_SIGNUP_URL;
    static final String SMS_OPT_IN_URL         = com.churchgeniuspro.service.PublicPagePolicy.SMS_OPT_IN_URL;
    static final String PUBLIC_PRAYER_URL      = com.churchgeniuspro.service.PublicPagePolicy.PUBLIC_PRAYER_URL;
    static final String PUBLIC_CALENDAR_URL    = com.churchgeniuspro.service.PublicPagePolicy.PUBLIC_CALENDAR_URL;
    static final String GUESS_IT_URL           = com.churchgeniuspro.service.PublicPagePolicy.GUESS_IT_URL;
    static final String KIDS_CHECKIN_URL       = com.churchgeniuspro.service.PublicPagePolicy.KIDS_CHECKIN_URL;
    static final String CONNECT_URL            = com.churchgeniuspro.service.PublicPagePolicy.CONNECT_URL;
    static final String PUBLIC_PRAYER_FORM_URL = com.churchgeniuspro.service.PublicPagePolicy.PUBLIC_PRAYER_FORM_URL;
    public static final List<Map<String, String>> AVAILABLE_PAGES = com.churchgeniuspro.service.PublicPagePolicy.AVAILABLE_PAGES;

    private final PublicScreenLinkRepository linkRepo;
    private final com.churchgeniuspro.service.PublicPagePolicy policy;
    private final com.churchgeniuspro.service.PublicLinkResolver links;

    @Value("${app.base-url}")
    private String baseUrl;

    public PublicScreensController(PublicScreenLinkRepository linkRepo,
                                   com.churchgeniuspro.service.PublicPagePolicy policy,
                                   com.churchgeniuspro.service.PublicLinkResolver links) {
        this.linkRepo = linkRepo;
        this.policy   = policy;
        this.links    = links;
    }

    /**
     * True for an evaluation tenant: a Trial subscription, or a demo/trial tenant
     * by client-id prefix.
     *
     * <p>Both halves are needed. {@code MessagingPolicy} — the one authority on
     * what Trial means — answers the subscription question, and covers a CHR-
     * client that has been put on the Trial plan. The prefix check covers a DEMO-
     * tenant, which {@code loadSmallDemo} can create on any plan and which is
     * therefore not necessarily Trial at all.
     *
     * <p>Never throws: an unreadable subscription answers "restricted", so a
     * database blip withholds two evaluation-only options rather than publishing
     * them. The cost of the wrong answer is not symmetric here.
     */
    /** Delegates to {@link com.churchgeniuspro.service.PublicPagePolicy} — the one rule for every link minter. */
    private String publishDenialReason(String pageUrl, String appClientId) {
        return policy.denialReason(pageUrl, appClientId);
    }

    // ── Page route ────────────────────────────────────────────────────────

    @GetMapping("/publicScreens")
    public String publicScreensPage(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "more.publicscreens");
        if (deny != null) return deny;
        return "forward:/publicScreens.html";
    }

    /**
     * The same gate as the page. These four handlers sat behind AuthFilter's
     * "/api/public" whitelist entry (bare-prefix match) and had no check of their
     * own, so an anonymous caller could revoke any church's links by id and a
     * QR-code visitor (publicView session) could list and mint links for that church.
     * Returns the staff caller's tenant, or null → respond 403.
     */
    private static String staffClientId(HttpServletRequest request) {
        if (RoleGuard.requireAdminOrUser(request) != null) return null;
        if (RoleGuard.requirePermission(request, "more.publicscreens") != null) return null;
        String cid = RoleGuard.clientId(request);   // null for publicView sessions
        return cid == null || cid.isBlank() ? null : cid;
    }

    // ── List available pages ──────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/public-screens/pages")
    public ResponseEntity<List<Map<String, String>>> getAvailablePages(HttpServletRequest request) {
        String appClientId = staffClientId(request);
        if (appClientId == null) return ResponseEntity.status(403).build();
        // Hide options whose feature is disabled by the client's subscription
        // plan (e.g. Member Signup when the Member Portal feature is off).
        List<Map<String, String>> pages = new ArrayList<>();
        for (Map<String, String> p : AVAILABLE_PAGES) {
            // Exactly the rule the POST enforces, so the dropdown can never offer
            // something that generation would then refuse.
            if (publishDenialReason(p.get("url"), appClientId) != null) continue;
            pages.add(p);
        }
        return ResponseEntity.ok(pages);
    }

    // ── List generated links ──────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/public-screens")
    public ResponseEntity<List<Map<String, Object>>> list(HttpServletRequest request) {
        String appClientId = staffClientId(request);
        if (appClientId == null) return ResponseEntity.status(403).build();
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
        String appClientId = staffClientId(request);
        if (appClientId == null) return ResponseEntity.status(403).body(Map.of("error", "Access denied."));
        String pageUrl   = (String) body.get("pageUrl");
        String pageLabel = (String) body.get("pageLabel");
        Object expObj    = body.get("expirationDate");

        // Every restriction in one place, so calling this endpoint directly is no
        // more permissive than using the dropdown.
        String denial = publishDenialReason(pageUrl, appClientId);
        if (denial != null) return bad(denial);

        // A fresh random token per link. It encodes nothing — the row is the link.
        String token = com.churchgeniuspro.service.PublicLinkResolver.newToken();

        LocalDate expiry = null;
        if (expObj instanceof String s && !s.isBlank()) {
            try { expiry = LocalDate.parse(s); } catch (Exception ignored) {}
        }

        // Declaration options — only meaningful for membership form
        Boolean showDecl = body.get("showDeclaration") instanceof Boolean b ? b : false;
        String  declText = body.get("declarationText") instanceof String s && !s.isBlank() ? s : null;

        PublicScreenLink link = new PublicScreenLink();
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
    public ResponseEntity<Map<String, Object>> revoke(@PathVariable Integer id, HttpServletRequest request) {
        String appClientId = staffClientId(request);
        if (appClientId == null) return ResponseEntity.status(403).body(Map.of("error", "Access denied."));
        // Only this church's link. Another tenant's id is "not found", not "revoked".
        Optional<PublicScreenLink> opt = linkRepo.findById(id)
                .filter(l -> appClientId.equals(l.getAppClientId()));
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
     * Legacy public entry point ({@code /pub/<token>}, the form links took before each
     * page had its own). Looks the link up, re-checks that its church may still publish
     * that page, and redirects to the page's own address with the token in the
     * parameter the page reads. Never touches a signed-in session (audit P11).
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

        try {
            String appClientId = link.getAppClientId() != null ? link.getAppClientId() : "";
            String pageUrl     = link.getPageUrl() != null ? link.getPageUrl() : "/home";

            // A link already in someone's hands must not outlive the restriction.
            String denial = publishDenialReason(pageUrl, appClientId);
            if (denial != null) {
                log.info("Public link refused at resolution — page={} tenant={} reason={}",
                         pageUrl, appClientId, denial);
                return "redirect:/login";
            }

            // Send the visitor to the page's own address, with the token in the parameter
            // that page reads — every public page resolves its church from the token in
            // its URL, so nothing has to be stored for it here.
            //
            // A signed-in visitor's session is left exactly as it is. It used to be
            // invalidated when it belonged to another church (and, before that, silently
            // moved onto the link's tenant), which let any page on the internet sign a
            // staff user out with an <img> pointing here (security audit P11).
            String target = linkPath(link);
            jakarta.servlet.http.HttpSession existing = request.getSession(false);
            if (existing != null && (existing.getAttribute("username") != null
                                     || existing.getAttribute("clientId") != null)) {
                return "redirect:" + target;
            }
            // Anonymous visitor: the read-only public-view marker, as before.
            jakarta.servlet.http.HttpSession session = request.getSession(true);
            session.setAttribute("publicView",    true);
            session.setAttribute("appClientId",   appClientId);
            // Don't set username — this keeps auth APIs returning 401 for writes

            return "redirect:" + target;
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

    /** The link's full public URL: the site address plus {@link #linkPath}. */
    private String buildLinkUrl(PublicScreenLink l) {
        return baseUrl + linkPath(l);
    }

    /**
     * The page's own address with this link's token in the parameter that page reads
     * (each page has its own; a page without a dedicated form takes {@code ?c=}).
     * Used both for the URL handed to staff and as the target of {@code /pub/{token}}.
     */
    public static String linkPath(PublicScreenLink l) {
        String page  = l.getPageUrl();
        String token = java.net.URLEncoder.encode(l.getToken(), java.nio.charset.StandardCharsets.UTF_8);
        if (MEMBERSHIP_FORM_URL.equals(page))    return MEMBERSHIP_FORM_URL + "?cid=" + token;
        if (DONATION_PAGE_URL.equals(page))      return DONATION_PAGE_URL + "/" + token;
        if (MEMBER_SIGNUP_URL.equals(page))      return MEMBER_SIGNUP_URL + "?token=" + token;
        if (SMS_OPT_IN_URL.equals(page))         return SMS_OPT_IN_URL + "?token=" + token;
        if (PUBLIC_PRAYER_URL.equals(page))      return PUBLIC_PRAYER_URL + "?cid=" + token;
        if (PUBLIC_CALENDAR_URL.equals(page))    return PUBLIC_CALENDAR_URL + "?cid=" + token;
        if (GUESS_IT_URL.equals(page))           return GUESS_IT_URL + "?cid=" + token;
        if (KIDS_CHECKIN_URL.equals(page))       return KIDS_CHECKIN_URL + "?cid=" + token;
        // Connect carries this link's own token, so revoking or expiring this row stops
        // this URL specifically. Previously every Connect link for a church was the
        // same ciphertext, which no revocation could distinguish.
        if (CONNECT_URL.equals(page))            return CONNECT_URL + "?c=" + token;
        if (PUBLIC_PRAYER_FORM_URL.equals(page)) return PUBLIC_PRAYER_FORM_URL + "?c=" + token + "&src=WEBSITE";
        String base = page != null && !page.isBlank() ? page : "/home";
        return base + (base.contains("?") ? "&" : "?") + "c=" + token;
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
