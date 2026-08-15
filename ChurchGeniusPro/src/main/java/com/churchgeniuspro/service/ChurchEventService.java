package com.churchgeniuspro.service;

import com.churchgeniuspro.common.States;
import com.churchgeniuspro.hibernate.ChurchEvent;
import com.churchgeniuspro.hibernate.ChurchEventDay;
import com.churchgeniuspro.hibernate.EmailSettings;
import com.churchgeniuspro.hibernate.EventRegistration;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.model.ChurchEventBO;
import com.churchgeniuspro.model.EventRegistrationBO;
import com.churchgeniuspro.repository.ChurchEventDayRepository;
import com.churchgeniuspro.repository.ChurchEventRepository;
import com.churchgeniuspro.repository.EmailSettingsRepository;
import com.churchgeniuspro.repository.EventRegistrationRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.util.EncryptionUtil;
import com.churchgeniuspro.util.PhoneUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service layer for {@link ChurchEvent} CRUD operations and event registrations.
 */
@Service
public class ChurchEventService {

    private final ChurchEventRepository          eventRepo;
    private final ChurchEventDayRepository       dayRepo;
    private final EventRegistrationRepository    regRepo;
    private final EmailService                   emailService;
    private final EmailSettingsRepository        emailSettingsRepo;
    private final SmsService                     smsService;
    private final FamilyMemberRepository         memberRepo;
    private final EventEmailTemplateService      eventEmailTemplateService;

    /** Public base URL — used to build the RSVP update link embedded in emails. */
    @Value("${app.base-url}")
    private String baseUrl;

    public ChurchEventService(ChurchEventRepository eventRepo,
                              ChurchEventDayRepository dayRepo,
                              EventRegistrationRepository regRepo,
                              EmailService emailService,
                              EmailSettingsRepository emailSettingsRepo,
                              SmsService smsService,
                              FamilyMemberRepository memberRepo,
                              EventEmailTemplateService eventEmailTemplateService) {
        this.eventRepo         = eventRepo;
        this.dayRepo           = dayRepo;
        this.regRepo           = regRepo;
        this.emailService      = emailService;
        this.emailSettingsRepo = emailSettingsRepo;
        this.smsService        = smsService;
        this.memberRepo        = memberRepo;
        this.eventEmailTemplateService = eventEmailTemplateService;
    }

