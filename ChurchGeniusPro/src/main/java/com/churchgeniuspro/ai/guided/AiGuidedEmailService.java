package com.churchgeniuspro.ai.guided;

import com.churchgeniuspro.ai.action.AiActionService;
import com.churchgeniuspro.ai.action.AiGroupTools;
import com.churchgeniuspro.ai.write.AiWritingService;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.NotifyEmailService;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.churchgeniuspro.ai.guided.AiGuidedRecordService.*;

/**
 * 2026-11-11 — Genius Assistant conversation for composing an e-mail: "Compose an email", "I want to create an
 * email", "Send an email to the members about the picnic".
 *
 * <p>Flow: write it with AI or yourself? → the subject →
 * <ul>
 *   <li>AI: what it should say → tone → length → language → a draft ({@link AiWritingService}, the Email page's
 *       own "Draft with AI"), then Rewrite / Shorten / Expand / Fix grammar / Translate, a typed instruction
 *       ("make it friendlier"), or the user's own text — as often as wanted;</li>
 *   <li>yourself: type the message;</li>
 * </ul>
 * → who receives it — the Email page's Recipient Type list (Members, Event Registrants + which event, Church
 * Guests, Group Email + the addresses) → a review card with the subject, the message, the recipients and their
 * number, where files can be attached → the user's explicit <b>Send email</b>.
 *
 * <p>SENDING. This is the one Genius Assistant conversation that sends something, at the user's request
 * (2026-11-11). It is deliberately NOT an {@code AiTool}: the model can never choose it and an external (MCP)
 * client can never reach it. The message leaves only through {@link #send} — called by the review card's button
 * or a typed "send" — which re-checks the Email page's own rule, consumes a one-time token, and hands the draft
 * kept on THIS session to {@link NotifyEmailService#sendEmail}, the method behind the Email page's Send button
 * (so the church's sender settings, the tenant-scoped recipient lists and the Trial / Demo "test address only"
 * rule all apply unchanged). Nothing is sent by a draft, a choice or a question.
 *
 * <p>Permission = the Email page's rule ({@code requireAdminOrUser} + {@code general.email}), staff logins only,
 * checked first and on every message and again at Send; plan features {@code composeEmail} and {@code aiActions}
 * (+ {@code aiWrite} for the AI path — without it the conversation simply continues with the user's own text).
 * No member data is put into a prompt: the model sees the subject, the user's request and the text itself.
 */
@Service
public class AiGuidedEmailService {

    public static final String SESSION_KEY = GuidedDrafts.EMAIL;
    static final String KIND = "email";
    public static final int MAX_SUBJECT = 200, MAX_BODY = 8000, MAX_ADDRESSES = 200, MAX_FILES = 10;

    private final AiAccessPolicy access;
    private final AiActionService actions;
    private final NotifyEmailService mail;
    private final AiWritingService writer;

    @Value("${ai.guided.draft-minutes:15}")
    public int draftMinutes = 15;
    /** An e-mail takes longer to write than an income entry: the draft is kept this long between messages. */
    @Value("${ai.guided.email-draft-minutes:45}")
    public int emailDraftMinutes = 45;

    public AiGuidedEmailService(AiAccessPolicy access, AiActionService actions, NotifyEmailService mail, AiWritingService writer) {
        this.access = access; this.actions = actions; this.mail = mail; this.writer = writer;
    }

    enum Stage { MODE, SUBJECT, TOPIC, TONE, LENGTH, LANGUAGE, DRAFT, TRANSLATE, EDIT_BODY, BODY, RECIPIENTS, EVENT, ADDRESSES, REVIEW }

