package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.util.AppClock;
import com.churchgeniuspro.repository.ExpenseRepository;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Intent-based AI search service for the home-page search bar.
 *
 * <p>Parses a free-text query into one of the following intents:
 * <ul>
 *   <li>income         — look up income records by member name / month</li>
 *   <li>expense        — look up expense records by category / month</li>
 *   <li>birthday       — fetch birthday(s) from the family-member table</li>
 *   <li>anniversary    — fetch wedding anniversary(ies)</li>
 *   <li>birthday_anniversary — fetch both in one combined list</li>
 *   <li>navigation     — return page links filtered by the user's role</li>
 *   <li>denied         — user's role does not permit the requested data</li>
 *   <li>error          — bad or empty query</li>
 * </ul>
 *
 * <p>No external AI API is required — intent is detected via keyword matching.
 *
 * <p>Every data intent is gated by the {@link AiAccess} the controller resolved from
 * the session ({@link AiAccessPolicy}): income rows need the Income page rule, expense
 * rows the Expense page rule, member counts / birthdays / anniversaries the Families
 * page rule. Navigation links are filtered by role section as before.
 */
@Service
public class AiSearchService {

    private final IncomeRepository       incomeRepo;
    private final ExpenseRepository      expenseRepo;
    private final FamilyMemberRepository memberRepo;

    public AiSearchService(IncomeRepository incomeRepo,
                           ExpenseRepository expenseRepo,
                           FamilyMemberRepository memberRepo) {
        this.incomeRepo  = incomeRepo;
        this.expenseRepo = expenseRepo;
        this.memberRepo  = memberRepo;
    }

    // ── Month lookup tables ───────────────────────────────────────────────

    private static final String[] MONTH_FULL = {
        "january","february","march","april","may","june",
        "july","august","september","october","november","december"
    };
    private static final String[] MONTH_SHORT = {
        "jan","feb","mar","apr","may","jun",
        "jul","aug","sep","oct","nov","dec"
    };

    // ── Navigation: the server catalogue (AiNavigationCatalog) filtered by what the
    //    session may open (AiAccess.pages, decided by AiAccessPolicy) ───────────────

    // ── Stop words for name extraction ────────────────────────────────────

    private static final Set<String> STOP_WORDS = Set.of(
        "the","a","an","this","my","our","church","income","expense","birthday",
        "anniversary","month","year","today","week","all","list","show","me","is",
        "it","did","i","add","any","records","data","there","to","in","on","at",
        "by","with","give","get","have","was","were","are","has","been","be","do",
        "not","no","yes","can","could","would","should","will","may","might","must",
        "last","next","current","please","much","many","some","when","what",
        "who","where","how","which","that","and","or","but","if","than","then",
        "so","because","as","about","just","only","also","too","very","more","most",
        // verbs and group words that sit where a contributor's name would — "who all
        // are GIVEN tithe", "who GAVE" — and must never be searched as a name
        "given","gave","paid","donated","contributed","whom","everyone","anyone",
        "someone","people","members","contributors","contributor","family","families"
    );

    /**
     * Known income fund/category words used to distinguish a category filter
     * from a person's name in free-text queries.
     */
    private static final Set<String> INCOME_CATEGORY_WORDS = Set.of(
        "tithe","tithes","offering","offerings","building","general","gift","gifts",
        "missions","mission","youth","special","pledge","pledges","collection",
        "fund","funds","contribution","contributions","donation","donations"
    );

    /**
     * Maps plural/variant category words to their canonical singular form used in the DB.
     * Ensures "gifts" → "gift" so the LIKE query matches the stored source_name correctly.
     */
    private static final Map<String, String> CATEGORY_NORMALIZER = Map.of(
        "tithes",        "tithe",
        "offerings",     "offering",
        "gifts",         "gift",
        "pledges",       "pledge",
        "funds",         "fund",
        "contributions", "contribution",
        "donations",     "donation",
        "missions",      "mission"
    );

    private String normalizeCategory(String cat) {
        if (cat == null) return null;
        return CATEGORY_NORMALIZER.getOrDefault(cat, cat);
    }

    // ── Main entry point ──────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> search(String query, AiAccess access, String appClientId) {
        if (query == null || query.isBlank()) {
            return mkResult("error", "Please enter a search query.", List.of(), null);
        }
        if (access == null || access.kids()) return denied();     // Kids Portal: nothing, not even navigation
        String q = query.toLowerCase().trim();
        LocalDate today = AppClock.today();                       // church calendar (America/Chicago)