    // ── List ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAll(String appClientId) {
        return eventRepo.findByAppClientIdAndDeleteFlagFalseOrderByCreatedDateDesc(appClientId)
                .stream()
                .map(this::toMapWithCounts)
                .collect(Collectors.toList());
    }

    // ── Single ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> getById(Integer id) {
        ChurchEvent ev = eventRepo.findByIdAndDeleteFlagFalse(id)
                .orElseThrow(() -> new IllegalArgumentException("Event not found: " + id));
        Map<String, Object> m = toMap(ev);
        // Single-event fetch may include the full image (small payload for one event).
        m.put("imageData", ev.getImageData());
        m.put("days", getDaysForEvent(ev.getId()));
        return m;
    }

    // ── Raw entity lookup (for controller-level use) ──────────────────────

    /**
     * Returns the raw {@link ChurchEvent} entity by ID, or {@code null} if not found.
     * Used by the controller to access fields (e.g. {@code appClientId}) not exposed
     * in the BO map.
     */
    @Transactional(readOnly = true)
    public ChurchEvent getEventEntityById(Integer id) {
        return eventRepo.findByIdAndDeleteFlagFalse(id).orElse(null);
    }

    // ── Public event detail (for registration page) ───────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> getPublicDetail(String token) {
        Integer id = decryptToken(token);
        ChurchEvent ev = eventRepo.findByIdAndDeleteFlagFalse(id)
                .orElseThrow(() -> new IllegalArgumentException("Event not found"));
        Map<String, Object> m = toMapWithCounts(ev);
        m.put("days", getDaysForEvent(ev.getId()));
        // churchName is overwritten by the controller with the full name from ChurchRegistration
        m.put("churchName", resolveChurchName(ev.getAppClientId()));
        return m;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getRegistrationsByToken(String token) {
        Integer id = decryptToken(token);
        return getRegistrations(id);
    }

    @Transactional
    public EventRegistration registerByToken(String token, EventRegistrationBO bo) {
        Integer id = decryptToken(token);
        return register(id, bo);
    }

    // ── Create ────────────────────────────────────────────────────────────

    @Transactional
    public ChurchEvent create(ChurchEventBO bo, String appClientId, String createdBy) {
        ChurchEvent ev = new ChurchEvent();
        applyBO(ev, bo);
        ev.setAppClientId(appClientId);
        ev.setCreatedBy(createdBy);
        ChurchEvent saved = eventRepo.save(ev);
        saveDays(saved.getId(), bo);
        return saved;
    }

    // ── Update ────────────────────────────────────────────────────────────

    @Transactional
    public ChurchEvent update(Integer id, ChurchEventBO bo, String createdBy) {
        ChurchEvent ev = eventRepo.findByIdAndDeleteFlagFalse(id)
                .orElseThrow(() -> new IllegalArgumentException("Event not found: " + id));
        applyBO(ev, bo);
        // Only update createdBy if provided (preserves original creator on edits)
        if (createdBy != null && ev.getCreatedBy() == null) {
            ev.setCreatedBy(createdBy);
        }
        ChurchEvent saved = eventRepo.save(ev);
        // Replace all day records
        dayRepo.deleteByEventId(id);
        saveDays(id, bo);
        return saved;
    }

    // ── Soft-Delete ───────────────────────────────────────────────────────

    @Transactional
    public void delete(Integer id) {
        ChurchEvent ev = eventRepo.findByIdAndDeleteFlagFalse(id)
                .orElseThrow(() -> new IllegalArgumentException("Event not found: " + id));
        ev.setDeleteFlag(true);
        eventRepo.save(ev);
    }

    // ── Registrations ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getRegistrations(Integer eventId) {
        return regRepo.findByEventIdOrderByCreatedDateAsc(eventId)
                .stream()
                .map(this::regToMap)
                .collect(Collectors.toList());
    }

    @Transactional
    public EventRegistration register(Integer eventId, EventRegistrationBO bo) {
        ChurchEvent ev = eventRepo.findByIdAndDeleteFlagFalse(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Event not found: " + eventId));

        String emailTrimmed = trim(bo.getEmail());
        String phoneTrimmed = trim(bo.getPhone());

        // Upsert: if this person already has a registration for THIS event (matched
        // by email, otherwise by phone), update that record instead of rejecting it
        // as a duplicate. Otherwise create a fresh registration.
        EventRegistration reg = null;
        if (emailTrimmed != null && !emailTrimmed.isBlank()) {
            reg = regRepo.findByEventIdAndEmailIgnoreCase(eventId, emailTrimmed).orElse(null);
        }
        if (reg == null && phoneTrimmed != null && !phoneTrimmed.isBlank()) {
            reg = regRepo.findFirstByEventIdAndPhone(eventId, phoneTrimmed).orElse(null);
        }
        if (reg == null) {
            reg = new EventRegistration();
            reg.setEventId(eventId);
        }
        // (Re)assert the parent event's tenant — also backfills legacy rows on update.
        reg.setClientId(ev.getAppClientId());

        reg.setFirstName(trim(bo.getFirstName()));
        reg.setLastName(trim(bo.getLastName()));
        reg.setEmail(emailTrimmed);
        reg.setPhone(phoneTrimmed);
        reg.setAdults(bo.getAdults());
        reg.setKids(bo.getKids());
        reg.setAttending(bo.getAttending());
        reg.setNote(bo.getNote());
        reg.setVegetarian(bo.getVegetarian());
        reg.setSelectedFoodItems(bo.getSelectedFoodItems());
        reg.setAttendingDays(bo.getAttendingDays());
        EventRegistration saved = regRepo.save(reg);

        // Send confirmation email if address is present
        if (saved.getEmail() != null && !saved.getEmail().isBlank()) {
            sendRegistrationEmail(saved, ev);
        }

        // Send confirmation SMS if phone is present
        if (saved.getPhone() != null && !saved.getPhone().isBlank()) {
            sendRegistrationSms(saved, ev);
        }

        return saved;
    }

    /**
     * Public auto-fill lookup for the registration page. Given the event token and
     * an email and/or phone the visitor typed, returns an existing registrant for the
     * SAME client as the event — first from {@code event_registration} (which also
     * carries adults/kids), otherwise from {@code family_member}. Returns
     * {@code {"found": false}} when nothing matches.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> lookupRegistrant(String token, String email, String phone) {
        Integer eventId = decryptToken(token);
        ChurchEvent ev  = eventRepo.findByIdAndDeleteFlagFalse(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Event not found"));
        String clientId = ev.getAppClientId();
        String em = trim(email);
        String ph = trim(phone);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", false);
        if ((em == null || em.isBlank()) && (ph == null || ph.isBlank())) return out;

        // 1) Existing registration for this client (richest match — has adults/kids).
        EventRegistration reg = null;
        if (em != null && !em.isBlank()) {
            reg = regRepo.findFirstByClientIdAndEmailIgnoreCaseOrderByCreatedDateDesc(clientId, em).orElse(null);
        }
        if (reg == null && ph != null && !ph.isBlank()) {
            reg = regRepo.findFirstByClientIdAndPhoneOrderByCreatedDateDesc(clientId, ph).orElse(null);
        }
        if (reg != null) {
            out.put("found", true);
            out.put("source", "registration");
            out.put("firstName", reg.getFirstName());
            out.put("lastName",  reg.getLastName());
            out.put("email",     reg.getEmail());
            out.put("phone",     reg.getPhone());
            out.put("adults",    reg.getAdults());
            out.put("kids",      reg.getKids());
            return out;
        }

        // 2) Otherwise a known member of the same church (no adults/kids on a member).
        FamilyMember fm = null;
        if (em != null && !em.isBlank()) fm = firstOrNull(memberRepo.findByPhoneOrEmailAndClient(em, clientId));
        if (fm == null && ph != null && !ph.isBlank()) fm = firstOrNull(memberRepo.findByPhoneOrEmailAndClient(ph, clientId));
        if (fm != null) {
            out.put("found", true);
            out.put("source", "member");
            out.put("firstName", fm.getFirstName());
            out.put("lastName",  fm.getLastName());
            out.put("email",     fm.getEmail());
            out.put("phone",     fm.getPhone());
            return out;
        }
        return out;
    }

    private static FamilyMember firstOrNull(List<FamilyMember> list) {
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    @Transactional
    public void deleteRegistration(Integer registrationId) {
        if (!regRepo.existsById(registrationId)) {
            throw new IllegalArgumentException("Registration not found: " + registrationId);
        }
        regRepo.deleteById(registrationId);
    }

    /**
     * Updates an existing RSVP identified by the registrant's email address.
     *
     * <p>The event is identified by its AES-encrypted token ({@code token}).
     * The registrant is looked up by {@code email} — which must match a previous
     * registration for that event.  Throws if no matching registration is found.
     *
     * @param token AES-encrypted event ID (same token used in the public registration URL)
     * @param email the registrant's email address
     * @param bo    updated registration details
     * @return the updated {@link EventRegistration}
     */
    @Transactional
    public EventRegistration updateRegistration(String token, String email, EventRegistrationBO bo) {
        Integer eventId = decryptToken(token);
        ChurchEvent ev  = eventRepo.findByIdAndDeleteFlagFalse(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Event not found"));

        EventRegistration reg = regRepo.findByEventIdAndEmailIgnoreCase(eventId, email.trim())
                .orElseThrow(() -> new IllegalArgumentException(
                        "No RSVP found for this email address. Please register first."));

        reg.setFirstName(trim(bo.getFirstName()));
        reg.setLastName(trim(bo.getLastName()));
        reg.setPhone(trim(bo.getPhone()));
        reg.setAdults(bo.getAdults());
        reg.setKids(bo.getKids());
        reg.setAttending(bo.getAttending());
        reg.setNote(bo.getNote());
        reg.setVegetarian(bo.getVegetarian());
        reg.setSelectedFoodItems(bo.getSelectedFoodItems());
        reg.setAttendingDays(bo.getAttendingDays());
        EventRegistration saved = regRepo.save(reg);

        // Resend confirmation email with updated RSVP details
        if (saved.getEmail() != null && !saved.getEmail().isBlank()) {
            sendRegistrationEmail(saved, ev);
        }

        // Resend confirmation SMS with updated RSVP details
        if (saved.getPhone() != null && !saved.getPhone().isBlank()) {
            sendRegistrationSms(saved, ev);
        }
        return saved;
    }

    // ── Email ─────────────────────────────────────────────────────────────

    private void sendRegistrationEmail(EventRegistration reg, ChurchEvent ev) {
        String firstName   = reg.getFirstName() != null ? reg.getFirstName() : "Friend";
        String eventName   = ev.getEventName()  != null ? ev.getEventName()  : "the event";
        Boolean attending  = reg.getAttending();   // null = maybe
        String churchName  = resolveChurchName(ev.getAppClientId());

        // Build the Update RSVP link — prefer the org's custom registration link when set.
        // For system-generated links, append ?email= so the form auto-populates on arrival.
        String registrantEmail = reg.getEmail() != null ? reg.getEmail().trim() : "";
        String rsvpLink = "";
        try {
            String token = EncryptionUtil.encrypt(String.valueOf(ev.getId()));
            rsvpLink = baseUrl + "/event-register/" + token
                     + (registrantEmail.isBlank() ? ""
                        : "?email=" + URLEncoder.encode(registrantEmail, StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
        if (ev.getRegistrationLink() != null && !ev.getRegistrationLink().isBlank()) {
            rsvpLink = ev.getRegistrationLink();
        }

        String subject;
        String html;
        if (Boolean.FALSE.equals(attending)) {
            subject = "We'll miss you – " + eventName;
            html    = buildNotAttendingEmail(firstName, eventName, ev, rsvpLink, churchName);
        } else {
            // Attending or "maybe" → confirmation rendered from the client's RSVP email
            // template (falls back to the built-in design on any error).
            subject = "Thank you for your RSVP – " + eventName;
            html    = buildTemplatedConfirmation(ev, reg, firstName, eventName, churchName, attending, rsvpLink);
        }

        emailService.sendGenericEmail(reg.getEmail(), subject, html, ev.getAppClientId());
    }

    // ── RSVP confirmation rendered from a customizable template ───────────────

    /** Renders the client's default RSVP email template, wrapped in the shared branded shell. */
    private String buildTemplatedConfirmation(ChurchEvent ev, EventRegistration reg, String firstName,
                                              String eventName, String churchName, Boolean attending,
                                              String rsvpLink) {
        try {
            String body    = eventEmailTemplateService.getEffectiveDefaultBody(ev.getAppClientId());
            String content = renderTemplateToHtml(body, ev, reg);
            content += buildRsvpButton(rsvpLink);   // keep the "Update RSVP" action
            String statusLabel = Boolean.TRUE.equals(attending) ? "RSVP Confirmed" : "RSVP Received";
            String emoji       = Boolean.TRUE.equals(attending) ? "&#127881;" : "&#128499;&#65039;";  // 🎉 / 🗓️
            return buildEmailShell("RSVP Confirmation", emoji, statusLabel, esc(eventName), esc(churchName), content);
        } catch (Exception e) {
            // Never fail the confirmation email — fall back to the built-in design.
            return Boolean.TRUE.equals(attending)
                    ? buildAttendingEmail(firstName, eventName, ev, rsvpLink, churchName)
                    : buildMaybeEmail(firstName, eventName, ev, rsvpLink, churchName);
        }
    }

    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\{([^}]+)\\}");

    /** Substitutes the RSVP placeholders into the template body and returns HTML paragraphs. */
    private String renderTemplateToHtml(String body, ChurchEvent ev, EventRegistration reg) {
        String name = ((reg.getFirstName() != null ? reg.getFirstName() : "") + " "
                     + (reg.getLastName()  != null ? reg.getLastName()  : "")).trim();
        if (name.isEmpty()) name = "Friend";
        String dateStr = ("One Day".equals(ev.getEventType()) && ev.getEventDate() != null)
                ? ev.getEventDate().format(DateTimeFormatter.ofPattern("MMMM d, yyyy")) : "";
        String timeStr = "";
        if (ev.getStartTime() != null && !ev.getStartTime().isBlank()) {
            timeStr = to12Hour(ev.getStartTime());
            if (ev.getEndTime() != null && !ev.getEndTime().isBlank() && !ev.getEndTime().equals(ev.getStartTime())) {
                timeStr += " - " + to12Hour(ev.getEndTime());
            }
        }

        java.util.Map<String, String> text = new java.util.LinkedHashMap<>();
        text.put("Registrant Name", esc(name));
        text.put("Event Name",      esc(ev.getEventName() != null ? ev.getEventName() : "the event"));
        text.put("Date",            esc(dateStr));
        text.put("Time",            esc(timeStr));
        text.put("Location",        buildEventLocation(ev));   // already HTML-escaped
        text.put("Contact",         buildContactPlain(ev));    // already HTML-escaped
        java.util.Map<String, String> htmlBtn = new java.util.LinkedHashMap<>();
        htmlBtn.put("Google Calendar Link", calendarButton(ev));
        htmlBtn.put("Directions Link",      directionsButton(ev));

        StringBuilder out = new StringBuilder();
        for (String para : (body == null ? "" : body).replace("\r\n", "\n").trim().split("\n\\s*\n")) {
            StringBuilder pBuf = new StringBuilder();
            boolean paraHasText = false;
            for (String raw : para.split("\n")) {
                String rendered = renderLine(raw, text, htmlBtn);
                if (rendered == null) continue;
                if (pBuf.length() > 0) pBuf.append("<br>");
                pBuf.append(rendered);
                if (!rendered.replaceAll("<[^>]+>", "").trim().isEmpty()) paraHasText = true;
            }
            if (pBuf.length() == 0) continue;
            if (paraHasText) {
                out.append("<p style='font-size:14px;color:#444;line-height:1.7;margin:0 0 16px;'>")
                   .append(pBuf).append("</p>");
            } else {
                out.append("<div style='margin:0 0 16px;text-align:center;'>").append(pBuf).append("</div>");
            }
        }
        return out.toString();
    }

    /** Renders one template line; returns null to drop a line of only empty placeholders / a dangling label. */
    private String renderLine(String raw, java.util.Map<String, String> text, java.util.Map<String, String> htmlBtn) {
        java.util.regex.Matcher m = PLACEHOLDER.matcher(raw);
        boolean anyKnown = false, allEmpty = true;
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(esc(raw.substring(last, m.start())));
            String key = m.group(1).trim();
            if (text.containsKey(key)) {
                anyKnown = true;
                String v = text.get(key);
                if (!v.isEmpty()) allEmpty = false;
                sb.append(v);                     // already escaped
            } else if (htmlBtn.containsKey(key)) {
                anyKnown = true;
                String v = htmlBtn.get(key);
                if (!v.isEmpty()) allEmpty = false;
                sb.append(v);                     // raw HTML button
            } else {
                sb.append(esc(m.group(0)));       // unknown placeholder — keep literal
            }
            last = m.end();
        }
        sb.append(esc(raw.substring(last)));
        String literal = PLACEHOLDER.matcher(raw).replaceAll("").trim();   // non-placeholder text on the line
        if (anyKnown && allEmpty && (literal.isEmpty() || literal.endsWith(":"))) {
            return null;
        }
        return sb.toString();
    }

    private String buildContactPlain(ChurchEvent ev) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (ev.getHostName()  != null && !ev.getHostName().isBlank())  parts.add(esc(ev.getHostName().trim()));
        if (ev.getHostPhone() != null && !ev.getHostPhone().isBlank()) parts.add(esc(PhoneUtil.format(ev.getHostPhone())));
        if (ev.getHostEmail() != null && !ev.getHostEmail().isBlank()) parts.add(esc(ev.getHostEmail().trim()));
        return String.join(" &middot; ", parts);
    }

    /** Comma-joined address (unescaped) for URL building. */
    private String eventLocationRaw(ChurchEvent ev) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (ev.getAddress1() != null && !ev.getAddress1().isBlank()) parts.add(ev.getAddress1().trim());
        if (ev.getAddress2() != null && !ev.getAddress2().isBlank()) parts.add(ev.getAddress2().trim());
        if (ev.getCity()     != null && !ev.getCity().isBlank())     parts.add(ev.getCity().trim());
        if (ev.getState()    != null && !ev.getState().isBlank()) {
            String s = ev.getState().trim();
            try { String a = States.getCode(Integer.parseInt(s)); if (a != null) s = a; } catch (NumberFormatException ignored) {}
            parts.add(s);
        }
        if (ev.getPinCode()  != null && !ev.getPinCode().isBlank())  parts.add(ev.getPinCode().trim());
        if (ev.getCountry()  != null && !ev.getCountry().isBlank() && !"USA".equalsIgnoreCase(ev.getCountry()))
            parts.add(ev.getCountry().trim());
        return String.join(", ", parts);
    }

    private String calendarButton(ChurchEvent ev) {
        if (ev.getEventDate() == null) return "";
        try {
            String enc = StandardCharsets.UTF_8.name();
            LocalDate date = ev.getEventDate();
            String dateStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String dtStart, dtEnd;
            if (ev.getStartTime() != null && !ev.getStartTime().isBlank()) {
                dtStart = dateStr + "T" + ev.getStartTime().replace(":", "") + "00";
                if (ev.getEndTime() != null && !ev.getEndTime().isBlank()) {
                    dtEnd = dateStr + "T" + ev.getEndTime().replace(":", "") + "00";
                } else {
                    LocalTime st = LocalTime.parse(ev.getStartTime());
                    dtEnd = dateStr + "T" + st.plusHours(1).format(DateTimeFormatter.ofPattern("HHmm")) + "00";
                }
            } else {
                dtStart = dateStr;
                dtEnd   = date.plusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            }
            String name = ev.getEventName() != null ? ev.getEventName() : "";
            String link = "https://calendar.google.com/calendar/render?action=TEMPLATE"
                    + "&text="     + URLEncoder.encode(name, enc)
                    + "&dates="    + dtStart + "/" + dtEnd
                    + "&details="  + URLEncoder.encode("You are registered for " + name, enc)
                    + "&location=" + URLEncoder.encode(eventLocationRaw(ev), enc);
            return "<a href='" + link + "' target='_blank' style='display:inline-block;margin:4px;padding:12px 22px;"
                    + "background:#4285F4;color:#ffffff;border-radius:8px;font-size:13px;font-weight:700;"
                    + "text-decoration:none;'>&#128197; Add to Google Calendar</a>";
        } catch (Exception e) { return ""; }
    }

    private String directionsButton(ChurchEvent ev) {
        String loc = eventLocationRaw(ev);
        if (loc.isEmpty()) return "";
        try {
            String link = "https://www.google.com/maps/dir/?api=1&destination="
                    + URLEncoder.encode(loc, StandardCharsets.UTF_8.name());
            return "<a href='" + link + "' target='_blank' style='display:inline-block;margin:4px;padding:12px 22px;"
                    + "background:#34A853;color:#ffffff;border-radius:8px;font-size:13px;font-weight:700;"
                    + "text-decoration:none;'>&#128205; Get Directions</a>";
        } catch (Exception e) { return ""; }
    }

    // ── SMS ───────────────────────────────────────────────────────────────

    private void sendRegistrationSms(EventRegistration reg, ChurchEvent ev) {
        if (!smsService.isConfigured()) return;

        String firstName  = reg.getFirstName() != null ? reg.getFirstName() : "Friend";
        String eventName  = ev.getEventName()  != null ? ev.getEventName()  : "the event";
        String churchName = resolveChurchName(ev.getAppClientId());
        Boolean attending = reg.getAttending();

        // Build public view link (event-register page with registration section hidden)
        String viewLink = "";
        try {
            String token = EncryptionUtil.encrypt(String.valueOf(ev.getId()));
            viewLink = baseUrl + "/event-register/" + token + "?view=1";
        } catch (Exception ignored) {}

        // Date string for Yes/Maybe messages
        String datePart = "";
        if ("One Day".equals(ev.getEventType()) && ev.getEventDate() != null) {
            datePart = " on " + ev.getEventDate().format(
                    java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy"));
            if (ev.getStartTime() != null && !ev.getStartTime().isBlank()) {
                datePart += " at " + to12Hour(ev.getStartTime());
            }
        }

        String body;
        if (Boolean.TRUE.equals(attending)) {
            body = churchName + ": Thank you for registering for " + eventName + datePart + "."
                 + (viewLink.isBlank() ? "" : " View event details: " + viewLink)
                 + " Reply STOP to opt out.";
        } else if (Boolean.FALSE.equals(attending)) {
            body = churchName + ": Sorry we will miss you at " + eventName + "."
                 + " Reply STOP to opt out.";
        } else {
            // Maybe
            body = churchName + ": Thank you for your RSVP. You selected 'Maybe' for "
                 + eventName + datePart + "."
                 + (viewLink.isBlank() ? "" : " View event details: " + viewLink)
                 + " Reply STOP to opt out.";
        }

        smsService.send(reg.getPhone().trim(), body);
    }

    // ── Shared email wrapper ──────────────────────────────────────────────────

    /** Wraps the per-variant content blocks in the shared outer shell. */
    private String buildEmailShell(String title, String headerEmoji,
                                    String statusLabel, String safeEvent,
                                    String safeChurch, String bodyContent) {
        return "<!DOCTYPE html>"
             + "<html lang='en'>"
             + "<head>"
             + "<meta charset='UTF-8'/>"
             + "<meta name='viewport' content='width=device-width,initial-scale=1.0'/>"
             + "<title>" + title + "</title>"
             + "</head>"
             + "<body style='margin:0;padding:0;background:#f0f2f5;"
             +   "font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,Helvetica,Arial,sans-serif;'>"

             // ── Outer wrapper ────────────────────────────────────────────────
             + "<table width='100%' cellpadding='0' cellspacing='0' role='presentation'"
             +   " style='background:#f0f2f5;padding:32px 16px;'>"
             + "<tr><td align='center'>"
             + "<table width='100%' cellpadding='0' cellspacing='0' role='presentation'"
             +   " style='max-width:580px;background:#ffffff;border-radius:16px;"
             +   "overflow:hidden;box-shadow:0 4px 24px rgba(0,0,0,0.08);'>"

             // ── Header banner ────────────────────────────────────────────────
             + "<tr><td style='background:linear-gradient(135deg,#673147 0%,#8d3f5f 100%);"
             +   "padding:36px 32px 28px;text-align:center;'>"
             + "<div style='font-size:42px;line-height:1;margin-bottom:14px;'>" + headerEmoji + "</div>"
             + "<h1 style='color:#ffffff;font-size:22px;font-weight:700;margin:0 0 10px;"
             +   "line-height:1.3;letter-spacing:-0.3px;'>" + safeEvent + "</h1>"

             // Status badge
             + "<div style='display:inline-block;background:rgba(255,255,255,0.18);"
             +   "border:1px solid rgba(255,255,255,0.35);border-radius:20px;"
             +   "padding:5px 16px;margin-bottom:8px;'>"
             + "<span style='color:#ffffff;font-size:12px;font-weight:600;"
             +   "letter-spacing:0.5px;text-transform:uppercase;'>" + statusLabel + "</span>"
             + "</div>"

             + "<p style='color:rgba(255,255,255,0.75);font-size:13px;margin:6px 0 0;'>" + safeChurch + "</p>"
             + "</td></tr>"

             // ── Body ─────────────────────────────────────────────────────────
             + "<tr><td style='padding:32px 32px 24px;'>"
             + bodyContent
             + "</td></tr>"

             // ── Footer ───────────────────────────────────────────────────────
             + "<tr><td style='background:#f8f9fb;border-top:1px solid #eeeff2;"
             +   "padding:18px 32px;text-align:center;'>"
             + "<p style='margin:0;font-size:11px;color:#aaa;line-height:1.6;'>"
             + "This is an automated message &mdash; please do not reply directly to this email."
             + "</p>"
             + "</td></tr>"

             + "</table>"
             + "</td></tr></table>"
             + "</body></html>";
    }

    private String buildAttendingEmail(String firstName, String eventName,
                                        ChurchEvent ev, String rsvpLink, String churchName) {
        String safe       = esc(firstName);
        String safeEvent  = esc(eventName);
        String safeChurch = esc(churchName);
        String dateInfo   = buildDateInfo(ev);
        String location   = buildEventLocation(ev);

        String body = "<p style='font-size:16px;color:#1a1a2e;margin:0 0 6px;'>"
             + "Hi <strong>" + safe + "</strong>,</p>"
             + "<p style='font-size:14px;color:#555;margin:0 0 24px;line-height:1.6;'>"
             + "Thank you for your RSVP! We&apos;re excited to see you at "
             + "<strong style='color:#673147;'>" + safeEvent + "</strong>.</p>"
             + buildEventInfoCards(dateInfo, location)
             + buildHostContact(ev)
             + buildCalendarAndDirectionButtons(ev)
             + buildRsvpButton(rsvpLink);

        return buildEmailShell("RSVP Confirmed", "🎉", "RSVP Confirmed", safeEvent, safeChurch, body);
    }

    private String buildMaybeEmail(String firstName, String eventName,
                                    ChurchEvent ev, String rsvpLink, String churchName) {
        String safe       = esc(firstName);
        String safeEvent  = esc(eventName);
        String safeChurch = esc(churchName);
        String dateInfo   = buildDateInfo(ev);
        String location   = buildEventLocation(ev);

        String body = "<p style='font-size:16px;color:#1a1a2e;margin:0 0 6px;'>"
             + "Hi <strong>" + safe + "</strong>,</p>"
             + "<p style='font-size:14px;color:#555;margin:0 0 24px;line-height:1.6;'>"
             + "Thanks for your RSVP to <strong style='color:#673147;'>" + safeEvent + "</strong>. "
             + "We&apos;ve noted that you might attend &mdash; we hope to see you there!</p>"
             + buildEventInfoCards(dateInfo, location)
             + buildHostContact(ev)
             + buildCalendarAndDirectionButtons(ev)
             + buildRsvpButton(rsvpLink);

        return buildEmailShell("RSVP Received", "🤔", "Maybe Attending", safeEvent, safeChurch, body);
    }

    private String buildNotAttendingEmail(String firstName, String eventName,
                                           ChurchEvent ev, String rsvpLink, String churchName) {
        String safe       = esc(firstName);
        String safeEvent  = esc(eventName);
        String safeChurch = esc(churchName);
        String location   = buildEventLocation(ev);

        String body = "<p style='font-size:16px;color:#1a1a2e;margin:0 0 6px;'>"
             + "Hi <strong>" + safe + "</strong>,</p>"
             + "<p style='font-size:14px;color:#555;margin:0 0 24px;line-height:1.6;'>"
             + "We&apos;re sorry to hear you won&apos;t be joining us at "
             + "<strong style='color:#673147;'>" + safeEvent + "</strong>. "
             + "Changed your mind? You can always update your RSVP below.</p>"
             + (location.isEmpty() ? "" : buildEventInfoCards("", location))
             + buildHostContact(ev)
             + buildRsvpButton(rsvpLink);

        return buildEmailShell("We'll Miss You", "💙", "Not Attending", safeEvent, safeChurch, body);
    }

    /**
     * Builds modern info cards for date/time and location.
     * Each card has an icon column and a text column.
     */
    private String buildEventInfoCards(String dateInfo, String location) {
        if (dateInfo.isEmpty() && location.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("<table width='100%' cellpadding='0' cellspacing='0' role='presentation'"
                + " style='margin:0 0 24px;border-radius:10px;overflow:hidden;"
                + "border:1px solid #e8eaf0;'>");

        if (!dateInfo.isEmpty()) {
            sb.append("<tr>")
              .append("<td style='background:#f7f3ff;padding:14px 16px;width:48px;"
                    + "text-align:center;border-bottom:" + (location.isEmpty() ? "none" : "1px solid #e8eaf0") + ";'>")
              .append("<span style='font-size:22px;'>&#128197;</span>")
              .append("</td>")
              .append("<td style='padding:14px 16px;font-size:14px;color:#333;"
                    + "line-height:1.5;border-bottom:" + (location.isEmpty() ? "none" : "1px solid #e8eaf0") + ";'>")
              .append("<div style='font-size:11px;font-weight:700;text-transform:uppercase;"
                    + "letter-spacing:0.6px;color:#888;margin-bottom:3px;'>Date &amp; Time</div>")
              .append("<div style='font-weight:600;color:#1a1a2e;'>").append(dateInfo).append("</div>")
              .append("</td>")
              .append("</tr>");
        }

        if (!location.isEmpty()) {
            sb.append("<tr>")
              .append("<td style='background:#f7f3ff;padding:14px 16px;width:48px;text-align:center;'>")
              .append("<span style='font-size:22px;'>&#128205;</span>")
              .append("</td>")
              .append("<td style='padding:14px 16px;font-size:14px;color:#333;line-height:1.5;'>")
              .append("<div style='font-size:11px;font-weight:700;text-transform:uppercase;"
                    + "letter-spacing:0.6px;color:#888;margin-bottom:3px;'>Location</div>")
              .append("<div style='font-weight:600;color:#1a1a2e;'>").append(location).append("</div>")
              .append("</td>")
              .append("</tr>");
        }

        sb.append("</table>");
        return sb.toString();
    }

    /**
     * Builds the "Update RSVP" button block.
     * No-ops (returns empty string) when the link is blank.
     */
    private String buildRsvpButton(String rsvpLink) {
        if (rsvpLink == null || rsvpLink.isBlank()) return "";
        return "<table width='100%' cellpadding='0' cellspacing='0' role='presentation'"
             + " style='margin:8px 0 0;'><tr><td align='center'>"
             + "<a href='" + rsvpLink + "' target='_blank'"
             + " style='display:inline-block;padding:13px 32px;background:#673147;"
             + "color:#ffffff;font-size:14px;font-weight:700;border-radius:8px;"
             + "text-decoration:none;letter-spacing:0.3px;'>&#10004; Update My RSVP</a>"
             + "</td></tr>"
             + "<tr><td align='center' style='padding-top:10px;'>"
             + "<span style='font-size:11px;color:#bbb;'>or copy: </span>"
             + "<a href='" + rsvpLink + "' style='font-size:11px;color:#673147;"
             + "word-break:break-all;'>" + rsvpLink + "</a>"
             + "</td></tr></table>";
    }

    private String buildDateInfo(ChurchEvent ev) {
        if ("One Day".equals(ev.getEventType()) && ev.getEventDate() != null) {
            String d = ev.getEventDate().format(
                    DateTimeFormatter.ofPattern("MMM d, yyyy"));
            String tStart = (ev.getStartTime() != null && !ev.getStartTime().isBlank())
                          ? " at " + to12Hour(ev.getStartTime()) : "";
            String tEnd   = (ev.getEndTime()   != null && !ev.getEndTime().isBlank()
                          && !ev.getEndTime().equals(ev.getStartTime()))
                          ? " &ndash; " + to12Hour(ev.getEndTime()) : "";
            return esc(d) + esc(tStart) + tEnd;
        }
        return "";
    }

    /**
     * Converts a 24-hour time string (e.g. "17:00" or "09:30") to 12-hour format
     * (e.g. "5:00 PM" or "9:30 AM").  Returns the original string on any parse error.
     */
    private String to12Hour(String time24) {
        if (time24 == null || time24.isBlank()) return time24;
        try {
            LocalTime lt = LocalTime.parse(time24.trim(),
                    DateTimeFormatter.ofPattern("H:mm"));
            return lt.format(DateTimeFormatter.ofPattern("h:mm a"));
        } catch (Exception ignored) {
            return time24;
        }
    }

    /**
     * Formats the event's address fields into a single comma-separated line.
     * Returns an empty string when no address fields are populated.
     */
    private String buildEventLocation(ChurchEvent ev) {
        StringBuilder parts = new StringBuilder();
        appendPart(parts, ev.getAddress1(), "");
        appendPart(parts, ev.getAddress2(), ", ");
        appendPart(parts, ev.getCity(),     ", ");
        // Resolve numeric state code → two-letter abbreviation (e.g. "16" → "KS")
        if (ev.getState() != null && !ev.getState().isBlank()) {
            String stateVal = ev.getState().trim();
            try {
                String abbr = States.getCode(Integer.parseInt(stateVal));
                if (abbr != null) stateVal = abbr;
            } catch (NumberFormatException ignored) { /* already an abbreviation */ }
            appendPart(parts, stateVal, ", ");
        }
        appendPart(parts, ev.getPinCode(),  " ");
        // Only show country when it's not the default (USA)
        if (ev.getCountry() != null && !ev.getCountry().isBlank()
                && !"USA".equalsIgnoreCase(ev.getCountry())) {
            appendPart(parts, ev.getCountry(), ", ");
        }
        return parts.toString();
    }

    private void appendPart(StringBuilder sb, String value, String separator) {
        if (value == null || value.isBlank()) return;
        if (sb.length() > 0) sb.append(separator);
        sb.append(esc(value.trim()));
    }

    private String buildHostContact(ChurchEvent ev) {
        if (ev.getHostName() == null && ev.getHostPhone() == null && ev.getHostEmail() == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("<table width='100%' cellpadding='0' cellspacing='0' role='presentation'"
                + " style='margin:0 0 20px;border-radius:10px;overflow:hidden;"
                + "border:1px solid #e8eaf0;'><tr>")
          .append("<td style='background:#f7f3ff;padding:14px 16px;width:48px;text-align:center;'>")
          .append("<span style='font-size:22px;'>&#9742;</span>")
          .append("</td>")
          .append("<td style='padding:14px 16px;font-size:14px;color:#333;'>")
          .append("<div style='font-size:11px;font-weight:700;text-transform:uppercase;"
                + "letter-spacing:0.6px;color:#888;margin-bottom:3px;'>Contact</div>");
        if (ev.getHostName()  != null) sb.append("<div style='font-weight:600;color:#1a1a2e;'>").append(esc(ev.getHostName())).append("</div>");
        if (ev.getHostPhone() != null) sb.append("<div style='color:#555;'>&#128222; ").append(esc(PhoneUtil.format(ev.getHostPhone()))).append("</div>");
        if (ev.getHostEmail() != null) sb.append("<div style='color:#555;'>&#9993; ").append(esc(ev.getHostEmail())).append("</div>");
        sb.append("</td></tr></table>");
        return sb.toString();
    }

    /**
     * Builds a button row with "Add to Google Calendar" and optionally "Get Directions".
     * Returns empty string on any error or when the event has no date.
     */
    private String buildCalendarAndDirectionButtons(ChurchEvent ev) {
        if (ev.getEventDate() == null) return "";
        try {
            String enc      = StandardCharsets.UTF_8.name();
            LocalDate date  = ev.getEventDate();
            String dateStr  = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String dtStart, dtEnd;
            if (ev.getStartTime() != null && !ev.getStartTime().isBlank()) {
                dtStart = dateStr + "T" + ev.getStartTime().replace(":", "") + "00";
                if (ev.getEndTime() != null && !ev.getEndTime().isBlank()) {
                    dtEnd = dateStr + "T" + ev.getEndTime().replace(":", "") + "00";
                } else {
                    LocalTime st = LocalTime.parse(ev.getStartTime());
                    dtEnd = dateStr + "T"
                          + st.plusHours(1).format(DateTimeFormatter.ofPattern("HHmm")) + "00";
                }
            } else {
                dtStart = dateStr;
                dtEnd   = date.plusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            }

            String name    = ev.getEventName() != null ? ev.getEventName() : "";
            String details = "You are registered for " + name;
            String locStr  = buildEventLocation(ev);

            String googleLink = "https://calendar.google.com/calendar/render?action=TEMPLATE"
                    + "&text="     + URLEncoder.encode(name,    enc)
                    + "&dates="    + dtStart + "/" + dtEnd
                    + "&details="  + URLEncoder.encode(details, enc)
                    + "&location=" + URLEncoder.encode(locStr,  enc);

            boolean hasLoc = !locStr.isEmpty();
            String mapsLink = hasLoc
                    ? "https://www.google.com/maps/dir/?api=1&destination=" + URLEncoder.encode(locStr, enc)
                    : "";

            // Button row — calendar always shown, directions only when address exists
            StringBuilder html = new StringBuilder();
            html.append("<table width='100%' cellpadding='0' cellspacing='0' role='presentation'"
                    + " style='margin:0 0 20px;'><tr>");

            // Calendar button
            String calTdStyle = hasLoc
                    ? "padding:0 8px 0 0;width:50%;"
                    : "padding:0;";
            html.append("<td align='center' style='" + calTdStyle + "'>"
                    + "<a href='" + googleLink + "' target='_blank'"
                    + " style='display:block;padding:12px 8px;background:#4285F4;color:#ffffff;"
                    + "border-radius:8px;font-size:13px;font-weight:700;text-decoration:none;"
                    + "text-align:center;letter-spacing:.2px;'>&#128197; Add to Google Calendar</a>"
                    + "</td>");

            // Directions button
            if (hasLoc) {
                html.append("<td align='center' style='padding:0;width:50%;'>"
                        + "<a href='" + mapsLink + "' target='_blank'"
                        + " style='display:block;padding:12px 8px;background:#34A853;color:#ffffff;"
                        + "border-radius:8px;font-size:13px;font-weight:700;text-decoration:none;"
                        + "text-align:center;letter-spacing:.2px;'>&#128205; Get Directions</a>"
                        + "</td>");
            }

            html.append("</tr></table>");
            return html.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private void applyBO(ChurchEvent ev, ChurchEventBO bo) {
        ev.setEventName(bo.getEventName() != null ? bo.getEventName().trim() : "");
        ev.setEventCode(bo.getEventCode() != null && !bo.getEventCode().isBlank()
                        ? bo.getEventCode().trim() : null);
        ev.setEventType(bo.getEventType());

        // One Day fields — clear for Multiple Days
        if ("One Day".equals(bo.getEventType())) {
            ev.setEventDate(parseDate(bo.getEventDate()));
            ev.setStartTime(bo.getStartTime());
            ev.setEndTime(bo.getEndTime());
        } else {
            ev.setEventDate(null);
            ev.setStartTime(null);
            ev.setEndTime(null);
        }

        ev.setRegistrationEndDate(parseDate(bo.getRegistrationEndDate()));
        ev.setRegistrationLink(bo.getRegistrationLink());
        ev.setFee(bo.getFee());
        ev.setMaxCapacity(bo.getMaxCapacity());
        ev.setShowRegistrants(bo.getShowRegistrants() == null || bo.getShowRegistrants());
        ev.setAllowMaybeRsvp(Boolean.TRUE.equals(bo.getAllowMaybeRsvp()));
        ev.setGenerateQrCode(Boolean.TRUE.equals(bo.getGenerateQrCode()));
        ev.setGenerateRegistrationId(Boolean.TRUE.equals(bo.getGenerateRegistrationId()));
        ev.setSelfCheckinEnabled(Boolean.TRUE.equals(bo.getSelfCheckinEnabled()));
        ev.setAddress1(bo.getAddress1());
        ev.setAddress2(bo.getAddress2());
        ev.setCity(bo.getCity());
        ev.setState(bo.getState());
        ev.setCountry(bo.getCountry() != null && !bo.getCountry().isBlank() ? bo.getCountry() : "USA");
        ev.setPinCode(bo.getPinCode());
        ev.setHostName(bo.getHostName());
        ev.setHostPhone(bo.getHostPhone());
        ev.setHostEmail(bo.getHostEmail());
        ev.setHostNote(bo.getHostNote());
        ev.setNote(bo.getNote());
        ev.setFoodAvailable(Boolean.TRUE.equals(bo.getFoodAvailable()));
        ev.setFoodItems(Boolean.TRUE.equals(bo.getFoodAvailable()) ? bo.getFoodItems() : null);
        String foodLabel = bo.getFoodLabel() != null ? bo.getFoodLabel().trim() : null;
        ev.setFoodLabel(Boolean.TRUE.equals(bo.getFoodAvailable()) && foodLabel != null && !foodLabel.isEmpty()
                ? foodLabel : null);
        ev.setAccommodationAvailable(Boolean.TRUE.equals(bo.getAccommodationAvailable()));
        ev.setAccommodationAddress(bo.getAccommodationAddress());
        ev.setAccommodationComments(bo.getAccommodationComments());
        // Only overwrite the image when a new one is supplied. A null payload means
        // "no change", so editing an event without re-uploading preserves the image.
        if (bo.getImageData() != null) {
            ev.setImageData(bo.getImageData());
        }
    }

    private void saveDays(Integer eventId, ChurchEventBO bo) {
        if ("Multiple Days".equals(bo.getEventType())
                && bo.getDays() != null && !bo.getDays().isEmpty()) {
            int order = 1;
            for (ChurchEventBO.DayBO d : bo.getDays()) {
                ChurchEventDay day = new ChurchEventDay();
                day.setEventId(eventId);
                day.setEventDate(parseDate(d.getEventDate()));
                day.setStartTime(d.getStartTime());
                day.setEndTime(d.getEndTime());
                day.setFoodAvailable(Boolean.TRUE.equals(d.getFoodAvailable()));
                day.setDayOrder(order++);
                dayRepo.save(day);
            }
        }
    }

    private List<Map<String, Object>> getDaysForEvent(Integer eventId) {
        return dayRepo.findByEventIdOrderByDayOrderAsc(eventId)
                .stream()
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id",            d.getId());
                    m.put("eventDate",     d.getEventDate() != null ? d.getEventDate().toString() : null);
                    m.put("startTime",     d.getStartTime());
                    m.put("endTime",       d.getEndTime());
                    m.put("dayOrder",      d.getDayOrder());
                    m.put("foodAvailable", d.isFoodAvailable());
                    return m;
                })
                .collect(Collectors.toList());
    }

    private Map<String, Object> toMapWithCounts(ChurchEvent ev) {
        Map<String, Object> m = toMap(ev);
        m.put("adultsCount", regRepo.sumAdultsByEventId(ev.getId()));
        m.put("kidsCount",   regRepo.sumKidsByEventId(ev.getId()));
        m.put("days",        getDaysForEvent(ev.getId()));
        return m;
    }

    private Map<String, Object> toMap(ChurchEvent ev) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                  ev.getId());
        m.put("eventName",           ev.getEventName());
        m.put("eventCode",           ev.getEventCode());
        try { m.put("encryptedId", EncryptionUtil.encrypt(String.valueOf(ev.getId()))); }
        catch (Exception ignored) { m.put("encryptedId", null); }
        m.put("eventType",           ev.getEventType());
        m.put("eventDate",           ev.getEventDate()           != null ? ev.getEventDate().toString()           : null);
        m.put("startTime",           ev.getStartTime());
        m.put("endTime",             ev.getEndTime());
        m.put("registrationEndDate", ev.getRegistrationEndDate() != null ? ev.getRegistrationEndDate().toString() : null);
        m.put("registrationLink",    ev.getRegistrationLink());
        m.put("fee",                 ev.getFee());
        m.put("maxCapacity",         ev.getMaxCapacity());
        m.put("showRegistrants",     ev.isShowRegistrants());
        m.put("allowMaybeRsvp",      ev.isAllowMaybeRsvp());
        m.put("generateQrCode",         ev.isGenerateQrCode());
        m.put("generateRegistrationId", ev.isGenerateRegistrationId());
        m.put("selfCheckinEnabled",     ev.isSelfCheckinEnabled());
        m.put("address1",            ev.getAddress1());
        m.put("address2",            ev.getAddress2());
        m.put("city",                ev.getCity());
        m.put("state",               ev.getState());
        m.put("country",             ev.getCountry());
        m.put("pinCode",             ev.getPinCode());
        m.put("hostName",            ev.getHostName());
        m.put("hostPhone",           ev.getHostPhone());
        m.put("hostEmail",           ev.getHostEmail());
        m.put("hostNote",            ev.getHostNote());
        m.put("note",                    ev.getNote());
        m.put("foodAvailable",           ev.isFoodAvailable());
        m.put("foodItems",               ev.getFoodItems());
        m.put("foodLabel",               ev.getFoodLabel());
        m.put("accommodationAvailable",  ev.isAccommodationAvailable());
        m.put("accommodationAddress",    ev.getAccommodationAddress());
        m.put("accommodationComments",   ev.getAccommodationComments());
        // Do NOT inline the base64 image in list/detail payloads — it bloats the
        // response. Callers load it via GET /api/event-register/{encryptedId}/image.
        m.put("hasImage",                ev.getImageData() != null && !ev.getImageData().isBlank());
        m.put("appClientId",             ev.getAppClientId());
        return m;
    }

    private Map<String, Object> regToMap(EventRegistration r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",        r.getId());
        m.put("eventId",   r.getEventId());
        m.put("firstName", r.getFirstName());
        m.put("lastName",  r.getLastName());
        m.put("email",     r.getEmail());
        m.put("phone",     r.getPhone());
        m.put("adults",    r.getAdults());
        m.put("kids",      r.getKids());
        m.put("attending",    r.getAttending());
        m.put("note",         r.getNote());
        m.put("vegetarian",        r.getVegetarian());
        m.put("selectedFoodItems", r.getSelectedFoodItems());
        m.put("attendingDays",     r.getAttendingDays());
        m.put("registrationCode",  r.getRegistrationCode());
        m.put("checkedIn",         r.isCheckedIn());
        m.put("checkedInAt",       r.getCheckedInAt());
        m.put("address1",          r.getAddress1());
        m.put("address2",          r.getAddress2());
        m.put("city",              r.getCity());
        m.put("state",             r.getState());
        m.put("zipCode",           r.getZipCode());
        m.put("walkIn",            r.isWalkIn());
        m.put("createdDate",       r.getCreatedDate());
        return m;
    }

    // ── Self Check-In ─────────────────────────────────────────────────────

    /**
     * Marks a registration as checked in.
     *
     * @param registrationCode the unique code stored in the QR / check-in link
     * @return the updated {@link EventRegistration}
     * @throws IllegalArgumentException when the code is not found
     */
    @Transactional
    public EventRegistration checkIn(String registrationCode) {
        EventRegistration reg = regRepo.findByRegistrationCode(registrationCode)
                .orElseThrow(() -> new IllegalArgumentException("Registration not found"));
        if (!reg.isCheckedIn()) {
            reg.setCheckedIn(true);
            reg.setCheckedInAt(new Date());
            regRepo.save(reg);
        }
        return reg;
    }

    // ── Admin Check-In (by registration ID) ──────────────────────────────

    /**
     * Marks a registration as checked in by its numeric ID.
     * Used by the admin event check-in page.
     *
     * @param registrationId the primary key of the registration
     * @return the updated {@link EventRegistration}
     * @throws IllegalArgumentException when the ID is not found
     */
    @Transactional
    public EventRegistration checkInById(Integer registrationId) {
        EventRegistration reg = regRepo.findById(registrationId)
                .orElseThrow(() -> new IllegalArgumentException("Registration not found: " + registrationId));
        if (!reg.isCheckedIn()) {
            reg.setCheckedIn(true);
            reg.setCheckedInAt(new Date());
            regRepo.save(reg);
        }
        return reg;
    }

    /**
     * Undoes a check-in for a registration by its numeric ID.
     *
     * @param registrationId the primary key of the registration
     * @return the updated {@link EventRegistration}
     */
    @Transactional
    public EventRegistration uncheckInById(Integer registrationId) {
        EventRegistration reg = regRepo.findById(registrationId)
                .orElseThrow(() -> new IllegalArgumentException("Registration not found: " + registrationId));
        reg.setCheckedIn(false);
        reg.setCheckedInAt(null);
        return regRepo.save(reg);
    }

    /**
     * Registers a walk-in attendee and immediately marks them checked in.
     * Used by the admin event check-in page.
     *
     * @param eventId numeric event ID
     * @param bo      registration details
     * @return the saved {@link EventRegistration}
     */
    @Transactional
    public EventRegistration adminWalkIn(Integer eventId, EventRegistrationBO bo) {
        ChurchEvent ev = eventRepo.findByIdAndDeleteFlagFalse(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Event not found: " + eventId));

        EventRegistration reg = new EventRegistration();
        reg.setEventId(eventId);
        reg.setClientId(ev.getAppClientId());   // tenant of the parent event, for per-client filtering/reporting
        reg.setFirstName(trim(bo.getFirstName()));
        reg.setLastName(trim(bo.getLastName()));
        reg.setEmail(trim(bo.getEmail()));
        reg.setPhone(trim(bo.getPhone()));
        reg.setAdults(bo.getAdults() > 0 ? bo.getAdults() : 1);
        reg.setKids(bo.getKids());
        reg.setAttending(true);
        reg.setNote(bo.getNote());
        reg.setAddress1(bo.getAddress1());
        reg.setAddress2(bo.getAddress2());
        reg.setCity(bo.getCity());
        reg.setState(bo.getState());
        reg.setZipCode(bo.getZipCode());
        reg.setWalkIn(true);
        // Will be persisted with checkedIn=false by @PrePersist, then we set it true
        EventRegistration saved = regRepo.save(reg);
        saved.setCheckedIn(true);
        saved.setCheckedInAt(new Date());
        return regRepo.save(saved);
    }

    /**
     * Returns check-in summary counts for an event.
     *
     * @return map with keys: totalRegistered, totalCheckedIn, totalNotCheckedIn
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getCheckinSummary(Integer eventId) {
        long total     = regRepo.countByEventId(eventId);
        long checkedIn = regRepo.countByEventIdAndCheckedInTrue(eventId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalRegistered",    total);
        m.put("totalCheckedIn",     checkedIn);
        m.put("totalNotCheckedIn",  total - checkedIn);
        return m;
    }

    /** Decrypt an AES-encrypted event token back to numeric event ID. */
    public Integer decryptToken(String token) {
        try {
            return Integer.parseInt(EncryptionUtil.decrypt(token));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid event token");
        }
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDate.parse(s); } catch (Exception e) { return null; }
    }

    private String trim(String s) { return s != null ? s.trim() : ""; }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }

    /**
     * Returns the short display name for the church identified by {@code clientId}
     * (from EmailSettings), used in email sender names and SMS bodies.
     * Falls back to "Church Genius Pro" when not found.
     */
    private String resolveChurchName(String clientId) {
        if (clientId == null || clientId.isBlank()) return "Church Genius Pro";
        return emailSettingsRepo.findByClientId(clientId)
                .map(s -> s.getDisplayName())
                .filter(n -> n != null && !n.isBlank())
                .orElse("Church Genius Pro");
    }

}