    static final class Draft implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        String clientId; Instant created; Stage stage = Stage.MODE; Stage returnTo;
        boolean ai; String subject, topic, tone, length, language, body;
        String recipientType, recipientLabel; Integer eventId; String eventLabel; ArrayList<String> addresses; int count;
        ArrayList<String> choices; ArrayList<Integer> eventIds; String token;
    }

    // ── vocabulary ────────────────────────────────────────────────────────────

    static final String LEAD = AiGuidedGroupService.LEAD;
    static final Pattern START = Pattern.compile("(?i)^\\s*" + LEAD
        + "(?:(?:compose|write|draft|create|send|prepare|make|start|new)\\s+(?:an?\\s+|the\\s+|another\\s+)?(?:new\\s+)?(?:church\\s+|group\\s+)?(?:e-?mails?|e-?mail\\s+messages?)"
        + "|e-?mail\\s+(?:all\\s+)?(?:the\\s+|our\\s+)?(?:members|congregation|church|guests|church\\s+guests|event\\s+registrants|registrants|everyone))\\b(.*)$");
    static final Pattern ABOUT = Pattern.compile("(?i)\\b(?:about|regarding|re:|on the subject of|announcing|to announce|inviting|to invite|reminding|to remind|saying|that says)\\s+(.+)$");
    static final Pattern TO_WHOM = Pattern.compile("(?i)\\b(?:to|for|e-?mail)\\s+(?:all\\s+)?(?:the\\s+|our\\s+)?(members|congregation|church\\s+guests|guests|visitors|event\\s+registrants|registrants)\\b");
    static final Pattern CHANGE = Pattern.compile(
        "(?i)^\\s*(?:(?:no|actually)[,!.]?\\s+)?(?:please\\s+)?(?:change|set|update|edit|correct)\\s+(?:the\\s+)?(subject|recipients?|message|text|body|content|email)\\b\\s*(?:to|as|is|:|=)?\\s*(.*)$");
    static final Pattern SEND = Pattern.compile("(?i)^\\s*(send|send it|send (the )?e-?mail|send now|yes,? send( it)?|yes|yeah|yep|ok|okay|go ahead|confirm|looks good,? send( it)?)[.!]?\\s*$");
    static final Pattern USE = Pattern.compile("(?i)^\\s*(use (this|it|this draft|the draft)|looks good|good|perfect|great|that'?s (good|fine|great|perfect)|continue|next|keep it|ok|okay|yes|done|i'?m happy|happy with it|choose recipients)[.!]?\\s*$");
    static final List<String> TONE_CHOICES = List.of("Warm", "Formal", "Casual", "Urgent", "Encouraging", "Celebratory");
    static final List<String> LENGTH_CHOICES = List.of("Short", "Medium", "Long");
    static final List<String> LANGUAGE_CHOICES = List.of("English", "Spanish", "French", "Portuguese", "Korean", "Chinese", "Hindi", "Malayalam");
    static final List<String> DRAFT_CHOICES = List.of("Use this draft", "Rewrite", "Shorten", "Expand", "Fix grammar", "Translate", "Edit the text", "Change the subject");
    static final List<String> RECIPIENT_CHOICES = List.of("Members", "Event Registrants", "Church Guests", "Group Email");

    public static boolean isStartRequest(String q) { return q != null && START.matcher(q).find(); }
    public static void clear(HttpSession s) { if (s != null) s.removeAttribute(SESSION_KEY); }

    /** The Email page's rule, for a staff login. */
    public static boolean mayCompose(HttpServletRequest req) {
        HttpSession s = req == null ? null : req.getSession(false);
        if (s == null || s.getAttribute("memberId") != null) return false;
        return RoleGuard.requireAdminOrUser(req) == null && RoleGuard.requirePermission(req, "general.email") == null;
    }

    // ── entry point ───────────────────────────────────────────────────────────

    public Map<String, Object> handle(HttpServletRequest req, String clientId, String question) {
        if (req == null || clientId == null || question == null) return null;
        HttpSession session = req.getSession(false);
        if (session == null) return null;
        String q = question.trim();
        if (q.isEmpty()) return null;
        Draft pending = pending(session, clientId);

        Matcher st = START.matcher(q);
        // while writing, "send an email …" typed as the MESSAGE text must not restart the conversation
        boolean typingText = pending != null && (pending.stage == Stage.BODY || pending.stage == Stage.EDIT_BODY || pending.stage == Stage.TOPIC || pending.stage == Stage.SUBJECT);
        if (st.find() && !typingText) {
            clear(session);
            Map<String, Object> gate = gate(req, clientId);
            if (gate != null) return gate;
            Draft d = new Draft(); d.clientId = clientId; d.created = Instant.now();
            String rest = st.group(1) == null ? "" : st.group(1);
            Matcher to = TO_WHOM.matcher(q);
            if (to.find()) presetRecipients(d, to.group(1));
            Matcher ab = ABOUT.matcher(rest);
            if (ab.find()) d.topic = cap(ab.group(1).replaceAll("[.!]+$", "").trim(), 2000);
            if (!aiAvailable(clientId)) { d.ai = false; return ask(session, d, Stage.SUBJECT, "Let's write an email. AI writing isn't available for this church right now, so you will type the message yourself. What is the subject?", null); }
            return ask(session, d, Stage.MODE, "Let's write an email. Would you like me to write it with AI, or will you write it yourself?",
                    List.of("Write with AI", "Write it myself"));
        }
        if (pending == null) return null;
        Map<String, Object> gate = gate(req, clientId);
        if (gate != null) { clear(session); return gate; }
        if (CANCEL.matcher(q).matches()) { clear(session); return base("guided.cancelled", "Okay — the email was not sent and the draft is discarded.", KIND); }
        return answer(req, session, pending, q);
    }

    private Map<String, Object> gate(HttpServletRequest req, String clientId) {
        AiAccess a = access.resolve(req, clientId);
        boolean allowed = !(a.member() || a.church() || a.kids() || AiAccessPolicy.isTemporaryAccess(req.getSession(false))) && mayCompose(req);
        if (!allowed) {
            Map<String, Object> m = base("guided.denied", "Your login doesn't have permission to compose email, so I can't do that. Nothing was sent. "
                + "Please ask your church administrator.", KIND);
            m.put("denied", true);
            return m;
        }
        if (!access.feature(clientId, "composeEmail")) return base("guided.unavailable", "Compose Email is not part of your church's current plan.", KIND);
        if (!access.feature(clientId, FEATURE) || !actions.isEnabled()) {
            Map<String, Object> m = base("guided.unavailable", "Composing email from Genius Assistant is not available on your current plan. You can write it on the Email page.", KIND);
            m.put("link", new LinkedHashMap<>(Map.of("label", "Open Email", "url", "/notifyEmail")));
            return m;
        }
        return null;
    }

    boolean aiAvailable(String clientId) { return writer != null && writer.isEnabled() && access.feature(clientId, "aiWrite"); }

    // ── answers ───────────────────────────────────────────────────────────────

    private Map<String, Object> answer(HttpServletRequest req, HttpSession session, Draft d, String q) {
        String t = q.trim();
        String l = t.toLowerCase(Locale.ROOT).replaceAll("[.!]+$", "");
        switch (d.stage) {
            case MODE -> {
                if (l.contains("myself") || l.contains("manual") || l.contains("my own") || l.startsWith("i'll write") || l.equals("me") || l.contains("write it my")) d.ai = false;
                else if (l.contains("ai") || l.contains("you write") || l.contains("genius") || l.contains("draft") || l.contains("write it for me") || l.equals("yes")) d.ai = true;
                else if (QUESTION.matcher(q).find()) return null;
                else return ask(session, d, Stage.MODE, "Should I write the email with AI, or will you write it yourself?", List.of("Write with AI", "Write it myself"));
                return ask(session, d, Stage.SUBJECT, "What is the subject of the email?", null);
            }
            case SUBJECT -> {
                String s = cleanLine(t, MAX_SUBJECT);
                if (s == null) return ask(session, d, Stage.SUBJECT, "I need a subject line (up to " + MAX_SUBJECT + " characters). What is the subject?", null);
                d.subject = s;
                if (d.returnTo != null) return backTo(req, session, d);
                if (!d.ai) return ask(session, d, Stage.BODY, "Please type the message of the email. (You can attach files on the review step.)", null);
                if (d.topic != null) return ask(session, d, Stage.TONE, "Which tone should it have?", TONE_CHOICES);
                return ask(session, d, Stage.TOPIC, "What should the email say? A sentence or a few key points is enough.", null);
            }
            case TOPIC -> {
                if (t.length() < 3) return ask(session, d, Stage.TOPIC, "Please tell me in a few words what the email should say.", null);
                d.topic = cap(t, 2000);
                return ask(session, d, Stage.TONE, "Which tone should it have?", TONE_CHOICES);
            }
            case TONE -> {
                String pick = oneOf(l, TONE_CHOICES);
                if (pick == null) { if (QUESTION.matcher(q).find()) return null; return ask(session, d, Stage.TONE, "Please choose a tone.", TONE_CHOICES); }
                d.tone = pick.toLowerCase(Locale.ROOT);
                return ask(session, d, Stage.LENGTH, "How long should it be?", LENGTH_CHOICES);
            }
            case LENGTH -> {
                String pick = oneOf(l, LENGTH_CHOICES);
                if (pick == null) { if (QUESTION.matcher(q).find()) return null; return ask(session, d, Stage.LENGTH, "Please choose a length.", LENGTH_CHOICES); }
                d.length = pick.toLowerCase(Locale.ROOT);
                return ask(session, d, Stage.LANGUAGE, "Which language should it be written in?", LANGUAGE_CHOICES);
            }
            case LANGUAGE -> {
                String lang = language(l);
                if (lang == null) return ask(session, d, Stage.LANGUAGE, "I can write in " + String.join(", ", sortedLanguages()) + ". Which language?", LANGUAGE_CHOICES);
                d.language = lang;
                return write(session, d, "draft", null, "Here is a draft. You can use it, or ask me to change it.");
            }
            case TRANSLATE -> {
                String lang = language(l);
                if (lang == null) return ask(session, d, Stage.TRANSLATE, "I can translate into " + String.join(", ", sortedLanguages()) + ". Which language?", LANGUAGE_CHOICES);
                d.language = lang;
                return write(session, d, "translate", null, "Here is the " + lang + " version.");
            }
            case DRAFT -> {
                if (USE.matcher(t).matches() || l.startsWith("use")) return askRecipients(session, d, null);
                if (l.equals("rewrite") || l.equals("regenerate") || l.equals("try again") || l.equals("another version")) return write(session, d, "rewrite", null, "Here is another version.");
                if (l.startsWith("shorten") || l.equals("shorter") || l.equals("make it shorter")) return write(session, d, "shorten", null, "Here is a shorter version.");
                if (l.startsWith("expand") || l.equals("longer") || l.equals("make it longer")) return write(session, d, "expand", null, "Here is a longer version.");
                if (l.startsWith("fix") || l.contains("grammar") || l.contains("spelling")) return write(session, d, "fix", null, "I corrected the spelling and grammar.");
                if (l.startsWith("translate")) {
                    String lang = language(l.replaceFirst("^translate\\s*(it\\s*)?(in)?to\\s*", "").replaceFirst("^translate\\s*", ""));
                    if (lang != null) { d.language = lang; return write(session, d, "translate", null, "Here is the " + lang + " version."); }
                    return ask(session, d, Stage.TRANSLATE, "Which language should I translate it into?", LANGUAGE_CHOICES);
                }
                if (l.startsWith("edit") || l.contains("my own text") || l.contains("type it")) return ask(session, d, Stage.EDIT_BODY,
                        "Type or paste the text you want. It replaces the current draft.", null);
                Matcher c = CHANGE.matcher(t);
                if (c.matches()) return change(req, session, d, c.group(1), c.group(2));
                if (QUESTION.matcher(q).find() && q.endsWith("?")) return null;
                if (t.length() < 4) return showDraft(session, d, "Please choose what to do with the draft.");
                return write(session, d, "rewrite", cap(t, 500), "Here is the updated draft.");          // "make it friendlier", "mention the potluck"
            }
            case EDIT_BODY, BODY -> {
                String body = cleanBody(t);
                if (body == null) return ask(session, d, d.stage, "The message can't be empty. Please type the message of the email.", null);
                d.body = body;
                if (d.returnTo != null) return backTo(req, session, d);
                if (d.stage == Stage.EDIT_BODY && d.ai) return showDraft(session, d, "I replaced the draft with your text.");
                return askRecipients(session, d, null);
            }
            case RECIPIENTS -> {
                String pick = oneOf(l, RECIPIENT_CHOICES);
                if (pick == null && (l.contains("member") || l.contains("congregation") || l.contains("everyone"))) pick = "Members";
                if (pick == null && (l.contains("registrant") || l.contains("event"))) pick = "Event Registrants";
                if (pick == null && (l.contains("guest") || l.contains("visitor"))) pick = "Church Guests";
                if (pick == null && (l.contains("group") || l.contains("address") || l.contains("@") || l.contains("specific"))) pick = "Group Email";
                if (pick == null) { if (QUESTION.matcher(q).find()) return null; return askRecipients(session, d, "Please choose who should receive it."); }
                if (pick.equals("Group Email") && l.contains("@")) { d.recipientType = "group-email"; d.recipientLabel = "Group Email"; d.stage = Stage.ADDRESSES; return answer(req, session, d, q); }
                return setRecipients(req, session, d, pick);
            }
            case EVENT -> {
                String picked = d.choices == null ? null : AiActionService.pick(t, d.choices);
                int i = picked == null ? -1 : d.choices.indexOf(picked);
                if (i < 0 || d.eventIds == null || i >= d.eventIds.size()) {
                    if (l.contains("other") || l.contains("different") || l.contains("back")) return askRecipients(session, d, null);
                    return ask(session, d, Stage.EVENT, "Please choose one of the events.", d.choices);
                }
                d.eventId = d.eventIds.get(i); d.eventLabel = picked;
                d.count = mail.getRecipients("event-guests", d.eventId, d.clientId).size();
                if (d.count == 0) return askRecipients(session, d, "Nobody registered for “" + picked + "” has an email address. Please choose other recipients.");
                d.recipientType = "event-guests"; d.recipientLabel = "Event Registrants — " + picked;
                return review(session, d, null);
            }
            case ADDRESSES -> {
                List<String> good = new ArrayList<>(), bad = new ArrayList<>();
                for (String part : t.replaceAll("(?i)^\\s*(?:group\\s+e-?mail\\s*:?|send (?:it )?to|to|addresses?\\s*:?)\\s*", "").split("[,;\\s\\n]+")) {
                    if (part.isBlank()) continue;
                    String e = AiGroupTools.cleanEmail(part.replaceAll("^[<(\\[]+|[>)\\]]+$", ""));
                    if (e == null || e.isEmpty()) bad.add(cap(part, 60)); else if (!good.contains(e)) good.add(e);
                }
                if (!bad.isEmpty()) return ask(session, d, Stage.ADDRESSES, (bad.size() == 1 ? "This doesn't look like an email address: " : "These don't look like email addresses: ")
                        + String.join(", ", bad.subList(0, Math.min(5, bad.size()))) + ". Please type all the addresses again, separated by commas.", null);
                if (good.isEmpty()) return ask(session, d, Stage.ADDRESSES, "Please type the email addresses, separated by commas (for example: anna@example.com, ben@example.com).", null);
                if (good.size() > MAX_ADDRESSES) return ask(session, d, Stage.ADDRESSES, "That is more than " + MAX_ADDRESSES + " addresses. Please send to fewer addresses, or use Members or Church Guests.", null);
                d.addresses = new ArrayList<>(good); d.count = good.size();
                d.recipientType = "group-email"; d.recipientLabel = "Group Email — " + (good.size() == 1 ? good.get(0) : good.size() + " addresses");
                return review(session, d, null);
            }
            case REVIEW -> {
                if (SEND.matcher(t).matches()) return send(req, d.clientId, d.token, null);
                if (NO.matcher(q).matches()) return review(session, d, "Okay — it has not been sent. Change anything with Edit, or say “cancel” to discard it.");
                Matcher c = CHANGE.matcher(t);
                if (c.matches()) return change(req, session, d, c.group(1), c.group(2));
                if (l.startsWith("edit the text") || l.equals("edit") || l.startsWith("edit the message")) return change(req, session, d, "message", "");
                if (l.startsWith("change the recipients") || l.startsWith("change recipients")) return change(req, session, d, "recipients", "");
                return null;
            }
            default -> { return null; }
        }
    }

    /** "Change the subject / message / recipients" from the draft or the review card; returns to where the user was. */
    private Map<String, Object> change(HttpServletRequest req, HttpSession session, Draft d, String field, String value) {
        String f = field.toLowerCase(Locale.ROOT);
        d.token = null;
        Stage from = d.stage == Stage.DRAFT ? Stage.DRAFT : Stage.REVIEW;
        if (f.startsWith("subject")) {
            String s = cleanLine(value, MAX_SUBJECT);
            if (s != null) { d.subject = s; d.returnTo = from; return backTo(req, session, d); }
            d.returnTo = from;
            return ask(session, d, Stage.SUBJECT, "What should the subject be?", null);
        }
        if (f.startsWith("recipient")) { d.returnTo = null; return askRecipients(session, d, null); }
        String body = cleanBody(value);
        if (body != null) { d.body = body; d.returnTo = from; return backTo(req, session, d); }
        d.returnTo = from;
        return ask(session, d, Stage.EDIT_BODY, "Type or paste the text you want. It replaces the current message.", null);
    }

    private Map<String, Object> backTo(HttpServletRequest req, HttpSession session, Draft d) {
        Stage to = d.returnTo; d.returnTo = null;
        if (to == Stage.DRAFT) return showDraft(session, d, "Updated.");
        if (d.recipientType == null) return askRecipients(session, d, null);
        return review(session, d, "Updated.");
    }

    // ── AI writing ────────────────────────────────────────────────────────────

    /** One {@link AiWritingService} call; the result replaces the draft. Failures keep what was there. */
    private Map<String, Object> write(HttpSession session, Draft d, String action, String guidance, String lead) {
        if (!aiAvailable(d.clientId)) {
            d.ai = false;
            if (d.body == null) return ask(session, d, Stage.BODY, "AI writing is not available right now, so please type the message of the email.", null);
            return showDraft(session, d, "AI writing is not available right now — the text is unchanged.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action); body.put("channel", "email");
        body.put("tone", d.tone == null ? "warm" : d.tone); body.put("length", d.length == null ? "medium" : d.length);
        body.put("language", d.language == null ? "English" : d.language);
        if ("draft".equals(action)) body.put("prompt", "Subject: " + d.subject + "\n" + d.topic);
        else { body.put("text", d.body); if (guidance != null) body.put("prompt", guidance); }
        AiWritingService.Draft out;
        try { out = writer.write(AiWritingService.validate(body)); }
        catch (IllegalArgumentException e) { return d.body == null ? ask(session, d, Stage.TOPIC, e.getMessage() + " What should the email say?", null) : showDraft(session, d, e.getMessage()); }
        catch (RuntimeException e) { out = null; }
        if (out == null || out.body() == null || out.body().isBlank()) {
            if (d.body == null) return ask(session, d, Stage.BODY, "I couldn't write a draft just now. Please type the message yourself, or say “cancel”.", null);
            return showDraft(session, d, "I couldn't do that just now — the text is unchanged. Please try again.");
        }
        d.body = cap(out.body(), MAX_BODY);
        return showDraft(session, d, lead);
    }

    private Map<String, Object> showDraft(HttpSession session, Draft d, String lead) {
        Map<String, Object> m = ask(session, d, Stage.DRAFT, lead + " When you are happy with it, choose “Use this draft”.", DRAFT_CHOICES);
        m.put("question", lead);
        m.put("preview", preview(d));
        return m;
    }

    private static Map<String, Object> preview(Draft d) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("subject", d.subject); p.put("body", d.body);
        return p;
    }

    // ── recipients ────────────────────────────────────────────────────────────

    private void presetRecipients(Draft d, String word) {
        String w = word.toLowerCase(Locale.ROOT);
        String type = w.contains("registrant") ? null : (w.contains("guest") || w.contains("visitor")) ? "church-guests" : "members";
        if (type == null) return;                                   // an event still has to be chosen
        int n = mail.getRecipients(type, null, d.clientId).size();
        if (n == 0) return;
        d.recipientType = type; d.recipientLabel = type.equals("members") ? "Members" : "Church Guests"; d.count = n;
    }

    private Map<String, Object> askRecipients(HttpSession session, Draft d, String lead) {
        if (lead == null && d.recipientType != null && d.count > 0 && d.stage != Stage.REVIEW && d.stage != Stage.RECIPIENTS) return review(session, d, null);
        d.recipientType = null; d.eventId = null; d.addresses = null; d.count = 0;
        return ask(session, d, Stage.RECIPIENTS, (lead == null ? "" : lead + " ") + "Who should receive this email?", RECIPIENT_CHOICES);
    }

    private Map<String, Object> setRecipients(HttpServletRequest req, HttpSession session, Draft d, String pick) {
        switch (pick) {
            case "Members", "Church Guests" -> {
                String type = pick.equals("Members") ? "members" : "church-guests";
                int n = mail.getRecipients(type, null, d.clientId).size();
                if (n == 0) return askRecipients(session, d, "There are no " + pick.toLowerCase(Locale.ROOT) + " with an email address.");
                d.recipientType = type; d.recipientLabel = pick; d.count = n; d.eventId = null; d.addresses = null;
                return review(session, d, null);
            }
            case "Event Registrants" -> {
                List<Map<String, Object>> events = mail.getEvents(d.clientId);
                if (events.isEmpty()) return askRecipients(session, d, "There are no events yet.");
                ArrayList<String> labels = new ArrayList<>(); ArrayList<Integer> ids = new ArrayList<>();
                for (Map<String, Object> e : events) {
                    if (labels.size() >= 20) break;
                    String label = String.valueOf(e.get("label"));
                    if (labels.contains(label)) label = label + " (#" + e.get("id") + ")";
                    labels.add(label); ids.add(Integer.valueOf(String.valueOf(e.get("id"))));
                }
                d.eventIds = ids;
                return ask(session, d, Stage.EVENT, "Which event's registrants should receive it?", labels);
            }
            default -> {
                d.recipientType = "group-email"; d.recipientLabel = "Group Email";
                return ask(session, d, Stage.ADDRESSES, "Please type the email addresses, separated by commas.", null);
            }
        }
    }

    // ── review and send ───────────────────────────────────────────────────────

    private Map<String, Object> review(HttpSession session, Draft d, String lead) {
        d.stage = Stage.REVIEW; d.choices = null; d.returnTo = null; d.created = Instant.now();
        d.token = UUID.randomUUID().toString();
        session.setAttribute(SESSION_KEY, d);
        Map<String, Object> m = base("email.review", (lead == null ? "" : lead + " ") + "Please review the email. It is sent only when you press Send email. "
                + "You can attach files below, or press Edit to change anything.", KIND);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("token", d.token); e.put("endpoint", "/api/ai/email/send");
        e.put("subject", d.subject); e.put("body", d.body);
        e.put("recipients", d.recipientLabel); e.put("count", d.count);
        e.put("maxFiles", MAX_FILES); e.put("maxFileMb", 15); e.put("maxTotalMb", 20);
        List<Map<String, Object>> fields = new ArrayList<>();
        Map<String, Object> f1 = AiGuidedGroupService.field("recipients", "Recipients", "group-email".equals(d.recipientType) ? d.recipientLabel : d.recipientLabel + " (" + d.count + (d.count == 1 ? " address" : " addresses") + ")", true, null);
        f1.put("ask", "Change the recipients"); fields.add(f1);
        fields.add(AiGuidedGroupService.field("subject", "Subject", d.subject, true, "Change the subject to "));
        Map<String, Object> f3 = AiGuidedGroupService.field("message", "Message", null, true, null); f3.put("ask", "Edit the text"); f3.put("long", true); fields.add(f3);
        e.put("fields", fields);
        m.put("email", e); m.put("preview", preview(d)); m.put("pending", true); m.put("asking", "review");
        return m;
    }

    /** Tokens being sent right now — a second click while the first is sending finds the token busy. */
    private final Set<String> sending = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The ONLY way an e-mail leaves this conversation: the review card's Send button ({@code /api/ai/email/send})
     * or a typed "send". One-time token bound to this session's draft; the Email page's rule re-checked; the
     * Email page's own send method.
     */
    public Map<String, Object> send(HttpServletRequest req, String clientId, String token, List<MultipartFile> files) {
        HttpSession session = req == null ? null : req.getSession(false);
        if (session == null || clientId == null || token == null) return expired();
        Draft d = pending(session, clientId);
        if (d == null || d.stage != Stage.REVIEW || d.token == null || !d.token.equals(token)) return expired();
        Map<String, Object> gate = gate(req, clientId);
        if (gate != null) { clear(session); return gate; }
        if (!sending.add(token)) return expired();
        try {
            List<MultipartFile> attach = new ArrayList<>();
            if (files != null) for (MultipartFile f : files) if (f != null && !f.isEmpty()) attach.add(f);
            if (attach.size() > MAX_FILES) return review(session, d, "At most " + MAX_FILES + " files can be attached. Nothing was sent.");
            d.token = null; session.setAttribute(SESSION_KEY, d);            // spent, whatever happens next
            int count;
            try {
                count = mail.sendEmail(d.recipientType, d.eventId, d.addresses == null ? null : String.join(",", d.addresses), d.subject, toHtml(d.body), attach, clientId);
            } catch (IllegalArgumentException | IllegalStateException e) {
                return review(session, d, "The email was not sent: " + e.getMessage());
            } catch (Exception e) {
                return review(session, d, "The email could not be sent just now. Nothing was sent — please try again, or use the Email page.");
            }
            clear(session);
            String test = mail.lastTestAddress();
            Map<String, Object> m = base("email.sent", test != null
                    ? "Trial/Demo test: 1 test email was sent to " + test + " — " + count + " recipient(s) simulated, none emailed."
                    : "The email “" + d.subject + "” was sent to " + count + (count == 1 ? " recipient." : " recipients."), KIND);
            m.put("sent", true); m.put("count", count); m.put("attachments", attach.size());
            return m;
        } finally { sending.remove(token); }
    }

    private static Map<String, Object> expired() {
        return base("email.expired", "That email draft is no longer open (already sent, cancelled or expired). Nothing was sent. Say “compose an email” to start again.", KIND);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Plain text → the simple HTML the Email page sends: escaped, blank lines = paragraphs, single line breaks kept. */
    public static String toHtml(String text) {
        StringBuilder sb = new StringBuilder("<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:14px;line-height:1.55;color:#222;\">");
        for (String para : (text == null ? "" : text).replace("\r", "").split("\\n\\s*\\n")) {
            if (para.isBlank()) continue;
            sb.append("<p style=\"margin:0 0 12px;\">").append(esc(para.trim()).replace("\n", "<br>")).append("</p>");
        }
        return sb.append("</div>").toString();
    }
    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    static String cleanLine(String raw, int max) {
        if (raw == null) return null;
        String s = raw.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim().replaceAll("^(?i)(?:subject\\s*(?:is|:)\\s*)", "").trim();
        if (s.length() >= 2) {
            char a = s.charAt(0), b = s.charAt(s.length() - 1);
            if ((a == '"' && b == '"') || (a == '“' && b == '”')) s = s.substring(1, s.length() - 1).trim();
        }
        return s.isEmpty() || s.length() > max ? null : s;
    }
    static String cleanBody(String raw) {
        if (raw == null) return null;
        String s = raw.replace("\r", "").replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ").trim();
        if (s.isEmpty()) return null;
        return s.length() > MAX_BODY ? s.substring(0, MAX_BODY) : s;
    }
    private static String oneOf(String lower, List<String> options) {
        for (String o : options) if (lower.equals(o.toLowerCase(Locale.ROOT))) return o;
        for (String o : options) if (lower.matches(".*\\b" + Pattern.quote(o.toLowerCase(Locale.ROOT)) + "\\b.*")) return o;
        try { int n = Integer.parseInt(lower.trim()); if (n >= 1 && n <= options.size()) return options.get(n - 1); } catch (NumberFormatException ignore) { }
        return null;
    }
    private static String language(String lower) {
        String l = lower.replaceAll("[^\\p{L} ]", " ").trim();
        for (String lang : AiWritingService.LANGUAGES) if (l.equals(lang.toLowerCase(Locale.ROOT))) return lang;
        for (String lang : AiWritingService.LANGUAGES) if (l.matches(".*\\b" + Pattern.quote(lang.toLowerCase(Locale.ROOT)) + "\\b.*")) return lang;
        return null;
    }
    private static List<String> sortedLanguages() { List<String> l = new ArrayList<>(AiWritingService.LANGUAGES); Collections.sort(l); return l; }

    private Map<String, Object> ask(HttpSession session, Draft d, Stage stage, String question, List<String> choices) {
        d.stage = stage; d.choices = choices == null ? null : new ArrayList<>(choices); d.created = Instant.now();
        if (stage != Stage.REVIEW) d.token = null;
        session.setAttribute(SESSION_KEY, d);
        Map<String, Object> m = base("guided.ask", question, KIND);
        m.put("asking", stage.name().toLowerCase(Locale.ROOT)); m.put("pending", true);
        if (choices != null && !choices.isEmpty()) { m.put("choices", choices); m.put("question", question); }
        if (stage == Stage.BODY || stage == Stage.EDIT_BODY || stage == Stage.TOPIC) m.put("multiline", true);
        return m;
    }

    private Draft pending(HttpSession session, String clientId) {
        Object o = session.getAttribute(SESSION_KEY);
        if (!(o instanceof Draft d)) return null;
        if (!clientId.equals(d.clientId) || d.created == null || d.created.plusSeconds(60L * Math.max(1, Math.max(draftMinutes, emailDraftMinutes))).isBefore(Instant.now())) {
            session.removeAttribute(SESSION_KEY); return null;
        }
        return d;
    }
}