        // ── Navigation / set-up requests go to the catalogue first ("go to …", "open …",
        //    "I need to add a new fund", "how do I set the daily verse") — otherwise the
        //    topic words below ("expense", "payment") would turn them into data lookups.
        if (Pattern.compile("^\\s*(go to|open|navigate to|take me to|show me the .* page|where (is|are|do i find)|how (do|can) i (set|configure|change|add|create|find|get to)|i (need|want) to (add|set|create|change|configure)( up)? (a|the|my)? ?(new )?)").matcher(q).find()
                || Pattern.compile("\\b(settings? page|setup page|add a new|set up|configure)\\b").matcher(q).find()) {
            return handleNavigation(q, access);
        }

        // ── Detect topic keywords ────────────────────────────────────────
        boolean topicIncome  = has(q, "income","tithe","tithes","offering","offerings",
                                       "contribution","donated","donation","giving","gave",
                                       "given","received","collected");
        boolean topicExpense = has(q, "expense","expenses","paid","payment","spent","cost",
                                       "purchase","purchased","spending","expenditure");
        boolean topicBday    = has(q, "birthday","birthdays","born","birth");
        boolean topicAnniv   = has(q, "anniversary","anniversaries","wedding","married","marriage");
        boolean topicMembers = has(q, "member","members","people","families","congregation");
        boolean topicCount   = has(q, "how many","how much","number of","total count");
        boolean topicNew     = has(q, "new","recent","added","joined","this month","this year");

        // ── Detect "data context" — means user wants DB results, not navigation ──
        boolean dataCtx = has(q, "this month","current month","last month","this year",
                                  "last year","did","check","find","list","show","add",
                                  "record","how much","when is","when are","who has",
                                  "was","were") || topicBday || topicAnniv || asksWho(q);

        boolean wantsData = dataCtx && (topicIncome || topicExpense || topicBday || topicAnniv);

        // ── Extract supporting entities ──────────────────────────────────
        int month    = extractMonth(q, today.getMonthValue());
        int year     = extractYear(q, today.getYear());
        String name  = extractName(q);
        String category = extractCategory(q);

        // ── Route to handler ─────────────────────────────────────────────
        if (wantsData && topicIncome) {
            if (!access.incomeRecords()) return denied();
            // "Who gave tithe this month?" — a contributor-list question, not a
            // search for a contributor called "Who"/"Given".
            if (name == null && asksWho(q)) return handleContributors(category, month, year, appClientId);
            return handleIncome(name, category, month, year, appClientId);
        }
        if (wantsData && topicExpense) {
            if (!access.expenseRecords()) return denied();
            return handleExpense(name, month, year, appClientId);
        }
        // Member data (names, dates, counts) follows the Families page rule — a
        // Member-portal login with only My Profile, or a User, gets a denial, not a list.
        if (topicBday && topicAnniv) {
            if (!access.members()) return denied();
            return handleBirthdayAndAnniversary(month, appClientId);
        }
        if (topicBday) {
            if (!access.members()) return denied();
            return handleBirthday(name, month, appClientId);
        }
        if (topicAnniv) {
            if (!access.members()) return denied();
            return handleAnniversary(name, month, appClientId);
        }
        if (topicCount && topicMembers) {
            if (!access.members()) return denied();
            return handleMemberCount(topicNew, month, year, appClientId, access);
        }

