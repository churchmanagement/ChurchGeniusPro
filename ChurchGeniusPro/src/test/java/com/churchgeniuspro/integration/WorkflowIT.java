package com.churchgeniuspro.integration;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import com.churchgeniuspro.service.AuditTableRetentionService;
import com.churchgeniuspro.service.IncomeService;
import com.churchgeniuspro.service.VisitorCheckInService;
import com.churchgeniuspro.service.VisitorCheckInService.Person;
import com.churchgeniuspro.service.VisitorCheckInService.Registration;
import com.churchgeniuspro.workflow.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI Phase 6 on PostgreSQL with two churches: a real visitor registration and a real income entry publish
 * events AFTER COMMIT and run only the right church's enabled rules; the same event twice → one run; a
 * revoked owner permission → DENIED and nothing written; the ten templates run (DONE or SKIPPED, never
 * FAILED) on real events; run history is per church; retention purges only aged runs; migration block twice.
 */
@EnabledIf("com.churchgeniuspro.integration.WorkflowIT#databaseAvailable")
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = { "app.base-url=http://localhost", "workflow.runs-per-tenant-per-day=500" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("AI Phase 6 — workflows on PostgreSQL (two churches)")
class WorkflowIT {

    static PostgreSQLContainer<?> POSTGRES;
    static boolean databaseAvailable() {
        if (System.getenv("CGP_IT_JDBC_URL") != null) return true;
        try { return DockerClientFactory.instance().isDockerAvailable(); } catch (Throwable t) { return false; }
    }
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        String url = System.getenv("CGP_IT_JDBC_URL");
        if (url != null) {
            r.add("spring.datasource.url", () -> url);
            r.add("spring.datasource.username", () -> System.getenv().getOrDefault("CGP_IT_JDBC_USER", "postgres"));
            r.add("spring.datasource.password", () -> System.getenv().getOrDefault("CGP_IT_JDBC_PASSWORD", ""));
            return;
        }
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine"); POSTGRES.start();
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername); r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    static final String A = "CGP-WF-ITA-" + System.currentTimeMillis(), B = A.replace("ITA", "ITB");
    static final ObjectMapper M = new ObjectMapper();

    @Autowired WorkflowEngine engine; @Autowired WorkflowEvents events; @Autowired VisitorCheckInService visitors; @Autowired IncomeService incomes;
    @Autowired WorkflowDefinitionRepository defs; @Autowired WorkflowRunRepository runs; @Autowired FollowUpRepository followUps;
    @Autowired AppUserRepository userRepo; @Autowired UserPermissionsRepository permRepo; @Autowired FamilyRepository familyRepo; @Autowired FamilyMemberRepository memberRepo;
    @Autowired SubSourceRepository subSources; @Autowired MainSourceRepository mainSources; @Autowired TransactionTypeRepository txTypes;
    @Autowired AuditTableRetentionService retention; @Autowired JdbcTemplate jdbc;

    AppUser adminA, adminB; Integer sarahA; Integer fundA, typeA; AttendanceServiceType svcA, svcB;

    @BeforeAll
    void seed() {
        for (String cid : List.of(A, B)) {
            AppUser u = new AppUser(); u.setUserId("STF-" + cid); u.setClientId(cid); u.setRole("Admin"); u.setFirstName("Pat"); u.setLastName(cid); u.setEmail("pat." + cid.toLowerCase() + "@example.test"); u = userRepo.save(u);
            Family f = new Family(); f.setAppClientId(cid); f = familyRepo.save(f);
            FamilyMember sarah = new FamilyMember(); sarah.setFamily(f); sarah.setFirstName("Sarah"); sarah.setLastName("Lee"); sarah.setRole("Adult"); sarah.setIncludeContributions(true); sarah = memberRepo.save(sarah);
            AttendanceServiceType svc = visitors.createTarget(cid, "service", "Sunday Service", null, false);
            if (cid.equals(A)) { adminA = u; sarahA = sarah.getId(); svcA = svc; } else { adminB = u; svcB = svc; }
        }
        MainSource ms = new MainSource(); ms.setAppClientId(A); ms.setSourceName("Offerings"); ms = mainSources.save(ms);
        SubSource ss = new SubSource(); ss.setAppClientId(A); ss.setSourceName("Missions"); ss.setMainSource(ms); fundA = subSources.save(ss).getId();
        TransactionType tt = new TransactionType(); tt.setAppClientId(A); tt.setTypeName("Cash"); typeA = txTypes.save(tt).getId();
    }

    WorkflowDefinition rule(String cid, AppUser owner, String name, String trigger, String conditions, String actions, boolean enabled) {
        WorkflowDefinition d = new WorkflowDefinition();
        d.setAppClientId(cid); d.setName(name); d.setTrigger(trigger); d.setConditionsJson(conditions); d.setActionsJson(actions);
        d.setEnabled(enabled); d.setOwnerUserId(owner.getId()); d.setOwnerUsername("pat-" + cid); d.setCreatedBy("it");
        return defs.save(d);
    }
    static Person p(String first, String last) { return new Person(first, last, null, null, null, first.toLowerCase() + "." + last.toLowerCase() + "@visit.test", null, null, null, null, null); }

    @Test @Order(1) @DisplayName("a real visitor registration runs A's enabled rule after commit (follow-up created in A), not B's, not A's disabled rule; the same event twice → one run")
    void visitorFlow() {
        WorkflowDefinition on = rule(A, adminA, "First visit", "VISITOR_RECORDED", "[{\"field\":\"newVisitor\",\"op\":\"is_true\"}]",
                "[{\"type\":\"create_follow_up\",\"params\":{\"title\":\"Welcome call: {{name}}\",\"assignTo\":\"owner\",\"dueInDays\":\"2\",\"priority\":\"HIGH\"}}]", true);
        WorkflowDefinition off = rule(A, adminA, "Disabled", "VISITOR_RECORDED", "[]", "[{\"type\":\"notify_user\",\"params\":{\"title\":\"t\",\"message\":\"m\"}}]", false);
        WorkflowDefinition bRule = rule(B, adminB, "B first visit", "VISITOR_RECORDED", "[]", "[{\"type\":\"create_follow_up\",\"params\":{\"title\":\"B: {{name}}\"}}]", true);
        long followUpsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM follow_up WHERE client_id = ?", Long.class, A);
        Map<String, Object> reg = visitors.register(A, "pat", new Registration("INDIVIDUAL", null, List.of(p("Ann", "Visitor")), svcA.getId(), LocalDate.of(2026, 11, 1), "INTERESTED", false, null, null, null, null, null));
        assertThat(reg.get("status")).isEqualTo("success");
        List<WorkflowRun> aRuns = runs.findTop50ByAppClientIdAndDefinitionIdOrderByCreatedAtDesc(A, on.getId());
        assertThat(aRuns).hasSize(1);
        assertThat(aRuns.get(0).getStatus()).as(aRuns.get(0).getLogJson()).isEqualTo("DONE");
        assertThat(aRuns.get(0).getSubject()).isEqualTo("Ann Visitor");
        List<Map<String, Object>> fus = jdbc.queryForList("SELECT title, assigned_to, status, priority, linked_type, created_by FROM follow_up WHERE client_id = ? AND created_by LIKE 'automation:%'", A);
        assertThat(fus).hasSize(1);
        assertThat(fus.get(0).get("title")).isEqualTo("Welcome call: Ann Visitor");
        assertThat(fus.get(0).get("assigned_to")).isEqualTo("pat-" + A);
        assertThat(fus.get(0).get("linked_type")).isEqualTo("VISITOR");
        assertThat(runs.findTop50ByAppClientIdAndDefinitionIdOrderByCreatedAtDesc(A, off.getId())).isEmpty();
        assertThat(runs.findTop50ByAppClientIdAndDefinitionIdOrderByCreatedAtDesc(B, bRule.getId())).as("B's rule never sees A's visitor").isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM follow_up WHERE client_id = ? AND created_by LIKE 'automation:%'", Long.class, B)).isZero();
        assertThat(defs.findByIdAndAppClientIdAndDeleteFlagFalse(on.getId(), A).get().getRunCount()).isEqualTo(1);
        // the same event republished (a retry, a duplicate delivery) → still one run, still one follow-up
        WorkflowEvent same = WorkflowEvent.of("VISITOR_RECORDED", A, aRuns.get(0).getEventKey(), "Ann Visitor", Map.of("name", "Ann Visitor", "newVisitor", true));
        assertThat(engine.handle(same)).isEmpty();
        assertThat(runs.findTop50ByAppClientIdAndDefinitionIdOrderByCreatedAtDesc(A, on.getId())).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM follow_up WHERE client_id = ?", Long.class, A)).isEqualTo(followUpsBefore + 1);
        // a returning visitor (not new) → SKIPPED, no second follow-up
        visitors.register(A, "pat", new Registration("INDIVIDUAL", null, List.of(p("Ann", "Visitor")), svcA.getId(), LocalDate.of(2026, 11, 8), "INTERESTED", false, null, null, null, null, null));
        aRuns = runs.findTop50ByAppClientIdAndDefinitionIdOrderByCreatedAtDesc(A, on.getId());
        assertThat(aRuns).hasSize(2);
        assertThat(aRuns).extracting(WorkflowRun::getStatus).containsExactlyInAnyOrder("DONE", "SKIPPED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM follow_up WHERE client_id = ? AND created_by LIKE 'automation:%'", Long.class, A)).isEqualTo(1L);
        on.setEnabled(false); defs.save(on); bRule.setEnabled(false); defs.save(bRule);
    }

    @Test @Order(2) @DisplayName("owner permission revoked → DENIED and nothing written; restored → DONE; a gift event through IncomeService runs the gift rule for A only")
    void permissionsAndGift() {
        WorkflowDefinition gift = rule(A, adminA, "Large gift", "GIFT_RECORDED", "[{\"field\":\"amount\",\"op\":\"gte\",\"value\":\"1000\"}]",
                "[{\"type\":\"create_follow_up\",\"params\":{\"title\":\"Thank {{name}} ({{fund}})\",\"assignTo\":\"owner\",\"dueInDays\":\"3\"}},{\"type\":\"email_staff\",\"params\":{\"to\":\"owner\",\"subject\":\"Gift {{amount}}\",\"body\":\"{{name}} gave {{amount}} to {{fund}}\"}}]", true);
        // revoke the owner's Follow-ups page
        UserPermissions up = new UserPermissions(); up.setAppUserId(adminA.getId()); up.setClientId(A); up.setPermissions("{\"more.followups\":false}"); up = permRepo.save(up);
        incomes.createIncome(sarahA, fundA, LocalDate.of(2026, 11, 2), typeA, "R-1", new BigDecimal("1500.00"), null, null, false, A, "pat", null, false);
        List<WorkflowRun> r = runs.findTop50ByAppClientIdAndDefinitionIdOrderByCreatedAtDesc(A, gift.getId());
        assertThat(r).hasSize(1);
        assertThat(r.get(0).getStatus()).isEqualTo("DENIED");
        assertThat(r.get(0).getLogJson()).contains("Follow-ups page");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM follow_up WHERE client_id = ? AND title LIKE 'Thank %'", Long.class, A)).isZero();
        // restore → the next gift runs; a small gift is SKIPPED
        permRepo.delete(up);
        incomes.createIncome(sarahA, fundA, LocalDate.of(2026, 11, 3), typeA, "R-2", new BigDecimal("2500.00"), null, null, false, A, "pat", null, false);
        incomes.createIncome(sarahA, fundA, LocalDate.of(2026, 11, 3), typeA, "R-3", new BigDecimal("20.00"), null, null, false, A, "pat", null, false);
        r = runs.findTop50ByAppClientIdAndDefinitionIdOrderByCreatedAtDesc(A, gift.getId());
        assertThat(r).hasSize(3);
        assertThat(r).extracting(WorkflowRun::getStatus).containsExactlyInAnyOrder("DENIED", "DONE", "SKIPPED");
        List<Map<String, Object>> fus = jdbc.queryForList("SELECT title, linked_type, linked_id, linked_label FROM follow_up WHERE client_id = ? AND title LIKE 'Thank %'", A);
        assertThat(fus).hasSize(1);
        assertThat(fus.get(0).get("title")).isEqualTo("Thank Sarah Lee (Missions)");
        assertThat(fus.get(0).get("linked_type")).isEqualTo("MEMBER");
        assertThat(((Number) fus.get(0).get("linked_id")).intValue()).isEqualTo(sarahA);
        WorkflowRun done = r.stream().filter(x -> "DONE".equals(x.getStatus())).findFirst().get();
        assertThat(done.getLogJson()).contains("Created follow-up").contains("E-mailed Pat");   // mail not configured in the IT → EmailService swallows, the log line still records the attempt
        assertThat(runs.findTop50ByAppClientIdOrderByCreatedAtDesc(B)).noneMatch(x -> x.getDefinitionId().equals(gift.getId()));
        gift.setEnabled(false); defs.save(gift);
    }

    @Test @Order(3) @DisplayName("the ten templates: every one validates, dry-runs, and runs on real visitor + gift events as DONE or SKIPPED — never FAILED or DENIED")
    void templates() {
        List<WorkflowDefinition> created = new java.util.ArrayList<>();
        for (WorkflowTemplates.Template t : WorkflowTemplates.ALL) {
            WorkflowSchema.Rule rule = WorkflowSchema.validate(t.rule());
            WorkflowDefinition d = new WorkflowDefinition();
            d.setAppClientId(A); d.setName("T " + t.key()); d.setTrigger(rule.trigger()); d.setTemplateKey(t.key());
            try { d.setConditionsJson(M.writeValueAsString(rule.conditions().stream().map(WorkflowSchema.Condition::toJson).toList())); d.setActionsJson(M.writeValueAsString(rule.actions().stream().map(WorkflowSchema.Action::toJson).toList())); }
            catch (Exception e) { throw new RuntimeException(e); }
            d.setEnabled(true); d.setOwnerUserId(adminA.getId()); d.setOwnerUsername("pat-" + A); d.setCreatedBy("it");
            created.add(defs.save(d));
            Map<String, Object> dry = engine.dryRun(d, WorkflowController.sampleFacts(rule.trigger()));
            assertThat(dry.get("status")).as(t.key() + " dry run: " + dry).isIn("DRY", "SKIPPED");
        }
        visitors.register(A, "pat", new Registration("FAMILY", null, List.of(p("Tom", "Family"), p("Tia", "Family")), svcA.getId(), LocalDate.of(2026, 11, 4), "INTERESTED", true, "Wants a visit", null, null, null, null));
        incomes.createIncome(sarahA, fundA, LocalDate.of(2026, 11, 4), typeA, "R-9", new BigDecimal("1000.00"), null, null, false, A, "pat", null, false);
        for (WorkflowDefinition d : created) {
            List<WorkflowRun> r = runs.findTop50ByAppClientIdAndDefinitionIdOrderByCreatedAtDesc(A, d.getId());
            if (d.getTrigger().equals("VISITOR_RECORDED") || d.getTrigger().equals("GIFT_RECORDED")) {
                assertThat(r).as(d.getName()).isNotEmpty();
                assertThat(r).as(d.getName() + " " + r.get(0).getLogJson()).allMatch(x -> "DONE".equals(x.getStatus()) || "SKIPPED".equals(x.getStatus()));
            } else assertThat(r).as(d.getName() + " waits for its own trigger").isEmpty();
            d.setEnabled(false); defs.save(d);
        }
        // the visiting family: first-time (DONE), interested (DONE), third visit (SKIPPED), family (DONE, e-mail logged)
        Map<String, String> byKey = new java.util.HashMap<>();
        for (WorkflowDefinition d : created) { List<WorkflowRun> r = runs.findTop50ByAppClientIdAndDefinitionIdOrderByCreatedAtDesc(A, d.getId()); if (!r.isEmpty()) byKey.put(d.getTemplateKey(), r.get(0).getStatus()); }
        assertThat(byKey).containsEntry("first_time_visitor", "DONE").containsEntry("interested_visitor", "DONE").containsEntry("third_visit", "SKIPPED").containsEntry("family_visit", "DONE").containsEntry("large_gift", "DONE");
    }

    @Test @Order(4) @DisplayName("run history is per church; retention purges only aged runs; migration block runs twice")
    void historyRetentionMigration() throws Exception {
        assertThat(runs.findTop50ByAppClientIdOrderByCreatedAtDesc(A)).isNotEmpty();
        assertThat(runs.findTop50ByAppClientIdOrderByCreatedAtDesc(A)).allMatch(r -> A.equals(r.getAppClientId()));
        assertThat(runs.findTop50ByAppClientIdOrderByCreatedAtDesc(B)).isEmpty();
        WorkflowDefinition d = defs.findByAppClientIdAndDeleteFlagFalseOrderByNameAsc(A).get(0);
        WorkflowRun old = new WorkflowRun(); old.setAppClientId(A); old.setDefinitionId(d.getId()); old.setTrigger("VISITOR_RECORDED"); old.setEventKey("old-" + System.nanoTime()); old.setStatus("DONE"); old.setLogJson("[]"); old.setCreatedAt(LocalDateTime.now().minusDays(181)); runs.save(old);
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_run WHERE app_client_id = ?", Long.class, A);
        Map<String, Integer> purged = retention.purgeNow();
        assertThat(purged.getOrDefault("workflow_run", 0)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workflow_run WHERE app_client_id = ?", Long.class, A)).isEqualTo(before - 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workflow_run WHERE id = ?", Long.class, old.getId())).isZero();
        String sql = Files.readString(Path.of("scripts/sql/migrate_production.sql"));
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS workflow_definition");
        assertThat(start).isPositive();
        String block = sql.substring(start);
        jdbc.execute(block); jdbc.execute(block);
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name = 'workflow_run'", String.class)).contains("event_key", "definition_id", "status", "log_json");
        // the unique (definition_id, event_key) index refuses a duplicate run row
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("INSERT INTO workflow_run (app_client_id, definition_id, trigger_type, event_key, status, created_at) SELECT app_client_id, definition_id, trigger_type, event_key, status, created_at FROM workflow_run WHERE app_client_id = ? LIMIT 1", A))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @AfterAll
    void cleanup() {
        for (String cid : List.of(A, B)) {
            jdbc.update("DELETE FROM workflow_run WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM workflow_definition WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM follow_up WHERE client_id = ?", cid);
            jdbc.update("DELETE FROM note WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM income WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM sub_source WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM main_source WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM transaction_type WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM attendance_record WHERE client_id = ?", cid);
            jdbc.update("DELETE FROM visitor_party WHERE client_id = ?", cid);
            jdbc.update("DELETE FROM attendance_visitor WHERE client_id = ?", cid);
            jdbc.update("DELETE FROM attendance_service_type WHERE client_id = ?", cid);
            jdbc.update("DELETE FROM user_permissions WHERE client_id = ?", cid);
            jdbc.update("DELETE FROM family_member WHERE family_id IN (SELECT id FROM family WHERE app_client_id = ?)", cid);
            jdbc.update("DELETE FROM family WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM app_user WHERE client_id = ?", cid);
        }
    }
}
