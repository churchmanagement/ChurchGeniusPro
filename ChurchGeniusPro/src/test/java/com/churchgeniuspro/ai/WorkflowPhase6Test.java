package com.churchgeniuspro.ai;

import com.churchgeniuspro.ai.write.AiWritingService;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.EmailService;
import com.churchgeniuspro.service.OpenAiVoiceService;
import com.churchgeniuspro.service.WebPushService;
import com.churchgeniuspro.util.SubscriptionFeatureCatalog;
import com.churchgeniuspro.workflow.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** AI Phase 6 — workflow automation: schema, operators, the engine's outcomes, actions, nightly triggers, the page API, wiring. */
@DisplayName("AI Phase 6 — workflow automation")
class WorkflowPhase6Test {

    static final String CID = "CHR-A";
    static final ObjectMapper M = new ObjectMapper();

    WorkflowDefinitionRepository defs; WorkflowRunRepository runs; AppUserRepository users; UserPermissionsRepository perms;
    FollowUpRepository followUps; NoteRepository notes; FamilyMemberRepository members; WebPushService push; EmailService email; AiWritingService writing;
    Map<Long, WorkflowDefinition> defStore = new LinkedHashMap<>(); Map<Long, WorkflowRun> runStore = new LinkedHashMap<>(); AtomicLong ids = new AtomicLong();
    AppUser pat; UserPermissions patPerms = null;
    WorkflowActions actions; WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        defs = mock(WorkflowDefinitionRepository.class); runs = mock(WorkflowRunRepository.class); users = mock(AppUserRepository.class); perms = mock(UserPermissionsRepository.class);
        followUps = mock(FollowUpRepository.class); notes = mock(NoteRepository.class); members = mock(FamilyMemberRepository.class);
        push = mock(WebPushService.class); email = mock(EmailService.class); writing = mock(AiWritingService.class);
        when(defs.save(any())).thenAnswer(i -> { WorkflowDefinition d = i.getArgument(0); if (d.getId() == null) d.setId(ids.incrementAndGet()); defStore.put(d.getId(), d); return d; });
        when(defs.findByAppClientIdAndTriggerAndEnabledTrueAndDeleteFlagFalse(anyString(), anyString())).thenAnswer(i -> defStore.values().stream().filter(d -> i.getArgument(0).equals(d.getAppClientId()) && i.getArgument(1).equals(d.getTrigger()) && d.isEnabled() && !d.isDeleteFlag()).toList());
        when(defs.findByAppClientIdAndDeleteFlagFalseOrderByNameAsc(anyString())).thenAnswer(i -> defStore.values().stream().filter(d -> i.getArgument(0).equals(d.getAppClientId()) && !d.isDeleteFlag()).sorted(Comparator.comparing(WorkflowDefinition::getName)).toList());
        when(defs.findByIdAndAppClientIdAndDeleteFlagFalse(anyLong(), anyString())).thenAnswer(i -> Optional.ofNullable(defStore.get((Long) i.getArgument(0))).filter(d -> i.getArgument(1).equals(d.getAppClientId()) && !d.isDeleteFlag()));
        when(defs.countByAppClientIdAndDeleteFlagFalse(anyString())).thenAnswer(i -> defStore.values().stream().filter(d -> i.getArgument(0).equals(d.getAppClientId()) && !d.isDeleteFlag()).count());
        when(defs.existsByAppClientIdAndNameIgnoreCaseAndDeleteFlagFalse(anyString(), anyString())).thenAnswer(i -> defStore.values().stream().anyMatch(d -> i.getArgument(0).equals(d.getAppClientId()) && !d.isDeleteFlag() && d.getName().equalsIgnoreCase(i.getArgument(1))));
        when(runs.save(any())).thenAnswer(i -> { WorkflowRun r = i.getArgument(0); if (r.getId() == null) r.setId(ids.incrementAndGet()); runStore.put(r.getId(), r); return r; });
        when(runs.existsByDefinitionIdAndEventKey(anyLong(), anyString())).thenAnswer(i -> runStore.values().stream().anyMatch(r -> i.getArgument(0).equals(r.getDefinitionId()) && i.getArgument(1).equals(r.getEventKey())));
        when(runs.countDoneSince(anyString(), any())).thenAnswer(i -> runStore.values().stream().filter(r -> i.getArgument(0).equals(r.getAppClientId()) && "DONE".equals(r.getStatus())).count());
        when(runs.findTop50ByAppClientIdOrderByCreatedAtDesc(anyString())).thenAnswer(i -> runStore.values().stream().filter(r -> i.getArgument(0).equals(r.getAppClientId())).toList());
        when(runs.findTop50ByAppClientIdAndDefinitionIdOrderByCreatedAtDesc(anyString(), anyLong())).thenAnswer(i -> runStore.values().stream().filter(r -> i.getArgument(0).equals(r.getAppClientId()) && i.getArgument(1).equals(r.getDefinitionId())).toList());
        pat = new AppUser(); pat.setId(7); pat.setUserId("STF-7"); pat.setClientId(CID); pat.setRole("Admin"); pat.setEnabled(true); pat.setFirstName("Pat"); pat.setLastName("Lee"); pat.setEmail("pat@example.test");
        AppUser sam = new AppUser(); sam.setId(8); sam.setUserId("STF-8"); sam.setClientId(CID); sam.setRole("User"); sam.setEnabled(true); sam.setFirstName("Sam"); sam.setLastName("Ray"); sam.setEmail("sam@example.test");
        when(users.findByIdAndClientId(7, CID)).thenReturn(Optional.of(pat));
        when(users.findByIdAndClientId(8, CID)).thenReturn(Optional.of(sam));
        when(users.findByClientIdAndDeleteFlagFalseOrderByLastNameAscFirstNameAsc(CID)).thenReturn(List.of(pat, sam));
        when(perms.findByAppUserId(anyInt())).thenAnswer(i -> Optional.ofNullable(patPerms));
        when(followUps.save(any())).thenAnswer(i -> { FollowUp f = i.getArgument(0); if (f.getId() == null) f.setId(ids.incrementAndGet()); return f; });
        when(notes.save(any())).thenAnswer(i -> i.getArgument(0));
        Family fam = new Family(); fam.setId(20); fam.setAppClientId(CID);
        FamilyMember sarah = new FamilyMember(); sarah.setId(5); sarah.setFirstName("Sarah"); sarah.setLastName("Lee"); sarah.setFamily(fam);
        when(members.findByIdAndTenant(5, CID)).thenReturn(Optional.of(sarah));
        when(writing.isEnabled()).thenReturn(true);
        when(writing.write(any())).thenReturn(new AiWritingService.Draft("Welcome!", "Dear friend, welcome.", 21, null));
        AiAccessPolicy access = new AiAccessPolicy();
        actions = new WorkflowActions(followUps, notes, members, users, push, email, writing, access);
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        engine = new WorkflowEngine(defs, runs, users, perms, access, actions, tm, true, 3);
    }

    WorkflowDefinition rule(String name, String trigger, List<Map<String, Object>> conds, List<Map<String, Object>> acts, boolean enabled) {
        Map<String, Object> raw = new LinkedHashMap<>(); raw.put("name", name); raw.put("trigger", trigger); raw.put("conditions", conds); raw.put("actions", acts);
        WorkflowSchema.Rule r = WorkflowSchema.validate(raw);
        WorkflowDefinition d = new WorkflowDefinition();
        d.setAppClientId(CID); d.setName(name); d.setTrigger(trigger); d.setEnabled(enabled); d.setOwnerUserId(7); d.setOwnerUsername("pat"); d.setCreatedAt(LocalDateTime.now());
        try { List<Object> cs = new ArrayList<>(); for (WorkflowSchema.Condition c : r.conditions()) cs.add(c.toJson()); d.setConditionsJson(M.writeValueAsString(cs));
              List<Object> as = new ArrayList<>(); for (WorkflowSchema.Action a : r.actions()) as.add(a.toJson()); d.setActionsJson(M.writeValueAsString(as)); } catch (Exception e) { throw new RuntimeException(e); }
        return defs.save(d);
    }
    static Map<String, Object> c(String f, String op, String v) { Map<String, Object> m = new LinkedHashMap<>(); m.put("field", f); m.put("op", op); if (v != null) m.put("value", v); return m; }
    static Map<String, Object> a(String type, String... kv) { Map<String, Object> p = new LinkedHashMap<>(); for (int i = 0; i + 1 < kv.length; i += 2) p.put(kv[i], kv[i + 1]); Map<String, Object> m = new LinkedHashMap<>(); m.put("type", type); m.put("params", p); return m; }
    static WorkflowEvent visitor(String key, boolean newVisitor, String interest) {
        return WorkflowEvent.of("VISITOR_RECORDED", CID, key, "Ann Visitor", WorkflowEvent.facts("name", "Ann Visitor", "kind", "FAMILY", "peopleCount", 3, "interest", interest, "followUpRequired", true, "newVisitor", newVisitor, "visitCount", 1, "serviceName", "Sunday Service", "date", "2026-11-05", "visitorId", 44L));
    }

    /* ───────────────────────── 1. schema ───────────────────────── */

    @Test @DisplayName("schema: a closed vocabulary — valid rules pass, every unknown or ill-typed piece is refused, placeholders fill, the ten templates validate")
    void schema() {
        WorkflowSchema.Rule r = WorkflowSchema.validate(Map.of("name", " First visit ", "trigger", "visitor_recorded",
                "conditions", List.of(c("newVisitor", "is_true", null), c("peopleCount", "gte", "2"), c("interest", "in", "INTERESTED,NOT_SURE")),
                "actions", List.of(a("create_follow_up", "title", "Call {{name}}", "dueInDays", "2", "priority", "high"), a("notify_user", "title", "t", "message", "m"))));
        assertThat(r.name()).isEqualTo("First visit"); assertThat(r.trigger()).isEqualTo("VISITOR_RECORDED");
        assertThat(r.conditions()).hasSize(3); assertThat(r.conditions().get(0).value()).isNull();
        assertThat(r.actions()).extracting(WorkflowSchema.Action::type).containsExactly("create_follow_up", "notify_user");
        assertThat(r.toJson()).containsKeys("name", "trigger", "conditions", "actions");

        assertThatThrownBy(() -> WorkflowSchema.validate(Map.of("name", "x", "trigger", "MEMBER_DELETED", "actions", List.of(a("notify_user", "title", "t", "message", "m"))))).hasMessageContaining("Unknown trigger");
        assertThatThrownBy(() -> WorkflowSchema.validate(Map.of("name", "x", "trigger", "VISITOR_RECORDED", "conditions", List.of(c("email", "eq", "x")), "actions", List.of(a("notify_user", "title", "t", "message", "m"))))).hasMessageContaining("Unknown field");
        assertThatThrownBy(() -> WorkflowSchema.validate(Map.of("name", "x", "trigger", "VISITOR_RECORDED", "conditions", List.of(c("name", "matches", "x")), "actions", List.of(a("notify_user", "title", "t", "message", "m"))))).hasMessageContaining("Unknown operator");
        assertThatThrownBy(() -> WorkflowSchema.validate(Map.of("name", "x", "trigger", "VISITOR_RECORDED", "conditions", List.of(c("name", "gt", "x")), "actions", List.of(a("notify_user", "title", "t", "message", "m"))))).hasMessageContaining("needs a number or date");
        assertThatThrownBy(() -> WorkflowSchema.validate(Map.of("name", "x", "trigger", "VISITOR_RECORDED", "conditions", List.of(c("name", "is_true", null)), "actions", List.of(a("notify_user", "title", "t", "message", "m"))))).hasMessageContaining("true/false");
        assertThatThrownBy(() -> WorkflowSchema.validate(Map.of("name", "x", "trigger", "VISITOR_RECORDED", "conditions", List.of(c("peopleCount", "gt", "many")), "actions", List.of(a("notify_user", "title", "t", "message", "m"))))).hasMessageContaining("compares with a number");
        assertThatThrownBy(() -> WorkflowSchema.validate(Map.of("name", "x", "trigger", "VISITOR_RECORDED", "conditions", List.of(c("name", "eq", "")), "actions", List.of(a("notify_user", "title", "t", "message", "m"))))).hasMessageContaining("needs a value");
        assertThatThrownBy(() -> WorkflowSchema.validate(Map.of("name", "x", "trigger", "VISITOR_RECORDED", "actions", List.of(a("send_sms", "to", "all"))))).hasMessageContaining("Unknown action");
        assertThatThrownBy(() -> WorkflowSchema.validate(Map.of("name", "x", "trigger", "VISITOR_RECORDED", "actions", List.of(a("notify_user", "title", "t", "message", "m", "sms", "yes"))))).hasMessageContaining("Unknown parameter");
        assertThatThrownBy(() -> WorkflowSchema.validate(Map.of("name", "x", "trigger", "VISITOR_RECORDED", "actions", List.of(a("notify_user", "title", "t"))))).hasMessageContaining("needs message");
        assertThatThrownBy(() -> WorkflowSchema.validate(Map.of("name", "x", "trigger", "VISITOR_RECORDED", "actions", List.of(a("create_follow_up", "title", "t", "dueInDays", "400"))))).hasMessageContaining("dueInDays");
        assertThatThrownBy(() -> WorkflowSchema.validate(Map.of("name", "x", "trigger", "VISITOR_RECORDED", "actions", List.of(a("create_follow_up", "title", "t", "priority", "URGENT"))))).hasMessageContaining("priority");
        assertThatThrownBy(() -> WorkflowSchema.validate(Map.of("name", "x", "trigger", "VISITOR_RECORDED", "actions", List.of(a("draft_email", "prompt", "p", "tone", "angry"))))).hasMessageContaining("tone");
        assertThatThrownBy(() -> WorkflowSchema.validate(Map.of("name", "x", "trigger", "PRAYER_REQUEST_CREATED", "actions", List.of(a("add_family_note", "title", "t", "body", "b"))))).hasMessageContaining("add_family_note needs a trigger that names a member");
        assertThatThrownBy(() -> WorkflowSchema.validate(Map.of("name", "x", "trigger", "VISITOR_RECORDED", "actions", List.of()))).hasMessageContaining("At least one action");
        assertThatThrownBy(() -> WorkflowSchema.validate(Map.of("name", "", "trigger", "VISITOR_RECORDED", "actions", List.of(a("notify_user", "title", "t", "message", "m"))))).hasMessageContaining("name");
        List<Map<String, Object>> six = new ArrayList<>(); for (int i = 0; i < 6; i++) six.add(a("notify_user", "title", "t", "message", "m"));
        assertThatThrownBy(() -> WorkflowSchema.validate(Map.of("name", "x", "trigger", "VISITOR_RECORDED", "actions", six))).hasMessageContaining("At most 5");
        List<Map<String, Object>> eleven = new ArrayList<>(); for (int i = 0; i < 11; i++) eleven.add(c("name", "exists", null));
        assertThatThrownBy(() -> WorkflowSchema.validate(Map.of("name", "x", "trigger", "VISITOR_RECORDED", "conditions", eleven, "actions", List.of(a("notify_user", "title", "t", "message", "m"))))).hasMessageContaining("At most 10");

        assertThat(WorkflowSchema.fill("Call {{name}} about {{amount}} {{unknown}}", Map.of("name", "Ann", "amount", 12.5))).isEqualTo("Call Ann about 12.5 ");
        assertThat(WorkflowTemplates.ALL).hasSize(10);
        assertThat(WorkflowTemplates.ALL.stream().map(WorkflowTemplates.Template::key).distinct()).hasSize(10);
        for (WorkflowTemplates.Template t : WorkflowTemplates.ALL) assertThat(WorkflowSchema.validate(t.rule()).actions()).isNotEmpty();
        assertThat(WorkflowSchema.describe()).containsKeys("triggers", "operators", "actions", "limits");
        assertThat(WorkflowSchema.TRIGGERS).hasSize(7); assertThat(WorkflowSchema.ACTIONS).hasSize(5);
    }

    @Test @DisplayName("operators: numbers vs strings, dates, in, exists, booleans, missing facts")
    void operators() {
        Map<String, Object> f = WorkflowEvent.facts("amount", 1250.0, "name", "Ann Visitor", "date", "2026-11-05", "newVisitor", true, "interest", "", "count", 3L);
        assertThat(WorkflowEngine.evaluate(List.of(new WorkflowSchema.Condition("amount", "gte", "1000")), f, null)).isNull();
        assertThat(WorkflowEngine.evaluate(List.of(new WorkflowSchema.Condition("amount", "lt", "1000")), f, null)).contains("amount lt 1000");
        assertThat(WorkflowEngine.evaluate(List.of(new WorkflowSchema.Condition("count", "eq", "3.0")), f, null)).isNull();
        assertThat(WorkflowEngine.evaluate(List.of(new WorkflowSchema.Condition("name", "eq", "ann visitor")), f, null)).isNull();
        assertThat(WorkflowEngine.evaluate(List.of(new WorkflowSchema.Condition("name", "contains", "VISIT")), f, null)).isNull();
        assertThat(WorkflowEngine.evaluate(List.of(new WorkflowSchema.Condition("name", "neq", "Ann Visitor")), f, null)).isNotNull();
        assertThat(WorkflowEngine.evaluate(List.of(new WorkflowSchema.Condition("name", "in", "Bob, ann visitor ,Cy")), f, null)).isNull();
        assertThat(WorkflowEngine.evaluate(List.of(new WorkflowSchema.Condition("date", "gt", "2026-11-01")), f, null)).isNull();
        assertThat(WorkflowEngine.evaluate(List.of(new WorkflowSchema.Condition("date", "lte", "2026-11-04")), f, null)).isNotNull();
        assertThat(WorkflowEngine.evaluate(List.of(new WorkflowSchema.Condition("newVisitor", "is_true", null)), f, null)).isNull();
        assertThat(WorkflowEngine.evaluate(List.of(new WorkflowSchema.Condition("newVisitor", "is_false", null)), f, null)).isNotNull();
        assertThat(WorkflowEngine.evaluate(List.of(new WorkflowSchema.Condition("interest", "exists", null)), f, null)).isNotNull();
        assertThat(WorkflowEngine.evaluate(List.of(new WorkflowSchema.Condition("missing", "exists", null)), f, null)).isNotNull();
        assertThat(WorkflowEngine.evaluate(List.of(new WorkflowSchema.Condition("missing", "neq", "x")), f, null)).isNull();
        assertThat(WorkflowEngine.evaluate(List.of(new WorkflowSchema.Condition("missing", "eq", "x")), f, null)).contains("was empty");
        List<String> log = new ArrayList<>();
        assertThat(WorkflowEngine.evaluate(List.of(new WorkflowSchema.Condition("amount", "gte", "1000"), new WorkflowSchema.Condition("name", "eq", "Bob")), f, log)).contains("name eq Bob");
        assertThat(log).containsExactly("amount gte 1000 → yes", "name eq Bob → no");
    }

    /* ───────────────────────── 2. the engine ───────────────────────── */

    @Test @DisplayName("engine: DONE writes through the owner; same event twice → one run; SKIPPED / DENIED (owner disabled, permission lost) / CAPPED / FAILED; disabled engine does nothing")
    void engine() {
        WorkflowDefinition d = rule("First visit", "VISITOR_RECORDED", List.of(c("newVisitor", "is_true", null)),
                List.of(a("create_follow_up", "title", "Welcome call: {{name}}", "description", "Visited {{serviceName}} on {{date}}", "assignTo", "owner", "dueInDays", "2", "priority", "HIGH"),
                        a("notify_user", "to", "8", "title", "New visitor {{name}}", "message", "{{peopleCount}} people")), true);
        List<WorkflowRun> out = engine.handle(visitor("v1", true, "INTERESTED"));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getStatus()).isEqualTo("DONE");
        ArgumentCaptor<FollowUp> fu = ArgumentCaptor.forClass(FollowUp.class);
        verify(followUps).save(fu.capture());
        assertThat(fu.getValue().getTitle()).isEqualTo("Welcome call: Ann Visitor");
        assertThat(fu.getValue().getDescription()).isEqualTo("Visited Sunday Service on 2026-11-05");
        assertThat(fu.getValue().getAssignedTo()).isEqualTo("pat");
        assertThat(fu.getValue().getPriority()).isEqualTo("HIGH"); assertThat(fu.getValue().getStatus()).isEqualTo("PENDING"); assertThat(fu.getValue().getClientId()).isEqualTo(CID);
        assertThat(fu.getValue().getCreatedBy()).isEqualTo("automation:pat");
        assertThat(fu.getValue().getLinkedType()).isEqualTo("VISITOR"); assertThat(fu.getValue().getLinkedId()).isEqualTo(44L);
        LocalDate due = fu.getValue().getDueDate().toInstant().atZone(ZoneId.of("America/Chicago")).toLocalDate();
        assertThat(due).isEqualTo(LocalDate.now(ZoneId.of("America/Chicago")).plusDays(2));
        verify(push).logAndSendToUser(eq("STF-8"), eq(CID), eq("staff"), eq("New visitor Ann Visitor"), eq("3 people"), eq("/followups"), eq("cgp-workflow-" + d.getId()));
        assertThat(out.get(0).getLogJson()).contains("Created follow-up").contains("Notified Sam Ray");
        assertThat(defStore.get(d.getId()).getRunCount()).isEqualTo(1);
        // idempotent: the same event again → no second run, no second follow-up
        assertThat(engine.handle(visitor("v1", true, "INTERESTED"))).isEmpty();
        verify(followUps, times(1)).save(any());
        // conditions false → SKIPPED, nothing executed
        List<WorkflowRun> skipped = engine.handle(visitor("v2", false, "INTERESTED"));
        assertThat(skipped.get(0).getStatus()).isEqualTo("SKIPPED");
        assertThat(skipped.get(0).getLogJson()).contains("newVisitor is_true");
        verify(followUps, times(1)).save(any());
        // owner disabled → DENIED
        pat.setEnabled(false);
        assertThat(engine.handle(visitor("v3", true, "X")).get(0).getStatus()).isEqualTo("DENIED");
        pat.setEnabled(true);
        // owner lost the Follow-ups page → DENIED before anything runs (the notify action would have been allowed)
        patPerms = new UserPermissions(); patPerms.setPermissions("{\"more.followups\":false}");
        WorkflowRun denied = engine.handle(visitor("v4", true, "X")).get(0);
        assertThat(denied.getStatus()).isEqualTo("DENIED");
        assertThat(denied.getLogJson()).contains("Follow-ups page");
        verify(push, times(1)).logAndSendToUser(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        patPerms = null;
        // owner moved to another church → DENIED
        when(users.findByIdAndClientId(7, CID)).thenReturn(Optional.empty());
        assertThat(engine.handle(visitor("v5", true, "X")).get(0).getStatus()).isEqualTo("DENIED");
        when(users.findByIdAndClientId(7, CID)).thenReturn(Optional.of(pat));
        // cap: 3 DONE per day in this engine → the 4th is CAPPED
        engine.handle(visitor("v6", true, "X")); engine.handle(visitor("v7", true, "X"));
        assertThat(runStore.values().stream().filter(r -> "DONE".equals(r.getStatus())).count()).isEqualTo(3);
        WorkflowRun capped = engine.handle(visitor("v8", true, "X")).get(0);
        assertThat(capped.getStatus()).isEqualTo("CAPPED");
        verify(followUps, times(3)).save(any());
        // an action that throws → FAILED, the later action never runs
        runStore.clear();
        WorkflowDefinition bad = rule("Bad", "VISITOR_RECORDED", List.of(), List.of(a("create_follow_up", "title", "x"), a("notify_user", "title", "t", "message", "m")), true);
        defStore.get(d.getId()).setEnabled(false);
        doThrow(new IllegalStateException("db")).when(followUps).save(any());
        WorkflowRun failed = engine.handle(visitor("v9", true, "X")).get(0);
        assertThat(failed.getStatus()).isEqualTo("FAILED"); assertThat(failed.getDefinitionId()).isEqualTo(bad.getId());
        assertThat(failed.getLogJson()).doesNotContain("db");
        verify(push, times(3)).logAndSendToUser(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());   // not a 4th time
        // other church's event → no rule
        assertThat(engine.handle(WorkflowEvent.of("VISITOR_RECORDED", "CHR-B", "b1", "x", Map.of("newVisitor", true)))).isEmpty();
        // disabled engine: zero side effects
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        WorkflowEngine off = new WorkflowEngine(defs, runs, users, perms, new AiAccessPolicy(), actions, tm, false, 200);
        runStore.clear();
        assertThat(off.handle(visitor("v10", true, "X"))).isEmpty();
        assertThat(runStore).isEmpty();
        verifyNoInteractions(tm);
        // publisher switched off never publishes
        ApplicationEventPublisher pub = mock(ApplicationEventPublisher.class);
        new WorkflowEvents(pub, false).publish(visitor("v11", true, "X"));
        verifyNoInteractions(pub);
        new WorkflowEvents(pub, true).publish(visitor("v11", true, "X"));
        verify(pub).publishEvent(any(WorkflowEvent.class));
        new WorkflowEvents(pub, true).publish(WorkflowEvent.of("NOT_A_TRIGGER", CID, "k", "s", Map.of()));
        verify(pub, times(1)).publishEvent(any(WorkflowEvent.class));
    }

    @Test @DisplayName("actions: e-mail only to staff of the church, family note via the member, AI draft saved as a follow-up, recipient validation, dry run writes nothing")
    void actionsTest() {
        WorkflowDefinition d = rule("Gift", "GIFT_RECORDED", List.of(c("amount", "gte", "1000")),
                List.of(a("email_staff", "to", "owner", "subject", "Gift from {{name}}", "body", "{{amount}} to {{fund}}"),
                        a("add_family_note", "title", "Large gift", "body", "{{amount}} on {{date}}"),
                        a("draft_email", "prompt", "Thank {{name}} for a gift to {{fund}}", "tone", "warm")), true);
        WorkflowEvent gift = WorkflowEvent.of("GIFT_RECORDED", CID, "income:1", "Sarah Lee", WorkflowEvent.facts("amount", 1500.0, "fund", "Missions", "guest", false, "memberId", 5, "name", "Sarah Lee", "date", "2026-11-05", "quickAdd", false));
        WorkflowRun r = engine.handle(gift).get(0);
        assertThat(r.getStatus()).as(r.getLogJson()).isEqualTo("DONE");
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email).sendOrgEmail(eq("pat@example.test"), eq("Gift from Sarah Lee"), html.capture(), eq(CID));
        assertThat(html.getValue()).contains("1500.0 to Missions").contains("automation");
        ArgumentCaptor<Note> note = ArgumentCaptor.forClass(Note.class);
        verify(notes).save(note.capture());
        assertThat(note.getValue().getTitle()).isEqualTo("Family note — Lee: Large gift"); assertThat(note.getValue().getAppClientId()).isEqualTo(CID); assertThat(note.getValue().getCreatedByName()).contains("Automation");
        ArgumentCaptor<AiWritingService.Request> wr = ArgumentCaptor.forClass(AiWritingService.Request.class);
        verify(writing).write(wr.capture());
        assertThat(wr.getValue().prompt()).isEqualTo("Thank Sarah Lee for a gift to Missions"); assertThat(wr.getValue().action()).isEqualTo("draft"); assertThat(wr.getValue().channel()).isEqualTo("email");
        ArgumentCaptor<FollowUp> fu = ArgumentCaptor.forClass(FollowUp.class);
        verify(followUps).save(fu.capture());
        assertThat(fu.getValue().getTitle()).isEqualTo("Draft: Welcome!"); assertThat(fu.getValue().getDescription()).contains("review before sending").contains("Dear friend");
        assertThat(fu.getValue().getLinkedType()).isEqualTo("MEMBER"); assertThat(fu.getValue().getLinkedLabel()).isEqualTo("Sarah Lee");
        // a small gift → SKIPPED, nothing sent
        WorkflowEvent small = WorkflowEvent.of("GIFT_RECORDED", CID, "income:2", "Sarah Lee", WorkflowEvent.facts("amount", 20.0, "fund", "General", "guest", false, "memberId", 5, "name", "Sarah Lee", "date", "2026-11-05", "quickAdd", false));
        assertThat(engine.handle(small).get(0).getStatus()).isEqualTo("SKIPPED");
        verify(email, times(1)).sendOrgEmail(anyString(), anyString(), anyString(), anyString());
        // recipients: a member-looking address or an unknown id is refused at check time; a staff e-mail or id is accepted
        AiAccessPolicy.AiAccess ac = new AiAccessPolicy().resolve(OwnerRequest.of(pat, "pat", null), CID);
        WorkflowActions.Ctx ctx = new WorkflowActions.Ctx(CID, gift, pat, "pat", ac, d.getId(), false);
        assertThat(actions.check(ctx, new WorkflowSchema.Action("email_staff", Map.of("to", "member@example.test", "subject", "s", "body", "b")))).contains("not an active staff account");
        assertThat(actions.check(ctx, new WorkflowSchema.Action("email_staff", Map.of("to", "999", "subject", "s", "body", "b")))).contains("not an active staff account");
        assertThat(actions.check(ctx, new WorkflowSchema.Action("email_staff", Map.of("to", "sam@example.test", "subject", "s", "body", "b")))).isNull();
        assertThat(actions.check(ctx, new WorkflowSchema.Action("notify_user", Map.of("to", "8", "title", "s", "message", "b")))).isNull();
        assertThat(actions.check(ctx, new WorkflowSchema.Action("create_follow_up", Map.of("title", "t", "assignTo", "STF-8")))).isNull();
        // the draft step needs the aiWrite plan feature
        com.churchgeniuspro.service.SubscriptionService subs = mock(com.churchgeniuspro.service.SubscriptionService.class);
        when(subs.isFeatureEnabled(anyString(), anyString())).thenAnswer(i -> !"aiWrite".equals(i.getArgument(1)));
        WorkflowActions gated = new WorkflowActions(followUps, notes, members, users, push, email, writing, new AiAccessPolicy(subs));
        assertThat(gated.check(ctx, new WorkflowSchema.Action("draft_email", Map.of("prompt", "p")))).contains("AI Writing");
        // dry run: "Would …" lines and no writes
        clearInvocations(email, notes, followUps, writing);
        Map<String, Object> sample = new LinkedHashMap<>(WorkflowController.sampleFacts("GIFT_RECORDED")); sample.put("memberId", 5);
        Map<String, Object> dry = engine.dryRun(d, sample);
        assertThat(dry.get("status")).isEqualTo("DRY");
        assertThat(dry.get("conditionsMet")).isEqualTo(true);
        assertThat((List<String>) dry.get("actions")).allMatch(s -> s.startsWith("Would")).hasSize(3);
        verifyNoInteractions(email, notes); verify(writing, never()).write(any()); verify(followUps, never()).save(any());
        assertThat((List<String>) dry.get("conditions")).containsExactly("amount gte 1000 → yes");
        // a member without a family / unknown member → the note is skipped, not failed
        WorkflowEvent unknown = WorkflowEvent.of("GIFT_RECORDED", CID, "income:3", "X", WorkflowEvent.facts("amount", 5000.0, "fund", "F", "guest", false, "memberId", 999, "name", "X", "date", "2026-11-05", "quickAdd", false));
        WorkflowRun r3 = engine.handle(unknown).get(0);
        assertThat(r3.getStatus()).isEqualTo("DONE"); assertThat(r3.getLogJson()).contains("Note skipped");
    }

    /* ───────────────────────── 3. nightly triggers ───────────────────────── */

    @Test @DisplayName("nightly: a changed signal between two snapshot days fires once per member per day; overdue open follow-ups fire with the day count")
    void nightly() {
        MemberEngagementSnapshotRepository snaps = mock(MemberEngagementSnapshotRepository.class);
        LocalDate today = LocalDate.of(2026, 11, 5);
        when(snaps.latestDateOnOrBefore(CID, today)).thenReturn(today);
        when(snaps.latestDateOnOrBefore(CID, today.minusDays(1))).thenReturn(today.minusDays(1));
        when(snaps.findByAppClientIdAndSnapshotDate(CID, today.minusDays(1))).thenReturn(List.of(snap(5, "ENGAGED"), snap(6, "WATCH"), snap(7, "NEW")));
        when(snaps.findByAppClientIdAndSnapshotDate(CID, today)).thenReturn(List.of(snap(5, "AT_RISK"), snap(6, "WATCH"), snap(8, "QUIET")));
        FamilyMember m5 = new FamilyMember(); m5.setId(5); m5.setFirstName("Sarah"); m5.setLastName("Lee"); Family fam = new Family(); fam.setId(20); m5.setFamily(fam);
        when(members.findByIdAndTenant(5, CID)).thenReturn(Optional.of(m5));
        ApplicationEventPublisher pub = mock(ApplicationEventPublisher.class);
        WorkflowEvents events = new WorkflowEvents(pub, true);
        WorkflowNightlyService nightly = new WorkflowNightlyService(defs, snaps, members, followUps, events);
        assertThat(nightly.signalChanges(CID, today)).isEqualTo(1);   // 6 unchanged, 7 gone, 8 has no previous day
        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(pub).publishEvent(ev.capture());
        WorkflowEvent e = (WorkflowEvent) ev.getValue();
        assertThat(e.trigger()).isEqualTo("ENGAGEMENT_SIGNAL_CHANGED"); assertThat(e.eventKey()).isEqualTo("signal:5:2026-11-05");
        assertThat(e.facts()).containsEntry("signal", "AT_RISK").containsEntry("previousSignal", "ENGAGED").containsEntry("name", "Sarah Lee").containsEntry("familyId", 20);
        // no previous snapshot → nothing
        when(snaps.latestDateOnOrBefore(CID, today.minusDays(1))).thenReturn(null);
        assertThat(nightly.signalChanges(CID, today)).isZero();
        // overdue
        FollowUp late = new FollowUp(); late.setId(40L); late.setTitle("Visit the Lees"); late.setStatus("PENDING"); late.setAssignedTo("pat"); late.setPriority("HIGH");
        late.setDueDate(java.util.Date.from(today.minusDays(4).atStartOfDay(ZoneId.of("America/Chicago")).toInstant()));
        FollowUp future = new FollowUp(); future.setId(41L); future.setTitle("Later"); future.setStatus("PENDING"); future.setDueDate(java.util.Date.from(today.plusDays(1).atStartOfDay(ZoneId.of("America/Chicago")).toInstant()));
        FollowUp undated = new FollowUp(); undated.setId(42L); undated.setTitle("No date"); undated.setStatus("PENDING");
        when(followUps.findByClientIdAndStatusAndDeleteFlagFalseOrderByCreatedAtDesc(CID, "PENDING")).thenReturn(List.of(late, future, undated));
        when(followUps.findByClientIdAndStatusAndDeleteFlagFalseOrderByCreatedAtDesc(CID, "MISSED")).thenReturn(List.of());
        clearInvocations(pub);
        assertThat(nightly.overdue(CID, today)).isEqualTo(1);
        verify(pub).publishEvent(ev.capture());
        e = (WorkflowEvent) ev.getValue();
        assertThat(e.trigger()).isEqualTo("FOLLOW_UP_OVERDUE"); assertThat(e.eventKey()).isEqualTo("overdue:40:2026-11-05");
        assertThat(e.facts()).containsEntry("daysOverdue", 4L).containsEntry("title", "Visit the Lees").containsEntry("assignedTo", "pat");
        // the nightly pass visits only churches with an enabled rule for the trigger, and does nothing when the engine is off
        when(defs.clientsWithEnabledTrigger("ENGAGEMENT_SIGNAL_CHANGED")).thenReturn(List.of());
        when(defs.clientsWithEnabledTrigger("FOLLOW_UP_OVERDUE")).thenReturn(List.of());
        clearInvocations(pub); nightly.nightly(); verifyNoInteractions(pub);
        clearInvocations(defs);
        new WorkflowNightlyService(defs, snaps, members, followUps, new WorkflowEvents(pub, false)).nightly();
        verifyNoInteractions(pub, defs);
    }

    static MemberEngagementSnapshot snap(int memberId, String signal) { MemberEngagementSnapshot s = new MemberEngagementSnapshot(); s.setMemberId(memberId); s.setSignal(signal); s.setAppClientId(CID); return s; }

    /* ───────────────────────── 4. the page API ───────────────────────── */

    @Test @DisplayName("/api/workflows: gates, list with templates/vocabulary/staff, create from template (off, owned), validation 400, update, enable makes the enabler owner, delete, runs, test run, draft 503/422/200")
    void api() throws Exception {
        OpenAiVoiceService openAi = mock(OpenAiVoiceService.class);
        when(openAi.isEnabled()).thenReturn(false);
        WorkflowRuleDrafter drafter = new WorkflowRuleDrafter(openAi);
        WorkflowEvents events = new WorkflowEvents(mock(ApplicationEventPublisher.class), true);
        WorkflowController c = new WorkflowController(defs, runs, users, engine, drafter, events);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(c).build();

        mvc.perform(get("/api/workflows")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/workflows").sessionAttr("username", "m").sessionAttr("role", "Member").sessionAttr("memberId", 3).sessionAttr("appClientId", CID)).andExpect(status().isForbidden());
        mvc.perform(get("/api/workflows").session(user("User", 8))).andExpect(status().isForbidden());
        mvc.perform(get("/api/workflows").sessionAttr("username", "c").sessionAttr("role", "church").sessionAttr("church", true).sessionAttr("appClientId", CID).sessionAttr("clientId", CID)).andExpect(status().isForbidden());
        mvc.perform(get("/workflows")).andExpect(forwardedUrl("/access-denied.html"));
        MockHttpSession admin = user("Admin", 7);
        mvc.perform(get("/workflows").session(admin)).andExpect(forwardedUrl("/workflows.html"));

        MvcResult r = mvc.perform(get("/api/workflows").session(admin)).andExpect(status().isOk()).andReturn();
        Map<String, Object> st = body(r);
        assertThat(st.get("engineEnabled")).isEqualTo(true); assertThat(st.get("draftEnabled")).isEqualTo(false);
        assertThat((List<?>) st.get("templates")).hasSize(10); assertThat((Map<String, Object>) st.get("vocabulary")).containsKeys("triggers", "actions");
        assertThat((List<Map<String, Object>>) st.get("staff")).extracting(m -> m.get("id")).containsExactlyInAnyOrder(7, 8);
        assertThat((List<?>) st.get("rules")).isEmpty();
        // template → created off, owned by the admin
        r = mvc.perform(post("/api/workflows").session(admin).contentType("application/json").content("{\"templateKey\":\"first_time_visitor\"}")).andExpect(status().isOk()).andReturn();
        Map<String, Object> created = body(r);
        assertThat(created.get("enabled")).isEqualTo(false); assertThat(created.get("owner")).isEqualTo("u7"); assertThat(created.get("trigger")).isEqualTo("VISITOR_RECORDED"); assertThat(created.get("templateKey")).isEqualTo("first_time_visitor");
        long id = ((Number) created.get("id")).longValue();
        // same template again → "(2)" name, unknown template 400, invalid rule 400 with the schema's sentence
        r = mvc.perform(post("/api/workflows").session(admin).contentType("application/json").content("{\"templateKey\":\"first_time_visitor\"}")).andExpect(status().isOk()).andReturn();
        assertThat(body(r).get("name")).asString().endsWith("(2)");
        mvc.perform(post("/api/workflows").session(admin).contentType("application/json").content("{\"templateKey\":\"nope\"}")).andExpect(status().isBadRequest());
        r = mvc.perform(post("/api/workflows").session(admin).contentType("application/json").content("{\"name\":\"x\",\"trigger\":\"VISITOR_RECORDED\",\"actions\":[{\"type\":\"send_sms\",\"params\":{}}]}")).andExpect(status().isBadRequest()).andReturn();
        assertThat(body(r).get("error")).asString().contains("Unknown action");
        // a User (role) cannot create even with the body right
        mvc.perform(post("/api/workflows").session(user("User", 8)).contentType("application/json").content("{\"templateKey\":\"first_time_visitor\"}")).andExpect(status().isForbidden());
        // update by another admin → that admin becomes the owner; name clash 409
        MockHttpSession other = user("SuperAdmin", 8);
        r = mvc.perform(put("/api/workflows/" + id).session(other).contentType("application/json").content("{\"name\":\"Renamed\",\"trigger\":\"VISITOR_RECORDED\",\"conditions\":[],\"actions\":[{\"type\":\"notify_user\",\"params\":{\"title\":\"t\",\"message\":\"m\"}}]}")).andExpect(status().isOk()).andReturn();
        assertThat(body(r).get("owner")).isEqualTo("u8"); assertThat(body(r).get("name")).isEqualTo("Renamed");
        assertThat(defStore.get(id).getOwnerUserId()).isEqualTo(8);
        mvc.perform(put("/api/workflows/" + id).session(admin).contentType("application/json").content("{\"name\":\"First-time visitor → follow-up in 2 days (2)\",\"trigger\":\"VISITOR_RECORDED\",\"actions\":[{\"type\":\"notify_user\",\"params\":{\"title\":\"t\",\"message\":\"m\"}}]}")).andExpect(status().isConflict());
        mvc.perform(put("/api/workflows/999").session(admin).contentType("application/json").content("{\"name\":\"x\",\"trigger\":\"VISITOR_RECORDED\",\"actions\":[{\"type\":\"notify_user\",\"params\":{\"title\":\"t\",\"message\":\"m\"}}]}")).andExpect(status().isNotFound());
        // enable → the enabler owns it; a broken stored rule cannot be enabled
        r = mvc.perform(post("/api/workflows/" + id + "/enable").session(admin).contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isOk()).andReturn();
        assertThat(body(r).get("enabled")).isEqualTo(true); assertThat(body(r).get("owner")).isEqualTo("u7");
        defStore.get(id).setActionsJson("[{\"type\":\"send_sms\"}]");
        mvc.perform(post("/api/workflows/" + id + "/enable").session(admin).contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isConflict());
        r = mvc.perform(get("/api/workflows").session(admin)).andReturn();
        assertThat(((List<Map<String, Object>>) body(r).get("rules")).stream().filter(m -> ((Number) m.get("id")).longValue() == id).findFirst().get()).containsEntry("broken", true);
        defStore.get(id).setActionsJson("[{\"type\":\"notify_user\",\"params\":{\"title\":\"t\",\"message\":\"m\"}}]");
        // test run (dry) on sample facts
        r = mvc.perform(post("/api/workflows/" + id + "/test").session(admin).contentType("application/json").content("{}")).andExpect(status().isOk()).andReturn();
        assertThat(body(r).get("status")).isEqualTo("DRY");
        assertThat((Map<String, Object>) body(r).get("facts")).containsEntry("name", "Sample Visitor");
        assertThat((List<String>) body(r).get("actions")).first().asString().startsWith("Would notify");
        verifyNoInteractions(push);
        r = mvc.perform(post("/api/workflows/" + id + "/test").session(admin).contentType("application/json").content("{\"facts\":{\"newVisitor\":\"false\",\"peopleCount\":\"4\",\"evil\":\"x\"}}")).andReturn();
        assertThat((Map<String, Object>) body(r).get("facts")).containsEntry("newVisitor", false).containsEntry("peopleCount", 4).doesNotContainKey("evil");
        // runs
        runStore.clear();
        WorkflowRun run = new WorkflowRun(); run.setAppClientId(CID); run.setDefinitionId(id); run.setTrigger("VISITOR_RECORDED"); run.setEventKey("k"); run.setSubject("Ann"); run.setStatus("DONE"); run.setLogJson("[\"Notified Pat Lee\"]"); runs.save(run);
        WorkflowRun otherChurch = new WorkflowRun(); otherChurch.setAppClientId("CHR-B"); otherChurch.setDefinitionId(99L); otherChurch.setTrigger("x"); otherChurch.setEventKey("k"); otherChurch.setStatus("DONE"); runs.save(otherChurch);
        r = mvc.perform(get("/api/workflows/runs").session(admin)).andExpect(status().isOk()).andReturn();
        assertThat((List<Map<String, Object>>) body(r).get("rows")).hasSize(1).first().satisfies(m -> { assertThat(m.get("rule")).isEqualTo("Renamed"); assertThat(m.get("log")).isEqualTo(List.of("Notified Pat Lee")); });
        r = mvc.perform(get("/api/workflows/" + id + "/runs").session(admin)).andReturn();
        assertThat((List<?>) body(r).get("rows")).hasSize(1);
        mvc.perform(get("/api/workflows/999/runs").session(admin)).andExpect(status().isNotFound());
        // delete = soft, disabled
        mvc.perform(delete("/api/workflows/" + id).session(admin)).andExpect(status().isOk());
        assertThat(defStore.get(id).isDeleteFlag()).isTrue(); assertThat(defStore.get(id).isEnabled()).isFalse();
        mvc.perform(delete("/api/workflows/" + id).session(admin)).andExpect(status().isNotFound());
        // draft: service off → 503; AI Assistant permission required; model refusal → 422; good JSON → validated rule, not saved
        mvc.perform(post("/api/workflows/draft").session(admin).contentType("application/json").content("{\"description\":\"when a visitor comes notify me\"}")).andExpect(status().isServiceUnavailable());
        when(openAi.isEnabled()).thenReturn(true);
        MockHttpSession noAi = user("Admin", 7); noAi.setAttribute("privileges", "{\"more.aiassistant\":false}");
        mvc.perform(post("/api/workflows/draft").session(noAi).contentType("application/json").content("{\"description\":\"x\"}")).andExpect(status().isForbidden());
        when(openAi.chatJson(anyString(), eq("text every member"), anyDouble())).thenReturn(M.readTree("{\"refusal\":\"rules cannot message members\"}"));
        r = mvc.perform(post("/api/workflows/draft").session(admin).contentType("application/json").content("{\"description\":\"text every member\"}")).andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(body(r).get("error")).asString().contains("cannot message members");
        when(openAi.chatJson(anyString(), eq("bad"), anyDouble())).thenReturn(M.readTree("{\"name\":\"x\",\"trigger\":\"VISITOR_RECORDED\",\"actions\":[{\"type\":\"delete_member\"}]}"));
        r = mvc.perform(post("/api/workflows/draft").session(admin).contentType("application/json").content("{\"description\":\"bad\"}")).andExpect(status().isUnprocessableEntity()).andReturn();
        assertThat(body(r).get("error")).asString().contains("refused").contains("Unknown action");
        when(openAi.chatJson(anyString(), eq("notify me about interested visitors"), anyDouble())).thenReturn(M.readTree("{\"name\":\"Interested visitors\",\"trigger\":\"VISITOR_RECORDED\",\"conditions\":[{\"field\":\"interest\",\"op\":\"eq\",\"value\":\"INTERESTED\"}],\"actions\":[{\"type\":\"notify_user\",\"params\":{\"title\":\"Interested: {{name}}\",\"message\":\"{{name}} at {{serviceName}}\"}}]}"));
        long before = defStore.size();
        r = mvc.perform(post("/api/workflows/draft").session(admin).contentType("application/json").content("{\"description\":\"notify me about interested visitors\"}")).andExpect(status().isOk()).andReturn();
        assertThat(body(r).get("draft")).isEqualTo(true); assertThat(body(r).get("trigger")).isEqualTo("VISITOR_RECORDED");
        assertThat(defStore).hasSize((int) before);
        ArgumentCaptor<String> sys = ArgumentCaptor.forClass(String.class);
        verify(openAi, atLeastOnce()).chatJson(sys.capture(), anyString(), anyDouble());
        assertThat(sys.getValue()).contains("VISITOR_RECORDED").contains("create_follow_up").contains("never a member or visitor").contains("age");
    }

    static MockHttpSession user(String role, int appUserId) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "u" + appUserId); s.setAttribute("role", role); s.setAttribute("appClientId", CID); s.setAttribute("appUserId", appUserId); s.setAttribute("clientId", "STF-" + appUserId);
        return s;
    }
    @SuppressWarnings("unchecked") static Map<String, Object> body(MvcResult r) throws Exception { return M.readValue(r.getResponse().getContentAsString(), LinkedHashMap.class); }

    /* ───────────────────────── 5. wiring ───────────────────────── */

    @Test @DisplayName("wiring: plan feature + seeder + properties + migration + audit channel + retention, the five publication points, AFTER_COMMIT listener, staff-only sends, nav/help/permission key")
    void wiring() throws Exception {
        Path root = Path.of("src/main");
        assertThat(SubscriptionFeatureCatalog.FEATURES).anySatisfy(f -> { assertThat(f.key()).isEqualTo("workflows"); assertThat(f.pathPrefixes()).contains("/api/workflows"); });
        String seeder = Files.readString(root.resolve("java/com/churchgeniuspro/config/SubscriptionPlanSeeder.java"));
        assertThat(seeder).contains("denyFeatureOnce(repo, \"FREE\", \"workflows\")").contains("denyFeatureOnce(repo, \"STANDARD\", \"workflows\")");
        assertThat(seeder.split("\\\\\"workflows\\\\\":false").length).isGreaterThanOrEqualTo(3);
        String props = Files.readString(root.resolve("resources/application.properties"));
        assertThat(props).contains("workflow.enabled=${WORKFLOW_ENABLED:true}").contains("workflow.runs-per-tenant-per-day=").contains("workflow.nightly-cron=").contains("audit.retention.workflow-run-days=");
        String mig = Files.readString(Path.of("scripts/sql/migrate_production.sql"));
        assertThat(mig).contains("CREATE TABLE IF NOT EXISTS workflow_definition").contains("CREATE TABLE IF NOT EXISTS workflow_run").contains("idx_wf_run_def_key ON workflow_run (definition_id, event_key)");
        assertThat(com.churchgeniuspro.ai.audit.AiAuditService.channelOf("/api/workflows/draft")).isEqualTo("WORKFLOWS");
        assertThat(Files.readString(root.resolve("java/com/churchgeniuspro/service/AuditTableRetentionService.java"))).contains("\"workflow_run\"");
        // the five publication points
        for (String f : List.of("service/VisitorCheckInService.java", "service/AttendanceService.java", "controller/FollowUpController.java", "controller/PrayerRequestController.java", "service/IncomeService.java")) {
            String src = Files.readString(root.resolve("java/com/churchgeniuspro/" + f));
            assertThat(src).as(f).contains("WorkflowEvents.publish(workflowEvents").contains("@org.springframework.beans.factory.annotation.Autowired(required = false)");
        }
        for (String t : List.of("VISITOR_RECORDED", "ATTENDANCE_SAVED", "FOLLOW_UP_CREATED", "PRAYER_REQUEST_CREATED", "GIFT_RECORDED")) {
            boolean found = false;
            for (String f : List.of("service/VisitorCheckInService.java", "service/AttendanceService.java", "controller/FollowUpController.java", "controller/PrayerRequestController.java", "service/IncomeService.java"))
                if (Files.readString(root.resolve("java/com/churchgeniuspro/" + f)).contains("\"" + t + "\"")) found = true;
            assertThat(found).as(t + " is published somewhere").isTrue();
        }
        String eng = Files.readString(root.resolve("java/com/churchgeniuspro/workflow/WorkflowEngine.java"));
        assertThat(eng).contains("@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)").contains("PROPAGATION_REQUIRES_NEW");
        // staff-only sends: the actions class never takes an address from the event facts and never touches SmsService / WhatsApp / member push
        String acts = Files.readString(root.resolve("java/com/churchgeniuspro/workflow/WorkflowActions.java")).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//.*", "");
        assertThat(acts).doesNotContain("SmsService").doesNotContain("WhatsApp").doesNotContain("\"member\"").doesNotContain("facts.get(\"email\")").doesNotContain("facts.get(\"phone\")");
        assertThat(acts).contains("sendOrgEmail(to.getEmail()");
        // UI + permission key
        assertThat(Files.readString(root.resolve("resources/static/shell.js"))).contains("href: '/workflows'");
        String session = Files.readString(root.resolve("resources/static/session.js"));
        assertThat(session).contains("'/workflows': 'workflows'").contains("'/workflows':           'more.workflows'").contains("'more.workflows'");
        assertThat(Files.readString(root.resolve("resources/static/viewUsers.html"))).contains("key: 'more.workflows'");
        assertThat(com.churchgeniuspro.util.PermissionCatalog.isKnown("more.workflows")).isTrue();
        String help = Files.readString(root.resolve("resources/static/helpCenter.html"));
        assertThat(help).contains("id=\"hc-workflows\"").contains("data-feature=\"workflows\"").contains("id: 'workflows'");
        assertThat(Files.readString(root.resolve("resources/static/aiActivity.html"))).contains("WORKFLOWS:'Automations'");
        String page = Files.readString(root.resolve("resources/static/workflows.html"));
        assertThat(page).contains("/cgp-api.js").contains("CGP_api('/api/workflows'").doesNotContain("alert(").doesNotContain("confirm(");
    }
}
