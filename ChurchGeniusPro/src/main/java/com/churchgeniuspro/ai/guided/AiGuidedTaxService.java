package com.churchgeniuspro.ai.guided;

import com.churchgeniuspro.ai.action.AiActionService;
import com.churchgeniuspro.ai.write.AiTaxLetterService;
import com.churchgeniuspro.repository.ChurchRegistrationRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.FinancialReportLetterService;
import com.churchgeniuspro.service.TaxStatements;
import com.churchgeniuspro.util.AppClock;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.churchgeniuspro.ai.guided.AiGuidedRecordService.base;
import static com.churchgeniuspro.ai.guided.AiGuidedRecordService.cap;

/**
 * 2026-11-20 — Genius Assistant conversation for the Year-End Tax Report: "Create a tax report for 2026",
 * "Generate tax reports for non-members for 2026", "Create a tax report for John Smith for 2026".
 *
 * <p>It asks, one step at a time, only for what the request did not say: the tax year (never assumed), who to
 * include (All / Members only / Non-Members only / a specific contributor — found by first or last name, part of a
 * name is enough, at most ten matches listed), and the template (the default wording, an AI draft of the wording, or
 * the church's own saved letter text). Then a summary to confirm, then the statement.
 *
 * <p>Read-only: nothing is written. The figures are the Tax Report's own ({@link TaxStatements#build} — the same
 * call as {@code GET /api/reports/tax}), limited to the signed-in church; the model is never given a contributor or
 * an amount (an AI draft is wording only — {@link AiTaxLetterService}). A single contributor, or a small group, is
 * returned for review and download in the conversation; a larger group is not generated here — the answer links to
 * the Tax Report page with the year, filter and template already chosen.
 *
 * <p>Access = the Tax Report page's own rule (SuperAdmin / Admin / Accountant with the Reports page), checked at the
 * start and on every message; never a Member Portal, Kids Portal, church-owner or temporary-access session.
 */
@Service
public class AiGuidedTaxService {

    public static final String SESSION_KEY = "aiTaxDraft";
    static final String KIND = "tax report";
    public static final String PAGE = "/tax-report";
    public static final String REDIRECT_MSG = "To ensure reliable report generation and downloading, please continue using the Tax Report page.";

    static final String WHO_ALL = "All (Members and Non-Members)", WHO_MEMBERS = "Members only", WHO_NON = "Non-Members only", WHO_ONE = "A specific contributor";
    static final String T_DEFAULT = "Use the default template", T_AI = "Generate an AI draft", T_MINE = "Use my template";
    static final String GENERATE = "Generate", CH_YEAR = "Change the year", CH_WHO = "Change the contributors", CH_TEMPLATE = "Change the template", CANCEL = "Cancel";
    static final String BACK = "Choose All, Members or Non-Members instead", AGAIN = "Search again";

    enum Stage { YEAR, WHO, NAME, PICK, TEMPLATE, CONFIRM }

