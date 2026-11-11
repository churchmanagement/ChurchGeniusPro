package com.churchgeniuspro.ai.guided;

import com.churchgeniuspro.ai.action.AiActionFollowUp;
import com.churchgeniuspro.ai.action.AiActionLookup;
import com.churchgeniuspro.ai.action.AiActionLookup.Hit;
import com.churchgeniuspro.ai.action.AiActionLookup.Person;
import com.churchgeniuspro.ai.action.AiActionService;
import com.churchgeniuspro.ai.action.AiGroupTools;
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
 * 2026-11-11 — Genius Assistant conversation for Groups: "Add a group", "Create a group called Youth Choir",
 * "Add members to the Welcome Team".
 *
 * <p>Flow: the group name (asked when missing; a name that exists is reported and another asked) → review card
 * → the user's Save ({@code create_group}) → "Do you want to add a member to it?" → for each person: someone
 * already in the church directory (search by first name, last name or e-mail; matches offered as choices; no
 * match → Try again / Go to Groups / Cancel) or someone new (first and last name, then an e-mail — with a note
 * that without one the person is not included when the group is e-mailed) → review card → Save
 * ({@code add_group_person}) → "Do you want to add another member?" … until the user says no.
 *
 * <p>Nothing is written by this class: both saves are {@link AiActionService#confirm} (one-time token, permission
 * re-check, re-resolution by id, the Groups page's own services). It continues after a card's button through
 * {@link AiActionFollowUp}. Rules only — no model call. Permission = the Groups page's write rule (SuperAdmin /
 * Admin with {@code admin.groups.edit}), checked first and on every message; plan features {@code groups} and
 * {@code aiActions}; every lookup is this church's.
 */
@Service
public class AiGuidedGroupService implements AiActionFollowUp {

    public static final String SESSION_KEY = GuidedDrafts.GROUP;
    static final String KIND = "group";

    private final AiAccessPolicy access;
    private final AiActionService actions;
    private final AiActionLookup lookup;

    @Value("${ai.guided.draft-minutes:15}")
    public int draftMinutes = 15;

    public AiGuidedGroupService(AiAccessPolicy access, AiActionService actions, AiActionLookup lookup) {
        this.access = access; this.actions = actions; this.lookup = lookup;
    }

    enum Stage { NAME, GROUP_CARD, ASK_ADD, WHO, FIND, PICK, NO_MATCH, DIFFERENT, NEW_NAME, NEW_EMAIL, NO_EMAIL, PERSON_CARD }

