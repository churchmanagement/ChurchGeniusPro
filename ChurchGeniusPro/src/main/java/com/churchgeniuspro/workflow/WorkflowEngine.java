package com.churchgeniuspro.workflow;

import com.churchgeniuspro.hibernate.AppUser;
import com.churchgeniuspro.hibernate.UserPermissions;
import com.churchgeniuspro.hibernate.WorkflowDefinition;
import com.churchgeniuspro.hibernate.WorkflowRun;
import com.churchgeniuspro.repository.AppUserRepository;
import com.churchgeniuspro.repository.UserPermissionsRepository;
import com.churchgeniuspro.repository.WorkflowDefinitionRepository;
import com.churchgeniuspro.repository.WorkflowRunRepository;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.workflow.WorkflowSchema.Action;
import com.churchgeniuspro.workflow.WorkflowSchema.Condition;
import com.churchgeniuspro.workflow.WorkflowSchema.Rule;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * The trigger → condition → action engine (AI Phase 6, 2026-11-05). Small and explicit: no scripting,
 * no model in the loop — the model only drafts rule JSON on the page ({@link WorkflowRuleDrafter}).
 *
 * <p>For every {@link WorkflowEvent} (delivered AFTER the publishing transaction committed) and every
 * ENABLED rule of that church for that trigger: (1) idempotency — a run already exists for
 * (rule, eventKey) → nothing; (2) the per-church daily cap ({@code workflow.runs-per-tenant-per-day})
 * → CAPPED; (3) the rule OWNER is re-read — disabled / deleted / moved / not staff → DENIED — and their
 * saved permission map is resolved into an {@link AiAccess} exactly as a sign-in would; (4) every
 * action's permission is checked BEFORE anything runs — one refused action → DENIED, nothing executed;
 * (5) conditions → SKIPPED; (6) the actions run inside ONE new transaction → DONE, or FAILED with
 * nothing kept. Every outcome is a {@link WorkflowRun} row with a plain-text log. The whole engine is
 * inert when {@code workflow.enabled=false} (zero side effects, no rows).
 */
