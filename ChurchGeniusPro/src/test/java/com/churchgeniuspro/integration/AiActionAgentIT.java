package com.churchgeniuspro.integration;

import com.churchgeniuspro.ai.action.AiActionService;
import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI Phase 4 on PostgreSQL with two churches: every tool resolves names inside the caller's church only, a
 * confirmed proposal writes exactly one row through the real services, cross-tenant ids in stored params are
 * refused at confirm time, the duplicate rules hold, and the migration block runs twice.
 */
@EnabledIf("com.churchgeniuspro.integration.AiActionAgentIT#databaseAvailable")
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = { "app.base-url=http://localhost", "ai.actions.llm-fallback=false" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("AI Phase 4 — action agent on PostgreSQL (two churches)")
class AiActionAgentIT {

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

    static final String A = "CGP-ACT-ITA-" + System.currentTimeMillis(), B = A.replace("ITA", "ITB");

    @Autowired AiActionService svc;
    @Autowired AiActionProposalRepository proposals;
    @Autowired JdbcTemplate jdbc;
    @Autowired FamilyRepository familyRepo; @Autowired FamilyMemberRepository memberRepo; @Autowired GroupRepository groupRepo; @Autowired GroupMemberRepository groupMemberRepo;
    @Autowired VolunteerRoleRepository roleRepo; @Autowired FollowUpRepository followUpRepo; @Autowired MeetingTypeRepository typeRepo;

    Integer sarahA, sarahB, familyA; Integer welcomeA, welcomeB; Long usherA; Long taskA; Integer deaconsA;

    @BeforeAll
    void seed() {
        for (String cid : List.of(A, B)) {
            Family f = new Family(); f.setAppClientId(cid); f = familyRepo.save(f);
            FamilyMember sam = new FamilyMember(); sam.setFamily(f); sam.setFirstName("Sam"); sam.setLastName("Smith"); sam.setRole("Head of Household"); sam = memberRepo.save(sam);
            FamilyMember sarah = new FamilyMember(); sarah.setFamily(f); sarah.setFirstName("Sarah"); sarah.setLastName("Lee"); sarah.setRole("Adult"); sarah.setEmail("sarah" + cid + "@x.test"); sarah = memberRepo.save(sarah);
            Group g = new Group(); g.setAppClientId(cid); g.setGroupName("Welcome Team"); g = groupRepo.save(g);
            VolunteerRole role = new VolunteerRole(); role.setAppClientId(cid); role.setRoleName("Usher"); role = roleRepo.save(role);
            FollowUp fu = new FollowUp(); fu.setClientId(cid); fu.setTitle("Visit the Lees"); fu = followUpRepo.save(fu);
            MeetingType mt = new MeetingType(); mt.setAppClientId(cid); mt.setTypeName("Deacons"); mt = typeRepo.save(mt);
            if (cid.equals(A)) { sarahA = sarah.getId(); familyA = f.getId(); welcomeA = g.getId(); usherA = role.getId(); taskA = fu.getId(); deaconsA = mt.getId(); }
            else { sarahB = sarah.getId(); welcomeB = g.getId(); }
        }
        // Church B also has a "John Moe" — A has none
        Family fb = familyRepo.findAll().stream().filter(x -> B.equals(x.getAppClientId())).findFirst().get();
        FamilyMember john = new FamilyMember(); john.setFamily(fb); john.setFirstName("John"); john.setLastName("Moe"); john.setRole("Adult"); memberRepo.save(john);
    }

    MockHttpServletRequest admin(String cid) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.getSession(true).setAttribute("username", "pat-" + cid); r.getSession().setAttribute("role", "Admin"); r.getSession().setAttribute("appClientId", cid);
        return r;
    }
    MockHttpServletRequest same(MockHttpServletRequest r) { MockHttpServletRequest n = new MockHttpServletRequest(); n.setSession(r.getSession()); return n; }
    @SuppressWarnings("unchecked") String token(Map<String, Object> m) { return (String) ((Map<String, Object>) m.get("confirm")).get("token"); }

    @Test @DisplayName("add to group: names resolve inside the church, one row written on confirm, duplicate refused, B's John unknown to A")
    void group() {
        MockHttpServletRequest r = admin(A);
        Map<String, Object> m = svc.handle(r, A, "Add Sarah Lee to Welcome Team");
        assertThat(m.get("intent")).isEqualTo("action.confirm");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM group_member WHERE app_client_id = ?", Long.class, A)).isZero();
        Map<String, Object> done = svc.confirm(same(r), A, token(m));
        assertThat(done.get("intent")).isEqualTo("action.done");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT group_id, family_member_id, email FROM group_member WHERE app_client_id = ?", A);
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0).get("group_id")).intValue()).isEqualTo(welcomeA);
        assertThat(((Number) rows.get(0).get("family_member_id")).intValue()).isEqualTo(sarahA);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM group_member WHERE app_client_id = ?", Long.class, B)).as("B untouched").isZero();
        // duplicate refused
        assertThat(svc.handle(admin(A), A, "Add Sarah Lee to Welcome Team").get("intent")).isEqualTo("action.refused");
        // a name only church B has
        Map<String, Object> unknown = svc.handle(admin(A), A, "Add John Moe to Welcome Team");
        assertThat(unknown.get("intent")).isEqualTo("action.ask");
        assertThat(String.valueOf(unknown.get("answer"))).contains("couldn't find a member");
        // the proposal row is tenant-scoped
        AiActionProposal p = proposals.findAll().stream().filter(x -> A.equals(x.getAppClientId()) && "EXECUTED".equals(x.getStatus())).findFirst().get();
        assertThat(p.getToken()).isNull(); assertThat(p.getParamsJson()).contains("\"memberId\":" + sarahA).doesNotContain("@x.test");
    }

    @Test @DisplayName("cross-tenant ids in stored params are refused at confirm: a proposal of A pointing at B's member / group writes nothing")
    void crossTenant() {
        MockHttpServletRequest r = admin(A);
        Map<String, Object> m = svc.handle(r, A, "Add Sam Smith to Welcome Team");
        String t = token(m);
        AiActionProposal p = proposals.findByTokenAndAppClientIdAndStatus(t, A, "PROPOSED").get();
        // tamper: point the stored params at church B's rows
        p.setParamsJson(p.getParamsJson().replace("\"groupId\":" + welcomeA, "\"groupId\":" + welcomeB).replace("\"memberId\":" + jdbc.queryForObject("SELECT fm.id FROM family_member fm JOIN family f ON f.id = fm.family_id WHERE f.app_client_id = ? AND fm.first_name = 'Sam'", Integer.class, A), "\"memberId\":" + sarahB));
        proposals.save(p);
        Map<String, Object> d = svc.confirm(same(r), A, t);
        assertThat(d.get("intent")).isEqualTo("action.refused");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM group_member WHERE app_client_id = ? OR group_id = ?", Long.class, B, welcomeB)).isZero();
        assertThat(proposals.findById(p.getId()).get().getStatus()).isEqualTo("REJECTED");
        // and a token of A can never be confirmed under B
        Map<String, Object> m2 = svc.handle(admin(A), A, "Add Sam Smith to Welcome Team");
        assertThat(svc.confirm(admin(B), B, token(m2)).get("intent")).isEqualTo("action.expired");
    }

    @Test @DisplayName("follow-up add + complete, family note, volunteer assignment and meeting through the real services, each in its own church")
    void otherTools() {
        LocalDate due = LocalDate.now().plusDays(10);
        MockHttpServletRequest r = admin(A);
        Map<String, Object> m = svc.handle(r, A, "add a follow-up for Sarah Lee: call about baptism, due " + due);
        assertThat(m.get("intent")).isEqualTo("action.confirm");
        assertThat(svc.confirm(same(r), A, token(m)).get("intent")).isEqualTo("action.done");
        Map<String, Object> fu = jdbc.queryForMap("SELECT title, status, linked_type, linked_id, linked_label, client_id FROM follow_up WHERE client_id = ? AND title = 'call about baptism'", A);
        assertThat(fu).containsEntry("status", "PENDING").containsEntry("linked_type", "MEMBER").containsEntry("linked_label", "Sarah Lee");
        assertThat(((Number) fu.get("linked_id")).intValue()).isEqualTo(sarahA);

        MockHttpServletRequest r2 = admin(A);
        Map<String, Object> c = svc.handle(r2, A, "Mark the follow-up for the Lees as done");
        assertThat(c.get("intent")).isEqualTo("action.confirm");
        svc.confirm(same(r2), A, token(c));
        assertThat(jdbc.queryForObject("SELECT status FROM follow_up WHERE id = ?", String.class, taskA)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM follow_up WHERE client_id = ? AND title = 'Visit the Lees'", String.class, B)).as("B's same-titled task untouched").isEqualTo("PENDING");

        MockHttpServletRequest r3 = admin(A);
        Map<String, Object> n = svc.handle(r3, A, "Add a note to the Smiths: new baby arrived");
        assertThat(n.get("intent")).isEqualTo("action.confirm");
        svc.confirm(same(r3), A, token(n));
        assertThat(jdbc.queryForMap("SELECT title, body FROM note WHERE app_client_id = ?", A)).containsEntry("title", "Family note — Sam Smith family").containsEntry("body", "new baby arrived");

        MockHttpServletRequest r4 = admin(A);
        Map<String, Object> v = svc.handle(r4, A, "Assign Sarah Lee as usher on " + due);
        assertThat(v.get("intent")).isEqualTo("action.confirm");
        svc.confirm(same(r4), A, token(v));
        Map<String, Object> va = jdbc.queryForMap("SELECT family_member_id, role_id, assignment_status, event_date FROM volunteer_assignment WHERE app_client_id = ?", A);
        assertThat(((Number) va.get("family_member_id")).intValue()).isEqualTo(sarahA);
        assertThat(((Number) va.get("role_id")).longValue()).isEqualTo(usherA);
        assertThat(va).containsEntry("assignment_status", "pending");

        // create_meeting through the registry (the conversational meeting flow is a separate, unchanged path)
        MockHttpServletRequest r5 = admin(A);
        Map<String, Object> mt = svc.propose(r5, r5.getSession(), new com.churchgeniuspro.service.AiAccessPolicy().resolve(r5, A), A, "create_meeting",
                Map.of("category", "Deacons", "date", due.toString(), "startTime", "19:00"), "TEXT");
        assertThat(mt.get("intent")).isEqualTo("action.confirm");
        assertThat(svc.confirm(same(r5), A, token(mt)).get("intent")).isEqualTo("action.done");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM meeting WHERE app_client_id = ? AND meeting_type_id = ?", Long.class, A, deaconsA)).isEqualTo(1L);
        // the same slot again is refused
        Map<String, Object> dup = svc.propose(admin(A), admin(A).getSession(), new com.churchgeniuspro.service.AiAccessPolicy().resolve(admin(A), A), A, "create_meeting",
                Map.of("category", "Deacons", "date", due.toString(), "startTime", "19:00"), "TEXT");
        assertThat(dup.get("intent")).isEqualTo("action.refused");
    }

    @Test @DisplayName("migration block runs twice; recent list is per church")
    void migration() throws Exception {
        String sql = Files.readString(Path.of("scripts/sql/migrate_production.sql"));
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS ai_action_proposal");
        assertThat(start).isPositive();
        String block = sql.substring(start);
        jdbc.execute(block); jdbc.execute(block);
        assertThat(svc.recent(B, "x", true)).allMatch(row -> true);
        assertThat(svc.recent(B, "x", true)).noneMatch(row -> String.valueOf(row.get("summary")).contains("Sarah Lee") && !String.valueOf(row.get("by")).endsWith(B));
    }

    @AfterAll
    void cleanup() {
        for (String cid : List.of(A, B)) {
            jdbc.update("DELETE FROM ai_action_proposal WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM meeting WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM meeting_type WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM volunteer_assignment WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM volunteer_role WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM note WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM follow_up WHERE client_id = ?", cid);
            jdbc.update("DELETE FROM group_member WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM app_group WHERE app_client_id = ?", cid);
            jdbc.update("DELETE FROM family_member WHERE family_id IN (SELECT id FROM family WHERE app_client_id = ?)", cid);
            jdbc.update("DELETE FROM family WHERE app_client_id = ?", cid);
        }
    }
}
