package com.churchgeniuspro.workflow;

import com.churchgeniuspro.ai.audit.AiAuditService;
import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.WorkflowDefinition;
import com.churchgeniuspro.hibernate.WorkflowRun;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.WorkflowDefinitionRepository;
import com.churchgeniuspro.repository.WorkflowRunRepository;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.util.AppClock;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Automations page and API (AI Phase 6, 2026-11-05). Rules are a church-level decision: SuperAdmin /
 * Admin staff only (never church-owner, Member, Kids or temporary sessions); the page key is
 * {@code more.workflows}; the plan feature {@code workflows} gates {@code /api/workflows}. Whoever
 * saves or enables a rule becomes its OWNER — the login whose permissions every run uses.
 */
@Controller
public class WorkflowController {

    public static final String PERM_WORKFLOWS = "more.workflows";
    public static final int MAX_RULES = 50;
    private static final ObjectMapper M = new ObjectMapper();

    private final WorkflowDefinitionRepository definitions;
    private final WorkflowRunRepository runs;
    private final AppUserRepository users;
    private final WorkflowEngine engine;
    private final WorkflowRuleDrafter drafter;
    private final WorkflowEvents events;

    public WorkflowController(WorkflowDefinitionRepository definitions, WorkflowRunRepository runs, AppUserRepository users,
                              WorkflowEngine engine, WorkflowRuleDrafter drafter, WorkflowEvents events) {
        this.definitions = definitions; this.runs = runs; this.users = users; this.engine = engine; this.drafter = drafter; this.events = events;
    }

    /* ───────────────────────── page ───────────────────────── */

    @GetMapping("/workflows")
    public String page(HttpServletRequest req) {
        if (gate(req) != null) return RoleGuard.FORWARD_ACCESS_DENIED;
        String deny = RoleGuard.requirePagePermission(req, PERM_WORKFLOWS);
        if (deny != null) return deny;
        return "forward:/workflows.html";
    }

    /* ───────────────────────── API ───────────────────────── */

