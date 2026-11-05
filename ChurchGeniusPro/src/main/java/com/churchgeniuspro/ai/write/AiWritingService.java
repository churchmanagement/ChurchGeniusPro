package com.churchgeniuspro.ai.write;

import com.churchgeniuspro.service.OpenAiVoiceService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * AI Phase 3 — writing help for the e-mail and SMS composers: draft, rewrite, shorten, expand, fix, translate.
 * The model sees only the staff member's request (and the text they selected); no member data, no dates of
 * birth, no ages. The result is a DRAFT returned to the page — this service and its controller never call
 * {@code EmailService} or {@code SmsService} (pinned by {@code AiWritingPhase3Test}). Output is reduced to plain
 * text (every tag stripped, control characters removed, length capped) before it leaves the server.
 */
@Slf4j
@Service
public class AiWritingService {

    public static final Set<String> ACTIONS   = Set.of("draft", "rewrite", "shorten", "expand", "fix", "translate");
    public static final Set<String> TONES     = Set.of("warm", "formal", "casual", "urgent", "encouraging", "celebratory");
    public static final Set<String> LENGTHS   = Set.of("short", "medium", "long");
    public static final Set<String> CHANNELS  = Set.of("email", "sms");
    public static final Set<String> LANGUAGES = Set.of("English", "Spanish", "French", "Portuguese", "Korean", "Chinese", "Tagalog", "Vietnamese", "Hindi", "Telugu", "Tamil", "Malayalam", "Swahili", "German", "Italian", "Arabic");
    public static final int MAX_PROMPT = 2_000, MAX_TEXT = 8_000, MAX_OUT_EMAIL = 6_000, MAX_OUT_SMS = 320, SMS_TARGET = 160;

    /** A validated request. {@code text} = the body / selection the action works on (may be blank for draft). */
    public record Request(String action, String channel, String prompt, String text, String tone, String length, String language, String churchName, boolean wantSubject) {}

    /** The draft. {@code subject} only for e-mail drafts; {@code chars} of the body; {@code note} = a one-line caveat for the page. */
    public record Draft(String subject, String body, int chars, String note) {}

    private final OpenAiVoiceService openAi;

    @Autowired
    public AiWritingService(OpenAiVoiceService openAi) { this.openAi = openAi; }

    public boolean isEnabled() { return openAi != null && openAi.isEnabled(); }

    /* ───────────────────────── validation ───────────────────────── */

    /** Builds a request from the JSON body; IllegalArgumentException on anything outside the allow-lists. */
    public static Request validate(Map<String, Object> body) {
        if (body == null) throw new IllegalArgumentException("A JSON body is required.");
        String action = low(str(body.get("action"), "draft"));
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("Unknown action.");
        String channel = low(str(body.get("channel"), "email"));
        if (!CHANNELS.contains(channel)) throw new IllegalArgumentException("Unknown channel.");
        String prompt = str(body.get("prompt"), "").trim();
        String text = str(body.get("text"), "").trim();
        if (prompt.length() > MAX_PROMPT) throw new IllegalArgumentException("The request is too long (max " + MAX_PROMPT + " characters).");
        if (text.length() > MAX_TEXT) throw new IllegalArgumentException("The text is too long (max " + MAX_TEXT + " characters).");
        if ("draft".equals(action) && prompt.isBlank()) throw new IllegalArgumentException("Describe what to write.");
        if (!"draft".equals(action) && text.isBlank()) throw new IllegalArgumentException("Select or type the text to work on first.");
        String tone = low(str(body.get("tone"), "warm"));
        if (!TONES.contains(tone)) throw new IllegalArgumentException("Unknown tone.");
        String length = low(str(body.get("length"), "sms".equals(channel) ? "short" : "medium"));
        if (!LENGTHS.contains(length)) throw new IllegalArgumentException("Unknown length.");
        String language = str(body.get("language"), "English").trim();
        if (!LANGUAGES.contains(language)) throw new IllegalArgumentException("Unknown language.");
        String church = str(body.get("churchName"), "").trim();
        if (church.length() > 120) church = church.substring(0, 120);
        boolean wantSubject = "email".equals(channel) && Boolean.TRUE.equals(body.get("wantSubject"));
        return new Request(action, channel, prompt, text, tone, length, language, church, wantSubject);
    }

    /* ───────────────────────── the call ───────────────────────── */

