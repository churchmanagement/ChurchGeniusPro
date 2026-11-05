package com.churchgeniuspro.mcp;

import com.churchgeniuspro.ai.action.AiActionService;
import com.churchgeniuspro.ai.action.AiTool;
import com.churchgeniuspro.ai.action.AiToolRegistry;
import com.churchgeniuspro.ai.filter.PeopleFilter;
import com.churchgeniuspro.ai.filter.PeopleFilterService;
import com.churchgeniuspro.ai.intel.MemberIntelligenceService;
import com.churchgeniuspro.ai.intel.MinistryReportService;
import com.churchgeniuspro.hibernate.AiSavedPeopleList;
import com.churchgeniuspro.repository.AiSavedPeopleListRepository;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.AiDataSearchService;
import com.churchgeniuspro.util.RoleGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

import static com.churchgeniuspro.mcp.McpToolAdapter.Result;
import static com.churchgeniuspro.mcp.McpToolAdapter.Ctx;
import static com.churchgeniuspro.mcp.McpToolAdapter.schema;

/**
 * The closed list of MCP tools (AI Phase 5): six read tools over the existing AI services and,
 * behind the {@code actions} scope, the Phase 4 action agent (propose → confirm, exactly as in
 * the assistant — nothing is written by {@code propose_action}). Nothing outside this list is
 * reachable from the endpoint.
 *
 * <p>Every service is optional at construction so the catalog can be built in a unit test with
 * only the pieces under test; a missing service answers "not available on this server".
 */
@Component
public class McpToolCatalog {

    private final AiDataSearchService dataSearch;
    private final PeopleFilterService filters;
    private final AiSavedPeopleListRepository lists;
    private final MinistryReportService reports;
    private final MemberIntelligenceService intel;
    private final AiActionService actions;
    private final AiToolRegistry registry;
    private final AiAccessPolicy access;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, McpToolAdapter> tools = new LinkedHashMap<>();

    @Autowired
    public McpToolCatalog(@Autowired(required = false) AiDataSearchService dataSearch,
                          @Autowired(required = false) PeopleFilterService filters,
                          @Autowired(required = false) AiSavedPeopleListRepository lists,
                          @Autowired(required = false) MinistryReportService reports,
                          @Autowired(required = false) MemberIntelligenceService intel,
                          @Autowired(required = false) AiActionService actions,
                          @Autowired(required = false) AiToolRegistry registry,
                          AiAccessPolicy access) {
        this.dataSearch = dataSearch; this.filters = filters; this.lists = lists; this.reports = reports;
        this.intel = intel; this.actions = actions; this.registry = registry; this.access = access;
        for (McpToolAdapter t : List.of(new Ask(), new SearchPeople(), new ListSavedLists(), new RunSavedList(),
                                        new Report(), new Engagement(), new ListActions(), new Propose(), new Confirm(), new Cancel()))
            tools.put(t.name(), t);
    }

    public McpToolAdapter get(String name) { return name == null ? null : tools.get(name); }

    /** The tools this token may see: read tools always, write tools only with the {@code actions} scope. */
    public List<McpToolAdapter> visible(Set<String> scopes) {
        List<McpToolAdapter> out = new ArrayList<>();
        for (McpToolAdapter t : tools.values()) if (t.requiredScope() == null || scopes.contains(t.requiredScope())) out.add(t);
        return out;
    }

    public Set<String> names() { return Collections.unmodifiableSet(tools.keySet()); }

    /* ───────────────────────── helpers ───────────────────────── */

