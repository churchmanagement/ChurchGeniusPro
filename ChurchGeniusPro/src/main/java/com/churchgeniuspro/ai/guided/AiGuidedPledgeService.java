package com.churchgeniuspro.ai.guided;

import com.churchgeniuspro.ai.action.AiActionLookup.Hit;
import com.churchgeniuspro.ai.action.AiActionService;
import com.churchgeniuspro.ai.action.AiFinanceLookup;
import com.churchgeniuspro.ai.action.AiPledgeTools;
import com.churchgeniuspro.ai.action.AiSettingsTools;
import com.churchgeniuspro.hibernate.PledgeCampaign;
import com.churchgeniuspro.repository.PledgeCampaignRepository;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.util.AppClock;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.churchgeniuspro.ai.guided.AiGuidedRecordService.*;

/**
 * 2026-11-11 — Genius Assistant conversation for a pledge campaign: "Create a pledge campaign", "Start a
 * campaign called Building Fund 2027 with a target of $50,000 ending December 31, 2027".
 *
 * <p>Same shape as the meeting / income / expense conversations: the one required field (the campaign name) is
 * asked when missing; then ONE question offers the optional fields (fund, target amount, end date, description,
 * other non-cash gifts) — each can be added or all skipped; then the review card; the user's Save runs
 * {@link AiActionService#confirm} → {@code create_pledge_campaign} (the Pledges page's own row, status Active).
 * Corrections ("change the target to 60,000", "change the fund", "remove the end date") and "cancel" at every
 * step. Rules only — no model call. Permission = the Pledges page's write rule (SuperAdmin / Admin / Accountant,
 * {@code accounting.pledges} + {@code accounting.pledges.edit}), checked first and on every message; plan
 * features {@code pledges} and {@code aiActions}.
 *
 * <p>A request to add ONE PERSON'S pledge ("add a pledge of $500 for John") is not created here — the answer
 * links to the Pledges page.
 */
@Service
public class AiGuidedPledgeService {