    /** Null when the model is not configured or returned nothing usable. */
    public Draft write(Request r) {
        if (!isEnabled()) return null;
        JsonNode node = openAi.chatJson(systemPrompt(r), userPrompt(r), "fix".equals(r.action()) ? 0.1 : 0.6);
        if (node == null) return null;
        String body = sanitize(node.path("body").asText(""), "sms".equals(r.channel()) ? MAX_OUT_SMS : MAX_OUT_EMAIL);
        if (body.isBlank()) return null;
        String subject = r.wantSubject() ? sanitize(node.path("subject").asText(""), 160).replace("\n", " ").trim() : null;
        if (subject != null && subject.isBlank()) subject = null;
        String note = "sms".equals(r.channel()) && body.length() > SMS_TARGET
                ? "Over " + SMS_TARGET + " characters — it will be sent as more than one text." : null;
        return new Draft(subject, body, body.length(), note);
    }

    public String systemPrompt(Request r) {
        StringBuilder s = new StringBuilder();
        s.append("You help a church office write messages to its congregation");
        if (!r.churchName().isBlank()) s.append(" (the church is called \"").append(r.churchName()).append("\")");
        s.append(". You write DRAFTS that a staff member will review and edit; nothing you write is sent automatically. ");
        s.append("Rules: write in ").append(r.language()).append("; tone ").append(r.tone()).append("; ");
        if ("sms".equals(r.channel())) {
            s.append("this is a TEXT MESSAGE (SMS): plain text, no greeting line, no sign-off block, no emojis unless asked, at most ")
             .append(SMS_TARGET).append(" characters including spaces; never add STOP or opt-out wording (the system adds it). ");
        } else {
            s.append("this is an E-MAIL body: plain text paragraphs separated by blank lines, no HTML, no markdown, no subject line inside the body; length ")
             .append(switch (r.length()) { case "short" -> "about 60–90 words"; case "long" -> "about 220–300 words"; default -> "about 120–170 words"; })
             .append(". ");
        }
        s.append("Never invent facts, dates, times, places, names, amounts or links that are not in the request — use a placeholder in square brackets such as [date] or [name] instead. ");
        s.append("Never mention anyone's age, birth year or how many years a couple has been married, even when asked for a birthday or anniversary message. ");
        s.append("Do not include instructions, explanations or alternatives — only the message. ");
        s.append("Reply ONLY as a JSON object with the keys \"body\" (the message text)");
        if (r.wantSubject()) s.append(" and \"subject\" (a short e-mail subject line, no quotes)");
        s.append(".");
        return s.toString();
    }

    public String userPrompt(Request r) {
        StringBuilder u = new StringBuilder();
        switch (r.action()) {
            case "draft"     -> u.append("Write the message. Request: ").append(r.prompt());
            case "rewrite"   -> u.append("Rewrite the following text in the requested tone, keeping its meaning and every fact.");
            case "shorten"   -> u.append("sms".equals(r.channel()) ? "Shorten the following text to fit in one text message (at most " + SMS_TARGET + " characters), keeping every fact."
                                                                  : "Shorten the following text to roughly half its length, keeping every fact.");
            case "expand"    -> u.append("Expand the following text with a little more warmth and context, keeping every fact; do not add new facts.");
            case "fix"       -> u.append("Correct spelling, grammar and punctuation in the following text. Change nothing else — keep the wording, tone and facts.");
            case "translate" -> u.append("Translate the following text into ").append(r.language()).append(", keeping its meaning, tone and every fact.");
            default -> throw new IllegalStateException(r.action());
        }
        if (!r.prompt().isBlank() && !"draft".equals(r.action())) u.append(" Extra guidance from the staff member: ").append(r.prompt());
        if (!r.text().isBlank()) u.append("\n\nTEXT:\n").append(r.text());
        return u.toString();
    }

    /* ───────────────────────── output hygiene ───────────────────────── */

    private static final Pattern TAGS = Pattern.compile("<[^>]{0,500}>");
    private static final Pattern BLOCKS = Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");

    /** Plain text only: tags, control characters and surrounding whitespace removed; at most {@code max} characters. */
    public static String sanitize(String s, int max) {
        if (s == null) return "";
        String t = BLOCKS.matcher(s).replaceAll("");
        t = TAGS.matcher(t).replaceAll("");
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        t = BLOCKS.matcher(t).replaceAll("");
        t = TAGS.matcher(t).replaceAll("");            // tags that were entity-encoded
        t = CONTROL.matcher(t).replaceAll("");
        t = t.replace("\r", "").replaceAll("\n{3,}", "\n\n").trim();
        if (t.length() > max) t = t.substring(0, max).trim();
        return t;
    }

    private static String str(Object o, String dflt) { return o == null ? dflt : String.valueOf(o); }
    private static String low(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }
}
