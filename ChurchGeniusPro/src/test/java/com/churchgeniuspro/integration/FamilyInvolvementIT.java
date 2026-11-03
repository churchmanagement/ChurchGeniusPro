package com.churchgeniuspro.integration;

import com.churchgeniuspro.service.FamilyInvolvementService;
import com.churchgeniuspro.service.FamilyInvolvementService.Access;
import com.churchgeniuspro.service.TestDataService;
import com.churchgeniuspro.util.AppClock;
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

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * View Family involvement against real PostgreSQL (2026-11-02): the four volunteer
 * sources, open follow-ups, groups; member-id links first, safe name / email fallback for
 * older rows; duplicate names, shared family emails, renamed members, past / declined /
 * maybe / deleted rows, another church holding look-alike rows; and a freshly provisioned
 * trial church whose demo data is linked by id (plus the same data with the ids removed,
 * i.e. an existing demo church).
 *
 * <p>Runs on {@code verify} with Docker (Testcontainers), or against {@code CGP_IT_JDBC_URL}.
 */
@EnabledIf("com.churchgeniuspro.integration.FamilyInvolvementIT#databaseAvailable")
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = { "app.base-url=http://localhost" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("View Family involvement — PostgreSQL")
class FamilyInvolvementIT {

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

    static final Access ALL = new Access(true, true, true, true, true, "admin@c", true);

    @Autowired JdbcTemplate jdbc;
    @Autowired FamilyInvolvementService svc;
    @Autowired TestDataService testData;

    final String C = "CGP-INV-C" + System.nanoTime() % 100000, X = "CGP-INV-X" + System.nanoTime() % 100000;
    final AtomicLong seq = new AtomicLong();
    final LocalDate today = AppClock.today();
    int f1, f2, fx, john, mary, kid, grandpa, john2, xMember;

    // ── seed ────────────────────────────────────────────────────────────────

    @BeforeAll
    void seed() {
        f1 = (int) insert("family", cols("app_client_id", C, "delete_flag", false, "inactive", false));
        f2 = (int) insert("family", cols("app_client_id", C, "delete_flag", false, "inactive", false));
        fx = (int) insert("family", cols("app_client_id", X, "delete_flag", false, "inactive", false));
        john    = member(f1, C, "John", "Mathew", "fam@mathew.test", "Head of Household");
        mary    = member(f1, C, "Mary", "Mathew", "fam@mathew.test", "Spouse");          // shares the family email
        kid     = member(f1, C, "Kid", "Mathew", "kid@mathew.test", "Son");
        grandpa = member(f1, C, "Grand", "Pa", null, "Father");
        john2   = member(f2, C, "John", "Mathew", "other.john@x.test", "Head of Household"); // same name, other family
        xMember = member(fx, X, "Mary", "Mathew", "fam@mathew.test", "Head of Household");   // other church, same name + email
        int deletedMember = member(f1, C, "Gone", "Mathew", "gone@x.test", "Son");
        jdbc.update("UPDATE family_member SET delete_flag = true WHERE id = ?", deletedMember);

        int futureEvent = event(C, "Christmas Program", today.plusDays(20));
        int pastEvent   = event(C, "Easter Service", today.minusDays(20));
        int todayEvent  = event(C, "Sunday Worship", today);
        int xEvent      = event(X, "Other Church Event", today.plusDays(5));

        // Volunteers page: registration + scheduled assignments
        long role = insert("volunteer_role", cols("app_client_id", C, "role_name", "Sound Team"));
        long prof = insert("volunteer_profile", cols("app_client_id", C, "family_member_id", john, "status", "Active", "delete_flag", false));
        insert("volunteer_profile_role", cols("app_client_id", C, "volunteer_profile_id", prof, "role_id", role));
        insert("volunteer_profile", cols("app_client_id", C, "family_member_id", grandpa, "status", "Inactive", "delete_flag", false));
        assignment(john, futureEvent, null, role, "confirmed", false);
        assignment(john, todayEvent, null, null, "pending", false);
        assignment(john, pastEvent, null, role, "confirmed", false);            // past → out
        assignment(john, futureEvent, null, null, "declined", false);           // declined → out
        assignment(john, futureEvent, null, null, "confirmed", true);           // deleted → out
        assignment(grandpa, futureEvent, null, null, "maybe", false);           // tentative only
        assignment(grandpa, null, today.plusDays(3), null, "cancelled", false); // cancelled → out
        assignment(john, xEvent, null, null, "confirmed", false);               // other church's event → out

        // Event Volunteers: linked member counts, typed-in person never does
        long ev = insert("event_volunteer", cols("app_client_id", C, "event_id", futureEvent, "family_member_id", kid,
                "status", "confirmed", "is_manual", false, "delete_flag", false));
        insert("event_volunteer_role", cols("app_client_id", C, "event_volunteer_id", ev, "role_name", "Usher", "delete_flag", false));
        insert("event_volunteer", cols("app_client_id", C, "event_id", futureEvent, "first_name", "Grand", "last_name", "Pa",
                "status", "confirmed", "is_manual", true, "delete_flag", false));
        insert("event_volunteer", cols("app_client_id", C, "event_id", pastEvent, "family_member_id", grandpa,
                "status", "confirmed", "is_manual", false, "delete_flag", false));   // past event

        // Kids Ministry + Prayer Team
        long room = insert("km_classroom", cols("client_id", C, "class_name", "Toddlers", "delete_flag", false));
        insert("km_volunteer", cols("client_id", C, "classroom_id", room, "family_member_id", mary, "name", "Mary Mathew",
                "role", "Teacher", "status", "confirmed", "is_manual", false, "inactive", false, "delete_flag", false));
        insert("km_volunteer", cols("client_id", C, "family_member_id", grandpa, "name", "Grand Pa",
                "status", "confirmed", "is_manual", false, "inactive", true, "delete_flag", false));      // inactive
        insert("prayer_volunteer", cols("client_id", C, "family_member_id", mary, "role", "Intercessor", "active", true, "delete_flag", false));
        insert("prayer_volunteer", cols("client_id", C, "family_member_id", grandpa, "role", "Intercessor", "active", false, "delete_flag", false));

        // Follow-ups
        followUp(C, "Welcome the Smiths", "PENDING", "John Mathew", john, false);          // linked by id
        followUp(C, "Done already", "COMPLETED", "John Mathew", john, false);              // completed → out
        followUp(C, "Call Mary's visitor", "MISSED", "Mary Mathew", null, false);          // unique name → Mary
        followUp(C, "Ambiguous call", "PENDING", "John Mathew", null, false);              // two Johns → possible
        followUp(C, "Deleted one", "PENDING", "Mary Mathew", null, true);                  // deleted → out
        followUp(X, "Other church", "PENDING", "Mary Mathew", john, false);                // other church, C's id → nobody
        followUp(X, "X Mary's task", "PENDING", "Mary Mathew", null, false);              // other church, unique there → X Mary
        followUp(C, "Renamed link", "PENDING", "Johnny M", kid, false);                    // id wins over a different name

        // Groups
        int men = group(C, "Men's Fellowship", false), choir = group(C, "Choir", false), youth = group(C, "Youth", false);
        int gone = group(C, "Old Group", true), xg = group(X, "Other Church Group", false);
        groupMember(men, C, "Johnny", "M", "changed@x.test", john, false);                 // renamed, still linked by id
        groupMember(choir, C, "Mary", "Mathew", "fam@mathew.test", null, false);           // shared email → possible
        groupMember(youth, C, "Kid", "Mathew", "kid@mathew.test", null, false);            // unique email → Kid
        groupMember(youth, C, "Kid", "Mathew", "kid@mathew.test", null, false);            // added twice → one group
        groupMember(gone, C, "John", "Mathew", "", john, false);                           // deleted group → out
        groupMember(choir, C, "John", "Mathew", "", john, true);                           // deleted membership → out
        groupMember(xg, X, "Mary", "Mathew", "fam@mathew.test", john, false);              // other church → out
    }

    // ── tests ───────────────────────────────────────────────────────────────

    @Test @DisplayName("Family counts are distinct people per area; another family with the same name gets nothing certain")
    void familyCounts() {
        Map<Integer, Map<String, Integer>> c = svc.counts(C, List.of(f1, f2, fx), ALL);
        assertThat(c.get(f1)).containsEntry("volunteers", 3)   // John (profile + assignments), Kid (event), Mary (KM + prayer)
                             .containsEntry("followUps", 3)    // John (id), Mary (unique name), Kid (id despite name)
                             .containsEntry("groups", 2);      // John (id), Kid (unique email)
        assertThat(c.get(f2)).containsEntry("volunteers", 0).containsEntry("followUps", 0).containsEntry("groups", 0);
        assertThat(c.get(fx)).containsEntry("volunteers", 0).containsEntry("followUps", 0).containsEntry("groups", 0);
    }

    @Test @DisplayName("Member details: every counted item, tentative and possible matches shown separately, nothing leaked")
    @SuppressWarnings("unchecked")
    void memberDetails() {
        Map<String, Object> d = svc.details(C, f1, ALL);
        Map<Integer, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> m : (List<Map<String, Object>>) d.get("members")) byId.put((Integer) m.get("memberId"), m);
        assertThat(byId).containsOnlyKeys(john, mary, kid, grandpa);    // deleted member never listed

        List<Map<String, Object>> jv = (List<Map<String, Object>>) byId.get(john).get("volunteers");
        assertThat(jv).extracting(m -> m.get("title")).containsExactlyInAnyOrder("Registered volunteer", "Sunday Worship", "Christmas Program");
        assertThat(jv).filteredOn(m -> "Registered volunteer".equals(m.get("title"))).first().extracting(m -> m.get("detail")).isEqualTo("Sound Team");
        assertThat(jv).filteredOn(m -> "Christmas Program".equals(m.get("title"))).first()
                .satisfies(m -> assertThat((String) m.get("detail")).contains("Sound Team"));
        assertThat(byId.get(john)).containsEntry("volunteer", true);
        assertThat((List<?>) byId.get(john).get("followUps")).extracting(m -> ((Map<String, Object>) m).get("title")).containsExactly("Welcome the Smiths");
        assertThat((List<?>) byId.get(john).get("possibleFollowUps")).extracting(m -> ((Map<String, Object>) m).get("title")).containsExactly("Ambiguous call");
        assertThat((List<?>) byId.get(john).get("groups")).extracting(m -> ((Map<String, Object>) m).get("title")).containsExactly("Men's Fellowship");

        List<Map<String, Object>> mv = (List<Map<String, Object>>) byId.get(mary).get("volunteers");
        assertThat(mv).extracting(m -> m.get("kind")).containsExactlyInAnyOrder("Kids Ministry", "Prayer Team");
        assertThat(mv).filteredOn(m -> "Kids Ministry".equals(m.get("kind"))).first()
                .satisfies(m -> assertThat((String) m.get("detail")).contains("Teacher").contains("Toddlers"));
        assertThat((List<?>) byId.get(mary).get("followUps")).extracting(m -> ((Map<String, Object>) m).get("title")).containsExactly("Call Mary's visitor");
        assertThat((List<?>) byId.get(mary).get("groups")).isEmpty();
        assertThat((List<?>) byId.get(mary).get("possibleGroups")).extracting(m -> ((Map<String, Object>) m).get("title")).containsExactly("Choir");

        assertThat((List<?>) byId.get(kid).get("volunteers")).extracting(m -> ((Map<String, Object>) m).get("detail")).containsExactly("Usher");
        assertThat((List<?>) byId.get(kid).get("followUps")).extracting(m -> ((Map<String, Object>) m).get("title")).containsExactly("Renamed link");
        assertThat((List<?>) byId.get(kid).get("groups")).extracting(m -> ((Map<String, Object>) m).get("title")).containsExactly("Youth");

        List<Map<String, Object>> gv = (List<Map<String, Object>>) byId.get(grandpa).get("volunteers");
        assertThat(gv).hasSize(1).first().satisfies(m -> assertThat(m).containsEntry("tentative", true));   // only the "maybe"
        assertThat(byId.get(grandpa)).containsEntry("volunteer", false);
        assertThat((List<?>) byId.get(grandpa).get("followUps")).isEmpty();
        assertThat((List<?>) byId.get(grandpa).get("groups")).isEmpty();

        // The other church's look-alike Mary sees none of church C's rows, and C's family is invisible to X.
        assertThat(svc.details(X, f1, ALL)).isNull();
        Map<String, Object> xd = svc.details(X, fx, ALL);
        Map<String, Object> xm = ((List<Map<String, Object>>) xd.get("members")).get(0);
        assertThat((List<?>) xm.get("followUps")).extracting(m -> ((Map<String, Object>) m).get("title")).containsExactly("X Mary's task");
        assertThat((List<?>) xm.get("possibleFollowUps")).isEmpty();     // a row carrying C's member id never falls back to the name
        assertThat((List<?>) xm.get("groups")).isEmpty();
    }

    @Test @DisplayName("The other John (same name, other family) only sees the ambiguous follow-up as a possible match")
    @SuppressWarnings("unchecked")
    void duplicateNameOtherFamily() {
        Map<String, Object> m = ((List<Map<String, Object>>) svc.details(C, f2, ALL).get("members")).get(0);
        assertThat((List<?>) m.get("followUps")).isEmpty();
        assertThat((List<?>) m.get("possibleFollowUps")).extracting(x -> ((Map<String, Object>) x).get("title")).containsExactly("Ambiguous call");
        assertThat((List<?>) m.get("groups")).isEmpty();
    }

    @Test @DisplayName("Sections follow the viewer's permissions; a User sees only follow-ups assigned to them")
    @SuppressWarnings("unchecked")
    void permissions() {
        assertThat(svc.counts(C, List.of(f1), Access.none())).isEmpty();
        Map<String, Integer> onlyPrayer = svc.counts(C, List.of(f1), new Access(false, false, true, false, false, "", false)).get(f1);
        assertThat(onlyPrayer).containsOnlyKeys("volunteers").containsEntry("volunteers", 1);     // Mary only
        Map<String, Integer> user = svc.counts(C, List.of(f1), new Access(false, false, false, true, false, "Mary Mathew", false)).get(f1);
        assertThat(user).containsOnlyKeys("followUps").containsEntry("followUps", 1);             // only "assigned_to = me"
        Map<String, Object> d = svc.details(C, f1, new Access(false, false, false, false, false, "", true));
        assertThat((Map<String, Object>) d.get("allowed")).containsEntry("volunteers", false).containsEntry("followUps", false).containsEntry("groups", true);
        assertThat(((List<Map<String, Object>>) d.get("members")).get(0)).doesNotContainKeys("volunteers", "followUps").containsKey("groups");
    }

    @Test @DisplayName("Trial church: new demo data is linked by id; with the ids removed (an existing demo church) the fallback still works")
    void trialDemoData() {
        String trial = TestDataService.TRIAL_CLIENT_PREFIX + System.currentTimeMillis();
        TestDataService.TenantSpec spec = new TestDataService.TenantSpec(trial, "Grace Trial Church",
                List.of("SuperAdmin"), 1, TestDataService.TenantSpec.Contact.of("Pat", "Trial", "pat@trial.test", "913-555-0100"));
        testData.provisionTenant(spec, null, today.plusDays(30));

        long gm = jdbc.queryForObject("SELECT count(*) FROM group_member WHERE app_client_id = ?", Long.class, trial);
        long gmLinked = jdbc.queryForObject("SELECT count(*) FROM group_member WHERE app_client_id = ? AND family_member_id IS NOT NULL", Long.class, trial);
        assertThat(gm).isPositive();
        assertThat(gmLinked).isEqualTo(gm);                                            // every demo membership linked
        long fuAssigned = jdbc.queryForObject("SELECT count(*) FROM follow_up WHERE client_id = ? AND assigned_to IS NOT NULL", Long.class, trial);
        long fuLinked = jdbc.queryForObject("SELECT count(*) FROM follow_up WHERE client_id = ? AND assigned_to IS NOT NULL AND assigned_member_id IS NOT NULL", Long.class, trial);
        assertThat(fuLinked).isEqualTo(fuAssigned);                                    // every assigned demo follow-up linked
        long badLinks = jdbc.queryForObject("SELECT count(*) FROM group_member gm JOIN family_member m ON m.id = gm.family_member_id "
                + "JOIN family f ON f.id = m.family_id WHERE gm.app_client_id = ? AND f.app_client_id <> ?", Long.class, trial, trial);
        assertThat(badLinks).isZero();                                                 // links stay inside the church

        List<Integer> fams = jdbc.queryForList("SELECT id FROM family WHERE app_client_id = ? AND delete_flag = false", Integer.class, trial);
        int linkedGroups = total(svc.counts(trial, fams, ALL), "groups");
        assertThat(linkedGroups).isPositive();

        // An existing demo church: same data, no ids → the safe fallback finds what it can and never more.
        jdbc.update("UPDATE group_member SET family_member_id = NULL WHERE app_client_id = ?", trial);
        jdbc.update("UPDATE follow_up SET assigned_member_id = NULL WHERE client_id = ?", trial);
        int fallbackGroups = total(svc.counts(trial, fams, ALL), "groups");
        assertThat(fallbackGroups).isBetween(0, linkedGroups);
    }

    @Test @DisplayName("Migration block: additive, runs twice")
    void migrationBlock() throws Exception {
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of("scripts/sql/migrate_production.sql"));
        String block = sql.substring(sql.indexOf("-- ── View Family involvement: member links"));
        block = block.substring(block.indexOf("DO $$"), block.indexOf("END $$;") + "END $$;".length());
        jdbc.execute(block);
        jdbc.execute(block);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name = 'follow_up' "
                + "AND column_name = 'assigned_member_id' AND is_nullable = 'YES'", Long.class)).isEqualTo(1L);
        assertThat(block).doesNotContainIgnoringCase("DROP").doesNotContainIgnoringCase("UPDATE ").doesNotContainIgnoringCase("DELETE");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    static int total(Map<Integer, Map<String, Integer>> counts, String key) {
        return counts.values().stream().mapToInt(m -> m.getOrDefault(key, 0)).sum();
    }

    int member(int family, String church, String first, String last, String email, String role) {
        return (int) insert("family_member", cols("family_id", family, "app_client_id", church, "first_name", first, "last_name", last,
                "email", email, "role", role, "delete_flag", false, "inactive", false));
    }

    int event(String church, String name, LocalDate date) {
        return (int) insert("church_event", cols("app_client_id", church, "event_name", name, "event_type", "One Day",
                "event_date", date, "delete_flag", false, "created_date", Timestamp.valueOf(LocalDateTime.now())));
    }

    void assignment(int member, Integer eventId, LocalDate date, Long role, String status, boolean deleted) {
        insert("volunteer_assignment", cols("app_client_id", C, "event_id", eventId, "event_date", date, "role_id", role,
                "family_member_id", member, "assignment_status", status, "notification_sent", false, "delete_flag", deleted));
    }

    void followUp(String church, String title, String status, String assignedTo, Integer memberId, boolean deleted) {
        insert("follow_up", cols("client_id", church, "title", title, "status", status, "assigned_to", assignedTo,
                "assigned_member_id", memberId, "delete_flag", deleted, "created_at", Timestamp.valueOf(LocalDateTime.now()),
                "due_date", java.sql.Date.valueOf(today.plusDays(2))));
    }

    int group(String church, String name, boolean deleted) {
        return (int) insert("app_group", cols("app_client_id", church, "group_name", name, "delete_flag", deleted,
                "created_date", Timestamp.valueOf(LocalDateTime.now())));
    }

    void groupMember(int group, String church, String first, String last, String email, Integer memberId, boolean deleted) {
        insert("group_member", cols("group_id", group, "app_client_id", church, "first_name", first, "last_name", last,
                "email", email, "family_member_id", memberId, "delete_flag", deleted, "created_date", Timestamp.valueOf(LocalDateTime.now())));
    }

    static Map<String, Object> cols(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** INSERT with the given columns; other NOT NULL columns without a default get a neutral value. */
    long insert(String table, Map<String, Object> cols) {
        List<Map<String, Object>> meta = jdbc.queryForList(
                "SELECT column_name, data_type, is_nullable, column_default FROM information_schema.columns "
              + "WHERE table_schema = current_schema() AND table_name = ?", table);
        assertThat(meta).as("table " + table + " exists").isNotEmpty();
        Map<String, Object> all = new LinkedHashMap<>(cols);
        for (Map<String, Object> m : meta) {
            String col = (String) m.get("column_name");
            if (all.containsKey(col) || m.get("column_default") != null || !"NO".equals(m.get("is_nullable"))) continue;
            if (col.equals("id")) {
                // a high range Hibernate's own sequences never reach, so later app inserts cannot collide
                all.put("id", jdbc.queryForObject("SELECT GREATEST(COALESCE(MAX(id), 0) + 1, 1500000000) FROM " + table, Long.class));
                continue;
            }
            all.put(col, switch ((String) m.get("data_type")) {
                case "boolean" -> false;
                case "integer", "bigint", "smallint", "numeric", "double precision", "real" -> 0;
                case "date" -> LocalDate.of(2000, 1, 1);
                case "timestamp without time zone", "timestamp with time zone" -> Timestamp.valueOf(LocalDateTime.of(2000, 1, 1, 0, 0));
                case "bytea" -> new byte[0];
                default -> "x" + UUID.randomUUID().toString().substring(0, 8);
            });
        }
        String names = String.join(",", all.keySet());
        String marks = String.join(",", Collections.nCopies(all.size(), "?"));
        Number n = jdbc.queryForObject("INSERT INTO " + table + " (" + names + ") VALUES (" + marks + ") RETURNING id",
                Number.class, all.values().toArray());
        return n.longValue();
    }
}
