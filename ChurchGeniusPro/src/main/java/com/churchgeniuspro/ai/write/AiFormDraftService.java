package com.churchgeniuspro.ai.write;

import com.churchgeniuspro.service.OpenAiVoiceService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * AI Phase 3 — describe-to-build for the Event / registration form. The staff member describes the event
 * ("VBS registration, June 9–13, 9 am to noon, free, max 80 children, ask for allergies and pickup authorisation")
 * and the model answers with JSON that is validated against the CLOSED schema below before the page fills the
 * form (the user still clicks Save). Two parts:
 * <ul>
 *   <li>{@code event} — values for the fields the Event form really has (names are the form's own ids);</li>
 *   <li>{@code questions} — extra registration questions the form cannot hold as fields yet; each has a type from
 *       {@link #FIELD_TYPES}, a label, a required flag and (for choice types) options. The page shows them and can
 *       append them to the event note as plain text. An unknown type makes the whole draft invalid (400 / refused).</li>
 * </ul>
 * No tenant data goes to the model — only the description and today's date.
 */
@Slf4j
@Service
public class AiFormDraftService {

    public static final Set<String> FIELD_TYPES = Set.of("text", "textarea", "email", "phone", "number", "date", "yesno", "checkbox", "select", "radio", "section");
    public static final Set<String> EVENT_TYPES = Set.of("One Day", "Multiple Days");
    public static final int MAX_DESCRIPTION = 2_000, MAX_QUESTIONS = 25, MAX_OPTIONS = 12, MAX_LABEL = 120, MAX_TEXT = 1_000;

    private final OpenAiVoiceService openAi;

    @Autowired
    public AiFormDraftService(OpenAiVoiceService openAi) { this.openAi = openAi; }

    public boolean isEnabled() { return openAi != null && openAi.isEnabled(); }

    /** Draft from a description; null when the model is off or returned nothing valid. */
    public Map<String, Object> draft(String description, LocalDate today) {
        if (description == null || description.isBlank()) throw new IllegalArgumentException("Describe the event or form first.");
        if (description.length() > MAX_DESCRIPTION) throw new IllegalArgumentException("The description is too long (max " + MAX_DESCRIPTION + " characters).");
        if (!isEnabled()) return null;
        JsonNode node = openAi.chatJson(systemPrompt(today), "DESCRIPTION:\n" + description.trim(), 0.2);
        if (node == null) return null;
        try {
            return validate(node);
        } catch (IllegalArgumentException e) {
            log.warn("[ai-forms] model output refused: {}", e.getMessage());
            return null;
        }
    }

    public static String systemPrompt(LocalDate today) {
        return "You turn a church staff member's description of an event or sign-up form into JSON for an event-registration form. "
             + "Today is " + today.format(DateTimeFormatter.ISO_DATE) + " (use it to resolve dates like 'next Saturday'; a date with no year is the next occurrence). "
             + "Reply ONLY as a JSON object: {\"event\": {...}, \"questions\": [...], \"unsupported\": [...]}. "
             + "\"event\" may contain ONLY these keys (omit any you cannot fill from the description — never guess): "
             + "eventName (string), eventType (\"One Day\" or \"Multiple Days\"), eventDate (YYYY-MM-DD, the first day), endDate (YYYY-MM-DD, multi-day only), "
             + "startTime (HH:MM 24h), endTime (HH:MM 24h), registrationEndDate (YYYY-MM-DD), fee (short text such as \"Free\" or \"$10 per child\"), "
             + "maxCapacity (integer), allowMaybeRsvp (boolean), showRegistrants (boolean), foodAvailable (boolean), foodLabel (string), foodOptions (array of short strings), "
             + "accommodationAvailable (boolean), accommodationAddress (string), accommodationComments (string), hostName, hostPhone, hostEmail (strings), note (string: a short public description for registrants). "
             + "\"questions\" = the extra things the form should ask each registrant (child's name, allergies, parent phone, pickup authorisation, T-shirt size …): "
             + "objects {\"type\": one of text|textarea|email|phone|number|date|yesno|checkbox|select|radio|section, \"label\": string, \"required\": boolean, \"options\": [strings] (select/radio only)}. "
             + "Do not repeat as questions what the registration form already collects (first name, last name, e-mail, phone, number of attendees, dietary preference when foodOptions is used). "
             + "\"unsupported\" = anything requested that an event registration form cannot do (payments, file uploads, waivers with signatures, recurring schedules), as short strings. "
             + "Never include personal data about real people. Keep every text short and plain (no HTML).";
    }

    /** Validates model (or client) JSON against the closed schema. IllegalArgumentException on any violation. */
    public static Map<String, Object> validate(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("Expected a JSON object.");
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> event = new LinkedHashMap<>();
        JsonNode e = node.path("event");
        if (e.isObject()) {
            for (Iterator<String> it = e.fieldNames(); it.hasNext(); ) {
                String k = it.next(); JsonNode v = e.get(k);
                if (v == null || v.isNull()) continue;
                switch (k) {
                    case "eventName", "fee", "foodLabel", "accommodationAddress", "hostName", "hostPhone", "hostEmail" -> event.put(k, text(v, k, MAX_LABEL));
                    case "accommodationComments", "note" -> event.put(k, text(v, k, MAX_TEXT));
                    case "eventType" -> { String t = text(v, k, 20); if (!EVENT_TYPES.contains(t)) throw new IllegalArgumentException("eventType must be One Day or Multiple Days."); event.put(k, t); }
                    case "eventDate", "endDate", "registrationEndDate" -> event.put(k, date(v, k));
                    case "startTime", "endTime" -> event.put(k, time(v, k));
                    case "maxCapacity" -> { if (!v.isNumber() || v.asInt() < 0 || v.asInt() > 1_000_000) throw new IllegalArgumentException("maxCapacity must be a whole number."); event.put(k, v.asInt()); }
                    case "allowMaybeRsvp", "showRegistrants", "foodAvailable", "accommodationAvailable" -> { if (!v.isBoolean()) throw new IllegalArgumentException(k + " must be true or false."); event.put(k, v.asBoolean()); }
                    case "foodOptions" -> event.put(k, strings(v, k, MAX_OPTIONS));
                    default -> throw new IllegalArgumentException("Unknown event field: " + k);
                }
            }
            if ("Multiple Days".equals(event.get("eventType")) && event.get("endDate") instanceof String end && event.get("eventDate") instanceof String start
                    && LocalDate.parse(end).isBefore(LocalDate.parse(start))) throw new IllegalArgumentException("endDate is before eventDate.");
            if (Boolean.TRUE.equals(event.get("foodAvailable")) && !event.containsKey("foodLabel")) event.put("foodLabel", "Dietary Preferences");
        }
        out.put("event", event);
        List<Map<String, Object>> questions = new ArrayList<>();
        JsonNode q = node.path("questions");
        if (q.isArray()) {
            if (q.size() > MAX_QUESTIONS) throw new IllegalArgumentException("Too many questions (max " + MAX_QUESTIONS + ").");
            for (JsonNode f : q) {
                if (!f.isObject()) throw new IllegalArgumentException("Each question must be an object.");
                String type = f.path("type").asText("").trim().toLowerCase(Locale.ROOT);
                if (!FIELD_TYPES.contains(type)) throw new IllegalArgumentException("Unknown field type: " + f.path("type").asText(""));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", type);
                m.put("label", text(f.path("label"), "label", MAX_LABEL));
                m.put("required", f.path("required").asBoolean(false));
                if ("select".equals(type) || "radio".equals(type)) {
                    List<String> opts = strings(f.path("options"), "options", MAX_OPTIONS);
                    if (opts.size() < 2) throw new IllegalArgumentException("A choice question needs at least two options.");
                    m.put("options", opts);
                } else if (f.has("options") && f.path("options").isArray() && f.path("options").size() > 0) {
                    throw new IllegalArgumentException("Only select and radio questions take options.");
                }
                questions.add(m);
            }
        }
        out.put("questions", questions);
        out.put("unsupported", node.path("unsupported").isArray() ? strings(node.path("unsupported"), "unsupported", 10) : List.of());
        return out;
    }

    /** The questions as the plain-text block the page appends to the event note. */
    @SuppressWarnings("unchecked")
    public static String questionsAsNote(List<Map<String, Object>> questions) {
        if (questions == null || questions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Registration questions:\n");
        for (Map<String, Object> q : questions) {
            sb.append("• ").append(q.get("label"));
            if (Boolean.TRUE.equals(q.get("required"))) sb.append(" (required)");
            if (q.get("options") instanceof List<?> o) sb.append(": ").append(String.join(" / ", (List<String>) o));
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static String text(JsonNode v, String k, int max) {
        if (!v.isTextual()) throw new IllegalArgumentException(k + " must be text.");
        String s = AiWritingService.sanitize(v.asText(), max);
        if (s.isBlank()) throw new IllegalArgumentException(k + " is empty.");
        return s;
    }
    private static String date(JsonNode v, String k) {
        try { return LocalDate.parse(text(v, k, 10)).toString(); } catch (Exception ex) { throw new IllegalArgumentException(k + " must be a date (YYYY-MM-DD)."); }
    }
    private static String time(JsonNode v, String k) {
        String t = text(v, k, 5);
        if (!t.matches("([01]\\d|2[0-3]):[0-5]\\d")) throw new IllegalArgumentException(k + " must be a time (HH:MM).");
        return t;
    }
    private static List<String> strings(JsonNode v, String k, int max) {
        if (!v.isArray()) throw new IllegalArgumentException(k + " must be a list.");
        if (v.size() > max) throw new IllegalArgumentException(k + " has too many entries (max " + max + ").");
        List<String> out = new ArrayList<>();
        for (JsonNode n : v) { String s = AiWritingService.sanitize(n.asText(""), 80); if (!s.isBlank()) out.add(s); }
        return out;
    }
}
