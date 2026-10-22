package com.churchgeniuspro.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI Search vocabulary: the different ways people ask the same thing, mapped to the one word
 * the deterministic router already understands. Applied once to the normalised question, before
 * any routing, by {@link AiDataSearchService} — so every category (giving, income, expenses,
 * transactions, meetings, events, volunteers) gains the same synonym handling and no intent has
 * to list every variant itself.
 *
 * <p>Rules:
 * <ul>
 *   <li>Whole words only, longest phrase first, case-insensitive; the input is the output of
 *       {@code AiDataSearchService.norm} (lower-case, punctuation removed, "Anson's" → "anson").</li>
 *   <li>Only routing changes. Names are matched on the ORIGINAL question, so a member called
 *       "Gift" or "Bill" is still found; period words are never rewritten.</li>
 *   <li>Idioms are protected before synonyms: "give me a list" is a request to show, not giving.</li>
 * </ul>
 * Nothing here grants access: the canonical question goes through the same AiAccess checks.
 */
public final class AiQueryLexicon {

    private AiQueryLexicon() {}

    /** One synonym group: every phrase in {@code variants} becomes {@code canonical}. */
    record Group(String canonical, List<String> variants) {}

    /**
     * Phrases rewritten before the synonym groups (idioms whose words would otherwise be read
     * as a synonym). Order matters only within this list.
     */
    static final List<String[]> IDIOMS = List.of(
        new String[] { "give me", "show me" },
        new String[] { "give us", "show us" },
        new String[] { "can you give", "can you show" },
        new String[] { "given period", "period" },
        new String[] { "for a given", "for a" }
    );

    static final List<Group> GROUPS = List.of(
        // Tithes stay their own word: the person-giving answer can restrict to tithe funds.
        new Group("tithe", List.of("tithes", "tithed", "tithing", "tithe")),
        new Group("contribution", List.of(
            "contributions", "contributed", "contributing", "contribute",
            "donations", "donation", "donated", "donating", "donate", "donor", "donors",
            "offerings", "offering", "gifts", "gift", "gave", "given", "giving", "gives", "give",
            "givers", "giver", "contributors", "contributor", "pledged money")),
        new Group("income", List.of(
            "money received", "money in", "funds received", "amount received",
            "revenue", "revenues", "receipts", "takings", "earnings", "collections", "collection", "income")),
        new Group("expenses", List.of(
            "money spent", "money out", "amount spent", "paid out", "payments made", "outgoings",
            "expenditures", "expenditure", "expenses", "expense", "spending", "spent", "spend",
            "costs", "cost", "bills", "purchases")),
        new Group("transactions", List.of(
            "ledger entries", "ledger", "money movements", "all entries", "transaction", "transactions")),
        new Group("meetings", List.of(
            "meet ups", "meetups", "meetup", "meet up", "meetings", "meeting")),
        new Group("events", List.of(
            "programmes", "programme", "programs", "program", "functions", "occasions", "happenings",
            "church activities", "events", "event")),
        new Group("volunteers", List.of(
            "volunteering", "volunteered", "helpers", "helper", "volunteers", "volunteer")),
        new Group("serving", List.of("on duty", "on the rota", "rota", "serving"))
    );

    private static final List<Object[]> COMPILED = new ArrayList<>();
    static {
        for (String[] i : IDIOMS) COMPILED.add(new Object[] { word(i[0]), i[1] });
        // Longest variant first across all groups, so "money spent" wins over "spent".
        List<String[]> pairs = new ArrayList<>();
        for (Group g : GROUPS) for (String v : g.variants()) pairs.add(new String[] { v, g.canonical() });
        pairs.sort((a, b) -> b[0].length() - a[0].length());
        for (String[] p : pairs) COMPILED.add(new Object[] { word(p[0]), p[1] });
    }

