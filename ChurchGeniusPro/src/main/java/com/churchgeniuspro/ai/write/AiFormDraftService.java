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
            return validate(normalizeModelOutput(node));
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


    // ── model output → schema shape ──────────────────────────────────────────────────────────────────
    // The model often answers with harmless format slips (an empty "hostName", fee 0, maxCapacity "60",
    // "9:00 AM", eventType "one day", a key the form does not have). validate() is strict on purpose and
    // rejected the WHOLE draft for any of them (2026-11-05: every description returned 502). This pass
    // repairs or drops such values for MODEL output only; validate() stays strict for client JSON
    // (/api/ai/forms/validate). Question types outside FIELD_TYPES are still refused by validate().
    private static final com.fasterxml.jackson.databind.ObjectMapper NM = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final Set<String> TEXT_KEYS = Set.of("eventName", "fee", "foodLabel", "accommodationAddress", "hostName", "hostPhone", "hostEmail", "accommodationComments", "note");
    private static final Set<String> DATE_KEYS = Set.of("eventDate", "endDate", "registrationEndDate");
    private static final Set<String> TIME_KEYS = Set.of("startTime", "endTime");
    private static final Set<String> BOOL_KEYS = Set.of("allowMaybeRsvp", "showRegistrants", "foodAvailable", "accommodationAvailable");

    public static JsonNode normalizeModelOutput(JsonNode node) {
        if (node == null || !node.isObject()) return node;
        com.fasterxml.jackson.databind.node.ObjectNode out = NM.createObjectNode();
        com.fasterxml.jackson.databind.node.ObjectNode ev = out.putObject("event");
        JsonNode e = node.path("event");
        List<String> dropped = new ArrayList<>();
        if (e.isObject()) {
            for (Iterator<String> it = e.fieldNames(); it.hasNext(); ) {
                String k = it.next(); JsonNode v = e.get(k);
                if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank()) || (v.isArray() && v.size() == 0)) continue;
                if (TEXT_KEYS.contains(k)) {
                    if ("fee".equals(k) && v.isNumber()) { ev.put(k, v.asDouble() == 0 ? "Free" : "$" + v.asText()); continue; }
                    if (v.isValueNode()) ev.put(k, v.asText().trim()); else dropped.add(k);
                } else if (DATE_KEYS.contains(k)) {
                    String d = v.asText("").trim();
                    if (d.matches("\\d{4}-\\d{2}-\\d{2}.*")) ev.put(k, d.substring(0, 10)); else dropped.add(k);
                } else if (TIME_KEYS.contains(k)) {
                    String t = toHHMM(v.asText(""));
                    if (t != null) ev.put(k, t); else dropped.add(k);
                } else if (BOOL_KEYS.contains(k)) {
                    Boolean b = v.isBoolean() ? Boolean.valueOf(v.asBoolean()) : yesNo(v.asText(""));
                    if (b != null) ev.put(k, b); else dropped.add(k);
                } else if ("maxCapacity".equals(k)) {
                    String n = v.asText("").replaceAll("[^0-9]", "");
                    if (v.isNumber()) ev.put(k, v.asInt()); else if (!n.isEmpty() && n.length() <= 7) ev.put(k, Integer.parseInt(n)); else dropped.add(k);
                } else if ("eventType".equals(k)) {
                    String t = v.asText("").trim().toLowerCase(Locale.ROOT);
                    if (t.startsWith("one") || t.startsWith("single")) ev.put(k, "One Day");
                    else if (t.startsWith("multi")) ev.put(k, "Multiple Days");
                    else dropped.add(k);
                } else if ("foodOptions".equals(k)) {
                    com.fasterxml.jackson.databind.node.ArrayNode a = ev.putArray(k);
                    if (v.isArray()) v.forEach(x -> { if (x.isValueNode() && !x.asText().isBlank()) a.add(x.asText().trim()); });
                    else for (String part : v.asText("").split(",")) if (!part.isBlank()) a.add(part.trim());
                    if (a.isEmpty()) ev.remove(k);
                } else dropped.add(k);
            }
        }
        com.fasterxml.jackson.databind.node.ArrayNode qs = out.putArray("questions");
        JsonNode q = node.path("questions");
        if (q.isArray()) for (JsonNode f : q) {
            if (!f.isObject()) { qs.add(f); continue; }                      // validate() refuses it
            String label = f.path("label").asText("").trim();
            if (label.isEmpty()) continue;
            String type = questionType(f.path("type").asText(""));
            com.fasterxml.jackson.databind.node.ObjectNode m = qs.addObject();
            m.put("type", type); m.put("label", label);
            JsonNode r = f.get("required");
            m.put("required", r != null && (r.isBoolean() ? r.asBoolean() : Boolean.TRUE.equals(yesNo(r.asText("")))));
            if ("select".equals(type) || "radio".equals(type)) {
                com.fasterxml.jackson.databind.node.ArrayNode o = m.putArray("options");
                f.path("options").forEach(x -> { if (x.isValueNode() && !x.asText().isBlank()) o.add(x.asText().trim()); });
                if (o.size() < 2) { m.remove("options"); m.put("type", "text"); }
            }
        }
        com.fasterxml.jackson.databind.node.ArrayNode un = out.putArray("unsupported");
        JsonNode u = node.path("unsupported");
        if (u.isArray()) u.forEach(x -> { if (x.isValueNode() && !x.asText().isBlank()) un.add(x.asText().trim()); });
        else if (u.isTextual() && !u.asText().isBlank()) un.add(u.asText().trim());
        if (!dropped.isEmpty()) log.info("[ai-forms] dropped unusable model fields: {}", dropped);
        return out;
    }

    /** "9", "9:30", "09:30:00", "9 AM", "9:30 pm", "noon", "21:00" → HH:MM; null when it is not a time. */
    static String toHHMM(String raw) {
        String t = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace(".", "");
        if (t.equals("noon")) return "12:00";
        if (t.equals("midnight")) return "00:00";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{1,2})(?::(\\d{2}))?(?::\\d{2})?\\s*(am|pm)?$").matcher(t);
        if (!m.matches()) return null;
        int h = Integer.parseInt(m.group(1)), min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        String ap = m.group(3);
        if (ap != null) { if (h < 1 || h > 12) return null; if (ap.equals("pm") && h != 12) h += 12; if (ap.equals("am") && h == 12) h = 0; }
        if (h > 23 || min > 59) return null;
        return String.format("%02d:%02d", h, min);
    }

    private static Boolean yesNo(String s) {
        String t = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
        if (Set.of("true", "yes", "y", "1").contains(t)) return Boolean.TRUE;
        if (Set.of("false", "no", "n", "0").contains(t)) return Boolean.FALSE;
        return null;
    }

    private static String questionType(String raw) {
        String t = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "", "string", "short text", "short_text", "shorttext", "input", "name" -> "text";
            case "long text", "long_text", "paragraph", "multiline", "comment", "comments" -> "textarea";
            case "tel", "telephone", "phone number", "phone_number", "mobile" -> "phone";
            case "e-mail", "email address" -> "email";
            case "integer", "numeric", "int" -> "number";
            case "boolean", "bool", "yes/no", "yes_no", "yes-no", "toggle" -> "yesno";
            case "dropdown", "drop-down", "choice", "multiple choice", "multiple_choice", "single choice", "list" -> "select";
            case "checkboxes" -> "checkbox";
            case "heading", "header", "title" -> "section";
            default -> t;                                                     // validate() decides
        };
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
