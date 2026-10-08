package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.ChurchEvent;
import com.churchgeniuspro.hibernate.ChurchEventDay;
import com.churchgeniuspro.hibernate.ChurchRegistration;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.Meeting;
import com.churchgeniuspro.repository.ChurchEventDayRepository;
import com.churchgeniuspro.repository.ChurchEventRepository;
import com.churchgeniuspro.repository.ChurchLogoRepository;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.MeetingRepository;
import com.churchgeniuspro.repository.MeetingSkipDateRepository;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Handles page routing and REST API for the Event Calendar.
 *
 * <p>Aggregates three event sources:
 * <ul>
 *   <li>Member birthdays  (all non-deleted members with birthday month/day set)</li>
 *   <li>Wedding anniversaries (Primary-role members only, to avoid couple duplicates)</li>
 *   <li>Meetings (One Time, Weekly, and Daily occurrences expanded into the month)</li>
 * </ul>
 */
@Controller
public class EventCalendarController {

    // The app's single operational time zone — same constant used for reminder/
    // scheduling cutoffs elsewhere (ReminderSchedulerService, EventRegistrationReminderService,
    // and this class's own ICS feed, which is already emitted as TZID=America/Chicago).
    // Used to decide "today" when the PUBLIC calendar hides past events (see getPublicEvents) —
    // the server JVM's default zone (typically UTC on a cloud host) would disagree with the
    // church's own day boundary and could hide or show the wrong day's events.
    private static final ZoneId CHURCH_ZONE = ZoneId.of("America/Chicago");

    private final FamilyMemberRepository       memberRepo;
    private final MeetingRepository            meetingRepo;
    private final ChurchRegistrationRepository churchRepo;
    private final ChurchLogoRepository         logoRepo;
    private final ChurchEventRepository        churchEventRepo;
    private final ChurchEventDayRepository     churchEventDayRepo;
    private final MeetingSkipDateRepository    meetingSkipRepo;

    private final com.churchgeniuspro.service.PublicLinkResolver links;

    public EventCalendarController(FamilyMemberRepository       memberRepo,
                                   MeetingRepository            meetingRepo,
                                   ChurchRegistrationRepository churchRepo,
                                   ChurchLogoRepository         logoRepo,
                                   ChurchEventRepository        churchEventRepo,
                                   ChurchEventDayRepository     churchEventDayRepo,
                                   MeetingSkipDateRepository    meetingSkipRepo,
            com.churchgeniuspro.service.PublicLinkResolver links) {
        this.links = links;
        this.memberRepo         = memberRepo;
        this.meetingRepo        = meetingRepo;
        this.churchRepo         = churchRepo;
        this.logoRepo           = logoRepo;
        this.churchEventRepo    = churchEventRepo;
        this.churchEventDayRepo = churchEventDayRepo;
        this.meetingSkipRepo    = meetingSkipRepo;
    }

    // ── Page routes ───────────────────────────────────────────────────────

    @GetMapping("/eventcalendar")
    public String eventCalendarPage(HttpServletRequest request) {
        String deny = RoleGuard.requireAdminOrUser(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "general.calendar");
        if (deny != null) return deny;
        return "forward:/eventcalendar.html";
    }

    /**
     * Public calendar page — no auth required; org identified via {@code cid} param.
     *
     * <p>The calendar grid is now built into {@code /upcomingEvents} (a "Calendar"
     * tab alongside its list view), so every existing {@code /viewEventCalendar}
     * link — printed, texted, bookmarked, or subscribed to a while back — keeps
     * working by forwarding straight into that page with the same token, opened
     * on the Calendar tab. There is no second copy of the calendar UI to keep in
     * sync any more.
     */
    @GetMapping("/viewEventCalendar")
    public String viewEventCalendarPage(@RequestParam(required = false) String cid) {
        String target = "/upcomingEvents?view=calendar";
        if (cid != null && !cid.isBlank()) {
            target += "&cid=" + java.net.URLEncoder.encode(cid, java.nio.charset.StandardCharsets.UTF_8);
        }
        return "redirect:" + target;
    }

    // ── Public API (no auth — cid identifies the org) ────────────────────

    /**
     * Returns church name and branding for the public calendar page.
     * The {@code cid} parameter is the AES-encrypted clientId produced by the
     * Public Screens link builder.
     */
    @ResponseBody
    @GetMapping("/api/event-calendar/public-church-info")
    public ResponseEntity<?> publicChurchInfo(@RequestParam String cid) {
        String clientId = decryptCid(cid);
        if (clientId == null) return ResponseEntity.badRequest().body(Map.of("error", "Invalid cid"));
        String name = churchRepo.findByClientIdAndDeleteFlagFalse(clientId)
                .map(ChurchRegistration::getChurchName)
                .orElse("");
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("churchName", name);
        return ResponseEntity.ok(res);
    }

