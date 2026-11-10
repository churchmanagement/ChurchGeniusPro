package com.churchgeniuspro.ai.guided;

import com.churchgeniuspro.ai.action.AiActionLookup;
import com.churchgeniuspro.ai.action.AiActionLookup.Hit;
import com.churchgeniuspro.ai.action.AiActionService;
import com.churchgeniuspro.ai.action.AiFinanceLookup;
import com.churchgeniuspro.ai.action.AiFinanceTools;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.OpenAiVoiceService;
import com.churchgeniuspro.util.AppClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2026-11-08 — AI Search guided conversation for "add an income" / "add an expense" (plan "AI Search
 * Conversations (Meeting, Income, Expense)", decisions 1A–5A).
 *
 * <p>Flow: one missing required field at a time → (future date: confirmed once) → the optional Reference Number /
 * Notes question → "Are you missing anything, or are you ready to review …?" → (same entry already recorded:
 * "Save anyway?") → review card with Required / Optional rows → the user's explicit Save (button or "yes") →
 * {@link AiActionService#confirm} saves ONCE through the page's own service. Corrections ("change the fund to
 * Building", "actually $60") work at every step; "cancel" always cancels.
 *
 * <p>Rules: deterministic parsing first; the model (when {@code ai.guided.llm-fallback} is on) only when the rules
 * cannot read a reply, and it sees the reply text, the field names and the church's fund / category / method
 * names — never member names or contact details; every value it returns is matched against this church's own
 * lists. Every lookup is scoped to the signed-in church. Permission = the Income / Expense page's write rule,
 * checked first and on every message; plan feature {@code aiActions}.
 */
@Slf4j
@Service
public class AiGuidedRecordService {

    public static final String SESSION_KEY = "aiGuidedDraft";
    public static final String FEATURE = "aiActions";
    static final ObjectMapper JSON = new ObjectMapper();
    static final DateTimeFormatter D = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.ENGLISH);

    public enum Kind { INCOME, EXPENSE }
    enum Stage { ASK, OPTIONAL, REVIEW, CONFIRM }

    private final AiAccessPolicy access;
    private final AiActionService actions;
    private final AiActionLookup people;
    private final AiFinanceLookup lookup;
    private final OpenAiVoiceService openAi;

    @Value("${ai.guided.llm-fallback:true}")
    public boolean llmFallback = true;
    @Value("${ai.guided.draft-minutes:15}")
    public int draftMinutes = 15;

    @Autowired
    public AiGuidedRecordService(AiAccessPolicy access, AiActionService actions, AiActionLookup people, AiFinanceLookup lookup,
                                 @Autowired(required = false) OpenAiVoiceService openAi) {
        this.access = access; this.actions = actions; this.people = people; this.lookup = lookup; this.openAi = openAi;
    }

    /** 2026-11-09 — the login's own Income / Expense alerts, the same service the Income / Expense pages use. */
    private com.churchgeniuspro.entryalert.EntryAlertService entryAlerts;
    @Autowired(required = false)
    public void setEntryAlerts(com.churchgeniuspro.entryalert.EntryAlertService entryAlerts) { this.entryAlerts = entryAlerts; }

    // ── draft ─────────────────────────────────────────────────────────────────

    static final class Draft implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        Kind kind; String clientId; String username; Stage stage = Stage.ASK; Instant created;
        Long memberId; String contributorLabel; String guestName;
        Long fundId; String fundLabel;
        Long categoryId; String categoryLabel;
        Long methodId; String methodLabel;
        String amount; LocalDate date;
        String refNo; String note;
        boolean futureConfirmed, optionalAsked, reviewOffered, oneShot, duplicateConfirmed;
        /** 2026-11-09: the user chose to continue despite their own Income / Expense alerts (reset by any correction). */
        boolean alertsConfirmed;
        /** 2026-11-09: what was wrong with the last value given (said once, before the question for that field). */
        String problem; String problemField; String suggestedDate;
        /** The question just asked: contributor, guestConfirm, amount, fund, category, date, futureDate, method, refNo, note, duplicate. */
        String asked;
        List<String> choices; List<Long> choiceIds;
        String pendingGuest;
        /** Several funds / categories / methods matched — the names, to pick from. */
        String token;
        String noun() { return kind == Kind.INCOME ? "income" : "expense"; }
    }

    // ── vocabulary ────────────────────────────────────────────────────────────

    /** "add an income", "record a $50 donation", "create new expense", "enter an expense entry" … */
    static final Pattern START = Pattern.compile(
        "(?i)^\\s*(?:please\\s+|can you\\s+|could you\\s+|i\\s+(?:want|need|would like)\\s+to\\s+|i'?d like to\\s+|let'?s\\s+|help me\\s+)?"
        + "(?:create|add|record|enter|log|post|make|new)\\s+(?:an?\\s+|new\\s+|another\\s+|the\\s+|one\\s+)?"
        + "(?:\\$?\\d[\\d,]*(?:\\.\\d{1,2})?\\s*(?:dollars?\\s+)?)?(?:new\\s+)?"
        + "(income|expense|donation|offering|contribution|gift|tithe)s?\\b"
        // "add an expense category / purpose / fund / type …" is an Account Settings entry (AiGuidedSettingService), not a record
        + "(?!\\s+(?:categor|purpose|type|fund|account|source))");
    static final Pattern YES = Pattern.compile(
        "(?i)^\\s*(yes|yeah|yep|yup|sure|ok|okay|confirm|confirmed|correct|go ahead|do it|proceed|please do|save|save it|save (the )?(income|expense)|that'?s right)"
        + "([,.! ]+(yes|please|save( it)?|go ahead|do it|confirm|thanks?|thank you))*[.!]?\\s*$");
    static final Pattern NO = Pattern.compile("(?i)^\\s*(no|nope|no thanks|not now)[.!]?\\s*$");
    static final Pattern CANCEL = Pattern.compile(
        "(?i)^\\s*(cancel|cancel (it|that|this)|never ?mind|stop|forget it|discard|abort|don'?t save( it)?|do not save( it)?)[.!]?\\s*$");
    static final Pattern READY = Pattern.compile(
        "(?i)^\\s*(yes|yeah|yep|sure|ok|okay|no|nope|nothing|none|nothing else|that'?s (all|it|everything)|all good|looks good|ready|i'?m ready|"
        + "review|review (it|them|the details)|let me review|show me|show (me )?the details|go ahead|proceed|continue|done|next|skip|skip (them|it|both)|"
        + "skip and (review|proceed)|skip (them |it )?and proceed to review|proceed to review|no,? (skip|proceed)( (them|to review))?)"
        + "([,.! ]+(please|thanks?|thank you|ready|review|i'?m ready|let'?s review|go ahead|proceed( to review)?))*[.!]?\\s*$");
    static final Pattern ADD_REF = Pattern.compile("(?i)^\\s*(?:yes,?\\s+)?(?:add|enter|include)\\s+(?:a\\s+|the\\s+)?(?:ref(?:erence)?(?:\\s+(?:number|no\\.?|#))?|check\\s+(?:number|no\\.?|#))[.!]?\\s*$");
    static final Pattern ADD_NOTE = Pattern.compile("(?i)^\\s*(?:yes,?\\s+)?(?:add|enter|include|write)\\s+(?:a\\s+|the\\s+|some\\s+)?notes?[.!]?\\s*$");
    static final Pattern QUESTION = Pattern.compile(
        "(?i)^\\s*(what|how|who|when|which|why|whose|show|list|go to|open|take me|navigate|find|where|is|are|do|does|can|could)\\b|\\?\\s*$");
    static final Pattern GUEST_WORDS = Pattern.compile("(?i)\\b(anonymous|anon|loose\\s+offerings?|loose\\s+plate|plate\\s+offering)\\b");
    static final Pattern FROM = Pattern.compile(
        "(?i)\\bfrom\\s+(.+?)(?=\\s+(?:to|for|by|via|using|on|in|into|with|paid|through|toward|towards|dated|amount|of)\\b|[,;]|\\.(?:\\s|$)|$)");
    static final Pattern REF = Pattern.compile(
        "(?i)\\b(?:ref(?:erence)?(?:\\s*(?:number|no\\.?|#))?|check\\s*(?:number|no\\.?|#)|cheque\\s*(?:number|no\\.?|#)|chk\\s*#?|#)\\s*[:#]?\\s*([A-Za-z0-9][A-Za-z0-9\\-/]{0,39})\\b");
    static final Pattern NOTE = Pattern.compile("(?i)\\b(?:notes?|memo)\\s*(?:is|are|:|-|=)?\\s+(.+)$");
    static final Pattern AMOUNT_CUR = Pattern.compile("\\$\\s*(\\d[\\d,]*(?:\\.\\d+)?)(?!\\d|\\.\\d)");
    static final Pattern AMOUNT_WORD = Pattern.compile("(?i)\\b(\\d[\\d,]*(?:\\.\\d+)?)\\s*(?:dollars?|usd|bucks)\\b");
    static final Pattern AMOUNT_OF = Pattern.compile(
        "(?i)\\b(?:of|amount(?:\\s+(?:of|is|was))?|for|totall?ing|worth)\\s+(\\d[\\d,]*(?:\\.\\d+)?)(?!\\d|\\.\\d)(?!\\s*(?:am|pm|a\\.m|p\\.m|st|nd|rd|th|/|:|-\\d))");
    static final Pattern AMOUNT_LEAD = Pattern.compile(
        "(?i)^\\s*(?:create|add|record|enter|log|post|make|new)\\s+(?:an?\\s+|new\\s+)?(\\d[\\d,]*(?:\\.\\d{1,2})?)\\s+(?:dollars?\\s+)?(?:income|expense|donation|offering|contribution|gift|tithe)");
    static final Pattern AMOUNT_BARE = Pattern.compile("(?i)^\\s*(?:it'?s\\s+|it was\\s+|the amount is\\s+)?\\$?\\s*(\\d[\\d,]*(?:\\.\\d+)?)\\s*(?:dollars?|usd)?[.!]?\\s*$");
    static final Pattern CORRECT = Pattern.compile(
        "(?i)^\\s*(?:(?:no|oh|sorry|wait|actually)[,!.]?\\s+)?(?:please\\s+)?(?:change|set|make|update|edit|correct|switch)\\s+(?:the\\s+)?"
        + "(contributor|giver|donor|name|member|guest|from|fund|account|category|expense category|purpose|amount|total|date|day|method|payment method|"
        + "payment|reference number|reference|ref(?:\\s*(?:number|no\\.?|#))?|check number|notes?|memo)\\b\\s*(?:to|into|as|is|:|=)?\\s*(.*)$");
    static final Pattern ACTUALLY = Pattern.compile("(?i)^\\s*(?:actually|i meant|sorry,?\\s+i meant|no,?\\s+i meant|make it)[,:]?\\s+(.+)$");
    static final Pattern FILLER = Pattern.compile(
        "(?i)\\b(please|create|add|record|enter|log|post|make|new|an?|the|another|one|income|expense|donation|offering|contribution|gift|entry|record|transaction|"
        + "from|to|for|by|via|using|on|in|into|with|paid|through|towards?|of|it|was|is|and|fund|dated|today|i|want|need|would|like|can|could|you|let'?s|me|help|s)\\b");

    /** 2026-11-09 — the exact sentences of the spec (§4). */
    static final String AMOUNT_INVALID = "The amount is invalid. Please enter a valid numeric amount, such as $100.";
    static final String DATE_INVALID = "The date format is invalid. Please enter the date in MM/DD/YYYY format.";
    static final DateTimeFormatter US = DateTimeFormatter.ofPattern("MM/dd/yyyy");
    /** "income of Anson", "an expense of rent", "amount is fifty", "$abc" — an amount was meant but no number was given. */
    static final Pattern AMOUNT_WORDS = Pattern.compile(
        "(?i)\\b(?:(?:income|expense|donation|offering|contribution|gift|tithe)s?\\s+of|amount\\s+(?:of|is|was|=|:))\\s+(?!\\$?\\s*\\d)\\S"
        + "|\\$\\s*(?!\\d)\\p{L}");
    /** A negative amount ("-50", "$-50", "- $50") — refused, never read as 50. */
    static final Pattern AMOUNT_NEGATIVE = Pattern.compile("(?<![\\w$/.-])-\\s*\\$?\\s*\\d|\\$\\s*-\\s*\\d");
    /** "to the Mission Fund" — a fund named in the text (checked only when none of the church's funds matched). */
    static final Pattern FUND_NAMED = Pattern.compile(
        "(?i)\\b(?:to|for|into|in|on|from|under)\\s+(?:the\\s+|our\\s+)?((?:[\\p{L}\\p{N}'&-]+\\s+){1,4})fund\\b");

    // ── entry point ───────────────────────────────────────────────────────────

    public static boolean isStartRequest(String q) { return q != null && START.matcher(q).find(); }
    static Kind kindOf(String q) {
        Matcher m = START.matcher(q == null ? "" : q);
        if (!m.find()) return null;
        return m.group(1).toLowerCase(Locale.ROOT).startsWith("expense") ? Kind.EXPENSE : Kind.INCOME;
    }

    public static void clear(HttpSession s) { if (s != null) s.removeAttribute(SESSION_KEY); }
    public static boolean hasDraft(HttpSession s) { return s != null && s.getAttribute(SESSION_KEY) instanceof Draft; }

    /** Null = nothing to do with a guided income / expense (the search continues with its other routes). */
    public Map<String, Object> handle(HttpServletRequest req, String clientId, String question) {
        if (req == null || clientId == null || question == null) return null;
        HttpSession session = req.getSession(false);
        if (session == null) return null;
        String q = question.trim();
        if (q.isEmpty()) return null;
        LocalDate today = AppClock.today();
        Draft pending = pending(session, clientId);

        if (isStartRequest(q)) {
            Kind kind = kindOf(q);
            if (pending != null && pending.stage == Stage.CONFIRM && pending.token != null) actions.reject(req, clientId, pending.token);
            clear(session);
            Map<String, Object> gate = gate(req, clientId, kind);
            if (gate != null) return gate;
            AiAccess a = access.resolve(req, clientId);
            Draft d = new Draft();
            d.kind = kind; d.clientId = clientId; d.username = a.username(); d.created = Instant.now();
            boolean any = fill(d, q, today, true);
            if (!any && llmFallback && residual(q)) llmFill(d, q, today, null);
            d.oneShot = missing(d).isEmpty();
            return advance(req, session, d);
        }
        if (pending == null) return null;
        // a card confirmed (or cancelled) with its buttons → the conversation is over
        if (pending.stage == Stage.CONFIRM && !actions.isOpen(clientId, pending.token)) { clear(session); return null; }
        Map<String, Object> gate = gate(req, clientId, pending.kind);           // permission changed mid-conversation
        if (gate != null) { clear(session); return gate; }

        if (CANCEL.matcher(q).matches()) return cancel(req, session, pending);
        if (correct(pending, q, today)) {
            if (pending.stage != Stage.ASK) pending.reviewOffered = true;
            dropCard(req, pending);
            return advance(req, session, pending);
        }
        switch (pending.stage) {
            case CONFIRM -> {
                if (YES.matcher(q).matches()) {
                    String token = pending.token;
                    clear(session);
                    Map<String, Object> done = actions.confirm(req, clientId, token);
                    if (done != null) done.put("kind", pending.noun());
                    return done;
                }
                if (NO.matcher(q).matches()) return cancel(req, session, pending);
                return null;                      // another question: the card waits for its button or expires
            }
            case OPTIONAL -> {
                if (ADD_REF.matcher(q).matches()) return ask(session, pending, "refNo", "What is the reference number?", null);
                if (ADD_NOTE.matcher(q).matches()) return ask(session, pending, "note", "What should the notes say?", null);
                if (READY.matcher(q).matches()) { pending.reviewOffered = true; return advance(req, session, pending); }
                if ("refNo".equals(pending.asked) || "note".equals(pending.asked)) return optionalAnswer(req, session, pending, q);
                boolean more = fillOptional(pending, q);
                if (!more) return null;
                return advance(req, session, pending);
            }
            case REVIEW -> {
                if (ADD_REF.matcher(q).matches()) return ask(session, pending, "refNo", "What is the reference number?", null);
                if (ADD_NOTE.matcher(q).matches()) return ask(session, pending, "note", "What should the notes say?", null);
                if ("refNo".equals(pending.asked) || "note".equals(pending.asked)) return optionalAnswer(req, session, pending, q);
                if (READY.matcher(q).matches()) { pending.reviewOffered = true; return advance(req, session, pending); }
                boolean more = fill(pending, q, today, false) || fillOptional(pending, q);
                if (!more) return null;
                return advance(req, session, pending);
            }
            default -> { return answer(req, session, pending, q, today); }
        }
    }

    /** Permission + plan, before ANY lookup. Null = allowed. */
    private Map<String, Object> gate(HttpServletRequest req, String clientId, Kind kind) {
        String noun = kind == Kind.EXPENSE ? "expense" : "income";
        HttpSession s = req.getSession(false);
        AiAccess a = access.resolve(req, clientId);
        boolean allowed = !(a.member() || a.church() || a.kids() || AiAccessPolicy.isTemporaryAccess(s))
                && (kind == Kind.EXPENSE ? AiFinanceTools.mayCreateExpense(req) : AiFinanceTools.mayCreateIncome(req));
        if (!allowed) {
            Map<String, Object> m = base("guided.denied", "Your login doesn't have permission to add " + (kind == Kind.EXPENSE ? "expenses" : "income")
                + ", so I can't create one. Nothing was saved.", noun);
            m.put("denied", true);
            return m;
        }
        if (!access.feature(clientId, FEATURE)) return base("guided.unavailable", "Adding " + (kind == Kind.EXPENSE ? "an expense" : "income")
            + " from the AI Search bar is not part of your current plan. You can add it on the " + (kind == Kind.EXPENSE ? "Expense" : "Income") + " page.", noun);
        if (!actions.isEnabled()) return base("guided.unavailable", "AI actions are switched off on this server. You can add it on the "
            + (kind == Kind.EXPENSE ? "Expense" : "Income") + " page.", noun);
        return null;
    }

    // ── answers to the question just asked ────────────────────────────────────

    private Map<String, Object> answer(HttpServletRequest req, HttpSession session, Draft d, String q, LocalDate today) {
        String asked = d.asked == null ? "" : d.asked;
        String t = q.replaceAll("[.!]+$", "").trim();
        if (QUESTION.matcher(q).find() && !asked.matches("guestConfirm|futureDate|duplicate|alerts")) return null;   // another question — the draft waits
        switch (asked) {
            case "guestConfirm" -> {
                if (YES.matcher(q).matches() || t.toLowerCase(Locale.ROOT).startsWith("yes")) {
                    setGuest(d, d.pendingGuest); d.pendingGuest = null; return advance(req, session, d);
                }
                if (NO.matcher(q).matches() || t.toLowerCase(Locale.ROOT).contains("another")) {
                    d.pendingGuest = null; return ask(session, d, "contributor", contributorQuestion(), List.of("Anonymous", "Loose Offering"));
                }
                d.pendingGuest = null;
                if (contributor(d, t)) return advance(req, session, d);
            }
            case "alerts" -> {
                // 2026-11-09: the login's own alerts were shown — continue only on an explicit yes
                if (YES.matcher(q).matches() || t.toLowerCase(Locale.ROOT).matches("^(yes|continue|keep|save anyway|review|go ahead|proceed).*")) {
                    d.alertsConfirmed = true; return advance(req, session, d);
                }
                if (NO.matcher(q).matches()) return cancel(req, session, d);
                return null;
            }
            case "futureDate", "duplicate" -> {
                boolean keep = YES.matcher(q).matches() || t.toLowerCase(Locale.ROOT).matches("^(yes|keep|save anyway|use it).*");
                if ("duplicate".equals(asked)) {
                    if (keep) { d.duplicateConfirmed = true; return advance(req, session, d); }
                    if (NO.matcher(q).matches()) return cancel(req, session, d);
                } else {
                    if (keep) { d.futureConfirmed = true; return advance(req, session, d); }
                    if (NO.matcher(q).matches() || t.toLowerCase(Locale.ROOT).contains("different")) { d.date = null; return advance(req, session, d); }
                    LocalDate before = d.date; d.date = null;
                    if (fill(d, q, today, false) && d.date != null) return advance(req, session, d);
                    d.date = before;
                }
                return null;
            }
            default -> { }
        }
        // a pick from the offered choices first ("2", "Building Fund", "Mark Lee")
        if (d.choices != null && !d.choices.isEmpty()) {
            String picked = AiActionService.pick(t, d.choices);
            if (picked != null && applyChoice(d, asked, picked)) return advance(req, session, d);
        }
        boolean filled = fill(d, q, today, false);
        if ("contributor".equals(asked) && d.kind == Kind.INCOME && d.memberId == null && d.guestName == null && d.pendingGuest == null && d.choiceIds == null) {
            // the answer is the name (anything else the reply carried — "$50", "today" — was taken above)
            String name = t.replaceAll("\\$\\s*\\d[\\d,]*(?:\\.\\d{1,2})?", " ").replaceAll("(?i)\\b(today|yesterday)\\b", " ").replaceAll("[,;]+", " ").replaceAll("\\s+", " ").trim();
            filled |= contributor(d, name);
        }
        if (!filled && llmFallback) filled = llmFill(d, q, today, asked);
        // 2026-11-09: an answer to "What is the amount?" with no valid number is said to be invalid — never guessed
        // (the model may still read names from the reply, but never the amount)
        if ("amount".equals(asked) && d.amount == null && d.problem == null) { d.problem = AMOUNT_INVALID; d.problemField = "amount"; filled = true; }
        if (!filled) {
            Map<String, Object> m = advance(req, session, d);
            m.put("answer", "I couldn't match “" + cap(t, 60) + "”. " + m.get("answer"));
            return m;
        }
        return advance(req, session, d);
    }

    private Map<String, Object> optionalAnswer(HttpServletRequest req, HttpSession session, Draft d, String q) {
        String t = q.replaceAll("[.!]+$", "").trim();
        if (!READY.matcher(q).matches() && !NO.matcher(q).matches()) {
            if ("refNo".equals(d.asked)) d.refNo = cap(t.replaceAll("(?i)^(?:it'?s\\s+|ref(?:erence)?(?:\\s+(?:number|no\\.?))?\\s*[:#]?\\s*|#)", "").trim(), 100);
            else d.note = cap(t.replaceAll("(?i)^(?:notes?|memo)\\s*(?:is|are|:|-)?\\s*", "").trim(), 2000);
        }
        d.asked = null;
        d.optionalAsked = true;
        return advance(req, session, d);
    }

    // ── corrections ───────────────────────────────────────────────────────────

    boolean correct(Draft d, String text, LocalDate today) {
        Matcher c = CORRECT.matcher(text);
        String field, value;
        if (c.matches()) { field = c.group(1).toLowerCase(Locale.ROOT); value = c.group(2).replaceAll("[.!]+$", "").trim(); }
        else {
            Matcher ac = ACTUALLY.matcher(text);
            if (!ac.matches()) return false;
            value = ac.group(1).replaceAll("[.!]+$", "").trim();
            if (amountOf(value, true) != null) field = "amount";
            else if (parseDate(value, today) != null) field = "date";
            else if (matchNames(value, methodList(d)).size() == 1) field = "method";
            else if (matchNames(value, fundList(d)).size() == 1) field = "fund";
            else if (d.kind == Kind.EXPENSE && matchNames(value, lookup.expenseCategories(d.clientId)).size() == 1) field = "category";
            else return false;
        }
        d.duplicateConfirmed = false;
        d.alertsConfirmed = false;
        if (field.matches("contributor|giver|donor|name|member|guest|from")) {
            if (d.kind != Kind.INCOME) return false;
            d.memberId = null; d.guestName = null; d.contributorLabel = null; d.pendingGuest = null;
            d.asked = "contributor"; d.choices = null; d.choiceIds = null;
            if (!value.isEmpty()) contributor(d, value);
        } else if (field.matches("fund|account")) {
            d.fundId = null; d.fundLabel = null; d.asked = "fund";
            if (!value.isEmpty()) pickFund(d, value);
        } else if (field.matches("category|expense category|purpose")) {
            if (d.kind != Kind.EXPENSE) return false;
            d.categoryId = null; d.categoryLabel = null; d.asked = "category";
            if (!value.isEmpty()) pickCategory(d, value);
        } else if (field.matches("amount|total")) {
            d.amount = null; d.asked = "amount";
            if (!value.isEmpty()) {
                BigDecimal a = amountOf(value, true);
                if (a != null) d.amount = a.toPlainString(); else { d.problem = AMOUNT_INVALID; d.problemField = "amount"; }
            }
        } else if (field.matches("date|day")) {
            d.date = null; d.futureConfirmed = false; d.asked = "date";
            if (!value.isEmpty()) {
                String[] bad = badDate(value, today);
                if (bad != null) setDateProblem(d, bad);
                else { Parsed p = parseDate(value, today); if (p != null) d.date = p.date; }
            }
        } else if (field.matches("method|payment method|payment")) {
            d.methodId = null; d.methodLabel = null; d.asked = "method";
            if (!value.isEmpty()) pickMethod(d, value);
        } else if (field.matches("notes?|memo")) {
            d.note = value.isEmpty() || NO.matcher(value).matches() || value.equalsIgnoreCase("none") ? null : cap(value, 2000);
            d.optionalAsked = true; d.asked = null;
        } else {                                                                      // reference number
            d.refNo = value.isEmpty() || NO.matcher(value).matches() || value.equalsIgnoreCase("none") ? null : cap(value.replaceAll("^#", ""), 100);
            d.optionalAsked = true; d.asked = null;
        }
        return true;
    }

    // ── filling ───────────────────────────────────────────────────────────────

    /** Takes everything the rules can read from the text. True when any field was set. */
    boolean fill(Draft d, String text, LocalDate today, boolean initial) {
        boolean any = false;
        String rest = " " + text.replaceAll("(?i)\\bcheque\\b", "check") + " ";
        String asked = d.asked == null ? "" : d.asked;
        // notes and reference numbers first (they may hold numbers and names)
        Matcher n = NOTE.matcher(rest);
        if (n.find() && d.note == null) { d.note = cap(n.group(1).trim().replaceAll("[.!]+$", ""), 2000); rest = rest.substring(0, n.start()) + " "; any = true; }
        Matcher r = REF.matcher(rest);
        if (r.find() && d.refNo == null && r.group(1).matches(".*\\d.*")) {
            d.refNo = cap(r.group(1), 100);
            boolean byCheck = r.group().toLowerCase(Locale.ROOT).startsWith("check") || r.group().toLowerCase(Locale.ROOT).startsWith("chk");
            rest = cut(rest, r) + (byCheck ? " check " : "");                 // "check #1234" also says how it was paid
            any = true;
        }
        // date
        if (d.date == null) {
            String[] bad = badDate(rest, today);
            if (bad != null) { setDateProblem(d, bad); rest = rest.replace(bad[0], " "); any = true; }   // never read as another date
            else {
                Parsed p = parseDate(rest, today);
                if (p != null) { d.date = p.date; rest = p.rest; any = true; }
            }
        }
        // amount
        if (d.amount == null) {
            BigDecimal a = null; Matcher m;
            boolean seen = false;
            if (AMOUNT_NEGATIVE.matcher(rest).find()) seen = true;           // "-50" is invalid, never read as 50
            else if ((m = AMOUNT_CUR.matcher(rest)).find() || (m = AMOUNT_WORD.matcher(rest)).find() || (m = AMOUNT_LEAD.matcher(text)).find()
                    || (m = AMOUNT_OF.matcher(rest)).find()) {
                a = number(m.group(1)); seen = true;
                if (m.pattern() != AMOUNT_LEAD) rest = cut(rest, m);
            } else if ("amount".equals(asked) && (m = AMOUNT_BARE.matcher(rest)).matches()) {
                a = number(m.group(1)); rest = " "; seen = true;
            }
            if (a == null && !seen && AMOUNT_WORDS.matcher(rest).find()) seen = true;   // "income of Anson", "$abc" — never a guess
            if (a != null) { d.amount = a.toPlainString(); any = true; }
            else if (seen) { d.problem = AMOUNT_INVALID; d.problemField = "amount"; any = true; }
        }
        // method, funds, category (the church's own names, longest first, whole words)
        if (d.methodId == null) { String hit = pickMethod(d, rest); if (hit != null) { rest = removeWords(rest, hit); any = true; } }
        if (d.kind == Kind.EXPENSE && d.categoryId == null) { String hit = pickCategory(d, rest); if (hit != null) { rest = removeWords(rest, hit); any = true; } }
        if (d.fundId == null) {
            String hit = pickFund(d, rest);
            if (hit != null) { rest = removeWords(rest, hit); any = true; }
            else if (d.problem == null) {
                // 2026-11-09: a fund was named but it is not one of this church's — said, never guessed
                Matcher fm = FUND_NAMED.matcher(rest);
                if (fm.find()) {
                    String named = fm.group(1).trim();
                    if (!named.matches("(?i)(?:the|a|an|this|that|which|any|some|my|our|its|income|expense|expenses|same|other|another|one)")) {
                        d.problem = "I couldn't find a fund called “" + cap(named, 60) + " Fund” in this church's funds.";
                        d.problemField = "fund"; any = true;
                    }
                }
            }
        }
        // contributor
        if (d.kind == Kind.INCOME && d.memberId == null && d.guestName == null && d.pendingGuest == null) {
            Matcher g = GUEST_WORDS.matcher(rest);
            Matcher f = FROM.matcher(rest);
            if (g.find()) { setGuest(d, g.group(1).toLowerCase(Locale.ROOT).startsWith("anon") ? "Anonymous" : "Loose Offering"); any = true; }
            else if (f.find()) {
                String who = f.group(1).trim();
                if (!who.isEmpty() && who.matches(".*\\p{L}.*") && contributor(d, who)) any = true;
            }
        }
        return any;
    }

    /** Ref No / Notes given as "ref 1234" / "note: …" while the optional or review question is open. */
    boolean fillOptional(Draft d, String text) {
        boolean any = false;
        Matcher n = NOTE.matcher(text);
        String rest = text;
        if (n.find()) { d.note = cap(n.group(1).trim().replaceAll("[.!]+$", ""), 2000); rest = text.substring(0, n.start()); any = true; }
        Matcher r = REF.matcher(rest);
        if (r.find() && r.group(1).matches(".*\\d.*")) { d.refNo = cap(r.group(1), 100); any = true; }
        if (any) d.optionalAsked = true;
        return any;
    }

    /** A contributor name: guest words, "guest …", else this church's members (one → set, several → asked, none → "record as a guest?"). */
    boolean contributor(Draft d, String raw) {
        String who = raw.replaceAll("(?i)^\\s*(?:it'?s\\s+|it was\\s+|from\\s+|the\\s+)", "").replaceAll("[.!,;]+$", "").trim();
        if (who.isEmpty() || !who.matches(".*\\p{L}.*")) return false;
        Matcher g = GUEST_WORDS.matcher(who);
        if (g.find() && who.length() - g.group(1).length() < 4) { setGuest(d, g.group(1).toLowerCase(Locale.ROOT).startsWith("anon") ? "Anonymous" : "Loose Offering"); return true; }
        boolean guestMarked = who.matches("(?i)^(?:a\\s+)?guest\\b.*") || who.matches("(?i).*\\(guest\\)$");
        who = who.replaceAll("(?i)^(?:a\\s+)?guest(?:\\s+named|\\s+called)?\\s*[:,-]?\\s*", "").replaceAll("(?i)\\s*\\(guest\\)$", "").trim();
        if (who.isEmpty()) return false;
        who = cap(who, 120);
        if (guestMarked) { setGuest(d, who); return true; }
        List<Hit> hits = people.members(d.clientId, who);
        if (hits.size() == 1) {
            d.memberId = hits.get(0).id(); d.contributorLabel = hits.get(0).label(); d.guestName = null; d.choices = null; d.choiceIds = null;
            return true;
        }
        if (hits.size() > 1) {
            d.asked = "contributor"; d.choices = new ArrayList<>(); d.choiceIds = new ArrayList<>();
            Map<String, Integer> seen = new HashMap<>();
            for (Hit h : hits) {
                String label = h.label();
                int k = seen.merge(label.toLowerCase(Locale.ROOT), 1, Integer::sum);
                if (k > 1) label = label + " (" + k + ")";
                d.choices.add(label); d.choiceIds.add(h.id());
            }
            d.pendingGuest = null;
            return true;
        }
        d.pendingGuest = who; d.asked = "guestConfirm";
        return true;
    }

    private void setGuest(Draft d, String name) {
        d.memberId = null; d.guestName = cap(name, 300); d.contributorLabel = d.guestName + " (guest)"; d.choices = null; d.choiceIds = null;
    }

    private boolean applyChoice(Draft d, String asked, String picked) {
        int i = d.choices.indexOf(picked);
        switch (asked) {
            case "contributor" -> {
                if (d.choiceIds != null && i >= 0 && i < d.choiceIds.size()) {
                    Hit h = people.memberById(d.clientId, d.choiceIds.get(i));
                    if (h == null) return false;
                    d.memberId = h.id(); d.contributorLabel = h.label(); d.guestName = null;
                } else if (picked.equalsIgnoreCase("Anonymous") || picked.equalsIgnoreCase("Loose Offering")) setGuest(d, picked);
                else return false;
            }
            case "fund" -> { if (pickFund(d, picked) == null) return false; }
            case "category" -> { if (pickCategory(d, picked) == null) return false; }
            case "method" -> { if (pickMethod(d, picked) == null) return false; }
            case "date" -> { if (!picked.equalsIgnoreCase("Today")) return false; d.date = AppClock.today(); }
            default -> { return false; }
        }
        d.choices = null; d.choiceIds = null;
        return true;
    }

    List<Hit> fundList(Draft d) { return d.kind == Kind.EXPENSE ? lookup.expenseFunds(d.clientId) : lookup.incomeFunds(d.clientId); }
    List<Hit> methodList(Draft d) { return lookup.methods(d.clientId); }

    /** The matched name (to cut from the text), or null. Several distinct matches → the question lists them. */
    private String pickFund(Draft d, String text) {
        List<Hit> hits = matchNames(text, fundList(d));
        if (hits.size() == 1) { d.fundId = hits.get(0).id(); d.fundLabel = hits.get(0).label(); return hits.get(0).label(); }
        if (hits.size() > 1 && "fund".equals(d.asked)) d.choices = labels(hits);
        return null;
    }
    private String pickCategory(Draft d, String text) {
        List<Hit> hits = matchNames(text, lookup.expenseCategories(d.clientId));
        if (hits.size() == 1) { d.categoryId = hits.get(0).id(); d.categoryLabel = hits.get(0).label(); return hits.get(0).label(); }
        if (hits.size() > 1 && "category".equals(d.asked)) d.choices = labels(hits);
        return null;
    }
    private String pickMethod(Draft d, String text) {
        List<Hit> hits = matchNames(text, methodList(d));
        if (hits.size() == 1) { d.methodId = hits.get(0).id(); d.methodLabel = hits.get(0).label(); return hits.get(0).label(); }
        if (hits.size() > 1 && "method".equals(d.asked)) d.choices = labels(hits);
        return null;
    }

    /** Names that appear in the text as whole words (case-insensitive); a name inside a longer matching name collapses to the longer one. */
    static List<Hit> matchNames(String text, List<Hit> names) {
        String lc = " " + norm(text) + " ";
        List<Hit> hits = new ArrayList<>();
        for (Hit h : names) {
            String n = norm(h.label());
            if (n.isEmpty()) continue;
            String bare = n.replaceAll("\\b(fund|funds|account)\\b", " ").replaceAll("\\s+", " ").trim();
            if (lc.contains(" " + n + " ") || (!bare.isEmpty() && bare.length() >= 3 && lc.contains(" " + bare + " "))) hits.add(h);
        }
        if (hits.size() > 1) {
            hits.sort((x, y) -> y.label().length() - x.label().length());
            List<Hit> keep = new ArrayList<>();
            for (Hit h : hits) {
                boolean inside = false;
                for (Hit k : keep) if (norm(k.label()).contains(norm(h.label()))) { inside = true; break; }
                if (!inside) keep.add(h);
            }
            hits = keep;
            // the exact reply ("General") wins over a longer name that merely contains it
            for (Hit h : hits) if (norm(h.label()).equals(norm(text))) return List.of(h);
        }
        return hits;
    }
    static String norm(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}&' ]", " ").replaceAll("\\s+", " ").trim(); }
    static List<String> labels(List<Hit> hits) { List<String> out = new ArrayList<>(); for (Hit h : hits) out.add(h.label()); return out; }
    static String removeWords(String text, String name) {
        String out = text.replaceAll("(?i)" + Pattern.quote(name), " ");
        String bare = name.replaceAll("(?i)\\b(fund|funds|account)\\b", " ").replaceAll("\\s+", " ").trim();
        if (!bare.isEmpty() && bare.length() >= 3) out = out.replaceAll("(?i)\\b" + Pattern.quote(bare) + "\\b", " ");
        return out;
    }

    // ── 2026-11-09: invalid values (spec §4) ──────────────────────────────────

    /**
     * A date written with slashes (or as YYYY-MM-DD) that is not a real MM/DD/YYYY date — "30/11/2026", "13/45",
     * "02/30/2026". Returns {the text as written, the MM/DD/YYYY reading with day and month swapped when THAT is a
     * real date (else null)}; null when every such date in the text is valid. Never fixes it silently.
     */
    public static String[] badDate(String text, LocalDate today) {
        Matcher m = SLASH.matcher(text);
        while (m.find()) {
            int a = i(m.group(1)), b = i(m.group(2));
            int y = m.group(3) == null ? -1 : i(m.group(3)); if (y >= 0 && y < 100) y += 2000;
            if (md(a, b, y, today) != null) continue;
            LocalDate swapped = md(b, a, y, today);
            return new String[]{m.group(), swapped == null ? null : US.format(swapped)};
        }
        Matcher iso = ISO.matcher(text);
        while (iso.find()) {
            if (safe(i(iso.group(1)), i(iso.group(2)), i(iso.group(3))) == null) return new String[]{iso.group(), null};
        }
        return null;
    }

    private static void setDateProblem(Draft d, String[] bad) {
        d.problem = DATE_INVALID + (bad[1] != null ? " Did you mean " + bad[1] + "?" : "");
        d.problemField = "date"; d.suggestedDate = bad[1];
    }

    // ── the next step ─────────────────────────────────────────────────────────

    static List<String> missing(Draft d) {
        List<String> out = new ArrayList<>();
        if (d.kind == Kind.INCOME && d.memberId == null && d.guestName == null) out.add("contributor");
        if (d.kind == Kind.EXPENSE && d.categoryId == null) out.add("category");
        if (d.amount == null) out.add("amount");
        if (d.fundId == null) out.add("fund");
        if (d.date == null) out.add("date");
        if (d.methodId == null) out.add("method");
        return out;
    }

    private String contributorQuestion() {
        return "Who is the income from? Give a member's name, or a guest name (Anonymous and Loose Offering are fine).";
    }

    /**
     * The next step. 2026-11-09: when the last value given was invalid (date, amount, unknown fund) that field is
     * asked first and the question starts with what was wrong; the rest of the request is kept.
     */
    Map<String, Object> advance(HttpServletRequest req, HttpSession session, Draft d) {
        String problem = d.problem, field = d.problemField, suggested = d.suggestedDate;
        d.problem = null; d.problemField = null; d.suggestedDate = null;
        if (problem != null && field != null && !missing(d).contains(field)) problem = null;    // fixed meanwhile
        Map<String, Object> m = advanceInner(req, session, d, problem == null ? null : field);
        if (problem != null && field.equals(m.get("asking"))) {
            String question = String.valueOf(m.get("answer"));
            m.put("answer", field.equals("fund") ? problem + " " + question : problem);
            m.put("invalid", field);
            if (field.equals("date")) {
                List<String> ch = new ArrayList<>();
                if (suggested != null) ch.add(suggested);
                ch.add("Today");
                m.put("choices", ch);
                d.choices = new ArrayList<>(ch);
                session.setAttribute(SESSION_KEY, d);
            }
        }
        return m;
    }

    private Map<String, Object> advanceInner(HttpServletRequest req, HttpSession session, Draft d, String first) {
        LocalDate today = AppClock.today();
        d.created = Instant.now();
        // an open contributor question (several members / record as a guest?) comes first
        if (d.kind == Kind.INCOME && d.memberId == null && d.guestName == null) {
            if (d.pendingGuest != null) {
                return ask(session, d, "guestConfirm", "I couldn't find a member called “" + d.pendingGuest + "”. Should I record it from a guest named “"
                    + d.pendingGuest + "”?", List.of("Yes, record as a guest", "No, choose another name"));
            }
            if (d.choiceIds != null && !d.choiceIds.isEmpty()) {
                return ask(session, d, "contributor", "Several members match — which one is it?", d.choices);
            }
        }
        List<String> miss = missing(d);
        if (first != null && miss.remove(first)) miss.add(0, first);
        if (!miss.isEmpty()) {
            String f = miss.get(0);
            switch (f) {
                case "contributor" -> { return ask(session, d, f, contributorQuestion(), List.of("Anonymous", "Loose Offering")); }
                case "category" -> {
                    List<String> names = labels(lookup.expenseCategories(d.clientId));
                    if (names.isEmpty()) return stop(session, d, "There are no expense categories yet — add one on the Expense page first.");
                    List<String> ch = "category".equals(d.asked) && d.choices != null ? d.choices : first(names, 20);
                    return ask(session, d, f, "Which expense category is it? " + String.join(", ", ch) + (names.size() > ch.size() ? " …" : "") + ".", ch);
                }
                case "amount" -> { return ask(session, d, f, "What is the amount?", null); }
                case "fund" -> {
                    List<String> names = labels(fundList(d));
                    if (names.isEmpty()) return stop(session, d, "There are no " + (d.kind == Kind.EXPENSE ? "expense" : "income") + " funds yet — add one on the "
                        + (d.kind == Kind.EXPENSE ? "Expense" : "Income") + " page first.");
                    List<String> ch = "fund".equals(d.asked) && d.choices != null ? d.choices : first(names, 20);
                    return ask(session, d, f, (d.kind == Kind.EXPENSE ? "Which fund is it paid from? " : "Which fund is it for? ") + String.join(", ", ch)
                        + (names.size() > ch.size() ? " …" : "") + ".", ch);
                }
                case "date" -> { return ask(session, d, f, "What date should I record? (for example today, yesterday or Nov 3)", List.of("Today")); }
                default -> {
                    List<String> names = labels(methodList(d));
                    if (names.isEmpty()) return stop(session, d, "There are no payment methods yet — add one on the " + (d.kind == Kind.EXPENSE ? "Expense" : "Income") + " page first.");
                    List<String> ch = "method".equals(d.asked) && d.choices != null ? d.choices : first(names, 20);
                    return ask(session, d, "method", "How was it paid? " + String.join(", ", ch) + ".", ch);
                }
            }
        }
        BigDecimal amt = new BigDecimal(d.amount);
        BigDecimal max = d.kind == Kind.EXPENSE ? com.churchgeniuspro.controller.ExpenseController.AMOUNT_MAX : com.churchgeniuspro.controller.IncomeController.AMOUNT_MAX;
        if (amt.signum() <= 0 || amt.compareTo(max) > 0) {
            d.amount = null;
            return ask(session, d, "amount", "The amount must be greater than zero and at most " + AiFinanceTools.money(max) + ". What is the amount?", null);
        }
        if (d.date.isAfter(today) && !d.futureConfirmed) {
            return ask(session, d, "futureDate", D.format(d.date) + " is in the future. Do you want to record the " + d.noun() + " on that date anyway, or use a different date?",
                List.of("Yes, keep " + D.format(d.date), "Use a different date"));
        }
        // 2026-11-09: the login's own Income / Expense alerts — the same check the Income / Expense pages make;
        // asked as soon as the date and amount are known (before the optional fields and the review)
        if (!d.alertsConfirmed && entryAlerts != null) {
            List<com.churchgeniuspro.entryalert.EntryAlertService.Alert> alerts = entryAlerts.check(
                com.churchgeniuspro.entryalert.EntryAlertService.owner(req),
                d.kind == Kind.INCOME ? com.churchgeniuspro.entryalert.EntryAlertService.Kind.INCOME : com.churchgeniuspro.entryalert.EntryAlertService.Kind.EXPENSE,
                d.date, amt, today);
            if (!alerts.isEmpty()) {
                StringBuilder b = new StringBuilder("Please check this " + d.noun() + " before saving — " + (alerts.size() == 1 ? "one of your alerts was" : alerts.size() + " of your alerts were") + " triggered:");
                for (var a : alerts) b.append("\n• ").append(a.message());
                b.append("\nDo you want to continue to the review, or change it?");
                List<String> ch = new ArrayList<>(List.of("Yes, continue to review"));
                boolean dateAlert = alerts.stream().anyMatch(a -> a.code().endsWith("DATE"));
                boolean amountAlert = alerts.stream().anyMatch(a -> a.code().startsWith("AMOUNT"));
                if (dateAlert) ch.add("Change the date");
                if (amountAlert) ch.add("Change the amount");
                ch.add("Cancel");
                Map<String, Object> m = ask(session, d, "alerts", b.toString(), ch);
                m.put("alerts", alerts.stream().map(com.churchgeniuspro.entryalert.EntryAlertService.Alert::toMap).toList());
                m.put("question", "Please check this " + d.noun() + " before saving. Do you want to continue to the review, or change it?");
                return m;
            }
        }
        if (!d.optionalAsked && d.refNo == null && d.note == null) {
            d.optionalAsked = true;
            d.stage = Stage.OPTIONAL; d.asked = null; d.choices = null;
            session.setAttribute(SESSION_KEY, d);
            Map<String, Object> m = base("guided.ask", "You haven't entered the optional Reference Number (useful for check payments) or Notes. "
                + "Would you like to add either of these, or should I skip them and proceed to review?", d.noun());
            m.put("asking", "optional"); m.put("pending", true); m.put("missing", List.of());
            m.put("choices", List.of("Add a reference number", "Add notes", "Skip and review"));
            return m;
        }
        if (!d.reviewOffered && !d.oneShot) {
            d.reviewOffered = true;
            d.stage = Stage.REVIEW; d.asked = null; d.choices = null;
            session.setAttribute(SESSION_KEY, d);
            Map<String, Object> m = base("guided.ask", "Are you missing anything, or are you ready to review the " + d.noun() + " details?", d.noun());
            m.put("asking", "review"); m.put("pending", true); m.put("missing", List.of());
            m.put("choices", List.of("Review the details", "Cancel"));
            return m;
        }
        if (!d.duplicateConfirmed) {
            int dup = d.kind == Kind.INCOME
                ? lookup.incomeDuplicates(d.clientId, d.date, amt, d.fundId, d.memberId, d.guestName)
                : lookup.expenseDuplicates(d.clientId, d.date, amt, d.categoryId, d.fundId);
            if (dup > 0) {
                String what = d.kind == Kind.INCOME
                    ? "An income of " + AiFinanceTools.money(amt) + " from " + d.contributorLabel + " to " + d.fundLabel
                    : "An expense of " + AiFinanceTools.money(amt) + " for " + d.categoryLabel + " from " + d.fundLabel;
                return ask(session, d, "duplicate", what + " on " + D.format(d.date) + " is already recorded. Save another one anyway?", List.of("Save anyway", "Cancel"));
            }
        }
        return card(req, session, d);
    }

    private Map<String, Object> card(HttpServletRequest req, HttpSession session, Draft d) {
        AiAccess a = access.resolve(req, d.clientId);
        Map<String, Object> raw = new LinkedHashMap<>();
        if (d.kind == Kind.INCOME) {
            if (d.memberId != null) raw.put("memberId", d.memberId); else raw.put("guestName", d.guestName);
        } else raw.put("categoryId", d.categoryId);
        raw.put("fundId", d.fundId); raw.put("amount", d.amount); raw.put("date", d.date.toString()); raw.put("methodId", d.methodId);
        if (d.refNo != null) raw.put("refNo", d.refNo);
        if (d.note != null) raw.put("note", d.note);
        String tool = d.kind == Kind.INCOME ? "create_income" : "create_expense";
        Map<String, Object> p = actions.proposeGuided(req, a, d.clientId, tool, raw);
        if (p.get("token") == null) {
            clear(session);
            Map<String, Object> m = base(Boolean.TRUE.equals(p.get("denied")) ? "guided.denied" : "guided.refused", p.get("refusal") + " Nothing was saved.", d.noun());
            if (Boolean.TRUE.equals(p.get("denied"))) m.put("denied", true);
            return m;
        }
        d.token = String.valueOf(p.get("token"));
        d.stage = Stage.CONFIRM; d.asked = null; d.choices = null; d.reviewOffered = true; d.created = Instant.now();
        session.setAttribute(SESSION_KEY, d);
        String label = d.kind == Kind.INCOME ? "Save income" : "Save expense";
        Map<String, Object> m = base("guided.confirm", "Please review the " + d.noun() + " details. Nothing is saved until you press "
            + label + " (or say “yes”). Press Edit on any row to change it.", d.noun());
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("token", d.token); c.put("proposalId", p.get("proposalId")); c.put("label", label);
        c.put("endpoint", "/api/ai/actions/confirm"); c.put("cancelEndpoint", "/api/ai/actions/reject");
        List<Map<String, Object>> fields = new ArrayList<>();
        if (d.kind == Kind.INCOME) {
            fields.add(field("contributor", "Contributor", d.contributorLabel, true, "Change contributor to "));
            fields.add(field("fund", "Fund", d.fundLabel, true, "Change fund to "));
        } else {
            fields.add(field("category", "Expense category", d.categoryLabel, true, "Change category to "));
            fields.add(field("fund", "Fund", d.fundLabel, true, "Change fund to "));
        }
        fields.add(field("amount", "Amount", AiFinanceTools.money(new BigDecimal(d.amount)), true, "Change amount to "));
        fields.add(field("date", "Date", D.format(d.date), true, "Change date to "));
        fields.add(field("method", "Method", d.methodLabel, true, "Change method to "));
        fields.add(field("refNo", "Reference number", d.refNo, false, "Change reference number to "));
        fields.add(field("note", "Notes", d.note, false, "Change notes to "));
        Map<String, String> summary = new LinkedHashMap<>();
        for (Map<String, Object> f : fields)
            summary.put(String.valueOf(f.get("label")), f.get("value") == null ? "Not entered (optional)" : String.valueOf(f.get("value")));
        c.put("summary", summary);
        // 2026-11-09: Edit on a field with choices re-asks it with the church's choices (the correction with no value)
        for (Map<String, Object> f : fields) {
            switch (String.valueOf(f.get("key"))) {
                case "fund" -> f.put("ask", "Change the fund");
                case "category" -> f.put("ask", "Change the expense category");
                case "method" -> f.put("ask", "Change the payment method");
                default -> { }
            }
        }
        c.put("fields", fields);
        m.put("confirm", c);
        m.put("pending", true);
        return m;
    }

    private Map<String, Object> ask(HttpSession session, Draft d, String field, String question, List<String> choices) {
        boolean memberChoices = "contributor".equals(field) && choices != null && choices == d.choices && d.choiceIds != null;
        if (!memberChoices) d.choiceIds = null;
        d.asked = field; d.choices = choices == null ? null : new ArrayList<>(choices);
        // the reference-number / notes questions belong to the optional (or review) step; every other one is a required field
        if (!field.equals("refNo") && !field.equals("note")) d.stage = Stage.ASK;
        d.created = Instant.now();
        session.setAttribute(SESSION_KEY, d);
        Map<String, Object> m = base("guided.ask", question, d.noun());
        m.put("asking", switch (field) { case "guestConfirm" -> "contributor"; case "futureDate" -> "date"; default -> field; });
        m.put("missing", missing(d));
        m.put("pending", true);
        if (choices != null && !choices.isEmpty()) m.put("choices", choices);
        return m;
    }

    private Map<String, Object> stop(HttpSession session, Draft d, String why) {
        clear(session);
        return base("guided.refused", why + " Nothing was saved.", d.noun());
    }

    private Map<String, Object> cancel(HttpServletRequest req, HttpSession session, Draft d) {
        dropCard(req, d);
        clear(session);
        return base("guided.cancelled", "Okay — I have not saved the " + d.noun() + ".", d.noun());
    }

    /** A correction or cancel while the card is open: the old card's token is withdrawn. */
    private void dropCard(HttpServletRequest req, Draft d) {
        if (d.token != null) { try { actions.reject(req, d.clientId, d.token); } catch (RuntimeException ignore) { } }
        d.token = null;
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

    // ── AI fallback (decision 1A) ─────────────────────────────────────────────

    /** True when the start request says more than "add an income" — something the rules may have missed. */
    static boolean residual(String q) {
        String r = FILLER.matcher(q.toLowerCase(Locale.ROOT)).replaceAll(" ").replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        return r.length() >= 3;
    }

    /**
     * The model reads the reply only when the rules could not. It gets the reply text, the field names and the
     * church's fund / category / method NAMES (never member names). Every value is checked against those lists.
     */
    boolean llmFill(Draft d, String text, LocalDate today, String asked) {
        if (openAi == null || !openAi.isEnabled()) return false;
        try {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("record", d.noun());
            ctx.put("fields", d.kind == Kind.INCOME ? List.of("contributor", "amount", "fund", "date", "method", "refNo", "note")
                                                    : List.of("category", "amount", "fund", "date", "method", "refNo", "note"));
            ctx.put("funds", first(labels(fundList(d)), 60));
            if (d.kind == Kind.EXPENSE) ctx.put("categories", first(labels(lookup.expenseCategories(d.clientId)), 60));
            ctx.put("methods", first(labels(methodList(d)), 30));
            if (asked != null && !asked.isBlank()) ctx.put("questionAsked", asked);
            ctx.put("today", today.toString());
            String system = "You read ONE message from church staff entering a " + d.noun() + " record and return ONLY JSON with the fields the message clearly gives. "
                + "Context (JSON): " + JSON.writeValueAsString(ctx) + ". Rules: fund / category / method must be copied EXACTLY from the lists or left out; "
                + "contributor = the person's or guest's name exactly as written (never invent one); amount = a plain number; date = YYYY-MM-DD; "
                + "omit anything not stated; never guess. Reply {} when nothing is clear.";
            JsonNode n = openAi.chatJson(system, cap(text, 500), 0);
            if (n == null || !n.isObject()) return false;
            boolean any = false;
            // 2026-11-09 (spec §4 "never guess an amount or a date"): the model never supplies the amount or the date —
            // those come only from the rules, and an unclear one is asked for.
            if (d.fundId == null && n.hasNonNull("fund")) any |= exact(fundList(d), n.get("fund").asText(), h -> { d.fundId = h.id(); d.fundLabel = h.label(); });
            if (d.methodId == null && n.hasNonNull("method")) any |= exact(methodList(d), n.get("method").asText(), h -> { d.methodId = h.id(); d.methodLabel = h.label(); });
            if (d.kind == Kind.EXPENSE && d.categoryId == null && n.hasNonNull("category"))
                any |= exact(lookup.expenseCategories(d.clientId), n.get("category").asText(), h -> { d.categoryId = h.id(); d.categoryLabel = h.label(); });
            if (d.kind == Kind.INCOME && d.memberId == null && d.guestName == null && n.hasNonNull("contributor")) {
                String who = n.get("contributor").asText("").trim();
                if (!who.isEmpty() && text.toLowerCase(Locale.ROOT).contains(who.toLowerCase(Locale.ROOT))) any |= contributor(d, who);   // must be in the user's own words
            }
            if (d.refNo == null && n.hasNonNull("refNo")) { String r = n.get("refNo").asText("").trim(); if (!r.isEmpty() && text.contains(r)) { d.refNo = cap(r, 100); any = true; } }
            if (d.note == null && n.hasNonNull("note")) { String r = n.get("note").asText("").trim(); if (!r.isEmpty()) { d.note = cap(r, 2000); any = true; } }
            return any;
        } catch (Exception e) {
            log.warn("[ai-guided] model fallback failed: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean exact(List<Hit> list, String name, java.util.function.Consumer<Hit> set) {
        for (Hit h : list) if (h.label() != null && h.label().trim().equalsIgnoreCase(name.trim())) { set.accept(h); return true; }
        return false;
    }

    // ── parsing helpers ───────────────────────────────────────────────────────

    record Parsed(LocalDate date, String rest) {}

    private static final String MONTHS = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)";
    private static final String DAYS = "(sun(?:day)?|mon(?:day)?|tue(?:s(?:day)?)?|wed(?:nesday)?|thu(?:rs(?:day)?)?|fri(?:day)?|sat(?:urday)?)";
    private static final Pattern ISO = Pattern.compile("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b");
    private static final Pattern SLASH = Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b");
    private static final Pattern MON_DAY = Pattern.compile("(?i)\\b" + MONTHS + "\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(\\d{4}))?\\b");
    private static final Pattern DAY_MON = Pattern.compile("(?i)\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?" + MONTHS + "\\b(?:,?\\s+(\\d{4}))?");
    private static final Pattern REL = Pattern.compile("(?i)\\b(day before yesterday|yesterday|today|tonight|this morning|this afternoon|this evening|tomorrow)\\b");
    private static final Pattern WEEKDAY = Pattern.compile("(?i)\\b(?:(last|this|past|next|coming)\\s+)?" + DAYS + "\\b");

    /**
     * A bookkeeping date: a month/day without a year is this year's, or last year's when that would be more than a
     * week ahead ("Dec 20" said in January = last December); a bare weekday is the most recent one ("Sunday" = last
     * Sunday, or today); "next Sunday" is ahead. Future dates are confirmed once by the conversation.
     */
    static Parsed parseDate(String text, LocalDate today) {
        Matcher m;
        if ((m = ISO.matcher(text)).find()) { LocalDate d = safe(i(m.group(1)), i(m.group(2)), i(m.group(3))); if (d != null) return new Parsed(d, cut(text, m)); }
        if ((m = SLASH.matcher(text)).find()) {
            int y = m.group(3) == null ? -1 : i(m.group(3)); if (y >= 0 && y < 100) y += 2000;
            LocalDate d = md(i(m.group(1)), i(m.group(2)), y, today); if (d != null) return new Parsed(d, cut(text, m));
        }
        if ((m = MON_DAY.matcher(text)).find()) { LocalDate d = md(month(m.group(1)), i(m.group(2)), m.group(3) == null ? -1 : i(m.group(3)), today); if (d != null) return new Parsed(d, cut(text, m)); }
        if ((m = DAY_MON.matcher(text)).find()) { LocalDate d = md(month(m.group(2)), i(m.group(1)), m.group(3) == null ? -1 : i(m.group(3)), today); if (d != null) return new Parsed(d, cut(text, m)); }
        if ((m = REL.matcher(text)).find()) {
            String w = m.group(1).toLowerCase(Locale.ROOT);
            LocalDate d = w.startsWith("day before") ? today.minusDays(2) : w.equals("yesterday") ? today.minusDays(1) : w.equals("tomorrow") ? today.plusDays(1) : today;
            return new Parsed(d, cut(text, m));
        }
        if ((m = WEEKDAY.matcher(text)).find()) {
            DayOfWeek dow = dow(m.group(2));
            String q = m.group(1) == null ? "" : m.group(1).toLowerCase(Locale.ROOT);
            LocalDate d;
            if (q.equals("next") || q.equals("coming")) d = today.with(TemporalAdjusters.next(dow));
            else if (q.equals("last") || q.equals("past")) d = today.with(TemporalAdjusters.previous(dow));
            else d = today.with(TemporalAdjusters.previousOrSame(dow));
            return new Parsed(d, cut(text, m));
        }
        return null;
    }
    private static LocalDate md(int month, int day, int year, LocalDate today) {
        if (month < 1 || month > 12) return null;
        if (year > 0) return safe(year, month, day);
        LocalDate d = safe(today.getYear(), month, day);
        if (d != null && d.isAfter(today.plusDays(7))) d = safe(today.getYear() - 1, month, day);
        return d;
    }
    private static LocalDate safe(int y, int m, int d) { try { return LocalDate.of(y, m, d); } catch (Exception e) { return null; } }
    private static int i(String s) { return Integer.parseInt(s); }
    private static int month(String s) { return List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(s.toLowerCase(Locale.ROOT).substring(0, 3)) + 1; }
    private static DayOfWeek dow(String s) {
        return switch (s.toLowerCase(Locale.ROOT).substring(0, 3)) {
            case "mon" -> DayOfWeek.MONDAY; case "tue" -> DayOfWeek.TUESDAY; case "wed" -> DayOfWeek.WEDNESDAY; case "thu" -> DayOfWeek.THURSDAY;
            case "fri" -> DayOfWeek.FRIDAY; case "sat" -> DayOfWeek.SATURDAY; default -> DayOfWeek.SUNDAY;
        };
    }
    /** An amount in the text ($50, 50 dollars, "of 50", or — when {@code bare} — just "50"). */
    static BigDecimal amountOf(String text, boolean bare) {
        Matcher m;
        if ((m = AMOUNT_CUR.matcher(text)).find() || (m = AMOUNT_WORD.matcher(text)).find() || (m = AMOUNT_OF.matcher(text)).find()) return number(m.group(1));
        if (bare && (m = AMOUNT_BARE.matcher(text)).matches()) return number(m.group(1));
        return null;
    }
    static BigDecimal number(String s) {
        try {
            String t = s.replace(",", "").replace("$", "").trim();
            BigDecimal a = new BigDecimal(t);
            if (a.scale() > 2) return null;
            return a.setScale(2, java.math.RoundingMode.UNNECESSARY);
        } catch (Exception e) { return null; }
    }
    private static String cut(String text, Matcher m) { return text.substring(0, m.start()) + " " + text.substring(m.end()); }
    static String cap(String s, int n) { return s == null ? null : s.length() > n ? s.substring(0, n) : s; }
    private static <T> List<T> first(List<T> l, int n) { return l.size() <= n ? l : new ArrayList<>(l.subList(0, n)); }

    private static Map<String, Object> field(String key, String label, String value, boolean required, String edit) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("key", key); f.put("label", label); f.put("value", value == null || value.isBlank() ? null : value);
        f.put("required", required); f.put("edit", edit);
        return f;
    }

    static Map<String, Object> base(String intent, String answer, String kind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("handled", true); m.put("denied", false); m.put("intent", intent); m.put("answer", answer); m.put("kind", kind); m.put("source", "AI Search");
        return m;
    }
}