    @GetMapping("/api/workflows")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> g = gate(req); if (g != null) return g;
        String cid = SessionUtil.getAppClientId(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engineEnabled", events.enabled());
        out.put("draftEnabled", drafter.isEnabled());
        List<Map<String, Object>> rules = new ArrayList<>();
        for (WorkflowDefinition d : definitions.findByAppClientIdAndDeleteFlagFalseOrderByNameAsc(cid)) rules.add(view(d));
        out.put("rules", rules);
        out.put("templates", WorkflowTemplates.describe());
        out.put("vocabulary", WorkflowSchema.describe());
        List<Map<String, Object>> staff = new ArrayList<>();
        for (AppUser u : users.findByClientIdAndDeleteFlagFalseOrderByLastNameAscFirstNameAsc(cid)) {
            if (!u.isEnabled() || !com.churchgeniuspro.mcp.PersonalAccessTokenService.isStaffRole(u.getRole())) continue;
            Map<String, Object> m = new LinkedHashMap<>(); m.put("id", u.getId()); m.put("name", WorkflowActions.display(u)); m.put("role", u.getRole()); staff.add(m);
        }
        out.put("staff", staff);
        out.put("maxRules", MAX_RULES);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/api/workflows")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> g = gate(req); if (g != null) return g;
        String cid = SessionUtil.getAppClientId(req);
        HttpSession s = req.getSession(false);
        if (body == null) return ResponseEntity.badRequest().body(Map.of("error", "A rule is required."));
        Map<String, Object> raw = body;
        if (body.get("templateKey") != null) {
            WorkflowTemplates.Template t = WorkflowTemplates.get(String.valueOf(body.get("templateKey")));
            if (t == null) return ResponseEntity.badRequest().body(Map.of("error", "Unknown template."));
            raw = new LinkedHashMap<>(t.rule());
        }
        WorkflowSchema.Rule rule;
        try { rule = WorkflowSchema.validate(raw); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
        if (definitions.countByAppClientIdAndDeleteFlagFalse(cid) >= MAX_RULES) return ResponseEntity.status(409).body(Map.of("error", "This church already has " + MAX_RULES + " rules. Delete one first."));
        String name = rule.name();
        if (definitions.existsByAppClientIdAndNameIgnoreCaseAndDeleteFlagFalse(cid, name)) {
            int i = 2; while (definitions.existsByAppClientIdAndNameIgnoreCaseAndDeleteFlagFalse(cid, name + " (" + i + ")")) i++;
            name = name + " (" + i + ")";
        }
        WorkflowDefinition d = new WorkflowDefinition();
        d.setAppClientId(cid); d.setEnabled(false); d.setCreatedBy(username(s));
        d.setTemplateKey(body.get("templateKey") == null ? null : String.valueOf(body.get("templateKey")));
        apply(d, rule, name, s);
        definitions.save(d);
        return ResponseEntity.ok(view(d));
    }

    @PutMapping("/api/workflows/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> g = gate(req); if (g != null) return g;
        String cid = SessionUtil.getAppClientId(req);
        WorkflowDefinition d = definitions.findByIdAndAppClientIdAndDeleteFlagFalse(id, cid).orElse(null);
        if (d == null) return ResponseEntity.status(404).body(Map.of("error", "Rule not found."));
        if (body == null) return ResponseEntity.badRequest().body(Map.of("error", "A rule is required."));
        WorkflowSchema.Rule rule;
        try { rule = WorkflowSchema.validate(body); }
        catch (IllegalArgumentException e) { return ResponseEntity.badRequest().body(Map.of("error", e.getMessage())); }
        String name = rule.name();
        if (!name.equalsIgnoreCase(d.getName()) && definitions.existsByAppClientIdAndNameIgnoreCaseAndDeleteFlagFalse(cid, name))
            return ResponseEntity.status(409).body(Map.of("error", "Another rule already has that name."));
        apply(d, rule, name, req.getSession(false));   // the editor becomes the owner
        definitions.save(d);
        return ResponseEntity.ok(view(d));
    }

    @PostMapping("/api/workflows/{id}/enable")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> enable(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> g = gate(req); if (g != null) return g;
        String cid = SessionUtil.getAppClientId(req);
        WorkflowDefinition d = definitions.findByIdAndAppClientIdAndDeleteFlagFalse(id, cid).orElse(null);
        if (d == null) return ResponseEntity.status(404).body(Map.of("error", "Rule not found."));
        boolean on = body != null && Boolean.TRUE.equals(body.get("enabled"));
        if (on) {
            try { WorkflowEngine.rule(d); } catch (IllegalArgumentException e) { return ResponseEntity.status(409).body(Map.of("error", "This rule cannot be enabled: " + e.getMessage())); }
            HttpSession s = req.getSession(false);
            d.setOwnerUserId(userId(s)); d.setOwnerUsername(username(s));   // the enabler takes responsibility
        }
        d.setEnabled(on); d.setUpdatedAt(LocalDateTime.now());
        definitions.save(d);
        return ResponseEntity.ok(view(d));
    }

    @DeleteMapping("/api/workflows/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> g = gate(req); if (g != null) return g;
        String cid = SessionUtil.getAppClientId(req);
        WorkflowDefinition d = definitions.findByIdAndAppClientIdAndDeleteFlagFalse(id, cid).orElse(null);
        if (d == null) return ResponseEntity.status(404).body(Map.of("error", "Rule not found."));
        d.setDeleteFlag(true); d.setEnabled(false); d.setUpdatedAt(LocalDateTime.now());
        definitions.save(d);
        return ResponseEntity.ok(Map.of("status", "success"));
    }

    @GetMapping("/api/workflows/runs")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> runs(HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> g = gate(req); if (g != null) return g;
        String cid = SessionUtil.getAppClientId(req);
        Map<Long, String> names = new HashMap<>();
        for (WorkflowDefinition d : definitions.findByAppClientIdAndDeleteFlagFalseOrderByNameAsc(cid)) names.put(d.getId(), d.getName());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WorkflowRun r : runs.findTop50ByAppClientIdOrderByCreatedAtDesc(cid)) rows.add(runView(r, names.getOrDefault(r.getDefinitionId(), "(deleted rule)")));
        return ResponseEntity.ok(Map.of("rows", rows));
    }

