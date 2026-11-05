package com.churchgeniuspro.service;

import com.churchgeniuspro.repository.AiCrossReferenceRepository;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.AiAccessPolicy.CrossAccess;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI Search Phase 1 (approved 2026-10-31) — cross-referenced questions that combine two modules,
 * answered with fixed rules (no AI model call, no new cost):
 * <ol>
 *   <li>lapsed attenders — "who came on Easter but hasn't attended since February", "regulars we haven't seen in a month"</li>
 *   <li>regular attenders not in any group</li>
 *   <li>Connect With Us visitors not followed up</li>
 *   <li>members who gave last year (month, quarter) but not this one</li>
 *   <li>regular attenders who are not serving</li>
 * </ol>
 *
 * <p>Safeguards (the approval's conditions):
 * <ul>
 *   <li>Every module a question reads must pass its page rule ({@link AiAccessPolicy}: role guard +
 *       page permission + plan feature). If ANY is missing the whole question is refused and the
 *       missing module is named — never a partial answer.</li>
 *   <li>The church is always the session's; every query filters every table by it.</li>
 *   <li>Follow-ups keep their ownership rule: non-admins only see items assigned to them.</li>
 *   <li>Group membership is confirmed only by member link or a unique exact email; same-name group
 *       rows are reported separately as unverified.</li>
 *   <li>Event registrations are never used to identify people.</li>
 *   <li>Results are capped ({@code ai.search.max-rows}) and windows longer than
 *       {@code ai.search.max-range-days} are refused with a request to narrow.</li>
 * </ul>
 * The definitions (regular, lapsed, open follow-up) are configurable properties, shown with every answer.
 */
@Service
public class AiCrossReferenceService {

    /** Thresholds in one place, so a later phase can make them per-church settings. */
    public record Thresholds(int regularMinDays, int regularWindowWeeks, int lapsedWeeks, int followUpOpenDays, int servingWindowMonths) {
        public static Thresholds defaults() { return new Thresholds(3, 12, 4, 14, 6); }
    }

    private final AiCrossReferenceRepository repo;

    @Value("${ai.search.max-rows:20}")                        public int maxRows = 20;
    @Value("${ai.search.max-range-days:400}")                 public int maxRangeDays = 400;
    @Value("${ai.crossref.regular-min-days:3}")               public int regularMinDays = 3;
    @Value("${ai.crossref.regular-window-weeks:12}")          public int regularWindowWeeks = 12;
    @Value("${ai.crossref.lapsed-weeks:4}")                   public int lapsedWeeks = 4;
    @Value("${ai.crossref.followup-open-days:14}")            public int followUpOpenDays = 14;
    @Value("${ai.crossref.serving-window-months:6}")          public int servingWindowMonths = 6;

    public AiCrossReferenceService(AiCrossReferenceRepository repo) { this.repo = repo; }

    public Thresholds thresholds() {
        return new Thresholds(regularMinDays, regularWindowWeeks, lapsedWeeks, followUpOpenDays, servingWindowMonths);
    }

    /* ════════════════════════ routing ════════════════════════ */

    private static boolean has(String q, String regex) { return Pattern.compile("(?<![a-z0-9])(?:" + regex + ")(?![a-z0-9])").matcher(q).find(); }

    static final String ATTEND  = "attend\\w*|attenders?|attendees?|came|been coming|stopped coming|showed up|show up|lapsed|regular attenders?|regulars|haven'?t seen|hasn'?t seen|not seen|seen in";
    static final String NEGATE  = "not|no|never|without|aren'?t|isn'?t|haven'?t|hasn'?t|didn'?t|don'?t|doesn'?t|stopped|no longer|missing|absent";
    static final String GROUPS  = "groups?|small groups?|life groups?|bible study groups?|cell groups?";
    static final String VISITOR = "visitors?|first[- ]time\\w*|newcomers?|new guests?|guests?|connect (?:with us )?(?:forms?|submissions?|cards?)|connect forms?";
    static final String FOLLOW  = "follow[- ]?ups?|followed[- ]up|follow[- ]?up tasks?|contacted";

    /**
     * Returns null when the question is not one of the five (the caller continues with the other
     * intents). {@code q} is the normalised, synonym-mapped question (AiQueryLexicon).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> route(String q, AiAccess a, CrossAccess x, String cid, LocalDate today) {
        if (q == null || a == null || a.kids()) return null;
        if (x == null) x = CrossAccess.none();
        boolean attend = has(q, ATTEND);
        boolean negate = has(q, NEGATE);
        if (attend && negate && has(q, GROUPS))                                  return notInGroup(q, a, x, cid, today);
        if (attend && negate && has(q, "serving|volunteers|serve|serves"))       return notServing(q, a, x, cid, today);
        if (has(q, VISITOR) && has(q, FOLLOW) && negate)                         return visitorsNoFollowUp(q, a, cid, today);
        if (AiQueryLexicon.isGiving(q) && negate && has(q, "last year|last month|last quarter|previous year|previous month|previous quarter")) return lapsedGivers(q, a, cid, today);
        if (attend && negate)                                                    return lapsedAttenders(q, a, x, cid, today);
        return null;
    }

    /* ════════════════════════ 1. lapsed attenders ════════════════════════ */

    record Window(LocalDate refFrom, LocalDate refTo, int minDays, LocalDate lapseFrom, String refLabel, String lapseLabel, boolean regular) {}

    Window lapsedWindow(String q, LocalDate today) {
        LocalDate refDay = null; String refName = null;
        LocalDate lapseFrom = explicitSince(q, today);
        // The reference day is the most recent one BEFORE the "since" date when one is given
        // ("came last Easter but hasn't attended since February" asked in October = last year's Easter).
        LocalDate before = lapseFrom != null ? lapseFrom.minusDays(1) : today;
        if (has(q, "easter")) { refDay = mostRecentEaster(before); refName = "Easter Sunday"; }
        else if (has(q, "christmas")) {
            LocalDate eve = LocalDate.of(before.getYear(), 12, 24);
            if (eve.plusDays(1).isAfter(before)) eve = eve.minusYears(1);
            refDay = eve; refName = "Christmas (Dec 24–25)";
        }
        if (refDay != null) {
            LocalDate refTo = refName.startsWith("Christmas") ? refDay.plusDays(1) : refDay;
            if (lapseFrom == null) lapseFrom = refTo.plusDays(1);
            return new Window(refDay, refTo, 1, lapseFrom, refName + " (" + range(refDay, refTo) + ")", lapseText(lapseFrom, today), false);
        }
        if (lapseFrom == null) lapseFrom = today.minusWeeks(lapsedWeeks).plusDays(1);
        LocalDate refTo = lapseFrom.minusDays(1);
        LocalDate refFrom = refTo.minusWeeks(regularWindowWeeks).plusDays(1);
        return new Window(refFrom, refTo, regularMinDays, lapseFrom, "the " + regularWindowWeeks + " weeks before that (" + range(refFrom, refTo) + ")",
                lapseText(lapseFrom, today), true);
    }

    private Map<String, Object> lapsedAttenders(String q, AiAccess a, CrossAccess x, String cid, LocalDate today) {
        Map<String, Object> deny = requireAll("lapsed attenders", missing(x.attendance(), "Attendance")); if (deny != null) return deny;
        Window w = lapsedWindow(q, today);
        if (!w.lapseFrom().isAfter(w.refTo()) || w.lapseFrom().isAfter(today))
            return clarify("lapsed", "Which period should I check? For example: “who came on Easter but hasn't attended since June”, or “regulars we haven't seen in 4 weeks”.");
        Map<String, Object> narrow = tooWide(w.refFrom(), w.refTo()); if (narrow == null) narrow = tooWide(w.lapseFrom(), today);
        if (narrow != null) return narrow;

        List<Object[]> rows = repo.lapsedAttenders(cid, w.refFrom(), w.refTo(), w.minDays(), w.lapseFrom(), today, maxRows + 1);
        long total = total(rows);
        List<String> defs = new ArrayList<>();
        if (w.regular()) defs.add("Regular attender: attended on " + w.minDays() + " or more separate days in " + w.refLabel() + ".");
        else defs.add("Attended: checked in on " + w.refLabel() + ".");
        defs.add("Not attended since: no check-in " + w.lapseLabel() + ".");
        defs.add(ATTENDANCE_RULE);
        String who = w.regular() ? "regular attender" : "person";
        String answer = total == 0
                ? "No " + (w.regular() ? "regular attenders" : "one who attended " + w.refLabel()) + " have been missing " + w.lapseLabel() + "."
                : total + " " + who + (total == 1 ? "" : "s") + (w.regular() ? "" : " who attended " + w.refLabel()) + " " + (total == 1 ? "has" : "have")
                  + " not attended " + w.lapseLabel() + "." + more(rows.size(), total);
        return people("crossref.lapsed", answer, rows, q, "Days attended", null, "/attendance", "Open Attendance",
                List.of("Attendance"), defs, total);
    }

    /* ════════════════════════ 2. regular attenders not in any group ════════════════════════ */

    private Map<String, Object> notInGroup(String q, AiAccess a, CrossAccess x, String cid, LocalDate today) {
        Map<String, Object> deny = requireAll("attenders not in a group", missing(x.attendance(), "Attendance"), missing(x.groups(), "Groups"));
        if (deny != null) return deny;
        LocalDate from = today.minusWeeks(regularWindowWeeks).plusDays(1);
        List<Object[]> rows = repo.regularNotInGroup(cid, from, today, regularMinDays, maxRows + 1);
        long total = total(rows);
        long nameOnly = total == 0 ? 0 : repo.countNotInGroupWithNameMatch(cid, from, today, regularMinDays);
        List<String> defs = new ArrayList<>(List.of(
                "Regular attender: attended on " + regularMinDays + " or more separate days in the last " + regularWindowWeeks + " weeks (" + range(from, today) + ").",
                "In a group (confirmed): a live group lists them by member record, or by an email that belongs to them alone (a shared family email doesn't count).",
                "Possible match (unverified): a group lists someone with the same first and last name but no member link or matching email — check before acting on it.",
                ATTENDANCE_RULE));
        String answer = total == 0
                ? "Every regular attender is in at least one group."
                : total + " regular attender" + (total == 1 ? " is" : "s are") + " not confirmed in any group."
                  + (nameOnly > 0 ? " " + nameOnly + " of them " + (nameOnly == 1 ? "has" : "have") + " an unverified same-name match in a group (see “Possible match”)." : "")
                  + more(rows.size(), total);
        Map<String, Object> m = people("crossref.notInGroup", answer, rows, q, "Days attended", "Possible match (unverified)", "/groups", "Open Groups",
                List.of("Attendance", "Groups"), defs, total);
        if (total > 0) m.put("summary", summary("Not in a group", String.valueOf(total), "Unverified name matches", String.valueOf(nameOnly)));
        return m;
    }

    /* ════════════════════════ 3. visitors not followed up ════════════════════════ */

    private Map<String, Object> visitorsNoFollowUp(String q, AiAccess a, String cid, LocalDate today) {
        Map<String, Object> deny = requireAll("visitors without follow-up", missing(a.followUps(), "Follow-ups")); if (deny != null) return deny;
        AiDataSearchService.DateRange p = AiDataSearchService.parsePeriod(q, today);
        LocalDate from = p != null ? p.from : today.minusDays(29), to = p != null ? p.to : today;
        String label = p != null ? p.label : "the last 30 days";
        if (to.isAfter(today)) to = today;
        Map<String, Object> narrow = tooWide(from, to); if (narrow != null) return narrow;
        List<Object[]> rows = repo.connectNotFollowedUp(cid, Timestamp.valueOf(from.atStartOfDay()), Timestamp.valueOf(to.plusDays(1).atStartOfDay()),
                a.followUpsAll(), a.username(), maxRows + 1);
        long total = total(rows);
        List<List<String>> out = new ArrayList<>();
        int stale = 0;
        for (Object[] r : rows.subList(0, Math.min(rows.size(), maxRows))) {
            String task;
            if (r[4] == null) task = "No follow-up task";
            else {
                long days = r[6] == null ? 0 : ChronoUnit.DAYS.between(toDate(r[6]), today);
                boolean old = days >= followUpOpenDays; if (old) stale++;
                task = "Open " + days + " day" + (days == 1 ? "" : "s") + (old ? " ⚠" : "");
            }
            out.add(List.of(name(r[0], r[1]).isBlank() ? "(no name)" : name(r[0], r[1]), r[2] == null ? "" : D.format(toDate(r[2])),
                    task, str(r[5]).isBlank() ? "Unassigned" : str(r[5])));
        }
        String scope = a.followUpsAll() ? "" : " (only follow-ups assigned to you)";
        List<String> defs = List.of(
                "Visitors: Connect With Us submissions received " + label + " (" + range(from, to) + ")" + scope + ".",
                "Not followed up: the submission isn't marked Contacted or Closed, and its follow-up task is missing or not completed.",
                "Open follow-up: a task still open " + followUpOpenDays + " or more days after it was created is marked ⚠.",
                "Visitors who only checked in through Attendance aren't included: follow-up tasks are not linked to attendance visitor records.");
        String answer = total == 0
                ? "Every Connect With Us visitor from " + label + " has been followed up" + scope + "."
                : total + " Connect With Us visitor" + (total == 1 ? " hasn't" : "s haven't") + " been followed up (" + label + ")" + scope + "."
                  + (stale > 0 ? " " + stale + (rows.size() > maxRows ? " of those shown" : "") + " ha" + (stale == 1 ? "s" : "ve") + " a task open " + followUpOpenDays + "+ days." : "")
                  + more(rows.size(), total);
        Map<String, Object> m = base("crossref.visitors", answer);
        m.put("columns", List.of("Visitor", "Received", "Follow-up", "Assigned to")); m.put("rows", out);
        m.put("shown", out.size()); m.put("truncated", rows.size() > maxRows); m.put("total", total);
        finish(m, "/followups?tab=connect", "Open Follow-ups", List.of("Follow-ups (Connect With Us)"), defs);
        return m;
    }

    /* ════════════════════════ 4. lapsed givers ════════════════════════ */

    record Periods(LocalDate prevFrom, LocalDate prevTo, LocalDate curFrom, LocalDate curTo, String prevLabel, String curLabel) {}

    static Periods givingPeriods(String q, LocalDate today) {
        if (has(q, "last month|previous month")) {
            LocalDate cur = today.withDayOfMonth(1), prev = cur.minusMonths(1);
            return new Periods(prev, cur.minusDays(1), cur, today, "last month (" + prev.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.US) + ")", "this month so far");
        }
        if (has(q, "last quarter|previous quarter")) {
            LocalDate cur = today.with(IsoFields.DAY_OF_QUARTER, 1), prev = cur.minusMonths(3);
            return new Periods(prev, cur.minusDays(1), cur, today, "last quarter", "this quarter so far");
        }
        LocalDate cur = today.withDayOfYear(1), prev = cur.minusYears(1);
        return new Periods(prev, cur.minusDays(1), cur, today, "last year (" + prev.getYear() + ")", "this year so far (" + cur.getYear() + ")");
    }

    private Map<String, Object> lapsedGivers(String q, AiAccess a, String cid, LocalDate today) {
        Map<String, Object> deny = requireAll("lapsed givers", missing(a.incomeRecords(), "Income")); if (deny != null) return deny;
        Periods p = givingPeriods(q, today);
        if (ChronoUnit.DAYS.between(p.prevFrom(), p.prevTo()) + 1 > maxRangeDays || ChronoUnit.DAYS.between(p.curFrom(), p.curTo()) + 1 > maxRangeDays)
            return tooWide(p.prevFrom(), p.curTo());
        List<Object[]> rows = repo.lapsedGivers(cid, p.prevFrom(), p.prevTo(), p.curFrom(), p.curTo(), maxRows + 1);
        long total = total(rows);
        List<List<String>> out = new ArrayList<>();
        for (Object[] r : rows.subList(0, Math.min(rows.size(), maxRows)))
            out.add(List.of(name(r[1], r[2]), r[3] == null ? "" : D.format(toDate(r[3])), money(r[5]), String.valueOf(num(r[4]).intValue())));
        List<String> defs = List.of(
                "Gave: a contribution recorded on the Income page and linked to the member (guest gifts can't be matched to a person).",
                "Compared: " + p.prevLabel() + " (" + range(p.prevFrom(), p.prevTo()) + ") with " + p.curLabel() + " (" + range(p.curFrom(), p.curTo()) + ").",
                "Amounts are gross contributions; online processing fees are recorded separately as expenses.",
                "Only members who are not deleted or inactive are listed.");
        String answer = total == 0
                ? "Everyone who gave " + p.prevLabel() + " has also given " + p.curLabel() + "."
                : total + " member" + (total == 1 ? "" : "s") + " gave " + p.prevLabel() + " but " + (total == 1 ? "hasn't" : "haven't") + " given " + p.curLabel() + "." + more(rows.size(), total);
        Map<String, Object> m = base("crossref.lapsedGivers", answer);
        m.put("columns", List.of("Member", "Last gift", "Given " + p.prevLabel().replaceAll(" \\(.*\\)", ""), "Gifts")); m.put("rows", out);
        m.put("shown", out.size()); m.put("truncated", rows.size() > maxRows); m.put("total", total);
        finish(m, "/income", "Open Income", List.of("Income"), defs);
        return m;
    }

    /* ════════════════════════ 5. regular attenders not serving ════════════════════════ */

    private Map<String, Object> notServing(String q, AiAccess a, CrossAccess x, String cid, LocalDate today) {
        Map<String, Object> deny = requireAll("attenders not serving", missing(x.attendance(), "Attendance"), missing(a.volunteers(), "Volunteers"));
        if (deny != null) return deny;
        LocalDate from = today.minusWeeks(regularWindowWeeks).plusDays(1);
        LocalDate servingFrom = today.minusMonths(servingWindowMonths);
        List<Object[]> rows = repo.regularNotServing(cid, from, today, regularMinDays, servingFrom, maxRows + 1);
        long total = total(rows);
        List<String> defs = List.of(
                "Regular attender: attended on " + regularMinDays + " or more separate days in the last " + regularWindowWeeks + " weeks (" + range(from, today) + ").",
                "Serving: has a volunteer assignment (not declined) dated since " + D.format(servingFrom) + ", including upcoming ones.",
                ATTENDANCE_RULE);
        String answer = total == 0
                ? "Every regular attender has served (or is scheduled to serve) since " + D.format(servingFrom) + "."
                : total + " regular attender" + (total == 1 ? " has" : "s have") + " no volunteer assignment since " + D.format(servingFrom) + "." + more(rows.size(), total);
        return people("crossref.notServing", answer, rows, q, "Days attended", null, "/volunteers", "Open Volunteers",
                List.of("Attendance", "Volunteers"), defs, total);
    }

    /* ════════════════════════ permissions ════════════════════════ */

    /** What each module needs — shown when a combined question is refused. */
    static final Map<String, String> REQUIREMENT = Map.of(
            "Attendance", "Attendance (Admin, SuperAdmin or User role with the Attendance permission, and Attendance in your plan)",
            "Groups", "Groups (Admin or SuperAdmin role with the Groups permission, and Groups in your plan)",
            "Volunteers", "Volunteers (Admin, SuperAdmin or User role with the Volunteers permission, and Volunteer Scheduling in your plan)",
            "Follow-ups", "Follow-ups (Admin, SuperAdmin or User role with the Follow-ups permission, and Follow-ups in your plan)",
            "Income", "Income (Admin, SuperAdmin or Accountant role with the Income permission, and Accounting in your plan)");

    private static String missing(boolean allowed, String module) { return allowed ? null : module; }

    /** Decision 1: refuse the WHOLE question if any module is missing, and name it. */
    private static Map<String, Object> requireAll(String topic, String... missing) {
        List<String> gone = new ArrayList<>();
        for (String s : missing) if (s != null) gone.add(s);
        if (gone.isEmpty()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("handled", true); m.put("denied", true); m.put("intent", "crossref.denied");
        StringBuilder sb = new StringBuilder("I can't answer this question because it needs access you don't have: ");
        for (int i = 0; i < gone.size(); i++) sb.append(i == 0 ? "" : "; ").append(REQUIREMENT.getOrDefault(gone.get(i), gone.get(i)));
        sb.append(". Please contact your administrator if you believe this is an error.");
        m.put("answer", sb.toString());
        m.put("missing", gone);
        return m;
    }

    /* ════════════════════════ shared output ════════════════════════ */

    static final String ATTENDANCE_RULE = "Attendance counts Present and Late check-ins of active members (not deleted or inactive), one per day.";

    private Map<String, Object> people(String intent, String answer, List<Object[]> rows, String q, String countCol, String extraCol,
                                       String url, String label, List<String> sources, List<String> defs, long total) {
        boolean byCampus = has(q, "campus|campuses|by site|per site");
        List<Object[]> shown = new ArrayList<>(rows.subList(0, Math.min(rows.size(), maxRows)));
        if (byCampus) shown.sort(Comparator.comparing((Object[] r) -> str(r[6]).isBlank() ? "~" : str(r[6]).toLowerCase(Locale.ROOT)));
        List<String> cols = new ArrayList<>(List.of("Name", "Last attended", countCol));
        if (extraCol != null) cols.add(extraCol);
        if (byCampus) cols.add("Campus");
        List<List<String>> out = new ArrayList<>();
        for (Object[] r : shown) {
            List<String> row = new ArrayList<>(List.of(name(r[1], r[2]), r[3] == null ? "" : D.format(toDate(r[3])), String.valueOf(num(r[4]).intValue())));
            if (extraCol != null) row.add(str(r[5]).isBlank() ? "" : str(r[5]));
            if (byCampus) row.add(str(r[6]).isBlank() ? "(no campus)" : str(r[6]));
            out.add(row);
        }
        Map<String, Object> m = base(intent, answer);
        m.put("columns", cols); m.put("rows", out); m.put("shown", out.size()); m.put("truncated", rows.size() > maxRows); m.put("total", total);
        List<String> d = new ArrayList<>(defs);
        if (byCampus) d.add("Campus: the campus on each person's most recent check-in" + (rows.size() > maxRows ? "; the rows shown are sorted by campus." : "."));
        finish(m, url, label, sources, d);
        return m;
    }

    private static void finish(Map<String, Object> m, String url, String label, List<String> sources, List<String> defs) {
        m.put("link", Map.of("label", label, "url", url));
        m.put("sources", sources);
        m.put("definitions", defs);
    }

    private Map<String, Object> tooWide(LocalDate from, LocalDate to) {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        if (days <= maxRangeDays) return null;
        return base("narrow", "That would look back " + days + " days, which is more than this search can check at once (" + maxRangeDays
                + " days). Please choose a shorter period — for example “since June” or “in the last 8 weeks”.");
    }

    private static Map<String, Object> base(String intent, String answer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("handled", true); m.put("denied", false); m.put("intent", intent); m.put("answer", answer);
        return m;
    }
    private static Map<String, Object> clarify(String topic, String question) {
        Map<String, Object> m = base("clarify", question); m.put("topic", topic); return m;
    }
    private static Map<String, String> summary(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
    private String more(int fetched, long total) { return fetched > maxRows ? " Showing the first " + maxRows + "." : ""; }
    private static long total(List<Object[]> rows) { return rows == null || rows.isEmpty() ? 0 : num(rows.get(0)[rows.get(0).length - 1]).longValue(); }

    /* ════════════════════════ dates ════════════════════════ */

    private static final Map<String, Month> MONTHS = new LinkedHashMap<>();
    static {
        for (Month mo : Month.values()) {
            String n = mo.getDisplayName(java.time.format.TextStyle.FULL, Locale.US).toLowerCase(Locale.ROOT);
            MONTHS.put(n, mo); MONTHS.put(n.substring(0, 3), mo);
        }
        MONTHS.put("sept", Month.SEPTEMBER);
    }
    private static final Pattern SINCE_MONTH = Pattern.compile("since (?:early |mid[- ]|late )?(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec)\\b");
    private static final Pattern IN_LAST = Pattern.compile("(?:in|for|during|over)\\s+(?:the\\s+)?(?:last|past)?\\s*(\\d{1,2}|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\\s+(days?|weeks?|months?)\\b");

    /** "since February" → Feb 1 (most recent); "in the last two months" / "in a month" → that many back. Null when not stated. */
    static LocalDate explicitSince(String q, LocalDate today) {
        Matcher m = SINCE_MONTH.matcher(q);
        if (m.find()) {
            Month mo = MONTHS.get(m.group(1));
            LocalDate d = LocalDate.of(today.getYear(), mo, 1);
            return d.isAfter(today) ? d.minusYears(1) : d;
        }
        m = IN_LAST.matcher(q);
        if (m.find()) {
            int n = count(m.group(1));
            String unit = m.group(2);
            if (n <= 0) return null;
            return unit.startsWith("day") ? today.minusDays(n - 1L) : unit.startsWith("week") ? today.minusWeeks(n).plusDays(1) : today.minusMonths(n).plusDays(1);
        }
        return null;
    }

    private static int count(String w) {
        return switch (w) {
            case "a", "an", "one" -> 1; case "two" -> 2; case "three" -> 3; case "four" -> 4; case "five" -> 5; case "six" -> 6;
            case "seven" -> 7; case "eight" -> 8; case "nine" -> 9; case "ten" -> 10; case "eleven" -> 11; case "twelve" -> 12;
            default -> { try { yield Integer.parseInt(w); } catch (NumberFormatException e) { yield -1; } }
        };
    }

    /** Easter Sunday (Gregorian, anonymous algorithm). */
    static LocalDate easter(int y) {
        int a = y % 19, b = y / 100, c = y % 100, d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3,
            h = (19 * a + b - d - g + 15) % 30, i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7, m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31, day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(y, month, day);
    }

    /** The most recent Easter on or before today. */
    static LocalDate mostRecentEaster(LocalDate today) {
        LocalDate e = easter(today.getYear());
        return e.isAfter(today) ? easter(today.getYear() - 1) : e;
    }

    private static String lapseText(LocalDate from, LocalDate today) { return "since " + D.format(from) + (from.equals(today) ? "" : " (through " + D.format(today) + ")"); }

    /* ════════════════════════ formatting ════════════════════════ */

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    private static String range(LocalDate f, LocalDate t) { return f.equals(t) ? D.format(f) : D.format(f) + " – " + D.format(t); }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static String name(Object f, Object l) { return (str(f) + " " + str(l)).trim(); }
    private static BigDecimal num(Object o) { return o == null ? BigDecimal.ZERO : o instanceof BigDecimal b ? b : new BigDecimal(o.toString()); }
    private static String money(Object o) { return NumberFormat.getCurrencyInstance(Locale.US).format(num(o)); }
    public static LocalDate toDate(Object o) {
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof Timestamp t) return t.toLocalDateTime().toLocalDate();
        if (o instanceof java.time.LocalDateTime t) return t.toLocalDate();
        if (o instanceof java.time.OffsetDateTime t) return t.toLocalDate();
        if (o instanceof java.time.Instant t) return t.atZone(com.churchgeniuspro.util.AppClock.ZONE).toLocalDate();
        if (o instanceof java.util.Date d) return new java.sql.Date(d.getTime()).toLocalDate();
        return LocalDate.parse(String.valueOf(o).substring(0, 10));
    }

}