    private static String str(Map<String, Object> a, String k) { Object v = a == null ? null : a.get(k); return v == null ? null : String.valueOf(v).trim(); }
    private static Integer integer(Map<String, Object> a, String k) {
        Object v = a == null ? null : a.get(k);
        if (v == null || String.valueOf(v).isBlank()) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v).trim()); } catch (NumberFormatException e) { throw new IllegalArgumentException(k + " must be a whole number."); }
    }
    private static Result unavailable(String what) { return Result.fail(what + " is not available on this server."); }
    /** An agent answer is an error outcome unless its intent is one of the expected ones (so a client sees a failed confirm as such). */
    private static Result outcome(Map<String, Object> m, Set<String> okIntents) {
        if (m == null) return Result.fail("No answer.");
        return new Result(new LinkedHashMap<>(m), !okIntents.contains(String.valueOf(m.get("intent"))));
    }
    private static Result fromAnswer(Map<String, Object> m) {
        if (m == null) return Result.fail("No answer.");
        return new Result(new LinkedHashMap<>(m), Boolean.TRUE.equals(m.get("denied")));
    }

    /* ───────────────────────── read tools ───────────────────────── */

    /** The assistant's whole data route: structured answers, cross-reference, people filters. */
    final class Ask implements McpToolAdapter {
        public String name() { return "ask_church_data"; }
        public String title() { return "Ask about church data"; }
        public String description() {
            return "Answer a plain-English question about this church's people, attendance, giving, groups, volunteers, "
                 + "follow-ups, events, meetings or pledges — e.g. \"regular attenders not in a group\", \"women 18–30 who gave this year\", "
                 + "\"how much did we receive for Missions in March\". Answers only what the token owner may see; refused modules are named. "
                 + "Returns the assistant's structured answer (answer text, columns/rows, totals, sources, definitions).";
        }
        public Map<String, Object> inputSchema() { return schema(List.of("question"), "question", "string", "The question, in plain English (up to 500 characters)."); }
        public Result call(Ctx c, Map<String, Object> a) {
            if (dataSearch == null) return unavailable("Church data search");
            String q = str(a, "question");
            if (q == null || q.isBlank()) return Result.fail("question is required.");
            if (q.length() > 500) return Result.fail("question is too long (500 characters).");
            return fromAnswer(dataSearch.answer(c.req(), c.cid(), q));
        }
    }

    /** Composable people filters from explicit predicates (the Phase 1 closed set). */
    final class SearchPeople implements McpToolAdapter {
        public String name() { return "search_people"; }
        public String title() { return "Search people with filters"; }
        public String description() {
            return "List the church's people matching ALL of the given predicates (AND). Predicate kinds: "
                 + "{kind:\"AgeBand\",min,max}, {kind:\"BirthMonth\",month}, {kind:\"Role\",role:Head|Spouse|Adult|Child}, "
                 + "{kind:\"Gender\",gender:Male|Female}, {kind:\"Status\",status:active|inactive|deleted|any}, "
                 + "{kind:\"MemberType\",type:Member|Guest|Visitor|NonMember}, {kind:\"HasContact\",negated,what:email|phone}, "
                 + "{kind:\"Attended\",negated,minDays,from,to,periodLabel}, {kind:\"Gave\",negated,from,to,periodLabel,fund}, "
                 + "{kind:\"InGroup\",negated,group}, {kind:\"Serving\",negated,since}, {kind:\"InCampaign\",negated,campaign}, "
                 + "{kind:\"RegisteredForEvent\",negated,event}, {kind:\"Engagement\",signal:ENGAGED|WATCH|AT_RISK|NEW|QUIET}. "
                 + "Dates are ISO (yyyy-mm-dd). Up to 12 predicates. Every module a predicate touches must be open to the token owner, or the whole search is refused.";
        }
        public Map<String, Object> inputSchema() {
            return schema(List.of("predicates"),
                    "predicates", Map.of("type", "array", "items", Map.of("type", "object"), "minItems", 1, "maxItems", 12), "The predicate objects (see the tool description).",
                    "limit", Map.of("type", "integer", "minimum", 1, "maximum", 500), "Maximum rows to return (default 200).");
        }
        public Result call(Ctx c, Map<String, Object> a) {
            if (filters == null) return unavailable("People filters");
            Object p = a == null ? null : a.get("predicates");
            if (!(p instanceof List<?> l) || l.isEmpty()) return Result.fail("predicates must be a non-empty list.");
            PeopleFilter f;
            try { f = PeopleFilter.fromJson(l); } catch (IllegalArgumentException e) { return Result.fail("Invalid predicates: " + e.getMessage()); }
            Integer limit = integer(a, "limit");
            int cap = limit == null ? 200 : Math.max(1, Math.min(500, limit));
            return fromAnswer(filters.run(f, c.access(), c.cross(), c.cid(), c.today(), cap));
        }
    }

    private Result savedListsGate(Ctx c) {
        String role = c.access().role();
        if (!("SuperAdmin".equals(role) || "Admin".equals(role) || "Accountant".equals(role)))
            return Result.fail("Saved Lists are available to SuperAdmin, Admin and Accountant accounts.");
        if (RoleGuard.requirePagePermission(c.req(), "more.savedlists") != null) return Result.fail("The token owner does not have access to Saved Lists.");
        return null;
    }

    final class ListSavedLists implements McpToolAdapter {
        public String name() { return "list_saved_lists"; }
        public String title() { return "List saved people lists"; }
        public String description() { return "The church's saved people lists (name, id, the filter's conditions, last run and count). Use run_saved_list to get the people."; }
        public Map<String, Object> inputSchema() { return schema(List.of()); }
        public Result call(Ctx c, Map<String, Object> a) {
            if (lists == null) return unavailable("Saved Lists");
            Result g = savedListsGate(c); if (g != null) return g;
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AiSavedPeopleList l : lists.findByAppClientIdAndDeleteFlagFalseOrderByNameAsc(c.cid())) rows.add(view(l));
            Map<String, Object> out = new LinkedHashMap<>(); out.put("lists", rows); out.put("count", rows.size());
            return Result.ok(out);
        }
    }

    final class RunSavedList implements McpToolAdapter {
        public String name() { return "run_saved_list"; }
        public String title() { return "Run a saved people list"; }
        public String description() { return "Run one saved people list by id and return the matching people (same columns as search_people)."; }
        public Map<String, Object> inputSchema() { return schema(List.of("id"), "id", "integer", "The saved list id (from list_saved_lists)."); }
        public Result call(Ctx c, Map<String, Object> a) {
            if (lists == null || filters == null) return unavailable("Saved Lists");
            Result g = savedListsGate(c); if (g != null) return g;
            Integer id = integer(a, "id");
            if (id == null) return Result.fail("id is required.");
            AiSavedPeopleList l = lists.findByIdAndAppClientIdAndDeleteFlagFalse(id.longValue(), c.cid()).orElse(null);
            if (l == null) return Result.fail("Saved list not found.");
            PeopleFilter f;
            try { f = PeopleFilter.fromJson(mapper.readValue(l.getFilterJson(), List.class)); }
            catch (Exception e) { return Result.fail("This saved list can no longer be read."); }
            Map<String, Object> m = filters.run(f, c.access(), c.cross(), c.cid(), c.today());
            if (Boolean.FALSE.equals(m.get("denied")) && m.get("total") != null) {
                l.setLastRunAt(LocalDateTime.now()); l.setLastCount((int) Math.min(Integer.MAX_VALUE, ((Number) m.get("total")).longValue()));
                try { lists.save(l); } catch (RuntimeException ignore) { /* informational */ }
            }
            Map<String, Object> out = new LinkedHashMap<>(m); out.put("list", view(l));
            return fromAnswer(out);
        }
    }

    private Map<String, Object> view(AiSavedPeopleList l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId()); m.put("name", l.getName());
        try { m.put("predicates", mapper.readValue(l.getFilterJson(), List.class)); } catch (Exception e) { m.put("predicates", List.of()); }
        m.put("lastRunAt", l.getLastRunAt() == null ? null : String.valueOf(l.getLastRunAt()));
        m.put("lastCount", l.getLastCount());
        return m;
    }

    final class Report implements McpToolAdapter {
        public String name() { return "ministry_report"; }
        public String title() { return "Monthly ministry report"; }
        public String description() {
            return "The monthly ministry report for one month: attendance, visitors, follow-ups, giving (finance viewers with the giving scope only), "
                 + "groups, volunteers and engagement counts, each compared with the previous month and the same month last year, plus the "
                 + "template narrative and definitions. Only the sections the token owner may see are included. Defaults to the last completed month.";
        }
        public Map<String, Object> inputSchema() {
            return schema(List.of(), "year", Map.of("type", "integer", "minimum", 2000), "Calendar year.", "month", Map.of("type", "integer", "minimum", 1, "maximum", 12), "Month 1–12.");
        }
        public Result call(Ctx c, Map<String, Object> a) {
            if (reports == null) return unavailable("The ministry report");
            AiAccessPolicy.AiAccess ac = c.access();
            if (ac.member() || ac.church() || ac.kids()) return Result.fail("The ministry report is available to staff accounts.");
            MinistryReportService.Access acc = new MinistryReportService.Access(c.cross().attendance(), ac.incomeRecords(), c.cross().groups(), ac.volunteers(), ac.followUps());
            if (!acc.any()) return Result.fail("The token owner has no access to any section of the ministry report.");
            Integer y = integer(a, "year"), m = integer(a, "month");
            java.time.LocalDate last = c.today().withDayOfMonth(1).minusMonths(1);
            if (y == null && m == null) { y = last.getYear(); m = last.getMonthValue(); }
            if (y == null || m == null) return Result.fail("Give both year and month, or neither.");
            if (m < 1 || m > 12 || y < 2000) return Result.fail("Invalid year or month.");
            java.time.LocalDate first = java.time.LocalDate.of(y, m, 1);
            if (first.isAfter(c.today())) return Result.fail("That month has not started yet.");
            return Result.ok(reports.monthly(c.cid(), y, m, acc, c.today()));
        }
    }

    final class Engagement implements McpToolAdapter {
        public String name() { return "engagement_summary"; }
        public String title() { return "Engagement signals summary"; }
        public String description() { return "How many people carry each engagement signal (ENGAGED, WATCH, AT_RISK, NEW, QUIET) on the latest nightly snapshot, with the snapshot date. Needs the Attendance page."; }
        public Map<String, Object> inputSchema() { return schema(List.of()); }
        public Result call(Ctx c, Map<String, Object> a) {
            if (intel == null) return unavailable("Engagement signals");
            if (!c.cross().attendance()) return Result.fail("The token owner does not have access to Attendance, which the engagement signals are built on.");
            Map<String, Object> out = new LinkedHashMap<>(intel.summary(c.cid(), c.today()));
            out.put("definitions", List.of(
                    "NEW — first attendance within the last 90 days.",
                    "QUIET — no attendance, gift, group or serving in 365 days.",
                    "AT_RISK — attended regularly then stopped (≥42-day gap), or gave last year but not this one with no recent attendance.",
                    "WATCH — attendance slipping (30-day gap) or giving paused, or active only through a group / serving / gift.",
                    "ENGAGED — everyone else with a snapshot."));
            return Result.ok(out);
        }
    }

    /* ───────────────────────── write tools (scope: actions) ───────────────────────── */

    private Result actionsGate(Ctx c) {
        if (actions == null || registry == null) return unavailable("AI actions");
        if (!access.feature(c.cid(), AiActionService.FEATURE)) return Result.fail("AI actions are not included in this church's plan.");
        return null;
    }

    final class ListActions implements McpToolAdapter {
        public String name() { return "list_actions"; }
        public String title() { return "List available actions"; }
        public String description() { return "The write actions the token owner may propose (name, label, parameters). Every action is proposed first and only carried out by confirm_action."; }
        public Map<String, Object> inputSchema() { return schema(List.of()); }
        public String requiredScope() { return "actions"; }
        public Result call(Ctx c, Map<String, Object> a) {
            Result g = actionsGate(c); if (g != null) return g;
            List<Map<String, Object>> out = new ArrayList<>();
            for (AiTool t : registry.all()) {
                if (!t.allowed(c.req(), c.access(), c.cross(), c.cid())) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", t.name()); m.put("label", t.label()); m.put("description", t.description()); m.put("parameters", t.parameters());
                out.add(m);
            }
            Map<String, Object> r = new LinkedHashMap<>(); r.put("actions", out); r.put("count", out.size());
            return Result.ok(r);
        }
    }

    final class Propose implements McpToolAdapter {
        public String name() { return "propose_action"; }
        public String title() { return "Propose an action"; }
        public String description() {
            return "Propose ONE write action (see list_actions) with its parameters. Nothing is saved: the reply is either a clarifying question "
                 + "(intent action.ask, with choices), a refusal, or a proposal (intent action.confirm) carrying a one-time token and a summary. "
                 + "Show the summary to the person and call confirm_action with the token ONLY after they explicitly agree; the token expires in 10 minutes.";
        }
        public Map<String, Object> inputSchema() {
            return schema(List.of("tool", "params"), "tool", "string", "The action name from list_actions.",
                          "params", Map.of("type", "object"), "The action's parameters (names as list_actions describes them).");
        }
        public String requiredScope() { return "actions"; }
        public boolean writes() { return true; }
        @SuppressWarnings("unchecked")
        public Result call(Ctx c, Map<String, Object> a) {
            Result g = actionsGate(c); if (g != null) return g;
            String tool = str(a, "tool");
            if (tool == null || registry.get(tool) == null) return Result.fail("Unknown action. Call list_actions first.");
            Object p = a.get("params");
            Map<String, Object> params = p instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
            Map<String, Object> m = actions.propose(c.req(), c.session(), c.access(), c.cid(), tool, params, "MCP");
            return outcome(m, Set.of("action.ask", "action.confirm"));
        }
    }

    final class Confirm implements McpToolAdapter {
        public String name() { return "confirm_action"; }
        public String title() { return "Confirm a proposed action"; }
        public String description() { return "Carry out a proposal made by propose_action, by its token, after the person agreed. The server re-checks the token owner, church, permission and expiry and re-reads every record by id before writing."; }
        public Map<String, Object> inputSchema() { return schema(List.of("token"), "token", "string", "The proposal token from propose_action."); }
        public String requiredScope() { return "actions"; }
        public boolean writes() { return true; }
        public Result call(Ctx c, Map<String, Object> a) {
            Result g = actionsGate(c); if (g != null) return g;
            String t = str(a, "token");
            if (t == null || t.isBlank()) return Result.fail("token is required.");
            return outcome(actions.confirm(c.req(), c.cid(), t), Set.of("action.done"));
        }
    }

    final class Cancel implements McpToolAdapter {
        public String name() { return "cancel_action"; }
        public String title() { return "Cancel a proposed action"; }
        public String description() { return "Discard a proposal made by propose_action (the person said no). Nothing is written."; }
        public Map<String, Object> inputSchema() { return schema(List.of("token"), "token", "string", "The proposal token from propose_action."); }
        public String requiredScope() { return "actions"; }
        public Result call(Ctx c, Map<String, Object> a) {
            Result g = actionsGate(c); if (g != null) return g;
            String t = str(a, "token");
            if (t == null || t.isBlank()) return Result.fail("token is required.");
            return outcome(actions.reject(c.req(), c.cid(), t), Set.of("action.cancelled"));
        }
    }
}