    @GetMapping("/api/workflows/{id}/runs")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> runsOf(@PathVariable Long id, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> g = gate(req); if (g != null) return g;
        String cid = SessionUtil.getAppClientId(req);
        WorkflowDefinition d = definitions.findByIdAndAppClientIdAndDeleteFlagFalse(id, cid).orElse(null);
        if (d == null) return ResponseEntity.status(404).body(Map.of("error", "Rule not found."));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WorkflowRun r : runs.findTop50ByAppClientIdAndDefinitionIdOrderByCreatedAtDesc(cid, id)) rows.add(runView(r, d.getName()));
        return ResponseEntity.ok(Map.of("rows", rows));
    }

    /** Dry run on sample (or supplied) facts: conditions + what each action would do; nothing is written. */
    @PostMapping("/api/workflows/{id}/test")
    @ResponseBody
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> test(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> g = gate(req); if (g != null) return g;
        String cid = SessionUtil.getAppClientId(req);
        WorkflowDefinition d = definitions.findByIdAndAppClientIdAndDeleteFlagFalse(id, cid).orElse(null);
        if (d == null) return ResponseEntity.status(404).body(Map.of("error", "Rule not found."));
        Map<String, Object> facts = new LinkedHashMap<>(sampleFacts(d.getTrigger()));
        if (body != null && body.get("facts") instanceof Map<?, ?> m) {
            WorkflowSchema.Trigger t = WorkflowSchema.TRIGGERS.get(d.getTrigger());
            for (Map.Entry<?, ?> e : m.entrySet()) if (t != null && t.facts().containsKey(String.valueOf(e.getKey()))) facts.put(String.valueOf(e.getKey()), coerce(t.facts().get(String.valueOf(e.getKey())), e.getValue()));
        }
        try {
            Map<String, Object> out = new LinkedHashMap<>(engine.dryRun(d, facts));
            out.put("facts", facts);
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(409).body(Map.of("error", "This rule cannot be run: " + e.getMessage()));
        }
    }

    /** Describe → draft rule JSON (validated), shown in the form, not saved. An AI channel (rate limit + audit). */
    @PostMapping("/api/workflows/draft")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> draft(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> g = gate(req); if (g != null) return g;
        if (RoleGuard.requireFeature(req, RoleGuard.PERM_AI_ASSISTANT) != null) return ResponseEntity.status(403).body(Map.of("error", "You do not have access to the AI Assistant."));
        AiAuditService.intent(req, "workflows.draft");
        if (!drafter.isEnabled()) return ResponseEntity.status(503).body(Map.of("error", "The AI drafting service is not configured on this server. Pick a template or build the rule by hand."));
        WorkflowRuleDrafter.Draft d = drafter.draft(body == null ? null : String.valueOf(body.getOrDefault("description", "")));
        if (d.rule() == null) return ResponseEntity.status(422).body(Map.of("error", d.refusal()));
        Map<String, Object> out = new LinkedHashMap<>(d.rule().toJson()); out.put("draft", true);
        return ResponseEntity.ok(out);
    }

    /* ───────────────────────── helpers ───────────────────────── */

    static ResponseEntity<Map<String, Object>> gate(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        String cid = SessionUtil.getAppClientId(req);
        if (s == null || s.getAttribute("username") == null || cid == null || cid.isBlank()) return ResponseEntity.status(401).body(Map.of("error", "Not signed in."));
        if (!com.churchgeniuspro.controller.VoiceCommandController.isStaffSession(req) || RoleGuard.isKidsPortalSession(s) || Boolean.TRUE.equals(s.getAttribute("church"))
                || AiAccessPolicy.isTemporaryAccess(s) || com.churchgeniuspro.mcp.TokenSession.isToken(s) || userId(s) == null)
            return ResponseEntity.status(403).body(Map.of("error", "Automations are available to staff accounts."));
        String role = String.valueOf(s.getAttribute("role"));
        if (!("SuperAdmin".equals(role) || "Admin".equals(role))) return ResponseEntity.status(403).body(Map.of("error", "Automations are available to SuperAdmin and Admin accounts."));
        return null;
    }

    static Integer userId(HttpSession s) {
        Object o = s.getAttribute("appUserId");
        if (o instanceof Number n) return n.intValue();
        try { return o == null ? null : Integer.valueOf(String.valueOf(o)); } catch (NumberFormatException e) { return null; }
    }
    static String username(HttpSession s) { return String.valueOf(s.getAttribute("username")); }

    private void apply(WorkflowDefinition d, WorkflowSchema.Rule rule, String name, HttpSession s) {
        d.setName(name); d.setDescription(rule.description()); d.setTrigger(rule.trigger());
        try {
            List<Object> cs = new ArrayList<>(); for (WorkflowSchema.Condition c : rule.conditions()) cs.add(c.toJson());
            List<Object> as = new ArrayList<>(); for (WorkflowSchema.Action a : rule.actions()) as.add(a.toJson());
            d.setConditionsJson(M.writeValueAsString(cs)); d.setActionsJson(M.writeValueAsString(as));
        } catch (Exception e) { throw new IllegalStateException("rule could not be serialised"); }
        d.setOwnerUserId(userId(s)); d.setOwnerUsername(username(s)); d.setUpdatedAt(LocalDateTime.now());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> view(WorkflowDefinition d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId()); m.put("name", d.getName()); m.put("description", d.getDescription()); m.put("trigger", d.getTrigger());
        WorkflowSchema.Trigger t = WorkflowSchema.TRIGGERS.get(d.getTrigger());
        m.put("triggerLabel", t == null ? d.getTrigger() : t.label());
        try {
            m.put("conditions", d.getConditionsJson() == null || d.getConditionsJson().isBlank() ? List.of() : M.readValue(d.getConditionsJson(), List.class));
            m.put("actions", M.readValue(d.getActionsJson(), List.class));
        } catch (Exception e) { m.put("conditions", List.of()); m.put("actions", List.of()); m.put("broken", true); }
        try { WorkflowEngine.rule(d); } catch (IllegalArgumentException e) { m.put("broken", true); m.put("brokenReason", e.getMessage()); }   // stored JSON that no longer passes the schema
        m.put("enabled", d.isEnabled()); m.put("owner", d.getOwnerUsername()); m.put("templateKey", d.getTemplateKey());
        m.put("createdAt", String.valueOf(d.getCreatedAt())); m.put("lastRunAt", d.getLastRunAt() == null ? null : String.valueOf(d.getLastRunAt())); m.put("runCount", d.getRunCount());
        return m;
    }

    static Map<String, Object> runView(WorkflowRun r, String ruleName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId()); m.put("rule", ruleName); m.put("definitionId", r.getDefinitionId()); m.put("trigger", r.getTrigger()); m.put("subject", r.getSubject());
        m.put("status", r.getStatus()); m.put("at", String.valueOf(r.getCreatedAt())); m.put("durationMs", r.getDurationMs());
        try { m.put("log", r.getLogJson() == null ? List.of() : M.readValue(r.getLogJson(), List.class)); } catch (Exception e) { m.put("log", List.of()); }
        return m;
    }

    /** Believable sample facts per trigger for the test-run button. */
    public static Map<String, Object> sampleFacts(String trigger) {
        String today = AppClock.today().toString();
        return switch (trigger == null ? "" : trigger) {
            case "VISITOR_RECORDED" -> WorkflowEvent.facts("name", "Sample Visitor", "kind", "INDIVIDUAL", "peopleCount", 1, "interest", "INTERESTED", "followUpRequired", true, "newVisitor", true, "visitCount", 1, "serviceName", "Sunday Service", "date", today);
            case "ATTENDANCE_SAVED" -> WorkflowEvent.facts("name", "Sample Member", "memberId", 0, "serviceName", "Sunday Service", "status", "PRESENT", "method", "manual", "date", today);
            case "FOLLOW_UP_CREATED" -> WorkflowEvent.facts("title", "Sample follow-up", "status", "PENDING", "priority", "HIGH", "assignedTo", "pat", "linkedType", "MEMBER", "linkedLabel", "Sample Member", "createdBy", "pat", "hasDueDate", true);
            case "PRAYER_REQUEST_CREATED" -> WorkflowEvent.facts("title", "Sample prayer request", "status", "New", "requesterName", "Sample Member", "assigned", false);
            case "GIFT_RECORDED" -> WorkflowEvent.facts("amount", 1250.00, "fund", "General Fund", "guest", false, "memberId", 0, "name", "Sample Member", "date", today, "quickAdd", false);
            case "ENGAGEMENT_SIGNAL_CHANGED" -> WorkflowEvent.facts("name", "Sample Member", "memberId", 0, "familyId", 0, "signal", "AT_RISK", "previousSignal", "ENGAGED", "date", today);
            case "FOLLOW_UP_OVERDUE" -> WorkflowEvent.facts("title", "Sample follow-up", "assignedTo", "pat", "daysOverdue", 4, "linkedType", "VISITOR", "linkedLabel", "Sample Visitor", "priority", "MEDIUM");
            default -> new LinkedHashMap<>();
        };
    }

    private static Object coerce(String type, Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        try {
            return switch (type) {
                case "number" -> s.contains(".") ? (Object) Double.parseDouble(s) : (Object) Long.parseLong(s);
                case "boolean" -> "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s) || "1".equals(s);
                default -> s.length() > 200 ? s.substring(0, 200) : s;
            };
        } catch (NumberFormatException e) { return s; }
    }
}