    static final class Match implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        String label; Integer memberId; String guestName; String name;
    }

    static final class Draft implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        String clientId; Instant created; Stage stage;
        Integer year; String who;                       // all | members | nonmembers | one
        Integer memberId; String guestName; String personLabel;
        String template;                                // default | ai | mine
        ArrayList<String> choices; ArrayList<Match> matches;
        boolean reviewing;                              // a change made from the summary returns to the summary
    }

    // ── vocabulary ────────────────────────────────────────────────────────────

    static final Pattern SUBJECT = Pattern.compile("(?i)\\b(tax\\s+(?:report|statement|letter|receipt)s?|(?:contribution|giving|donation|donor)\\s+(?:tax\\s+)?(?:statement|letter|receipt)s?|"
        + "year[-\\s]?end\\s+(?:tax|giving|contribution|donation)\\s+(?:report|statement|summary|letter)s?)\\b");
    static final Pattern VERB = Pattern.compile("(?i)\\b(create|generate|make|prepare|produce|build|download|print|export|get|run|send me|need|want|give me|do)\\b");
    static final Pattern QUESTION = Pattern.compile("(?i)^\\s*(how|where|what|when|which|who|why|is|are|does|do i|can i see|explain|tell me about)\\b");
    static final Pattern YEAR = Pattern.compile("(?<![\\d$.,/-])((?:19|20)\\d{2})(?![\\d/-])");
    static final Pattern NON = Pattern.compile("(?i)\\bnon[-\\s]?members?\\b|\\b(guests?|visitors?)\\b(?!\\s+name)");
    static final Pattern MEMBERS = Pattern.compile("(?i)\\b(members\\s+only|only\\s+(?:the\\s+)?members|(?:for|of)\\s+(?:all\\s+|the\\s+|our\\s+)?members|all\\s+members)\\b");
    static final Pattern EVERYONE = Pattern.compile("(?i)\\b(all\\s+(?:the\\s+|our\\s+)?(?:contributors|donors|givers|people)|every(?:one|body)|all\\s*\\(|members\\s+and\\s+non[-\\s]?members|for\\s+all\\b|^all$)");
    static final Pattern ONE = Pattern.compile("(?i)\\b(a\\s+)?(specific|single|one|particular)\\s+(contributor|person|member|donor|giver)\\b|\\bone\\s+person\\b");
    /** "for John Smith" — capitalised words after "for" that are not vocabulary of the request. */
    static final Pattern FOR_NAME = Pattern.compile("\\b(?:for|of)\\s+((?:[A-Z][\\p{L}'.-]*)(?:\\s+[A-Z][\\p{L}'.-]*){0,3})");
    static final Set<String> NOT_NAMES = Set.of("all", "members", "member", "non-members", "nonmembers", "non", "everyone", "everybody", "tax", "year", "the", "our",
        "contributors", "donors", "guests", "visitors", "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december", "this", "last", "each", "every");
    static final Pattern STOP = Pattern.compile("(?i)^\\s*(cancel|cancel (it|that|this)|never ?mind|stop|forget it|abort|quit)[.!]?\\s*$");
    static final Pattern YES = Pattern.compile("(?i)^\\s*(yes|yeah|yep|sure|ok|okay|generate|generate it|go ahead|do it|proceed|confirm|please do|create it|make it)[.!]?\\s*$");

    private final AiAccessPolicy access;
    private final IncomeRepository incomes;
    private final ChurchRegistrationRepository churches;

    @Autowired(required = false) private FinancialReportLetterService letters;
    @Autowired(required = false) private AiTaxLetterService aiLetters;

    @Value("${ai.guided.draft-minutes:15}")
    public int draftMinutes = 15;
    /** A group larger than this is not generated in the conversation — the answer links to the Tax Report page. */
    @Value("${ai.guided.tax-max-in-chat:10}")
    public int maxInChat = 10;

    public AiGuidedTaxService(AiAccessPolicy access, IncomeRepository incomes, ChurchRegistrationRepository churches) {
        this.access = access; this.incomes = incomes; this.churches = churches;
    }

    public void setLetters(FinancialReportLetterService s) { this.letters = s; }
    public void setAiLetters(AiTaxLetterService s) { this.aiLetters = s; }

    public static boolean isStartRequest(String q) {
        if (q == null || QUESTION.matcher(q).find()) return false;
        return SUBJECT.matcher(q).find() && VERB.matcher(q).find();          // "tax report" / "open the tax report" stay page requests
    }
    public static boolean hasDraft(HttpSession s) { return s != null && s.getAttribute(SESSION_KEY) instanceof Draft; }
    public static void clear(HttpSession s) { if (s != null) s.removeAttribute(SESSION_KEY); }

    // ── the conversation ──────────────────────────────────────────────────────

    public Map<String, Object> handle(HttpServletRequest req, String clientId, String question) {
        if (req == null || clientId == null || question == null) return null;
        HttpSession session = req.getSession(false);
        if (session == null) return null;
        String q = question.trim();
        if (q.isEmpty()) return null;
        Draft d = pending(session, clientId);

        if (isStartRequest(q)) {
            GuidedDrafts.clearOthers(session, SESSION_KEY); clear(session);
            Map<String, Object> gate = gate(req, clientId);
            if (gate != null) return gate;
            d = new Draft(); d.clientId = clientId; d.created = Instant.now();
            readRequest(d, q);
            if (d.who != null && d.who.startsWith("name:")) { String name = d.who.substring(5); d.who = "one"; if (d.year != null) return search(session, d, name); d.personLabel = name; }
            return advance(req, session, d);
        }
        if (d == null) return null;
        // another conversation was started instead — this one is dropped and the request goes on to it
        if (AiGuidedBatchService.isStartRequest(q) || AiGuidedRecordService.isStartRequest(q)) { clear(session); return null; }
        Map<String, Object> gate = gate(req, clientId);
        if (gate != null) { clear(session); return gate; }
        String t = q.replaceAll("[.!]+$", "").trim();
        if (STOP.matcher(q).matches() || CANCEL.equalsIgnoreCase(t)) {
            clear(session);
            return base("guided.cancelled", "Okay — I've stopped. No tax report was generated.", KIND);
        }
        String picked = d.choices == null || YEAR.matcher(t).matches() ? null : AiActionService.pick(t, d.choices);

        switch (d.stage) {
            case YEAR -> {
                Integer y = yearIn(t);
                if (y == null) return askYear(session, d, "I need a four-digit year, such as " + (AppClock.today().getYear() - 1) + ". ");
                String bad = badYear(y);
                if (bad != null) return askYear(session, d, bad + " ");
                d.year = y;
                if ("one".equals(d.who) && d.memberId == null && d.guestName == null && d.personLabel != null) { String n = d.personLabel; d.personLabel = null; return search(session, d, n); }
                return advance(req, session, d);
            }
            case WHO -> {
                String who = whoFrom(picked != null ? picked : t);
                if (who == null) return askWho(session, d, "Please choose one of these. ");
                d.who = who; d.memberId = null; d.guestName = null; d.personLabel = null;
                return advance(req, session, d);
            }
            case NAME -> {
                if (BACK.equalsIgnoreCase(picked) || BACK.equalsIgnoreCase(t)) { d.who = null; return askWho(session, d, ""); }
                return search(session, d, t);
            }
            case PICK -> {
                if (BACK.equalsIgnoreCase(picked)) { d.who = null; d.matches = null; return askWho(session, d, ""); }
                if (AGAIN.equalsIgnoreCase(picked)) { d.matches = null; return askName(session, d, ""); }
                Match m = null;
                if (picked != null && d.matches != null) for (Match x : d.matches) if (x.label.equals(picked)) m = x;
                if (m == null) return search(session, d, t);                    // another name typed instead of a choice
                d.memberId = m.memberId; d.guestName = m.guestName; d.personLabel = m.name; d.matches = null;
                return advance(req, session, d);
            }
            case TEMPLATE -> {
                String tpl = templateFrom(picked != null ? picked : t);
                if (tpl == null) return askTemplate(session, d, "Please choose one of these. ");
                Map<String, Object> refusal = templateRefusal(session, d, tpl);
                if (refusal != null) return refusal;
                d.template = tpl;
                return advance(req, session, d);
            }
            case CONFIRM -> {
                String low = (picked != null ? picked : t).toLowerCase(Locale.ROOT);
                if (GENERATE.equalsIgnoreCase(picked) || YES.matcher(q).matches()) return generate(session, d);
                if (low.contains("year")) { d.reviewing = true; Integer y = yearIn(t); if (y != null && badYear(y) == null) { d.year = y; return advance(req, session, d); } d.year = null; return askYear(session, d, ""); }
                if (low.contains("template")) { d.reviewing = true; d.template = null; return askTemplate(session, d, ""); }
                if (low.contains("contributor") || low.contains("who") || low.contains("member")) { d.reviewing = true; d.who = null; d.memberId = null; d.guestName = null; d.personLabel = null; return askWho(session, d, ""); }
                if (low.matches("no|nope|not now")) { clear(session); return base("guided.cancelled", "Okay — I've stopped. No tax report was generated.", KIND); }
                return null;                                                    // something else — the summary waits
            }
        }
        return null;
    }

    /** The next question — only what is still missing — or the summary. */
    private Map<String, Object> advance(HttpServletRequest req, HttpSession session, Draft d) {
        if (d.year == null) return askYear(session, d, "");
        if (d.who == null) return askWho(session, d, "");
        if ("one".equals(d.who) && d.memberId == null && d.guestName == null) return askName(session, d, "");
        if (d.template == null) return askTemplate(session, d, "");
        return confirm(session, d);
    }

    private Map<String, Object> askYear(HttpSession session, Draft d, String lead) {
        int y = AppClock.today().getYear();
        return ask(session, d, Stage.YEAR, lead + "Which year would you like to generate the tax report for?", List.of(String.valueOf(y), String.valueOf(y - 1), String.valueOf(y - 2)));
    }

    private Map<String, Object> askWho(HttpSession session, Draft d, String lead) {
        return ask(session, d, Stage.WHO, lead + "Which contributors should I include?", List.of(WHO_ALL, WHO_MEMBERS, WHO_NON, WHO_ONE));
    }

    private Map<String, Object> askName(HttpSession session, Draft d, String lead) {
        return ask(session, d, Stage.NAME, lead + "What is the contributor's first and last name? Part of a name is fine.", List.of(BACK, CANCEL));
    }

    private Map<String, Object> askTemplate(HttpSession session, Draft d, String lead) {
        return ask(session, d, Stage.TEMPLATE, lead + "Which template should I use?", List.of(T_DEFAULT, T_AI, T_MINE));
    }

    private Map<String, Object> confirm(HttpSession session, Draft d) {
        d.reviewing = false;
        Map<String, Object> m = ask(session, d, Stage.CONFIRM, "Here is what I'll generate. Shall I go ahead?", List.of(GENERATE, CH_YEAR, CH_WHO, CH_TEMPLATE, CANCEL));
        m.put("summary", selection(d));
        return m;
    }

    private Map<String, String> selection(Draft d) {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("Tax year", String.valueOf(d.year));
        s.put("Contributors", "one".equals(d.who) ? d.personLabel : TaxStatements.contributorTypeLabel(d.who));
        s.put("Template", "ai".equals(d.template) ? "AI draft" : "mine".equals(d.template) ? "My template (the church's letter text)" : "Default template");
        return s;
    }

    // ── a specific contributor ────────────────────────────────────────────────

    /**
     * Contributors with tax-deductible gifts in the chosen year whose first or last name contains every word typed.
     * Up to ten are listed; more than ten asks for a narrower name; none says so. Only this church's contributors.
     */
    private Map<String, Object> search(HttpSession session, Draft d, String typed) {
        String name = typed == null ? "" : typed.replaceAll("[\"“”]", "").trim();
        if (name.length() < 2) return askName(session, d, "Please give at least two letters of the name. ");
        if (name.length() > 80) name = name.substring(0, 80);
        List<String> words = new ArrayList<>();
        for (String w : name.toLowerCase(Locale.ROOT).split("[\\s,]+")) if (!w.isBlank()) words.add(w);
        List<Match> exact = new ArrayList<>(), part = new ArrayList<>();
        String full = String.join(" ", words);
        for (Map<String, Object> m : contributors(d)) {
            String first = str(m.get("firstName")), last = str(m.get("lastName"));
            String hay = (first + " " + last).toLowerCase(Locale.ROOT).trim();
            boolean all = true;
            for (String w : words) if (!hay.contains(w)) { all = false; break; }
            if (!all) continue;
            Match x = new Match();
            x.memberId = m.get("id") instanceof Integer i ? i : null;
            x.guestName = x.memberId == null ? first : null;
            x.name = (first + " " + last).trim();
            String place = String.join(", ", nonBlank(str(m.get("city")), str(m.get("state"))));
            x.label = x.name + (place.isEmpty() ? "" : " — " + place) + (x.memberId == null ? " (non-member, by name)" : Boolean.TRUE.equals(m.get("nonMember")) ? " (non-member)" : " (member)");
            (hay.equals(full) || (last + " " + first).toLowerCase(Locale.ROOT).trim().equals(full) ? exact : part).add(x);
        }
        List<Match> found = new ArrayList<>(exact); found.addAll(part);
        String shown = "“" + cap(name, 60) + "”";
        if (found.isEmpty())
            return ask(session, d, Stage.NAME, "I couldn't find a contributor with tax-deductible gifts in " + d.year + " matching " + shown + ". You can try another name.", List.of(BACK, CANCEL));
        if (found.size() > 10)
            return ask(session, d, Stage.NAME, found.size() + " contributors match " + shown + ". Please give a more specific first or last name so I can narrow it to ten or fewer.", List.of(BACK, CANCEL));
        Set<String> seen = new HashSet<>();
        for (Match x : found) { String l = x.label; int n = 2; while (!seen.add(x.label)) x.label = l + " #" + n++; }   // two people who look the same stay two choices
        d.matches = new ArrayList<>(found);
        List<String> ch = new ArrayList<>();
        for (Match x : found) ch.add(x.label);
        ch.add(AGAIN); ch.add(BACK);
        return ask(session, d, Stage.PICK, (found.size() == 1 ? "I found one contributor matching " + shown + ". Is this the person?" : "I found " + found.size() + " contributors matching " + shown + ". Which one do you mean?"), ch);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> contributors(Draft d) {
        Map<String, Object> all = TaxStatements.build(incomes, d.clientId, "", d.year, null, null, TaxStatements.ALL);
        Object ms = all.get("members");
        return ms instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    // ── templates ─────────────────────────────────────────────────────────────

    /** Why the chosen template cannot be used here (with the other choices), or null when it can. */
    private Map<String, Object> templateRefusal(HttpSession session, Draft d, String tpl) {
        if ("mine".equals(tpl)) {
            boolean saved = false;
            try { saved = letters != null && Boolean.TRUE.equals(letters.forEditing(d.clientId).get("customised")); } catch (RuntimeException ignore) { }
            if (!saved) {
                Map<String, Object> m = ask(session, d, Stage.TEMPLATE, "Your church has not saved its own letter text yet, so there is no template of yours to use — and a template file "
                    + "(Word or PDF) cannot be uploaded here, because it could not be filled in reliably. You can write your own letter text on the Tax Report page (Edit letter text); "
                    + "it is then used as your template. For now, which should I use?", List.of(T_DEFAULT, T_AI, CANCEL));
                m.put("link", link("Open Tax Report", PAGE));
                return m;
            }
        }
        if ("ai".equals(tpl)) {
            String why = !access.feature(d.clientId, "aiWrite") ? "AI drafting is not part of your current plan"
                       : aiLetters == null || !aiLetters.isEnabled() ? "AI drafting is not available on this server at the moment" : null;
            if (why != null) return ask(session, d, Stage.TEMPLATE, why + ". Which template should I use instead?", List.of(T_DEFAULT, T_MINE, CANCEL));
        }
        return null;
    }

    // ── the statement ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> generate(HttpSession session, Draft d) {
        String church = churches.findByClientIdAndDeleteFlagFalse(d.clientId).map(c -> c.getChurchName() == null ? "" : c.getChurchName()).orElse("");
        boolean one = "one".equals(d.who);
        Map<String, Object> data = TaxStatements.build(incomes, d.clientId, church, d.year, one ? d.memberId : null, one ? d.guestName : null, one ? TaxStatements.ALL : d.who);
        List<Map<String, Object>> people = (List<Map<String, Object>>) data.get("members");
        String whoText = one ? d.personLabel : TaxStatements.ALL.equals(d.who) ? "all contributors" : TaxStatements.contributorTypeLabel(d.who).toLowerCase(Locale.ROOT);
        if (people == null || people.isEmpty()) {
            Map<String, Object> m = ask(session, d, Stage.CONFIRM, "No tax-deductible gifts were found for " + whoText + " in " + d.year + ", so there is no statement to generate. What would you like to change?",
                List.of(CH_YEAR, CH_WHO, CANCEL));
            m.put("summary", selection(d));
            return m;
        }
        String pageUrl = pageUrl(d);
        if (!one && people.size() > Math.max(1, maxInChat)) {                    // a large batch belongs on the page
            clear(session);
            Map<String, Object> m = base("guided.redirect", people.size() + " contributors have tax-deductible gifts in " + d.year + " (" + whoText + "). " + REDIRECT_MSG
                + " The year, contributors and template you chose are already selected there.", KIND);
            m.put("link", link("Open Tax Report", pageUrl));
            m.put("summary", selection(d));
            m.put("readOnly", true);
            return m;
        }
        Map<String, String> wording;
        if ("ai".equals(d.template)) {
            wording = aiLetters == null ? null : aiLetters.draft(church, d.year, null);
            if (wording == null) {
                Map<String, Object> m = ask(session, d, Stage.TEMPLATE, "The AI draft could not be produced just now, so nothing was generated. Which template should I use?", List.of(T_AI, T_DEFAULT, T_MINE, CANCEL));
                return m;
            }
            wording = new LinkedHashMap<>(wording); wording.remove("draft");
            Map<String, String> sig = letters != null ? letters.rendered(d.clientId, church, d.year) : FinancialReportLetterService.defaults(church, d.year);
            wording.put("signatureName", sig.get("signatureName")); wording.put("signatureTitle", sig.get("signatureTitle"));
        } else if ("mine".equals(d.template) && letters != null) wording = letters.rendered(d.clientId, church, d.year);
        else wording = FinancialReportLetterService.defaults(church, d.year);
        data.putAll(wording);

        int gifts = 0;
        for (Map<String, Object> p : people) { Object e = p.get("entries"); if (e instanceof List<?> l) gifts += l.size(); }
        String total = money((BigDecimal) data.get("grandTotal"));
        clear(session);
        Map<String, Object> m = base("guided.statement", (one
                ? "Here is the " + d.year + " tax statement for " + d.personLabel + ": " + gifts + (gifts == 1 ? " gift" : " gifts") + ", total " + total + "."
                : "Here are the " + d.year + " tax statements for " + people.size() + (people.size() == 1 ? " contributor" : " contributors") + " (" + whoText + "): total " + total + ".")
            + ("ai".equals(d.template) ? " The wording is an AI draft — the names and figures come from your records." : "")
            + " Review " + (people.size() == 1 ? "it" : "them") + " below, then download the PDF.", KIND);
        Map<String, String> s = new LinkedHashMap<>(selection(d));
        s.put(people.size() == 1 ? "Gifts" : "Statements", String.valueOf(people.size() == 1 ? gifts : people.size()));
        s.put("Total", total);
        m.put("summary", s);
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("year", d.year);
        st.put("report", data);
        st.put("aiDraft", "ai".equals(d.template));
        st.put("count", people.size());
        st.put("filename", "tax-report-" + d.year + "-" + (one ? slug(d.personLabel) : d.who) + ".pdf");
        m.put("taxStatement", st);
        m.put("link", link("Open in Tax Report page", pageUrl));
        m.put("readOnly", true);
        return m;
    }

    /** The Tax Report page with this conversation's choices already made (an AI draft is drawn again there). */
    private static String pageUrl(Draft d) {
        StringBuilder u = new StringBuilder(PAGE).append("?year=").append(d.year);
        if ("one".equals(d.who)) {
            if (d.memberId != null) u.append("&memberId=").append(d.memberId);
            else if (d.guestName != null) u.append("&guestName=").append(URLEncoder.encode(d.guestName, StandardCharsets.UTF_8));
        } else u.append("&type=").append(d.who);
        u.append("&template=").append(d.template == null ? "mine" : d.template).append("&auto=1");
        return u.toString();
    }

    // ── reading the request ───────────────────────────────────────────────────

    private void readRequest(Draft d, String q) {
        Integer y = yearIn(q);
        if (y != null && badYear(y) == null) d.year = y;
        if (ONE.matcher(q).find()) d.who = "one";
        else if (NON.matcher(q).find()) d.who = TaxStatements.NONMEMBERS;
        else if (EVERYONE.matcher(q).find()) d.who = TaxStatements.ALL;
        else if (MEMBERS.matcher(q).find()) d.who = TaxStatements.MEMBERS;
        else {
            Matcher m = FOR_NAME.matcher(q);
            while (m.find()) {
                List<String> keep = new ArrayList<>();
                for (String w : m.group(1).split("\\s+")) { if (NOT_NAMES.contains(w.toLowerCase(Locale.ROOT)) || YEAR.matcher(w).matches()) break; keep.add(w); }
                if (!keep.isEmpty()) { d.who = "name:" + String.join(" ", keep); break; }
            }
        }
        String low = q.toLowerCase(Locale.ROOT);
        if (low.matches(".*\\b(ai|a\\.i\\.)\\s+(draft|written|generated|template)\\b.*") || low.contains("ai-created")) d.template = "ai";
        else if (low.contains("default template")) d.template = "default";
        else if (low.matches(".*\\b(my|our|own)\\s+(template|letter text|wording)\\b.*")) d.template = "mine";
    }

    static Integer yearIn(String s) {
        if (s == null) return null;
        Matcher m = YEAR.matcher(s);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    static String badYear(int y) {
        int now = AppClock.today().getYear();
        if (y > now) return y + " has not started yet.";
        if (y < 2000) return "I can generate tax reports from the year 2000 onwards.";
        return null;
    }

    static String whoFrom(String t) {
        if (t == null) return null;
        if (WHO_ONE.equalsIgnoreCase(t) || ONE.matcher(t).find() || t.toLowerCase(Locale.ROOT).matches("(a |one |specific |single )*(contributor|person|someone|somebody|one)")) return "one";
        if (WHO_NON.equalsIgnoreCase(t) || NON.matcher(t).find()) return TaxStatements.NONMEMBERS;
        if (WHO_ALL.equalsIgnoreCase(t) || EVERYONE.matcher(t).find() || t.equalsIgnoreCase("both")) return TaxStatements.ALL;
        if (WHO_MEMBERS.equalsIgnoreCase(t) || MEMBERS.matcher(t).find() || t.toLowerCase(Locale.ROOT).matches("members?")) return TaxStatements.MEMBERS;
        return null;
    }

    static String templateFrom(String t) {
        if (t == null) return null;
        String low = t.toLowerCase(Locale.ROOT);
        if (T_AI.equalsIgnoreCase(t) || low.matches(".*\\b(ai|a\\.i\\.)\\b.*")) return "ai";
        if (T_MINE.equalsIgnoreCase(t) || low.matches(".*\\b(my|mine|our|own|custom|upload(ed)?)\\b.*")) return "mine";
        if (T_DEFAULT.equalsIgnoreCase(t) || low.contains("default") || low.contains("standard") || low.contains("existing") || low.contains("original")) return "default";
        return null;
    }

    // ── access ────────────────────────────────────────────────────────────────

    /** The Tax Report page's own rule; a refusal says so and gives no data. */
    private Map<String, Object> gate(HttpServletRequest req, String clientId) {
        HttpSession s = req.getSession(false);
        AiAccess a = access.resolve(req, clientId);
        boolean allowed = !(a.member() || a.church() || a.kids() || AiAccessPolicy.isTemporaryAccess(s))
                && RoleGuard.requireAccountantOrAdmin(req) == null && RoleGuard.requirePagePermission(req, "accounting.reports") == null;
        if (!allowed) {
            Map<String, Object> m = base("guided.denied", "Your login doesn't have access to the Tax Report, so I can't generate one.", KIND);
            m.put("denied", true);
            return m;
        }
        if (!access.feature(clientId, "accounting")) return base("guided.unavailable", "Tax reports are not part of your current plan.", KIND);
        return null;
    }

    // ── plumbing ──────────────────────────────────────────────────────────────

    private Map<String, Object> ask(HttpSession session, Draft d, Stage stage, String question, List<String> choices) {
        d.stage = stage; d.choices = choices == null ? null : new ArrayList<>(choices); d.created = Instant.now();
        session.setAttribute(SESSION_KEY, d);
        Map<String, Object> m = base("guided.ask", question, KIND);
        m.put("asking", stage.name().toLowerCase(Locale.ROOT));
        m.put("pending", true);
        if (choices != null && !choices.isEmpty()) m.put("choices", choices);
        m.put("question", question);
        return m;
    }

    private Draft pending(HttpSession session, String clientId) {
        Object o = session.getAttribute(SESSION_KEY);
        if (!(o instanceof Draft d)) return null;
        if (!clientId.equals(d.clientId) || d.created == null || d.created.plusSeconds(Math.max(1, draftMinutes) * 60L).isBefore(Instant.now())) { clear(session); return null; }
        return d;
    }

    private static Map<String, Object> link(String label, String url) {
        Map<String, Object> l = new LinkedHashMap<>(); l.put("label", label); l.put("url", url); return l;
    }
    private static List<String> nonBlank(String... v) { List<String> o = new ArrayList<>(); for (String s : v) if (s != null && !s.isBlank()) o.add(s.trim()); return o; }
    private static String str(Object o) { return o == null ? "" : o.toString().trim(); }
    static String money(BigDecimal v) { return NumberFormat.getCurrencyInstance(Locale.US).format(v == null ? BigDecimal.ZERO : v); }
    static String slug(String s) { String t = s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", ""); return t.isEmpty() ? "contributor" : cap(t, 40); }
}
