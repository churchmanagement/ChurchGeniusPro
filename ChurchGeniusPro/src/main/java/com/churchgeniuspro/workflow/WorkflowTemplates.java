package com.churchgeniuspro.workflow;

import java.util.*;

/**
 * The template gallery (AI Phase 6): ten deterministic rules a church can add with one click, each
 * validated by {@link WorkflowSchema} at start-up so a typo here fails the build's tests, not a church.
 * Every template is added DISABLED; the owner is the person who adds it.
 */
public final class WorkflowTemplates {

    private WorkflowTemplates() {}

    public record Template(String key, String name, String description, Map<String, Object> rule) {}

    public static final List<Template> ALL = List.of(
        tpl("first_time_visitor", "First-time visitor → follow-up in 2 days",
            "When a brand-new visitor checks in, create a follow-up for the rule owner due in two days.",
            "VISITOR_RECORDED", conds(c("newVisitor", "is_true", null)),
            acts(a("create_follow_up", "title", "Welcome call: {{name}}", "description", "First visit on {{date}} ({{serviceName}}). Interest: {{interest}}.", "assignTo", "owner", "dueInDays", "2", "priority", "HIGH"))),
        tpl("interested_visitor", "Interested visitor → notify the owner",
            "When a visitor marks Interested, notify the rule owner right away.",
            "VISITOR_RECORDED", conds(c("interest", "eq", "INTERESTED")),
            acts(a("notify_user", "to", "owner", "title", "Interested visitor: {{name}}", "message", "{{name}} ({{kind}}) marked Interested at {{serviceName}} on {{date}}."))),
        tpl("third_visit", "Third visit → membership conversation",
            "When a visitor reaches their third visit, create a follow-up to talk about membership.",
            "VISITOR_RECORDED", conds(c("visitCount", "gte", "3")),
            acts(a("create_follow_up", "title", "Membership conversation: {{name}}", "description", "{{name}} has visited {{visitCount}} times. Invite them to the next membership class.", "assignTo", "owner", "dueInDays", "7"))),
        tpl("family_visit", "Family visit → e-mail the owner",
            "When a whole family checks in as visitors, e-mail the rule owner the details.",
            "VISITOR_RECORDED", conds(c("kind", "eq", "FAMILY")),
            acts(a("email_staff", "to", "owner", "subject", "New visiting family: {{name}}", "body", "A family of {{peopleCount}} visited on {{date}} ({{serviceName}}). Interest: {{interest}}. Follow-up requested: {{followUpRequired}}."))),
        tpl("at_risk_member", "Member slips to At risk → follow-up",
            "When the nightly engagement signal moves a member to AT_RISK, create a follow-up for the owner.",
            "ENGAGEMENT_SIGNAL_CHANGED", conds(c("signal", "eq", "AT_RISK")),
            acts(a("create_follow_up", "title", "Check in with {{name}}", "description", "Engagement signal changed from {{previousSignal}} to {{signal}} on {{date}}.", "assignTo", "owner", "dueInDays", "5", "priority", "HIGH"))),
        tpl("quiet_member_note", "Member goes Quiet → family note + notify",
            "When a member's signal becomes QUIET, add a family note and notify the owner.",
            "ENGAGEMENT_SIGNAL_CHANGED", conds(c("signal", "eq", "QUIET")),
            acts(a("add_family_note", "title", "Gone quiet", "body", "{{name}}'s engagement signal moved from {{previousSignal}} to QUIET on {{date}}."),
                 a("notify_user", "to", "owner", "title", "Gone quiet: {{name}}", "message", "No attendance, gift, group or serving in the last year."))),
        tpl("overdue_follow_up", "Follow-up 3+ days overdue → notify the owner",
            "Each night, when an open follow-up is three or more days past due, notify the rule owner.",
            "FOLLOW_UP_OVERDUE", conds(c("daysOverdue", "gte", "3")),
            acts(a("notify_user", "to", "owner", "title", "Overdue follow-up: {{title}}", "message", "Assigned to {{assignedTo}}, {{daysOverdue}} day(s) overdue."))),
        tpl("high_priority_followup", "High-priority follow-up created → notify the owner",
            "When someone creates a HIGH priority follow-up, notify the rule owner.",
            "FOLLOW_UP_CREATED", conds(c("priority", "eq", "HIGH")),
            acts(a("notify_user", "to", "owner", "title", "High-priority follow-up", "message", "{{title}} — created by {{createdBy}}, assigned to {{assignedTo}}."))),
        tpl("new_prayer_request", "New prayer request → e-mail the owner",
            "When a prayer request is added, e-mail the rule owner its title.",
            "PRAYER_REQUEST_CREATED", conds(),
            acts(a("email_staff", "to", "owner", "subject", "New prayer request: {{title}}", "body", "A new prayer request was recorded ({{status}}). Requested by: {{requesterName}}."))),
        tpl("large_gift", "Gift of $1,000 or more → thank-you follow-up",
            "When a gift of $1,000 or more is recorded, create a follow-up for the owner to send a thank-you.",
            "GIFT_RECORDED", conds(c("amount", "gte", "1000")),
            acts(a("create_follow_up", "title", "Thank-you note: {{name}}", "description", "Gift to {{fund}} on {{date}}.", "assignTo", "owner", "dueInDays", "3")))
    );

    public static Template get(String key) { for (Template t : ALL) if (t.key().equals(key)) return t; return null; }

    public static List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Template t : ALL) { Map<String, Object> m = new LinkedHashMap<>(); m.put("key", t.key()); m.put("name", t.name()); m.put("description", t.description()); m.put("rule", t.rule()); out.add(m); }
        return out;
    }

    private static Template tpl(String key, String name, String description, String trigger, List<Map<String, Object>> conditions, List<Map<String, Object>> actions) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("name", name); rule.put("description", description); rule.put("trigger", trigger); rule.put("conditions", conditions); rule.put("actions", actions);
        WorkflowSchema.validate(rule);   // a bad template fails at class load → caught by the tests
        return new Template(key, name, description, Collections.unmodifiableMap(rule));
    }
    private static List<Map<String, Object>> conds(Map<String, Object>... cs) { return List.of(cs); }
    private static Map<String, Object> c(String field, String op, String value) { Map<String, Object> m = new LinkedHashMap<>(); m.put("field", field); m.put("op", op); if (value != null) m.put("value", value); return m; }
    private static List<Map<String, Object>> acts(Map<String, Object>... as) { return List.of(as); }
    private static Map<String, Object> a(String type, String... kv) { Map<String, Object> p = new LinkedHashMap<>(); for (int i = 0; i + 1 < kv.length; i += 2) p.put(kv[i], kv[i + 1]); Map<String, Object> m = new LinkedHashMap<>(); m.put("type", type); m.put("params", p); return m; }
}
