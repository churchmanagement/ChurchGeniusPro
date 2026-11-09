package com.churchgeniuspro.ai.guided;

import com.churchgeniuspro.ai.action.AiActionLookup.Hit;
import com.churchgeniuspro.ai.action.AiFinanceLookup;
import com.churchgeniuspro.ai.action.AiSettingsTools;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2026-11-08 — AI Search never edits or deletes an existing record. A request to change, rename or delete a fund,
 * sub-fund, purpose (expense category), transaction type (payment method), an income / expense entry or a meeting
 * is answered with a message and a link to the page that manages it ("Open Funds" → {@code /fund}); the change is
 * then made there with the page's own workflow, validation and permission checks. Nothing is read beyond the
 * church's own Account Settings names (to recognise "delete Zelle"), and nothing is written.
 *
 * <p>The link is shown only when this login may open that page (the page's own rule); otherwise the message says so.
 * Open guided conversations are kept: while one is open, a correction of that conversation's own field ("change the
 * fund to …", "rename it to …", "remove the note") is left to it — only a request naming an existing record is
 * redirected. How-to questions ("how do I delete a fund?") are left to the help routes.
 */
@Service
public class AiManageRedirectService {

    public enum Target { FUND, PURPOSE, TYPE, INCOME, EXPENSE, RECORD, MEETING }

    /** Account Settings names, to recognise "delete Zelle"; optional so hand-built tests can leave it out. */
    @Autowired(required = false)
    private AiFinanceLookup lookup;

    public AiManageRedirectService() {}
    public AiManageRedirectService(AiFinanceLookup lookup) { this.lookup = lookup; }

    static final String LEAD = "(?:please\\s+|kindly\\s+|can\\s+you\\s+|could\\s+you\\s+|would\\s+you\\s+|will\\s+you\\s+|"
        + "i\\s+(?:want|need|would\\s+like)\\s+to\\s+|i'?d\\s+like\\s+to\\s+|let'?s\\s+|help\\s+me\\s+|go\\s+ahead\\s+and\\s+)*";
    /** Edit / delete verbs — never a create verb, never "mark" / "complete" (follow-up actions) or "cancel" (ends a conversation). */
    static final String VERBS = "(edit|change|update|modify|rename|correct|fix|amend|adjust|delete|remove|erase|drop|"
        + "deactivate|disable|inactivate|archive|hide|merge|get\\s+rid\\s+of|take\\s+out)";
    static final Pattern MANAGE = Pattern.compile("(?i)^\\s*" + LEAD + VERBS + "\\b\\s*(.*)$");
    static final Pattern DESTRUCTIVE = Pattern.compile("(?i)^(delete|remove|erase|drop|deactivate|disable|inactivate|archive|hide|get\\s+rid\\s+of|take\\s+out)$");
    /** A field of an open conversation right after the verb: "change the fund to …", "rename it", "remove the note". */
    static final Pattern OWN_FIELD = Pattern.compile("(?i)^(?:the\\s+|my\\s+|this\\s+|that\\s+)?(?:(?:meeting|income|expense|entry|fund|sub-?fund)(?:'s)?\\s+)?(it|that|this|name|title|main\\s+fund|parent(?:\\s+fund)?|fund|"
        + "tax[- ]?deductible(?:\\s+status)?|deductible|contributor|giver|donor|member|guest|from|account|category|expense\\s+category|purpose|"
        + "amount|total|date|day|method|payment\\s+method|payment|reference(?:\\s+number)?|ref(?:\\s*(?:number|no\\.?|#))?|check\\s+number|"
        + "notes?|memo|meeting\\s+type|type|start\\s+time|time|location|place|venue|address)\\b\\s*(?:to|into|as|is|:|=|$|[.!?])");
    static final Pattern KIND = Pattern.compile("(?i)\\b" + AiGuidedSettingService.KIND_WORDS + "\\b(?!\\s*-?\\s*rais)");
    static final String NOT_A_SETTING_AFTER = "(?!\\s+(?:funds?|categor(?:y|ies)|types?|purposes?|methods?|sub[- ]?\\w+|(?:transaction|payment)\\s+types?))";
    static final Pattern INCOME_WORD = Pattern.compile("(?i)\\b(income|incomes|donations?|gifts?|contributions?|tithes?|offerings?|receipts?)\\b" + NOT_A_SETTING_AFTER
        + "|\\b(gave|given|donated|contributed|tithed)\\b");
    static final Pattern EXPENSE_WORD = Pattern.compile("(?i)\\b(expenses?|bills?|purchases?|reimbursements?)\\b" + NOT_A_SETTING_AFTER
        + "|\\b(spent|bought|purchased|reimbursed)\\b");
    static final Pattern RECORD_WORD = Pattern.compile("(?i)\\b(transactions?|entr(?:y|ies))\\b" + NOT_A_SETTING_AFTER + "|\\$\\s?\\d|\\b\\d+(?:\\.\\d{1,2})?\\s*dollars?\\b");
    static final Pattern MEETING_WORD = Pattern.compile("(?i)\\bmeetings?\\b");
    static final Pattern TARGET_END = Pattern.compile("(?i)\\s+(?:to|into|as|from|so|because|with|and\\s+(?:make|call|rename|change))\\b|[,;]|[.!?]\\s*$");

    /** The record kind an edit / delete request is about, or null when it is not one (pure text rules, no lookup). */
    public static Target detect(String question) { return detect(question, List.of()); }

    /**
     * Same, with the church's Account Settings names masked first, so a fund called "Tithe" or a transaction type
     * called "Special Offering" is not read as an income entry. Income / expense words are looked for only in the part
     * that names the record — before "to / into / as" ("rename the fund Tithe to Tithes" is about a fund).
     */
    static Target detect(String question, List<String> settingNames) {
        Matcher m = question == null ? null : MANAGE.matcher(question);
        if (m == null || !m.matches()) return null;
        String rest = m.group(2);
        String target = rest;
        Matcher end = Pattern.compile("(?i)\\s+(?:to|into|as)\\s").matcher(target);
        if (end.find()) target = target.substring(0, end.start());
        for (String n : settingNames) {
            if (n == null || n.isBlank()) continue;
            target = target.replaceAll("(?i)(?<![\\p{L}\\p{N}])" + Pattern.quote(n.trim()) + "(?![\\p{L}\\p{N}])", " entry-name ");
        }
        String recordPart = target.replace(" entry-name ", " ");
        // "delete the transaction type Special Offering": a settings word BEFORE any income / expense word names the record
        Matcher k0 = KIND.matcher(recordPart);
        if (k0.find()) {
            int first = firstIndex(recordPart, INCOME_WORD, EXPENSE_WORD, RECORD_WORD);
            if (first < 0 || k0.start() < first) return kindOf(k0.group(1));
        }
        boolean income = INCOME_WORD.matcher(recordPart).find(), expense = EXPENSE_WORD.matcher(recordPart).find();
        if (income && !expense) return Target.INCOME;
        if (expense && !income) return Target.EXPENSE;
        if (income || RECORD_WORD.matcher(recordPart).find()) return Target.RECORD;
        Matcher k = KIND.matcher(rest);
        if (k.find()) return kindOf(k.group(1));
        if (MEETING_WORD.matcher(rest).find()) return Target.MEETING;
        return null;
    }

    private static int firstIndex(String text, Pattern... ps) {
        int best = -1;
        for (Pattern p : ps) { Matcher x = p.matcher(text); if (x.find() && (best < 0 || x.start() < best)) best = x.start(); }
        return best;
    }

    static Target kindOf(String kindWords) {
        String k = kindWords.toLowerCase(Locale.ROOT);
        if (k.matches(".*\\b(?:types?|methods?)\\b.*") && !k.startsWith("expense")) return Target.TYPE;
        if (k.contains("purpose") || k.startsWith("expense ") && !k.contains("fund")) return Target.PURPOSE;
        return Target.FUND;
    }

    /**
     * The redirect answer, or null when the question is not an edit / delete request of a record (or is a correction
     * of the open conversation). {@code draftOpen} = a meeting, income / expense or Account Settings conversation is
     * waiting for an answer.
     */
    public Map<String, Object> handle(HttpServletRequest req, String cid, String question, boolean draftOpen) {
        Matcher m = question == null ? null : MANAGE.matcher(question.trim());
        if (m == null || !m.matches()) return null;
        String verb = m.group(1).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        String rest = m.group(2).trim();
        if (draftOpen && OWN_FIELD.matcher(rest).lookingAt()) return null;     // "change the fund to …" belongs to the open conversation

        boolean settingsAllowed = AiSettingsTools.mayManageSettings(req);
        Named named = settingsAllowed ? exactName(cid, rest) : null;        // "delete Zelle", "rename Missions to Global Missions"
        Target t = named != null ? named.target : detect(question.trim(), settingNames(cid));
        if (t == null) return null;
        if (named == null && settingsAllowed && (t == Target.FUND || t == Target.PURPOSE || t == Target.TYPE)) named = mentioned(cid, t, rest);

        boolean delete = DESTRUCTIVE.matcher(verb).matches();
        String act = delete ? "delete" : "change";
        String what = named == null ? null : "“" + named.label + "”";
        String answer; String kind; String url = null; String label = null; boolean may;
        switch (t) {
            case FUND, PURPOSE, TYPE -> {
                String page = t == Target.FUND ? "Funds" : t == Target.PURPOSE ? "Expense Categories (Purposes)" : "Transaction Types";
                String path = t == Target.FUND ? "Account Settings → Fund" : t == Target.PURPOSE ? "Account Settings → Purpose" : "Account Settings → Transaction Type";
                String nouns = t == Target.FUND ? "funds or sub-funds" : t == Target.PURPOSE ? "purposes (expense categories)" : "transaction types";
                kind = t == Target.FUND ? (named != null && named.sub ? "sub-fund" : "fund") : t == Target.PURPOSE ? "purpose" : "transaction type";
                may = settingsAllowed;
                url = t == Target.FUND ? "/fund" : t == Target.PURPOSE ? "/purpose" : "/transactiontype";
                label = t == Target.FUND ? "Open Funds" : t == Target.PURPOSE ? "Open Purposes" : "Open Transaction Types";
                answer = "AI Search can add new " + nouns + ", but it can't change or delete existing ones. "
                    + (may ? "To " + act + " " + (what != null ? what : "it") + ", open the " + page + " page (" + path + ") — the change is made there with the page's usual checks and permissions."
                           : "You don't have access to the " + page + " page (" + path + "), so please ask your church administrator.");
            }
            case INCOME, EXPENSE, RECORD -> {
                boolean inc = mayOpen(req, "accounting.income"), exp = mayOpen(req, "accounting.expense");
                kind = t == Target.INCOME ? "income" : t == Target.EXPENSE ? "expense" : "income or expense";
                if (t == Target.INCOME) { may = inc; url = "/income"; label = "Open Income"; }
                else if (t == Target.EXPENSE) { may = exp; url = "/expense"; label = "Open Expense"; }
                else { may = inc || exp; url = inc ? "/income" : "/expense"; label = inc ? "Open Income" : "Open Expense"; }
                String page = t == Target.INCOME ? "Income" : t == Target.EXPENSE ? "Expense" : (inc && exp ? "Income or Expense" : inc ? "Income" : "Expense");
                answer = "AI Search can't change or delete " + kind + " entries. "
                    + (may ? "Open the " + page + " page, find the entry and " + act + " it there — the page's usual checks and permissions apply."
                           : "You don't have access to the " + page + " page, so please ask your church administrator.");
            }
            default -> {
                kind = "meeting";
                may = staffRole(req) && RoleGuard.requirePagePermission(req, "general.meetings") == null;
                url = "/meetings"; label = "Open Meetings";
                answer = "AI Search can schedule a new meeting, but it can't change or delete existing meetings. "
                    + (may ? "Open the Meetings page, find the meeting and " + act + " it there."
                           : "You don't have access to the Meetings page, so please ask your church administrator.");
            }
        }
        Map<String, Object> out = AiGuidedRecordService.base("manage.redirect", answer, kind);
        out.put("action", act);
        out.put("readOnly", true);                         // nothing was changed
        if (named != null) out.put("record", named.label);
        if (may) out.put("link", new LinkedHashMap<>(Map.of("label", label, "url", url)));
        if (draftOpen) {
            out.put("answer", answer + " Your unsaved entry here is still open — answer the last question to carry on, or say “cancel”.");
            out.put("pending", true);                      // the conversation thread stays open
        }
        return out;
    }

    /** True while a meeting, income / expense or Account Settings conversation (or an action card) waits for an answer. */
    public static boolean draftOpen(HttpSession s) {
        if (s == null) return false;
        return s.getAttribute("aiMeetingDraft") != null || AiGuidedRecordService.hasDraft(s)
            || s.getAttribute(AiGuidedSettingService.SESSION_KEY) != null
            || s.getAttribute("aiActionDraft") != null || s.getAttribute("aiActionPendingToken") != null;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    record Named(Target target, String label, boolean sub) {}

    /** The text after the verb is EXACTLY one of this church's Account Settings names ("delete Zelle"). */
    Named exactName(String cid, String rest) {
        if (lookup == null || cid == null) return null;
        String probe = rest.replaceFirst("(?i)^(?:the|our|my|this|that)\\s+", "");
        Matcher e = TARGET_END.matcher(probe);
        if (e.find()) probe = probe.substring(0, e.start());
        String p = AiGuidedRecordService.norm(probe.replaceAll("[\"“”]", " "));
        if (p.isEmpty()) return null;
        for (Object[] src : sources(cid)) {
            for (Hit h : list(src)) if (AiGuidedRecordService.norm(h.label()).equals(p)) return new Named((Target) src[0], h.label(), (Boolean) src[2]);
        }
        return null;
    }

    /** The longest of this church's names of that kind that the request mentions ("delete the Building Fund"). */
    Named mentioned(String cid, Target t, String rest) {
        if (lookup == null || cid == null) return null;
        Named best = null;
        for (Object[] src : sources(cid)) {
            if (src[0] != t) continue;
            for (Hit h : AiGuidedRecordService.matchNames(rest, list(src)))
                if (best == null || h.label().length() > best.label.length()) best = new Named(t, h.label(), (Boolean) src[2]);
        }
        return best;
    }

    /** Every Account Settings name of this church, longest first (masking only — never shown to the user). */
    List<String> settingNames(String cid) {
        if (lookup == null || cid == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Object[] src : sources(cid)) for (Hit h : list(src)) if (h.label() != null) out.add(h.label());
        out.sort(Comparator.comparingInt(String::length).reversed());
        return out;
    }

    private List<Object[]> sources(String cid) {
        return List.of(new Object[]{Target.TYPE, (java.util.function.Supplier<List<Hit>>) () -> lookup.methods(cid), false},
                       new Object[]{Target.PURPOSE, (java.util.function.Supplier<List<Hit>>) () -> lookup.expenseCategories(cid), false},
                       new Object[]{Target.FUND, (java.util.function.Supplier<List<Hit>>) () -> lookup.expenseFunds(cid), false},
                       new Object[]{Target.FUND, (java.util.function.Supplier<List<Hit>>) () -> lookup.incomeFunds(cid), true});
    }
    @SuppressWarnings("unchecked")
    private static List<Hit> list(Object[] src) {
        try { List<Hit> l = ((java.util.function.Supplier<List<Hit>>) src[1]).get(); return l == null ? List.of() : l; }
        catch (RuntimeException e) { return List.of(); }
    }

    private static boolean mayOpen(HttpServletRequest req, String pageKey) {
        return RoleGuard.requireAccountantOrAdmin(req) == null && RoleGuard.requirePagePermission(req, pageKey) == null;
    }
    private static final Set<String> STAFF = Set.of("SuperAdmin", "Admin", "Accountant", "User");
    private static boolean staffRole(HttpServletRequest req) {
        HttpSession s = req == null ? null : req.getSession(false);
        if (s == null) return false;
        Object ch = s.getAttribute("church");
        if (Boolean.TRUE.equals(ch) || "true".equalsIgnoreCase(String.valueOf(ch))) return false;
        return STAFF.contains(String.valueOf(s.getAttribute("role")));
    }
}