    /**
     * Serves the church logo image for the public calendar page.
     */
    @GetMapping("/api/event-calendar/public-logo")
    public ResponseEntity<byte[]> publicLogo(@RequestParam String cid) {
        String clientId = decryptCid(cid);
        if (clientId == null) return ResponseEntity.notFound().build();
        return logoRepo.findByClientId(clientId)
                .filter(l -> l.getLogoData() != null && l.getLogoData().length > 0)
                .map(l -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_PNG)
                        .body(l.getLogoData()))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Returns calendar events for the given org (identified by {@code cid}),
     * year and month.  No authentication required.
     *
     * <p>PUBLIC VISIBILITY RULE: only Church Events and Meetings are exposed.
     * Member birthdays and wedding anniversaries are personal data and are
     * filtered out server-side so they can never reach an anonymous visitor.
     */
    @GetMapping("/api/event-calendar/public-events")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getPublicEvents(
            @RequestParam String cid,
            @RequestParam int year,
            @RequestParam int month) {

        String clientId = decryptCid(cid);
        if (clientId == null) return ResponseEntity.badRequest().build();

        // Church-zone "today" (see CHURCH_ZONE) — today's own events/meetings stay in,
        // only strictly-past dates are dropped. The authenticated staff calendar
        // (getEvents/buildEvents below) is untouched and still shows past events, since
        // staff legitimately review history; this cutoff applies to the public view only.
        LocalDate today = LocalDate.now(CHURCH_ZONE);
        List<Map<String, Object>> publicEvents = buildEvents(clientId, year, month).stream()
                .filter(e -> isPublicType((String) e.get("type")))
                .filter(e -> isTodayOrLater(e, today))
                .map(e -> { // internal row ids are for the staff calendar, not the public one (audit P14)
                    Map<String, Object> m = new LinkedHashMap<>(e);
                    m.remove("meetingId"); m.remove("eventId");
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(publicEvents);
    }

    /** Event types visible to anonymous/public viewers. */
    private static boolean isPublicType(String type) {
        return "meeting".equals(type) || "churchevent".equals(type);
    }

    /** True when this calendar-event map's "date" (yyyy-MM-dd, set by event() below) isn't in the past. */
    private static boolean isTodayOrLater(Map<String, Object> e, LocalDate today) {
        Object d = e.get("date");
        return d != null && !LocalDate.parse(d.toString()).isBefore(today);
    }

    // ── API (authenticated) ───────────────────────────────────────────────

    /**
     * Returns all calendar events for the given year + month.
     *
     * <p>Query params: {@code year} (e.g. 2026) and {@code month} (1-12).
     */
    @GetMapping("/api/event-calendar/events")
    @ResponseBody
    public ResponseEntity<List<Map<String, Object>>> getEvents(
            @RequestParam int year,
            @RequestParam int month,
            HttpServletRequest request) {
        // Mirrors /eventcalendar: staff role + permission. Birthdays/anniversaries of
        // every member are in this payload.
        if (RoleGuard.requireAdminOrUser(request) != null
                || RoleGuard.requirePermission(request, "general.calendar") != null) {
            return ResponseEntity.status(403).build();
        }
        String appClientId = com.churchgeniuspro.util.SessionUtil.getAppClientId(request);
        if (appClientId == null || appClientId.isBlank()) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(buildEvents(appClientId, year, month));
    }

    /**
     * Generates an iCalendar (.ics) feed for the authenticated org's events —
     * covering a 12-month window centred on today.
     * This URL can be subscribed to directly in Google Calendar (or any calendar app).
     */
    // NOTE: no `produces` constraint on purpose. A calendar feed must be served
    // regardless of the subscriber's Accept header — Google/Apple Calendar and other
    // clients often request with an Accept that excludes text/calendar, which with a
    // `produces` constraint would be rejected as 406 (No acceptable representation).
    // We set the content type explicitly on the response instead.
    @GetMapping(value = "/api/event-calendar/ics")
    @ResponseBody
    public ResponseEntity<String> getIcsFeed(HttpServletRequest request) {
        String deny = RoleGuard.requireAuth(request);
        if (deny != null) return ResponseEntity.status(401).build();

        String appClientId = com.churchgeniuspro.util.SessionUtil.getAppClientId(request);
        String churchName  = churchRepo.findByClientIdAndDeleteFlagFalse(appClientId)
                .map(ChurchRegistration::getChurchName).orElse("Church");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/calendar;charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=\"church-calendar.ics\"")
                .header("Cache-Control", "no-cache")
                .body(buildIcsFeed(appClientId, churchName, true));
    }

    /**
     * Returns the encrypted {@code cid} for the current session so the UI can build
     * a shareable /api/event-calendar/public-ics?cid=... URL that Google Calendar
     * (and other calendar apps) can subscribe to without needing a user session.
     */
    @GetMapping("/api/event-calendar/ics-token")
    @ResponseBody
    public ResponseEntity<?> getIcsToken(HttpServletRequest request) {
        String deny = RoleGuard.requireAuth(request);
        if (deny != null) return ResponseEntity.status(401).build();
        String appClientId = com.churchgeniuspro.util.SessionUtil.getAppClientId(request);
        // The subscription URL carries the church's live calendar link token (created
        // on first use). Revoking that link on Public Screens ends every subscribed feed.
        return links.ensureLink(appClientId, com.churchgeniuspro.service.PublicPagePolicy.PUBLIC_CALENDAR_URL, "Event Calendar")
                .<ResponseEntity<?>>map(l -> ResponseEntity.ok(Map.of("cid", l.getToken())))
                .orElseGet(() -> ResponseEntity.status(403).body(Map.of("error", "The public calendar is not available for this account.")));
    }

    /**
     * Generates an iCalendar (.ics) feed for a public org (identified by encrypted {@code cid}) —
     * covering a 12-month window centred on today. No authentication required.
     *
     * <p>Like the public calendar page, this feed contains ONLY Church Events and
     * Meetings — member birthdays and anniversaries are excluded.
     */
    @GetMapping(value = "/api/event-calendar/public-ics")
    @ResponseBody
    public ResponseEntity<String> getPublicIcsFeed(@RequestParam String cid) {
        String clientId = decryptCid(cid);
        if (clientId == null) return ResponseEntity.badRequest().build();

        String churchName = churchRepo.findByClientIdAndDeleteFlagFalse(clientId)
                .map(ChurchRegistration::getChurchName).orElse("Church");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/calendar;charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=\"church-calendar.ics\"")
                .header("Cache-Control", "no-cache")
                .body(buildIcsFeed(clientId, churchName, false));
    }

