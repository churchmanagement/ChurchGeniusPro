package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Public NTAG landing page: per-church branding + customizable buttons, the public
 * payload, view/click analytics, and the public "upcoming events" aggregation
 * (church events + meetings; birthdays/anniversaries are reminders, not events, so
 * they are naturally excluded).
 */
@Service
public class NtagLandingService {

    private static final Logger LOG = LoggerFactory.getLogger(NtagLandingService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    // The app's single operational time zone — same constant used for reminder/
    // scheduling cutoffs elsewhere (ReminderSchedulerService, EventRegistrationReminderService).
    // "Today" for the public Upcoming Events list must be evaluated here, not in the
    // server JVM's default zone: a cloud host typically runs in UTC, which can call it
    // "tomorrow" while it is still today for the church, silently hiding same-day events.
    private static final ZoneId CHURCH_ZONE = ZoneId.of("America/Chicago");

    private final NtagLandingConfigRepository configRepo;
    private final NtagLandingEventRepository eventRepo;
    private final ChurchRegistrationRepository churchRepo;
    private final ChurchLogoRepository logoRepo;
    private final ChurchEventRepository churchEventRepo;
    private final MeetingRepository meetingRepo;

    private final PublicLinkResolver links;

    public NtagLandingService(NtagLandingConfigRepository configRepo,
                              NtagLandingEventRepository eventRepo,
                              ChurchRegistrationRepository churchRepo,
                              ChurchLogoRepository logoRepo,
                              ChurchEventRepository churchEventRepo,
                              MeetingRepository meetingRepo,
            PublicLinkResolver links) {
        this.links = links;
        this.configRepo = configRepo;
        this.eventRepo = eventRepo;
        this.churchRepo = churchRepo;
        this.logoRepo = logoRepo;
        this.churchEventRepo = churchEventRepo;
        this.meetingRepo = meetingRepo;
    }

    // ── Config (admin) ─────────────────────────────────────────────────────────

    @Transactional
    public NtagLandingConfig getOrCreate(String clientId) {
        return configRepo.findByClientId(clientId).orElseGet(() -> {
            NtagLandingConfig c = new NtagLandingConfig();
            c.setClientId(clientId);
            c.setEnabled(true);
            c.setThemeColor("#673147");
            ChurchRegistration cr = churchRepo.findByClientIdAndDeleteFlagFalse(clientId).orElse(null);
            c.setWelcomeMessage("Welcome! We're glad you're here.");
            c.setButtonsJson(writeButtons(defaultButtons(clientId, cr)));
            return configRepo.save(c);
        });
    }

    private List<Map<String, Object>> defaultButtons(String clientId, ChurchRegistration cr) {
        // Each button carries the church's own live link for that page (created here
        // if needed). Revoking a link on Public Screens ends that button's URL.
        String connect = tokenFor(clientId, PublicPagePolicy.CONNECT_URL,            "Connect With Us");
        String events  = tokenFor(clientId, PublicPagePolicy.UPCOMING_EVENTS_URL,    "Upcoming Events");
        String prayer  = tokenFor(clientId, PublicPagePolicy.PUBLIC_PRAYER_FORM_URL, "Prayer Request (Public)");
        String website   = cr != null ? nz(cr.getWebsiteUrl())   : "";
        String facebook  = cr != null ? nz(cr.getFacebookUrl())  : "";
        String instagram = cr != null ? nz(cr.getInstagramUrl()) : "";
        String youtube   = cr != null ? nz(cr.getYoutubeUrl())   : "";
        String email     = cr != null ? nz(cr.getEmail())        : "";
        List<Map<String, Object>> b = new ArrayList<>();
        // Main action buttons.
        b.add(button("website",   "Website",          "🌐", website, true, 1, "main"));
        b.add(button("connect",   "Connect With Us",  "🤝", connect.isEmpty() ? "" : "/connect?c=" + connect, true, 2, "main"));
        b.add(button("events",    "Upcoming Events",  "📅", events.isEmpty()  ? "" : "/upcomingEvents?c=" + events, true, 3, "main"));
        b.add(button("prayer",    "Prayer Request",   "🙏", prayer.isEmpty()  ? "" : "/publicPrayer?c=" + prayer + "&src=NTAG", true, 4, "main"));
        b.add(button("give",      "Give",             "💝", "", true, 5, "main"));
        // Footer / social links (icon-only on the public page).
        b.add(button("facebook",  "Facebook",         "📘", facebook, true, 6, "social"));
        b.add(button("instagram", "Instagram",        "📸", instagram, true, 7, "social"));
        b.add(button("youtube",   "YouTube",          "▶️", youtube, true, 8, "social"));
        b.add(button("x",         "X (Twitter)",      "✖️", "", true, 9, "social"));
        b.add(button("whatsapp",  "WhatsApp",         "💬", "", true, 10, "social"));
        b.add(button("linkedin",  "LinkedIn",         "💼", "", true, 11, "social"));
        b.add(button("email",     "Email",            "✉️", email.isEmpty() ? "" : ("mailto:" + email), true, 12, "social"));
        return b;
    }

    private static Map<String, Object> button(String key, String label, String icon, String url, boolean enabled, int order, String section) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key); m.put("label", label); m.put("icon", icon);
        m.put("url", url == null ? "" : url); m.put("enabled", enabled); m.put("order", order);
        m.put("section", section);
        return m;
    }

    /**
     * Keeps the church-managed social/website links in sync with Church Settings.
     *
     * <p>For the keys {@code website}, {@code facebook}, {@code instagram},
     * {@code youtube} (and {@code email} → mailto), the URL is ALWAYS taken live
     * from the current {@link ChurchRegistration}, overriding whatever snapshot is
     * stored in the saved config. Any managed button missing from an older saved
     * config (for example YouTube) is injected from the defaults, so the footer
     * always shows the full set with the right icons. This means updating a URL in
     * Church Settings is reflected on the NTAG landing footer automatically, with
     * no extra configuration.
     */
    private List<Map<String, Object>> applyChurchSocials(List<Map<String, Object>> buttons,
                                                         ChurchRegistration cr, String clientId) {
        Map<String, String> live = new LinkedHashMap<>();
        live.put("website",   cr != null ? nz(cr.getWebsiteUrl())   : "");
        live.put("facebook",  cr != null ? nz(cr.getFacebookUrl())  : "");
        live.put("instagram", cr != null ? nz(cr.getInstagramUrl()) : "");
        live.put("youtube",   cr != null ? nz(cr.getYoutubeUrl())   : "");
        String email = cr != null ? nz(cr.getEmail()) : "";
        live.put("email", email.isEmpty() ? "" : ("mailto:" + email));

        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> b : buttons) {
            String key = b.get("key") == null ? "" : b.get("key").toString().toLowerCase();
            seen.add(key);
            if (live.containsKey(key)) {
                String savedUrl = b.get("url") == null ? "" : b.get("url").toString().trim();
                // "email" is EDITABLE on the NTAG Landing admin page, so a saved
                // value must win; Church Settings only fills it when blank.
                // The other managed keys (website/socials) are read-only in the
                // admin UI and always mirror Church Settings.
                boolean keepSaved = "email".equals(key) && !savedUrl.isEmpty();
                Map<String, Object> copy = new LinkedHashMap<>(b);
                copy.put("url", keepSaved ? savedUrl : live.get(key));
                out.add(copy);
            } else {
                out.add(b);
            }
        }
        // Inject any managed button an older saved config doesn't yet have (e.g. YouTube).
        for (Map<String, Object> def : defaultButtons(clientId, cr)) {
            String key = def.get("key").toString().toLowerCase();
            if (live.containsKey(key) && !seen.contains(key)) out.add(def);
        }
        // Upgrade generic 🔗 icons on well-known links (e.g. a custom "Song Book"
        // button pointing at /songbook/view gets the songbook icon 🎵). Icons the
        // admin has customised are left untouched.
        for (Map<String, Object> b : out) {
            String icon = b.get("icon") == null ? "" : b.get("icon").toString().trim();
            if (!icon.isEmpty() && !"🔗".equals(icon)) continue;
            String url = b.get("url") == null ? "" : b.get("url").toString();
            if (url.contains("/songbook/view"))      b.put("icon", "🎵");
            else if (url.contains("/donate/"))       b.put("icon", "💝");
            else if (url.contains("/upcomingEvents")) b.put("icon", "📅");
            else if (url.contains("/publicPrayer"))  b.put("icon", "🙏");
        }
        return out;
    }

    @Transactional
    public NtagLandingConfig save(String clientId, String welcomeMessage, String themeColor, String bannerImage,
                                  Boolean showLogo, Boolean enabled, List<Map<String, Object>> buttons) {
        NtagLandingConfig c = getOrCreate(clientId);
        c.setWelcomeMessage(welcomeMessage);
        if (themeColor != null && !themeColor.isBlank()) c.setThemeColor(themeColor);
        c.setBannerImage(bannerImage);
        if (showLogo != null) c.setShowLogo(showLogo);
        if (enabled != null) c.setEnabled(enabled);
        if (buttons != null) c.setButtonsJson(writeButtons(buttons));
        return configRepo.save(c);
    }

    /** Full config for the admin editor (+ church branding for preview + the public link token). */
    public Map<String, Object> configMap(String clientId) {
        NtagLandingConfig c = getOrCreate(clientId);
        ChurchRegistration cr = churchRepo.findByClientIdAndDeleteFlagFalse(clientId).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", c.isEnabled());
        m.put("welcomeMessage", c.getWelcomeMessage());
        m.put("themeColor", c.getThemeColor());
        m.put("bannerImage", c.getBannerImage());
        m.put("showLogo", c.isShowLogo());
        m.put("buttons", applyChurchSocials(readButtons(c.getButtonsJson()), cr, clientId));
        m.put("churchName", cr != null ? cr.getChurchName() : "Church");
        m.put("logo", logoDataUrl(clientId));
        m.put("cidToken", tokenFor(clientId, PublicPagePolicy.NTAG_LANDING_URL, "Tap-to-Connect Landing"));
        return m;
    }

    // ── Public payload ─────────────────────────────────────────────────────────

    /** The branded payload for the public landing page; records a view. {@code null} if disabled/not found. */
    @Transactional
    public Map<String, Object> publicPayload(String cid, String ip, String ua) {
        String clientId = decrypt(cid);
        if (clientId == null) return null;
        NtagLandingConfig c = configRepo.findByClientId(clientId).orElse(null);
        if (c == null) c = getOrCreate(clientId);
        if (!c.isEnabled()) return null;
        ChurchRegistration cr = churchRepo.findByClientIdAndDeleteFlagFalse(clientId).orElse(null);

        List<Map<String, Object>> buttons = applyChurchSocials(readButtons(c.getButtonsJson()), cr, clientId).stream()
                .filter(b -> Boolean.TRUE.equals(b.get("enabled")))
                .filter(b -> b.get("url") != null && !b.get("url").toString().isBlank())
                .sorted(Comparator.comparingInt(b -> asInt(b.get("order"))))
                .toList();

        record(clientId, "view", null, null, ip, ua);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("churchName", cr != null ? cr.getChurchName() : "Church");
        m.put("welcomeMessage", c.getWelcomeMessage());
        m.put("themeColor", c.getThemeColor() == null ? "#673147" : c.getThemeColor());
        m.put("bannerImage", c.getBannerImage());
        m.put("showLogo", c.isShowLogo());
        m.put("logo", c.isShowLogo() ? logoDataUrl(clientId) : null);
        m.put("buttons", buttons);
        return m;
    }

    @Transactional
    public void track(String cid, String type, String buttonKey, String label, String ip, String ua) {
        String clientId = decrypt(cid);
        if (clientId == null) return;
        if (!"view".equals(type) && !"click".equals(type)) return;
        record(clientId, type, buttonKey, label, ip, ua);
    }

    private void record(String clientId, String type, String buttonKey, String label, String ip, String ua) {
        try {
            NtagLandingEvent e = new NtagLandingEvent();
            e.setClientId(clientId); e.setType(type); e.setButtonKey(buttonKey); e.setButtonLabel(label);
            e.setIpAddress(ip); e.setUserAgent(ua != null && ua.length() > 300 ? ua.substring(0, 300) : ua);
            eventRepo.save(e);
        } catch (Exception ex) { LOG.warn("[NtagLanding] analytics save failed: {}", ex.toString()); }
    }

    // ── Report (admin) ──────────────────────────────────────────────────────────

    public Map<String, Object> report(String clientId, int recentLimit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("views", eventRepo.countByClientIdAndType(clientId, "view"));
        out.put("clicks", eventRepo.countByClientIdAndType(clientId, "click"));
        List<Map<String, Object>> totals = new ArrayList<>();
        for (Object[] row : eventRepo.clickTotals(clientId)) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("buttonKey", row[0]); t.put("buttonLabel", row[1]); t.put("count", row[2]);
            totals.add(t);
        }
        out.put("clickTotals", totals);
        List<Map<String, Object>> recent = new ArrayList<>();
        for (NtagLandingEvent e : eventRepo.findByClientIdOrderByCreatedAtDesc(clientId, PageRequest.of(0, Math.max(1, Math.min(recentLimit, 300))))) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("time", e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
            r.put("type", e.getType()); r.put("buttonLabel", e.getButtonLabel());
            r.put("ip", e.getIpAddress());
            recent.add(r);
        }
        out.put("recent", recent);
        return out;
    }

    // ── Public upcoming events (events + meetings) ─────────────────────────────

    public List<Map<String, Object>> upcomingEvents(String cid) {
        // The events/calendar page is reached from the landing page's button (its own
        // link), directly from the landing token, or from an Event Calendar link
        // (the two pages' content is merged now) — accept any of their live tokens.
        String clientId = links.resolveClientIdAnyOf(cid, PublicPagePolicy.UPCOMING_EVENTS_FAMILY);
        if (clientId == null) return List.of();
        // Church-zone "today" — see CHURCH_ZONE. Today's own events/meetings stay in
        // (>= today, not > today), only strictly-past dates are dropped.
        LocalDate today = LocalDate.now(CHURCH_ZONE);
        List<Map<String, Object>> out = new ArrayList<>();

        for (ChurchEvent ev : churchEventRepo.findByAppClientIdAndDeleteFlagFalseOrderByCreatedDateDesc(clientId)) {
            if (ev.getEventDate() == null || ev.getEventDate().isBefore(today)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "event");
            m.put("name", ev.getEventName());
            m.put("date", ev.getEventDate().toString());
            m.put("startTime", ev.getStartTime());
            m.put("endTime", ev.getEndTime());
            m.put("location", joinLoc(ev.getAddress1(), ev.getCity(), ev.getState()));
            m.put("description", ev.getNote());
            m.put("fee", ev.getFee());
            m.put("registrationLink", ev.getRegistrationLink());
            out.add(m);
        }
        for (Meeting mt : meetingRepo.findAllActiveByAppUserOrderByDateDesc(clientId)) {
            if (mt.getMeetingDate() == null || mt.getMeetingDate().isBefore(today)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", "meeting");
            m.put("name", mt.getMeetingType() != null ? mt.getMeetingType().getTypeName() : "Meeting");
            m.put("date", mt.getMeetingDate().toString());
            m.put("startTime", mt.getStartTime());
            m.put("endTime", mt.getEndTime());
            m.put("location", joinLoc(mt.getAddress1(), mt.getCity(), mt.getState()));
            m.put("description", mt.getNote());
            out.add(m);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("date")))
                .thenComparing(m -> nz((String) m.get("startTime"))));
        return out;
    }

    // ── helpers ──

    private String logoDataUrl(String clientId) {
        ChurchLogo logo = logoRepo.findByClientId(clientId).orElse(null);
        if (logo == null || logo.getLogoData() == null || logo.getLogoData().length == 0) return null;
        String ct = logo.getContentType() != null ? logo.getContentType() : "image/png";
        return "data:" + ct + ";base64," + Base64.getEncoder().encodeToString(logo.getLogoData());
    }

    private String writeButtons(List<Map<String, Object>> b) {
        try { return mapper.writeValueAsString(b); } catch (Exception e) { return "[]"; }
    }
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readButtons(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try { return mapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {}); }
        catch (Exception e) { return new ArrayList<>(); }
    }
    /** The landing-page token → tenant. Nothing is decrypted; the link row is looked up. */
    private String decrypt(String cid) { return links.resolveClientId(cid, PublicPagePolicy.NTAG_LANDING_URL); }
    /** Live link token for a page, minting one when the church has none. Empty when the policy forbids it. */
    private String tokenFor(String clientId, String pageUrl, String label) {
        return links.ensureLink(clientId, pageUrl, label).map(com.churchgeniuspro.hibernate.PublicScreenLink::getToken).orElse("");
    }
    private static int asInt(Object o) { try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 999; } }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String joinLoc(String a1, String city, String state) {
        List<String> p = new ArrayList<>();
        if (a1 != null && !a1.isBlank()) p.add(a1.trim());
        // Skip a purely-numeric state code (some records store the numeric lookup id).
        String st = (state != null && !state.isBlank() && !state.trim().matches("\\d+")) ? state.trim() : "";
        String cs = ((city == null ? "" : city.trim()) + (!st.isEmpty() ? ", " + st : "")).trim();
        if (cs.startsWith(",")) cs = cs.substring(1).trim();
        if (!cs.isEmpty()) p.add(cs);
        return String.join(" · ", p);
    }
}
