package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.Income;
import com.churchgeniuspro.hibernate.VolunteerProfile;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.VolunteerProfileRepository;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
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
 * path is <b>permission-gated server-side</b> using the same session role +
 * privilege semantics as {@link RoleGuard}. A user who lacks access to a
 * module receives a denial message and NO data.
 *
 * <p>Returned map: {@code handled} (did this look like a data question we
 * understand?), {@code answer}, {@code denied}, {@code intent}.
 */
@Service
public class AiDataSearchService {

    private final FamilyMemberRepository memberRepo;
    private final IncomeRepository incomeRepo;
    private final VolunteerProfileRepository volunteerRepo;

    public AiDataSearchService(FamilyMemberRepository memberRepo,
                               IncomeRepository incomeRepo,
                               VolunteerProfileRepository volunteerRepo) {
        this.memberRepo = memberRepo;
        this.incomeRepo = incomeRepo;
        this.volunteerRepo = volunteerRepo;
    }

    public static final String DENIED_MSG =
            "You do not have permission to access this information. "
          + "Please contact your administrator if you believe this is an error.";

    /* ════════════════════════ entry point ════════════════════════ */

    @Transactional(readOnly = true)
    public Map<String, Object> answer(HttpServletRequest req, String clientId, String question) {
        String q = norm(question);
        LocalDate today = LocalDate.now();

        // Order matters: volunteers first (unambiguous), then finance (tithe /
        // income), then people dates (birthday / anniversary).
        if (q.matches(".*\\bvolunteers?\\b.*"))               return volunteers(req, clientId, q);
        if (q.matches(".*\\b(tithe|tithes|contribut\\w*|donat\\w*|gave|give|giving|offering)\\b.*")
                && findPersonName(clientId, q) != null)        return personGiving(req, clientId, q, today);
        if (q.matches(".*\\b(income|revenue|collections?)\\b.*")) return incomeSummary(req, clientId, q, today);
        boolean bday = q.matches(".*\\bbirth\\s?days?\\b.*");
        boolean anni = q.matches(".*\\banniversar\\w*\\b.*");
        if (bday || anni)                                      return datesLookup(req, clientId, q, today, bday, anni);

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

    /* ════════════════════════ permissions ════════════════════════
     * Same semantics as the page routes:
     *  • Members data  — mirrors /viewfamily (Admin/Accountant/User staff +
     *    admin.family perm); Member portal mirrors the Directory tab.
     *  • Finance data  — mirrors the income reports (SuperAdmin/Admin/
     *    Accountant + reports.income perm). Member portal: denied.
     *  • Volunteers    — mirrors /volunteers (Admin/User staff +
     *    general.volunteers perm). Member portal: denied.
     * Church-owner sessions bypass granular permissions (as everywhere else).
     */

    private boolean isChurch(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null && Boolean.TRUE.equals(s.getAttribute("church"));
    }

    private String role(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        Object r = s == null ? null : s.getAttribute("role");
        return r == null ? "" : String.valueOf(r);
    }

    private boolean isMemberSession(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null && "Member".equals(role(req)) && s.getAttribute("memberId") != null;
    }

    private boolean roleIn(HttpServletRequest req, String... roles) {
        String r = role(req);
        for (String x : roles) if (x.equals(r)) return true;
        return false;
    }

    private boolean permOk(HttpServletRequest req, String key) {
        return RoleGuard.requirePermission(req, key) == null;
    }

    boolean canSeeMembers(HttpServletRequest req) {
        if (isChurch(req)) return true;
        if (isMemberSession(req))
            return RoleGuard.requireMemberPermission(req, "member.directory") == null;
        return roleIn(req, "SuperAdmin", "Admin", "Accountant", "User") && permOk(req, "admin.family");
    }

    boolean canSeeFinance(HttpServletRequest req) {
        if (isChurch(req)) return true;
        if (isMemberSession(req)) return false;
        return roleIn(req, "SuperAdmin", "Admin", "Accountant") && permOk(req, "reports.income");
    }

    boolean canSeeVolunteers(HttpServletRequest req) {
        if (isChurch(req)) return true;
        if (isMemberSession(req)) return false;
        return roleIn(req, "SuperAdmin", "Admin", "User") && permOk(req, "general.volunteers");
    }

    /* ════════════════════════ intents ════════════════════════ */

    private Map<String, Object> volunteers(HttpServletRequest req, String clientId, String q) {
        if (!canSeeVolunteers(req)) return denied("volunteers");
        List<VolunteerProfile> profiles =
                volunteerRepo.findByAppClientIdAndDeleteFlagFalseOrderByFamilyMemberIdAsc(clientId);
        if (profiles.isEmpty()) return ok("volunteers", "No volunteers have been registered yet.");
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

    private Map<String, Object> incomeSummary(HttpServletRequest req, String clientId, String q, LocalDate today) {
        if (!canSeeFinance(req)) return denied("income");
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

    private Map<String, Object> personGiving(HttpServletRequest req, String clientId, String q, LocalDate today) {
        if (!canSeeFinance(req)) return denied("contributions");
        FamilyMember person = findPersonName(clientId, q);
        if (person == null) return notHandled();
        DateRange r = parsePeriod(q, today);
        if (r == null) r = DateRange.month(today, "this month");
        boolean titheOnly = q.contains("tithe");
        List<Income> list = incomeRepo.findRangeByAppClientId(clientId, r.from, r.to);
        BigDecimal total = BigDecimal.ZERO;
        int n = 0;
        List<String> details = new ArrayList<>();
        for (Income i : list) {
            if (i.getMember() == null || i.getMember().getId() == null
                    || !i.getMember().getId().equals(person.getId())) continue;
            String fund = i.getSubSource() != null && i.getSubSource().getSourceName() != null
                    ? i.getSubSource().getSourceName() : "";
            if (titheOnly && !fund.toLowerCase(Locale.ROOT).contains("tithe")) continue;
            if (i.getAmount() != null) total = total.add(i.getAmount());
            n++;
            if (details.size() < 5)
                details.add(money(i.getAmount()) + (fund.isBlank() ? "" : " to " + fund) + " on " + fmtDate(i.getIncomeDate()));
        }
        String what = titheOnly ? "tithe" : "contribution";
        String name = fullName(person);
        if (n == 0)
            return ok("contributions", "No, " + name + " has no recorded " + what
                    + " for " + r.label + " (" + fmtRange(r) + ").");
        return ok("contributions", "Yes — " + name + " gave " + money(total) + " ("
                + n + " " + what + (n == 1 ? "" : "s") + ") in " + r.label + ": "
                + String.join("; ", details) + (n > details.size() ? "; …" : "") + ".");
    }

    private Map<String, Object> datesLookup(HttpServletRequest req, String clientId, String q,
                                            LocalDate today, boolean bday, boolean anni) {
        if (!canSeeMembers(req)) return denied(bday ? "birthdays" : "anniversaries");

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

    static final class DateRange {
        final LocalDate from, to; final String label;
        DateRange(LocalDate from, LocalDate to, String label) { this.from = from; this.to = to; this.label = label; }
        static DateRange month(LocalDate anchor, String label) {
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
    DateRange parsePeriod(String q, LocalDate today) {
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