    private static Pattern word(String phrase) {
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(phrase).replace(" ", "\\E\\s+\\Q") + "(?![a-z0-9])");
    }

    /**
     * The question with every synonym replaced by its canonical word. The result is used for
     * routing only; callers keep the original for name matching and for quoting back.
     */
    public static String canonical(String normalized) {
        if (normalized == null || normalized.isBlank()) return normalized == null ? "" : normalized;
        String s = " " + normalized.toLowerCase(Locale.ROOT) + " ";
        // Two passes over one list would re-match a canonical word with itself; mark replaced
        // spans with a placeholder so a later, shorter pattern cannot rewrite them again.
        List<String> outs = new ArrayList<>();
        for (Object[] c : COMPILED) {
            Matcher m = ((Pattern) c[0]).matcher(s);
            if (!m.find()) continue;
            StringBuilder sb = new StringBuilder();
            int last = 0;
            m.reset();
            while (m.find()) {
                sb.append(s, last, m.start());
                sb.append('\u0001').append(outs.size()).append('\u0002');
                last = m.end();
            }
            sb.append(s.substring(last));
            outs.add((String) c[1]);
            s = sb.toString();
        }
        Matcher ph = Pattern.compile("\u0001(\\d+)\u0002").matcher(s);
        StringBuilder out = new StringBuilder();
        while (ph.find()) ph.appendReplacement(out, Matcher.quoteReplacement(outs.get(Integer.parseInt(ph.group(1)))));
        ph.appendTail(out);
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    /** Every phrase the vocabulary maps (for the test that keeps the search bar's word list in step). */
    public static List<String> allVariants() {
        List<String> out = new ArrayList<>();
        for (Group g : GROUPS) out.addAll(g.variants());
        return out;
    }

    /** True when the canonical question asks about giving (any synonym). */
    public static boolean isGiving(String canonical) {
        return canonical != null && Pattern.compile("(?<![a-z])(contribution|tithe)(?![a-z])").matcher(canonical).find();
    }

    /** True when the canonical question asks about tithes specifically. */
    public static boolean isTithe(String canonical) {
        return canonical != null && Pattern.compile("(?<![a-z])tithe(?![a-z])").matcher(canonical).find();
    }

    /*
     * Who the question is about, from the ORIGINAL wording ("Anson's contribution", "did Anson
     * give", "Anson Mathew donated", "contributions from/by Anson"). Used only to say "I could not
     * find a member named …" instead of silently answering for the whole church — never to look
     * anything up by itself.
     */
    private static final String GIVE = "(?:give|gave|given|giving|donate|donated|donating|contribute|contributed|tithe|tithed|tithes|offer|offered)";
    private static final String GIFT = "(?:contribution|contributions|donation|donations|gift|gifts|giving|offering|offerings|tithe|tithes|tithing|pledge|pledges)";
    private static final String NAME = "([A-Z][\\p{L}'\\-]+(?:\\s+[A-Z][\\p{L}'\\-]+)?)";
    private static final List<Pattern> PERSON = List.of(
        Pattern.compile("\\b" + NAME + "(?:'s|’s|s')\\s+(?:\\w+\\s+){0,2}" + GIFT + "\\b"),
        Pattern.compile("(?i:\\b(?:did|has|have|does|do|will)\\s+)" + NAME + "\\s+(?i:(?:already\\s+|ever\\s+)?" + GIVE + ")\\b"),
        Pattern.compile("\\b" + NAME + "\\s+(?i:" + GIVE + ")\\b"),
        Pattern.compile("(?i:\\b" + GIFT + "\\s+(?:from|by|of)\\s+)" + NAME));
    private static final java.util.Set<String> NOT_NAMES = java.util.Set.of(
        "what", "how", "who", "which", "show", "list", "total", "the", "our", "my", "this", "last", "church", "members",
        "everyone", "anyone", "people", "we", "they", "has", "have", "did", "does", "much", "many", "i", "you",
        "ai", "please", "tell", "find", "give", "get", "all", "any", "each", "every", "whose", "when", "where");

    /** The person a giving question names (as typed), or null. */
    public static String personMentioned(String original) {
        if (original == null) return null;
        for (Pattern p : PERSON) {
            Matcher m = p.matcher(original);
            while (m.find()) {
                // Drop leading ordinary words a capitalised sentence start brings in
                // ("Show Anson's …" → "Anson"); nothing left means no person.
                List<String> words = new ArrayList<>(List.of(m.group(1).trim().replaceAll("['’]s$", "").split("\\s+")));
                while (!words.isEmpty() && NOT_NAMES.contains(words.get(0).toLowerCase(Locale.ROOT))) words.remove(0);
                if (words.isEmpty()) continue;
                return String.join(" ", words);
            }
        }
        return null;
    }
}