    static final class Draft implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        String clientId; Instant created; Stage stage = Stage.NAME;
        String groupName; Long groupId; boolean createdHere; int added;
        String token;
        ArrayList<String> choices; ArrayList<Long> matchIds; String searched;
        Long memberId; String first, last, email, label; boolean emailDeclined;
    }

    // ── vocabulary ────────────────────────────────────────────────────────────

    static final String LEAD = "(?:please\\s+|can you\\s+|could you\\s+|i\\s+(?:want|need|would like)\\s+to\\s+|i'?d like to\\s+|let'?s\\s+|help me\\s+)?";
    static final Pattern START = Pattern.compile("(?i)^\\s*" + LEAD
        + "(?:create|add|make|new|set\\s?up|start|form|register)\\s+(?:an?\\s+|the\\s+|another\\s+|one\\s+)?(?:new\\s+)?(?:ministry\\s+|small\\s+|church\\s+)?group\\b"
        + "(?!\\s+(?:e-?mails?|members?|messages?|sms|texts?)\\b)(.*)$");
    static final Pattern START_MEMBERS = Pattern.compile("(?i)^\\s*" + LEAD
        + "(?:add|put|enrol+|include)\\s+(?:an?\\s+|a\\s+new\\s+|new\\s+|some\\s+|more\\s+|another\\s+)?(?:members?|people|person|someone|somebody)\\s+(?:to|into|in)\\s+(?:the\\s+|our\\s+|my\\s+)?(.+?)[.!]?\\s*$");
    static final Pattern NAME_LEAD = Pattern.compile("(?i)^\\s*(?:called|named|titled|with\\s+(?:the\\s+)?name|as|:|-|=|for(?:\\s+the)?)?\\s*");
    static final Pattern RENAME = Pattern.compile("(?i)^\\s*(?:(?:no|actually)[,!.]?\\s+)?(?:call\\s+it|name\\s+it|rename\\s+it(?:\\s+to)?|(?:change|set|update|correct)\\s+(?:the\\s+)?(?:group\\s+)?name(?:\\s+to)?)\\s+(.+)$");
    static final Pattern CHANGE_EMAIL = Pattern.compile("(?i)^\\s*(?:(?:no|actually)[,!.]?\\s+)?(?:change|set|update|correct|add|enter)\\s+(?:the\\s+|an?\\s+)?e-?mail(?:\\s+address)?(?:\\s+(?:to|as|is))?\\s*(.*)$");
    static final Pattern CHANGE_PERSON = Pattern.compile("(?i)^\\s*(?:(?:no|actually)[,!.]?\\s+)?(?:change|set|update|correct)\\s+(?:the\\s+)?(?:person'?s?\\s+)?name(?:\\s+to)?\\s+(.+)$");
    static final Pattern SAY_YES = Pattern.compile("(?i)^\\s*(yes|yeah|yep|yup|sure|ok|okay|please|yes please|go ahead|continue|add (one|another|a member|another member|more))[.!]?\\s*$");
    static final Pattern SAY_NO = Pattern.compile("(?i)^\\s*(no|nope|no thanks|not now|that'?s (all|it|everything)|done|i'?m done|finish|finished|no,? (that'?s all|finish|i'?m done))[.!]?\\s*$");
    static final Pattern SKIP = Pattern.compile("(?i)^\\s*(skip|skip it|none|no|nope|no e-?mail|no e-?mail address|n/?a|don'?t have (one|it)|doesn'?t have (one|an e-?mail)|not available|unknown)[.!]?\\s*$");
    static final Pattern EXISTING = Pattern.compile("(?i)\\b(existing|already|current|directory|in (the|our) (church|directory|list)|a member)\\b");
    static final Pattern NEW = Pattern.compile("(?i)\\b(new|not (a member|in the (directory|list))|someone new|new person|guest|visitor)\\b");

    public static boolean isStartRequest(String q) {
        return q != null && (START.matcher(q).find() || START_MEMBERS.matcher(q).find());
    }
    public static void clear(HttpSession s) { if (s != null) s.removeAttribute(SESSION_KEY); }

    // ── entry point ───────────────────────────────────────────────────────────

    /** Null = nothing to do with a group conversation. */
    public Map<String, Object> handle(HttpServletRequest req, String clientId, String question) {
        if (req == null || clientId == null || question == null) return null;
        HttpSession session = req.getSession(false);
        if (session == null) return null;
        String q = question.trim();
        if (q.isEmpty()) return null;
        Draft pending = pending(session, clientId);

        Matcher st = START.matcher(q), sm = START_MEMBERS.matcher(q);
        boolean start = st.find(), startMembers = !start && sm.find();
        if (startMembers) {                                   // only when it names exactly one of this church's groups
            Map<String, Object> g = gate(req, clientId);
            List<Hit> groups = g != null ? List.of() : lookup.groups(clientId, sm.group(1).replaceAll("(?i)\\s+group$", ""));
            if (g == null && groups.size() != 1) startMembers = false;
            else {
                dropOpenCard(req, pending); clear(session);
                if (g != null) return g;
                Draft d = new Draft(); d.clientId = clientId; d.groupId = groups.get(0).id(); d.groupName = groups.get(0).label();
                return askWho(session, d, null);
            }
        }
        if (start) {
            dropOpenCard(req, pending); clear(session);
            Map<String, Object> g = gate(req, clientId);
            if (g != null) return g;
            Draft d = new Draft(); d.clientId = clientId;
            d.groupName = AiSettingsTools.cleanName(NAME_LEAD.matcher(st.group(1) == null ? "" : st.group(1)).replaceFirst(""));
            return afterName(req, session, d);
        }
        if (pending == null) return null;
        if (pending.token != null && !actions.isOpen(clientId, pending.token)) { clear(session); return null; }     // the card expired
        Map<String, Object> g = gate(req, clientId);
        if (g != null) { dropOpenCard(req, pending); clear(session); return g; }
        return answer(req, session, pending, q);
    }

    private Map<String, Object> gate(HttpServletRequest req, String clientId) {
        AiAccess a = access.resolve(req, clientId);
        boolean allowed = !(a.member() || a.church() || a.kids() || AiAccessPolicy.isTemporaryAccess(req.getSession(false)))
                && AiGroupTools.mayEditGroups(req);
        if (!allowed) {
            Map<String, Object> m = base("guided.denied", "Your login doesn't have permission to add or change groups, so I can't do that. "
                + "Nothing was saved. Please ask your church administrator.", KIND);
            m.put("denied", true);
            return m;
        }
        if (!access.feature(clientId, "groups")) return base("guided.unavailable", "Groups are not part of your church's current plan.", KIND);
        if (!access.feature(clientId, FEATURE)) return withGroupsLink(base("guided.unavailable",
                "Creating groups from Genius Assistant is not part of your current plan. You can add the group on the Groups page.", KIND));
        if (!actions.isEnabled()) return withGroupsLink(base("guided.unavailable", "AI actions are switched off on this server. You can add the group on the Groups page.", KIND));
        return null;
    }

    private static Map<String, Object> withGroupsLink(Map<String, Object> m) {
        m.put("link", new LinkedHashMap<>(Map.of("label", "Open Groups", "url", "/groups")));
        return m;
    }

    // ── answers ───────────────────────────────────────────────────────────────

    private Map<String, Object> answer(HttpServletRequest req, HttpSession session, Draft d, String q) {
        String t = q.replaceAll("[.!]+$", "").trim();
        boolean cancel = CANCEL.matcher(q).matches();
        switch (d.stage) {
            case NAME -> {
                if (cancel) return cancelAll(session, d);
                if (q.endsWith("?") && QUESTION.matcher(q).find()) return null;
                Matcher rn = RENAME.matcher(t);
                d.groupName = AiSettingsTools.cleanName(NAME_LEAD.matcher(rn.matches() ? rn.group(1) : t).replaceFirst(""));
                if (d.groupName == null) return ask(session, d, Stage.NAME, "I couldn't use that as a name. What should the group be called?", null);
                return afterName(req, session, d);
            }
            case GROUP_CARD -> {
                if (cancel || NO.matcher(q).matches()) { String token = d.token; return actions.reject(req, d.clientId, token); }
                if (YES.matcher(q).matches() || SAY_YES.matcher(q).matches()) return actions.confirm(req, d.clientId, d.token);
                Matcher rn = RENAME.matcher(t);
                if (rn.matches()) {
                    dropOpenCard(req, d);
                    d.groupName = AiSettingsTools.cleanName(rn.group(1));
                    if (d.groupName == null) return ask(session, d, Stage.NAME, "What should the group be called?", null);
                    return afterName(req, session, d);
                }
                return null;
            }
            case ASK_ADD -> {
                if (SAY_YES.matcher(q).matches() || YES.matcher(q).matches()) return askWho(session, d, null);
                if (SAY_NO.matcher(q).matches() || cancel) return finish(session, d);
                if (EXISTING.matcher(t).find() || NEW.matcher(t).find()) { d.stage = Stage.WHO; return answer(req, session, d, q); }
                if (QUESTION.matcher(q).find()) return null;
                return ask(session, d, Stage.ASK_ADD, "Do you want to add " + (d.added > 0 ? "another" : "a") + " member to “" + d.groupName + "”?", List.of("Yes", "No"));
            }
            case WHO -> {
                if (cancel) return askDifferent(session, d, "Okay.");
                String l = t.toLowerCase(Locale.ROOT);
                if (l.startsWith("new") || (NEW.matcher(t).find() && !l.startsWith("exist"))) return ask(session, d, Stage.NEW_NAME,
                        "What is the person's first and last name? (for example: Daniel Ben)", null);
                if (l.startsWith("exist") || EXISTING.matcher(t).find()) return ask(session, d, Stage.FIND,
                        "What is their first name, last name or email address?", null);
                if (SAY_NO.matcher(q).matches()) return finish(session, d);
                if (QUESTION.matcher(q).find()) return null;
                return search(req, session, d, t);                   // they typed a name or an address straight away
            }
            case FIND -> {
                if (cancel) return askDifferent(session, d, "Okay.");
                return search(req, session, d, t);
            }
            case PICK -> {
                if (cancel) return askDifferent(session, d, "Okay.");
                String picked = d.choices == null ? null : AiActionService.pick(t, d.choices);
                int i = picked == null ? -1 : d.choices.indexOf(picked);
                if (i >= 0 && i < d.matchIds.size()) {
                    d.memberId = d.matchIds.get(i); d.first = null; d.last = null; d.email = null;
                    return personCard(req, session, d);
                }
                if (picked != null || t.toLowerCase(Locale.ROOT).matches("(none|none of (these|them)|not (here|listed)|no)")) return noMatchChoices(session, d,
                        "No problem. What would you like to do?");
                return search(req, session, d, t);                   // a different name or address
            }
            case NO_MATCH -> {
                String l = t.toLowerCase(Locale.ROOT);
                if (l.startsWith("try") || l.contains("again") || l.contains("search")) return ask(session, d, Stage.FIND, "What is their first name, last name or email address?", null);
                if (l.contains("group")) { clear(session); return withGroupsLink(base("guided.done", "Here is the Groups page — you can add members to “" + d.groupName + "” there.", KIND)); }
                if (l.startsWith("new") || NEW.matcher(t).find()) return ask(session, d, Stage.NEW_NAME, "What is the person's first and last name? (for example: Daniel Ben)", null);
                if (cancel || NO.matcher(q).matches()) return askDifferent(session, d, "Okay.");
                return search(req, session, d, t);
            }
            case DIFFERENT -> {
                if (SAY_YES.matcher(q).matches() || YES.matcher(q).matches()) return askWho(session, d, null);
                if (SAY_NO.matcher(q).matches() || cancel) return finish(session, d);
                if (QUESTION.matcher(q).find()) return null;
                return ask(session, d, Stage.DIFFERENT, "Do you want to add a different member to “" + d.groupName + "”?", List.of("Yes", "No"));
            }
            case NEW_NAME -> {
                if (cancel) return askDifferent(session, d, "Okay.");
                String[] parts = t.replaceAll("\\s+", " ").split(" ");
                String first = parts.length < 2 ? null : AiGroupTools.cleanPersonName(String.join(" ", Arrays.copyOfRange(parts, 0, parts.length - 1)));
                String last = parts.length < 2 ? null : AiGroupTools.cleanPersonName(parts[parts.length - 1]);
                if (first == null || last == null) return ask(session, d, Stage.NEW_NAME,
                        "I need both a first name and a last name, separated by a space (for example: Daniel Ben). What is the person's name?", null);
                d.memberId = null; d.first = first; d.last = last; d.email = null; d.emailDeclined = false;
                return ask(session, d, Stage.NEW_EMAIL, "What is " + first + "'s email address? (You can say “skip” if there isn't one.)", List.of("Skip"));
            }
            case NEW_EMAIL, NO_EMAIL -> {
                if (cancel) return askDifferent(session, d, "Okay — " + d.first + " " + d.last + " was not added.");
                Matcher ce = CHANGE_EMAIL.matcher(t);
                String value = ce.matches() ? ce.group(1).trim() : t;
                String em = AiGroupTools.cleanEmail(value);
                if (em != null && !em.isEmpty()) { d.email = em; return personCard(req, session, d); }
                if (d.stage == Stage.NO_EMAIL) {
                    if (SAY_YES.matcher(q).matches() || YES.matcher(q).matches()) return ask(session, d, Stage.NEW_EMAIL, "What is " + d.first + "'s email address?", null);
                    if (SAY_NO.matcher(q).matches() || SKIP.matcher(q).matches()) { d.email = ""; d.emailDeclined = true; return personCard(req, session, d); }
                    return ask(session, d, Stage.NO_EMAIL, "That doesn't look like an email address. Do you want to enter one for " + d.first + "?", List.of("Yes", "No"));
                }
                if (SKIP.matcher(q).matches() || value.isEmpty()) return ask(session, d, Stage.NO_EMAIL,
                        "You haven't entered an email address, so " + d.first + " won't be included when this group is emailed. Do you want to enter one?", List.of("Yes", "No"));
                return ask(session, d, Stage.NEW_EMAIL, "“" + cap(value, 60) + "” doesn't look like an email address. Please type it again, or say “skip”.", List.of("Skip"));
            }
            case PERSON_CARD -> {
                if (cancel || NO.matcher(q).matches()) { String token = d.token; return actions.reject(req, d.clientId, token); }
                if (YES.matcher(q).matches() || SAY_YES.matcher(q).matches() || t.toLowerCase(Locale.ROOT).matches("(submit|add|add (him|her|them)|add to (the )?group)")) return actions.confirm(req, d.clientId, d.token);
                Matcher ce = CHANGE_EMAIL.matcher(t);
                if (ce.matches() && d.memberId == null) {
                    String em = AiGroupTools.cleanEmail(ce.group(1));
                    dropOpenCard(req, d);
                    if (em == null || em.isEmpty()) return ask(session, d, Stage.NEW_EMAIL, "What is " + d.first + "'s email address? (You can say “skip”.)", List.of("Skip"));
                    d.email = em; return personCard(req, session, d);
                }
                Matcher cp = CHANGE_PERSON.matcher(t);
                if (cp.matches() && d.memberId == null) {
                    dropOpenCard(req, d); d.stage = Stage.NEW_NAME;
                    String keep = d.email; Map<String, Object> r = answer(req, session, d, cp.group(1));
                    if (d.stage == Stage.NEW_EMAIL && keep != null) { d.email = keep; return personCard(req, session, d); }
                    return r;
                }
                return null;
            }
            default -> { return null; }
        }
    }

    // ── steps ─────────────────────────────────────────────────────────────────

    /** The name is known (or not): ask for it, report a duplicate, or show the review card. */
    private Map<String, Object> afterName(HttpServletRequest req, HttpSession session, Draft d) {
        if (d.groupName == null) return ask(session, d, Stage.NAME, "What should the new group be called?", null);
        Map<String, Object> raw = new LinkedHashMap<>(); raw.put("name", d.groupName);
        Map<String, Object> p = actions.proposeGuided(req, access.resolve(req, d.clientId), d.clientId, "create_group", raw);
        if (p.get("token") == null) {
            String why = String.valueOf(p.get("refusal"));
            if (Boolean.TRUE.equals(p.get("denied"))) { clear(session); Map<String, Object> m = base("guided.denied", why + " Nothing was saved.", KIND); m.put("denied", true); return m; }
            if (why.contains("already exists")) { d.groupName = null; return ask(session, d, Stage.NAME, why + " What name should I use instead?", null); }
            if (why.contains("name is needed")) { d.groupName = null; return ask(session, d, Stage.NAME, why + " What should the group be called?", null); }
            clear(session);
            return base("guided.refused", why + " Nothing was saved.", KIND);
        }
        d.token = String.valueOf(p.get("token")); d.stage = Stage.GROUP_CARD; d.choices = null; d.created = Instant.now();
        @SuppressWarnings("unchecked") Map<String, Object> resolved = (Map<String, Object>) p.get("resolved");
        d.groupName = String.valueOf(resolved.get("name"));
        session.setAttribute(SESSION_KEY, d);
        Map<String, Object> m = base("guided.confirm", "Please review the new group. It is created only when you press Save group (or say “yes”).", KIND);
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("name", "Group name", d.groupName, true, "Change the name to "));
        m.put("confirm", card(d.token, p.get("proposalId"), "Save group", fields)); m.put("pending", true);
        return m;
    }

    private Map<String, Object> askWho(HttpSession session, Draft d, String lead) {
        d.memberId = null; d.first = null; d.last = null; d.email = null; d.matchIds = null; d.searched = null;
        return ask(session, d, Stage.WHO, (lead == null ? "" : lead + " ") + "Is the person already in your church directory, or someone new?",
                List.of("Existing member", "New person"));
    }

    private Map<String, Object> askDifferent(HttpSession session, Draft d, String lead) {
        return ask(session, d, Stage.DIFFERENT, lead + " Do you want to add a different member to “" + d.groupName + "”?", List.of("Yes", "No"));
    }

    private Map<String, Object> noMatchChoices(HttpSession session, Draft d, String lead) {
        return ask(session, d, Stage.NO_MATCH, lead, List.of("Try again", "New person", "Go to Groups", "Cancel"));
    }

    /** Directory search by first name, last name or e-mail; the matches become the choices. */
    private Map<String, Object> search(HttpServletRequest req, HttpSession session, Draft d, String text) {
        String t = text.replaceAll("(?i)^(?:it'?s|its|the name is|name is|named|called|email is|e-?mail is)\\s+", "").trim();
        if (t.length() < 2) return ask(session, d, Stage.FIND, "Please type at least two letters of their first name, last name or email address.", null);
        List<Person> hits = lookup.searchPeople(d.clientId, t, 9);
        d.searched = cap(t, 60);
        if (hits.isEmpty()) return noMatchChoices(session, d, "I couldn't find anyone in the church directory matching “" + d.searched + "”. What would you like to do?");
        boolean more = hits.size() > 8; if (more) hits = hits.subList(0, 8);
        ArrayList<String> labels = new ArrayList<>(); ArrayList<Long> ids = new ArrayList<>();
        for (Person p : hits) {
            String label = p.name() + (p.email().isBlank() ? " — no email on file" : " — " + p.email());
            if (labels.contains(label)) label = label + " (#" + p.id() + ")";
            labels.add(label); ids.add(p.id());
        }
        labels.add("None of these");
        d.matchIds = ids;
        String lead = hits.size() == 1 ? "I found one person matching “" + d.searched + "”. Is this who you mean?"
                : "I found " + hits.size() + (more ? " or more" : "") + " people matching “" + d.searched + "”. Who should I add?"
                  + (more ? " (Type more of the name to narrow it down.)" : "");
        return ask(session, d, Stage.PICK, lead, labels);
    }

    private Map<String, Object> personCard(HttpServletRequest req, HttpSession session, Draft d) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("groupId", d.groupId);
        if (d.memberId != null) raw.put("memberId", d.memberId);
        else { raw.put("firstName", d.first); raw.put("lastName", d.last); raw.put("email", d.email == null ? "" : d.email); }
        Map<String, Object> p = actions.proposeGuided(req, access.resolve(req, d.clientId), d.clientId, "add_group_person", raw);
        if (p.get("token") == null) {
            String why = String.valueOf(p.get("refusal"));
            if (Boolean.TRUE.equals(p.get("denied"))) { clear(session); Map<String, Object> m = base("guided.denied", why + " Nothing was saved.", KIND); m.put("denied", true); return m; }
            if (why.contains("group no longer exists")) { clear(session); return withGroupsLink(base("guided.refused", why + " Nothing was saved.", KIND)); }
            return askDifferent(session, d, why);                     // already in the group, left the directory, bad e-mail …
        }
        @SuppressWarnings("unchecked") Map<String, Object> r = (Map<String, Object>) p.get("resolved");
        d.token = String.valueOf(p.get("token")); d.stage = Stage.PERSON_CARD; d.choices = null; d.created = Instant.now();
        d.label = String.valueOf(r.get("label"));
        session.setAttribute(SESSION_KEY, d);
        String em = String.valueOf(r.getOrDefault("email", ""));
        boolean directory = d.memberId != null;
        Map<String, Object> m = base("guided.confirm", "Please review. " + d.label + " is added to “" + d.groupName + "” only when you press Add to group (or say “yes”)."
                + (em.isBlank() ? " There is no email address, so " + (directory ? "this person" : d.first) + " won't be included when the group is emailed." : ""), KIND);
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("person", directory ? "Member (church directory)" : "New person", d.label, true, directory ? null : "Change the name to "));
        fields.add(field("email", "Email", em.isBlank() ? null : em, false, directory ? null : "Change the email to "));
        fields.add(field("group", "Group", d.groupName, true, null));
        m.put("confirm", card(d.token, p.get("proposalId"), "Add to group", fields)); m.put("pending", true);
        return m;
    }

    private Map<String, Object> finish(HttpSession session, Draft d) {
        clear(session);
        int n = d.groupId == null ? 0 : lookup.groupSize(d.clientId, d.groupId);
        String text = (d.added == 0 ? "Okay — no members were added. " : "All done. ")
                + "“" + d.groupName + "” has " + n + (n == 1 ? " member" : " members") + ". You can manage the group on the Groups page.";
        return withGroupsLink(base("guided.done", text, KIND));
    }

    private Map<String, Object> cancelAll(HttpSession session, Draft d) {
        clear(session);
        return base("guided.cancelled", "Okay — I have not created a group.", KIND);
    }

    // ── after a card was saved / cancelled (button or "yes" / "no") ────────────

    @Override
    public void confirmed(HttpServletRequest req, String clientId, String token, String tool, Map<String, Object> result, Map<String, Object> response) {
        HttpSession session = req == null ? null : req.getSession(false);
        Draft d = session == null ? null : pending(session, clientId);
        if (d == null || token == null || !token.equals(d.token)) return;
        d.token = null;
        if ("create_group".equals(tool)) {
            Object id = result.get("groupId");
            if (id == null) { clear(session); return; }
            d.groupId = Long.valueOf(String.valueOf(id)); d.createdHere = true;
            if (result.get("groupName") != null) d.groupName = String.valueOf(result.get("groupName"));
            follow(session, d, Stage.ASK_ADD, response, "The group “" + d.groupName + "” was created.", "Do you want to add a member to it?", List.of("Yes", "No"));
        } else if ("add_group_person".equals(tool)) {
            d.added++;
            follow(session, d, Stage.ASK_ADD, response, d.label + " has been added to “" + d.groupName + "”.", "Do you want to add another member?", List.of("Yes", "No"));
        }
    }

    @Override
    public void rejected(HttpServletRequest req, String clientId, String token, Map<String, Object> response) {
        HttpSession session = req == null ? null : req.getSession(false);
        Draft d = session == null ? null : pending(session, clientId);
        if (d == null || token == null || !token.equals(d.token)) return;
        d.token = null;
        if (d.stage == Stage.GROUP_CARD) { clear(session); response.put("answer", "Okay — I have not created the group."); response.put("kind", KIND); return; }
        follow(session, d, Stage.DIFFERENT, response, "Okay — " + d.label + " was not added.", "Do you want to add a different member to “" + d.groupName + "”?", List.of("Yes", "No"));
    }

    private void follow(HttpSession session, Draft d, Stage next, Map<String, Object> response, String done, String question, List<String> choices) {
        d.stage = next; d.choices = new ArrayList<>(choices); d.created = Instant.now();
        session.setAttribute(SESSION_KEY, d);
        response.put("answer", done + " " + question);
        response.put("done", done); response.put("question", question);
        response.put("choices", choices); response.put("pending", true); response.put("asking", next.name().toLowerCase(Locale.ROOT)); response.put("kind", KIND);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> ask(HttpSession session, Draft d, Stage stage, String question, List<String> choices) {
        d.stage = stage; d.choices = choices == null ? null : new ArrayList<>(choices); d.created = Instant.now();
        session.setAttribute(SESSION_KEY, d);
        Map<String, Object> m = base("guided.ask", question, KIND);
        m.put("asking", stage.name().toLowerCase(Locale.ROOT)); m.put("pending", true);
        if (choices != null && !choices.isEmpty()) { m.put("choices", choices); m.put("question", question); }
        return m;
    }

    private void dropOpenCard(HttpServletRequest req, Draft d) {
        if (d == null || d.token == null) return;
        String token = d.token; d.token = null;                       // cleared first: this is not the user's Cancel, no follow-up question
        try { actions.reject(req, d.clientId, token); } catch (RuntimeException ignore) { }
    }

    private Draft pending(HttpSession session, String clientId) {
        Object o = session.getAttribute(SESSION_KEY);
        if (!(o instanceof Draft d)) return null;
        if (!clientId.equals(d.clientId) || d.created == null || d.created.plusSeconds(60L * Math.max(1, draftMinutes)).isBefore(Instant.now())) {
            session.removeAttribute(SESSION_KEY); return null;
        }
        return d;
    }

    static Map<String, Object> card(String token, Object proposalId, String label, List<Map<String, Object>> fields) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("token", token); c.put("proposalId", proposalId); c.put("label", label);
        c.put("endpoint", "/api/ai/actions/confirm"); c.put("cancelEndpoint", "/api/ai/actions/reject");
        Map<String, String> summary = new LinkedHashMap<>();
        for (Map<String, Object> f : fields) summary.put(String.valueOf(f.get("label")), f.get("value") == null ? "Not entered" : String.valueOf(f.get("value")));
        c.put("summary", summary); c.put("fields", fields);
        return c;
    }

    static Map<String, Object> field(String key, String label, String value, boolean required, String edit) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("key", key); f.put("label", label); f.put("value", value); f.put("required", required);
        if (edit != null) f.put("edit", edit);
        return f;
    }
}
