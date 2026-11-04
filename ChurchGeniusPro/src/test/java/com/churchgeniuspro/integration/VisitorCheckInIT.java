package com.churchgeniuspro.integration;

import com.churchgeniuspro.controller.AttendanceController;
import com.churchgeniuspro.controller.FollowUpController;
import com.churchgeniuspro.hibernate.AttendanceRecord;
import com.churchgeniuspro.hibernate.AttendanceServiceType;
import com.churchgeniuspro.service.AttendanceService;
import com.churchgeniuspro.service.TestDataService;
import com.churchgeniuspro.service.VisitorCheckInService;
import com.churchgeniuspro.service.VisitorCheckInService.CheckInException;
import com.churchgeniuspro.service.VisitorCheckInService.Person;
import com.churchgeniuspro.service.VisitorCheckInService.Registration;
import com.churchgeniuspro.util.AppClock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Visitor check-in and attendance enhancements (2026-11-04) on PostgreSQL, two churches. */
@EnabledIf("com.churchgeniuspro.integration.VisitorCheckInIT#databaseAvailable")
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = { "app.base-url=http://localhost" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Visitor check-in — PostgreSQL")
class VisitorCheckInIT {

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
        POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
        POSTGRES.start();
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired TestDataService testData;
    @Autowired VisitorCheckInService visitors;
    @Autowired AttendanceService attendance;
    @Autowired AttendanceController controller;
    @Autowired FollowUpController followUps;
    @Autowired JdbcTemplate jdbc;

    String a, b;          // two churches
    Integer memberA;      // a member of church A
    LocalDate today;

    @BeforeAll
    void churches() {
        long n = System.currentTimeMillis();
        a = TestDataService.TRIAL_CLIENT_PREFIX + n;
        b = TestDataService.TRIAL_CLIENT_PREFIX + (n + 1);
        today = AppClock.today();
        for (String c : List.of(a, b)) {
            testData.provisionTenant(new TestDataService.TenantSpec(c, "Visitor Test " + c, List.of("SuperAdmin"), 1,
                    TestDataService.TenantSpec.Contact.of("Pat", "Trial", "pat+" + c + "@visit.test", "913-555-0100")), null, today.plusDays(30));
            attendance.serviceTypes(c); // seed defaults
        }
        memberA = jdbc.queryForObject("SELECT min(fm.id) FROM family_member fm JOIN family f ON f.id = fm.family_id " +
                "WHERE f.app_client_id = ? AND fm.delete_flag = false", Integer.class, a);
    }

    private static Person p(String first, String last, String email, String phone) {
        return new Person(first, last, null, null, phone, email, null, null, null, null, null);
    }

    private AttendanceServiceType service(String church, String name) {
        try { return visitors.createTarget(church, "service", name, null, false); }
        catch (CheckInException e) { return visitors.target(church, ((Number) ((Map<?, ?>) e.extra.get("existing")).get("id")).longValue()); }
    }

    private static List<Object> col(Map<String, Object> report, String key) {
        return ((List<?>) report.get("rows")).stream().map(o -> (Object) ((Map<?, ?>) o).get(key)).toList();
    }

    private MockHttpServletRequest staff(String church) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.getSession(true).setAttribute("username", "pat@visit.test");
        req.getSession().setAttribute("role", "SuperAdmin");
        req.getSession().setAttribute("appClientId", church);
        return req;
    }

    @Test @DisplayName("Services unique ignoring case/spaces; events by name + date; suggestions; tenant scoped")
    void targetsRules() {
        AttendanceServiceType s = visitors.createTarget(a, "service", "Youth  Night", null, false);
        assertThatThrownBy(() -> visitors.createTarget(a, "service", "youthnight", null, false))
                .isInstanceOf(CheckInException.class).extracting("code").isEqualTo("SERVICE_EXISTS");
        // seeded default "Sunday Worship" also blocks a look-alike
        assertThatThrownBy(() -> visitors.createTarget(a, "service", " SUNDAY worship ", null, false))
                .extracting("code").isEqualTo("SERVICE_EXISTS");
        // the other church may use the same name
        assertThat(visitors.createTarget(b, "service", "Youth Night", null, false).getId()).isNotEqualTo(s.getId());

        AttendanceServiceType e1 = visitors.createTarget(a, "event", "Fall Fest", today.plusDays(10), false);
        assertThatThrownBy(() -> visitors.createTarget(a, "event", "fall fest", today.plusDays(10), false))
                .extracting("code").isEqualTo("EVENT_EXISTS");
        CheckInException sugg = org.assertj.core.api.Assertions.catchThrowableOfType(CheckInException.class,
                () -> visitors.createTarget(a, "event", "FallFest", today.plusDays(40), false));
        assertThat(sugg.code).isEqualTo("EVENT_NAME_EXISTS");
        assertThat((List<?>) sugg.extra.get("suggestions")).hasSize(1);
        AttendanceServiceType e2 = visitors.createTarget(a, "event", "FallFest", today.plusDays(40), true);
        assertThat(e2.getId()).isNotEqualTo(e1.getId());
        visitors.createTarget(a, "event", "Easter Picnic", today.minusDays(5), false);
        visitors.createTarget(a, "event", "Prayer Breakfast", today, false);
        assertThatThrownBy(() -> visitors.createTarget(a, "event", "No date", null, false)).extracting("code").isEqualTo("DATE_REQUIRED");

        List<AttendanceServiceType> all = attendance.serviceTypes(a);
        assertThat(visitors.targets(a, "services", null, all)).extracting(m -> m.get("kind")).containsOnly("service");
        assertThat(visitors.targets(a, "today", null, all)).extracting(m -> m.get("name")).contains("Prayer Breakfast").doesNotContain("Fall Fest");
        assertThat(visitors.targets(a, "future", null, all)).extracting(m -> m.get("name")).contains("Fall Fest", "FallFest");
        assertThat(visitors.targets(a, "past", null, all)).extracting(m -> m.get("name")).contains("Easter Picnic");
        assertThat(visitors.targets(a, "all", "fall", all)).hasSize(2);
        // church B's own list never shows church A's rows, and A's id is refused for B
        assertThat(visitors.targets(b, "all", "fall", attendance.serviceTypes(b))).isEmpty();
        assertThatThrownBy(() -> visitors.target(b, e1.getId())).extracting("code").isEqualTo("TARGET_NOT_FOUND");
    }

    @Test @DisplayName("Individual: match by e-mail, no duplicate record, past date saved checked out")
    void individual() {
        AttendanceServiceType svc = service(a, "Individual Svc");
        String email = "ind" + System.nanoTime() + "@visit.test";
        Map<String, Object> r1 = visitors.register(a, "pat", new Registration("INDIVIDUAL", null,
                List.of(p("Ann", "Lee", email, "913-555-0101")), svc.getId(), today, "INTERESTED", false, null, null, null, null, null));
        Map<?, ?> v1 = (Map<?, ?>) ((List<?>) r1.get("visitors")).get(0);
        assertThat(v1.get("matchedExisting")).isEqualTo(false);
        assertThat(r1.get("checkedOut")).isEqualTo(false);
        Long id = ((Number) v1.get("visitorId")).longValue();

        // same person, same date + service → no second record
        Map<String, Object> again = visitors.register(a, "pat", new Registration("INDIVIDUAL", null,
                List.of(p("ann", "Lee", email.toUpperCase(), null)), svc.getId(), today, "NOT_SURE", false, null, null, null, null, null));
        Map<?, ?> v2 = (Map<?, ?>) ((List<?>) again.get("visitors")).get(0);
        assertThat(v2.get("visitorId")).isEqualTo(id);
        assertThat(v2.get("alreadyCheckedIn")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_record WHERE visitor_id = ? AND delete_flag = false", Long.class, id)).isEqualTo(1);

        // last week, matched by phone + first name → second visit, closed record
        Map<String, Object> past = visitors.register(a, "pat", new Registration("INDIVIDUAL", null,
                List.of(p("Ann", "Lee", null, "(913) 555-0101")), svc.getId(), today.minusDays(7), null, false, null, null, null, null, null));
        assertThat(past.get("checkedOut")).isEqualTo(true);
        assertThat(((Map<?, ?>) ((List<?>) past.get("visitors")).get(0)).get("visitorId")).isEqualTo(id);
        Map<String, Object> row = jdbc.queryForMap("SELECT visit_count, first_visit_date, last_visit_date, interest_level FROM attendance_visitor WHERE id = ?", id);
        assertThat(((Number) row.get("visit_count")).intValue()).isEqualTo(2);
        assertThat(row.get("first_visit_date").toString()).isEqualTo(today.minusDays(7).toString());
        assertThat(row.get("last_visit_date").toString()).isEqualTo(today.toString());
        // the visit from last week is older, so the visitor keeps today's answer (D6)
        assertThat(row.get("interest_level")).isEqualTo("NOT_SURE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_record WHERE visitor_id = ? AND attendance_date = ? AND check_out_time IS NOT NULL",
                Long.class, id, java.sql.Date.valueOf(today.minusDays(7)))).isEqualTo(1);

        // a different first name on the same phone is a different person
        Map<String, Object> other = visitors.register(a, "pat", new Registration("INDIVIDUAL", null,
                List.of(p("Bob", "Lee", null, "913-555-0101")), svc.getId(), today, null, false, null, null, null, null, null));
        assertThat(((Map<?, ?>) ((List<?>) other.get("visitors")).get(0)).get("matchedExisting")).isEqualTo(false);
        // another church never matches church A's visitor
        Map<String, Object> inB = visitors.register(b, "pat", new Registration("INDIVIDUAL", null,
                List.of(p("Ann", "Lee", email, null)), service(b, "Individual Svc").getId(), today, null, false, null, null, null, null, null));
        assertThat(((Map<?, ?>) ((List<?>) inB.get("visitors")).get(0)).get("visitorId")).isNotEqualTo(id);
        // church A's service id is refused for church B
        assertThatThrownBy(() -> visitors.register(b, "pat", new Registration("INDIVIDUAL", null,
                List.of(p("X", "Y", null, null)), svc.getId(), today, null, false, null, null, null, null, null)))
                .extracting("code").isEqualTo("TARGET_NOT_FOUND");
    }

    @Test @DisplayName("Family: shared e-mail keeps people apart, one party, one follow-up task appended on a repeat visit")
    void familyAndFollowUp() {
        AttendanceServiceType svc = service(a, "Family Svc");
        String email = "fam" + System.nanoTime() + "@visit.test";
        List<Person> people = List.of(
                new Person("Sam", "Park", "M", null, "816-555-0199", email, "1 Main St", null, "Olathe", "KS", "66061"),
                new Person("Jo", "Park", "F", "SPOUSE", null, email, "1 Main St", null, "Olathe", "KS", "66061"),
                new Person("Kim", "Park", null, "CHILD", null, email, "1 Main St", null, "Olathe", "KS", "66061"));
        Map<String, Object> r = visitors.register(a, "pat", new Registration("FAMILY", null, people, svc.getId(), today,
                "INTERESTED", true, "Call about kids ministry", "Pastor Tim", null, null, null));
        List<?> vs = (List<?>) r.get("visitors");
        assertThat(vs).hasSize(3);
        assertThat(vs.stream().map(v -> ((Map<?, ?>) v).get("visitorId")).distinct()).hasSize(3);
        Long party = ((Number) r.get("partyId")).longValue();
        Long fu = ((Number) r.get("followUpId")).longValue();
        Long primary = ((Number) ((Map<?, ?>) vs.get(0)).get("visitorId")).longValue();
        assertThat(jdbc.queryForList("SELECT relationship FROM attendance_visitor WHERE party_id = ? ORDER BY id", String.class, party))
                .containsExactly("PRIMARY", "SPOUSE", "CHILD");
        Map<String, Object> task = jdbc.queryForMap("SELECT status, assigned_to, linked_type, linked_id, linked_label, description FROM follow_up WHERE id = ?", fu);
        assertThat(task.get("status")).isEqualTo("PENDING");
        assertThat(task.get("assigned_to")).isNull();
        assertThat(task.get("linked_type")).isEqualTo("VISITOR");
        assertThat(((Number) task.get("linked_id")).longValue()).isEqualTo(primary);
        assertThat(task.get("linked_label")).isEqualTo("Park family");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_record WHERE party_id = ? AND follow_up_id = ?", Long.class, party, fu)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT address FROM attendance_visitor WHERE id = ?", String.class, primary)).isEqualTo("1 Main St, Olathe, KS 66061");

        // next week the same family returns: matched, same party, the same task gets the visit appended
        Map<String, Object> again = visitors.register(a, "pat", new Registration("FAMILY", null, people, svc.getId(), today.plusDays(7),
                "INTERESTED", true, "Still keen", null, null, null, null));
        assertThat(again.get("partyId")).isEqualTo(r.get("partyId"));
        assertThat(again.get("followUpId")).isEqualTo(r.get("followUpId"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM follow_up WHERE linked_type = 'VISITOR' AND linked_id = ? AND delete_flag = false", Long.class, primary)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT description FROM follow_up WHERE id = ?", String.class, fu)).contains("Still keen").contains("Call about kids ministry");
        assertThat(again.get("checkedOut")).isEqualTo(true); // future date

        // Follow-ups → Visitors tab: admins see it with contact details; church B sees nothing
        ResponseEntity<?> list = followUps.visitorFollowUps("OPEN", staff(a));
        assertThat((List<?>) list.getBody()).anySatisfy(o -> {
            Map<?, ?> m = (Map<?, ?>) o;
            assertThat(m.get("id")).isEqualTo(fu);
            assertThat(m.get("visitorName")).isEqualTo("Sam Park");
            assertThat(m.get("visitorEmail")).isEqualTo(email);
        });
        assertThat((List<?>) followUps.visitorFollowUps("OPEN", staff(b)).getBody())
                .noneSatisfy(o -> assertThat(((Map<?, ?>) o).get("id")).isEqualTo(fu));

        // edit + delete: soft delete keeps history; primary moves to the next person
        Map<String, Object> edited = visitors.updateVisitor(a, primary, new Person("Samuel", "Park", "M", "PRIMARY",
                "816-555-0199", email, "2 Oak Ave", null, "Olathe", "KS", "66062"), "NOT_SURE", false, "moved");
        assertThat(edited.get("name")).isEqualTo("Samuel Park");
        assertThat(edited.get("interestLevel")).isEqualTo("NOT_SURE");
        visitors.deleteVisitor(a, primary);
        assertThat(jdbc.queryForObject("SELECT delete_flag FROM attendance_visitor WHERE id = ?", Boolean.class, primary)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_record WHERE visitor_id = ? AND delete_flag = false", Long.class, primary)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT primary_visitor_id FROM visitor_party WHERE id = ?", Long.class, party)).isNotEqualTo(primary);
        assertThatThrownBy(() -> visitors.getVisitor(b, ((Number) ((Map<?, ?>) vs.get(1)).get("visitorId")).longValue()))
                .extracting("code").isEqualTo("VISITOR_NOT_FOUND");
    }

    @Test @DisplayName("Member check-in for another date: typed lookup, no duplicate, closed record, linked to the service")
    void memberWithDate() {
        AttendanceServiceType svc = service(a, "Member Date Svc");
        LocalDate d = today.minusDays(3);
        AttendanceRecord r1 = attendance.checkInMember(a, "pat", memberA, "Member", svc.getName(), svc.getId(), d, null, null, null, "manual");
        AttendanceRecord r2 = attendance.checkInMember(a, "pat", memberA, "Member", svc.getName(), svc.getId(), d, null, null, "LATE", "manual");
        assertThat(r2.getId()).isEqualTo(r1.getId());
        assertThat(r2.getCheckOutTime()).isNotNull();
        assertThat(r2.getServiceTypeId()).isEqualTo(svc.getId());
        AttendanceRecord now = attendance.checkInMember(a, "pat", memberA, "Member", svc.getName(), "", "", null, "manual");
        assertThat(now.getAttendanceDate()).isEqualTo(today);
        assertThat(now.getCheckOutTime()).isNull();
        // via the controller with an id instead of a name
        Map<String, Object> body = new java.util.HashMap<>(Map.of("memberId", memberA, "serviceTypeId", svc.getId(), "date", today.plusDays(2).toString()));
        ResponseEntity<?> res = controller.checkIn(body, staff(a));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(((Map<?, ?>) res.getBody()).get("serviceType")).isEqualTo(svc.getName());
        body.put("date", "not-a-date");
        assertThat(controller.checkIn(body, staff(a)).getStatusCode().value()).isEqualTo(400);
        body.put("date", today.plusYears(5).toString());
        assertThat(controller.checkIn(body, staff(a)).getStatusCode().value()).isEqualTo(400);
        // church A's service id from church B → 404
        assertThat(controller.checkIn(new java.util.HashMap<>(Map.of("memberId", memberA, "serviceTypeId", svc.getId())), staff(b))
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test @DisplayName("Five reports: counts, target filter, church scoped")
    void reports() {
        AttendanceServiceType svc = service(a, "Report Svc");
        AttendanceServiceType ev = visitors.createTarget(a, "event", "Report Gala " + System.nanoTime(), today.minusDays(1), false);
        String tag = String.valueOf(System.nanoTime());
        visitors.register(a, "pat", new Registration("GROUP", "Study friends", List.of(
                p("Gail", "One" + tag, "g1" + tag + "@visit.test", null), p("Hank", "Two" + tag, "g2" + tag + "@visit.test", null)),
                svc.getId(), today, "INTERESTED", true, "group call", null, null, null, null));
        visitors.register(a, "pat", new Registration("INDIVIDUAL", null, List.of(p("Ivy", "Three" + tag, "i" + tag + "@visit.test", null)),
                ev.getId(), today.minusDays(1), "NOT_INTERESTED", false, null, null, null, null, null));
        // church B activity must never appear
        visitors.register(b, "pat", new Registration("INDIVIDUAL", null, List.of(p("Zed", "Other" + tag, null, null)),
                service(b, "Report Svc").getId(), today, null, true, null, null, null, null, null));

        Map<String, Object> att = visitors.attendeeReport(a, today.minusDays(2), today, svc.getId());
        assertThat(col(att, "name")).contains("Gail One" + tag, "Hank Two" + tag)
                .doesNotContain("Ivy Three" + tag, "Zed Other" + tag);
        Map<String, Object> vis = visitors.visitorListReport(a, today.minusDays(2), today, null);
        assertThat(col(vis, "name")).contains("Ivy Three" + tag).doesNotContain("Zed Other" + tag);
        Map<String, Object> interest = visitors.interestReport(a, today.minusDays(2), today, ev.getId());
        assertThat(((Map<?, ?>) interest.get("counts")).get("NOT_INTERESTED")).isEqualTo(1);
        Map<String, Object> fu = visitors.followUpReport(a, today.minusDays(2), today, svc.getId());
        assertThat(col(fu, "taskStatus")).containsOnly("PENDING");
        assertThat((List<?>) fu.get("rows")).hasSize(2);
        Map<String, Object> parties = visitors.partyReport(a, today.minusDays(2), today, null);
        assertThat((List<?>) parties.get("rows")).anySatisfy(o -> {
            Map<?, ?> m = (Map<?, ?>) o;
            assertThat(m.get("name")).isEqualTo("Study friends");
            assertThat(m.get("kind")).isEqualTo("GROUP");
            assertThat((List<?>) m.get("people")).hasSize(2);
        });
        assertThatThrownBy(() -> visitors.attendeeReport(a, today, today.minusDays(1), null)).extracting("code").isEqualTo("BAD_RANGE");
        assertThatThrownBy(() -> visitors.attendeeReport(b, null, null, svc.getId())).extracting("code").isEqualTo("TARGET_NOT_FOUND");
    }

    @Test @DisplayName("Validation: names required, bad e-mail, interest, too many people")
    void validation() {
        Long svc = service(a, "Validation Svc").getId();
        assertThatThrownBy(() -> visitors.register(a, "pat", new Registration("INDIVIDUAL", null, List.of(p("", "X", null, null)), svc, today,
                null, false, null, null, null, null, null))).extracting("code").isEqualTo("FIRST_NAME_REQUIRED");
        assertThatThrownBy(() -> visitors.register(a, "pat", new Registration("INDIVIDUAL", null, List.of(p("A", "B", "nope", null)), svc, today,
                null, false, null, null, null, null, null))).extracting("code").isEqualTo("BAD_EMAIL");
        assertThatThrownBy(() -> visitors.register(a, "pat", new Registration("INDIVIDUAL", null, List.of(p("A", "B", null, null)), svc, today,
                "MAYBE", false, null, null, null, null, null))).extracting("code").isEqualTo("BAD_INTEREST");
        assertThatThrownBy(() -> visitors.register(a, "pat", new Registration("INDIVIDUAL", null, List.of(p("A", "B", null, null), p("C", "D", null, null)),
                svc, today, null, false, null, null, null, null, null))).extracting("code").isEqualTo("INDIVIDUAL_ONE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_visitor WHERE client_id = ? AND last_name IN ('B','D','X')", Long.class, a)).isZero();
    }

    @Test @DisplayName("migrate_production.sql attendance block runs twice")
    void migrationBlockTwice() throws Exception {
        String sql = Files.readString(Path.of("scripts/sql/migrate_production.sql"));
        String block = sql.substring(sql.indexOf("-- ── Attendance: visitor check-in enhancements (2026-11-04)"));
        block = block.substring(block.indexOf("DO $$"));
        jdbc.execute(block);
        jdbc.execute(block);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname IN " +
                "('idx_att_visitor_client_party','idx_att_record_client_visitor','idx_follow_up_client_linked','idx_visitor_party_client')", Long.class)).isEqualTo(4);
    }
}
