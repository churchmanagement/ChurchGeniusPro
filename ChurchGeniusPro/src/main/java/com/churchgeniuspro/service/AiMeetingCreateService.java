package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.Meeting;
import com.churchgeniuspro.hibernate.MeetingType;
import com.churchgeniuspro.model.MeetingBO;
import com.churchgeniuspro.repository.MeetingRepository;
import com.churchgeniuspro.repository.MeetingTypeRepository;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.util.AppClock;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI Search — "Create a meeting …" (Phase E).
 *
 * <p>Deterministic, no LLM. The flow is <b>parse → ask for anything missing → confirm → create</b>:
 * <ol>
 *   <li>Authorization first: {@link AiAccess#meetingsEdit()} — the same rule as {@code POST /api/meetings}
 *       (staff role + general.meetings page + general.meetings.edit). Member portal, Kids Portal and the
 *       church login are denied before any meeting type is looked up.</li>
 *   <li>The request is parsed for the meeting category (must match one of this church's meeting types),
 *       the date and an optional start/end time and note. Only <b>one-time</b> meetings are created;
 *       any recurrence wording is refused and pointed at the Meetings page.</li>
 *   <li>Required fields are the Meetings page's own rule for a one-time meeting: category and date.
 *       Missing ones are asked for; the partial draft lives in the HTTP session for a few minutes.</li>
 *   <li>Nothing is written until the user confirms — by the "Create meeting" button (a one-time token)
 *       or by answering "yes" in Voice/Converse. The token is consumed atomically, so a double click or a
 *       repeated "yes" cannot create two meetings, and an identical meeting (type + date + start time)
 *       already on the calendar is reported instead of duplicated.</li>
 *   <li>Creation goes through {@link MeetingService#save} — the same code path, validation and tenant
 *       checks as the Meetings page — and success is reported only after the row exists.</li>
 * </ol>
 */
@Service
public class AiMeetingCreateService {

    static final String SESSION_KEY = "aiMeetingDraft";
    static final String DENIED_MSG = AiDataSearchService.DENIED_MSG;
    static final String MEETINGS_URL = "/meetings";

    /** Minutes a pending draft (missing fields or awaiting confirmation) stays valid. */
    @Value("${ai.search.meeting-draft-minutes:10}")
    public int draftMinutes = 10;
    @Value("${ai.search.max-rows:20}")
    public int maxRows = 20;

    private final MeetingTypeRepository typeRepo;
    private final MeetingRepository meetingRepo;
    private final MeetingService meetingService;
    private final AiAccessPolicy access;

    public AiMeetingCreateService(MeetingTypeRepository typeRepo, MeetingRepository meetingRepo,
                                  MeetingService meetingService, AiAccessPolicy access) {
        this.typeRepo = typeRepo; this.meetingRepo = meetingRepo; this.meetingService = meetingService; this.access = access;
    }

    // ── Draft kept in the session ────────────────────────────────────────────

    /** Stage ASK = fields still missing; CONFIRM = complete, waiting for the user's yes. */
    enum Stage { ASK, CONFIRM }

    static final class Draft implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        String clientId; String username; Stage stage;
        Integer typeId; String typeName; LocalDate date; LocalTime start; LocalTime end; String note;
        /** An hour given without AM/PM ("at 7") — the time stays required until it is resolved. */
        String timeHint;
        String token; Instant created;
        boolean complete() { return typeId != null && date != null && (start != null || timeHint == null); }
    }

    // ── Entry points ─────────────────────────────────────────────────────────

    private static final Pattern CREATE = Pattern.compile(
        "\\b(create|schedule|add|set\\s?up|book|plan|new|make|arrange|organi[sz]e)\\b.*\\bmeeting\\b|\\bmeeting\\b.*\\b(create|schedule|set\\s?up|book|plan)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_CREATE = Pattern.compile(
        "^\\s*(how|where|what|when|which|who|why|is|are|do|does|can|could|list|show|find|upcoming|next|open|go to|take me)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern RECURRING = Pattern.compile(
        "\\b(every|weekly|daily|monthly|recurring|recurrence|repeat\\w*|each (week|day|month)|series)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern YES = Pattern.compile(
        "^\\s*(yes|yeah|yep|yup|sure|confirm|confirmed|correct|ok|okay|go ahead|do it|create it|create|proceed|that'?s right|please do)"
        + "([,.! ]+(yes|please|create it|go ahead|do it|confirm|thanks?|thank you|that'?s right))*[.!]?\\s*$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern NO = Pattern.compile(
        "^\\s*(no|nope|cancel|never ?mind|stop|forget it|don'?t|do not|discard|not now)"
        + "([,.! ]+(no|nope|cancel|never ?mind|stop|forget it|please|thanks?|thank you|don'?t|do not))*[.!]?\\s*$", Pattern.CASE_INSENSITIVE);

    /** True when the text asks to create a meeting (not a how-to or a listing question). */
    public static boolean isCreateRequest(String q) {
        if (q == null) return false;
        return CREATE.matcher(q).find() && !NOT_CREATE.matcher(q).find();
    }

    /**
     * Handles a Type/Voice/Converse message. Returns null when the message has nothing to do with
     * meeting creation (the caller continues with its normal routing).
     */
    public Map<String, Object> handle(HttpServletRequest req, String clientId, String question) {
        if (question == null || clientId == null) return null;
        String q = question.trim();
        HttpSession session = req.getSession(false);
        if (session == null) return null;
        LocalDate today = AppClock.today();
        Draft pending = pending(session, clientId);

        if (isCreateRequest(q)) {
            AiAccess a = access.resolve(req, clientId);
            if (!a.meetingsEdit()) { clear(session); return denied(); }
            if (RECURRING.matcher(q).find()) {
                clear(session);
                return withLink(base("meeting.unsupported", "I can only create one-time meetings from here — a recurring series "
                    + "(weekly, monthly, daily) is set up on the Meetings page.", false), a);
            }
            Draft d = new Draft();
            d.clientId = clientId; d.username = a.username(); d.created = Instant.now();
            fill(d, q, today, clientId, true);
            return advance(session, d, a);
        }

        if (pending == null) return null;
        AiAccess a = access.resolve(req, clientId);
        if (!a.meetingsEdit()) { clear(session); return denied(); }   // permission changed mid-conversation

        if (pending.stage == Stage.CONFIRM) {
            if (YES.matcher(q).matches()) return confirm(req, clientId, pending.token);
            if (NO.matcher(q).matches())  { clear(session); return base("meeting.cancelled", "Okay — I have not created the meeting.", false); }
            return null;                   // some other question: the draft stays until the button is used or it expires
        }
        // ASK stage: does this message supply any of the missing fields?
        if (NO.matcher(q).matches()) { clear(session); return base("meeting.cancelled", "Okay — I have not created the meeting.", false); }
        boolean filled = fill(pending, q, today, clientId, false);
        if (!filled) { clear(session); return null; }   // the user moved on; do not hijack their question
        return advance(session, pending, a);
    }

    /**
     * The "Create meeting" button / a spoken "yes": consumes the one-time token and creates the
     * meeting. Re-checks authorization and the required fields; a used, unknown or expired token
     * creates nothing.
     */
    public Map<String, Object> confirm(HttpServletRequest req, String clientId, String token) {
        HttpSession session = req.getSession(false);
        if (session == null || clientId == null) return expired();
        AiAccess a = access.resolve(req, clientId);
        if (!a.meetingsEdit()) { clear(session); return denied(); }
        Draft d;
        synchronized (this) {                                          // first caller takes the draft; a double submit finds nothing
            d = pending(session, clientId);
            if (d == null || d.stage != Stage.CONFIRM || token == null || !token.equals(d.token)) return expired();
            session.removeAttribute(SESSION_KEY);
        }
        if (!d.complete()) return expired();
        // Validate against the church's data again at creation time (the type may have been deleted meanwhile).
        MeetingType mt = typeRepo.findByIdAndAppClientIdAndDeleteFlagFalse(d.typeId, clientId).orElse(null);
        if (mt == null) return base("meeting.error", "That meeting category no longer exists, so I have not created the meeting.", false);
        String start = d.start == null ? "" : hhmm(d.start);
        if (meetingRepo.aiCountSameSlot(clientId, d.typeId, d.date, start) > 0) {
            return withLink(base("meeting.duplicate", "A " + mt.getTypeName() + " meeting on " + fmtDate(d.date)
                + (d.start == null ? "" : " at " + fmtTime(d.start)) + " already exists, so I have not created another one.", false), a);
        }
        MeetingBO bo = new MeetingBO();
        bo.setMeetingTypeId(d.typeId);
        bo.setMeetingDate(d.date.toString());
        bo.setStartTime(d.start == null ? null : hhmm(d.start));
        bo.setEndTime(d.end == null ? null : hhmm(d.end));
        bo.setNote(d.note);
        bo.setOccurrence("One-time");
        bo.setDoNotAutoDelete(false);
        Meeting saved;
        try {
            saved = meetingService.save(bo, clientId);
        } catch (IllegalArgumentException e) {
            return base("meeting.error", "I could not create the meeting: " + e.getMessage(), false);
        }
        if (saved == null || saved.getId() == null) return base("meeting.error", "The meeting was not created.", false);
        Map<String, Object> m = base("meeting.created", "Created a one-time " + mt.getTypeName() + " meeting on " + fmtDate(d.date)
            + timeText(d) + (d.note == null ? "" : " (note: " + d.note + ")") + ".", false);
        m.put("created", true);
        return withLink(m, a);
    }

    // ── Flow helpers ─────────────────────────────────────────────────────────

    private Map<String, Object> advance(HttpSession session, Draft d, AiAccess a) {
        if (d.complete()) {
            d.stage = Stage.CONFIRM; d.token = UUID.randomUUID().toString(); d.created = Instant.now();
            session.setAttribute(SESSION_KEY, d);
            Map<String, Object> m = base("meeting.confirm", "Ready to create a one-time " + d.typeName + " meeting on " + fmtDate(d.date)
                + timeText(d) + (d.note == null ? "" : " (note: " + d.note + ")") + ". Shall I create it?", false);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("token", d.token);
            Map<String, String> summary = new LinkedHashMap<>();
            summary.put("Category", d.typeName); summary.put("Date", fmtDate(d.date));
            summary.put("Time", d.start == null ? "not set" : fmtTime(d.start) + (d.end == null ? "" : " – " + fmtTime(d.end)));
            summary.put("Repeats", "One-time");
            if (d.note != null) summary.put("Note", d.note);
            c.put("summary", summary);
            m.put("confirm", c);
            return m;
        }
        // Something is missing — ask, with the church's categories when that is what is missing.
        d.stage = Stage.ASK; d.created = Instant.now();
        session.setAttribute(SESSION_KEY, d);
        List<String> missing = new ArrayList<>();
        StringBuilder ask = new StringBuilder();
        if (d.typeId == null) {
            missing.add("category");
            List<MeetingType> types = typeRepo.findByAppClientIdAndDeleteFlagFalseOrderByTypeNameAsc(d.clientId);
            if (types.isEmpty()) {
                clear(session);
                return withLink(base("meeting.ask", "There are no meeting categories yet — add one under Meeting Types first, then ask me again.", false), a);
            }
            if (d.typeName != null) ask.append("Several categories match \"").append(d.typeName).append("\". ");
            ask.append("Which category is the meeting? ");
            List<String> names = new ArrayList<>();
            for (int i = 0; i < types.size() && i < maxRows; i++) names.add(types.get(i).getTypeName());
            ask.append(String.join(", ", names));
            if (types.size() > maxRows) ask.append(" … (more on the Meeting Types page)");
            ask.append(". ");
        }
        if (d.date == null) { missing.add("date"); ask.append("What date is the meeting? "); }
        if (d.start == null && d.timeHint != null) { missing.add("time"); ask.append("Is ").append(d.timeHint).append(" o'clock AM or PM? "); }
        Map<String, Object> m = base("meeting.ask", ask.toString().trim(), false);
        m.put("missing", missing);
        m.put("pending", true);
        return m;
    }

    /**
     * Fills the draft from free text. {@code initial} = the message that asked to create (the
     * verb/"meeting" words are removed before the category is looked for). Returns true when any
     * field was set.
     */
    boolean fill(Draft d, String text, LocalDate today, String clientId, boolean initial) {
        boolean any = false;
        String rest = text;
        if (d.date == null) {
            Parsed<LocalDate> p = parseDate(rest, today);
            if (p != null) { d.date = p.value; rest = p.rest; any = true; }
        }
        if (d.start == null && d.timeHint != null) {                     // answer to "AM or PM?"
            Matcher mer = MERIDIEM_ONLY.matcher(rest);
            if (mer.matches()) {
                boolean pm = mer.group(1).toLowerCase(Locale.ROOT).startsWith("p") || mer.group(1).toLowerCase(Locale.ROOT).contains("evening")
                          || mer.group(1).toLowerCase(Locale.ROOT).contains("afternoon") || mer.group(1).toLowerCase(Locale.ROOT).contains("night");
                LocalTime t = time(d.timeHint, null, pm ? "pm" : "am");
                if (t != null) { d.start = t; d.timeHint = null; return true; }
            }
        }
        if (d.start == null) {
            Parsed<LocalTime[]> t = parseTime(rest);
            if (t != null) { d.start = t.value[0]; d.end = t.value[1]; d.timeHint = null; rest = t.rest; any = true; }
            else {
                Matcher am = AMBIGUOUS_HOUR.matcher(rest);                // "at 7" — AM or PM?
                if (am.find()) { d.timeHint = am.group(1); rest = cut(rest, am); any = true; }
            }
        }
        String noteText = null;
        if (d.note == null) {
            Matcher n = NOTE.matcher(rest);
            if (n.find()) { noteText = cap(n.group(2).trim()); rest = rest.substring(0, n.start()) + " "; }
        }
        if (d.typeId == null) {
            String probe = rest;
            if (initial) probe = CREATE_WORDS.matcher(probe).replaceAll(" ");
            probe = cleanProbe(probe);
            List<MeetingType> types = probe.isEmpty() && noteText == null ? List.of()
                    : typeRepo.findByAppClientIdAndDeleteFlagFalseOrderByTypeNameAsc(clientId);
            List<MeetingType> hits = probe.isEmpty() ? List.of() : matchTypes(types, probe);
            if (hits.isEmpty() && noteText != null) {                      // "a meeting about Bible Study" → the category, not a note
                hits = matchTypes(types, cleanProbe(noteText));
                if (hits.size() == 1) noteText = null;
            }
            if (hits.size() == 1) { d.typeId = hits.get(0).getId(); d.typeName = hits.get(0).getTypeName(); any = true; }
            else if (hits.size() > 1) { d.typeName = probe; any = true; }   // ambiguous → the ask lists the categories
        }
        if (noteText != null && !noteText.isEmpty()) { d.note = noteText; any = true; }
        return any;
    }

    private static String cleanProbe(String probe) {
        return probe.replaceAll("(?i)\\b(a|an|the|new|one[- ]time|single|meeting|for|on|at|in|called|named|titled|please|me|us|with|about|it|is|will be)\\b", " ")
                    .replaceAll("[^\\p{L}\\p{N}&' ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Meeting types whose name appears in the probe text (whole words); nested matches collapse to the longest. */
    private static List<MeetingType> matchTypes(List<MeetingType> types, String probe) {
        List<MeetingType> hits = new ArrayList<>();
        String lc = " " + probe.toLowerCase(Locale.ROOT) + " ";
        for (MeetingType t : types) {
            String name = t.getTypeName() == null ? "" : t.getTypeName().trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            if (name.isEmpty()) continue;
            // "Prayer Meeting" is matched by "prayer" too: the word meeting is noise on both sides.
            String bare = name.replaceAll("\\bmeetings?\\b", " ").replaceAll("\\s+", " ").trim();
            if (lc.contains(" " + name + " ") || (!bare.isEmpty() && lc.contains(" " + bare + " "))) hits.add(t);
        }
        if (hits.size() > 1) {
            hits.sort((x, y) -> y.getTypeName().length() - x.getTypeName().length());
            MeetingType top = hits.get(0);
            boolean nested = hits.stream().skip(1).allMatch(h -> top.getTypeName().toLowerCase(Locale.ROOT).contains(h.getTypeName().toLowerCase(Locale.ROOT)));
            if (nested) return List.of(top);
        }
        return hits;
    }

    private static final Pattern MERIDIEM_ONLY = Pattern.compile("(?i)^\\s*(?:in the\\s+)?(a\\.?m\\.?|p\\.?m\\.?|morning|afternoon|evening|night)[.!]?\\s*$");
    private static final Pattern AMBIGUOUS_HOUR = Pattern.compile("(?i)\\b(?:at|@)\\s*(\\d{1,2})(?::(\\d{2}))?\\b(?!\\s*(?:a\\.?m|p\\.?m|/|-|:|st|nd|rd|th|\\d))");

    private static final Pattern CREATE_WORDS = Pattern.compile(
        "(?i)\\b(create|schedule|add|set\\s?up|book|plan|make|arrange|organi[sz]e|can you|could you|i (want|need|would like) to|let'?s|i'?d like to)\\b");
    private static final Pattern NOTE = Pattern.compile("(?i)\\b(about|regarding|note[:s]?|notes?:|topic[:s]?)\\s+(.+)$");

    // ── Parsing ──────────────────────────────────────────────────────────────

    record Parsed<T>(T value, String rest) {}

    private static final String MONTHS = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)";
    private static final String DAYS = "(sun(?:day)?|mon(?:day)?|tue(?:s(?:day)?)?|wed(?:nesday)?|thu(?:rs(?:day)?)?|fri(?:day)?|sat(?:urday)?)";
    private static final Pattern ISO = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    private static final Pattern SLASH = Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b");
    private static final Pattern MON_DAY = Pattern.compile("(?i)\\b" + MONTHS + "\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?\\b");
    private static final Pattern DAY_MON = Pattern.compile("(?i)\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?" + MONTHS + "\\b(?:,?\\s+(\\d{4}))?");
    private static final Pattern REL = Pattern.compile("(?i)\\b(day after tomorrow|tomorrow|today|tonight|this evening|this morning|this afternoon)\\b");
    private static final Pattern WEEKDAY = Pattern.compile("(?i)\\b(?:(this|next|coming)\\s+)?" + DAYS + "\\b");
    private static final Pattern ORDINAL = Pattern.compile("(?i)\\bthe\\s+(\\d{1,2})(?:st|nd|rd|th)\\b");

    /** First recognisable single date in the text; null when none. */
    static Parsed<LocalDate> parseDate(String text, LocalDate today) {
        Matcher m;
        if ((m = ISO.matcher(text)).find()) {
            LocalDate d = safe(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            if (d != null) return new Parsed<>(d, cut(text, m));
        }
        if ((m = SLASH.matcher(text)).find()) {
            int yr = m.group(3) == null ? -1 : Integer.parseInt(m.group(3)); if (yr >= 0 && yr < 100) yr += 2000;
            LocalDate d = monthDay(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), yr, today);
            if (d != null) return new Parsed<>(d, cut(text, m));
        }
        if ((m = MON_DAY.matcher(text)).find()) {
            LocalDate d = monthDay(month(m.group(1)), Integer.parseInt(m.group(2)), m.group(3) == null ? -1 : Integer.parseInt(m.group(3)), today);
            if (d != null) return new Parsed<>(d, cut(text, m));
        }
        if ((m = DAY_MON.matcher(text)).find()) {
            LocalDate d = monthDay(month(m.group(2)), Integer.parseInt(m.group(1)), m.group(3) == null ? -1 : Integer.parseInt(m.group(3)), today);
            if (d != null) return new Parsed<>(d, cut(text, m));
        }
        if ((m = REL.matcher(text)).find()) {
            String w = m.group(1).toLowerCase(Locale.ROOT);
            LocalDate d = w.startsWith("day after") ? today.plusDays(2) : w.equals("tomorrow") ? today.plusDays(1) : today;
            return new Parsed<>(d, cut(text, m));
        }
        if ((m = WEEKDAY.matcher(text)).find()) {
            DayOfWeek dow = dow(m.group(2));
            String qual = m.group(1) == null ? "" : m.group(1).toLowerCase(Locale.ROOT);
            LocalDate d = today.with(TemporalAdjusters.nextOrSame(dow));
            if (qual.equals("next") && d.equals(today)) d = d.plusWeeks(1);
            return new Parsed<>(d, cut(text, m));
        }
        if ((m = ORDINAL.matcher(text)).find()) {
            int day = Integer.parseInt(m.group(1));
            LocalDate d = safe(today.getYear(), today.getMonthValue(), day);
            if (d != null && d.isBefore(today)) d = safe(today.plusMonths(1).getYear(), today.plusMonths(1).getMonthValue(), day);
            if (d != null) return new Parsed<>(d, cut(text, m));
        }
        return null;
    }

    private static final String TIME = "(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?";
    private static final Pattern RANGE = Pattern.compile("(?i)\\b(?:from\\s+)?" + TIME + "\\s*(?:-|–|to|until|till|through)\\s*" + TIME + "\\b");
    private static final Pattern AT = Pattern.compile("(?i)\\b(?:at|@|starting(?: at)?|starts(?: at)?)\\s*" + TIME + "\\b");
    private static final Pattern BARE = Pattern.compile("(?i)\\b(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)\\b");
    private static final Pattern H24 = Pattern.compile("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b");
    private static final Pattern WORDS = Pattern.compile("(?i)\\b(noon|midday|midnight)\\b");

    /** Start (+ optional end) time; null when none that is unambiguous. "at 7" without am/pm is not accepted. */
    static Parsed<LocalTime[]> parseTime(String text) {
        Matcher m;
        if ((m = RANGE.matcher(text)).find()) {
            String mer1 = m.group(3), mer2 = m.group(6);
            if (mer1 == null) mer1 = mer2;                      // "7 to 8:30pm" → both pm
            LocalTime a = time(m.group(1), m.group(2), mer1), b = time(m.group(4), m.group(5), mer2);
            if (a != null && b != null) return new Parsed<>(new LocalTime[]{a, b}, cut(text, m));
        }
        if ((m = AT.matcher(text)).find()) {
            LocalTime a = time(m.group(1), m.group(2), m.group(3));
            if (a != null) return new Parsed<>(new LocalTime[]{a, null}, cut(text, m));
        }
        if ((m = BARE.matcher(text)).find()) {
            LocalTime a = time(m.group(1), m.group(2), m.group(3));
            if (a != null) return new Parsed<>(new LocalTime[]{a, null}, cut(text, m));
        }
        if ((m = H24.matcher(text)).find()) {
            return new Parsed<>(new LocalTime[]{LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))), null}, cut(text, m));
        }
        if ((m = WORDS.matcher(text)).find()) {
            LocalTime a = m.group(1).toLowerCase(Locale.ROOT).startsWith("mid") && m.group(1).toLowerCase(Locale.ROOT).contains("night") ? LocalTime.MIDNIGHT : LocalTime.NOON;
            return new Parsed<>(new LocalTime[]{a, null}, cut(text, m));
        }
        return null;
    }

    /** Hour/minute with meridiem → LocalTime; a 1–12 hour without am/pm is ambiguous → null unless written with minutes as 24h. */
    private static LocalTime time(String h, String min, String mer) {
        int hh = Integer.parseInt(h), mm = min == null ? 0 : Integer.parseInt(min);
        if (mm > 59) return null;
        if (mer != null) {
            boolean pm = mer.toLowerCase(Locale.ROOT).startsWith("p");
            if (hh < 1 || hh > 12) return null;
            if (hh == 12) hh = pm ? 12 : 0; else if (pm) hh += 12;
            return LocalTime.of(hh, mm);
        }
        if (min != null && hh <= 23) return LocalTime.of(hh, mm);     // "19:00"
        return null;
    }

    private static LocalDate monthDay(int month, int day, int year, LocalDate today) {
        if (month < 1 || month > 12) return null;
        if (year > 0) return safe(year, month, day);
        LocalDate d = safe(today.getYear(), month, day);
        if (d == null) return null;
        if (d.isBefore(today.minusDays(7))) d = safe(today.getYear() + 1, month, day);   // "Jan 5" said in November → next year
        return d;
    }
    private static LocalDate safe(int y, int m, int d) { try { return LocalDate.of(y, m, d); } catch (Exception e) { return null; } }
    private static int month(String s) {
        String k = s.toLowerCase(Locale.ROOT).substring(0, 3);
        for (Month mo : Month.values()) if (mo.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toLowerCase(Locale.ROOT).equals(k)) return mo.getValue();
        return -1;
    }
    private static DayOfWeek dow(String s) {
        String k = s.toLowerCase(Locale.ROOT).substring(0, 3);
        for (DayOfWeek d : DayOfWeek.values()) if (d.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toLowerCase(Locale.ROOT).equals(k)) return d;
        return DayOfWeek.SUNDAY;
    }
    private static String cut(String text, Matcher m) { return (text.substring(0, m.start()) + " " + text.substring(m.end())).trim(); }
    private static String cap(String s) { return s.length() > 500 ? s.substring(0, 500) : s; }

    // ── Output helpers ───────────────────────────────────────────────────────

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    static String fmtDate(LocalDate d) { return d.format(DATE_FMT); }
    static String fmtTime(LocalTime t) { return t.format(TIME_FMT); }
    static String hhmm(LocalTime t) { return String.format("%02d:%02d", t.getHour(), t.getMinute()); }
    private static String timeText(Draft d) {
        if (d.start == null) return " (no time set)";
        return " at " + fmtTime(d.start) + (d.end == null ? "" : " – " + fmtTime(d.end));
    }

    private Draft pending(HttpSession session, String clientId) {
        Object o = session.getAttribute(SESSION_KEY);
        if (!(o instanceof Draft d)) return null;
        if (!clientId.equals(d.clientId) || d.created == null
                || d.created.plusSeconds(60L * Math.max(1, draftMinutes)).isBefore(Instant.now())) {
            session.removeAttribute(SESSION_KEY);
            return null;
        }
        return d;
    }
    private static void clear(HttpSession s) { if (s != null) s.removeAttribute(SESSION_KEY); }

    private static Map<String, Object> base(String intent, String answer, boolean denied) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("handled", true); m.put("denied", denied); m.put("intent", intent); m.put("answer", answer);
        return m;
    }
    private static Map<String, Object> denied() { return base("meeting.denied", DENIED_MSG, true); }
    private static Map<String, Object> expired() {
        return base("meeting.expired", "That confirmation is no longer valid (it was already used or has expired). Ask me again to create the meeting.", false);
    }
    private static Map<String, Object> withLink(Map<String, Object> m, AiAccess a) {
        if (a != null && a.canOpen(MEETINGS_URL)) {
            Map<String, String> link = new LinkedHashMap<>(); link.put("label", "Open Meetings"); link.put("url", MEETINGS_URL);
            m.put("link", link);
        }
        return m;
    }
}