    /**
     * Shared ICS feed builder — produces a standards-compliant iCalendar feed.
     *
     * <p>Strategy:
     * <ul>
     *   <li>Recurring meetings (Daily / Weekly / Monthly) are emitted as ONE master VEVENT
     *       with an RRULE, rooted at the series start date.  Google Calendar and all RFC 5545
     *       clients expand the recurrence themselves — we must NOT also send individual
     *       per-occurrence VEVENTs or the events will appear doubled / conflicting.</li>
     *   <li>One-time meetings, birthdays, anniversaries and church events continue to be
     *       emitted as individual VEVENTs.</li>
     * </ul>
     *
     * @param includePersonal when {@code false} (public feeds), birthday and
     *                        anniversary events are excluded — only Church Events
     *                        and Meetings are emitted.
     */
    private String buildIcsFeed(String appClientId, String churchName, boolean includePersonal) {

        // Fetch all active meetings — we generate VEVENTs directly from them,
        // not from the expanded buildEvents() list, so we can emit proper RRULEs.
        List<Meeting> meetings = meetingRepo.findAllActiveByAppUserOrderByDateAsc(appClientId);

        // For non-meeting events (birthdays, anniversaries, church events) we still use
        // the 12-month expansion so we don't have to duplicate the birthday/anniversary logic.
        LocalDate today = LocalDate.now();
        java.util.LinkedHashSet<String> seenNonMtg = new java.util.LinkedHashSet<>();
        List<Map<String, Object>> nonMeetingEvents = new ArrayList<>();
        for (int offset = 0; offset < 12; offset++) {
            LocalDate d = today.plusMonths(offset);
            for (Map<String, Object> e : buildEvents(appClientId, d.getYear(), d.getMonthValue())) {
                if ("meeting".equals(e.get("type"))) continue; // handled separately below
                if (!includePersonal && !isPublicType((String) e.get("type"))) continue; // no birthdays/anniversaries on public feeds
                String key = e.get("date") + "|" + e.get("type") + "|" + e.get("title");
                if (seenNonMtg.add(key)) nonMeetingEvents.add(e);
            }
        }

        StringBuilder ics = new StringBuilder();
        ics.append("BEGIN:VCALENDAR\r\n");
        ics.append("VERSION:2.0\r\n");
        ics.append("PRODID:-//ChurchGeniusPro//EventCalendar//EN\r\n");
        ics.append("CALSCALE:GREGORIAN\r\n");
        ics.append("METHOD:PUBLISH\r\n");
        ics.append("X-WR-CALNAME:").append(icsEscape(churchName)).append(" Calendar\r\n");
        ics.append("X-WR-TIMEZONE:America/Chicago\r\n");

        // VTIMEZONE block for America/Chicago (CST/CDT)
        ics.append("BEGIN:VTIMEZONE\r\n");
        ics.append("TZID:America/Chicago\r\n");
        ics.append("BEGIN:STANDARD\r\n");
        ics.append("TZNAME:CST\r\n");
        ics.append("DTSTART:19701101T020000\r\n");
        ics.append("RRULE:FREQ=YEARLY;BYDAY=1SU;BYMONTH=11\r\n");
        ics.append("TZOFFSETFROM:-0500\r\n");
        ics.append("TZOFFSETTO:-0600\r\n");
        ics.append("END:STANDARD\r\n");
        ics.append("BEGIN:DAYLIGHT\r\n");
        ics.append("TZNAME:CDT\r\n");
        ics.append("DTSTART:19700308T020000\r\n");
        ics.append("RRULE:FREQ=YEARLY;BYDAY=2SU;BYMONTH=3\r\n");
        ics.append("TZOFFSETFROM:-0600\r\n");
        ics.append("TZOFFSETTO:-0500\r\n");
        ics.append("END:DAYLIGHT\r\n");
        ics.append("END:VTIMEZONE\r\n");

        String stampNow = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));

        // ── 1. Meetings — one VEVENT per meeting (RRULE for recurring) ────────
        for (Meeting mtg : meetings) {
            if (mtg.getMeetingDate() == null) continue;
            String typeName = mtg.getMeetingType() != null ? mtg.getMeetingType().getTypeName() : "Meeting";
            String occ      = mtg.getOccurrence() != null ? mtg.getOccurrence().trim() : "One-time";
            String seriesDateStr = mtg.getMeetingDate().toString(); // YYYY-MM-DD (series start)
            String dtDate    = seriesDateStr.replace("-", "");

            // Deterministic UID based on meeting DB id so calendar apps can de-dup on re-import
            String uid = "mtg-" + mtg.getId() + "@churchgeniuspro";

            ics.append("BEGIN:VEVENT\r\n");
            ics.append("UID:").append(uid).append("\r\n");
            ics.append("DTSTAMP:").append(stampNow).append("\r\n");

            // DTSTART / DTEND
            if (mtg.getStartTime() != null && !mtg.getStartTime().isBlank()) {
                String startHHMM = mtg.getStartTime().replace(":", "");
                String dtStart = dtDate + "T" + startHHMM + "00";
                ics.append("DTSTART;TZID=America/Chicago:").append(dtStart).append("\r\n");
                if (mtg.getEndTime() != null && !mtg.getEndTime().isBlank()) {
                    String dtEnd = dtDate + "T" + mtg.getEndTime().replace(":", "") + "00";
                    ics.append("DTEND;TZID=America/Chicago:").append(dtEnd).append("\r\n");
                } else {
                    // Default: 1-hour duration (add 1 hour to start time)
                    java.time.LocalTime lt = java.time.LocalTime.parse(mtg.getStartTime());
                    String dtEnd = dtDate + "T" + lt.plusHours(1).format(java.time.format.DateTimeFormatter.ofPattern("HHmmss"));
                    ics.append("DTEND;TZID=America/Chicago:").append(dtEnd).append("\r\n");
                }
                String timeRange = buildTimeRange(mtg.getStartTime(), mtg.getEndTime());
                if (timeRange != null) ics.append("DESCRIPTION:").append(icsEscape(timeRange)).append("\r\n");
            } else {
                // All-day event — DTEND is next day per RFC 5545
                ics.append("DTSTART;VALUE=DATE:").append(dtDate).append("\r\n");
                String nextDay = mtg.getMeetingDate().plusDays(1).toString().replace("-", "");
                ics.append("DTEND;VALUE=DATE:").append(nextDay).append("\r\n");
            }

            // Location
            String location = buildLocation(mtg);
            if (!location.isBlank()) ics.append("LOCATION:").append(icsEscape(location)).append("\r\n");

            // RRULE for recurring meetings
            switch (occ) {
                case "Weekly", "Weekly " -> {
                    StringBuilder rrule = new StringBuilder("FREQ=WEEKLY");
                    if (mtg.getEndDate() != null) {
                        rrule.append(";UNTIL=").append(mtg.getEndDate().toString().replace("-", "")).append("T235959Z");
                    }
                    // BYDAY: use weekDays field (comma-separated 0-6, 0=Sun) if set,
                    // otherwise fall back to the series start day-of-week.
                    java.util.Set<Integer> dayNums = parseIntSet(mtg.getWeekDays());
                    if (!dayNums.isEmpty()) {
                        String byDay = dayNums.stream()
                                .map(this::numToIcsDow)
                                .collect(Collectors.joining(","));
                        rrule.append(";BYDAY=").append(byDay);
                    } else {
                        rrule.append(";BYDAY=").append(dowToIcs(mtg.getMeetingDate().getDayOfWeek()));
                    }
                    ics.append("RRULE:").append(rrule).append("\r\n");
                }
                case "Daily" -> {
                    StringBuilder rrule = new StringBuilder("FREQ=DAILY");
                    if (mtg.getEndDate() != null) {
                        rrule.append(";UNTIL=").append(mtg.getEndDate().toString().replace("-", "")).append("T235959Z");
                    }
                    ics.append("RRULE:").append(rrule).append("\r\n");
                }
                case "Monthly" -> {
                    StringBuilder rrule = new StringBuilder("FREQ=MONTHLY");
                    if (mtg.getEndDate() != null) {
                        rrule.append(";UNTIL=").append(mtg.getEndDate().toString().replace("-", "")).append("T235959Z");
                    }
                    // Constrain to specific months if set (e.g. allowedMonths = "1,6,9")
                    java.util.Set<Integer> allowedMonths = parseIntSet(mtg.getMonthMonths());
                    if (!allowedMonths.isEmpty()) {
                        String byMonth = allowedMonths.stream().sorted()
                                .map(String::valueOf).collect(Collectors.joining(","));
                        rrule.append(";BYMONTH=").append(byMonth);
                    }
                    if (mtg.getMonthWeekOrdinal() != null && mtg.getMonthWeekDay() != null) {
                        // e.g. "1st Sunday" → BYDAY=1SU, "Last Sunday" (ordinal=5) → BYDAY=-1SU
                        int ord = mtg.getMonthWeekOrdinal() == 5 ? -1 : mtg.getMonthWeekOrdinal();
                        String byday = ord + numToIcsDow(mtg.getMonthWeekDay());
                        rrule.append(";BYDAY=").append(byday);
                    } else if (mtg.getMonthDayOfMonth() != null) {
                        // Fixed day-of-month e.g. 15th → BYMONTHDAY=15
                        rrule.append(";BYMONTHDAY=").append(mtg.getMonthDayOfMonth());
                    }
                    ics.append("RRULE:").append(rrule).append("\r\n");
                }
                // "One-time" / "One Time" / "Once" — no RRULE needed
            }

            // EXDATE — deleted single occurrences (meeting_skip_date)
            List<com.churchgeniuspro.hibernate.MeetingSkipDate> mtgSkips =
                    meetingSkipRepo.findByMeetingId(mtg.getId());
            if (!mtgSkips.isEmpty()) {
                boolean timed = mtg.getStartTime() != null && !mtg.getStartTime().isBlank();
                String startHHMMSS = timed ? mtg.getStartTime().replace(":", "") + "00" : null;
                for (com.churchgeniuspro.hibernate.MeetingSkipDate sd : mtgSkips) {
                    String exDate = sd.getSkipDate().toString().replace("-", "");
                    if (timed) {
                        ics.append("EXDATE;TZID=America/Chicago:")
                           .append(exDate).append("T").append(startHHMMSS).append("\r\n");
                    } else {
                        ics.append("EXDATE;VALUE=DATE:").append(exDate).append("\r\n");
                    }
                }
            }

            ics.append("SUMMARY:").append(icsEscape(typeName)).append("\r\n");
            ics.append("CATEGORIES:Meeting\r\n");
            ics.append("END:VEVENT\r\n");
        }

        // ── 2. Birthdays, anniversaries, church events ────────────────────────
        for (Map<String, Object> e : nonMeetingEvents) {
            String dateStr = (String) e.get("date");   // YYYY-MM-DD
            String title   = (String) e.get("title");
            String type    = (String) e.get("type");
            String dtDate  = dateStr.replace("-", "");
            String uid = Math.abs((dateStr + "-" + type + "-" + title).hashCode()) + "@churchgeniuspro";

            ics.append("BEGIN:VEVENT\r\n");
            ics.append("UID:").append(uid).append("\r\n");
            ics.append("DTSTAMP:").append(stampNow).append("\r\n");
            ics.append("DTSTART;VALUE=DATE:").append(dtDate).append("\r\n");
            // All-day DTEND = next day per RFC 5545
            String nextDay = LocalDate.parse(dateStr).plusDays(1).toString().replace("-", "");
            ics.append("DTEND;VALUE=DATE:").append(nextDay).append("\r\n");
            if ("churchevent".equals(type)) {
                String evtTime = (String) e.get("time");
                if (evtTime != null && !evtTime.isBlank()) {
                    ics.append("DESCRIPTION:").append(icsEscape(evtTime)).append("\r\n");
                }
                Object loc = e.get("location");
                if (loc instanceof String ls && !ls.isBlank()) {
                    ics.append("LOCATION:").append(icsEscape(ls)).append("\r\n");
                }
            }
            ics.append("SUMMARY:").append(icsEscape(title)).append("\r\n");
            ics.append("CATEGORIES:").append(icsEscape(typeLabel(type))).append("\r\n");
            ics.append("END:VEVENT\r\n");
        }

        ics.append("END:VCALENDAR\r\n");
        return ics.toString();
    }

    /** Converts a 0–6 integer day number (0=Sun) to RFC 5545 two-letter day abbreviation. */
    private String numToIcsDow(int n) {
        return switch (n) {
            case 0 -> "SU";
            case 1 -> "MO";
            case 2 -> "TU";
            case 3 -> "WE";
            case 4 -> "TH";
            case 5 -> "FR";
            case 6 -> "SA";
            default -> "MO";
        };
    }

    /** Converts a Java {@link DayOfWeek} to RFC 5545 two-letter day abbreviation. */
    private String dowToIcs(DayOfWeek dow) {
        return switch (dow) {
            case SUNDAY    -> "SU";
            case MONDAY    -> "MO";
            case TUESDAY   -> "TU";
            case WEDNESDAY -> "WE";
            case THURSDAY  -> "TH";
            case FRIDAY    -> "FR";
            case SATURDAY  -> "SA";
        };
    }

    // ── Shared event builder ──────────────────────────────────────────────

    /**
     * Builds the list of calendar events for a given month.
     *
     * @param appClientId  if non-null, results are scoped to this org;
     *                     if null, returns events for all orgs (authenticated context).
     */
    /** Package-visible so {@code MembershipFormController} can delegate to the same recurrence engine. */
    List<Map<String, Object>> buildEvents(String appClientId, int year, int month) {

        List<Map<String, Object>> events = new ArrayList<>();

        YearMonth ym    = YearMonth.of(year, month);
        LocalDate start = ym.atDay(1);
        LocalDate end   = ym.atEndOfMonth();

        // ── 1. Birthdays & Anniversaries ──────────────────────────────────

        List<FamilyMember> members = appClientId != null
                ? memberRepo.findAllWithFamilyByAppUser(appClientId)
                : memberRepo.findAllWithFamily();

        // Group members by family id so we can build couple anniversary titles
        // and deduplicate — one anniversary event per couple.
        // Key = familyId (Integer); value = list of members in that family.
        Map<Integer, List<FamilyMember>> byFamily = members.stream()
                .collect(Collectors.groupingBy(
                        m -> m.getFamily() != null ? m.getFamily().getId() : -m.getId()));

        // Track families for which we already emitted an anniversary this month
        // (prevents duplicates when both Head and Spouse have anniversary data set).
        java.util.Set<Integer> anniversaryEmitted = new java.util.HashSet<>();

        for (FamilyMember m : members) {

            String fullName = com.churchgeniuspro.util.MemberNameUtil.display(m.getFirstName(), m.getLastName(), m.getOtherName());

            // Birthday — all roles
            if (m.getBirthdayMonth() != null
                    && m.getBirthdayDay() != null
                    && m.getBirthdayMonth() == month) {
                int day = m.getBirthdayDay();
                if (day >= 1 && day <= ym.lengthOfMonth()) {
                    events.add(event("birthday",
                            fullName + "'s Birthday",
                            LocalDate.of(year, month, day),
                            null));
                }
            }

            // Anniversary — emit once per family when ANY member has anniversary data.
            //
            // History: the UI only shows the anniversary date field for the "Spouse"
            // role, so the data is stored on the Spouse (or "Wife") member, not the
            // Head.  The old code checked role == "Head" and therefore never matched.
            // We now accept any role that has anniversary month/day set, and we
            // deduplicate by familyId so a couple whose BOTH members have the date
            // set only produces one calendar entry.
            //
            // Title format: "Anson and Christina's Anniversary" when a Head-of-Household
            // partner is found in the same family; otherwise "[Name]'s Anniversary".
            if (m.getAnniversaryMonth() != null
                    && m.getAnniversaryDay() != null
                    && m.getAnniversaryMonth() == month) {

                int day = m.getAnniversaryDay();
                if (day >= 1 && day <= ym.lengthOfMonth()) {
                    Integer familyId = m.getFamily() != null ? m.getFamily().getId() : null;

                    // Skip if we already emitted an anniversary for this family
                    if (familyId != null && !anniversaryEmitted.add(familyId)) continue;

                    // Build a "Name1 and Name2" title by pairing this member with their
                    // Head-of-Household (or Spouse) partner in the same family.
                    String title;
                    if (familyId != null) {
                        List<FamilyMember> siblings = byFamily.getOrDefault(familyId, List.of());
                        // Find the partner: Head-of-Household or Spouse that is NOT this member
                        FamilyMember partner = siblings.stream()
                                .filter(s -> !s.getId().equals(m.getId()))
                                .filter(s -> {
                                    String r = s.getRole();
                                    return r != null && (
                                        r.equalsIgnoreCase("Head") ||
                                        r.equalsIgnoreCase("Head of Household") ||
                                        r.equalsIgnoreCase("Spouse") ||
                                        r.equalsIgnoreCase("Wife") ||
                                        r.equalsIgnoreCase("Husband"));
                                })
                                .findFirst().orElse(null);

                        if (partner != null) {
                            // Determine which is Head and which is Spouse so the
                            // display order is consistent: Head first, then partner.
                            boolean thisIsHead = m.getRole() != null && (
                                    m.getRole().equalsIgnoreCase("Head") ||
                                    m.getRole().equalsIgnoreCase("Head of Household"));
                            String partnerName = com.churchgeniuspro.util.MemberNameUtil.display(
                                    partner.getFirstName(), partner.getLastName(), partner.getOtherName());
                            String name1 = thisIsHead ? fullName : partnerName;
                            String name2 = thisIsHead ? partnerName : fullName;
                            title = name1 + " and " + name2 + "'s Anniversary";
                        } else {
                            title = fullName + "'s Anniversary";
                        }
                    } else {
                        title = fullName + "'s Anniversary";
                    }

                    events.add(event("anniversary", title,
                            LocalDate.of(year, month, day), null));
                }
            }
        }

        // ── 2. Meetings ────────────────────────────────────────────────────

        List<Meeting> meetings = appClientId != null
                ? meetingRepo.findAllActiveByAppUserOrderByDateAsc(appClientId)
                : meetingRepo.findAllActiveOrderByDateDesc(); // no-appClientId path not used in production

        // Deleted single occurrences (meeting_skip_date) — loaded once per request.
        Map<Integer, java.util.Set<LocalDate>> skipMap = new java.util.HashMap<>();
        if (!meetings.isEmpty()) {
            List<Integer> mids = meetings.stream().map(Meeting::getId).collect(Collectors.toList());
            for (com.churchgeniuspro.hibernate.MeetingSkipDate sd : meetingSkipRepo.findByMeetingIdIn(mids)) {
                skipMap.computeIfAbsent(sd.getMeetingId(), k -> new java.util.HashSet<>())
                       .add(sd.getSkipDate());
            }
        }

        for (Meeting m : meetings) {
            if (m.getMeetingDate() == null) continue;

            String typeName  = m.getMeetingType() != null
                    ? m.getMeetingType().getTypeName()
                    : "Meeting";
            String timeRange = buildTimeRange(m.getStartTime(), m.getEndTime());
            // Normalise occurrence — the form saves "One-time" but guard against "One Time" too
            String occ = m.getOccurrence() != null ? m.getOccurrence().trim() : "One-time";

            switch (occ) {

                // ── One-time (also accept legacy "One Time") ───────────────
                case "One-time", "One Time", "Once" -> {
                    if (!m.getMeetingDate().isBefore(start)
                            && !m.getMeetingDate().isAfter(end)) {
                        addMeetingEvent(events, m, typeName, m.getMeetingDate(), timeRange, skipMap);
                    }
                }

                // ── Daily ──────────────────────────────────────────────────
                case "Daily" -> {
                    LocalDate effectiveEnd = m.getEndDate() != null
                            ? (m.getEndDate().isBefore(end) ? m.getEndDate() : end)
                            : end;
                    LocalDate from = m.getMeetingDate().isBefore(start)
                            ? start : m.getMeetingDate();
                    for (LocalDate cur = from; !cur.isAfter(effectiveEnd); cur = cur.plusDays(1)) {
                        addMeetingEvent(events, m, typeName, cur, timeRange, skipMap);
                    }
                }

                // ── Weekly ─────────────────────────────────────────────────
                case "Weekly" -> {
                    LocalDate effectiveEnd = m.getEndDate() != null
                            ? (m.getEndDate().isBefore(end) ? m.getEndDate() : end)
                            : end;

                    // Parse weekDays: comma-separated "0,2,5" (0=Sun…6=Sat)
                    java.util.Set<Integer> dayNums = parseIntSet(m.getWeekDays());
                    if (dayNums.isEmpty()) {
                        // Fall back to the day-of-week of the series start date
                        dayNums.add(dowToNum(m.getMeetingDate().getDayOfWeek()));
                    }

                    LocalDate from = m.getMeetingDate().isBefore(start) ? start : m.getMeetingDate();
                    for (LocalDate cur = from; !cur.isAfter(effectiveEnd); cur = cur.plusDays(1)) {
                        if (dayNums.contains(dowToNum(cur.getDayOfWeek()))) {
                            addMeetingEvent(events, m, typeName, cur, timeRange, skipMap);
                        }
                    }
                }

                // ── Monthly ────────────────────────────────────────────────
                case "Monthly" -> {
                    LocalDate effectiveEnd = m.getEndDate() != null
                            ? (m.getEndDate().isBefore(end) ? m.getEndDate() : end)
                            : end;

                    // Which months does this repeat in?  null/empty = all months.
                    java.util.Set<Integer> allowedMonths = parseIntSet(m.getMonthMonths());

                    // Iterate day-by-day through the window; for each day check
                    // whether it satisfies the monthly pattern.
                    LocalDate from = m.getMeetingDate().isBefore(start) ? start : m.getMeetingDate();
                    for (LocalDate cur = from; !cur.isAfter(effectiveEnd); cur = cur.plusDays(1)) {

                        // Month filter
                        if (!allowedMonths.isEmpty() && !allowedMonths.contains(cur.getMonthValue())) {
                            continue;
                        }

                        boolean matches = false;

                        if (m.getMonthWeekOrdinal() != null && m.getMonthWeekDay() != null) {
                            // e.g. "1st Sunday" — check this day against that ordinal+weekday
                            matches = isNthWeekday(cur,
                                    m.getMonthWeekOrdinal(),
                                    m.getMonthWeekDay());
                        } else if (m.getMonthDayOfMonth() != null) {
                            // Fixed day-of-month (e.g. 15th)
                            matches = (cur.getDayOfMonth() == m.getMonthDayOfMonth());
                        }
                        // If neither pattern is set, skip the meeting entirely

                        if (matches) {
                            addMeetingEvent(events, m, typeName, cur, timeRange, skipMap);
                        }
                    }
                }
            }
        }

        // ── 3. Church Events ──────────────────────────────────────────────

        List<ChurchEvent> churchEvents = appClientId != null
                ? churchEventRepo.findByAppClientIdAndDeleteFlagFalseOrderByCreatedDateDesc(appClientId)
                : churchEventRepo.findAllByDeleteFlagFalseOrderByCreatedDateDesc();

        for (ChurchEvent ce : churchEvents) {
            String evtName  = ce.getEventName() != null ? ce.getEventName().trim() : "Event";
            String timeRange = buildTimeRange(ce.getStartTime(), ce.getEndTime());

            if ("One Day".equalsIgnoreCase(ce.getEventType())) {
                if (ce.getEventDate() != null
                        && !ce.getEventDate().isBefore(start)
                        && !ce.getEventDate().isAfter(end)) {
                    events.add(churchEventEntry(ce, evtName, ce.getEventDate(), timeRange));
                }
            } else if ("Multiple Days".equalsIgnoreCase(ce.getEventType())) {
                List<ChurchEventDay> days = churchEventDayRepo.findByEventIdOrderByDayOrderAsc(ce.getId());
                for (ChurchEventDay d : days) {
                    if (d.getEventDate() == null) continue;
                    if (!d.getEventDate().isBefore(start) && !d.getEventDate().isAfter(end)) {
                        String dayTimeRange = buildTimeRange(d.getStartTime(), d.getEndTime());
                        events.add(churchEventEntry(ce, evtName, d.getEventDate(), dayTimeRange));
                    }
                }
            }
        }

        // Sort by date ASC, then start time ASC, then by type order
        events.sort(Comparator
                .comparing((Map<String, Object> e) -> (String) e.get("date"))
                .thenComparing(e -> {
                    Object t = e.get("time");
                    return t instanceof String s ? s : "";
                })
                .thenComparing(e -> typeOrder((String) e.get("type"))));

        return events;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Parses a comma-separated integer string (e.g. {@code "0,2,5"}) into a mutable Set.
     * Returns an empty set for null or blank input.
     */
    private java.util.Set<Integer> parseIntSet(String csv) {
        java.util.Set<Integer> set = new java.util.HashSet<>();
        if (csv == null || csv.isBlank()) return set;
        for (String part : csv.split(",")) {
            try { set.add(Integer.parseInt(part.trim())); } catch (NumberFormatException ignored) {}
        }
        return set;
    }

    /**
     * Converts a {@link DayOfWeek} to the 0=Sun … 6=Sat numbering used in the meeting form.
     */
    private int dowToNum(DayOfWeek dow) {
        // Java DayOfWeek: MONDAY=1 … SUNDAY=7; we need SUNDAY=0 … SATURDAY=6
        return dow == DayOfWeek.SUNDAY ? 0 : dow.getValue();
    }

    /**
     * Returns {@code true} if {@code date} is the Nth occurrence of its day-of-week
     * within its month, where {@code ordinal} is 1–4 for 1st–4th and 5 for Last.
     * {@code weekDay} uses the 0=Sun … 6=Sat convention.
     */
    private boolean isNthWeekday(LocalDate date, int ordinal, int weekDay) {
        if (dowToNum(date.getDayOfWeek()) != weekDay) return false;

        YearMonth ym = YearMonth.from(date);

        if (ordinal == 5) {
            // "Last" — find the last occurrence of this weekday in the month
            LocalDate lastDay = ym.atEndOfMonth();
            LocalDate candidate = lastDay;
            while (dowToNum(candidate.getDayOfWeek()) != weekDay) {
                candidate = candidate.minusDays(1);
            }
            return date.equals(candidate);
        }

        // 1st–4th: count how many times this weekday has occurred so far this month
        int count = 0;
        for (LocalDate d = ym.atDay(1); !d.isAfter(date); d = d.plusDays(1)) {
            if (dowToNum(d.getDayOfWeek()) == weekDay) count++;
        }
        return count == ordinal;
    }

    /** Builds a single-line address string from meeting fields; empty string if no address set. */
    private String buildLocation(Meeting m) {
        return joinAddress(m.getAddress1(), m.getAddress2(), m.getCity(),
                           m.getState(), m.getPinCode(), m.getCountry());
    }

    /** Builds a single-line address string from church-event fields; empty string if no address set. */
    private String buildLocation(ChurchEvent ce) {
        return joinAddress(ce.getAddress1(), ce.getAddress2(), ce.getCity(),
                           ce.getState(), ce.getPinCode(), ce.getCountry());
    }

    /** Joins address parts into one display line, converting numeric state codes to abbreviations. */
    private String joinAddress(String address1, String address2, String city,
                               String state, String pinCode, String country) {
        StringBuilder sb = new StringBuilder();
        if (address1 != null && !address1.isBlank()) sb.append(address1.trim());
        if (address2 != null && !address2.isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(address2.trim());
        }
        if (city != null && !city.isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(city.trim());
        }
        if (state != null && !state.isBlank()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(stateDisplay(state.trim()));
        }
        if (pinCode != null && !pinCode.isBlank()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(pinCode.trim());
        }
        if (country != null && !country.isBlank()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(country.trim());
        }
        return sb.toString();
    }

    /**
     * Converts a numeric state code (stored by the meeting form, e.g. "10") to its
     * two-letter abbreviation via {@link com.churchgeniuspro.common.States}.
     * Non-numeric values (already-textual states) are returned unchanged.
     */
    private String stateDisplay(String state) {
        try {
            String abbr = com.churchgeniuspro.common.States.STATE_CODES.get(Integer.parseInt(state));
            return abbr != null ? abbr : state;
        } catch (NumberFormatException e) {
            return state;
        }
    }

    /**
     * Resolves the {@code cid} query param to a tenant. Returns null on failure.
     *
     * <p>Accepts a live token minted for the Event Calendar OR for the merged
     * Upcoming Events page (they render the same calendar data now — see
     * {@link com.churchgeniuspro.service.PublicPagePolicy#UPCOMING_EVENTS_FAMILY})
     * or for the NTAG landing page. Nothing is decrypted — every candidate is a
     * real, revocable database lookup.
     */
    private String decryptCid(String cid) {
        return links.resolveClientIdAnyOf(cid, com.churchgeniuspro.service.PublicPagePolicy.UPCOMING_EVENTS_FAMILY);
    }

    private Map<String, Object> event(String type, String title,
                                      LocalDate date, String time) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date",  date.toString());   // yyyy-MM-dd
        m.put("type",  type);
        m.put("title", title);
        m.put("time",  time);              // null for birthdays/anniversaries
        return m;
    }

    /**
     * Adds one meeting occurrence to the event list unless that single
     * occurrence has been deleted ({@code meeting_skip_date}). Meeting events
     * carry {@code meetingId} + {@code recurring} so the calendar UI can offer
     * per-occurrence deletion.
     */
    private void addMeetingEvent(List<Map<String, Object>> events, Meeting m,
                                 String typeName, LocalDate date, String timeRange,
                                 Map<Integer, java.util.Set<LocalDate>> skipMap) {
        java.util.Set<LocalDate> skips = skipMap.get(m.getId());
        if (skips != null && skips.contains(date)) return;   // occurrence deleted
        Map<String, Object> e = event("meeting", typeName, date, timeRange);
        e.put("meetingId", m.getId());
        String occ = m.getOccurrence() != null ? m.getOccurrence().trim() : "One-time";
        e.put("recurring", !(occ.equalsIgnoreCase("One-time")
                || occ.equalsIgnoreCase("One Time") || occ.equalsIgnoreCase("Once")));
        // Details for the calendar detail view (public + internal)
        putIfHasText(e, "location",    buildLocation(m));
        putIfHasText(e, "description", m.getNote());
        events.add(e);
    }

    /**
     * Builds one calendar entry for a Church Event occurrence, enriched with the
     * details shown in the calendar's event-details dialog (location, description,
     * organizer, fee, registration link).
     */
    private Map<String, Object> churchEventEntry(ChurchEvent ce, String evtName,
                                                 LocalDate date, String timeRange) {
        Map<String, Object> e = event("churchevent", evtName, date, timeRange);
        e.put("eventId", ce.getId());
        putIfHasText(e, "location",         buildLocation(ce));
        putIfHasText(e, "description",      ce.getNote());
        putIfHasText(e, "organizer",        ce.getHostName());
        putIfHasText(e, "fee",              ce.getFee());
        putIfHasText(e, "registrationLink", ce.getRegistrationLink());
        return e;
    }

    /** Puts {@code value} into the map only when it is non-null and non-blank. */
    private static void putIfHasText(Map<String, Object> m, String key, String value) {
        if (value != null && !value.isBlank()) m.put(key, value.trim());
    }

    private String buildTimeRange(String s, String e) {
        if (s == null && e == null) return null;
        if (s != null && e != null) return fmt12(s) + " – " + fmt12(e);
        return s != null ? fmt12(s) : null;
    }

    /** Converts 24-hour "HH:mm" to 12-hour "h:mm AM/PM". */
    private String fmt12(String t24) {
        if (t24 == null || t24.length() < 5) return t24;
        try {
            String[] p  = t24.split(":");
            int h       = Integer.parseInt(p[0]);
            String min  = p[1];
            String ampm = h >= 12 ? "PM" : "AM";
            int h12     = h % 12;
            if (h12 == 0) h12 = 12;
            return h12 + ":" + min + " " + ampm;
        } catch (Exception ex) { return t24; }
    }

    private String safe(String s) { return s != null ? s.trim() : ""; }

    private int typeOrder(String type) {
        return switch (type) {
            case "birthday"    -> 0;
            case "anniversary" -> 1;
            case "meeting"     -> 2;
            case "churchevent" -> 3;
            default            -> 4;
        };
    }

    private String typeLabel(String type) {
        return switch (type) {
            case "birthday"    -> "Birthday";
            case "anniversary" -> "Anniversary";
            case "churchevent" -> "Event";
            default            -> "Meeting";
        };
    }

    /** Escapes special characters for iCalendar text values. */
    private String icsEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace(";",  "\\;")
                .replace(",",  "\\,")
                .replace("\n", "\\n")
                .replace("\r", "");
    }
}
