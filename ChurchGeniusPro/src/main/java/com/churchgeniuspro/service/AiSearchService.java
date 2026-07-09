package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.FamilyMember;
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

    // ── Accessible sections per role ──────────────────────────────────────

    private Set<String> accessibleSections(String role, boolean isChurch) {
        if (isChurch) return Set.of("admin");
        if (role == null) return Set.of();
        return switch (role) {
            case "SuperAdmin" -> Set.of("admin","admin-settings","reminders",
                                        "accounting","accounting-reports","account-settings");
            case "Admin"      -> Set.of("admin","admin-settings","reminders");
            case "Accountant" -> Set.of("admin","accounting","accounting-reports","account-settings");
            default           -> Set.of("admin");  // "User" — GENERAL section only
        };
    }

    // ── All navigable pages ───────────────────────────────────────────────

    private static final List<Map<String, String>> ALL_PAGES = List.of(
        // ── Accounting (transactional) ───────────────────────────────────
        pg("Income",            "/income",              "💰", "Record and view income transactions",          "accounting",           "income,tithe,offering,contribution,donation,giving,revenue,collected"),
        pg("Expense",           "/expense",             "📤", "Record and view expenses",                    "accounting",           "expense,payment,spending,cost,purchase,expenditure"),
        // ── Accounting Reports ────────────────────────────────────────────
        pg("Income Reports",    "/income-report",       "📊", "View income reports and summaries",           "accounting-reports",   "report,income,reports,summary,annual"),
        pg("Expense Reports",   "/expense-report",      "📈", "View expense reports and summaries",          "accounting-reports",   "report,expense,reports,summary"),
        pg("Transactions",      "/transactions-report", "📋", "View all transaction records",                "accounting-reports",   "transaction,transactions,history,ledger,all"),
        pg("Tax Report",        "/tax-report",          "🧾", "Generate year-end tax reports",               "accounting-reports",   "tax,annual,year,report"),
        // ── General (admin) ───────────────────────────────────────────────
        pg("Families",          "/family",              "👨‍👩‍👧", "Manage church families and members",          "admin",                "family,families,member,members,people,household"),
        pg("Meetings",          "/meetings",            "📅", "Schedule and track meetings",                 "admin",                "meeting,meetings,schedule,service,worship"),
        pg("Events",            "/event",               "🎉", "Create and manage events",                   "admin",                "event,events,program,programs,celebration,conference"),
        pg("Groups",            "/groups",              "👥", "Manage groups and small groups",              "admin",                "group,groups,ministry,team,cell"),
        pg("Prayer Requests",   "/prayerRequest",       "🙏", "View and manage prayer requests",             "admin",                "prayer,prayers,request,requests,intercession"),
        pg("Event Calendar",    "/eventcalendar",       "📆", "View the full event calendar",                "admin",                "calendar,schedule,event,agenda"),
        pg("Notify / Email",    "/notifyEmail",         "✉️", "Send bulk notifications and emails",          "admin",                "email,notify,notification,send,message,bulk"),
        // ── Admin Settings ────────────────────────────────────────────────
        pg("View Users",        "/viewusers",           "🧑‍💼", "Manage staff user accounts",                 "admin-settings",       "user,users,staff,account,login,password"),
        pg("Meeting Types",     "/meetingtype",         "📋", "Configure meeting type categories",           "admin-settings",       "meeting,type,category"),
        // ── Reminders ─────────────────────────────────────────────────────
        pg("Reminders",         "/eventReminders",      "🔔", "Manage event reminders",                      "reminders",            "reminder,reminders,alert,alerts,notification"),
        pg("Meeting Reminders", "/meetingReminders",    "🔔", "Manage meeting reminders",                    "reminders",            "reminder,meeting,alert"),
        // ── Account Settings ──────────────────────────────────────────────
        pg("Purposes",          "/purpose",             "🗂️", "Manage expense purpose categories",           "account-settings",     "purpose,category,expense,type"),
        pg("Funds / Sources",   "/source",              "💳", "Manage income fund categories",               "account-settings",     "fund,source,category,income,type"),
        pg("Transaction Types", "/transactiontype",     "🏷️", "Configure transaction type categories",       "account-settings",     "transaction,type,category")
    );

    private static Map<String, String> pg(String label, String url, String icon,
                                           String desc, String section, String keywords) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("label", label); m.put("url", url); m.put("icon", icon);
        m.put("description", desc); m.put("section", section); m.put("keywords", keywords);
        return m;
    }

    // ── Stop words for name extraction ────────────────────────────────────

    private static final Set<String> STOP_WORDS = Set.of(
        "the","a","an","this","my","our","church","income","expense","birthday",
        "anniversary","month","year","today","week","all","list","show","me","is",
        "it","did","i","add","any","records","data","there","to","in","on","at",
        "by","with","give","get","have","was","were","are","has","been","be","do",
        "not","no","yes","can","could","would","should","will","may","might","must",
        "last","next","current","please","much","many","some","when","what",
        "who","where","how","which","that","and","or","but","if","than","then",
        "so","because","as","about","just","only","also","too","very","more","most"
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
    public Map<String, Object> search(String query, String role,
                                      boolean isChurch, String appClientId) {
        if (query == null || query.isBlank()) {
            return mkResult("error", "Please enter a search query.", List.of(), null);
        }

        String q = query.toLowerCase().trim();
        LocalDate today = LocalDate.now();

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
                                  "was","were") || topicBday || topicAnniv;

        boolean wantsData = dataCtx && (topicIncome || topicExpense || topicBday || topicAnniv);

        // ── Extract supporting entities ──────────────────────────────────
        int month    = extractMonth(q, today.getMonthValue());
        int year     = extractYear(q, today.getYear());
        String name  = extractName(q);
        String category = extractCategory(q);

        // ── Route to handler ─────────────────────────────────────────────
        if (wantsData && topicIncome) {
            if (!canAccessFinancials(role)) return denied();
            return handleIncome(name, category, month, year, appClientId);
        }
        if (wantsData && topicExpense) {
            if (!canAccessFinancials(role)) return denied();
            return handleExpense(name, month, year, appClientId);
        }
        if (topicBday && topicAnniv) {
            return handleBirthdayAndAnniversary(month, appClientId);
        }
        if (topicBday) {
            return handleBirthday(name, month, appClientId);
        }
        if (topicAnniv) {
            return handleAnniversary(name, month, appClientId);
        }
        if (topicCount && topicMembers) {
            return handleMemberCount(topicNew, month, year, appClientId, role, isChurch);
        }

        // ── Default: navigation ───────────────────────────────────────────
        return handleNavigation(q, role, isChurch);
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

    private Map<String, Object> handleNavigation(String q, String role, boolean isChurch) {
        Set<String> allowed = accessibleSections(role, isChurch);

        List<Map<String, Object>> links = ALL_PAGES.stream()
            .filter(p -> allowed.contains(p.get("section")))
            .map(p -> {
                int score = 0;
                for (String kw : p.get("keywords").split(",")) {
                    if (q.contains(kw.trim())) score += 3;
                }
                if (q.contains(p.get("label").toLowerCase())) score += 6;
                Map<String, Object> out = new LinkedHashMap<>(p);
                out.put("score", score);
                return out;
            })
            .filter(p -> (int) p.get("score") > 0)
            .sorted((a, b) -> (int) b.get("score") - (int) a.get("score"))
            .limit(8)
            .collect(Collectors.toList());

        // Build section labels
        List<String> sections = new ArrayList<>();
        if (allowed.contains("accounting"))         sections.add("ACCOUNTING");
        if (allowed.contains("accounting-reports")) sections.add("ACCOUNTING REPORTS");
        if (allowed.contains("admin"))              sections.add("GENERAL");
        if (allowed.contains("reminders"))          sections.add("REMINDERS");
        if (allowed.contains("admin-settings"))     sections.add("ADMIN SETTINGS");
        if (allowed.contains("account-settings"))   sections.add("ACCOUNT SETTINGS");

        String ans;
        if (links.isEmpty() && sections.isEmpty()) {
            ans = "No accessible pages found. Please contact your administrator for access.";
        } else if (links.isEmpty()) {
            ans = "Your accessible sections: " + String.join(", ", sections) + ". No pages matched your query — try a more specific term.";
        } else {
            ans = "Here are " + links.size() + " page(s) matching your search:";
        }

        Map<String, Object> res = mkResult("navigation", ans, List.of(), links);
        res.put("sections", sections);
        return res;
    }

    // ── Members count ─────────────────────────────────────────────────────

    private Map<String, Object> handleMemberCount(boolean isNew, int month, int year,
                                                   String appClientId,
                                                   String role, boolean isChurch) {
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

        // Include the Families page link if the role has access
        Set<String> allowed = accessibleSections(role, isChurch);
        List<Map<String, Object>> links = List.of();
        if (allowed.contains("admin")) {
            links = ALL_PAGES.stream()
                .filter(p -> "/family".equals(p.get("url")))
                .map(p -> { Map<String, Object> out = new LinkedHashMap<>(p); out.put("score", 10); return out; })
                .collect(Collectors.toList());
        }
        return mkResult("members_count", ans, List.of(), links);
    }

    // ── Permission helpers ────────────────────────────────────────────────

    private boolean canAccessFinancials(String role) {
        return "SuperAdmin".equals(role) || "Accountant".equals(role);
    }

    private Map<String, Object> denied() {
        return mkResult("denied",
            "You are not permitted to view income or expense data. " +
            "Only Accountants and Super Admins can access financial records.",
            List.of(), null);
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
