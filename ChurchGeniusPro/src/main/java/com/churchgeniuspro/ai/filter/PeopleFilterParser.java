package com.churchgeniuspro.ai.filter;

import com.churchgeniuspro.ai.filter.PeopleFilter.*;
import com.churchgeniuspro.service.AiDataSearchService;
import com.churchgeniuspro.service.AiQueryLexicon;

import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI Phase 1 (2026-11-04): plain English → {@link PeopleFilter}, deterministically (no model).
 *
 * <p>Input is the AI Search's canonical question ({@code AiDataSearchService.norm} +
 * {@link AiQueryLexicon#canonical}: lower-case, no punctuation, apostrophes removed, giving
 * synonyms → "contribution", helpers → "volunteers"). The parser:
 * <ol>
 *   <li>lifts the whole-question facts that contain "and" ("between 18 and 30", "born in March");</li>
 *   <li>splits the rest into clauses on but / and / who / that / which / while / comma;</li>
 *   <li>reads each clause with its own negation ("attended twice <b>but didn't give</b>");</li>
 *   <li>names (fund, group, campaign, event) are matched against the church's own lists in
 *       {@link Context}, longest first, whole words — never guessed.</li>
 * </ol>
 * It returns {@link Result#filter} plus {@link Result#claim}: whether the filter route should
 * take the question at all. One plain condition the older routes already answer (giving only,
 * attendance only, volunteers only, status lists, children) is left to them, so their answers
 * are unchanged; two or more conditions, or one the older routes cannot express (age, gender,
 * birth month, guest/visitor, a named group, a campaign, an event, missing contact details),
 * are claimed.
 */
public final class PeopleFilterParser {

    /** The church's own names the parser may recognise (already lower-cased by {@link #normName}). */
    public record Context(List<String> funds, List<String> groups, List<String> campaigns, List<String> events,
                          int regularMinDays, int regularWindowWeeks, int lapsedWeeks, int servingWindowMonths) {
        public static Context empty() { return new Context(List.of(), List.of(), List.of(), List.of(), 3, 12, 4, 6); }
    }

    /** {@code claim} = the filter route should answer; {@code clarify} = a question to ask instead (e.g. an event with no name). */
    public record Result(PeopleFilter filter, boolean claim, String clarify, List<String> unknownWords) {}

    private final Context ctx;
    private final LocalDate today;

    public PeopleFilterParser(Context ctx, LocalDate today) {
        this.ctx = ctx == null ? Context.empty() : ctx;
        this.today = today;
    }

    /* ─────────────────────────── vocabulary ─────────────────────────── */

    static final String NEGATE  = "not|no|never|without|arent|isnt|havent|hasnt|didnt|dont|doesnt|werent|wasnt|stopped|no longer|missing|absent|lacking|lack|unassigned|non|nothing|none|zero|nobody";
    static final String ATTEND  = "attend\\w*|attenders?|attendees?|came|come|coming|been coming|showed up|show up|checked in|check in|check-in|present|regulars?|lapsed|seen";
    static final String GROUPS  = "groups?|small groups?|life groups?|bible study groups?|cell groups?|home groups?";
    static final String SERVE   = "serving|serve|serves|served|volunteers|volunteering|on a team|on the team|rota|roster";
    static final String PLEDGE  = "pledge|pledged|pledges|pledging|campaign|campaigns";
    static final String EVENT   = "registered for|signed up for|signed up to|rsvp\\w*|registered to|registrations? for|attending the";
    static final String HOWTO   = "how do|how to|how can|how should|where do|where can|what is|what are|explain|help me";

    private static boolean has(String s, String regex) { return Pattern.compile("(?<![a-z0-9])(?:" + regex + ")(?![a-z0-9])").matcher(s).find(); }
    private static Matcher find(String s, String regex) { Matcher m = Pattern.compile("(?<![a-z0-9])(?:" + regex + ")(?![a-z0-9])").matcher(s); return m.find() ? m : null; }

    /* ─────────────────────────── entry ─────────────────────────── */

    public Result parse(String canonicalQuestion) {
        String q = " " + (canonicalQuestion == null ? "" : canonicalQuestion.toLowerCase(Locale.ROOT).trim()) + " ";
        if (q.isBlank() || has(q, HOWTO)) return new Result(new PeopleFilter(List.of()), false, null, List.of());
        List<Predicate> out = new ArrayList<>();
        boolean strong = false;              // a condition the older routes cannot express
        String clarify = null;

        // 1. whole-question facts whose wording contains "and" / month names
        q = lift(q, out);
        for (Predicate p : out) strong = true;

        // 2. clauses
        String[] clauses = q.split("(?<![a-z0-9])(?:but|and|who|that|which|while|plus|also|as well as)(?![a-z0-9])");
        for (String c0 : clauses) {
            String full = " " + c0.trim() + " ";
            if (full.isBlank()) continue;
            // The church's own names first, cut out of the working text so "Youth Group" is a group, not teenagers.
            String fundName = nameIn(full, ctx.funds()), groupName = nameIn(full, ctx.groups()),
                   campaignName = nameIn(full, ctx.campaigns()), eventName = nameIn(full, ctx.events());
            String c = full;
            for (String n : new String[] { groupName, campaignName, eventName }) if (n != null)   // funds stay: "tithe" is also the giving word
                for (String v : variants(n)) c = c.replaceAll("(?<![a-z0-9])" + Pattern.quote(v) + "(?![a-z0-9])", " \u0007 ");
            // Negation applies to what FOLLOWS the negating word in this clause ("regular attenders not in a group":
            // attendance is positive, the group is negated; "havent attended": attendance negated).
            Matcher negM = find(c, NEGATE);
            int negAt = negM == null ? -1 : negM.start();
            boolean neg = negAt >= 0;

            // status / type / role / gender — simple word facts
            if (has(c, "inactive"))                       { add(out, new Status("inactive")); strong = true; }
            else if (has(c, "deleted"))                   { add(out, new Status("deleted")); strong = true; }
            if (has(c, "non members|nonmembers|non member|nonmember")) { add(out, new MemberType("NonMember")); strong = true; }
            else if (has(c, "visitors?"))                 { add(out, new MemberType("Visitor")); strong = true; }
            else if (has(c, "guests?"))                   { add(out, new MemberType("Guest")); strong = true; }
            if (has(c, "men|man|male|males|husbands?|fathers?|dads?|boys?|brothers")) { add(out, new Gender("Male")); strong = true; }
            else if (has(c, "women|woman|female|females|ladies|lady|wives|wife|mothers?|moms?|girls?|sisters")) { add(out, new Gender("Female")); strong = true; }
            if (has(c, "heads? of household|household heads?|primary members?")) { add(out, new Role("Head")); strong = true; }
            else if (has(c, "spouses?"))                  { add(out, new Role("Spouse")); strong = true; }
            else if (has(c, "children|kids|child"))       { add(out, new Role("Child")); }
            if (has(c, "young adults?"))                  { add(out, new AgeBand(18, 30)); strong = true; }
            else if (has(c, "adults?"))                   { add(out, new AgeBand(18, null)); }
            if (has(c, "teens|teenagers|teenager|youth|youths")) { add(out, new AgeBand(13, 19)); strong = true; }
            if (has(c, "seniors?|elderly|older members"))  { add(out, new AgeBand(65, null)); strong = true; }

            // contact details
            Matcher cm = find(c, "(?:no|without|missing|lacking|lack|dont have|do not have|have no|has no)\\s+(?:an?\\s+)?(email|e mail|phone|phone number|mobile)");
            if (cm != null) { add(out, new HasContact(true, cm.group(1).startsWith("e") ? "email" : "phone")); strong = true; }
            else {
                Matcher hm = find(c, "(?:with|have|has|having)\\s+(?:an?\\s+)?(email|e mail|phone|phone number|mobile)(?:\\s+(?:address|number|on file|recorded))?");
                if (hm != null) { add(out, new HasContact(false, hm.group(1).startsWith("e") ? "email" : "phone")); strong = true; }
            }

            // attendance
            if (has(c, ATTEND)) {
                Attended a = attendance(c, negFor(c, ATTEND, negAt));
                if (a != null) add(out, a);
            }
            // giving (AiQueryLexicon already mapped every synonym to "contribution"/"tithe")
            if (AiQueryLexicon.isGiving(c)) {
                AiDataSearchService.DateRange r = period(c, "this year", new AiDataSearchService.DateRange(today.withDayOfYear(1), today, "this year"));
                boolean gNeg = negFor(c, "contribution|tithe", negAt) || has(c, "(?:contribution|tithe)\\s+(?:nothing|none|zero|no|not)");
                add(out, new Gave(gNeg, r.from, r.to, r.label, fundName));
                if (fundName != null) strong = true;
            }
            // groups
            if (has(c, GROUPS) || groupName != null) {
                add(out, new InGroup(negFor(c, GROUPS + "|\\u0007", negAt), groupName));
                if (groupName != null) strong = true;
            }
            // serving
            if (has(c, SERVE) && !has(c, "volunteers? (?:list|page|profiles?)")) {
                add(out, new Serving(negFor(c, SERVE, negAt), today.minusMonths(ctx.servingWindowMonths())));
            }
            // pledges / campaigns
            if (has(c, PLEDGE) || campaignName != null) {
                add(out, new InCampaign(negFor(c, PLEDGE + "|\\u0007", negAt), campaignName));
                strong = true;
            }
            // events
            if (has(c, EVENT)) {
                if (eventName == null) clarify = "Which event do you mean? Please include the event's name, for example “registered for Spring Picnic”.";
                else { add(out, new RegisteredForEvent(negFor(c, EVENT, negAt), eventName)); strong = true; }
            }
        }

        boolean peopleQuestion = has(q, "members?|people|persons?|families|folks|everyone|anyone|who|list|show|find|which|how many|count|adults?|men|women|children|kids|visitors?|guests?|attenders?|givers?|volunteers?|regulars?|seniors?|teens|youth");
        boolean claim = clarify == null && peopleQuestion && (out.size() >= 2 || (out.size() == 1 && strong));
        return new Result(new PeopleFilter(out), claim, clarify, List.of());
    }

    /* ─────────────────────────── pieces ─────────────────────────── */

    /** Negated when the clause's negating word comes before the condition's own trigger word. */
    private static boolean negFor(String c, String trigger, int negAt) {
        if (negAt < 0) return false;
        Matcher m = find(c, trigger);
        return m == null || m.start() > negAt;
    }

    private static void add(List<Predicate> out, Predicate p) {
        for (Predicate x : out) if (x.getClass() == p.getClass() && x.equals(p)) return;
        out.add(p);
    }

    private static final String NUM = "(\\d{1,3})";

    /** Age ranges and birth months — removed from the text so the clause split cannot cut "between 18 and 30". */
    private String lift(String q, List<Predicate> out) {
        Matcher m;
        if ((m = find(q, "(?:between|from|aged?|ages)\\s+" + NUM + "\\s+(?:and|to|through|-)\\s+" + NUM + "(?:\\s+years?(?:\\s+old)?)?")) != null
                || (m = find(q, NUM + "\\s*(?:to|-|\\s)\\s*" + NUM + "\\s+years?(?:\\s+old)?")) != null
                || (m = find(q, "ages?\\s+" + NUM + "\\s+" + NUM)) != null) {
            out.add(new AgeBand(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
            q = cut(q, m);
        }
        String notUnit = "(?!\\s*(?:times?|days?|weeks?|months?|sundays?|services?|dollars?|percent|members|people|visits?|years? ago))";
        if ((m = find(q, "older than\\s+" + NUM + notUnit + "(?:\\s+years?(?:\\s+old)?)?|more than\\s+" + NUM + "\\s+years?(?:\\s+old)?")) != null) {
            out.add(new AgeBand(Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2)) + 1, null)); q = cut(q, m);
        }
        if ((m = find(q, "(?:over|above|at least)\\s+" + NUM + "\\s+years?(?:\\s+old)?|(?:over|above)\\s+" + NUM + notUnit + "|" + NUM + "\\s*(?:\\+|plus|and older|or older|and up|and over|or over)")) != null) {
            String g = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            out.add(new AgeBand(Integer.parseInt(g), null)); q = cut(q, m);
        }
        if ((m = find(q, "(?:younger than|below)\\s+" + NUM + "|under\\s+" + NUM + "s?(?:\\s+years?(?:\\s+old)?)?")) != null) {
            int n = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
            out.add(new AgeBand(null, Math.max(0, n - 1))); q = cut(q, m);
        }
        if ((m = find(q, "(?:born in|birthdays? in|birthday month(?: of| is)?|birthdays? (?:this|during))\\s+(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec)")) != null
                || (m = find(q, "(january|february|march|april|may|june|july|august|september|october|november|december)\\s+birthdays?")) != null) {
            out.add(new BirthMonth(month(m.group(1)).getValue())); q = cut(q, m);
        }
        return q;
    }

    private static String cut(String q, Matcher m) { return q.substring(0, m.start()) + " " + q.substring(m.end()); }

    private Attended attendance(String c, boolean neg) {
        // count
        int min = 1;
        Matcher m;
        String unit = "(?:times?|sundays?|services?|visits?|separate days)";
        if ((m = find(c, "(?:at least|minimum of|a minimum of)\\s+" + NUM + "\\s+" + unit)) != null) min = Integer.parseInt(m.group(1));
        else if ((m = find(c, NUM + "\\s+or more\\s+" + unit)) != null) min = Integer.parseInt(m.group(1));
        else if ((m = find(c, "(?:more than|over)\\s+" + NUM + "\\s+" + unit)) != null) min = Integer.parseInt(m.group(1)) + 1;
        else if ((m = find(c, NUM + "\\s+" + unit)) != null) min = Integer.parseInt(m.group(1));
        else if (has(c, "twice")) min = 2;
        else if (has(c, "three times|thrice")) min = 3;
        else if (has(c, "four times")) min = 4;
        else if (has(c, "five times")) min = 5;
        else if (has(c, "regulars?|regularly|regular attenders?")) min = ctx.regularMinDays();

        LocalDate since = since(c);
        AiDataSearchService.DateRange r;
        if (since != null) r = new AiDataSearchService.DateRange(since, today, "since " + D(since));
        else if (neg) r = period(c, null, new AiDataSearchService.DateRange(today.minusWeeks(ctx.lapsedWeeks()).plusDays(1), today, "in the last " + ctx.lapsedWeeks() + " weeks"));
        else r = period(c, null, new AiDataSearchService.DateRange(today.minusWeeks(ctx.regularWindowWeeks()).plusDays(1), today, "in the last " + ctx.regularWindowWeeks() + " weeks"));
        if (r.to.isAfter(today)) r = new AiDataSearchService.DateRange(r.from, today, r.label);
        return new Attended(neg, neg ? 0 : min, r.from, r.to, r.label);
    }

    private static final Pattern SINCE_MONTH = Pattern.compile("since\\s+(?:early |mid[- ]|late )?(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec)\\b(?:\\s+(20\\d{2}))?");
    private static final Pattern SINCE_YEAR = Pattern.compile("since\\s+(20\\d{2})\\b");

    private LocalDate since(String c) {
        if (has(c, "since easter")) return easter(today).plusDays(1);
        if (has(c, "since christmas")) { LocalDate x = LocalDate.of(today.getYear(), 12, 26); return x.isAfter(today) ? x.minusYears(1) : x; }
        Matcher m = SINCE_MONTH.matcher(c);
        if (m.find()) {
            LocalDate d = LocalDate.of(m.group(2) != null ? Integer.parseInt(m.group(2)) : today.getYear(), month(m.group(1)), 1);
            return d.isAfter(today) ? d.minusYears(1) : d;
        }
        m = SINCE_YEAR.matcher(c);
        if (m.find()) return LocalDate.of(Integer.parseInt(m.group(1)), 1, 1);
        return null;
    }

    /** A period in the clause, else the given default. "since …" is handled by the caller. */
    private AiDataSearchService.DateRange period(String c, String ignored, AiDataSearchService.DateRange dflt) {
        LocalDate s = since(c);
        if (s != null) return new AiDataSearchService.DateRange(s, today, "since " + D(s));
        Matcher m = find(c, "(?:in|for|during|over|within)\\s+(?:the\\s+)?(?:last|past)\\s+(\\d{1,3}|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\\s+(days?|weeks?|months?|years?)");
        if (m != null) {
            int n = num(m.group(1)); String u = m.group(2);
            LocalDate from = u.startsWith("day") ? today.minusDays(n - 1L) : u.startsWith("week") ? today.minusWeeks(n).plusDays(1)
                    : u.startsWith("month") ? today.minusMonths(n).plusDays(1) : today.minusYears(n).plusDays(1);
            return new AiDataSearchService.DateRange(from, today, "in the last " + n + " " + u);
        }
        AiDataSearchService.DateRange r = AiDataSearchService.parsePeriod(c, today);
        if (r != null) return r;
        Matcher y = find(c, "in\\s+(20\\d{2})");
        if (y != null) { int yr = Integer.parseInt(y.group(1)); return new AiDataSearchService.DateRange(LocalDate.of(yr, 1, 1), LocalDate.of(yr, 12, 31), String.valueOf(yr)); }
        return dflt;
    }

    private static int num(String w) {
        return switch (w) {
            case "a", "an", "one" -> 1; case "two" -> 2; case "three" -> 3; case "four" -> 4; case "five" -> 5; case "six" -> 6;
            case "seven" -> 7; case "eight" -> 8; case "nine" -> 9; case "ten" -> 10; case "eleven" -> 11; case "twelve" -> 12;
            default -> { try { yield Math.max(1, Integer.parseInt(w)); } catch (NumberFormatException e) { yield 1; } }
        };
    }

    private static final Map<String, Month> MONTHS = new LinkedHashMap<>();
    static {
        for (Month mo : Month.values()) {
            String n = mo.getDisplayName(java.time.format.TextStyle.FULL, Locale.US).toLowerCase(Locale.ROOT);
            MONTHS.put(n, mo); MONTHS.put(n.substring(0, 3), mo);
        }
        MONTHS.put("sept", Month.SEPTEMBER);
    }
    private static Month month(String s) { return MONTHS.get(s.toLowerCase(Locale.ROOT)); }

    static LocalDate easter(LocalDate today) {
        int y = today.getYear();
        LocalDate e = easterOf(y);
        return e.isAfter(today) ? easterOf(y - 1) : e;
    }
    static LocalDate easterOf(int y) {
        int a = y % 19, b = y / 100, c = y % 100, d = b / 4, e = b % 4, f = (b + 8) / 25, g = (b - f + 1) / 3,
            h = (19 * a + b - d - g + 15) % 30, i = c / 4, k = c % 4, l = (32 + 2 * e + 2 * i - h - k) % 7, m = (a + 11 * h + 22 * l) / 451;
        return LocalDate.of(y, (h + l - 7 * m + 114) / 31, ((h + l - 7 * m + 114) % 31) + 1);
    }

    private static String D(LocalDate d) { return d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.US) + " " + d.getDayOfMonth() + ", " + d.getYear(); }

    /** The church name (fund / group / campaign / event) that appears in the clause as whole words — longest first, or null. */
    static String nameIn(String clause, List<String> names) {
        if (names == null || names.isEmpty()) return null;
        String best = null; int bestLen = 0;
        for (String n : names) {
            for (String norm : variants(n)) {
                if (norm.length() < 3) continue;
                if (has(clause, Pattern.quote(norm)) && norm.length() > bestLen) { best = n; bestLen = norm.length(); }
            }
        }
        return best;
    }

    /** "Men's Breakfast" is typed as "mens breakfast" or normalised to "men breakfast" — both must match. */
    static List<String> variants(String name) {
        String a = normName(name);
        String b = String.valueOf(name == null ? "" : name).toLowerCase(Locale.ROOT).replace("’", "'").replace("'", "")
                .replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        return a.equals(b) ? List.of(a) : List.of(a, b);
    }

    /** The question before {@code norm} loses "65+" → "65": call this on the ORIGINAL text first. */
    public static String preNorm(String original) {
        return original == null ? "" : original.replaceAll("(\\d)\\s*\\+", "$1 plus");
    }

    /** Same normalisation as the question: lower-case, apostrophes dropped, punctuation → space. */
    public static String normName(String s) {
        return String.valueOf(s == null ? "" : s).toLowerCase(Locale.ROOT)
                .replace("’", "'").replace("'s ", " ").replace("'", "")
                .replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}