@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);
    private static final ObjectMapper M = new ObjectMapper();

    private final WorkflowDefinitionRepository definitions;
    private final WorkflowRunRepository runs;
    private final AppUserRepository users;
    private final UserPermissionsRepository permissions;
    private final AiAccessPolicy access;
    private final WorkflowActions actions;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final int perTenantPerDay;

    @Autowired
    public WorkflowEngine(WorkflowDefinitionRepository definitions, WorkflowRunRepository runs, AppUserRepository users,
                          UserPermissionsRepository permissions, AiAccessPolicy access, WorkflowActions actions,
                          PlatformTransactionManager txManager,
                          @Value("${workflow.enabled:true}") boolean enabled,
                          @Value("${workflow.runs-per-tenant-per-day:200}") int perTenantPerDay) {
        this.definitions = definitions; this.runs = runs; this.users = users; this.permissions = permissions;
        this.access = access; this.actions = actions; this.enabled = enabled; this.perTenantPerDay = perTenantPerDay;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);   // AFTER_COMMIT listeners sit in a finished transaction
    }

    public boolean enabled() { return enabled; }

    /** Spring delivers each published event here once the publisher's transaction committed (or at once outside one). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(WorkflowEvent event) { handle(event); }

    /** Evaluates every enabled rule of the event's church for its trigger. Never throws. */
    public List<WorkflowRun> handle(WorkflowEvent event) {
        List<WorkflowRun> out = new ArrayList<>();
        if (!enabled || event == null) return out;
        List<WorkflowDefinition> defs;
        try { defs = definitions.findByAppClientIdAndTriggerAndEnabledTrueAndDeleteFlagFalse(event.clientId(), event.trigger()); }
        catch (RuntimeException e) { log.warn("[workflow] could not load rules: {}", e.getClass().getSimpleName()); return out; }
        for (WorkflowDefinition d : defs) {
            try { WorkflowRun r = runOne(d, event); if (r != null) out.add(r); }
            catch (RuntimeException e) { log.warn("[workflow] rule {} failed outside its transaction: {}", d.getId(), e.getClass().getSimpleName()); }
        }
        return out;
    }

    private WorkflowRun runOne(WorkflowDefinition d, WorkflowEvent event) {
        long started = System.currentTimeMillis();
        if (runs.existsByDefinitionIdAndEventKey(d.getId(), event.eventKey())) return null;   // idempotent
        Rule rule;
        try { rule = rule(d); }
        catch (IllegalArgumentException e) { return record(d, event, "FAILED", List.of("The rule could not be read: " + e.getMessage()), started); }
        LocalDateTime startOfDay = LocalDate.now(WorkflowActions.CHURCH_ZONE).atStartOfDay();
        if (perTenantPerDay > 0 && runs.countDoneSince(d.getAppClientId(), startOfDay) >= perTenantPerDay)
            return record(d, event, "CAPPED", List.of("The church reached its daily limit of " + perTenantPerDay + " automation runs; nothing was done."), started);

        Owner owner = owner(d);
        if (owner.refusal != null) return record(d, event, "DENIED", List.of(owner.refusal), started);
        WorkflowActions.Ctx ctx = new WorkflowActions.Ctx(d.getAppClientId(), withRule(event, d), owner.user, d.getOwnerUsername(), owner.access, d.getId(), false);
        for (Action a : rule.actions()) {
            String why = actions.check(ctx, a);
            if (why != null) return record(d, event, "DENIED", List.of("Not run — " + why), started);
        }
        List<String> trace = new ArrayList<>();
        String failing = evaluate(rule.conditions(), event.facts(), trace);
        if (failing != null) return record(d, event, "SKIPPED", List.of("Conditions not met: " + failing), started);

        List<String> done = new ArrayList<>();
        try {
            tx.executeWithoutResult(status -> { for (Action a : rule.actions()) done.add(actions.execute(ctx, a)); });
        } catch (RuntimeException e) {
            log.warn("[workflow] rule {} failed: {}", d.getId(), e.getClass().getSimpleName());
            return record(d, event, "FAILED", List.of("An action failed (" + e.getClass().getSimpleName() + "); nothing from this run was kept."), started);
        }
        return record(d, event, "DONE", done, started);
    }

    /* ───────────────────────── pieces ───────────────────────── */

    record Owner(AppUser user, AiAccess access, String refusal) {}

    Owner owner(WorkflowDefinition d) {
        AppUser u = d.getOwnerUserId() == null ? null : users.findByIdAndClientId(d.getOwnerUserId(), d.getAppClientId()).orElse(null);
        if (u == null || u.isDeleteFlag() || !u.isEnabled()) return new Owner(null, null, "Not run — the rule owner's account is no longer active.");
        if (!com.churchgeniuspro.mcp.PersonalAccessTokenService.isStaffRole(u.getRole())) return new Owner(null, null, "Not run — the rule owner is not a staff login.");
        String priv = com.churchgeniuspro.util.RolePermissionDefaults.effective(u.getRole(),
                permissions.findByAppUserId(u.getId()).map(UserPermissions::getPermissions).orElse(u.getPrivileges()));
        HttpServletRequest req = OwnerRequest.of(u, d.getOwnerUsername(), priv);
        return new Owner(u, access.resolve(req, d.getAppClientId()), null);
    }

    /** Null when every condition holds, else a sentence naming the first that does not. Appends readable lines to {@code log}. */
    public static String evaluate(List<Condition> conditions, Map<String, Object> facts, List<String> log) {
        for (Condition c : conditions) {
            boolean ok = holds(c, facts.get(c.field()));
            if (log != null) log.add(c.field() + " " + c.op() + (c.value() == null ? "" : " " + c.value()) + " → " + (ok ? "yes" : "no"));
            if (!ok) return c.field() + " " + c.op() + (c.value() == null ? "" : " " + c.value()) + " (was " + (facts.get(c.field()) == null ? "empty" : facts.get(c.field())) + ")";
        }
        return null;
    }

    static boolean holds(Condition c, Object actual) {
        String op = c.op();
        switch (op) {
            case "exists": return actual != null && !String.valueOf(actual).isBlank();
            case "is_true": return actual instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(actual));
            case "is_false": return actual instanceof Boolean b ? !b : actual == null || "false".equalsIgnoreCase(String.valueOf(actual));
            default: break;
        }
        if (actual == null) return "neq".equals(op);
        String a = String.valueOf(actual).trim(), v = c.value() == null ? "" : c.value().trim();
        Double an = num(a), vn = num(v);
        switch (op) {
            case "eq": return an != null && vn != null ? an.equals(vn) : a.equalsIgnoreCase(v);
            case "neq": return an != null && vn != null ? !an.equals(vn) : !a.equalsIgnoreCase(v);
            case "contains": return a.toLowerCase(Locale.ROOT).contains(v.toLowerCase(Locale.ROOT));
            case "in": for (String x : v.split(",")) if (x.trim().equalsIgnoreCase(a) || (an != null && num(x) != null && num(x).equals(an))) return true; return false;
            case "gt": case "gte": case "lt": case "lte": {
                int cmp;
                if (an != null && vn != null) cmp = an.compareTo(vn);
                else { try { cmp = LocalDate.parse(a).compareTo(LocalDate.parse(v)); } catch (RuntimeException e) { return false; } }
                return switch (op) { case "gt" -> cmp > 0; case "gte" -> cmp >= 0; case "lt" -> cmp < 0; default -> cmp <= 0; };
            }
            default: return false;
        }
    }

    private static Double num(String s) { try { return s == null || s.isBlank() ? null : Double.valueOf(s); } catch (NumberFormatException e) { return null; } }

    static WorkflowEvent withRule(WorkflowEvent e, WorkflowDefinition d) {
        Map<String, Object> f = new LinkedHashMap<>(e.facts()); f.put("_rule", d.getName());
        return new WorkflowEvent(e.trigger(), e.clientId(), e.eventKey(), e.subject(), Collections.unmodifiableMap(f), e.occurredAt());
    }

    public static Rule rule(WorkflowDefinition d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", d.getName()); m.put("description", d.getDescription()); m.put("trigger", d.getTrigger());
        try {
            m.put("conditions", d.getConditionsJson() == null || d.getConditionsJson().isBlank() ? List.of() : M.readValue(d.getConditionsJson(), List.class));
            m.put("actions", M.readValue(d.getActionsJson(), List.class));
        } catch (Exception e) { throw new IllegalArgumentException("stored JSON is not readable"); }
        return WorkflowSchema.validate(m);
    }

    private WorkflowRun record(WorkflowDefinition d, WorkflowEvent event, String status, List<String> lines, long started) {
        WorkflowRun r = new WorkflowRun();
        r.setAppClientId(d.getAppClientId()); r.setDefinitionId(d.getId()); r.setTrigger(event.trigger()); r.setEventKey(cut(event.eventKey(), 160));
        r.setSubject(cut(event.subject(), 200)); r.setStatus(status);
        try { r.setLogJson(M.writeValueAsString(lines)); } catch (Exception e) { r.setLogJson("[]"); }
        r.setDurationMs((int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - started));
        try {
            return tx.execute(s -> {
                WorkflowRun saved = runs.save(r);
                if ("DONE".equals(status)) {
                    definitions.findByIdAndAppClientIdAndDeleteFlagFalse(d.getId(), d.getAppClientId()).ifPresent(x -> { x.setLastRunAt(LocalDateTime.now()); x.setRunCount(x.getRunCount() + 1); definitions.save(x); });
                }
                return saved;
            });
        } catch (DataIntegrityViolationException dup) {
            return null;   // the same event raced in twice — the other run stands
        }
    }

    private static String cut(String s, int n) { return s == null ? null : s.length() <= n ? s : s.substring(0, n); }

    /** Dry run from the page: conditions + what each action WOULD do, nothing written. */
    public Map<String, Object> dryRun(WorkflowDefinition d, Map<String, Object> facts) {
        Map<String, Object> out = new LinkedHashMap<>();
        Rule rule = rule(d);
        List<String> log = new ArrayList<>();
        String failing = evaluate(rule.conditions(), facts, log);
        out.put("conditions", log);
        out.put("conditionsMet", failing == null);
        Owner owner = owner(d);
        List<String> plan = new ArrayList<>();
        if (owner.refusal != null) { plan.add(owner.refusal); out.put("status", "DENIED"); }
        else {
            WorkflowEvent e = WorkflowEvent.of(d.getTrigger(), d.getAppClientId(), "dry", "Test run", facts);
            WorkflowActions.Ctx ctx = new WorkflowActions.Ctx(d.getAppClientId(), withRule(e, d), owner.user, d.getOwnerUsername(), owner.access, d.getId(), true);
            String denied = null;
            for (Action a : rule.actions()) { String why = actions.check(ctx, a); if (why != null) { denied = why; break; } }
            if (denied != null) { plan.add("Not run — " + denied); out.put("status", "DENIED"); }
            else if (failing != null) { plan.add("Nothing would run: " + failing); out.put("status", "SKIPPED"); }
            else { for (Action a : rule.actions()) plan.add(actions.execute(ctx, a)); out.put("status", "DRY"); }
        }
        out.put("actions", plan);
        return out;
    }
}