        // ── Default: navigation ───────────────────────────────────────────
        return handleNavigation(q, access);
    }

    // ── Income ────────────────────────────────────────────────────────────

    private Map<String, Object> handleIncome(String name, String category,
                                             int month, int year, String appClientId) {
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end   = YearMonth.of(year, month).atEndOfMonth();

        List<Object[]> rows = incomeRepo.searchIncome(
                appClientId, name, category, Date.valueOf(start), Date.valueOf(end));

        String monthLabel = capitalize(MONTH_FULL[month - 1]) + " " + year;
        String catDesc    = category != null ? " (" + capitalize(category) + ")" : "";
        String nameDesc   = name != null ? " for '" + capitalize(name) + "'" : "";

        if (rows.isEmpty()) {
            String ans = name != null
                ? "No, there are no" + catDesc + " income records for '" +
                  capitalize(name) + "' in " + monthLabel + "."
                : "No income records found" + catDesc + " in " + monthLabel + ".";
            return mkResult("income", ans, List.of(), null);
        }

        BigDecimal total = rows.stream()
            .map(r -> r[6] != null ? new BigDecimal(r[6].toString()) : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        String ans = name != null
            ? "Yes! Found " + rows.size() + " income record(s) for '" + capitalize(name) + "'" +
              catDesc + " in " + monthLabel + " — total $" + fmt(total) + "."
            : "Found " + rows.size() + " income record(s)" + catDesc + nameDesc +
              " in " + monthLabel + " — total $" + fmt(total) + ".";

        return mkResult("income", ans, fmtIncome(rows), null);
    }

    /** "who gave / who paid / who all are given / who contributed / show me who …" */
    static boolean asksWho(String q) {
        return q != null && Pattern.compile("\\bwho\\b").matcher(q).find();
    }

    /**
     * Lists the people who gave in a category and month: one line per contributor
     * (first appearance order, newest gift first) with that person's total, backed
     * by the same tenant-scoped query and role check as {@link #handleIncome}.
     */
    private Map<String, Object> handleContributors(String category, int month, int year, String appClientId) {
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end   = YearMonth.of(year, month).atEndOfMonth();
        List<Object[]> rows = incomeRepo.searchIncome(appClientId, null, category, Date.valueOf(start), Date.valueOf(end));

        String monthLabel = capitalize(MONTH_FULL[month - 1]) + " " + year;
        String catDesc    = category != null ? capitalize(category) : "income";
        if (rows.isEmpty()) {
            return mkResult("income", "No one gave " + catDesc + " in " + monthLabel + ".", List.of(), null);
        }
        Map<String, BigDecimal> byPerson = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String who = (trim2(r[2]) + " " + trim2(r[3])).trim();
            BigDecimal amt = r[6] != null ? new BigDecimal(r[6].toString()) : BigDecimal.ZERO;
            byPerson.merge(who.isEmpty() ? "(unnamed)" : who, amt, BigDecimal::add);
        }
        String names = byPerson.entrySet().stream()
                .map(e -> e.getKey() + " ($" + fmt(e.getValue()) + ")")
                .collect(Collectors.joining(", "));
        String ans = byPerson.size() + (byPerson.size() == 1 ? " person" : " people") + " gave " + catDesc
                + " in " + monthLabel + ": " + names + ".";
        return mkResult("income", ans, fmtIncome(rows), null);
    }

    // ── Expense ───────────────────────────────────────────────────────────

    private Map<String, Object> handleExpense(String name, int month, int year, String appClientId) {
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end   = YearMonth.of(year, month).atEndOfMonth();

        List<Object[]> rows = expenseRepo.searchExpense(
                appClientId, name, Date.valueOf(start), Date.valueOf(end));

        String monthLabel = capitalize(MONTH_FULL[month - 1]) + " " + year;
        String nameDesc   = name != null ? " matching '" + capitalize(name) + "'" : "";

        if (rows.isEmpty()) {
            return mkResult("expense",
                "No expense records found" + nameDesc + " in " + monthLabel + ".",
                List.of(), null);
        }

        BigDecimal total = rows.stream()
            .map(r -> r[3] != null ? new BigDecimal(r[3].toString()) : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        String ans = "Found " + rows.size() + " expense record(s)" + nameDesc + " in " +
                     monthLabel + " — total $" + fmt(total) + ".";
        return mkResult("expense", ans, fmtExpense(rows), null);
    }

    // ── Birthday ──────────────────────────────────────────────────────────

    private Map<String, Object> handleBirthday(String name, int month, String appClientId) {
        if (name != null) {
            // "When is Anson's birthday?"
            List<FamilyMember> found = memberRepo.searchByName(name, appClientId);
            List<Map<String, Object>> list = found.stream()
                .filter(m -> m.getBirthdayMonth() != null && m.getBirthdayDay() != null)
                .map(m -> bdayRow(m, null))
                .collect(Collectors.toList());
            if (list.isEmpty()) {
                return mkResult("birthday",
                    "No birthday on record for '" + capitalize(name) + "'.", List.of(), null);
            }
            Map<String, Object> first = list.get(0);
            String ans = capitalize(name) + "'s birthday is " +
                capitalize((String) first.get("monthName")) + " " + first.get("day") +
                (first.get("year") != null ? ", " + first.get("year") : "") + ".";
            return mkResult("birthday", ans, list, null);
        }
        // "List birthdays this month"
        List<FamilyMember> members = memberRepo.findByBirthdayMonth(month, appClientId);
        String ml = capitalize(MONTH_FULL[month - 1]);
        if (members.isEmpty()) {
            return mkResult("birthday", "No birthdays in " + ml + ".", List.of(), null);
        }
        List<Map<String, Object>> list = members.stream()
            .map(m -> bdayRow(m, null)).collect(Collectors.toList());
        return mkResult("birthday", members.size() + " birthday(s) in " + ml + ":", list, null);
    }

    // ── Anniversary ───────────────────────────────────────────────────────

    private Map<String, Object> handleAnniversary(String name, int month, String appClientId) {
        if (name != null) {
            List<FamilyMember> found = memberRepo.searchByName(name, appClientId);
            List<Map<String, Object>> list = found.stream()
                .filter(m -> m.getAnniversaryMonth() != null && m.getAnniversaryDay() != null)
                .map(m -> annivRow(m, null))
                .collect(Collectors.toList());
            if (list.isEmpty()) {
                return mkResult("anniversary",
                    "No wedding anniversary on record for '" + capitalize(name) + "'.", List.of(), null);
            }
            Map<String, Object> first = list.get(0);
            String ans = capitalize(name) + "'s wedding anniversary is " +
                capitalize((String) first.get("monthName")) + " " + first.get("day") +
                (first.get("year") != null ? ", " + first.get("year") : "") + ".";
            return mkResult("anniversary", ans, list, null);
        }
        List<FamilyMember> members = memberRepo.findByAnniversaryMonth(month, appClientId);
        String ml = capitalize(MONTH_FULL[month - 1]);
        if (members.isEmpty()) {
            return mkResult("anniversary", "No wedding anniversaries in " + ml + ".", List.of(), null);
        }
        List<Map<String, Object>> list = members.stream()
            .map(m -> annivRow(m, null)).collect(Collectors.toList());
        return mkResult("anniversary", members.size() + " wedding anniversary(ies) in " + ml + ":", list, null);
    }

    // ── Birthday + Anniversary combined ───────────────────────────────────

    private Map<String, Object> handleBirthdayAndAnniversary(int month, String appClientId) {
        List<FamilyMember> bdays  = memberRepo.findByBirthdayMonth(month, appClientId);
        List<FamilyMember> annivs = memberRepo.findByAnniversaryMonth(month, appClientId);
        String ml = capitalize(MONTH_FULL[month - 1]);

        List<Map<String, Object>> combined = new ArrayList<>();
        bdays.forEach( m -> combined.add(bdayRow(m,  "Birthday")));
        annivs.forEach(m -> combined.add(annivRow(m, "Anniversary")));
        combined.sort(Comparator.comparingInt(r -> (Integer) r.get("day")));

        String ans = "Found " + bdays.size() + " birthday(s) and " + annivs.size() +
                     " wedding anniversary(ies) in " + ml + ".";
        return mkResult("birthday_anniversary", ans, combined, null);
    }

    // ── Navigation ────────────────────────────────────────────────────────

    private Map<String, Object> handleNavigation(String q, AiAccess access) {
        // "I need to add a new method" — a transaction method, or something else? Ask (scenario 24).
        boolean methodWord = Pattern.compile("\\bmethods?\\b").matcher(q).find();
        boolean methodQualified = Pattern.compile("\\b(payment|transaction|pay|cash|check|cheque|card|bank|income|expense|accounting)\\b").matcher(q).find();
        if (methodWord && !methodQualified) {
            List<Map<String, Object>> opts = new ArrayList<>();
            catalogPage("/transactiontype").ifPresent(p -> { if (access.canOpen(p.url())) opts.add(link(p, 10)); });
            catalogPage("/helpCenter").ifPresent(p -> { if (access.canOpen(p.url())) opts.add(link(p, 5)); });
            Map<String, Object> res = mkResult("clarify",
                    "Do you mean a payment / transaction method (Cash, Check, Card, Bank Transfer — managed under Transaction Types), or something else? "
                    + "Say “add a new payment method”, or tell me which feature you have in mind.", List.of(), opts);
            res.put("topic", "method");
            return res;
        }

        List<Map<String, Object>> links = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        List<Map.Entry<AiNavigationCatalog.Page, Integer>> scored = new ArrayList<>();
        for (AiNavigationCatalog.Page p : AiNavigationCatalog.PAGES) {
            int score = 0;
            for (String kw : p.keywords()) if (Pattern.compile("\\b" + Pattern.quote(kw) + "\\b").matcher(q).find()) score += 3 + kw.length() / 4;
            if (q.contains(p.label().toLowerCase())) score += 6;
            if (score > 0) scored.add(Map.entry(p, score));
        }
        scored.sort((a, b) -> b.getValue() - a.getValue());
        for (Map.Entry<AiNavigationCatalog.Page, Integer> e : scored) {
            AiNavigationCatalog.Page p = e.getKey();
            if (access.canOpen(p.url())) { if (links.size() < 8) links.add(link(p, e.getValue())); }
            else if (unavailable.size() < 3 && !unavailableAlready(unavailable, p)) unavailable.add(p.label() + " — " + p.description());
        }

        String ans;
        if (links.isEmpty() && unavailable.isEmpty()) {
            ans = "I couldn’t match that to a page. Try the feature’s name, for example “Open the Income Report” or “Go to Meetings”.";
        } else if (links.isEmpty()) {
            // The feature exists; this login cannot open it. Say so — never that it does not exist.
            ans = "That feature is part of ChurchGeniusPro but is not available to your current login, permissions or plan — an administrator can enable it: "
                    + String.join("; ", unavailable) + ".";
        } else {
            ans = "Here " + (links.size() == 1 ? "is the page" : "are " + links.size() + " pages") + " matching your search:"
                    + (unavailable.isEmpty() ? "" : " (Also related, but not available to your login: " + String.join("; ", unavailable) + ".)");
        }
        Map<String, Object> res = mkResult("navigation", ans, List.of(), links);
        res.put("sections", List.of());
        return res;
    }

    private static boolean unavailableAlready(List<String> list, AiNavigationCatalog.Page p) {
        for (String s : list) if (s.startsWith(p.label() + " — ")) return true;
        return false;
    }
    private static java.util.Optional<AiNavigationCatalog.Page> catalogPage(String url) {
        return AiNavigationCatalog.PAGES.stream().filter(p -> p.url().equals(url)).findFirst();
    }
    private static Map<String, Object> link(AiNavigationCatalog.Page p, int score) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", p.label()); m.put("url", p.url()); m.put("icon", "🔗");
        m.put("description", p.description()); m.put("section", p.category()); m.put("score", score);
        return m;
    }

    // ── Members count ─────────────────────────────────────────────────────

    private Map<String, Object> handleMemberCount(boolean isNew, int month, int year,
                                                   String appClientId, AiAccess access) {
        long count;
        String ans;
        if (isNew) {
            count = memberRepo.countNewMembersInMonth(appClientId, month, year);
            String monthLabel = capitalize(MONTH_FULL[month - 1]) + " " + year;
            ans = count + " new member(s) were added in " + monthLabel + ".";
        } else {
            count = memberRepo.countActiveMembers(appClientId);
            ans = "There are " + count + " active member(s) in the church.";
        }

        // Include the Families page link when this login may open it
        List<Map<String, Object>> links = new ArrayList<>();
        catalogPage("/viewfamily").ifPresent(p -> { if (access.canOpen(p.url())) links.add(link(p, 10)); });
        return mkResult("members_count", ans, List.of(), links);
    }

    // ── Permission helpers ────────────────────────────────────────────────

    /** The one denial wording: never says what exists or who else may see it. */
    public static final String DENIED_MSG = AiDataSearchService.DENIED_MSG;

    private Map<String, Object> denied() {
        return mkResult("denied", DENIED_MSG, List.of(), null);
    }

    // ── Row builders ──────────────────────────────────────────────────────

    private List<Map<String, Object>> fmtIncome(List<Object[]> rows) {
        return rows.stream().limit(20).map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date",   r[1] != null ? r[1].toString() : "");
            m.put("name",   trim2(r[2]) + " " + trim2(r[3]));
            m.put("fund",   trim2(r[4]) + " / " + trim2(r[5]));
            m.put("amount", r[6] != null ? "$" + fmt(new BigDecimal(r[6].toString())) : "$0.00");
            m.put("method", trim2(r[7]));
            return m;
        }).collect(Collectors.toList());
    }

    private List<Map<String, Object>> fmtExpense(List<Object[]> rows) {
        return rows.stream().limit(20).map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fund",    trim2(r[0]));
            m.put("purpose", trim2(r[1]));
            m.put("date",    r[2] != null ? r[2].toString() : "");
            m.put("amount",  r[3] != null ? "$" + fmt(new BigDecimal(r[3].toString())) : "$0.00");
            m.put("method",  trim2(r[4]));
            return m;
        }).collect(Collectors.toList());
    }

    private Map<String, Object> bdayRow(FamilyMember m, String type) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (type != null) row.put("type", type);
        row.put("name",      fullName(m));
        row.put("day",       m.getBirthdayDay());
        row.put("month",     m.getBirthdayMonth());
        row.put("year",      m.getBirthdayYear());
        row.put("monthName", m.getBirthdayMonth() != null
                             ? MONTH_FULL[m.getBirthdayMonth() - 1] : "");
        return row;
    }

    private Map<String, Object> annivRow(FamilyMember m, String type) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (type != null) row.put("type", type);
        row.put("name",      fullName(m));
        row.put("day",       m.getAnniversaryDay());
        row.put("month",     m.getAnniversaryMonth());
        row.put("year",      m.getAnniversaryYear());
        row.put("monthName", m.getAnniversaryMonth() != null
                             ? MONTH_FULL[m.getAnniversaryMonth() - 1] : "");
        return row;
    }

    // ── NLP helpers ───────────────────────────────────────────────────────

    private boolean has(String text, String... keywords) {
        for (String kw : keywords) if (text.contains(kw)) return true;
        return false;
    }

    private String extractName(String q) {
        // Ordered patterns: most specific first
        String[] pats = {
            "\\bfor ([a-z]+)\\b",                                          // "for Anson"
            "\\b([a-z]+)'s\\b",                                            // "Anson's"
            "\\bof ([a-z]+)\\b",                                           // "of Anson"
            "\\babout ([a-z]+)\\b",                                        // "about Anson"
            "\\b(?:did|does|has|have|check) ([a-z]{3,})\\b",              // "did Anson give"
            "\\b([a-z]{3,}) (?:give|gave|paid|donated|tithe|contributed)\\b" // "Anson give"
        };
        for (String pat : pats) {
            Matcher m = Pattern.compile(pat).matcher(q);
            if (m.find()) {
                String cand = m.group(1);
                if (!STOP_WORDS.contains(cand)
                        && !INCOME_CATEGORY_WORDS.contains(cand)
                        && cand.length() >= 3) return cand;
            }
        }
        return null;
    }

    /**
     * Extracts a fund/category keyword from the query for income filtering.
     * Scans each word for a match against INCOME_CATEGORY_WORDS, then normalizes
     * plurals to their singular canonical form (e.g. "gifts" → "gift").
     */
    private String extractCategory(String q) {
        for (String word : q.replaceAll("[^a-z ]", "").split("\\s+")) {
            if (INCOME_CATEGORY_WORDS.contains(word)) return normalizeCategory(word);
        }
        return null;
    }

    private int extractMonth(String q, int def) {
        if (q.contains("this month") || q.contains("current month")) return def;
        if (q.contains("last month")) return def == 1 ? 12 : def - 1;
        for (int i = 0; i < MONTH_FULL.length; i++) {
            if (q.contains(MONTH_FULL[i]) || q.contains(MONTH_SHORT[i])) return i + 1;
        }
        return def;
    }

    private int extractYear(String q, int def) {
        Matcher m = Pattern.compile("\\b(20\\d{2})\\b").matcher(q);
        if (m.find()) return Integer.parseInt(m.group(1));
        if (q.contains("last year")) return def - 1;
        return def;
    }

    private String fullName(FamilyMember m) {
        return (nvl(m.getFirstName()) + " " + nvl(m.getLastName())).trim();
    }

    private String nvl(String s) { return s != null ? s : ""; }
    private String trim2(Object o) { return o != null ? o.toString().trim() : ""; }
    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
    private String fmt(BigDecimal bd) { return bd.setScale(2, RoundingMode.HALF_UP).toPlainString(); }

    // ── Response builder ──────────────────────────────────────────────────

    private Map<String, Object> mkResult(String intent, String answer,
                                          List<?> results, List<?> links) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intent",  intent);
        m.put("answer",  answer);
        m.put("results", results != null ? results : List.of());
        m.put("links",   links   != null ? links   : List.of());
        return m;
    }
}
