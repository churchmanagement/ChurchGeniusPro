package com.churchgeniuspro.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * The closed vocabulary of automation rules (AI Phase 6, 2026-11-05): triggers and the facts each one
 * carries, condition operators, actions and their parameters. {@link #validate} turns untrusted JSON
 * (the page, a template, the model's draft) into a {@link Rule} or throws {@link IllegalArgumentException}
 * naming the first problem — an unknown trigger, field, operator, action or parameter is refused, never
 * guessed. Nothing outside this vocabulary ever reaches the engine.
 */
public final class WorkflowSchema {

    private WorkflowSchema() {}

    private static final ObjectMapper M = new ObjectMapper();
    public static final int MAX_CONDITIONS = 10, MAX_ACTIONS = 5, MAX_TEXT = 500;

    /* ───────────────────────── triggers ───────────────────────── */

    /** A trigger with the facts (field → type) its events carry. Types: string, number, boolean, date. */
    public record Trigger(String key, String label, String description, Map<String, String> facts, boolean nightly) {}

    public static final Map<String, Trigger> TRIGGERS = new LinkedHashMap<>();
    static {
        t("VISITOR_RECORDED", "A visitor checks in", "A visitor (individual, family or group) is recorded on the Attendance page.",
          f("name","string", "kind","string", "peopleCount","number", "interest","string", "followUpRequired","boolean", "newVisitor","boolean", "visitCount","number", "serviceName","string", "date","date"), false);
        t("ATTENDANCE_SAVED", "A member checks in", "A member's attendance is saved (Attendance page, kiosk or import).",
          f("name","string", "memberId","number", "serviceName","string", "status","string", "method","string", "date","date"), false);
        t("FOLLOW_UP_CREATED", "A follow-up is created", "A new follow-up task is added (by hand, from a visitor check-in or by the assistant).",
          f("title","string", "status","string", "priority","string", "assignedTo","string", "linkedType","string", "linkedLabel","string", "createdBy","string", "hasDueDate","boolean"), false);
        t("PRAYER_REQUEST_CREATED", "A prayer request is added", "A staff member records a new prayer request.",
          f("title","string", "status","string", "requesterName","string", "assigned","boolean"), false);
        t("GIFT_RECORDED", "A gift is recorded", "An income entry is saved (hand entry, scan or online giving).",
          f("amount","number", "fund","string", "guest","boolean", "memberId","number", "name","string", "date","date", "quickAdd","boolean"), false);
        t("ENGAGEMENT_SIGNAL_CHANGED", "A member's engagement signal changes (nightly)", "The nightly snapshot gives a member a different signal than the day before (e.g. ENGAGED → AT_RISK).",
          f("name","string", "memberId","number", "familyId","number", "signal","string", "previousSignal","string", "date","date"), true);
        t("FOLLOW_UP_OVERDUE", "A follow-up is overdue (nightly)", "An open follow-up passed its due date; fires once per task per day while it stays open.",
          f("title","string", "assignedTo","string", "daysOverdue","number", "linkedType","string", "linkedLabel","string", "priority","string"), true);
    }
    private static void t(String k, String l, String d, Map<String, String> facts, boolean nightly) { TRIGGERS.put(k, new Trigger(k, l, d, facts, nightly)); }
    private static Map<String, String> f(String... kv) { Map<String, String> m = new LinkedHashMap<>(); for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]); return m; }

    /* ───────────────────────── conditions ───────────────────────── */

    public static final Set<String> OPS = new LinkedHashSet<>(List.of("eq", "neq", "contains", "gt", "gte", "lt", "lte", "in", "is_true", "is_false", "exists"));

    public record Condition(String field, String op, String value) {
        public Map<String, Object> toJson() { Map<String, Object> m = new LinkedHashMap<>(); m.put("field", field); m.put("op", op); if (value != null) m.put("value", value); return m; }
    }

    /* ───────────────────────── actions ───────────────────────── */

    /** An action with its parameters (name → required?) and the module it needs from the owner. */
    public record ActionType(String key, String label, String description, Map<String, Boolean> params, String needs) {}

    public static final Map<String, ActionType> ACTIONS = new LinkedHashMap<>();
    static {
        a("create_follow_up", "Create a follow-up", "Adds a follow-up task (title and description may use {{name}}, {{title}} and other facts); assigned to the rule owner or a named staff login; due in N days.",
          p("title",true, "description",false, "assignTo",false, "dueInDays",false, "priority",false), "followUps");
        a("notify_user", "Notify a staff member", "Sends an in-app / push notification to the rule owner or a named staff login.",
          p("title",true, "message",true, "to",false), null);
        a("email_staff", "E-mail a staff member", "Sends a plain e-mail to the rule owner's or a named staff login's address (never to a member).",
          p("subject",true, "body",true, "to",false), null);
        a("add_family_note", "Add a family note", "Adds a note to the family of the member in the event (Notes page).",
          p("title",true, "body",true), "members");
        a("draft_email", "Draft an e-mail with AI", "Asks the writing assistant for a draft (not sent) and stores it as a follow-up for the owner to review.",
          p("prompt",true, "tone",false), "followUps");
    }
    private static void a(String k, String l, String d, Map<String, Boolean> params, String needs) { ACTIONS.put(k, new ActionType(k, l, d, params, needs)); }
    private static Map<String, Boolean> p(Object... kv) { Map<String, Boolean> m = new LinkedHashMap<>(); for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], (Boolean) kv[i + 1]); return m; }

    public record Action(String type, Map<String, String> params) {
        public Map<String, Object> toJson() { Map<String, Object> m = new LinkedHashMap<>(); m.put("type", type); m.put("params", new LinkedHashMap<>(params)); return m; }
        public String param(String k) { return params.get(k); }
    }

    /** A validated rule. */
    public record Rule(String name, String description, String trigger, List<Condition> conditions, List<Action> actions) {
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name); m.put("description", description); m.put("trigger", trigger);
            List<Object> cs = new ArrayList<>(); for (Condition c : conditions) cs.add(c.toJson()); m.put("conditions", cs);
            List<Object> as = new ArrayList<>(); for (Action a : actions) as.add(a.toJson()); m.put("actions", as);
            return m;
        }
    }

    /* ───────────────────────── validation ───────────────────────── */

    public static Rule validate(Map<String, ?> raw) { JsonNode n = M.valueToTree(raw); return validate(n); }

    public static Rule validate(JsonNode n) {
        if (n == null || !n.isObject()) throw new IllegalArgumentException("A rule must be a JSON object.");
        String name = text(n, "name");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Give the rule a name.");
        if (name.length() > 120) throw new IllegalArgumentException("The name is too long (120 characters).");
        String description = text(n, "description");
        if (description != null && description.length() > MAX_TEXT) throw new IllegalArgumentException("The description is too long (500 characters).");
        String trigger = text(n, "trigger");
        Trigger tr = trigger == null ? null : TRIGGERS.get(trigger.trim().toUpperCase(Locale.ROOT));
        if (tr == null) throw new IllegalArgumentException("Unknown trigger: " + trigger + ". One of " + TRIGGERS.keySet() + ".");

        List<Condition> conditions = new ArrayList<>();
        JsonNode cs = n.get("conditions");
        if (cs != null && !cs.isNull()) {
            if (!cs.isArray()) throw new IllegalArgumentException("conditions must be a list.");
            if (cs.size() > MAX_CONDITIONS) throw new IllegalArgumentException("At most " + MAX_CONDITIONS + " conditions.");
            for (JsonNode c : cs) conditions.add(condition(c, tr));
        }

        JsonNode as = n.get("actions");
        if (as == null || !as.isArray() || as.isEmpty()) throw new IllegalArgumentException("At least one action is required.");
        if (as.size() > MAX_ACTIONS) throw new IllegalArgumentException("At most " + MAX_ACTIONS + " actions.");
        List<Action> actions = new ArrayList<>();
        for (JsonNode a : as) actions.add(action(a, tr));
        return new Rule(name.trim(), description == null ? null : description.trim(), tr.key(), conditions, actions);
    }

    private static Condition condition(JsonNode c, Trigger tr) {
        if (c == null || !c.isObject()) throw new IllegalArgumentException("Each condition must be an object {field, op, value}.");
        String field = text(c, "field"), op = text(c, "op"), value = c.hasNonNull("value") ? (c.get("value").isValueNode() ? c.get("value").asText() : c.get("value").toString()) : null;
        if (field == null || !tr.facts().containsKey(field)) throw new IllegalArgumentException("Unknown field for " + tr.key() + ": " + field + ". One of " + tr.facts().keySet() + ".");
        if (op == null || !OPS.contains(op.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Unknown operator: " + op + ". One of " + OPS + ".");
        op = op.toLowerCase(Locale.ROOT);
        String type = tr.facts().get(field);
        boolean needsValue = !Set.of("is_true", "is_false", "exists").contains(op);
        if (needsValue && (value == null || value.isBlank())) throw new IllegalArgumentException("Condition on " + field + " needs a value.");
        if (!needsValue) value = null;
        if (value != null && value.length() > MAX_TEXT) throw new IllegalArgumentException("Condition value too long.");
        if (Set.of("gt", "gte", "lt", "lte").contains(op) && !"number".equals(type) && !"date".equals(type))
            throw new IllegalArgumentException("Operator " + op + " needs a number or date field; " + field + " is " + type + ".");
        if (Set.of("is_true", "is_false").contains(op) && !"boolean".equals(type))
            throw new IllegalArgumentException("Operator " + op + " needs a true/false field; " + field + " is " + type + ".");
        if ("number".equals(type) && needsValue && !"in".equals(op)) {
            try { Double.parseDouble(value.trim()); } catch (NumberFormatException e) { throw new IllegalArgumentException(field + " compares with a number, not \"" + value + "\"."); }
        }
        return new Condition(field, op, value);
    }

    private static Action action(JsonNode a, Trigger tr) {
        if (a == null || !a.isObject()) throw new IllegalArgumentException("Each action must be an object {type, params}.");
        String type = text(a, "type");
        ActionType at = type == null ? null : ACTIONS.get(type.trim().toLowerCase(Locale.ROOT));
        if (at == null) throw new IllegalArgumentException("Unknown action: " + type + ". One of " + ACTIONS.keySet() + ".");
        Map<String, String> params = new LinkedHashMap<>();
        JsonNode p = a.get("params");
        if (p != null && !p.isNull()) {
            if (!p.isObject()) throw new IllegalArgumentException("params of " + at.key() + " must be an object.");
            Iterator<String> it = p.fieldNames();
            while (it.hasNext()) {
                String k = it.next();
                if (!at.params().containsKey(k)) throw new IllegalArgumentException("Unknown parameter for " + at.key() + ": " + k + ". One of " + at.params().keySet() + ".");
                JsonNode v = p.get(k);
                String s = v == null || v.isNull() ? "" : v.isValueNode() ? v.asText() : v.toString();
                if (s.length() > 2000) throw new IllegalArgumentException(at.key() + "." + k + " is too long (2,000 characters).");
                params.put(k, s);
            }
        }
        for (Map.Entry<String, Boolean> e : at.params().entrySet())
            if (e.getValue() && (params.get(e.getKey()) == null || params.get(e.getKey()).isBlank()))
                throw new IllegalArgumentException(at.key() + " needs " + e.getKey() + ".");
        if (params.containsKey("dueInDays")) {
            try { int d = Integer.parseInt(params.get("dueInDays").trim()); if (d < 0 || d > 365) throw new NumberFormatException(); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("dueInDays must be a whole number between 0 and 365."); }
        }
        if (params.containsKey("priority") && !params.get("priority").isBlank() && !Set.of("LOW", "MEDIUM", "HIGH").contains(params.get("priority").trim().toUpperCase(Locale.ROOT)))
            throw new IllegalArgumentException("priority must be LOW, MEDIUM or HIGH.");
        if (params.containsKey("tone") && !params.get("tone").isBlank() && !Set.of("warm", "formal", "casual", "urgent", "encouraging", "celebratory").contains(params.get("tone").trim().toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("tone must be warm, formal, casual, urgent, encouraging or celebratory.");
        if ("add_family_note".equals(at.key()) && !tr.facts().containsKey("memberId"))
            throw new IllegalArgumentException("add_family_note needs a trigger that names a member (" + tr.key() + " does not).");
        return new Action(at.key(), params);
    }

    private static String text(JsonNode n, String k) { JsonNode v = n.get(k); return v == null || v.isNull() ? null : v.isValueNode() ? v.asText() : v.toString(); }

    /** Placeholders {@code {{field}}} → the event's facts (escaped nowhere: the targets are plain-text fields). */
    public static String fill(String template, Map<String, Object> facts) {
        if (template == null) return null;
        String out = template;
        for (Map.Entry<String, Object> e : facts.entrySet())
            out = out.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : String.valueOf(e.getValue()));
        return out.replaceAll("\\{\\{[a-zA-Z0-9_]+}}", "");
    }

    /** The vocabulary as JSON for the page and the drafter prompt. */
    public static Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> ts = new ArrayList<>();
        for (Trigger t : TRIGGERS.values()) { Map<String, Object> x = new LinkedHashMap<>(); x.put("key", t.key()); x.put("label", t.label()); x.put("description", t.description()); x.put("facts", t.facts()); x.put("nightly", t.nightly()); ts.add(x); }
        m.put("triggers", ts);
        m.put("operators", new ArrayList<>(OPS));
        List<Map<String, Object>> as = new ArrayList<>();
        for (ActionType a : ACTIONS.values()) { Map<String, Object> x = new LinkedHashMap<>(); x.put("key", a.key()); x.put("label", a.label()); x.put("description", a.description()); x.put("params", a.params()); x.put("needs", a.needs()); as.add(x); }
        m.put("actions", as);
        m.put("limits", Map.of("conditions", MAX_CONDITIONS, "actions", MAX_ACTIONS));
        return m;
    }
}
