package com.churchgeniuspro.ai.action;

import com.churchgeniuspro.ai.action.AiTool.Resolution;
import com.churchgeniuspro.hibernate.AiActionProposal;
import com.churchgeniuspro.repository.AiActionProposalRepository;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.AiAccessPolicy.CrossAccess;
import com.churchgeniuspro.service.OpenAiVoiceService;
import com.churchgeniuspro.util.AppClock;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI Phase 4 — the action agent: <b>propose → confirm → execute</b>, never anything else.
 * <ul>
 *   <li>{@link #handle} reads a staff message. Six deterministic patterns map the common phrasings to a tool;
 *       otherwise, when the message looks like a request to DO something and the LLM fallback is on, gpt-4o-mini
 *       picks a tool + parameters from the closed registry (it never executes anything). A missing / ambiguous
 *       name becomes a question (the draft waits in the session for the answer); a complete request becomes a
 *       stored {@code ai_action_proposal} (PROPOSED, one-time token, TTL) and a confirm card.</li>
 *   <li>{@link #confirm} consumes the token: same church, same user, not expired, permission re-checked, every
 *       referenced row re-resolved inside the tenant, then the tool runs in one transaction → EXECUTED (or FAILED,
 *       nothing written). "Yes" / "no" typed or spoken while a proposal is open confirm / reject it.</li>
 * </ul>
 * Kill switch {@code ai.actions.enabled}; plan feature {@code aiActions}; Member / Kids / temp / church sessions
 * never get here (the AI Search route refuses them first). No tool sends anything.
 */
@Slf4j
@Service
public class AiActionService {

    public static final String FEATURE = "aiActions";
    public static final String SESSION_DRAFT = "aiActionDraft", SESSION_PENDING = "aiActionPendingToken";
    static final ObjectMapper JSON = new ObjectMapper();
    static final DateTimeFormatter D = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy");

    private final AiToolRegistry registry;
    private final AiActionProposalRepository proposals;
    private final AiAccessPolicy access;
    private final OpenAiVoiceService openAi;
    private final TransactionTemplate tx;
    private final boolean enabled, llmFallback;
    private final int ttlMinutes;

    @Autowired
    public AiActionService(AiToolRegistry registry, AiActionProposalRepository proposals, AiAccessPolicy access,
                           OpenAiVoiceService openAi, TransactionTemplate tx,
                           @Value("${ai.actions.enabled:true}") boolean enabled,
                           @Value("${ai.actions.llm-fallback:true}") boolean llmFallback,
                           @Value("${ai.actions.ttl-minutes:10}") int ttlMinutes) {
        this.registry = registry; this.proposals = proposals; this.access = access; this.openAi = openAi; this.tx = tx;
        this.enabled = enabled; this.llmFallback = llmFallback; this.ttlMinutes = ttlMinutes;
    }

    public boolean isEnabled() { return enabled; }

    /* ═══════════════════════════ vocabulary ═══════════════════════════ */

    static final Pattern YES = Pattern.compile("^\\s*(yes|yeah|yep|yup|sure|confirm|confirmed|correct|ok|okay|go ahead|do it|proceed|please do|that'?s right)([,.! ]+(yes|please|go ahead|do it|confirm|thanks?|thank you))*[.!]?\\s*$", Pattern.CASE_INSENSITIVE);
    static final Pattern NO = Pattern.compile("^\\s*(no|nope|cancel|stop|don'?t|do not|never mind|nevermind|forget it|not now|abort)([,.! ]+.*)?$", Pattern.CASE_INSENSITIVE);
    static final Pattern HOWTO = Pattern.compile("^\\s*(how|where|what|when|which|who|why|is|are|do|does|can|could|list|show|find|tell|give|open|go to|take me)\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern ACTION_VERB = Pattern.compile("^\\s*(please\\s+)?(add|put|enrol|enroll|include|assign|sign up|mark|complete|close|finish|log|record|note|write|create|make|set up|schedule|remind me)\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern MEETING = Pattern.compile("\\bmeeting\\b", Pattern.CASE_INSENSITIVE);

    static final Pattern P_GROUP = Pattern.compile("^(?:please\\s+)?(?:add|put|enrol|enroll|include)\\s+(?<m>.+?)\\s+(?:to|in|into|on)\\s+(?:the\\s+)?(?<g>.+?)\\s*[.!]?$", Pattern.CASE_INSENSITIVE);
    static final Pattern P_FOLLOWUP_ADD = Pattern.compile("^(?:please\\s+)?(?:add|create|make|log|set up|open|schedule)\\s+(?:a\\s+|an\\s+)?(?:new\\s+)?follow[- ]?up\\s*(?:task\\s+)?(?:(?:for|about|on|with)\\s+(?<m>[^:,]+))?(?:\\s*[:,]\\s*|\\s+to\\s+|\\s+saying\\s+)?(?<t>.+?)?(?:\\s+(?:due|by|before)\\s+(?<d>.+?))?\\s*[.!]?$", Pattern.CASE_INSENSITIVE);
    static final Pattern P_REMIND = Pattern.compile("^(?:please\\s+)?remind me to\\s+(?<t>.+?)(?:\\s+(?:due|by|before|on)\\s+(?<d>.+?))?\\s*[.!]?$", Pattern.CASE_INSENSITIVE);
    static final Pattern P_FOLLOWUP_DONE = Pattern.compile("^(?:please\\s+)?(?:mark|complete|close|finish)\\s+(?:the\\s+)?(?:follow[- ]?up\\s+)?(?:(?:for|about|on|with)\\s+)?(?<x>.+?)(?:\\s+as\\s+(?:done|complete|completed|finished|followed up))?\\s*[.!]?$", Pattern.CASE_INSENSITIVE);
    static final Pattern P_NOTE = Pattern.compile("^(?:please\\s+)?(?:add|put|write|record|make)\\s+(?:a\\s+)?note\\s+(?:to|on|for|about)\\s+(?:the\\s+)?(?<f>[^:,]+?)(?:\\s+family)?\\s*(?:[:,]\\s*|\\s+saying\\s+|\\s+that\\s+)(?<n>.+?)\\s*$", Pattern.CASE_INSENSITIVE);
    static final Pattern P_ASSIGN = Pattern.compile("^(?:please\\s+)?(?:assign|sign up|schedule)\\s+(?<m>.+?)\\s+(?:as|to|for)\\s+(?:an?\\s+|the\\s+)?(?<r>.+?)(?:\\s+(?:on|for)\\s+(?<d>.+?))?\\s*[.!]?$", Pattern.CASE_INSENSITIVE);

    /** Deterministic intent → (tool, raw params), or null. Create-meeting phrasing is left to AiMeetingCreateService. */
    public static Map.Entry<String, Map<String, Object>> parse(String q, LocalDate today) {
        if (q == null) return null;
        String t = q.trim();
        if (t.isBlank() || HOWTO.matcher(t).find() || MEETING.matcher(t).find()) return null;
        Matcher m;
        if ((m = P_NOTE.matcher(t)).matches())
            return Map.entry("add_family_note", params("family", m.group("f"), "note", m.group("n")));
        if ((m = P_FOLLOWUP_ADD.matcher(t)).matches()) {
            String title = m.group("t") == null ? null : m.group("t").replaceAll("[,;:\\s]+$", "").trim();
            if (title != null && title.isBlank()) title = null;
            Map<String, Object> p = params("member", m.group("m"), "title", title != null ? title : (m.group("m") != null ? "Follow up with " + m.group("m").trim() : null));
            if (m.group("d") != null) p.put("dueDate", date(m.group("d"), today));
            return Map.entry("add_follow_up", p);
        }
        if ((m = P_REMIND.matcher(t)).matches()) {
            Map<String, Object> p = params("title", m.group("t"));
            if (m.group("d") != null) p.put("dueDate", date(m.group("d"), today));
            return Map.entry("add_follow_up", p);
        }
        if (t.toLowerCase(Locale.ROOT).matches("^(?:please\\s+)?(?:mark|complete|close|finish)\\b.*") && (t.toLowerCase(Locale.ROOT).contains("follow") || t.toLowerCase(Locale.ROOT).matches(".*\\bas\\s+(done|complete|completed|finished|followed up)\\s*[.!]?$"))
                && (m = P_FOLLOWUP_DONE.matcher(t)).matches())
            return Map.entry("complete_follow_up", params("match", m.group("x").replaceAll("(?i)^(the\\s+)?follow[- ]?up\\s*(for|about|on|with)?\\s*", "")));
        if ((m = P_ASSIGN.matcher(t)).matches()) {
            Map<String, Object> p = params("member", m.group("m"), "role", m.group("r").replaceAll("(?i)\\s+(duty|role|slot)$", ""));
            if (m.group("d") != null) p.put("date", date(m.group("d"), today));
            return Map.entry("assign_volunteer", p);
        }
        if ((m = P_GROUP.matcher(t)).matches() && !t.toLowerCase(Locale.ROOT).contains("note") && !t.toLowerCase(Locale.ROOT).contains("follow"))
            return Map.entry("add_group_member", params("member", m.group("m"), "group", m.group("g").replaceAll("(?i)\\s+group$", "")));
        return null;
    }

    static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null && !String.valueOf(kv[i + 1]).trim().isEmpty()) m.put((String) kv[i], String.valueOf(kv[i + 1]).trim());
        return m;
    }

    /** "2026-11-23", "11/23", "Nov 23", "today", "tomorrow", "(this|next) Sunday", "Sunday" → ISO date or the raw text (the tool then asks). */
    public static String date(String text, LocalDate today) {
        if (text == null) return null;
        String t = text.trim().toLowerCase(Locale.ROOT).replaceAll("^(on|for)\\s+", "").replaceAll("[.!]$", "");
        try { return LocalDate.parse(t).toString(); } catch (Exception ignore) {}
        if (t.equals("today")) return today.toString();
        if (t.equals("tomorrow")) return today.plusDays(1).toString();
        Matcher w = Pattern.compile("^(?:(this|next|coming)\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)$").matcher(t);
        if (w.matches()) {
            DayOfWeek want = DayOfWeek.valueOf(w.group(2).toUpperCase(Locale.ROOT));
            int add = (want.getValue() - today.getDayOfWeek().getValue() + 7) % 7;
            if (add == 0 && !"this".equals(w.group(1))) add = 7;
            if ("next".equals(w.group(1)) && add < 7 && today.getDayOfWeek() != DayOfWeek.SUNDAY) add += 0;   // "next Sunday" = the coming one
            return today.plusDays(add).toString();
        }
        Matcher us = Pattern.compile("^(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?$").matcher(t);
        if (us.matches()) {
            try {
                int y = us.group(3) == null ? today.getYear() : (us.group(3).length() == 2 ? 2000 + Integer.parseInt(us.group(3)) : Integer.parseInt(us.group(3)));
                LocalDate d = LocalDate.of(y, Integer.parseInt(us.group(1)), Integer.parseInt(us.group(2)));
                if (us.group(3) == null && d.isBefore(today)) d = d.plusYears(1);
                return d.toString();
            } catch (Exception ignore) { return text.trim(); }
        }
        Matcher mon = Pattern.compile("^(?:the\\s+)?(?:(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?)?(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s*(\\d{1,2})?(?:st|nd|rd|th)?(?:,?\\s*(\\d{4}))?$").matcher(t);
        if (mon.matches()) {
            String dayS = mon.group(1) != null ? mon.group(1) : mon.group(3);
            if (dayS == null) return text.trim();
            int month = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(mon.group(2).substring(0, 3)) + 1;
            try {
                int y = mon.group(4) == null ? today.getYear() : Integer.parseInt(mon.group(4));
                LocalDate d = LocalDate.of(y, month, Integer.parseInt(dayS));
                if (mon.group(4) == null && d.isBefore(today)) d = d.plusYears(1);
                return d.toString();
            } catch (Exception ignore) { return text.trim(); }
        }
        return text.trim();
    }

    /* ═══════════════════════════ the conversation ═══════════════════════════ */

    /** A pending question's draft (tool + the params gathered so far + the field asked). Lives in the session. */
    public static final class Draft implements java.io.Serializable {
        public String tool; public Map<String, Object> params = new LinkedHashMap<>(); public String asked; public List<String> choices; public String source; public long createdAt;
    }

    /**
     * Entry point from the AI Search data route. Null = not an action (the normal search continues).
     * Returns the usual answer map (handled / denied / intent / answer …) otherwise.
     */
    public Map<String, Object> handle(HttpServletRequest req, String clientId, String question) {
        if (!enabled || req == null || clientId == null || question == null) return null;
        HttpSession session = req.getSession(false);
        if (session == null) return null;
        String q = question.trim();
        if (q.isBlank()) return null;
        AiAccess a = access.resolve(req, clientId);
        if (a.member() || a.church() || a.kids() || AiAccessPolicy.isTemporaryAccess(session)) return null;

        // 1. an open proposal + yes/no
        String pendingToken = (String) session.getAttribute(SESSION_PENDING);
        if (pendingToken != null) {
            if (YES.matcher(q).matches()) return confirm(req, clientId, pendingToken);
            if (NO.matcher(q).matches())  return reject(req, clientId, pendingToken);
            // another question: the card stays until it is used, cancelled or expires
        }
        // 2. an open clarifying question
        Draft draft = (Draft) session.getAttribute(SESSION_DRAFT);
        if (draft != null) {
            if (System.currentTimeMillis() - draft.createdAt > ttlMinutes * 60_000L) { session.removeAttribute(SESSION_DRAFT); draft = null; }
        }
        if (draft != null) {
            if (NO.matcher(q).matches()) { session.removeAttribute(SESSION_DRAFT); return base("action.cancelled", "Okay — nothing was done."); }
            Map.Entry<String, Map<String, Object>> fresh = parse(q, AppClock.today());
            if (fresh != null) { session.removeAttribute(SESSION_DRAFT); return propose(req, session, a, clientId, fresh.getKey(), fresh.getValue(), "TEXT"); }
            String answer = q.replaceAll("[.!]$", "").trim();
            if (draft.choices != null && !draft.choices.isEmpty()) {
                String picked = pick(answer, draft.choices);
                if (picked == null) { session.removeAttribute(SESSION_DRAFT); return null; }   // the user moved on
                answer = picked;
            }
            if ("dueDate".equals(draft.asked) || "date".equals(draft.asked)) answer = date(answer, AppClock.today());
            draft.params.put(draft.asked, answer);
            session.removeAttribute(SESSION_DRAFT);
            return propose(req, session, a, clientId, draft.tool, draft.params, draft.source);
        }
        // 3. a new request
        Map.Entry<String, Map<String, Object>> parsed = parse(q, AppClock.today());
        String source = "TEXT";
        if (parsed == null) {
            if (!ACTION_VERB.matcher(q).find() || MEETING.matcher(q).find()) return null;
            // App Store readiness D6 (decision D): a question is never sent to OpenAI when the
            // church's plan does not include AI actions — the plan check comes FIRST.
            if (!access.feature(clientId, FEATURE)) return base("action.unavailable",
                    "AI actions (doing things from the search bar) are not part of your current plan.");
            parsed = llmPick(q, a);
            if (parsed == null) return null;
            source = "LLM";
        }
        if (!access.feature(clientId, FEATURE)) return base("action.unavailable", "AI actions (doing things from the search bar) are not part of your current plan. You can do this on the "
                + registry.get(parsed.getKey()).label().toLowerCase(Locale.ROOT).replace("add ", "").replace("complete ", "") + " page.");
        return propose(req, session, a, clientId, parsed.getKey(), parsed.getValue(), source);
    }

    /** Matches a typed answer to one of the offered choices (whole label, or a unique contains / number). */
    public static String pick(String answer, List<String> choices) {
        String a = answer.toLowerCase(Locale.ROOT).trim();
        for (String c : choices) if (c.toLowerCase(Locale.ROOT).equals(a)) return c;
        try { int n = Integer.parseInt(a.replaceAll("^(the\\s+)?(first|1st)$", "1").replaceAll("^(the\\s+)?(second|2nd)$", "2").replaceAll("^(the\\s+)?(third|3rd)$", "3")); if (n >= 1 && n <= choices.size()) return choices.get(n - 1); } catch (Exception ignore) {}
        String hit = null;
        for (String c : choices) if (c.toLowerCase(Locale.ROOT).contains(a) || a.contains(c.toLowerCase(Locale.ROOT))) { if (hit != null) return null; hit = c; }
        return hit;
    }

    public Map<String, Object> propose(HttpServletRequest req, HttpSession session, AiAccess a, String clientId, String toolName, Map<String, Object> raw, String source) {
        AiTool tool = registry.get(toolName);
        if (tool == null) return null;
        // 2026-11-08: income / expense are created only through the guided conversation (every field asked, review card).
        if (tool.guidedOnly()) return base("action.refused", "That is done through the guided conversation in the AI Search bar — say \"add an "
                + (toolName.contains("expense") ? "expense" : "income") + "\". Nothing was done.");
        CrossAccess x = access.crossAccess(req, clientId);
        if (!tool.allowed(req, a, x, clientId)) {
            Map<String, Object> m = base("action.denied", "I can't " + tool.label().toLowerCase(Locale.ROOT) + " for you — your login doesn't have that permission.");
            m.put("denied", true); return m;
        }
        Resolution r;
        try { r = tool.resolve(raw, clientId, a.username()); }
        catch (RuntimeException e) { log.warn("[ai-actions] resolve failed for {}: {}", toolName, e.getClass().getSimpleName()); return base("action.failed", "I couldn't work out that request. Nothing was done."); }
        if (r.refusal() != null) return base("action.refused", r.refusal() + " Nothing was done.");
        if (r.ask() != null) {
            Draft d = new Draft(); d.tool = toolName; d.params = new LinkedHashMap<>(raw); d.asked = r.ask().field(); d.choices = r.ask().choices(); d.source = source; d.createdAt = System.currentTimeMillis();
            session.setAttribute(SESSION_DRAFT, d);
            Map<String, Object> m = base("action.ask", r.ask().question() + (r.ask().choices() != null && !r.ask().choices().isEmpty() ? " " + String.join(" · ", r.ask().choices()) : ""));
            if (r.ask().choices() != null && !r.ask().choices().isEmpty()) m.put("choices", r.ask().choices());
            m.put("tool", toolName);
            return m;
        }
        AiActionProposal p = new AiActionProposal();
        p.setAppClientId(clientId); p.setUsername(a.username()); p.setTool(toolName); p.setStatus("PROPOSED"); p.setSource(source);
        p.setToken(UUID.randomUUID().toString()); p.setExpiresAt(LocalDateTime.now().plusMinutes(ttlMinutes));
        p.setSummary(cap(tool.summary(r.resolved()), 500));
        try { p.setParamsJson(JSON.writeValueAsString(r.resolved())); } catch (Exception e) { return base("action.failed", "I couldn't record that request. Nothing was done."); }
        p = proposals.save(p);
        session.setAttribute(SESSION_PENDING, p.getToken());
        Map<String, Object> m = base("action.confirm", p.getSummary() + " Shall I go ahead?");
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("token", p.getToken()); c.put("proposalId", p.getId()); c.put("label", tool.label()); c.put("endpoint", "/api/ai/actions/confirm");
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("Action", tool.label());
        for (Map.Entry<String, Object> e : r.resolved().entrySet()) if (e.getKey().endsWith("Label") || List.of("title", "note", "dueDate", "date", "startTime", "endTime", "location", "label", "assignedTo").contains(e.getKey()))
            summary.put(pretty(e.getKey()), String.valueOf(e.getValue()));
        summary.put("Expires", "in " + ttlMinutes + " minutes");
        c.put("summary", summary);
        m.put("confirm", c); m.put("tool", toolName);
        return m;
    }

    /**
     * 2026-11-08 — the guided conversation's proposal: the tool's own resolve (tenant ids re-checked) and the same
     * PROPOSED row + one-time token as {@link #propose}, so {@link #confirm} (button or "yes") is the only write
     * path. Returns {@code {token, proposalId, summary}} or {@code {refusal}}. Never sets the spoken-"yes" pending
     * token — the guided conversation handles its own "yes".
     */
    public Map<String, Object> proposeGuided(HttpServletRequest req, AiAccess a, String clientId, String toolName, Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        AiTool tool = registry.get(toolName);
        if (!enabled) { out.put("refusal", "AI actions are switched off on this server."); return out; }
        if (tool == null || !tool.guidedOnly()) { out.put("refusal", "That action is not available."); return out; }
        if (!access.feature(clientId, FEATURE)) { out.put("refusal", "AI-assisted entry is not part of your current plan."); return out; }
        if (!tool.allowed(req, a, access.crossAccess(req, clientId), clientId)) { out.put("refusal", "Your login doesn't have permission for that."); out.put("denied", true); return out; }
        Resolution r;
        try { r = tool.resolve(raw, clientId, a.username()); }
        catch (RuntimeException e) { log.warn("[ai-actions] guided resolve failed for {}: {}", toolName, e.getClass().getSimpleName()); out.put("refusal", "I couldn't check those details."); return out; }
        if (!r.isOk()) { out.put("refusal", r.refusal() != null ? r.refusal() : r.ask().question()); return out; }
        AiActionProposal p = new AiActionProposal();
        p.setAppClientId(clientId); p.setUsername(a.username()); p.setTool(toolName); p.setStatus("PROPOSED"); p.setSource("GUIDED");
        p.setToken(UUID.randomUUID().toString()); p.setExpiresAt(LocalDateTime.now().plusMinutes(ttlMinutes));
        p.setSummary(cap(tool.summary(r.resolved()), 500));
        try { p.setParamsJson(JSON.writeValueAsString(r.resolved())); } catch (Exception e) { out.put("refusal", "I couldn't record that request."); return out; }
        p = proposals.save(p);
        out.put("token", p.getToken()); out.put("proposalId", p.getId()); out.put("summary", p.getSummary()); out.put("resolved", r.resolved());
        return out;
    }

    /** True while the proposal behind {@code token} is still open (the guided draft drops itself after a button confirm). */
    public boolean isOpen(String clientId, String token) {
        return token != null && clientId != null && proposals.findByTokenAndAppClientIdAndStatus(token, clientId, "PROPOSED").isPresent();
    }

    /** Tokens being confirmed right now — a second click / repeated "yes" while the first is saving finds the token busy. */
    private final java.util.Set<String> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The one-time confirmation. Re-checks everything, executes in one transaction, records the outcome. */
    public Map<String, Object> confirm(HttpServletRequest req, String clientId, String token) {
        if (token != null && !inFlight.add(token)) return expired();
        try { return confirmOnce(req, clientId, token); }
        finally { if (token != null) inFlight.remove(token); }
    }

    private Map<String, Object> confirmOnce(HttpServletRequest req, String clientId, String token) {
        HttpSession session = req.getSession(false);
        if (session != null) session.removeAttribute(SESSION_PENDING);
        if (!enabled) return base("action.unavailable", "AI actions are switched off on this server. Nothing was done.");
        if (token == null || clientId == null) return expired();
        AiActionProposal p = proposals.findByTokenAndAppClientIdAndStatus(token, clientId, "PROPOSED").orElse(null);
        if (p == null) return expired();
        AiAccess a = access.resolve(req, clientId);
        if (!Objects.equals(p.getUsername(), a.username())) return expired();                    // somebody else's card
        if (p.getExpiresAt().isBefore(LocalDateTime.now())) { decide(p, "EXPIRED", null); return expired(); }
        AiTool tool = registry.get(p.getTool());
        if (tool == null) { decide(p, "FAILED", "{\"error\":\"tool missing\"}"); return base("action.failed", "That action is no longer available. Nothing was done."); }
        if (!access.feature(clientId, FEATURE) || !tool.allowed(req, a, access.crossAccess(req, clientId), clientId)) {
            decide(p, "REJECTED", "{\"reason\":\"permission\"}");
            Map<String, Object> m = base("action.denied", "Your login no longer has permission to " + tool.label().toLowerCase(Locale.ROOT) + ". Nothing was done."); m.put("denied", true); return m;
        }
        Map<String, Object> stored;
        try { stored = JSON.readValue(p.getParamsJson(), new TypeReference<Map<String, Object>>() {}); } catch (Exception e) { decide(p, "FAILED", null); return base("action.failed", "That request could not be read back. Nothing was done."); }
        // re-resolve: every id must still be a live row of THIS church
        Resolution again;
        try { again = tool.resolve(stored, clientId, a.username()); }
        catch (RuntimeException e) { decide(p, "FAILED", null); return base("action.failed", "That request could not be checked again. Nothing was done."); }
        if (!again.isOk()) { decide(p, "REJECTED", "{\"reason\":\"changed\"}"); return base("action.refused", (again.refusal() != null ? again.refusal() : "Something changed since the proposal (" + again.ask().question() + ")") + " Nothing was done."); }
        // mark CONFIRMED first (the token is spent even if execution fails) then execute in one transaction
        p.setToken(null); p.setDecidedAt(LocalDateTime.now());
        Map<String, Object> result;
        try {
            final AiActionProposal fp = p; final Map<String, Object> resolved = again.resolved();
            result = tx.execute(status -> {
                Map<String, Object> res = tool.execute(resolved, clientId, a.username());
                fp.setStatus("EXECUTED");
                try { fp.setResultJson(JSON.writeValueAsString(res)); } catch (Exception ignore) { fp.setResultJson("{}"); }
                proposals.save(fp);
                return res;
            });
        } catch (RuntimeException e) {
            log.warn("[ai-actions] execute failed for {}: {}", p.getTool(), e.getClass().getSimpleName());
            decide(p, "FAILED", null);
            return base("action.failed", "The action could not be completed, so nothing was saved. Please try again or use the page directly.");
        }
        Map<String, Object> m = base("action.done", "Done — " + p.getSummary().replaceFirst("\\.$", "") + ".");
        m.put("executed", true); m.put("proposalId", p.getId()); m.put("tool", p.getTool());
        if (result != null && result.get("link") instanceof Map<?, ?> link) m.put("link", link);
        return m;
    }

    public Map<String, Object> reject(HttpServletRequest req, String clientId, String token) {
        HttpSession session = req.getSession(false);
        if (session != null) session.removeAttribute(SESSION_PENDING);
        if (token == null || clientId == null) return base("action.cancelled", "Okay — nothing was done.");
        proposals.findByTokenAndAppClientIdAndStatus(token, clientId, "PROPOSED").ifPresent(p -> decide(p, "REJECTED", null));
        return base("action.cancelled", "Okay — nothing was done.");
    }

    private void decide(AiActionProposal p, String status, String resultJson) {
        p.setStatus(status); p.setToken(null); p.setDecidedAt(LocalDateTime.now());
        if (resultJson != null) p.setResultJson(resultJson);
        try { proposals.save(p); } catch (RuntimeException e) { log.warn("[ai-actions] could not record {}: {}", status, e.getClass().getSimpleName()); }
    }

    /** Recent proposals of the church (admins) or of the caller — for the AI Activity page. */
    public List<Map<String, Object>> recent(String clientId, String username, boolean all) {
        try { proposals.expireOverdue(LocalDateTime.now()); } catch (RuntimeException ignore) {}
        List<AiActionProposal> rows = all ? proposals.findTop50ByAppClientIdOrderByCreatedAtDesc(clientId) : proposals.findTop50ByAppClientIdAndUsernameOrderByCreatedAtDesc(clientId, username);
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiActionProposal p : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId()); m.put("tool", p.getTool()); m.put("label", registry.get(p.getTool()) == null ? p.getTool() : registry.get(p.getTool()).label());
            m.put("summary", p.getSummary()); m.put("status", p.getStatus()); m.put("by", p.getUsername()); m.put("source", p.getSource());
            m.put("createdAt", p.getCreatedAt() == null ? null : p.getCreatedAt().toString()); m.put("decidedAt", p.getDecidedAt() == null ? null : p.getDecidedAt().toString());
            out.add(m);
        }
        return out;
    }

    /* ═══════════════════════════ LLM fallback ═══════════════════════════ */

    /** gpt-4o-mini picks a tool + parameters from the closed list, or nothing. The message text only. */
    Map.Entry<String, Map<String, Object>> llmPick(String q, AiAccess a) {
        if (!llmFallback || openAi == null || !openAi.isEnabled()) return null;
        try {
            String system = "You map a church staff member's request to ONE tool from this closed list, or to none. Tools (JSON): " + JSON.writeValueAsString(registry.describe())
                    + ". Reply ONLY as JSON {\"tool\": <name or null>, \"params\": {<parameter>: <string>}}. Copy names and words exactly as the person wrote them; never invent a value; dates as YYYY-MM-DD when the text gives a full date, otherwise the text as written. "
                    + "Use null when the request is a question, a search, a how-to, or asks to send/e-mail/text anyone (there is no sending tool). Today is " + AppClock.today() + ".";
            JsonNode n = openAi.chatJson(system, q, 0);
            if (n == null || !n.hasNonNull("tool")) return null;
            String tool = n.get("tool").asText("");
            if (registry.get(tool) == null || registry.get(tool).guidedOnly()) return null;
            Map<String, Object> params = new LinkedHashMap<>();
            JsonNode ps = n.path("params");
            if (ps.isObject()) ps.fields().forEachRemaining(e -> { if (e.getValue().isValueNode() && !e.getValue().isNull()) params.put(e.getKey(), cap(e.getValue().asText(), 500)); });
            return Map.entry(tool, params);
        } catch (Exception e) {
            log.warn("[ai-actions] llm fallback failed: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /* ═══════════════════════════ helpers ═══════════════════════════ */

    static Map<String, Object> base(String intent, String answer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("handled", true); m.put("denied", false); m.put("intent", intent); m.put("answer", answer); m.put("source", "AI Actions");
        return m;
    }
    static Map<String, Object> expired() { return base("action.expired", "That confirmation is no longer valid (already used, cancelled or expired). Ask me again if you still want it done."); }
    static String cap(String s, int n) { return s == null ? null : s.length() > n ? s.substring(0, n) : s; }
    static String pretty(String key) {
        String k = key.replace("Label", "").replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        return Character.toUpperCase(k.charAt(0)) + k.substring(1);
    }
}
