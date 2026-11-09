package com.churchgeniuspro.ai.guided;

import com.churchgeniuspro.ai.action.AiActionLookup.Hit;
import com.churchgeniuspro.ai.action.AiActionService;
import com.churchgeniuspro.ai.action.AiFinanceLookup;
import com.churchgeniuspro.ai.action.AiSettingsTools;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.churchgeniuspro.ai.guided.AiGuidedRecordService.*;

/**
 * 2026-11-08 — AI Search guided conversation for Account Settings entries: "Add a new fund called Building Fund",
 * "Create a new transaction type called Special Offering", "Add a purpose named Youth Camp", "Add a sub-fund Roof
 * Repair under Building Fund".
 *
 * <p>Flow: the name (asked when missing) → for "fund": main fund or sub-fund? → for a sub-fund: which main fund
 * (choices = this church's main funds) → a name that already exists is reported and a new name asked → review card
 * (Name, Fund level / Main fund, Tax-deductible for a sub-fund) → the user's explicit Save → {@link AiActionService#confirm}
 * → the Account Settings pages' own service methods (see {@link AiSettingsTools}). Corrections ("change the name to …",
 * "make it a main fund", "change the main fund to General", "not tax-deductible") and "cancel" at every step.
 *
 * <p>Rules only — no model call (a name needs no interpretation). Permission = the Fund / Purpose / Transaction Type
 * pages' rule (SuperAdmin / Admin / Accountant + {@code accounting.settings}), checked first and on every message, and
 * again at Save; plan feature {@code aiActions}; every lookup is this church's.
 */
@Service
public class AiGuidedSettingService {

    public static final String SESSION_KEY = "aiSettingDraft";

    public enum Kind { TYPE, PURPOSE, FUND, SUB_FUND, FUND_UNDECIDED }

    private final AiAccessPolicy access;
    private final AiActionService actions;
    private final AiFinanceLookup lookup;

    @Value("${ai.guided.draft-minutes:15}")
    public int draftMinutes = 15;

    public AiGuidedSettingService(AiAccessPolicy access, AiActionService actions, AiFinanceLookup lookup) {
        this.access = access; this.actions = actions; this.lookup = lookup;
    }

