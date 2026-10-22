package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.hibernate.VolunteerProfile;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.VolunteerProfileRepository;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.util.AppClock;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.DayOfWeek;
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
 * Natural-language DATA search for the AI Search bar ("When is Anson's
 * birthday?", "What was the income for March?", "Who are the volunteers?").
 *
 * <p>Deterministic (no LLM call) so it works even when OpenAI is not
 * configured, is cheap to run on every query, and — critically — every answer
 * path is <b>permission-gated server-side</b> by {@link AiAccessPolicy}, which mirrors
 * each module's page rule (role guard + alias-aware page permission + subscription
 * feature). A user who lacks access to a module receives a denial message and NO data.
 *
 * <p>Returned map: {@code handled} (did this look like a data question we
 * understand?), {@code answer}, {@code denied}, {@code intent}.
 */
@Service
public class AiDataSearchService {

    private final FamilyMemberRepository memberRepo;
    private final IncomeRepository incomeRepo;
    private final VolunteerProfileRepository volunteerRepo;
    private final AiAccessPolicy access;
    private final AiStructuredAnswerService structured;   // Phase B intents; null in the legacy unit tests

    @org.springframework.beans.factory.annotation.Autowired
    public AiDataSearchService(FamilyMemberRepository memberRepo,
                               IncomeRepository incomeRepo,
                               VolunteerProfileRepository volunteerRepo,
                               AiAccessPolicy access,
                               AiStructuredAnswerService structured) {
        this.memberRepo = memberRepo;
        this.incomeRepo = incomeRepo;
        this.volunteerRepo = volunteerRepo;
        this.access = access;
        this.structured = structured;
    }

    public AiDataSearchService(FamilyMemberRepository memberRepo, IncomeRepository incomeRepo,
                               VolunteerProfileRepository volunteerRepo, AiAccessPolicy access) {
        this(memberRepo, incomeRepo, volunteerRepo, access, null);
    }

    /** Without a subscription service (unit tests): plan features are assumed enabled. */
    public AiDataSearchService(FamilyMemberRepository memberRepo,
                               IncomeRepository incomeRepo,
                               VolunteerProfileRepository volunteerRepo) {
        this(memberRepo, incomeRepo, volunteerRepo, new AiAccessPolicy(), null);
    }

    public static final String DENIED_MSG =
            "You do not have permission to access this information. "
          + "Please contact your administrator if you believe this is an error.";

    /* ════════════════════════ entry point ════════════════════════ */

    @Transactional(readOnly = true)
    public Map<String, Object> answer(HttpServletRequest req, String clientId, String question) {
        String q = norm(question);
        LocalDate today = AppClock.today();                    // the church calendar (America/Chicago), not the server's zone
        AiAccess a = access.resolve(req, clientId);
        if (a.kids()) return denied("kids");                   // Kids Portal: never any data

        // Synonyms ("donated", "gave", "offerings", "spent", "programs", "helpers" …) are mapped
        // to the router's own words once, here, for every category (AiQueryLexicon). Names are
        // still matched on the original question; the period words are never rewritten.
        String qc = AiQueryLexicon.canonical(q);

        // Order matters: volunteers (profiles) first, then a named person's giving, then
        // the Phase B structured intents, then the remaining legacy intents.
        boolean assignments = qc.matches(".*\\b(assign\\w*|schedul\\w*|serving|roster|upcoming)\\b.*");
        if (qc.matches(".*\\bvolunteers?\\b.*") && !assignments) return volunteers(a, clientId, qc);
        boolean giving = AiQueryLexicon.isGiving(qc);
        if (giving && !a.incomeRecords() && !a.reports())      return denied("contributions");   // never run a name search the user may not see
        if (giving) {
            // A question about ONE person's giving is answered for that person only, under the
            // Income page rule — never with the church-wide total.
            List<FamilyMember> people = findPersonMatches(clientId, q);
            String named = AiQueryLexicon.personMentioned(question);
            if ((!people.isEmpty() || named != null) && !a.incomeRecords()) return denied("contributions");
            if (people.size() > 1) return whichPerson(people);
            if (people.size() == 1) return personGiving(a, clientId, q, qc, people.get(0), today);
            if (named != null) return ok("contributions", "I could not find an active member named “" + named + "”. "
                    + "Please check the spelling, or use their first and last name.");
        }
        if (structured != null) {
            Map<String, Object> s = structured.route(qc, a, clientId, today);
            if (s != null) return s;
        }
        if (qc.matches(".*\\b(income|revenue|collections?)\\b.*")) return incomeSummary(a, clientId, q, today);
        boolean bday = q.matches(".*\\bbirth\\s?days?\\b.*");
        boolean anni = q.matches(".*\\banniversar\\w*\\b.*");
        if (bday || anni)                                      return datesLookup(a, clientId, q, today, bday, anni);

        return notHandled();
    }

