package com.churchgeniuspro.ai.filter;

import com.churchgeniuspro.ai.filter.PeopleFilter.*;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.AiAccessPolicy.CrossAccess;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI Phase 1 (2026-11-04): answers composable people questions ("women who attended twice in the last
 * 60 days but didn't give this year") from a {@link PeopleFilter}, deterministically.
 *
 * <p>Permissions follow the cross-reference rule (decision 1, 2026-10-31): every module a filter reads
 * must pass its page rule in {@link AiAccessPolicy}; if any is missing the WHOLE question is refused and
 * the missing modules are named — never a partial list. The church is always the session's. Windows
 * longer than {@code ai.search.max-range-days} are refused with a request to narrow. Results are capped
 * at {@code ai.search.max-rows} and carry the parsed filter back as chips so a misread condition can be
 * removed and the question re-run without retyping.
 */
@Service
public class PeopleFilterService {

    @PersistenceContext private EntityManager em;
    private final AiAccessPolicy access;

    @Value("${ai.search.max-rows:20}")                public int maxRows = 20;
    @Value("${ai.search.max-range-days:400}")         public int maxRangeDays = 400;
    @Value("${ai.crossref.regular-min-days:3}")       public int regularMinDays = 3;
    @Value("${ai.crossref.regular-window-weeks:12}")  public int regularWindowWeeks = 12;
    @Value("${ai.crossref.lapsed-weeks:4}")           public int lapsedWeeks = 4;
    @Value("${ai.crossref.serving-window-months:6}")  public int servingWindowMonths = 6;

    public PeopleFilterService(AiAccessPolicy access) { this.access = access; }

    /** Unit tests: inject an EntityManager (or null to test parsing / gating only). */
    public void setEntityManager(EntityManager em) { this.em = em; }

    public static final String INTENT = "filter.people";
    public static final String INTENT_DENIED = "filter.denied";

    /* ═══════════════════════════ entry points ═══════════════════════════ */

    /**
     * Parses the question and answers it when the filter route should take it; null otherwise (the caller
     * continues with the older intents). {@code original} is the raw question (for "65+"), {@code canonical}
     * the normalised, synonym-mapped one the other routes use.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> route(String original, String canonical, AiAccess a, CrossAccess x, String cid, LocalDate today) {
        if (a == null || a.kids() || a.member() || a.church()) return null;     // staff questions about the whole church
        String q = canonical;
        if (original != null && original.matches(".*\\d\\s*\\+.*")) q = PeopleFilterParser.preNorm(original).toLowerCase(Locale.ROOT)
                .replace("’", "'").replace("'s ", " ").replace("'", "").replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        PeopleFilterParser.Result r = new PeopleFilterParser(context(cid), today).parse(com.churchgeniuspro.service.AiQueryLexicon.canonical(q));
        if (r.clarify() != null) { Map<String, Object> m = base("clarify", r.clarify()); m.put("topic", "filter"); return m; }
        if (!r.claim()) return null;
        return answer(r.filter(), a, x, cid, today, maxRows);
    }

    /** Runs a filter the page or a saved list hands back (JSON → PeopleFilter already validated). */
    @Transactional(readOnly = true)
    public Map<String, Object> run(PeopleFilter filter, AiAccess a, CrossAccess x, String cid, LocalDate today) {
        return run(filter, a, x, cid, today, maxRows);
    }

    /** Same, with an explicit row cap (the CSV export of a saved list uses a larger one than the search bar). */
    @Transactional(readOnly = true)
    public Map<String, Object> run(PeopleFilter filter, AiAccess a, CrossAccess x, String cid, LocalDate today, int cap) {
        if (a == null || a.kids() || a.member() || a.church()) return requireAll(List.of("Members"));
        if (filter == null || filter.isEmpty()) return base("clarify", "Add at least one condition — for example an age range, a group, or attendance.");
        return answer(filter, a, x, cid, today, Math.max(1, Math.min(5000, cap)));
    }

    /** Distinct e-mail addresses of everyone the filter matches (up to {@code cap}), for the "Email this list" hand-off; same gates. */
    @Transactional(readOnly = true)
    public Map<String, Object> emails(PeopleFilter filter, AiAccess a, CrossAccess x, String cid, LocalDate today, int cap) {
        if (a == null || a.kids() || a.member() || a.church()) return requireAll(List.of("Members"));
        List<String> gone = missing(filter, a, x);
        if (!gone.isEmpty()) return requireAll(gone);
        if (em == null) return base("filter.error", "The people search is not available right now.");
        PeopleFilterQueryBuilder.Built b = PeopleFilterQueryBuilder.build(filter, today, Math.max(1, cap));
        Query qy = em.createNativeQuery(b.sql());
        for (Map.Entry<String, Object> e : b.params().entrySet()) qy.setParameter(e.getKey(), "cid".equals(e.getKey()) ? cid : e.getValue());
        @SuppressWarnings("unchecked") List<Object[]> rows = qy.getResultList();
        java.util.LinkedHashSet<String> emails = new java.util.LinkedHashSet<>();
        int withoutEmail = 0;
        for (Object[] r : rows) { String e = str(r[7]).toLowerCase(Locale.ROOT); if (e.contains("@")) emails.add(e); else withoutEmail++; }
        Map<String, Object> m = base(INTENT, emails.size() + " address" + (emails.size() == 1 ? "" : "es"));
        m.put("emails", new ArrayList<>(emails)); m.put("withoutEmail", withoutEmail); m.put("people", rows.size());
        m.put("capped", rows.size() >= cap);
        return m;
    }

    /* ═══════════════════════════ permissions ═══════════════════════════ */

    static final Map<String, String> REQUIREMENT = Map.of(
            "Members", "Families (SuperAdmin, Admin or Accountant role with the Family permission)",
            "Attendance", "Attendance (Admin, SuperAdmin or User role with the Attendance permission, and Attendance in your plan)",
            "Groups", "Groups (Admin or SuperAdmin role with the Groups permission, and Groups in your plan)",
            "Volunteers", "Volunteers (Admin, SuperAdmin or User role with the Volunteers permission, and Volunteer Scheduling in your plan)",
            "Income", "Income (Admin, SuperAdmin or Accountant role with the Income permission, and Accounting in your plan)",
            "Pledges", "Pledges (Admin, SuperAdmin or Accountant role with the Pledges permission, and Pledges in your plan)",
            "Events", "Events (staff role with the Events permission)");

    /** The modules of {@code f} the session may NOT read. */
    public static List<String> missing(PeopleFilter f, AiAccess a, CrossAccess x) {
        if (x == null) x = CrossAccess.none();
        List<String> gone = new ArrayList<>();
        for (String m : f.modules()) {
            boolean ok = switch (m) {
                case "Members" -> a.members();
                case "Attendance" -> x.attendance();
                case "Income" -> a.incomeRecords();
                case "Groups" -> x.groups();
                case "Volunteers" -> a.volunteers();
                case "Pledges" -> a.pledges();
                case "Events" -> a.events();
                default -> false;
            };
            if (!ok) gone.add(m);
        }
        return gone;
    }

    private static Map<String, Object> requireAll(List<String> gone) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("handled", true); m.put("denied", true); m.put("intent", INTENT_DENIED);
        StringBuilder sb = new StringBuilder("I can't answer this question because it needs access you don't have: ");
        for (int i = 0; i < gone.size(); i++) sb.append(i == 0 ? "" : "; ").append(REQUIREMENT.getOrDefault(gone.get(i), gone.get(i)));
        sb.append(". Please contact your administrator if you believe this is an error.");
        m.put("answer", sb.toString()); m.put("missing", gone);
        return m;
    }

    /* ═══════════════════════════ answer ═══════════════════════════ */

    private Map<String, Object> answer(PeopleFilter f, AiAccess a, CrossAccess x, String cid, LocalDate today, int cap) {
        List<String> gone = missing(f, a, x);
        if (!gone.isEmpty()) return requireAll(gone);
        for (Predicate p : f.predicates()) {
            LocalDate from = null, to = null;
            if (p instanceof Attended at) { from = at.from(); to = at.to(); }
            if (p instanceof Gave g) { from = g.from(); to = g.to(); }
            if (from != null && ChronoUnit.DAYS.between(from, to) + 1 > maxRangeDays)
                return base("narrow", "“" + p.label() + "” looks back " + (ChronoUnit.DAYS.between(from, to) + 1) + " days, which is more than this search can check at once ("
                        + maxRangeDays + " days). Please choose a shorter period — for example “since June” or “in the last 8 weeks”.");
        }
        if (em == null) return base("filter.error", "The people search is not available right now.");

        PeopleFilterQueryBuilder.Built b = PeopleFilterQueryBuilder.build(f, today, cap + 1);
        Query qy = em.createNativeQuery(b.sql());
        for (Map.Entry<String, Object> e : b.params().entrySet()) qy.setParameter(e.getKey(), "cid".equals(e.getKey()) ? cid : e.getValue());
        @SuppressWarnings("unchecked")
        List<Object[]> rows = qy.getResultList();
        long total = rows.isEmpty() ? 0 : num(rows.get(0)[rows.get(0).length - 1]).longValue();

        boolean showAge = f.predicates().stream().anyMatch(p -> p instanceof AgeBand || p instanceof BirthMonth);
        boolean showType = f.predicates().stream().anyMatch(p -> p instanceof MemberType || p instanceof Status);
        boolean showEmail = f.predicates().stream().anyMatch(p -> p instanceof HasContact h && "email".equals(h.what()) && !h.negated());
        boolean showPhone = f.predicates().stream().anyMatch(p -> p instanceof HasContact h && "phone".equals(h.what()) && !h.negated());
        List<String> cols = new ArrayList<>(List.of("Name", "Role"));
        if (showType) cols.add("Type");
        if (showAge) cols.add("Age");
        if (b.hasAttendance()) cols.add("Last attended");
        if (b.hasGiving()) cols.add("Gave");
        if (showEmail) cols.add("Email");
        if (showPhone) cols.add("Phone");
        List<List<String>> out = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        for (Object[] r : rows.subList(0, Math.min(rows.size(), cap))) {
            ids.add(num(r[0]).intValue());
            List<String> row = new ArrayList<>();
            row.add((str(r[1]) + " " + str(r[2])).trim());
            row.add(com.churchgeniuspro.util.FamilyRoles.canonical(str(r[3])));
            if (showType) row.add(str(r[4]).isBlank() ? "Member" : str(r[4]));
            if (showAge) row.add(r[6] == null ? "" : String.valueOf(num(r[6]).intValue()));
            if (b.hasAttendance()) row.add(r[9] == null ? "" : D.format(com.churchgeniuspro.service.AiCrossReferenceService.toDate(r[9])));
            if (b.hasGiving()) row.add(r[10] == null ? "" : money(r[10]));
            if (showEmail) row.add(str(r[7]));
            if (showPhone) row.add(str(r[8]));
            out.add(row);
        }

        List<String> labels = new ArrayList<>();
        for (Predicate p : f.predicates()) labels.add(p.label());
        String answer = total == 0 ? "No one matches: " + String.join(" · ", labels) + "."
                : total + (total == 1 ? " person matches" : " people match") + ": " + String.join(" · ", labels) + "."
                  + (rows.size() > cap ? " Showing the first " + cap + "." : "");
        Map<String, Object> m = base(INTENT, answer);
        m.put("columns", cols); m.put("rows", out); m.put("shown", out.size()); m.put("truncated", rows.size() > cap); m.put("total", total);
        m.put("memberIds", ids);
        m.put("sources", new ArrayList<>(f.modules()));
        m.put("definitions", definitions(f));
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("predicates", f.toJson()); filter.put("labels", labels);
        m.put("filter", filter);
        m.put("link", Map.of("label", "Open Families", "url", "/viewfamily"));
        return m;
    }

    private List<String> definitions(PeopleFilter f) {
        List<String> d = new ArrayList<>();
        d.add("People: Families-page records of this church" + (f.predicates().stream().anyMatch(p -> p instanceof Status) ? "." : " that are active (not deleted or inactive)."));
        d.add("Conditions are combined with AND — a person must meet every one.");
        for (Predicate p : f.predicates()) {
            switch (p) {
                case AgeBand ab -> d.add("Age: whole years today from the recorded birth date; people without a complete birth date are never included.");
                case Attended at -> d.add("Attended: Present or Late check-ins, counted as separate days, " + at.periodLabel() + ".");
                case Gave g -> d.add("Gave: income rows recorded for the person " + g.periodLabel() + (g.fund() != null ? " to the fund “" + g.fund() + "”" : "") + " (gross amounts; fees are expenses).");
                case InGroup ig -> d.add("In a group (confirmed): a live group lists them by member record, or by an email that belongs to them alone (a shared family email doesn't count).");
                case Serving s -> d.add("Serving: a volunteer assignment that is not declined, dated on or after " + D.format(s.since()) + " (undated assignments count).");
                case InCampaign ic -> d.add("Pledged: a live pledge in " + (ic.campaign() != null ? "the campaign “" + ic.campaign() + "”" : "any campaign") + ".");
                case RegisteredForEvent re -> d.add("Registered: a registration for “" + re.event() + "” whose email belongs to this person alone — registrations carry no member record.");
                case Role r -> d.add("Role: the family role on the record (Head of household, Spouse, Adult, Child), legacy spellings included.");
                case MemberType t -> d.add("Type: the person's member type on the Families page (blank counts as Member).");
                case Engagement en -> d.add("Engagement (" + en.label() + "): the person's latest nightly engagement signal, worked out from attendance days, "
                        + "giving presence (never amounts), group membership and serving — see the Ministry Report for the rules. People without a snapshot yet are never included.");
                default -> { }
            }
        }
        return d;
    }

    /* ═══════════════════════════ the church's names ═══════════════════════════ */

    /** Fund, group, campaign and event names of the church (read-only, bounded) for the parser. */
    public PeopleFilterParser.Context context(String cid) {
        if (em == null) return new PeopleFilterParser.Context(List.of(), List.of(), List.of(), List.of(), regularMinDays, regularWindowWeeks, lapsedWeeks, servingWindowMonths);
        return new PeopleFilterParser.Context(
                names("SELECT source_name FROM sub_source WHERE app_client_id = :cid AND delete_flag = false ORDER BY source_name LIMIT 500", cid),
                names("SELECT group_name FROM app_group WHERE app_client_id = :cid AND delete_flag = false ORDER BY group_name LIMIT 500", cid),
                names("SELECT name FROM pledge_campaign WHERE client_id = :cid AND delete_flag = false ORDER BY name LIMIT 500", cid),
                names("SELECT event_name FROM church_event WHERE app_client_id = :cid AND delete_flag = false ORDER BY event_name LIMIT 500", cid),
                regularMinDays, regularWindowWeeks, lapsedWeeks, servingWindowMonths);
    }

    @SuppressWarnings("unchecked")
    private List<String> names(String sql, String cid) {
        try {
            List<Object> raw = em.createNativeQuery(sql).setParameter("cid", cid).getResultList();
            List<String> out = new ArrayList<>();
            for (Object o : raw) if (o != null && !String.valueOf(o).isBlank()) out.add(String.valueOf(o).trim());
            return out;
        } catch (RuntimeException e) { return List.of(); }
    }

    /* ═══════════════════════════ helpers ═══════════════════════════ */

    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    private static Map<String, Object> base(String intent, String answer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("handled", true); m.put("denied", false); m.put("intent", intent); m.put("answer", answer);
        return m;
    }
    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
    private static BigDecimal num(Object o) { return o == null ? BigDecimal.ZERO : o instanceof BigDecimal b ? b : new BigDecimal(o.toString()); }
    private static String money(Object o) { return NumberFormat.getCurrencyInstance(Locale.US).format(num(o)); }
}