    static final class Draft implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        Kind kind; String clientId; Instant created;
        String name; Long parentId; String parentLabel; boolean taxDeductible = true;
        String asked; List<String> choices; String token; boolean carded;
        String noun() {
            return switch (kind) {
                case TYPE -> "transaction type"; case PURPOSE -> "purpose"; case FUND -> "main fund";
                case SUB_FUND -> "sub-fund"; default -> "fund";
            };
        }
    }

    // ── vocabulary ────────────────────────────────────────────────────────────

    static final String KIND_WORDS = "(main\\s+fund|main\\s+category|sub[- ]?funds?|sub[- ]?categor(?:y|ies)|sub[- ]?sources?|"
        + "(?:income|giving|donation|offering)\\s+(?:funds?|categor(?:y|ies))|expense\\s+funds?|funds?|"
        + "(?:transaction|payment|offering|donation|giving|income)\\s+types?|payment\\s+methods?|methods?\\s+of\\s+payment|"
        + "purposes?|expense\\s+(?:categor(?:y|ies)|purposes?|types?))";
    static final Pattern START = Pattern.compile(
        "(?i)^\\s*(?:please\\s+|can you\\s+|could you\\s+|i\\s+(?:want|need|would like)\\s+to\\s+|i'?d like to\\s+|let'?s\\s+|help me\\s+)?"
        + "(?:create|add|make|set\\s?up|new|register|define|enter|record)\\s+(?:an?\\s+|the\\s+|another\\s+|one\\s+)?(?:new\\s+)?" + KIND_WORDS + "\\b(?!\\s*-?\\s*rais)(.*)$");   // "fund raising …" is not a fund
    static final Pattern PARENT = Pattern.compile("(?i)\\s+(?:under|in|within|inside|beneath)\\s+(?:the\\s+)?(?:main\\s+fund\\s+)?(.+)$");
    static final Pattern NOT_TAX = Pattern.compile("(?i)[,(]?\\s*\\b(?:that\\s+is\\s+|which\\s+is\\s+|it'?s\\s+)?(?:not|non)[- ]?(?:tax[- ]?deductible|deductible|taxable)\\b\\)?");
    static final Pattern NAME_LEAD = Pattern.compile("(?i)^\\s*(?:called|named|titled|with\\s+(?:the\\s+)?name|as|:|-|=|for)?\\s*");
    static final Pattern CORRECT = Pattern.compile(
        "(?i)^\\s*(?:(?:no|oh|sorry|wait|actually)[,!.]?\\s+)?(?:please\\s+)?(?:change|set|update|edit|correct|rename)\\s+(?:the\\s+)?"
        + "(name|title|main\\s+fund|parent(?:\\s+fund)?|fund|tax[- ]?deductible|deductible)\\b\\s*(?:to|into|as|is|:|=)?\\s*(.*)$");
    static final Pattern MAKE_MAIN = Pattern.compile("(?i)^\\s*(?:(?:no|actually)[,!.]?\\s+)?(?:make\\s+it\\s+|it\\s+should\\s+be\\s+|change\\s+it\\s+to\\s+)?(?:a\\s+)?main(?:\\s+fund)?(?:\\s+instead)?[.!]?\\s*$");
    static final Pattern MAKE_SUB = Pattern.compile("(?i)^\\s*(?:(?:no|actually)[,!.]?\\s+)?(?:make\\s+it\\s+|it\\s+should\\s+be\\s+|change\\s+it\\s+to\\s+)?(?:a\\s+)?sub[- ]?fund(?:\\s+instead)?(.*)$");
    static final Pattern RENAME = Pattern.compile("(?i)^\\s*(?:call\\s+it|name\\s+it|rename\\s+it(?:\\s+to)?|it\\s+should\\s+be\\s+called)\\s+(.+)$");
    static final Pattern TAX_NO = Pattern.compile("(?i)^\\s*(?:no|not|non)[- ]?(?:tax[- ]?deductible|deductible)?[.!]?\\s*$|^\\s*(?:it'?s\\s+|make\\s+it\\s+)?(?:not|non)[- ]?(?:tax[- ]?deductible|deductible|taxable)[.!]?\\s*$");
    static final Pattern TAX_YES = Pattern.compile("(?i)^\\s*(?:yes|it'?s\\s+|make\\s+it\\s+)?(?:tax[- ]?deductible|deductible)[.!]?\\s*$");

    public static boolean isStartRequest(String q) { return q != null && START.matcher(q).find(); }
    public static void clear(HttpSession s) { if (s != null) s.removeAttribute(SESSION_KEY); }

    static Kind kindOf(String word) {
        String w = word.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (w.startsWith("main") || w.startsWith("expense fund")) return Kind.FUND;              // expenses are paid from main funds
        if (w.matches("(income|giving|donation|offering) (fund|categor).*")) return Kind.SUB_FUND; // income is recorded to sub-funds
        if (w.matches("(transaction|payment|offering|donation|giving|income) type.*")) return Kind.TYPE;
        if (w.startsWith("sub")) return Kind.SUB_FUND;
        if (w.startsWith("fund")) return Kind.FUND_UNDECIDED;
        if (w.startsWith("purpose") || w.startsWith("expense")) return Kind.PURPOSE;
        return Kind.TYPE;
    }

    // ── entry point ───────────────────────────────────────────────────────────

    /** Null = nothing to do with an Account Settings entry. */
    public Map<String, Object> handle(HttpServletRequest req, String clientId, String question) {
        if (req == null || clientId == null || question == null) return null;
        HttpSession session = req.getSession(false);
        if (session == null) return null;
        String q = question.trim();
        if (q.isEmpty()) return null;
        Draft pending = pending(session, clientId);

        Matcher st = START.matcher(q);
        if (st.find()) {
            if (pending != null && pending.token != null) actions.reject(req, clientId, pending.token);
            clear(session);
            Kind kind = kindOf(st.group(1));
            Map<String, Object> gate = gate(req, clientId, kind);
            if (gate != null) return gate;
            Draft d = new Draft(); d.kind = kind; d.clientId = clientId; d.created = Instant.now();
            fillFromText(d, st.group(2));
            return advance(req, session, d);
        }
        if (pending == null) return null;
        if (pending.token != null && !actions.isOpen(clientId, pending.token)) { clear(session); return null; }   // Saved / cancelled with the card's buttons
        Map<String, Object> gate = gate(req, clientId, pending.kind);
        if (gate != null) { clear(session); return gate; }
        if (CANCEL.matcher(q).matches()) return cancel(req, session, pending);
        if (correct(pending, q)) { dropCard(req, pending); return advance(req, session, pending); }
        if (pending.token != null) {                                         // the card is shown
            if (YES.matcher(q).matches()) {
                String token = pending.token; clear(session);
                Map<String, Object> done = actions.confirm(req, clientId, token);
                if (done != null) done.put("kind", pending.noun());
                return done;
            }
            if (NO.matcher(q).matches()) return cancel(req, session, pending);
            return null;
        }
        return answer(req, session, pending, q);
    }

    private Map<String, Object> gate(HttpServletRequest req, String clientId, Kind kind) {
        String noun = kind == Kind.TYPE ? "transaction type" : kind == Kind.PURPOSE ? "purpose" : "fund";
        AiAccess a = access.resolve(req, clientId);
        boolean allowed = !(a.member() || a.church() || a.kids() || AiAccessPolicy.isTemporaryAccess(req.getSession(false)))
                && AiSettingsTools.mayManageSettings(req);
        if (!allowed) {
            Map<String, Object> m = base("guided.denied", "Your login doesn't have permission to change Account Settings (funds, purposes and transaction types), "
                + "so I can't add a " + noun + ". Nothing was saved.", noun);
            m.put("denied", true);
            return m;
        }
        if (!access.feature(clientId, FEATURE)) return base("guided.unavailable", "Adding a " + noun + " from the AI Search bar is not part of your current plan. "
            + "You can add it under Account Settings.", noun);
        if (!actions.isEnabled()) return base("guided.unavailable", "AI actions are switched off on this server. You can add it under Account Settings.", noun);
        return null;
    }

    // ── reading the text ──────────────────────────────────────────────────────

    /** Name, parent ("under …") and "not tax-deductible" from the start request's remainder. */
    void fillFromText(Draft d, String rest) {
        String r = rest == null ? "" : rest;
        Matcher nt = NOT_TAX.matcher(r);
        if (nt.find()) { d.taxDeductible = false; r = r.substring(0, nt.start()) + " " + r.substring(nt.end()); }
        if (d.kind == Kind.SUB_FUND || d.kind == Kind.FUND_UNDECIDED) {
            Matcher p = PARENT.matcher(r);
            if (p.find()) {
                String parentText = p.group(1).replaceAll("[.!]+$", "").trim();
                r = r.substring(0, p.start());
                d.kind = Kind.SUB_FUND;
                if (!setParent(d, parentText)) d.asked = "parent";
            }
        }
        String n = AiSettingsTools.cleanName(NAME_LEAD.matcher(r).replaceFirst(""));
        if (n != null) d.name = n;
        if (!d.taxDeductible && d.kind == Kind.FUND_UNDECIDED) d.kind = Kind.SUB_FUND;     // only sub-funds carry the tax flag
    }

    /** True when the text names exactly one main fund of this church. */
    private boolean setParent(Draft d, String text) {
        List<Hit> mains = lookup.expenseFunds(d.clientId);
        List<Hit> hits = matchNames(text, mains);
        if (hits.isEmpty()) {
            for (Hit h : mains) if (norm(h.label()).equals(norm(text))) { hits = List.of(h); break; }
        }
        if (hits.size() == 1) { d.parentId = hits.get(0).id(); d.parentLabel = hits.get(0).label(); return true; }
        return false;
    }

    private Map<String, Object> answer(HttpServletRequest req, HttpSession session, Draft d, String q) {
        String t = q.replaceAll("[.!]+$", "").trim();
        String asked = d.asked == null ? "" : d.asked;
        if (QUESTION.matcher(q).find() && !asked.equals("noMain")) return null;        // another question — the draft waits
        switch (asked) {
            case "name" -> {
                Matcher rn = RENAME.matcher(t);
                String raw = rn.matches() ? rn.group(1) : t;
                Draft probe = new Draft(); probe.kind = d.kind; probe.clientId = d.clientId; probe.taxDeductible = d.taxDeductible;
                fillFromText(probe, " " + raw);
                if (probe.name == null) return reask(req, session, d, t);
                d.name = probe.name; d.taxDeductible = probe.taxDeductible;
                if (probe.kind == Kind.SUB_FUND && d.kind == Kind.FUND_UNDECIDED) d.kind = Kind.SUB_FUND;
                if (probe.parentId != null) { d.parentId = probe.parentId; d.parentLabel = probe.parentLabel; }
            }
            case "level" -> {
                String l = t.toLowerCase(Locale.ROOT);
                if (l.startsWith("main")) d.kind = Kind.FUND;
                else if (l.startsWith("sub") || l.contains("under")) {
                    d.kind = Kind.SUB_FUND;
                    Matcher p = PARENT.matcher(" " + t);
                    if (p.find()) setParent(d, p.group(1));
                } else return reask(req, session, d, t);
            }
            case "parent" -> {
                String picked = d.choices == null ? null : AiActionService.pick(t, d.choices);
                if (!setParent(d, picked != null ? picked : t)) return reask(req, session, d, t);
            }
            case "noMain" -> {
                String l = t.toLowerCase(Locale.ROOT);
                if (l.contains("main")) { d.kind = Kind.FUND; d.parentId = null; d.parentLabel = null; }
                else if (NO.matcher(q).matches() || l.contains("cancel")) return cancel(req, session, d);
                else return null;
            }
            case "tax" -> {
                if (TAX_NO.matcher(t).matches() || NO.matcher(q).matches()) d.taxDeductible = false;
                else if (TAX_YES.matcher(t).matches() || YES.matcher(q).matches()) d.taxDeductible = true;
                else return reask(req, session, d, t);
            }
            default -> { return null; }
        }
        d.asked = null; d.choices = null;
        return advance(req, session, d);
    }

    private Map<String, Object> reask(HttpServletRequest req, HttpSession session, Draft d, String t) {
        Map<String, Object> m = advance(req, session, d);
        m.put("answer", "I couldn't use “" + cap(t, 60) + "”. " + m.get("answer"));
        return m;
    }

    /** "change the name to …", "rename it to …", "make it a main fund / sub-fund", "change the main fund to …", "not tax-deductible". */
    boolean correct(Draft d, String text) {
        String t = text.replaceAll("[.!]+$", "").trim();
        boolean fundish = d.kind == Kind.FUND || d.kind == Kind.SUB_FUND || d.kind == Kind.FUND_UNDECIDED;
        if (fundish && MAKE_MAIN.matcher(t).matches()) { d.kind = Kind.FUND; d.parentId = null; d.parentLabel = null; d.taxDeductible = true; d.asked = null; return true; }
        Matcher ms = MAKE_SUB.matcher(t);
        if (fundish && ms.matches() && !"level".equals(d.asked)) {
            d.kind = Kind.SUB_FUND; d.asked = null;
            Matcher p = PARENT.matcher(ms.group(1) == null ? "" : ms.group(1));
            if (p.find()) setParent(d, p.group(1));
            return true;
        }
        if (d.kind == Kind.SUB_FUND && d.carded && (TAX_NO.matcher(t).matches() && !NO.matcher(t).matches())) { d.taxDeductible = false; return true; }
        Matcher rn = RENAME.matcher(t);
        if (rn.matches() && !"name".equals(d.asked)) { d.name = AiSettingsTools.cleanName(rn.group(1)); d.asked = d.name == null ? "name" : null; return true; }
        Matcher c = CORRECT.matcher(t);
        if (!c.matches()) return false;
        String field = c.group(1).toLowerCase(Locale.ROOT), value = c.group(2).trim();
        if (field.startsWith("name") || field.startsWith("title")) {
            d.name = value.isEmpty() ? null : AiSettingsTools.cleanName(value);
            d.asked = d.name == null ? "name" : null;
        } else if (field.startsWith("tax") || field.startsWith("deductible")) {
            if (d.kind != Kind.SUB_FUND) return false;
            if (value.isEmpty()) { d.asked = "tax"; return true; }
            d.taxDeductible = !(value.toLowerCase(Locale.ROOT).matches("(no|not|false|non).*"));
            d.asked = null;
        } else {                                                                     // main fund / parent / fund
            if (!fundish) return false;
            d.kind = Kind.SUB_FUND; d.parentId = null; d.parentLabel = null;
            d.asked = value.isEmpty() || !setParent(d, value) ? "parent" : null;
        }
        return true;
    }

    // ── next step ─────────────────────────────────────────────────────────────

    Map<String, Object> advance(HttpServletRequest req, HttpSession session, Draft d) {
        d.created = Instant.now();
        if (d.name == null) return ask(session, d, "name", "What should the new " + d.noun() + " be called?", null);
        if (d.kind == Kind.FUND_UNDECIDED) {
            return ask(session, d, "level", "Should “" + d.name + "” be a main fund, or a sub-fund under one of your main funds? "
                + "(Income entries are recorded to sub-funds; expenses are paid from main funds.)", List.of("Main fund", "Sub-fund"));
        }
        if (d.kind == Kind.SUB_FUND && d.parentId == null) {
            List<String> mains = labels(lookup.expenseFunds(d.clientId));
            if (mains.isEmpty()) return ask(session, d, "noMain", "There are no main funds yet, so “" + d.name + "” can't be a sub-fund. "
                + "Should I make it a main fund instead?", List.of("Make it a main fund", "Cancel"));
            List<String> ch = mains.size() > 20 ? new ArrayList<>(mains.subList(0, 20)) : mains;
            return ask(session, d, "parent", "Which main fund should “" + d.name + "” go under? " + String.join(", ", ch) + (mains.size() > ch.size() ? " …" : "") + ".", ch);
        }
        return card(req, session, d);
    }

    private Map<String, Object> card(HttpServletRequest req, HttpSession session, Draft d) {
        AiAccess a = access.resolve(req, d.clientId);
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", d.name);
        String tool = switch (d.kind) { case TYPE -> "create_transaction_type"; case PURPOSE -> "create_purpose"; case FUND -> "create_fund"; default -> "create_sub_fund"; };
        if (d.kind == Kind.SUB_FUND) { raw.put("parentId", d.parentId); raw.put("taxDeductible", d.taxDeductible); }
        Map<String, Object> p = actions.proposeGuided(req, a, d.clientId, tool, raw);
        if (p.get("token") == null) {
            if (Boolean.TRUE.equals(p.get("denied"))) {
                clear(session);
                Map<String, Object> m = base("guided.denied", p.get("refusal") + " Nothing was saved.", d.noun()); m.put("denied", true); return m;
            }
            String why = String.valueOf(p.get("refusal"));
            if (why.contains("already exists")) {                               // keep the conversation: ask for another name
                d.name = null;
                return ask(session, d, "name", why + " What name should I use instead?", null);
            }
            clear(session);
            return base("guided.refused", why + " Nothing was saved.", d.noun());
        }
        d.token = String.valueOf(p.get("token")); d.asked = null; d.choices = null; d.carded = true; d.created = Instant.now();
        session.setAttribute(SESSION_KEY, d);
        String label = switch (d.kind) { case TYPE -> "Save transaction type"; case PURPOSE -> "Save purpose"; case FUND -> "Save fund"; default -> "Save sub-fund"; };
        Map<String, Object> m = base("guided.confirm", "Please review the new " + d.noun() + ". It is added under Account Settings only when you press "
            + label + " (or say “yes”).", d.noun());
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("token", d.token); c.put("proposalId", p.get("proposalId")); c.put("label", label);
        c.put("endpoint", "/api/ai/actions/confirm"); c.put("cancelEndpoint", "/api/ai/actions/reject");
        List<Map<String, Object>> fields = new ArrayList<>();
        String what = switch (d.kind) { case TYPE -> "Transaction type"; case PURPOSE -> "Purpose (expense category)"; case FUND -> "Main fund"; default -> "Sub-fund"; };
        fields.add(field("name", what, d.name, true, "Change name to "));
        if (d.kind == Kind.FUND) fields.add(field("level", "Fund level", "Main fund", true, "Make it a sub-fund under "));
        if (d.kind == Kind.SUB_FUND) {
            fields.add(field("parent", "Main fund", d.parentLabel, true, "Change main fund to "));
            fields.add(field("tax", "Tax-deductible", d.taxDeductible ? "Yes" : "No", false, "Change tax-deductible to "));
        }
        Map<String, String> summary = new LinkedHashMap<>();
        for (Map<String, Object> f : fields) summary.put(String.valueOf(f.get("label")), String.valueOf(f.get("value")));
        c.put("summary", summary); c.put("fields", fields);
        m.put("confirm", c); m.put("pending", true);
        return m;
    }

    private Map<String, Object> ask(HttpSession session, Draft d, String field, String question, List<String> choices) {
        d.asked = field; d.choices = choices == null ? null : new ArrayList<>(choices); d.created = Instant.now();
        session.setAttribute(SESSION_KEY, d);
        Map<String, Object> m = base("guided.ask", question, d.noun());
        m.put("asking", field); m.put("pending", true);
        if (choices != null && !choices.isEmpty()) m.put("choices", choices);
        return m;
    }

    private Map<String, Object> cancel(HttpServletRequest req, HttpSession session, Draft d) {
        dropCard(req, d); clear(session);
        return base("guided.cancelled", "Okay — I have not added the " + d.noun() + ".", d.noun());
    }
    private void dropCard(HttpServletRequest req, Draft d) {
        if (d.token != null) { try { actions.reject(req, d.clientId, d.token); } catch (RuntimeException ignore) { } }
        d.token = null;
    }
    private Draft pending(HttpSession session, String clientId) {
        Object o = session.getAttribute(SESSION_KEY);
        if (!(o instanceof Draft d)) return null;
        if (!clientId.equals(d.clientId) || d.created == null || d.created.plusSeconds(60L * Math.max(1, draftMinutes)).isBefore(Instant.now())) {
            session.removeAttribute(SESSION_KEY); return null;
        }
        return d;
    }
    private static Map<String, Object> field(String key, String label, String value, boolean required, String edit) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("key", key); f.put("label", label); f.put("value", value); f.put("required", required); f.put("edit", edit);
        return f;
    }
}