    private Map<String, Object> notHandled() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("handled", false);
        return m;
    }

    private Map<String, Object> ok(String intent, String answer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("handled", true);
        m.put("denied", false);
        m.put("intent", intent);
        m.put("answer", answer);
        return m;
    }

    private Map<String, Object> denied(String intent) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("handled", true);
        m.put("denied", true);
        m.put("intent", intent);
        m.put("answer", DENIED_MSG);
        return m;
    }

    /* Permissions: see AiAccessPolicy — one rule set shared by every AI endpoint. */

    /* ════════════════════════ intents ════════════════════════ */

    private Map<String, Object> volunteers(AiAccess a, String clientId, String q) {
        if (!a.volunteers()) return denied("volunteers");
        List<VolunteerProfile> profiles = new ArrayList<>();
        for (VolunteerProfile p : volunteerRepo.findByAppClientIdAndDeleteFlagFalseOrderByFamilyMemberIdAsc(clientId))
            if (p.getStatus() == null || "Active".equalsIgnoreCase(p.getStatus())) profiles.add(p);   // the page's active rule
        if (profiles.isEmpty()) return ok("volunteers", "No active volunteers have been registered yet.");
        Map<Integer, FamilyMember> byId = new LinkedHashMap<>();
        for (FamilyMember m : members(clientId)) byId.put(intId(m.getId()), m);
        List<String> names = new ArrayList<>();
        for (VolunteerProfile p : profiles) {
            FamilyMember m = p.getFamilyMemberId() == null ? null : byId.get(p.getFamilyMemberId());
            String nm = m != null ? fullName(m) : null;
            if (nm == null || nm.isBlank()) continue;
            String skills = p.getSkills() == null || p.getSkills().isBlank() ? "" : (" (" + p.getSkills().trim() + ")");
            names.add(nm + skills);
        }
        if (names.isEmpty()) return ok("volunteers", "No volunteers have been registered yet.");
        return ok("volunteers", "There " + (names.size() == 1 ? "is 1 volunteer" : "are " + names.size() + " volunteers")
                + ": " + String.join("; ", names) + ".");
    }

    private Map<String, Object> incomeSummary(AiAccess a, String clientId, String q, LocalDate today) {
        if (!a.reports()) return denied("income");            // a total is a report figure → the Reports page rule
        DateRange r = parsePeriod(q, today);
        if (r == null) r = DateRange.month(today, "this month");
        List<Income> list = incomeRepo.findRangeByAppClientId(clientId, r.from, r.to);
        BigDecimal total = BigDecimal.ZERO;
        for (Income i : list) if (i.getAmount() != null) total = total.add(i.getAmount());
        if (list.isEmpty())
            return ok("income", "No income was recorded for " + r.label + " (" + fmtRange(r) + ").");
        return ok("income", "Total income for " + r.label + " (" + fmtRange(r) + "): " + money(total)
                + " across " + list.size() + " transaction" + (list.size() == 1 ? "" : "s") + ".");
    }

    /** More than one active member matches the name: ask which one (names only, bounded). */
    private Map<String, Object> whichPerson(List<FamilyMember> people) {
        List<String> names = new ArrayList<>();
        for (FamilyMember m : people) { if (names.size() >= 8) break; names.add(fullName(m)); }
        Map<String, Object> m = ok("clarify", "More than one member matches that name: " + String.join(", ", names)
                + (people.size() > names.size() ? ", …" : "") + ". Which one do you mean? Please ask again with the full name.");
        m.put("clarify", true);
        return m;
    }

    private static final int PERSON_ROWS = 5;

    /**
     * One member's giving for a period — two bounded statements (an aggregate and the newest
     * {@value #PERSON_ROWS} gifts), never the whole period's income list. Tithe questions count
     * tithe funds only.
     */
    private Map<String, Object> personGiving(AiAccess a, String clientId, String q, String qc, FamilyMember person, LocalDate today) {
        if (!a.incomeRecords()) return denied("contributions"); // individual gifts → the Income page rule
        DateRange r = parsePeriod(q, today);
        if (r == null) r = DateRange.month(today, "this month");
        boolean titheOnly = AiQueryLexicon.isTithe(qc);
        Integer mid = intId(person.getId());
        java.sql.Date from = java.sql.Date.valueOf(r.from), to = java.sql.Date.valueOf(r.to);
        List<Object[]> tot = incomeRepo.aiMemberGivingTotals(clientId, mid, from, to, titheOnly);
        Object[] t = tot == null || tot.isEmpty() ? null : tot.get(0);
        BigDecimal total = t == null || t[0] == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(t[0]));
        long n = t == null || t[1] == null ? 0 : ((Number) t[1]).longValue();
        String what = titheOnly ? "tithe" : "contribution";
        String name = fullName(person);
        String when = inPeriod(r.label) + " (" + fmtRange(r) + ")";
        boolean yesNo = q.matches("^(did|has|have|does|do|is|was|were)\\b.*");
        if (n == 0) {
            Map<String, Object> m = ok("contributions", (yesNo ? "No — " : "") + name + " has no recorded " + what + "s " + when + ".");
            m.put("period", periodMap(r));
            return m;
        }
        List<List<String>> rows = new ArrayList<>();
        for (Object[] x : incomeRepo.aiMemberGivingRows(clientId, mid, from, to, titheOnly, PERSON_ROWS)) {
            String ref = x[3] == null ? "" : String.valueOf(x[3]);
            String via = ref.startsWith("pi_") || ref.startsWith("donation:") ? "Online donation" : "";
            rows.add(List.of(fmtDate(toLocalDate(x[0])), x[2] == null ? "" : String.valueOf(x[2]).trim(),
                             money(x[1] == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(x[1]))), via));
        }
        String answer = (yesNo ? "Yes — " : "") + name + " gave " + money(total) + " " + when + " across " + n + " " + what + (n == 1 ? "" : "s")
                + (n > rows.size() ? ". The " + rows.size() + " most recent are listed." : ".");
        Map<String, Object> m = ok("contributions", answer);
        m.put("columns", List.of("Date", "Fund", "Amount", "Note"));
        m.put("rows", rows);
        m.put("total", n);
        m.put("shown", rows.size());
        m.put("truncated", n > rows.size());
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Contributor", name); summary.put("Total", money(total)); summary.put(titheOnly ? "Tithes" : "Contributions", String.valueOf(n));
        m.put("summary", summary);
        m.put("period", periodMap(r));
        Map<String, String> link = new LinkedHashMap<>(); link.put("label", "Open Income"); link.put("url", "/income");
        if (a.canOpen("/income")) m.put("link", link);
        return m;
    }

    /** "this month" stays as is; "September 2026" becomes "in September 2026". */
    static String inPeriod(String label) {
        String l = label == null ? "" : label.trim();
        return l.matches("(?i)^(this|last|next|past|previous|today|yesterday|so far|year to date).*") ? l : "in " + l;
    }
    private static Map<String, String> periodMap(DateRange r) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("label", r.label); p.put("from", r.from.toString()); p.put("to", r.to.toString());
        return p;
    }
    private static LocalDate toLocalDate(Object o) {
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.util.Date d) return new java.sql.Date(d.getTime()).toLocalDate();
        return LocalDate.parse(String.valueOf(o).substring(0, 10));
    }

    private Map<String, Object> datesLookup(AiAccess a, String clientId, String q,
                                            LocalDate today, boolean bday, boolean anni) {
        if (!a.members()) return denied(bday ? "birthdays" : "anniversaries");

        // Person-specific: "When is Anson's birthday?"
        FamilyMember person = findPersonName(clientId, q);
        if (person != null && !q.matches(".*\\b(list|all|everyone)\\b.*")) {
            StringBuilder sb = new StringBuilder();
            String name = fullName(person);
            if (bday) {
                sb.append(person.getBirthdayMonth() != null && person.getBirthdayDay() != null
                        ? name + "'s birthday is " + monthName(person.getBirthdayMonth()) + " " + person.getBirthdayDay()
                          + (person.getBirthdayYear() != null ? ", " + person.getBirthdayYear() : "") + "."
                        : "No birthday is on file for " + name + ".");
            }
            if (anni) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(person.getAnniversaryMonth() != null && person.getAnniversaryDay() != null
                        ? name + "'s anniversary is " + monthName(person.getAnniversaryMonth()) + " " + person.getAnniversaryDay()
                          + (person.getAnniversaryYear() != null ? ", " + person.getAnniversaryYear() : "") + "."
                        : "No anniversary is on file for " + name + ".");
            }
            return ok(bday ? "birthday" : "anniversary", sb.toString());
        }

        // Period list: "List the birthdays and anniversaries in May."
        DateRange r = parsePeriod(q, today);
        if (r == null) r = DateRange.month(today, "this month");
        List<String> bdays = new ArrayList<>(), annis = new ArrayList<>();
        for (FamilyMember m : members(clientId)) {
            if (bday && inRangeMonthDay(m.getBirthdayMonth(), m.getBirthdayDay(), r))
                bdays.add(fullName(m) + " — " + monthName(m.getBirthdayMonth()) + " " + m.getBirthdayDay());
            if (anni && inRangeMonthDay(m.getAnniversaryMonth(), m.getAnniversaryDay(), r))
                annis.add(fullName(m) + " — " + monthName(m.getAnniversaryMonth()) + " " + m.getAnniversaryDay());
        }
        StringBuilder sb = new StringBuilder();
        if (bday) sb.append(bdays.isEmpty()
                ? "No birthdays in " + r.label + "."
                : "Birthdays in " + r.label + " (" + bdays.size() + "): " + String.join("; ", bdays) + ".");
        if (anni) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(annis.isEmpty()
                    ? "No anniversaries in " + r.label + "."
                    : "Anniversaries in " + r.label + " (" + annis.size() + "): " + String.join("; ", annis) + ".");
        }
        return ok(bday && anni ? "birthdays+anniversaries" : (bday ? "birthdays" : "anniversaries"), sb.toString());
    }

    /* ════════════════════════ name matching ════════════════════════ */

    /**
     * Every active member the question names: full-name matches first (longest wins); otherwise
     * everyone whose first name (≥ 3 letters) appears; otherwise a unique last name. Several
     * people with the same first name are all returned so the caller can ask which one.
     */
    List<FamilyMember> findPersonMatches(String clientId, String q) {
        String padded = " " + q + " ";
        List<FamilyMember> full = new ArrayList<>(), firsts = new ArrayList<>(), lasts = new ArrayList<>();
        int bestFull = 0;
        for (FamilyMember m : members(clientId)) {
            String first = lc(m.getFirstName()), last = lc(m.getLastName());
            String fn = norm(first + " " + last);
            if (!fn.isBlank() && fn.contains(" ") && padded.contains(" " + fn + " ")) {
                if (fn.length() > bestFull) { full.clear(); bestFull = fn.length(); }
                if (fn.length() == bestFull) full.add(m);
                continue;
            }
            String f = norm(first), l = norm(last);
            if (f.length() >= 3 && padded.contains(" " + f + " ")) firsts.add(m);
            else if (l.length() >= 3 && padded.contains(" " + l + " ")) lasts.add(m);
        }
        if (!full.isEmpty()) return full;
        if (!firsts.isEmpty()) return firsts;
        return lasts.size() == 1 ? lasts : (lasts.isEmpty() ? List.of() : lasts);
    }

    /** Finds a member whose name appears in the question (longest match wins). */
    FamilyMember findPersonName(String clientId, String q) {
        String padded = " " + q + " ";
        FamilyMember best = null;
        int bestLen = 0;
        for (FamilyMember m : members(clientId)) {
            String first = lc(m.getFirstName()), last = lc(m.getLastName());
            String full = (first + " " + last).trim();
            if (!full.isBlank() && padded.contains(" " + full + " ") && full.length() > bestLen) {
                best = m; bestLen = full.length();
            } else if (!first.isBlank() && first.length() >= 3 && padded.contains(" " + first + " ")
                    && first.length() > bestLen) {
                best = m; bestLen = first.length();
            }
        }
        return best;
    }

    private List<FamilyMember> membersCacheList;
    private String membersCacheClient;
    private long membersCacheAt;

    /** Members list with a tiny 30s cache (name matching runs per keystroke-ish). */
    private synchronized List<FamilyMember> members(String clientId) {
        long now = System.currentTimeMillis();
        if (membersCacheList != null && clientId.equals(membersCacheClient) && now - membersCacheAt < 30_000)
            return membersCacheList;
        membersCacheList = memberRepo.findAllWithFamilyByAppUser(clientId);
        membersCacheClient = clientId;
        membersCacheAt = now;
        return membersCacheList;
    }

    /* ════════════════════════ date parsing ════════════════════════ */

    public static final class DateRange {
        public final LocalDate from, to; public final String label;
        public DateRange(LocalDate from, LocalDate to, String label) { this.from = from; this.to = to; this.label = label; }
        public long days() { return java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1; }
        public static DateRange month(LocalDate anchor, String label) {
            LocalDate f = anchor.withDayOfMonth(1);
            return new DateRange(f, f.plusMonths(1).minusDays(1), label);
        }
    }

    private static final Map<String, Integer> MONTHS = new LinkedHashMap<>();
    static {
        String[] names = {"january","february","march","april","may","june",
                          "july","august","september","october","november","december"};
        for (int i = 0; i < names.length; i++) {
            MONTHS.put(names[i], i + 1);
            MONTHS.put(names[i].substring(0, 3), i + 1);
        }
        MONTHS.put("sept", 9);
    }

    /** "this month", "last month", "March", "March 2026", "2026", "today", "next sunday", … */
    public static DateRange parsePeriod(String q, LocalDate today) {
        // Relative spans (Phase B): "last two months", "past 90 days", "next 30 days",
        // optionally ending "through yesterday" / "until today".
        Matcher span = Pattern.compile("\\b(last|past|previous|next|coming)\\s+(\\d{1,3}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\\s+(days?|weeks?|months?|years?)\\b").matcher(q);
        if (span.find()) {
            int n = numberWord(span.group(2));
            String unit = span.group(3);
            boolean forward = span.group(1).equals("next") || span.group(1).equals("coming");
            LocalDate from, to;
            if (forward) {
                from = today.plusDays(1);
                to = unit.startsWith("day") ? today.plusDays(n) : unit.startsWith("week") ? today.plusWeeks(n)
                   : unit.startsWith("month") ? today.plusMonths(n) : today.plusYears(n);
            } else {
                to = today;
                from = unit.startsWith("day") ? today.minusDays(n - 1) : unit.startsWith("week") ? today.minusWeeks(n).plusDays(1)
                    : unit.startsWith("month") ? today.minusMonths(n).plusDays(1) : today.minusYears(n).plusDays(1);
                if (q.matches(".*\\b(through|thru|until|till|up to|ending)\\s+yesterday\\b.*")) { to = today.minusDays(1); from = from.minusDays(1); }
            }
            return new DateRange(from, to, span.group(0));
        }
        if (q.contains("today")) return new DateRange(today, today, "today");
        if (q.contains("yesterday")) { LocalDate d = today.minusDays(1); return new DateRange(d, d, "yesterday"); }
        if (q.contains("tomorrow"))  { LocalDate d = today.plusDays(1);  return new DateRange(d, d, "tomorrow"); }
        if (q.contains("this week")) {
            LocalDate start = today.with(DayOfWeek.MONDAY);
            return new DateRange(start, start.plusDays(6), "this week");
        }
        if (q.contains("last week")) {
            LocalDate start = today.with(DayOfWeek.MONDAY).minusWeeks(1);
            return new DateRange(start, start.plusDays(6), "last week");
        }
        if (q.contains("this month")) return DateRange.month(today, "this month");
        if (q.contains("last month")) return DateRange.month(today.minusMonths(1), "last month");
        if (q.contains("next month")) return DateRange.month(today.plusMonths(1), "next month");
        if (q.contains("this year"))
            return new DateRange(today.withDayOfYear(1), LocalDate.of(today.getYear(), 12, 31), "this year");
        if (q.contains("last year"))
            return new DateRange(LocalDate.of(today.getYear() - 1, 1, 1), LocalDate.of(today.getYear() - 1, 12, 31), "last year");
        // "next sunday" / weekday names
        Matcher wd = Pattern.compile("\\b(next\\s+|this\\s+)?(sunday|monday|tuesday|wednesday|thursday|friday|saturday)\\b").matcher(q);
        if (wd.find()) {
            DayOfWeek want = DayOfWeek.valueOf(wd.group(2).toUpperCase(Locale.ROOT));
            LocalDate d = today;
            int add = (want.getValue() - d.getDayOfWeek().getValue() + 7) % 7;
            if (add == 0) add = 7;                              // a named weekday means the upcoming one
            d = d.plusDays(add);
            return new DateRange(d, d, wd.group(2).substring(0, 1).toUpperCase(Locale.ROOT) + wd.group(2).substring(1)
                    + ", " + monthName(d.getMonthValue()) + " " + d.getDayOfMonth());
        }
        // Month name (+ optional year)
        for (Map.Entry<String, Integer> e : MONTHS.entrySet()) {
            if (q.matches(".*\\b" + e.getKey() + "\\b.*")) {
                int year = today.getYear();
                Matcher y = Pattern.compile("\\b(20\\d{2})\\b").matcher(q);
                if (y.find()) year = Integer.parseInt(y.group(1));
                LocalDate f = LocalDate.of(year, e.getValue(), 1);
                return new DateRange(f, f.plusMonths(1).minusDays(1),
                        monthName(e.getValue()) + " " + year);
            }
        }
        // Bare year
        Matcher y = Pattern.compile("\\b(20\\d{2})\\b").matcher(q);
        if (y.find()) {
            int year = Integer.parseInt(y.group(1));
            return new DateRange(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), String.valueOf(year));
        }
        return null;
    }

    private static int numberWord(String w) {
        String[] words = {"zero","one","two","three","four","five","six","seven","eight","nine","ten","eleven","twelve"};
        for (int i = 0; i < words.length; i++) if (words[i].equals(w)) return i;
        try { return Integer.parseInt(w); } catch (NumberFormatException e) { return 1; }
    }

    private boolean inRangeMonthDay(Integer month, Integer day, DateRange r) {
        if (month == null || day == null) return false;
        // Walk each (month,day) against the range ignoring year (recurring dates).
        for (int year = r.from.getYear(); year <= r.to.getYear(); year++) {
            try {
                LocalDate d = LocalDate.of(year, month, Math.min(day, Month.of(month).length(false)));
                if (!d.isBefore(r.from) && !d.isAfter(r.to)) return true;
            } catch (Exception ignore) {}
        }
        return false;
    }

    /* ════════════════════════ helpers ════════════════════════ */

    private static String norm(String s) {
        return String.valueOf(s == null ? "" : s).toLowerCase(Locale.ROOT)
                .replace("’", "'").replace("'s ", " ").replace("'", "")
                .replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
    private static String lc(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).trim(); }
    private static String fullName(FamilyMember m) {
        return ((m.getFirstName() == null ? "" : m.getFirstName().trim()) + " "
              + (m.getLastName() == null ? "" : m.getLastName().trim())).trim();
    }
    private static Integer intId(Object id) {
        try { return Integer.valueOf(String.valueOf(id)); } catch (Exception e) { return -1; }
    }
    private static String monthName(int m) { return Month.of(m).getDisplayName(java.time.format.TextStyle.FULL, Locale.US); }
    private static String money(BigDecimal v) {
        return NumberFormat.getCurrencyInstance(Locale.US).format(v == null ? BigDecimal.ZERO : v);
    }
    private static String fmtDate(LocalDate d) {
        return d == null ? "" : monthName(d.getMonthValue()) + " " + d.getDayOfMonth() + ", " + d.getYear();
    }
    private static String fmtRange(DateRange r) {
        return r.from.equals(r.to) ? fmtDate(r.from) : fmtDate(r.from) + " – " + fmtDate(r.to);
    }
}
