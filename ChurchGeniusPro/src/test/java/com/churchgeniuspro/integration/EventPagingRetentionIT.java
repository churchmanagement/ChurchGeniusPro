package com.churchgeniuspro.integration;

import com.churchgeniuspro.service.EventListService;
import com.churchgeniuspro.service.EventRetentionService;
import com.churchgeniuspro.service.PrivateAccessService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Events tabs / Past paging, event retention (archive, permanent delete, dry run, restore)
 * and Access Audit keyset paging against real PostgreSQL with several churches.
 *
 * <p>Rows are inserted with plain SQL; {@link #insert} fills any other NOT NULL column that
 * has no default with a neutral value, so the test does not depend on every column of every
 * table. Runs on {@code verify} with Docker (Testcontainers), or against {@code CGP_IT_JDBC_URL}.
 */
@EnabledIf("com.churchgeniuspro.integration.EventPagingRetentionIT#databaseAvailable")
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = { "app.base-url=http://localhost" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Events paging + retention + Access Audit paging — PostgreSQL")
class EventPagingRetentionIT {

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

    static final String A = "CGP-EVP-ITA", B = "CGP-EVP-ITB", C = "CGP-EVR-ITC", D = "CGP-EVR-ITD";

    @Autowired JdbcTemplate jdbc;
    @Autowired EventListService lists;
    @Autowired EventRetentionService retention;
    @Autowired PrivateAccessService access;

    final AtomicLong ids = new AtomicLong(970_000);
    final LocalDate today = AppClock.today();
    final Map<String, Integer> ev = new HashMap<>();

    // ── seed ────────────────────────────────────────────────────────────────

    @BeforeAll
    void seed() {
        for (String t : List.of(A, B, C, D)) {
            String mine = " IN (SELECT id FROM church_event WHERE app_client_id = ?)";
            jdbc.update("DELETE FROM volunteer_attendance WHERE assignment_id IN (SELECT id FROM volunteer_assignment WHERE event_id" + mine + ")", t);
            jdbc.update("DELETE FROM event_volunteer_role WHERE event_volunteer_id IN (SELECT id FROM event_volunteer WHERE event_id" + mine + ")", t);
            jdbc.update("DELETE FROM event_registration_reminder_log WHERE event_id" + mine, t);
            for (String child : List.of("volunteer_assignment", "event_volunteer", "event_registration_reminder_contact",
                                        "event_reminder", "event_registration", "church_event_image", "church_event_day"))
                jdbc.update("DELETE FROM " + child + " WHERE event_id" + mine, t);
            jdbc.update("DELETE FROM church_event WHERE app_client_id = ?", t);
            jdbc.update("DELETE FROM private_access_log WHERE client_id = ?", t);
            jdbc.update("DELETE FROM import_link WHERE client_id = ?", t);
        }
        // Church A — tab membership
        ev.put("today",     event(A, "Today Service", "One Day", today, false, null));
        ev.put("yesterday", event(A, "Yesterday Picnic", "One Day", today.minusDays(1), false, null));
        int multiNow = event(A, "Camp Running", "Multiple Days", today.minusDays(3), false, null);
        day(multiNow, today.minusDays(3)); day(multiNow, today.plusDays(1));
        ev.put("multiNow", multiNow);
        int multiDone = event(A, "Camp Done", "Multiple Days", today.minusDays(9), false, null);
        day(multiDone, today.minusDays(9)); day(multiDone, today.minusDays(2));
        ev.put("multiDone", multiDone);
        ev.put("undated",   event(A, "Someday", "One Day", null, false, null));
        ev.put("deleted",   event(A, "Deleted Past", "One Day", today.minusDays(5), true, "USER"));
        // 14 more past events, several sharing an end date
        for (int i = 0; i < 14; i++) event(A, "Past " + (char) ('A' + i) + (i == 3 ? " 100%" : ""), "One Day", today.minusDays(10 + i / 3), false, null);
        // Church B — must never leak into A
        for (int i = 0; i < 4; i++) event(B, "Other church " + i, "One Day", today.minusDays(4 + i), false, null);
    }

    int event(String church, String name, String type, LocalDate date, boolean deleted, String reason) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("app_client_id", church);
        c.put("event_name", name);
        c.put("event_type", type);
        c.put("event_date", date);
        c.put("delete_flag", deleted);
        c.put("delete_reason", reason);
        c.put("created_date", Timestamp.valueOf(LocalDateTime.now()));
        return (int) insert("church_event", c);
    }

    void day(int eventId, LocalDate d) {
        insert("church_event_day", new LinkedHashMap<>(Map.of("event_id", eventId, "event_date", d)));
    }

    /** INSERT with the given columns; other NOT NULL columns without a default get a neutral value. */
    long insert(String table, Map<String, Object> cols) {
        List<Map<String, Object>> meta = jdbc.queryForList(
                "SELECT column_name, data_type, is_nullable, column_default FROM information_schema.columns "
              + "WHERE table_schema = current_schema() AND table_name = ?", table);
        assertThat(meta).as("table " + table + " exists").isNotEmpty();
        Map<String, Object> all = new LinkedHashMap<>(cols);
        Long id = null;
        for (Map<String, Object> m : meta) {
            String col = (String) m.get("column_name");
            if (all.containsKey(col) || m.get("column_default") != null || !"NO".equals(m.get("is_nullable"))) continue;
            if (col.equals("id")) {
                id = Math.max(ids.incrementAndGet(), jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM " + table, Long.class));
                ids.set(id); all.put("id", id); continue;
            }
            all.put(col, filler((String) m.get("data_type")));
        }
        String names = String.join(",", all.keySet());
        String marks = String.join(",", Collections.nCopies(all.size(), "?"));
        boolean hasId = meta.stream().anyMatch(m -> "id".equals(m.get("column_name")));
        if (!hasId) { jdbc.update("INSERT INTO " + table + " (" + names + ") VALUES (" + marks + ")", all.values().toArray()); return 0; }
        Number n = jdbc.queryForObject("INSERT INTO " + table + " (" + names + ") VALUES (" + marks + ") RETURNING id",
                Number.class, all.values().toArray());
        return n.longValue();
    }

    Object filler(String type) {
        return switch (type) {
            case "boolean" -> false;
            case "integer", "bigint", "smallint" -> 0;
            case "numeric", "double precision", "real" -> 0;
            case "date" -> LocalDate.of(2000, 1, 1);
            case "timestamp without time zone", "timestamp with time zone" -> Timestamp.valueOf(LocalDateTime.of(2000, 1, 1, 0, 0));
            case "bytea" -> new byte[0];
            default -> "x" + UUID.randomUUID().toString().substring(0, 8);
        };
    }

    // ── Events tabs ─────────────────────────────────────────────────────────

    @Test @Order(1)
    @DisplayName("Upcoming = no date or last day today or later; deleted and other churches never shown")
    void upcomingTab() {
        Set<Integer> up = idsOf(lists.upcoming(A));
        assertThat(up).containsExactlyInAnyOrder(ev.get("today"), ev.get("multiNow"), ev.get("undated"));
    }

    @Test @Order(2)
    @DisplayName("Past pages of 10, newest first, no repeats, complete, equals the Past stat")
    void pastNewestPaging() {
        List<Map<String, Object>> all = pageAll("newest", null);
        Set<Integer> seen = new HashSet<>();
        for (Map<String, Object> r : all) assertThat(seen.add(((Number) r.get("id")).intValue())).isTrue();
        assertThat(seen).contains(ev.get("yesterday"), ev.get("multiDone"))
                        .doesNotContain(ev.get("deleted"), ev.get("today"), ev.get("multiNow"), ev.get("undated"));
        assertThat(all).hasSize(16);
        assertThat(lists.stats(A)).containsEntry("past", 16L).containsEntry("upcoming", 3L)
                                  .containsEntry("total", 19L).containsEntry("multi", 2L);
        assertThat(all.get(0).get("id")).isEqualTo(ev.get("yesterday"));   // most recent first
        assertThat(all.get(1).get("id")).isEqualTo(ev.get("multiDone"));   // multi-day ordered by its LAST day
    }

    @Test @Order(3)
    @DisplayName("Oldest and name sorts page completely; search escapes wildcards; bad input refused")
    void otherSortsAndSearch() {
        List<Map<String, Object>> oldest = pageAll("oldest", null);
        assertThat(oldest).hasSize(16);
        assertThat(oldest.get(oldest.size() - 1).get("id")).isEqualTo(ev.get("yesterday"));
        List<Map<String, Object>> byName = pageAll("name", null);
        assertThat(byName).hasSize(16);
        List<String> names = byName.stream().map(r -> String.valueOf(r.get("eventName") != null ? r.get("eventName") : r.get("name")).toLowerCase()).toList();
        assertThat(names).isSorted();
        assertThat(pageAll("newest", "100%")).hasSize(1);
        assertThat(pageAll("newest", "%")).hasSize(1);           // '%' is a literal: only "100%" matches
        assertThat(pageAll("newest", "_")).isEmpty();            // '_' is a literal too
        assertThat(pageAll("newest", "camp")).hasSize(1);        // only the finished camp is past
        assertThatThrownBy(() -> lists.past(A, null, null, "newest", "garbage", 10)).isInstanceOf(EventListService.BadRequest.class);
        assertThatThrownBy(() -> lists.past(A, null, null, "name", "x5", 10)).isInstanceOf(EventListService.BadRequest.class);
        assertThatThrownBy(() -> lists.past(A, null, null, "random", null, 10)).isInstanceOf(EventListService.BadRequest.class);
    }

    @Test @Order(4)
    @DisplayName("Editing an event's date moves it between tabs; a delete mid-paging causes no gap or repeat")
    void editAndDeleteWhilePaging() {
        EventListService.PastPage p1 = lists.past(A, null, null, "newest", null, 10);
        assertThat(p1.hasMore()).isTrue();
        // move yesterday's event to tomorrow → Upcoming
        jdbc.update("UPDATE church_event SET event_date = ? WHERE id = ?", today.plusDays(1), ev.get("yesterday"));
        // delete an event the next page would show
        Integer victim = ((Number) pageAll("newest", null).get(12).get("id")).intValue();
        jdbc.update("UPDATE church_event SET delete_flag = true WHERE id = ?", victim);
        EventListService.PastPage p2 = lists.past(A, null, null, "newest", p1.nextCursor(), 10);
        Set<Integer> first = idsOf(p1.rows()), second = idsOf(p2.rows());
        assertThat(Collections.disjoint(first, second)).isTrue();
        assertThat(second).doesNotContain(victim);
        assertThat(first.size() + second.size()).isEqualTo(15);           // 16 − 1 deleted
        assertThat(idsOf(lists.upcoming(A))).contains(ev.get("yesterday"));
        // and back
        jdbc.update("UPDATE church_event SET event_date = ? WHERE id = ?", today.minusDays(1), ev.get("yesterday"));
        jdbc.update("UPDATE church_event SET delete_flag = false WHERE id = ?", victim);
        assertThat(idsOf(lists.upcoming(A))).doesNotContain(ev.get("yesterday"));
    }

    @Test @Order(5)
    @DisplayName("Church B sees only its own events")
    void tenantIsolation() {
        assertThat(pageAll("newest", null).stream().map(r -> r.get("id")).toList())
                .doesNotContainAnyElementsOf(idsOf(lists.past(B, null, null, "newest", null, 50).rows()));
        assertThat(lists.past(B, null, null, "newest", null, 50).rows()).hasSize(4);
        assertThat(lists.past("", null, null, "newest", null, 10).rows()).isEmpty();
    }

    List<Map<String, Object>> pageAll(String sort, String q) {
        List<Map<String, Object>> out = new ArrayList<>();
        String cursor = null;
        for (int guard = 0; guard < 20; guard++) {
            EventListService.PastPage p = lists.past(A, q, null, sort, cursor, 10);
            assertThat(p.rows().size()).isLessThanOrEqualTo(10);
            out.addAll(p.rows());
            if (!p.hasMore()) { assertThat(p.nextCursor()).isNull(); return out; }
            cursor = p.nextCursor();
        }
        throw new AssertionError("paging did not end");
    }

    static Set<Integer> idsOf(List<Map<String, Object>> rows) {
        Set<Integer> s = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) s.add(((Number) r.get("id")).intValue());
        return s;
    }

    // ── Retention ───────────────────────────────────────────────────────────

    static final LocalDate RUN_DAY = LocalDate.of(2026, 11, 2);   // archive cutoff 2021-11-02, delete cutoff 2016-11-02

    @Test @Order(10)
    @DisplayName("Archive: strictly older than 5 years by last day; idempotent; user deletes and undated untouched")
    void archiveRules() {
        int justOver  = event(C, "Ended 2021-11-01", "One Day", LocalDate.of(2021, 11, 1), false, null);
        int exactly   = event(C, "Ended 2021-11-02", "One Day", LocalDate.of(2021, 11, 2), false, null);
        int multi     = event(C, "Multi ending 2021-11-05", "Multiple Days", LocalDate.of(2021, 10, 25), false, null);
        day(multi, LocalDate.of(2021, 10, 25)); day(multi, LocalDate.of(2021, 11, 5));
        int userDel   = event(C, "User deleted 2019", "One Day", LocalDate.of(2019, 5, 5), true, "USER");
        int undated   = event(C, "Undated", "One Day", null, false, null);
        ev.put("c.justOver", justOver);

        int n = retention.archive(RUN_DAY);
        assertThat(n).isGreaterThanOrEqualTo(1);
        assertThat(state(justOver)).isEqualTo("true|RETENTION");
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM church_event WHERE id = ?", Boolean.class, justOver)).isTrue();
        assertThat(state(exactly)).isEqualTo("false|null");     // exactly 5 years → not yet
        assertThat(state(multi)).isEqualTo("false|null");       // its LAST day is within 5 years
        assertThat(state(userDel)).isEqualTo("true|USER");      // reason never overwritten
        assertThat(state(undated)).isEqualTo("false|null");
        assertThat(retention.archive(RUN_DAY)).isZero();         // second run changes nothing
        // church A's recent events untouched
        assertThat(lists.stats(A)).containsEntry("total", 19L);
    }

    @Test @Order(11)
    @DisplayName("Leap day: an event on 29 Feb 2020 is archived on 1 Mar 2025, not on 28 Feb 2025")
    void leapDay() {
        int leap = event(D, "Leap day 2020", "One Day", LocalDate.of(2020, 2, 29), false, null);
        retention.archive(LocalDate.of(2025, 2, 28));
        assertThat(state(leap)).isEqualTo("false|null");
        retention.archive(LocalDate.of(2025, 3, 1));
        assertThat(state(leap)).isEqualTo("true|RETENTION");
        assertThat(retention.archiveCutoff(LocalDate.of(2025, 2, 28))).isEqualTo(LocalDate.of(2020, 2, 28));
        assertThat(retention.archiveCutoff(LocalDate.of(2024, 2, 29))).isEqualTo(LocalDate.of(2019, 2, 28));
    }

    @Test @Order(12)
    @DisplayName("Restore brings back only a RETENTION archive and the nightly archive skips it afterwards")
    void restore() {
        int id = ev.get("c.justOver");
        assertThat(retention.restore(id)).isTrue();
        assertThat(state(id)).isEqualTo("false|RESTORED");
        assertThat(retention.archive(RUN_DAY)).isZero();
        assertThat(state(id)).isEqualTo("false|RESTORED");
        int userDel = jdbc.queryForObject("SELECT id FROM church_event WHERE app_client_id = ? AND delete_reason = 'USER'", Integer.class, C);
        assertThat(retention.restore(userDel)).isFalse();
        assertThat(retention.restore(999_999_999)).isFalse();
    }

    @Test @Order(13)
    @DisplayName("Dry run lists per church and the related rows; permanent delete removes exactly those, once")
    void dryRunAndPurge() {
        // a 10-year-old event with every kind of related row (never archived first)
        int old = event(C, "Ended 2016-10-30", "Multiple Days", LocalDate.of(2016, 10, 28), false, null);
        day(old, LocalDate.of(2016, 10, 28)); day(old, LocalDate.of(2016, 10, 30));
        // already archived, also past 10 years
        int oldArchived = event(C, "Ended 2015", "One Day", LocalDate.of(2015, 6, 1), true, "RETENTION");
        // other church, past 10 years too
        int otherOld = event(D, "D ended 2014", "One Day", LocalDate.of(2014, 1, 1), false, null);
        // keeper in C (archived, not old enough to delete)
        int keeper = ev.get("c.justOver");

        long reg = insert("event_registration", cols("event_id", old));
        long reminder = insert("event_reminder", cols("event_id", old));
        long img = insert("church_event_image", cols("event_id", old));
        long contact = insert("event_registration_reminder_contact", cols("event_id", old));
        insert("event_registration_reminder_log", cols("event_id", old, "contact_id", contact));
        long ev1 = insert("event_volunteer", cols("event_id", old));
        insert("event_volunteer_role", cols("event_volunteer_id", ev1));
        long asg = insert("volunteer_assignment", cols("event_id", old));
        insert("volunteer_attendance", cols("assignment_id", asg));
        insert("import_link", cols("client_id", C, "entity", "event", "target_id", old));
        insert("import_link", cols("client_id", C, "entity", "event_registration", "target_id", reg));
        long keepPersonLink = insert("import_link", cols("client_id", C, "entity", "person", "target_id", old));       // same number, other entity
        long keepOtherChurch = insert("import_link", cols("client_id", A, "entity", "event", "target_id", old));       // other church
        long keepReg = insert("event_registration", cols("event_id", keeper));

        Map<String, Object> dry = retention.dryRun(RUN_DAY, 500);
        @SuppressWarnings("unchecked") List<Map<String, Object>> churches = (List<Map<String, Object>>) dry.get("churches");
        Map<String, Object> cRow = churches.stream().filter(m -> C.equals(m.get("church"))).findFirst().orElseThrow();
        assertThat(((Number) cRow.get("to_delete")).intValue()).isEqualTo(2);
        @SuppressWarnings("unchecked") Map<String, Object> rows = (Map<String, Object>) dry.get("permanentDeleteRows");
        assertThat(((Number) rows.get("church_event_day")).longValue()).isGreaterThanOrEqualTo(2);
        assertThat(((Number) rows.get("event_registration")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) rows.get("volunteer_attendance")).longValue()).isGreaterThanOrEqualTo(1);
        assertThat(eventExists(old)).isTrue();                    // dry run changed nothing
        @SuppressWarnings("unchecked") List<Map<String, Object>> list = (List<Map<String, Object>>) dry.get("events");
        assertThat(list).anyMatch(m -> Integer.valueOf(old).equals(m.get("id")) && "DELETE PERMANENTLY".equals(m.get("action")));

        Map<String, Object> r = retention.purge(RUN_DAY, 200);
        assertThat(((Number) r.get("failed")).intValue()).isZero();
        assertThat(eventExists(old)).isFalse();
        assertThat(eventExists(oldArchived)).isFalse();
        assertThat(eventExists(otherOld)).isFalse();
        assertThat(eventExists(keeper)).isTrue();
        for (String t : List.of("church_event_day", "event_registration", "event_reminder", "church_event_image",
                                "event_registration_reminder_contact", "event_registration_reminder_log",
                                "event_volunteer", "volunteer_assignment")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + t + " WHERE event_id = ?", Long.class, old)).as(t).isZero();
        }
        assertThat(count("SELECT COUNT(*) FROM volunteer_attendance WHERE assignment_id = ?", asg)).isZero();
        assertThat(count("SELECT COUNT(*) FROM event_volunteer_role WHERE event_volunteer_id = ?", ev1)).isZero();
        assertThat(count("SELECT COUNT(*) FROM import_link WHERE client_id = ? AND entity IN ('event','event_registration') AND target_id IN (?, ?)", C, old, reg)).isZero();
        assertThat(count("SELECT COUNT(*) FROM import_link WHERE id IN (?, ?)", keepPersonLink, keepOtherChurch)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM event_registration WHERE id = ?", keepReg)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM event_reminder WHERE id = ?", reminder)).isZero();
        assertThat(count("SELECT COUNT(*) FROM church_event_image WHERE id = ?", img)).isZero();

        Map<String, Object> again = retention.purge(RUN_DAY, 200);
        assertThat(((Number) again.get("deleted")).intValue()).isZero();
        // church A untouched by all of it
        assertThat(lists.stats(A)).containsEntry("total", 19L);
    }

    static Map<String, Object> cols(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    boolean eventExists(int id) { return count("SELECT COUNT(*) FROM church_event WHERE id = ?", id) == 1; }
    String state(int id) {
        return jdbc.queryForObject("SELECT delete_flag || '|' || COALESCE(delete_reason, 'null') FROM church_event WHERE id = ?", String.class, id);
    }

    // ── Access Audit ────────────────────────────────────────────────────────

    @Test @Order(20)
    @DisplayName("Access Audit: 10 per page, newest first, equal timestamps, stable while new rows arrive, per church")
    void accessAuditPaging() {
        LocalDateTime base = LocalDateTime.of(2026, 10, 1, 9, 0);
        for (int i = 0; i < 25; i++) audit(A, base.plusMinutes(i / 4));       // groups of 4 share a timestamp
        for (int i = 0; i < 3; i++) audit(B, base.plusMinutes(30));

        Map<String, Object> p1 = access.report(A, 10, null, null);
        assertThat(p1).containsKey("summary").containsEntry("hasMore", true);
        List<Map<String, Object>> rows = new ArrayList<>(recent(p1));
        assertThat(rows).hasSize(10);
        audit(A, base.plusDays(1));                                              // new row while paging
        Map<String, Object> last = rows.get(rows.size() - 1);
        for (int guard = 0; guard < 10; guard++) {
            Map<String, Object> p = access.report(A, 10, LocalDateTime.parse((String) last.get("time")), ((Number) last.get("id")).longValue());
            assertThat(p).doesNotContainKey("summary");
            rows.addAll(recent(p));
            if (!(Boolean) p.get("hasMore")) break;
            last = rows.get(rows.size() - 1);
        }
        assertThat(rows).hasSize(25);
        assertThat(rows.stream().map(r -> r.get("id")).distinct().count()).isEqualTo(25);
        for (int i = 1; i < rows.size(); i++) {
            String t0 = (String) rows.get(i - 1).get("time"), t1 = (String) rows.get(i).get("time");
            assertThat(t0.compareTo(t1) > 0 || (t0.equals(t1)
                    && ((Number) rows.get(i - 1).get("id")).longValue() > ((Number) rows.get(i).get("id")).longValue())).isTrue();
        }
        assertThat(recent(access.report(A, 10, null, null)).get(0).get("time")).isEqualTo(base.plusDays(1).toString());
        assertThat(recent(access.report(B, 10, null, null))).hasSize(3);
    }

    void audit(String church, LocalDateTime at) {
        insert("private_access_log", cols("client_id", church, "created_at", Timestamp.valueOf(at), "status", "BLOCKED",
                "username", "it-user", "page_key", "income"));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> recent(Map<String, Object> page) { return (List<Map<String, Object>>) page.get("recent"); }
}