    public static final String SESSION_KEY = GuidedDrafts.PLEDGE;
    static final String KIND = "pledge campaign";
    static final DateTimeFormatter SHOW = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US);

    private final AiAccessPolicy access;
    private final AiActionService actions;
    private final AiFinanceLookup funds;
    private final PledgeCampaignRepository campaigns;

    @Value("${ai.guided.draft-minutes:15}")
    public int draftMinutes = 15;

    public AiGuidedPledgeService(AiAccessPolicy access, AiActionService actions, AiFinanceLookup funds, PledgeCampaignRepository campaigns) {
        this.access = access; this.actions = actions; this.funds = funds; this.campaigns = campaigns;
    }

    static final class Draft implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        String clientId; Instant created;
        String name; boolean dupAccepted;
        Long fundId; String fundLabel; String target; String endDate; String description; String otherGifts;
        String asked; ArrayList<String> choices; String token; boolean optionalDone;
    }

    // ── vocabulary ────────────────────────────────────────────────────────────

    static final String LEAD = AiGuidedGroupService.LEAD;
    static final Pattern START = Pattern.compile("(?i)^\\s*" + LEAD
        + "(?:create|add|make|new|set\\s?up|start|launch|open|begin|register)\\s+(?:an?\\s+|the\\s+|another\\s+|one\\s+)?(?:new\\s+)?"
        + "(pledge\\s+(?:campaign|drive)s?|(?:fund\\s*rais(?:ing|er)|capital|giving|stewardship|building)\\s+campaigns?|campaigns?|pledges?)\\b(.*)$");
    /** "add a pledge for John", "add a pledge of $500 from Mary" — one person's pledge, not a campaign. */
    static final Pattern MEMBER_PLEDGE = Pattern.compile("(?i)^\\s*(?:(?:of|for)\\s+\\$?\\d|for\\s+|from\\s+|by\\s+|to\\s+(?:the\\s+)?\\S)");
    static final Pattern NAME_LEAD = Pattern.compile("(?i)^\\s*(?:called|named|titled|with\\s+(?:the\\s+)?name|as|:|-|=)?\\s*");
    static final Pattern TARGET = Pattern.compile("(?i)[,;]?\\s*\\b(?:with\\s+)?(?:an?\\s+|the\\s+)?(?:target|goal)(?:\\s+amount)?\\s*(?:of|is|:|=)?\\s*(\\$?\\s*\\d[\\d,]*(?:\\.\\d{1,2})?)\\s*(?:dollars?)?");
    static final Pattern END = Pattern.compile("(?i)[,;]?\\s*\\b(?:that\\s+)?(?:ending|ends|end\\s+date(?:\\s+(?:of|is))?|until|through|running\\s+(?:until|through))\\s+(?:on\\s+)?(.+?)(?=[,;]|\\s+(?:with|for\\s+the|target|goal)\\b|$)");
    static final Pattern FUND_PART = Pattern.compile("(?i)[,;]?\\s*\\b(?:for|to|into|toward|towards|using|under)\\s+(?:the\\s+|our\\s+)?(.+?)\\s*(?:fund)?\\s*(?=[,;]|\\s+(?:with|ending|until|target|goal)\\b|$)");
    static final Pattern CORRECT = Pattern.compile(
        "(?i)^\\s*(?:(?:no|oh|sorry|wait|actually)[,!.]?\\s+)?(?:please\\s+)?(change|set|update|edit|correct|add|remove|clear|delete)\\s+(?:the\\s+|an?\\s+)?(?:campaign\\s+)?"
        + "(name|title|fund|target(?:\\s+amount)?|goal|end\\s+date|end|date|description|other\\s+gifts|gifts)\\b\\s*(?:to|as|is|:|=)?\\s*(.*)$");
    static final Pattern SKIP = Pattern.compile(
        "(?i)^\\s*(skip|skip (it|them|all|these)|skip and review|skip to review|no|nope|none|nothing|nothing else|no thanks|that'?s (all|it|everything)|review|review it|"
        + "ready|i'?m ready|done|continue|proceed|proceed to review|go ahead|next)[.!]?\\s*$");
    static final Pattern HAS_YEAR = Pattern.compile("\\b(19|20)\\d{2}\\b|\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b");
    static final Pattern WEEKDAY = Pattern.compile("(?i)\\b(sun|mon|tue|wed|thu|fri|sat)[a-z]*\\b");

    public static boolean isStartRequest(String q) { return q != null && START.matcher(q).find(); }
    public static void clear(HttpSession s) { if (s != null) s.removeAttribute(SESSION_KEY); }

    // ── entry point ───────────────────────────────────────────────────────────

    public Map<String, Object> handle(HttpServletRequest req, String clientId, String question) {
        if (req == null || clientId == null || question == null) return null;
        HttpSession session = req.getSession(false);
        if (session == null) return null;
        String q = question.trim();
        if (q.isEmpty()) return null;
        Draft pending = pending(session, clientId);

        Matcher st = START.matcher(q);
        if (st.find()) {
            String word = st.group(1).toLowerCase(Locale.ROOT), rest = st.group(2) == null ? "" : st.group(2);
            dropCard(req, pending); clear(session);
            Map<String, Object> gate = gate(req, clientId);
            if (gate != null) return gate;
            if (word.matches("pledges?") && MEMBER_PLEDGE.matcher(rest).find()) {
                Map<String, Object> m = base("page.link", "I can create a pledge campaign here, but one person's pledge is added on the Pledges page: "
                    + "open the campaign and choose Add Pledge. Nothing was saved.", KIND);
                m.put("link", new LinkedHashMap<>(Map.of("label", "Open Pledges", "url", "/pledges")));
                m.put("readOnly", true);
                return m;
            }
            Draft d = new Draft(); d.clientId = clientId; d.created = Instant.now();
            fillFromText(d, rest);
            return advance(req, session, d);
        }
        if (pending == null) return null;
        if (pending.token != null && !actions.isOpen(clientId, pending.token)) { clear(session); return null; }
        Map<String, Object> gate = gate(req, clientId);
        if (gate != null) { dropCard(req, pending); clear(session); return gate; }
        if (CANCEL.matcher(q).matches()) return cancel(req, session, pending);
        if (correct(pending, q)) { dropCard(req, pending); return advance(req, session, pending); }
        if (pending.token != null) {
            if (YES.matcher(q).matches()) {
                String token = pending.token; clear(session);
                Map<String, Object> done = actions.confirm(req, clientId, token);
                if (done != null) done.put("kind", KIND);
                return done;
            }
            if (NO.matcher(q).matches()) return cancel(req, session, pending);
            return null;
        }
        return answer(req, session, pending, q);
    }

    private Map<String, Object> gate(HttpServletRequest req, String clientId) {
        AiAccess a = access.resolve(req, clientId);
        boolean allowed = !(a.member() || a.church() || a.kids() || AiAccessPolicy.isTemporaryAccess(req.getSession(false)))
                && AiPledgeTools.mayEditPledges(req);
        if (!allowed) {
            Map<String, Object> m = base("guided.denied", "Your login doesn't have permission to add pledge campaigns, so I can't do that. "
                + "Nothing was saved. Please ask your church administrator.", KIND);
            m.put("denied", true);
            return m;
        }
        if (!access.feature(clientId, "pledges")) return base("guided.unavailable", "Pledges are not part of your church's current plan.", KIND);
        if (!access.feature(clientId, FEATURE) || !actions.isEnabled()) {
            Map<String, Object> m = base("guided.unavailable", "Creating a campaign from Genius Assistant is not available on your current plan. You can add it on the Pledges page.", KIND);
            m.put("link", new LinkedHashMap<>(Map.of("label", "Open Pledges", "url", "/pledges")));
            return m;
        }
        return null;
    }

    // ── reading the text ──────────────────────────────────────────────────────

    /** Name, "target of $…", "ending <date>" and "for the <fund>" from the start request's remainder. */
    void fillFromText(Draft d, String rest) {
        String r = rest == null ? "" : rest.replaceAll("[.!]+\\s*$", "");
        LocalDate today = AppClock.today();
        Matcher t = TARGET.matcher(r);
        if (t.find()) { BigDecimal a = number(t.group(1)); if (okTarget(a)) d.target = a.toPlainString(); r = r.substring(0, t.start()) + " " + r.substring(t.end()); }
        Matcher e = END.matcher(r);
        if (e.find()) { LocalDate end = futureDate(e.group(1), today); if (end != null && !end.isBefore(today)) d.endDate = end.toString(); r = r.substring(0, e.start()) + " " + r.substring(e.end()); }
        String named = NAME_LEAD.matcher(r).replaceFirst("");
        boolean explicit = named.length() != r.trim().length() && !r.trim().isEmpty() && r.trim().matches("(?i)^(called|named|titled|with|as|:|-|=).*");
        if (!explicit) {                                                   // "… campaign for the Building Fund": a fund, not a name
            Matcher f = FUND_PART.matcher(r);
            if (f.find() && setFund(d, f.group(1))) r = r.substring(0, f.start()) + " " + r.substring(f.end());
            named = NAME_LEAD.matcher(r).replaceFirst("");
        } else {
            Matcher f = FUND_PART.matcher(named);
            if (f.find() && f.start() > 0 && setFund(d, f.group(1))) named = named.substring(0, f.start()) + " " + named.substring(f.end());
        }
        String n = AiSettingsTools.cleanName(named.replaceAll("[,;]+\\s*$", ""));
        if (n != null && n.length() <= AiPledgeTools.MAX_NAME) d.name = n;
    }

    private boolean setFund(Draft d, String text) {
        List<Hit> all = funds.incomeFunds(d.clientId);
        List<Hit> hits = matchNames(text, all);
        if (hits.isEmpty()) for (Hit h : all) if (norm(h.label()).equals(norm(text))) { hits = List.of(h); break; }
        if (hits.size() == 1) { d.fundId = hits.get(0).id(); d.fundLabel = hits.get(0).label(); return true; }
        return false;
    }

    static boolean okTarget(BigDecimal a) { return a != null && a.signum() > 0 && a.compareTo(AiPledgeTools.MAX_TARGET) <= 0; }

    /** A date meant to be ahead: without a year it is the next such day, not the last one. Null when unreadable. */
    static LocalDate futureDate(String text, LocalDate today) {
        if (text == null) return null;
        Parsed p = parseDate(text, today);
        if (p == null) return null;
        LocalDate d = p.date();
        if (d.isBefore(today) && !HAS_YEAR.matcher(text).find()) {
            if (WEEKDAY.matcher(text).find()) { while (d.isBefore(today)) d = d.plusWeeks(1); }
            else d = d.plusYears(1);
        }
        return d;
    }

    private Map<String, Object> answer(HttpServletRequest req, HttpSession session, Draft d, String q) {
        String t = q.replaceAll("[.!]+$", "").trim();
        String asked = d.asked == null ? "" : d.asked;
        LocalDate today = AppClock.today();
        switch (asked) {
            case "name" -> {
                if (q.endsWith("?") && QUESTION.matcher(q).find()) return null;
                Draft probe = new Draft(); probe.clientId = d.clientId;
                fillFromText(probe, " " + t);
                if (probe.name == null) return ask(session, d, "name", "I couldn't use that as a name. What should the campaign be called?", null);
                d.name = probe.name; d.dupAccepted = false;
                if (probe.target != null) d.target = probe.target;
                if (probe.endDate != null) d.endDate = probe.endDate;
                if (probe.fundId != null) { d.fundId = probe.fundId; d.fundLabel = probe.fundLabel; }
            }
            case "duplicate" -> {
                if (YES.matcher(q).matches() || t.toLowerCase(Locale.ROOT).startsWith("create")) d.dupAccepted = true;
                else if (NO.matcher(q).matches() || t.toLowerCase(Locale.ROOT).contains("different")) { d.name = null; }
                else { d.name = AiSettingsTools.cleanName(t); d.dupAccepted = false; if (d.name == null) return ask(session, d, "name", "What should the campaign be called?", null); }
            }
            case "optional" -> {
                String l = t.toLowerCase(Locale.ROOT);
                String picked = d.choices == null ? null : AiActionService.pick(t, d.choices);
                if (picked != null) l = picked.toLowerCase(Locale.ROOT);
                else if (!l.matches("(?:yes,?\\s+)?(?:add|set|enter|include)?\\s*(?:an?\\s+|the\\s+)?(?:fund|target(?: amount)?|goal|amount|end date|date|description|notes?|other gifts|gifts)")
                         && !SKIP.matcher(q).matches()) {
                    if (QUESTION.matcher(q).find()) return null;
                    return reask(req, session, d, t);                     // anything else is not an answer to this question
                }
                if (SKIP.matcher(q).matches() || l.startsWith("skip")) d.optionalDone = true;
                else if (l.contains("fund")) return askFund(session, d);
                else if (l.contains("target") || l.contains("goal") || l.contains("amount")) return ask(session, d, "target", "What is the target amount? (for example $50,000)", null);
                else if (l.contains("date") || l.contains("end")) return ask(session, d, "end", "When does the campaign end? (for example December 31, 2027)", null);
                else if (l.contains("description") || l.contains("note") || l.contains("detail")) return ask(session, d, "description", "What should the description say?", null);
                else if (l.contains("gift") || l.contains("non-cash") || l.contains("other")) return ask(session, d, "other", "Which other (non-cash) gifts should be noted? (for example: sound system setup, volunteer hours)", null);
                else if (QUESTION.matcher(q).find()) return null;
                else return reask(req, session, d, t);
            }
            case "fund" -> {
                if (SKIP.matcher(q).matches()) { d.fundId = null; d.fundLabel = null; }
                else {
                    String picked = d.choices == null ? null : AiActionService.pick(t, d.choices);
                    if (!setFund(d, picked != null ? picked : t)) return reask(req, session, d, t);
                }
            }
            case "target" -> {
                if (SKIP.matcher(q).matches()) d.target = null;
                else {
                    BigDecimal a = amountOf(t, true);
                    if (!okTarget(a)) return ask(session, d, "target", "The target must be an amount above zero, such as $50,000. What is the target amount? (or say “skip”)", null);
                    d.target = a.toPlainString();
                }
            }
            case "end" -> {
                if (SKIP.matcher(q).matches()) d.endDate = null;
                else {
                    LocalDate end = futureDate(t, today);
                    if (end == null) return ask(session, d, "end", "I couldn't read that as a date. When does the campaign end? (for example December 31, 2027, or say “skip”)", null);
                    if (end.isBefore(today)) return ask(session, d, "end", end.format(SHOW) + " is already past. Which end date should I use? (or say “skip”)", null);
                    d.endDate = end.toString();
                }
            }
            case "description" -> d.description = SKIP.matcher(q).matches() ? null : cap(t, AiPledgeTools.MAX_DESCRIPTION);
            case "other" -> d.otherGifts = SKIP.matcher(q).matches() ? null : cap(t, AiPledgeTools.MAX_OTHER);
            default -> { return null; }
        }
        d.asked = null; d.choices = null;
        return advance(req, session, d);
    }

    private Map<String, Object> reask(HttpServletRequest req, HttpSession session, Draft d, String t) {
        String keep = d.asked;
        Map<String, Object> m = "fund".equals(keep) ? askFund(session, d) : advance(req, session, d);
        m.put("answer", "I couldn't use “" + cap(t, 60) + "”. " + m.get("answer"));
        return m;
    }

    /** "change the name to …", "change the target to 60000", "change the fund", "remove the end date" … */
    boolean correct(Draft d, String text) {
        String t = text.replaceAll("[.!]+$", "").trim();
        Matcher c = CORRECT.matcher(t);
        if (!c.matches()) return false;
        String verb = c.group(1).toLowerCase(Locale.ROOT), field = c.group(2).toLowerCase(Locale.ROOT).replaceAll("\\s+", " "), value = c.group(3).trim();
        boolean remove = verb.matches("remove|clear|delete");
        LocalDate today = AppClock.today();
        if (field.startsWith("name") || field.startsWith("title")) {
            if (remove) return false;
            d.name = value.isEmpty() ? null : AiSettingsTools.cleanName(value); d.dupAccepted = false; d.asked = d.name == null ? "name" : null;
        } else if (field.startsWith("fund")) {
            if (remove) { d.fundId = null; d.fundLabel = null; d.asked = null; }
            else if (value.isEmpty() || !setFund(d, value)) { d.asked = "fund"; }
            else d.asked = null;
        } else if (field.startsWith("target") || field.startsWith("goal")) {
            if (remove) { d.target = null; d.asked = null; }
            else { BigDecimal a = value.isEmpty() ? null : amountOf(value, true); if (okTarget(a)) { d.target = a.toPlainString(); d.asked = null; } else d.asked = "target"; }
        } else if (field.startsWith("end") || field.startsWith("date")) {
            if (remove) { d.endDate = null; d.asked = null; }
            else { LocalDate end = value.isEmpty() ? null : futureDate(value, today); if (end != null && !end.isBefore(today)) { d.endDate = end.toString(); d.asked = null; } else d.asked = "end"; }
        } else if (field.startsWith("description")) {
            if (remove) { d.description = null; d.asked = null; } else if (value.isEmpty()) d.asked = "description"; else { d.description = cap(value, AiPledgeTools.MAX_DESCRIPTION); d.asked = null; }
        } else {
            if (remove) { d.otherGifts = null; d.asked = null; } else if (value.isEmpty()) d.asked = "other"; else { d.otherGifts = cap(value, AiPledgeTools.MAX_OTHER); d.asked = null; }
        }
        return true;
    }

    // ── next step ─────────────────────────────────────────────────────────────

    Map<String, Object> advance(HttpServletRequest req, HttpSession session, Draft d) {
        d.created = Instant.now();
        if (d.name == null) return ask(session, d, "name", "What should the pledge campaign be called? (for example: Building Fund 2027)", null);
        if (!d.dupAccepted && exists(d.clientId, d.name)) return ask(session, d, "duplicate",
                "A campaign called “" + d.name + "” already exists. Do you want to create another one with the same name, or use a different name?",
                List.of("Create anyway", "Use a different name"));
        if ("fund".equals(d.asked)) return askFund(session, d);
        if ("target".equals(d.asked)) return ask(session, d, "target", "What is the target amount? (for example $50,000)", null);
        if ("end".equals(d.asked)) return ask(session, d, "end", "When does the campaign end? (for example December 31, 2027)", null);
        if ("description".equals(d.asked)) return ask(session, d, "description", "What should the description say?", null);
        if ("other".equals(d.asked)) return ask(session, d, "other", "Which other (non-cash) gifts should be noted?", null);
        List<String> open = openOptional(d);
        if (!d.optionalDone && !open.isEmpty()) {
            List<String> ch = new ArrayList<>(open); ch.add("Skip and review");
            boolean none = open.size() == optionalCount(d);
            return ask(session, d, "optional", (none
                    ? "The campaign name is all that is required. You can also add optional details — a fund (gifts to it are credited to pledges automatically), a target amount, an end date, a description or other non-cash gifts. "
                    : "Got it. ") + "Would you like to add " + (none ? "any of these" : "anything else") + ", or skip and review?", ch);
        }
        return card(req, session, d);
    }

    private int optionalCount(Draft d) { return funds.incomeFunds(d.clientId).isEmpty() ? 4 : 5; }

    private List<String> openOptional(Draft d) {
        List<String> o = new ArrayList<>();
        if (d.fundId == null && !funds.incomeFunds(d.clientId).isEmpty()) o.add("Add a fund");
        if (d.target == null) o.add("Add a target amount");
        if (d.endDate == null) o.add("Add an end date");
        if (d.description == null) o.add("Add a description");
        if (d.otherGifts == null) o.add("Add other gifts");
        return o;
    }

    private Map<String, Object> askFund(HttpSession session, Draft d) {
        List<String> names = labels(funds.incomeFunds(d.clientId));
        if (names.isEmpty()) { d.asked = null; return ask(session, d, "optional", "There are no income funds yet, so a fund can't be linked. Would you like to add anything else, or skip and review?",
                List.of("Add a target amount", "Add an end date", "Skip and review")); }
        List<String> ch = names.size() > 20 ? new ArrayList<>(names.subList(0, 20)) : new ArrayList<>(names);
        return ask(session, d, "fund", "Which fund should the campaign use? " + String.join(", ", ch) + (names.size() > ch.size() ? " …" : "") + ".", ch);
    }

    private boolean exists(String cid, String name) {
        for (PledgeCampaign c : campaigns.findByClientId(cid)) if (c.getName() != null && c.getName().trim().equalsIgnoreCase(name.trim())) return true;
        return false;
    }

    private Map<String, Object> card(HttpServletRequest req, HttpSession session, Draft d) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("name", d.name);
        if (d.fundId != null) raw.put("fundId", d.fundId);
        if (d.target != null) raw.put("targetAmount", d.target);
        if (d.endDate != null) raw.put("endDate", d.endDate);
        if (d.description != null) raw.put("description", d.description);
        if (d.otherGifts != null) raw.put("otherGifts", d.otherGifts);
        Map<String, Object> p = actions.proposeGuided(req, access.resolve(req, d.clientId), d.clientId, "create_pledge_campaign", raw);
        if (p.get("token") == null) {
            clear(session);
            Map<String, Object> m = base(Boolean.TRUE.equals(p.get("denied")) ? "guided.denied" : "guided.refused", p.get("refusal") + " Nothing was saved.", KIND);
            if (Boolean.TRUE.equals(p.get("denied"))) m.put("denied", true);
            return m;
        }
        d.token = String.valueOf(p.get("token")); d.asked = null; d.choices = null; d.optionalDone = true; d.created = Instant.now();
        session.setAttribute(SESSION_KEY, d);
        Map<String, Object> m = base("guided.confirm", "Please review the pledge campaign. It is created only when you press Save campaign (or say “yes”). Press Edit on any row to change it.", KIND);
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(AiGuidedGroupService.field("name", "Campaign name", d.name, true, "Change the name to "));
        Map<String, Object> fund = AiGuidedGroupService.field("fund", "Fund", d.fundLabel, false, "Change the fund to "); fund.put("ask", "Change the fund"); fields.add(fund);
        fields.add(AiGuidedGroupService.field("target", "Target amount", d.target == null ? null : "$" + String.format(Locale.US, "%,.2f", new BigDecimal(d.target)), false, "Change the target to "));
        fields.add(AiGuidedGroupService.field("end", "End date", d.endDate == null ? null : LocalDate.parse(d.endDate).format(SHOW), false, "Change the end date to "));
        fields.add(AiGuidedGroupService.field("description", "Description", d.description, false, "Change the description to "));
        fields.add(AiGuidedGroupService.field("other", "Other gifts (non-cash)", d.otherGifts, false, "Change the other gifts to "));
        fields.add(AiGuidedGroupService.field("status", "Status", "Active", true, null));
        m.put("confirm", AiGuidedGroupService.card(d.token, p.get("proposalId"), "Save campaign", fields)); m.put("pending", true);
        return m;
    }

    private Map<String, Object> ask(HttpSession session, Draft d, String field, String question, List<String> choices) {
        d.asked = field; d.choices = choices == null ? null : new ArrayList<>(choices); d.created = Instant.now();
        session.setAttribute(SESSION_KEY, d);
        Map<String, Object> m = base("guided.ask", question, KIND);
        m.put("asking", field); m.put("pending", true);
        if (choices != null && !choices.isEmpty()) m.put("choices", choices);
        return m;
    }

    private Map<String, Object> cancel(HttpServletRequest req, HttpSession session, Draft d) {
        dropCard(req, d); clear(session);
        return base("guided.cancelled", "Okay — I have not created the pledge campaign.", KIND);
    }
    private void dropCard(HttpServletRequest req, Draft d) {
        if (d == null || d.token == null) return;
        try { actions.reject(req, d.clientId, d.token); } catch (RuntimeException ignore) { }
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
}
