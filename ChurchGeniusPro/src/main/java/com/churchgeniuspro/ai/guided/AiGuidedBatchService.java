package com.churchgeniuspro.ai.guided;

import com.churchgeniuspro.ai.action.AiActionFollowUp;
import com.churchgeniuspro.ai.action.AiActionLookup.Hit;
import com.churchgeniuspro.ai.action.AiActionService;
import com.churchgeniuspro.ai.action.AiFinanceLookup;
import com.churchgeniuspro.ai.action.AiFinanceTools;
import com.churchgeniuspro.ai.guided.AiGuidedRecordService.BatchItem;
import com.churchgeniuspro.util.AppClock;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 2026-11-20 — Genius Assistant: several donations in one conversation.
 *
 * <p>"I received 20 checks and $2,000 in cash this Sunday. Please add them to the General Fund." → the cash
 * (as one entry or split), then the checks one photo at a time, then a summary of what was actually saved.
 *
 * <p>This class only ORGANISES the conversation. Every single entry is an ordinary guided income
 * ({@link AiGuidedRecordService#startBatchItem}): the same permission gate, validation, the login's own alerts,
 * the duplicate check, the review card and the one-time Save through the Income page's own service. Nothing is
 * written here, and nothing is saved without the user's explicit Confirm &amp; Save on that entry's card.
 *
 * <p>Checks are read with the existing check scan ({@code POST /api/income/check-scan}); the chat hands the
 * result to {@link #checkScanned}. An unreadable photo falls back to typing the check in. A check whose number
 * and amount were already saved (in this conversation or earlier) is questioned before it is offered again.
 *
 * <p>The summary counts only entries whose Save succeeded ({@link #confirmed}); "20 checks" in the request is
 * what was expected, never what is reported as recorded. Cancelling at any point keeps what was already saved
 * (each was confirmed on its own) and saves nothing more.
 */
@Slf4j
@Service
public class AiGuidedBatchService implements AiActionFollowUp {

    public static final String SESSION_KEY = "aiBatchDraft";
    /** Set by {@link AiGuidedRecordService} when it draws the review card of a batch entry: which token, which values. */
    public static final String ITEM_KEY = "aiBatchItem";
    static final String KIND = "offerings";
    static final DateTimeFormatter D = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.ENGLISH);
    static final DateTimeFormatter US = DateTimeFormatter.ofPattern("MM/dd/yyyy");

    enum Stage { FUND, OFFER_GUIDE, CASH_MODE, SPLIT_AMOUNT, ITEM, ITEM_DROPPED, OFFER_CHECKS, SCAN, DUP_CHECK, AFTER_CHECK }

    static final String YES_GUIDE = "Yes, please", CANCEL = "Cancel";
    static final String SINGLE = "Single transaction", SPLIT = "Split into multiple transactions";
    static final String SCAN_NEXT = "Scan Next Check", FINISH = "Finish & Review Summary", BY_HAND = "Enter it by hand";
    static final String REST = "The rest";

    private final AiGuidedRecordService records;
    private final AiFinanceLookup lookup;
    private final AiActionService actions;

    @Value("${ai.guided.batch-minutes:90}")
    public int draftMinutes = 90;

    public AiGuidedBatchService(AiGuidedRecordService records, AiFinanceLookup lookup, @Lazy AiActionService actions) {
        this.records = records; this.lookup = lookup; this.actions = actions;
    }

    // ── draft ─────────────────────────────────────────────────────────────────

    static final class Saved implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        boolean check; String amount; String refNo; String contributor; String date; String method; Long incomeId;
    }

    static final class Draft implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        String clientId; Instant created; Stage stage;
        Long fundId; String fundLabel;
        /** Cash named in the request (null = none) and how much of it is still to record. */
        String cashTotal; String cashLeft; boolean splitting;
        /** Checks named in the request (null = none / not counted). */
        Integer expectedChecks;
        /** The transaction date of the first saved entry — offered for the following ones. */
        LocalDate date;
        /** What the entry in progress is: "cash" or "check". */
        String current; String currentAmount;
        ArrayList<Saved> saved = new ArrayList<>();
        ArrayList<String> choices;
        /** A scanned check waiting for the "already recorded — again?" answer. */
        HashMap<String, String> heldCheck;
        boolean checksOffered;
        /** The entry in progress was started from a photo (its image may be kept with the saved income). */
        boolean currentScanned;
        int checksSaved() { int n = 0; for (Saved s : saved) if (s.check) n++; return n; }
    }

    // ── vocabulary ────────────────────────────────────────────────────────────

    private static final String NUM_WORD = "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty";
    static final Pattern CHECKS = Pattern.compile("(?i)\\b(\\d{1,3}|" + NUM_WORD + "|a few|few|several|some|many|multiple|a couple of|a bunch of)\\s+(?:offering\\s+|donation\\s+|tithe\\s+)?(?:checks|cheques)\\b");
    static final Pattern CHECKS_ANY = Pattern.compile("(?i)\\b(?:checks|cheques)\\b");
    static final Pattern CASH = Pattern.compile(
        "(?i)\\$\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)\\s*(?:dollars?\\s+)?(?:in\\s+|of\\s+|worth\\s+of\\s+)?cash\\b"
        + "|\\b(\\d[\\d,]*(?:\\.\\d{1,2})?)\\s*(?:dollars?|usd|bucks)\\s+(?:in\\s+|of\\s+)?cash\\b"
        + "|\\bcash\\s+(?:offerings?\\s+|donations?\\s+|collection\\s+)?(?:of|totall?ing|for|amount(?:ing)?\\s+to|worth|:|=|-)?\\s*\\$\\s*(\\d[\\d,]*(?:\\.\\d{1,2})?)"
        + "|\\bcash\\s+(?:offerings?\\s+|donations?\\s+|collection\\s+)?(?:of|totall?ing)\\s+(\\d[\\d,]*(?:\\.\\d{1,2})?)(?!\\s*(?:checks|cheques))");
    static final Pattern RECEIVE = Pattern.compile("(?i)\\b(received|receive|got|have|has|collected|counted|came in|record|add|enter|log|post|deposit)\\b");
    static final Pattern MULTI = Pattern.compile(
        "(?i)\\b(?:add|record|enter|log|post)\\s+(?:the\\s+|our\\s+|this\\s+|today'?s\\s+|sunday'?s?\\s+|last\\s+sunday'?s\\s+)?(?:multiple|several|many|a batch of|a few|all the|all|sunday|today'?s|this week'?s)\\s+(?:sunday\\s+)?(?:donations|offerings|contributions|gifts|tithes|collections?)\\b");
    static final Pattern QUESTION = Pattern.compile("(?i)^\\s*(how do i|how can i|how to|how many|how much|what|who|when|which|why|show|list|where|did|do we|does|is|are)\\b|\\?\\s*$");
    static final Pattern YES = Pattern.compile("(?i)^\\s*(yes|yeah|yep|yup|sure|ok|okay|please|yes,? please|go ahead|do it|proceed|let'?s (go|do it|start)|start|continue|ready)([,.! ]+(please|thanks?|thank you|go ahead|let'?s (go|start)))*[.!]?\\s*$");
    static final Pattern NO = Pattern.compile("(?i)^\\s*(no|nope|no thanks|not now|skip|skip it)[.!]?\\s*$");
    static final Pattern STOP = Pattern.compile("(?i)^\\s*(cancel|cancel (it|that|this|everything|all)|never ?mind|stop|forget it|abort|quit)[.!]?\\s*$");
    static final Pattern DONE = Pattern.compile("(?i)^\\s*(finish|finished|done|i'?m done|that'?s (all|it|everything)|no more( checks)?|finish (and|&) review( summary)?|review( the)? summary|show( me)?( the)? summary|summary|no,? finish.*)[.!]?\\s*$");
    static final Pattern NEXT = Pattern.compile("(?i)^\\s*(scan( the)? next( check)?|next( check)?|scan( a| another)?( check)?|another( check| one)?|take( a)? photo|upload( an?)? image|yes,? scan.*|scan one more check)[.!]?\\s*$");
    static final Pattern HAND = Pattern.compile("(?i)^\\s*(enter (it|this|the check|one) by hand|by hand|manual(ly)?|type it( in)?|enter (it )?manually|i'?ll type it)[.!]?\\s*$");

    /** "I received 20 checks and $2,000 in cash …", "add several offerings", "record 5 checks to the Building Fund". */
    public static boolean isStartRequest(String q) {
        if (q == null || q.isBlank() || QUESTION.matcher(q).find()) return false;
        boolean checks = CHECKS.matcher(q).find(), cash = CASH.matcher(q).find(), verb = RECEIVE.matcher(q).find();
        if (checks && (cash || verb)) return true;
        if (cash && CHECKS_ANY.matcher(q).find()) return true;
        if (cash && verb && Pattern.compile("(?i)\\b(received|collected|counted|got|have)\\b").matcher(q).find()) return true;
        return MULTI.matcher(q).find();
    }

    public static void clear(HttpSession s) { if (s != null) { s.removeAttribute(SESSION_KEY); s.removeAttribute(ITEM_KEY); } }
    public static boolean hasDraft(HttpSession s) { return s != null && s.getAttribute(SESSION_KEY) instanceof Draft; }

    // ── the conversation ──────────────────────────────────────────────────────

    /** Null = nothing to do with a batch (the search continues with its other routes, e.g. the entry in progress). */
    public Map<String, Object> handle(HttpServletRequest req, String clientId, String question) {
        if (req == null || clientId == null || question == null) return null;
        HttpSession session = req.getSession(false);
        if (session == null) return null;
        String q = question.trim();
        if (q.isEmpty()) return null;
        Draft d = pending(session, clientId);

        if (isStartRequest(q) && (d == null || d.stage != Stage.ITEM)) return start(req, session, clientId, q, d);
        if (d == null) return null;
        Map<String, Object> gate = records.incomeGate(req, clientId);              // permission changed mid-conversation
        if (gate != null) { records.cancelOpen(req, clientId); clear(session); return gate; }
        String t = q.replaceAll("[.!]+$", "").trim();
        String picked = d.choices == null ? null : AiActionService.pick(t, d.choices);

        switch (d.stage) {
            case ITEM -> {
                // the entry in progress answers for itself (AiGuidedRecordService); only "cancel" and a vanished entry are ours
                if (STOP.matcher(q).matches()) { records.cancelOpen(req, clientId); return dropped(session, d, "Okay — I have not saved that " + noun(d) + "."); }
                if (!AiGuidedRecordService.hasDraft(session) && !itemOpen(session, clientId))
                    return dropped(session, d, "That " + noun(d) + " was not saved (it was left open too long).");
                return null;
            }
            case FUND -> {
                if (STOP.matcher(q).matches()) return stop(session, d);
                Hit f = fundFrom(clientId, picked != null ? picked : t);
                if (f == null) return askFund(session, d, "I couldn't match “" + AiGuidedRecordService.cap(t, 60) + "” to one of your funds. ");
                d.fundId = f.id(); d.fundLabel = f.label();
                return offerGuide(session, d);
            }
            case OFFER_GUIDE -> {
                if (STOP.matcher(q).matches() || NO.matcher(q).matches() || CANCEL.equalsIgnoreCase(picked)) return stop(session, d);
                if (YES.matcher(q).matches() || YES_GUIDE.equalsIgnoreCase(picked)) return firstStep(req, session, d);
                return null;
            }
            case CASH_MODE -> {
                if (STOP.matcher(q).matches() || CANCEL.equalsIgnoreCase(picked)) return stop(session, d);
                String low = t.toLowerCase(Locale.ROOT);
                if (SINGLE.equalsIgnoreCase(picked) || low.matches("^(single|one|just one|as one|one transaction|single transaction|together|all at once).*")) {
                    d.splitting = false;
                    return startItem(req, session, d, "cash", d.cashLeft, null, null, null, null);
                }
                if (SPLIT.equalsIgnoreCase(picked) || low.matches("^(split|multiple|several|separate|more than one).*")) {
                    d.splitting = true;
                    return askSplit(session, d, "The cash total is " + money(d.cashLeft) + ". What is the amount of the first part?");
                }
                return null;
            }
            case SPLIT_AMOUNT -> {
                if (STOP.matcher(q).matches() || CANCEL.equalsIgnoreCase(picked)) return stop(session, d);
                BigDecimal left = new BigDecimal(d.cashLeft);
                if (picked != null && picked.startsWith(REST)) return startItem(req, session, d, "cash", d.cashLeft, null, null, null, null);
                if (picked != null && picked.startsWith("Stop splitting")) { d.cashLeft = null; return afterCash(req, session, d, "Okay — the remaining " + money(left.toPlainString()) + " of the cash was not recorded. "); }
                BigDecimal a = AiGuidedRecordService.amountOf(t, true);
                if (a == null || a.signum() <= 0) return askSplit(session, d, "Please enter an amount, such as $500. " + money(d.cashLeft) + " of the cash is left.");
                if (a.compareTo(left) > 0) return askSplit(session, d, money(a.toPlainString()) + " is more than the " + money(d.cashLeft) + " of cash that is left. What is the amount of this part?");
                return startItem(req, session, d, "cash", a.toPlainString(), null, null, null, null);
            }
            case ITEM_DROPPED -> {
                if (STOP.matcher(q).matches() || CANCEL.equalsIgnoreCase(picked)) return stop(session, d);
                if (DONE.matcher(q).matches() || FINISH.equalsIgnoreCase(picked)) return summary(session, d, null);
                String low = (picked != null ? picked : t).toLowerCase(Locale.ROOT);
                if (low.startsWith("record the cash")) return d.splitting ? askSplit(session, d, money(d.cashLeft) + " of the cash is left. What is the amount of this part?") : startItem(req, session, d, "cash", d.cashLeft, null, null, null, null);
                if (low.startsWith("continue to the checks") || NEXT.matcher(q).matches() || SCAN_NEXT.equalsIgnoreCase(picked)) return scanPrompt(session, d, null);
                if (HAND.matcher(q).matches() || BY_HAND.equalsIgnoreCase(picked)) return startItem(req, session, d, "check", null, null, null, null, null);
                return null;
            }
            case OFFER_CHECKS -> {
                if (STOP.matcher(q).matches() || CANCEL.equalsIgnoreCase(picked)) return stop(session, d);
                if (DONE.matcher(q).matches() || NO.matcher(q).matches() || (picked != null && picked.startsWith("No"))) return summary(session, d, null);
                if (YES.matcher(q).matches() || NEXT.matcher(q).matches() || (picked != null && picked.startsWith("Yes"))) return scanPrompt(session, d, "Great! Click the Take Photo button whenever you're ready.");
                return null;
            }
            case SCAN, AFTER_CHECK -> {
                if (STOP.matcher(q).matches() || CANCEL.equalsIgnoreCase(picked)) return stop(session, d);
                if (DONE.matcher(q).matches() || FINISH.equalsIgnoreCase(picked) || (d.stage == Stage.AFTER_CHECK && NO.matcher(q).matches())) return summary(session, d, null);
                if (HAND.matcher(q).matches() || BY_HAND.equalsIgnoreCase(picked)) return startItem(req, session, d, "check", null, null, null, null, null);
                if (NEXT.matcher(q).matches() || YES.matcher(q).matches() || SCAN_NEXT.equalsIgnoreCase(picked) || (picked != null && picked.startsWith("Scan")))
                    return scanPrompt(session, d, "Click the Take Photo button whenever you're ready.");
                return null;
            }
            case DUP_CHECK -> {
                if (STOP.matcher(q).matches() || CANCEL.equalsIgnoreCase(picked)) return stop(session, d);
                HashMap<String, String> held = d.heldCheck; d.heldCheck = null;
                if (held != null && (YES.matcher(q).matches() || (picked != null && picked.startsWith("Yes")))) return startCheck(req, session, d, held);
                if (DONE.matcher(q).matches() || FINISH.equalsIgnoreCase(picked)) return summary(session, d, null);
                return scanPrompt(session, d, "Okay — that check was not recorded again. Click the Take Photo button for the next check.");
            }
            default -> { return null; }
        }
    }

    private Map<String, Object> start(HttpServletRequest req, HttpSession session, String clientId, String q, Draft old) {
        if (old != null) records.cancelOpen(req, clientId);
        clear(session);
        GuidedDrafts.clearOthers(session, GuidedDrafts.BATCH);          // a new conversation: every other open draft is dropped
        Map<String, Object> gate = records.incomeGate(req, clientId);
        if (gate != null) return gate;
        Draft d = new Draft();
        d.clientId = clientId; d.created = Instant.now();
        Matcher cm = CASH.matcher(q);
        if (cm.find()) {
            String raw = cm.group(1) != null ? cm.group(1) : cm.group(2) != null ? cm.group(2) : cm.group(3) != null ? cm.group(3) : cm.group(4);
            BigDecimal a = AiGuidedRecordService.number(raw);
            if (a != null && a.signum() > 0 && a.compareTo(com.churchgeniuspro.controller.IncomeController.AMOUNT_MAX) <= 0) { d.cashTotal = a.toPlainString(); d.cashLeft = d.cashTotal; }
        }
        Matcher km = CHECKS.matcher(q);
        if (km.find()) { d.expectedChecks = count(km.group(1)); if (d.expectedChecks == null) d.expectedChecks = 0; }
        else if (CHECKS_ANY.matcher(q).find()) d.expectedChecks = 0;                 // "some checks": expected, not counted
        Hit f = fundFrom(clientId, q);
        if (f != null) { d.fundId = f.id(); d.fundLabel = f.label(); }
        if (d.fundId == null) return askFund(session, d, "");
        return offerGuide(session, d);
    }

    private Map<String, Object> askFund(HttpSession session, Draft d, String lead) {
        List<String> names = AiGuidedRecordService.labels(lookup.incomeFunds(d.clientId));
        if (names.isEmpty()) { clear(session); return base("guided.refused", "There are no income funds yet — add one on the Income page first. Nothing was saved."); }
        List<String> ch = names.size() <= 20 ? names : new ArrayList<>(names.subList(0, 20));
        Map<String, Object> m = ask(session, d, Stage.FUND, lead + "Which fund should these donations go to?", ch);
        return m;
    }

    private Map<String, Object> offerGuide(HttpSession session, Draft d) {
        String what = described(d);
        return ask(session, d, Stage.OFFER_GUIDE, "That's great! I can help you record " + what + " in the " + d.fundLabel
            + ". Would you like me to guide you through adding them one by one?", List.of(YES_GUIDE, CANCEL));
    }

    private Map<String, Object> firstStep(HttpServletRequest req, HttpSession session, Draft d) {
        if (d.cashLeft != null)
            return ask(session, d, Stage.CASH_MODE, "Certainly! First, how would you like to record the " + money(d.cashLeft) + " cash offering?", List.of(SINGLE, SPLIT));
        return offerChecks(session, d, "Certainly! ");
    }

    private Map<String, Object> askSplit(HttpSession session, Draft d, String question) {
        List<String> ch = new ArrayList<>();
        if (d.cashLeft != null && d.cashTotal != null && !d.cashLeft.equals(d.cashTotal)) { ch.add(REST + " (" + money(d.cashLeft) + ")"); ch.add("Stop splitting"); }
        return ask(session, d, Stage.SPLIT_AMOUNT, question, ch.isEmpty() ? null : ch);
    }

    /** After the cash is dealt with: the checks (when the request named some), otherwise the summary. */
    private Map<String, Object> afterCash(HttpServletRequest req, HttpSession session, Draft d, String lead) {
        if (d.expectedChecks != null) return offerChecks(session, d, lead);
        return summary(session, d, lead);
    }

    private Map<String, Object> offerChecks(HttpSession session, Draft d, String lead) {
        d.checksOffered = true;
        String n = d.expectedChecks != null && d.expectedChecks > 0 ? "the " + d.expectedChecks + " check" + (d.expectedChecks == 1 ? "" : "s") : "the checks";
        return ask(session, d, Stage.OFFER_CHECKS, (lead == null ? "" : lead) + "Now, we can make recording " + n + " much easier. Simply take a photo of each check, one at a time. "
            + "I'll read the details, show them to you for review, and you confirm each check before it is saved. Would you like me to help you do that?",
            List.of("Yes", "No, finish & review summary", CANCEL));
    }

    /** The "take a photo" step: the chat shows Take Photo / Upload Image; the choices are the other ways on. */
    private Map<String, Object> scanPrompt(HttpSession session, Draft d, String lead) {
        Map<String, Object> m = ask(session, d, Stage.SCAN, lead == null ? "Click the Take Photo button whenever you're ready." : lead, List.of(BY_HAND, FINISH, CANCEL));
        m.put("scan", scanInfo());
        return m;
    }

    static Map<String, Object> scanInfo() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("scanEndpoint", "/api/income/check-scan"); s.put("submitEndpoint", "/api/ai/actions/batch-check");
        s.put("imageEndpoint", "/api/income/{id}/check-image");
        return s;
    }

    // ── a scanned check arrives ───────────────────────────────────────────────

    /**
     * The chat scanned a photo with the existing check scan and hands over what was read:
     * {@code {readable, fields:{amount, checkNo, date, payorName, memo}, contributor:{autoSelectId}, error}}.
     * Starts that check's review (or says why not). Never saves.
     */
    public Map<String, Object> checkScanned(HttpServletRequest req, String clientId, Map<String, Object> body) {
        HttpSession session = req == null ? null : req.getSession(false);
        Draft d = session == null ? null : pending(session, clientId);
        if (d == null) return base("guided.expired", "That conversation is no longer open, so the photo was not used. Nothing was saved. Start again when you are ready.");
        Map<String, Object> gate = records.incomeGate(req, clientId);
        if (gate != null) { records.cancelOpen(req, clientId); clear(session); return gate; }
        if (d.stage == Stage.ITEM && (AiGuidedRecordService.hasDraft(session) || itemOpen(session, clientId)))
            return keepOpen(base("guided.ask", "Please finish or cancel the entry that is open before scanning another check."));
        if (d.stage != Stage.SCAN && d.stage != Stage.AFTER_CHECK && d.stage != Stage.OFFER_CHECKS && d.stage != Stage.ITEM_DROPPED && d.stage != Stage.DUP_CHECK && d.stage != Stage.ITEM)
            return keepOpen(base("guided.ask", "I'm not ready for a check photo yet. " + "Please answer the question above first."));

        HashMap<String, String> c = new HashMap<>();
        if (body != null && body.get("fields") instanceof Map<?, ?> f) {
            for (String k : List.of("amount", "checkNo", "date", "payorName", "memo")) {
                Object v = f.get(k);
                if (v != null && !String.valueOf(v).isBlank()) c.put(k, AiGuidedRecordService.cap(String.valueOf(v).trim(), 300));
            }
        }
        if (body != null && body.get("contributor") instanceof Map<?, ?> cm && cm.get("autoSelectId") != null) {
            try { c.put("memberId", String.valueOf(Long.parseLong(String.valueOf(cm.get("autoSelectId")).replaceAll("\\.0$", "")))); } catch (NumberFormatException ignore) { }
        }
        BigDecimal amt = c.get("amount") == null ? null : AiGuidedRecordService.number(c.get("amount"));
        if (amt == null || amt.signum() <= 0) c.remove("amount"); else c.put("amount", amt.toPlainString());
        if (c.get("checkNo") != null) { String n = c.get("checkNo").replaceAll("[^A-Za-z0-9-]", ""); if (n.isEmpty()) c.remove("checkNo"); else c.put("checkNo", n); }

        boolean nothing = !c.containsKey("amount") && !c.containsKey("checkNo") && !c.containsKey("payorName");
        if (nothing) {
            String why = body != null && body.get("error") instanceof String e && !e.isBlank() ? AiGuidedRecordService.cap(e, 200) + " " : "";
            Map<String, Object> m = ask(session, d, Stage.SCAN, "I couldn't read that check. " + why + "You can take another photo, or enter this check by hand.", List.of(BY_HAND, FINISH, CANCEL));
            m.put("scan", scanInfo()); m.put("scanFailed", true);
            return m;
        }
        // already recorded? (this conversation first, then the church's income)
        if (c.containsKey("checkNo") && c.containsKey("amount")) {
            String when = null;
            for (Saved s : d.saved) if (s.check && c.get("checkNo").equalsIgnoreCase(String.valueOf(s.refNo)) && new BigDecimal(s.amount).compareTo(amt) == 0) { when = "a moment ago in this conversation"; break; }
            if (when == null) {
                try { LocalDate on = lookup.incomeDateForCheck(clientId, c.get("checkNo"), amt); if (on != null) when = "on " + D.format(on); }
                catch (RuntimeException e) { log.debug("check duplicate lookup failed: {}", e.getClass().getSimpleName()); }
            }
            if (when != null) {
                d.heldCheck = c;
                return ask(session, d, Stage.DUP_CHECK, "Check #" + c.get("checkNo") + " for " + money(c.get("amount")) + " was already recorded " + when
                    + ". Do you want to record it again?", List.of("No, scan a different check", "Yes, record it again", FINISH));
            }
        }
        return startCheck(req, session, d, c);
    }

    private Map<String, Object> startCheck(HttpServletRequest req, HttpSession session, Draft d, Map<String, String> c) {
        Long memberId = c.get("memberId") == null ? null : Long.valueOf(c.get("memberId"));
        String note = c.get("memo") == null ? null : "Memo: " + c.get("memo");
        Map<String, Object> m = startItem(req, session, d, "check", c.get("amount"), c.get("checkNo"), note, memberId, memberId == null ? c.get("payorName") : null);
        if (m != null) {
            if (Boolean.TRUE.equals(m.get("pending")) && d.stage == Stage.ITEM) { d.currentScanned = true; session.setAttribute(SESSION_KEY, d); }
            List<String> read = new ArrayList<>();
            if (c.get("amount") != null) read.add("amount " + money(c.get("amount")));
            if (c.get("checkNo") != null) read.add("check #" + c.get("checkNo"));
            if (c.get("payorName") != null) read.add("from " + c.get("payorName"));
            if (c.get("date") != null) read.add("dated " + c.get("date"));
            if (!read.isEmpty()) m.put("scanned", "I read: " + String.join(", ", read) + ". Please check it against the check.");
        }
        return m;
    }

    private Map<String, Object> startItem(HttpServletRequest req, HttpSession session, Draft d, String what, String amount, String refNo, String note, Long memberId, String payer) {
        d.stage = Stage.ITEM; d.current = what; d.currentAmount = amount; d.choices = null; d.created = Instant.now(); d.currentScanned = false;
        session.setAttribute(SESSION_KEY, d);
        boolean check = "check".equals(what);
        String label = check ? "Check" + (refNo == null ? "" : " #" + refNo) : "Cash offering";
        Map<String, Object> m = records.startBatchItem(req, d.clientId, new BatchItem(label, d.fundId, amount, check ? "Check" : "Cash", d.date, refNo, note, memberId, payer));
        if (m == null || !Boolean.TRUE.equals(m.get("pending"))) {                // refused / denied: the batch cannot go on with this entry
            if (m != null && Boolean.TRUE.equals(m.get("denied"))) { clear(session); return m; }
            Map<String, Object> r = dropped(session, d, m == null ? "That entry could not be started." : String.valueOf(m.get("answer")));
            return r;
        }
        m.put("batch", progress(d));
        return m;
    }

    /** The entry in progress was cancelled / refused / left: what next? Nothing of it was saved. */
    private Map<String, Object> dropped(HttpSession session, Draft d, String lead) {
        session.removeAttribute(ITEM_KEY);
        boolean check = "check".equals(d.current);
        List<String> ch = new ArrayList<>();
        if (!check && d.cashLeft != null) { ch.add("Record the cash again"); if (d.expectedChecks != null) ch.add("Continue to the checks"); }
        else { ch.add(SCAN_NEXT); ch.add(BY_HAND); }
        ch.add(FINISH); ch.add(CANCEL);
        Map<String, Object> m = ask(session, d, Stage.ITEM_DROPPED, lead + " What would you like to do next?", ch);
        if (check || d.cashLeft == null) m.put("scan", scanInfo());
        return m;
    }

    // ── after an entry's card was saved / cancelled ───────────────────────────

    @Override
    public void confirmed(HttpServletRequest req, String clientId, String token, String tool, Map<String, Object> result, Map<String, Object> response) {
        HttpSession session = req == null ? null : req.getSession(false);
        Draft d = session == null ? null : pending(session, clientId);
        Map<String, Object> item = item(session);
        if (d == null || item == null || token == null || !token.equals(item.get("token")) || !"create_income".equals(tool)) return;
        session.removeAttribute(ITEM_KEY);
        AiGuidedRecordService.clear(session);                      // a button Save leaves the entry's draft behind
        Saved s = new Saved();
        s.check = "check".equals(d.current);
        s.amount = String.valueOf(item.get("amount")); s.date = String.valueOf(item.get("date"));
        s.refNo = item.get("refNo") == null ? null : String.valueOf(item.get("refNo"));
        s.contributor = item.get("contributor") == null ? null : String.valueOf(item.get("contributor"));
        s.method = item.get("method") == null ? null : String.valueOf(item.get("method"));
        Object id = result == null ? null : result.get("incomeId");
        if (id != null) { try { s.incomeId = Long.valueOf(String.valueOf(id)); } catch (NumberFormatException ignore) { } }
        d.saved.add(s);
        if (d.date == null) { try { d.date = LocalDate.parse(s.date); } catch (RuntimeException ignore) { } }
        if (s.incomeId != null) response.put("incomeId", s.incomeId);
        if (s.incomeId != null && s.check && d.currentScanned) response.put("checkImage", true);
        d.currentScanned = false;
        response.put("kind", KIND);

        Map<String, Object> next;
        if (!s.check) {
            BigDecimal left = d.cashLeft == null ? BigDecimal.ZERO : new BigDecimal(d.cashLeft).subtract(new BigDecimal(s.amount));
            if (d.splitting && left.signum() > 0) {
                d.cashLeft = left.toPlainString();
                next = askSplit(session, d, money(s.amount) + " of the cash has been recorded. " + money(d.cashLeft) + " is left. What is the amount of the next part?");
            } else {
                d.cashLeft = null;
                next = afterCash(req, session, d, "Excellent! The cash offering has been recorded. ");
            }
        } else {
            int n = d.checksSaved();
            boolean all = d.expectedChecks != null && d.expectedChecks > 0 && n >= d.expectedChecks;
            String done = "Check" + (s.refNo == null ? "" : " #" + s.refNo) + " for " + money(s.amount) + " has been recorded"
                + (d.expectedChecks != null && d.expectedChecks > 0 ? " (" + n + " of " + d.expectedChecks + ")" : " (" + n + " so far)") + ". ";
            if (all) next = ask(session, d, Stage.AFTER_CHECK, done + "That was the last of the " + d.expectedChecks + " checks. Would you like to finish and review the summary?",
                    List.of(FINISH, "Scan one more check", CANCEL));
            else next = ask(session, d, Stage.AFTER_CHECK, done + "Would you like to scan the next check?", List.of(SCAN_NEXT, FINISH, CANCEL));
            next.put("scan", scanInfo());
        }
        merge(response, next);
    }

    @Override
    public void rejected(HttpServletRequest req, String clientId, String token, Map<String, Object> response) {
        HttpSession session = req == null ? null : req.getSession(false);
        Draft d = session == null ? null : pending(session, clientId);
        Map<String, Object> item = item(session);
        if (d == null || d.stage != Stage.ITEM || item == null || token == null || !token.equals(item.get("token"))) return;
        AiGuidedRecordService.clear(session);
        Map<String, Object> next = dropped(session, d, "Okay — I have not saved that " + noun(d) + ".");
        response.put("kind", KIND);
        merge(response, next);
    }

    /** Puts the follow-up question on a saved / cancelled response (the saved confirmation itself stays). */
    private static void merge(Map<String, Object> response, Map<String, Object> next) {
        String q = String.valueOf(next.get("answer"));
        if (next.get("summary") != null || !Boolean.TRUE.equals(next.get("pending"))) {         // the conversation ended with the summary
            for (String k : List.of("summary", "columns", "rows", "batchSummary", "link", "summaryTitle")) if (next.get(k) != null) response.put(k, next.get(k));
            response.put("question", q);
            if (!(response.get("receipt") instanceof Map<?, ?>)) response.put("answer", q);
            response.remove("pending"); response.remove("choices");
            return;
        }
        response.put("question", q);
        if (!(response.get("receipt") instanceof Map<?, ?>)) response.put("answer", q);
        else response.put("answer", response.get("answer") + " " + q);
        for (String k : List.of("choices", "asking", "scan", "batch")) if (next.get(k) != null) response.put(k, next.get(k));
        response.put("pending", true);
    }

    // ── the end ───────────────────────────────────────────────────────────────

    private Map<String, Object> stop(HttpSession session, Draft d) {
        if (d.saved.isEmpty()) { clear(session); Map<String, Object> m = base("guided.cancelled", "Okay — I've stopped. Nothing was saved."); return m; }
        return summary(session, d, "Okay — I've stopped. Nothing more was saved; the entries you already confirmed stay recorded. ");
    }

    /** What was actually saved in this conversation — and only that. Ends the conversation. */
    private Map<String, Object> summary(HttpSession session, Draft d, String lead) {
        clear(session);
        AiGuidedRecordService.clear(session);
        if (d.saved.isEmpty()) return base("guided.cancelled", (lead == null ? "" : lead) + "No donations were recorded in this conversation, so there is nothing to summarise. Nothing was saved.");
        BigDecimal cash = BigDecimal.ZERO, checks = BigDecimal.ZERO; int nCash = 0, nChecks = 0;
        LinkedHashSet<String> dates = new LinkedHashSet<>();
        List<List<String>> rows = new ArrayList<>();
        int i = 0;
        for (Saved s : d.saved) {
            BigDecimal a = new BigDecimal(s.amount);
            if (s.check) { checks = checks.add(a); nChecks++; } else { cash = cash.add(a); nCash++; }
            String on = s.date;
            try { on = US.format(LocalDate.parse(s.date)); } catch (RuntimeException ignore) { }
            dates.add(on);
            rows.add(List.of(String.valueOf(++i), s.check ? "Check" : "Cash", s.contributor == null ? "" : s.contributor, AiFinanceTools.money(a), s.refNo == null ? "" : s.refNo, on));
        }
        Map<String, Object> sum = new LinkedHashMap<>();
        if (nCash > 0 || d.cashTotal != null) sum.put("Cash Offering", AiFinanceTools.money(cash) + (nCash > 1 ? " (" + nCash + " entries)" : ""));
        if (nChecks > 0 || d.expectedChecks != null)
            sum.put("Checks Recorded", nChecks + (d.expectedChecks != null && d.expectedChecks > 0 ? " of " + d.expectedChecks : ""));
        if (nChecks > 0) sum.put("Total Check Amount", AiFinanceTools.money(checks));
        sum.put("Total Donations Recorded", AiFinanceTools.money(cash.add(checks)));
        sum.put("Fund", d.fundLabel);
        sum.put("Transaction Date", dates.size() == 1 ? dates.iterator().next() : "Several dates (see the list)");

        List<String> notes = new ArrayList<>();
        if (d.expectedChecks != null && d.expectedChecks > 0 && nChecks < d.expectedChecks)
            notes.add((d.expectedChecks - nChecks) + " of the " + d.expectedChecks + " checks " + (d.expectedChecks - nChecks == 1 ? "was" : "were") + " not recorded.");
        if (d.cashTotal != null && cash.compareTo(new BigDecimal(d.cashTotal)) < 0)
            notes.add(AiFinanceTools.money(new BigDecimal(d.cashTotal).subtract(cash)) + " of the " + money(d.cashTotal) + " cash was not recorded.");
        String text = (lead == null ? "" : lead) + "Here is the summary of the donations recorded: " + d.saved.size() + (d.saved.size() == 1 ? " entry" : " entries")
            + " totalling " + AiFinanceTools.money(cash.add(checks)) + " to the " + d.fundLabel + "."
            + (notes.isEmpty() ? "" : " " + String.join(" ", notes)) + " Only entries you confirmed are counted.";
        Map<String, Object> m = base("guided.summary", text);
        m.put("summary", sum);
        m.put("columns", List.of("#", "Type", "Contributor", "Amount", "Check / Ref #", "Date"));
        m.put("rows", rows);
        m.put("batchSummary", true);
        m.put("link", Map.of("label", "Open Income", "url", "/income"));
        return m;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> ask(HttpSession session, Draft d, Stage stage, String question, List<String> choices) {
        d.stage = stage; d.choices = choices == null ? null : new ArrayList<>(choices); d.created = Instant.now();
        session.setAttribute(SESSION_KEY, d);
        Map<String, Object> m = base("guided.ask", question);
        m.put("asking", stage.name().toLowerCase(Locale.ROOT));
        m.put("pending", true);
        if (choices != null && !choices.isEmpty()) m.put("choices", choices);
        m.put("question", question);
        m.put("batch", progress(d));
        return m;
    }

    private static Map<String, Object> keepOpen(Map<String, Object> m) { m.put("pending", true); return m; }

    private static Map<String, Object> progress(Draft d) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("saved", d.saved.size()); p.put("checksSaved", d.checksSaved());
        if (d.expectedChecks != null && d.expectedChecks > 0) p.put("checksExpected", d.expectedChecks);
        if (d.fundLabel != null) p.put("fund", d.fundLabel);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> item(HttpSession session) {
        return session != null && session.getAttribute(ITEM_KEY) instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /** True while the entry's review card can still be saved (so the entry is not abandoned). */
    private boolean itemOpen(HttpSession session, String clientId) {
        Map<String, Object> item = item(session);
        return item != null && item.get("token") != null && actions.isOpen(clientId, String.valueOf(item.get("token")));
    }

    private Hit fundFrom(String clientId, String text) {
        List<Hit> funds = lookup.incomeFunds(clientId);
        for (Hit h : funds) if (AiGuidedRecordService.norm(h.label()).equals(AiGuidedRecordService.norm(text))) return h;
        List<Hit> hits = AiGuidedRecordService.matchNames(text, funds);
        return hits.size() == 1 ? hits.get(0) : null;
    }

    private static String described(Draft d) {
        List<String> parts = new ArrayList<>();
        if (d.expectedChecks != null) parts.add(d.expectedChecks > 0 ? d.expectedChecks + " check" + (d.expectedChecks == 1 ? "" : "s") : "the checks");
        if (d.cashTotal != null) parts.add(money(d.cashTotal) + " in cash");
        return parts.isEmpty() ? "these donations" : String.join(" and ", parts);
    }

    private static String noun(Draft d) { return "check".equals(d.current) ? "check" : "cash offering"; }
    private static String money(String plain) { try { return AiFinanceTools.money(new BigDecimal(plain)); } catch (RuntimeException e) { return String.valueOf(plain); } }

    static Integer count(String w) {
        if (w == null) return null;
        String t = w.trim().toLowerCase(Locale.ROOT);
        if (t.matches("\\d{1,3}")) { int n = Integer.parseInt(t); return n >= 1 && n <= 500 ? n : null; }
        List<String> words = List.of("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen",
                "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty");
        int i = words.indexOf(t);
        if (i >= 0) return i + 1;
        return switch (t) { case "thirty" -> 30; case "forty" -> 40; case "fifty" -> 50; default -> null; };
    }

    private Draft pending(HttpSession session, String clientId) {
        Object o = session.getAttribute(SESSION_KEY);
        if (!(o instanceof Draft d)) return null;
        if (!clientId.equals(d.clientId) || d.created == null || d.created.plusSeconds(60L * Math.max(1, draftMinutes)).isBefore(Instant.now())) {
            clear(session);
            return null;
        }
        return d;
    }

    static Map<String, Object> base(String intent, String answer) { return AiGuidedRecordService.base(intent, answer, KIND); }
}
