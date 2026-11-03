package com.churchgeniuspro.service;

import com.churchgeniuspro.util.AppClock;
import com.churchgeniuspro.util.MemberNameUtil;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.*;

/**
 * View Family → who in a family volunteers, carries follow-ups, or belongs to groups
 * (2026-11-02, user-approved plan). Read-only.
 *
 * <h3>What counts</h3>
 * <ul>
 *   <li><b>Volunteers</b>, four sources, each only when the viewer may see that page:
 *     <ul>
 *       <li>Volunteers page — an Active {@code volunteer_profile}, and {@code volunteer_assignment}
 *           rows for today or later (Central time) with status pending / confirmed;</li>
 *       <li>Event Volunteers — {@code event_volunteer} rows LINKED to a member (typed-in people
 *           have no member id and are never counted), pending / confirmed, for an event whose
 *           last day is today or later;</li>
 *       <li>Kids Ministry — {@code km_volunteer} not inactive, status pending / confirmed / blank;</li>
 *       <li>Prayer Team — {@code prayer_volunteer} active.</li>
 *     </ul>
 *     "maybe" is listed as tentative and never counted; declined, cancelled, inactive and
 *     deleted are left out. An assignment or event with no date counts as upcoming (the
 *     Events page rule).</li>
 *   <li><b>Follow-ups</b> assigned to the member, status PENDING or MISSED, not deleted —
 *       by {@code follow_up.assigned_member_id}; for older rows without it, by the assignee
 *       NAME only when that name belongs to exactly one live member of the church. A name
 *       shared by several members is shown as a possible match and never counted.</li>
 *   <li><b>Groups</b> — {@code group_member} rows of live groups, by {@code family_member_id};
 *       for older rows without it, by EMAIL only when that email belongs to exactly one live
 *       member of the church. Name-only or shared-email rows are possible matches only.</li>
 * </ul>
 * Card counts are distinct people (a member with three assignments counts once).
 * Deleted members are never included. Every query is limited to one church.
 */
@Service
public class FamilyInvolvementService {

    public static final int MAX_FAMILIES = 50;

    private static final Set<String> COUNTED   = Set.of("", "pending", "confirmed");
    private static final Set<String> TENTATIVE = Set.of("maybe");

    /** Last day of an event — same rule as the Events page. */
    private static final String EVENT_END = EventListService.END_DATE;

    private final NamedParameterJdbcTemplate jdbc;

    public FamilyInvolvementService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Which sections the viewer may see (the pages' own rules, resolved by the caller). */
    public record Access(boolean volunteers, boolean kidsMinistry, boolean prayer,
                         boolean followUps, boolean followUpsAll, String username, boolean groups) {
        public static Access none() { return new Access(false, false, false, false, false, "", false); }
        public boolean anyVolunteers() { return volunteers || kidsMinistry || prayer; }
        public boolean any() { return anyVolunteers() || followUps || groups; }
    }

    // ── public API ───────────────────────────────────────────────────────────

    /** {familyId: {volunteers?: n, followUps?: n, groups?: n}} — only the sections the viewer may see. */
    public Map<Integer, Map<String, Integer>> counts(String clientId, Collection<Integer> familyIds, Access access) {
        Map<Integer, Map<String, Integer>> out = new LinkedHashMap<>();
        List<Integer> fids = cleanIds(familyIds);
        if (blank(clientId) || fids.isEmpty() || access == null || !access.any()) return out;
        Result r = compute(clientId, fids, access);
        for (Integer fid : fids) {
            Map<String, Integer> c = new LinkedHashMap<>();
            Set<Integer> vol = new HashSet<>(), fu = new HashSet<>(), gr = new HashSet<>();
            for (Person p : r.membersOf(fid)) {
                if (p.volunteers.stream().anyMatch(v -> !v.tentative)) vol.add(p.id);
                if (!p.followUps.isEmpty()) fu.add(p.id);
                if (!p.groups.isEmpty()) gr.add(p.id);
            }
            if (access.anyVolunteers()) c.put("volunteers", vol.size());
            if (access.followUps())     c.put("followUps", fu.size());
            if (access.groups())        c.put("groups", gr.size());
            out.put(fid, c);
        }
        return out;
    }

    /** Per-member details for one family, or null when the family is not in this church. */
    public Map<String, Object> details(String clientId, Integer familyId, Access access) {
        if (blank(clientId) || familyId == null) return null;
        Integer found = jdbc.query("SELECT id FROM family WHERE id = :f AND app_client_id = :c",
                new MapSqlParameterSource().addValue("f", familyId).addValue("c", clientId),
                rs -> rs.next() ? rs.getInt(1) : null);
        if (found == null) return null;
        Access a = access == null ? Access.none() : access;
        Result r = a.any() ? compute(clientId, List.of(familyId), a) : new Result(loadMembers(clientId, List.of(familyId)));

        Map<String, Object> allowed = new LinkedHashMap<>();
        allowed.put("volunteers", a.anyVolunteers());
        allowed.put("followUps",  a.followUps());
        allowed.put("groups",     a.groups());
        List<Map<String, Object>> members = new ArrayList<>();
        for (Person p : r.membersOf(familyId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("memberId", p.id);
            m.put("name", p.display);
            m.put("inactive", p.inactive);
            if (a.anyVolunteers()) {
                m.put("volunteer", p.volunteers.stream().anyMatch(v -> !v.tentative));
                m.put("volunteers", p.volunteers.stream().map(Item::toMap).toList());
            }
            if (a.followUps()) {
                m.put("followUps", p.followUps.stream().map(Item::toMap).toList());
                m.put("possibleFollowUps", p.possibleFollowUps.stream().map(Item::toMap).toList());
            }
            if (a.groups()) {
                m.put("groups", p.groups.stream().map(Item::toMap).toList());
                m.put("possibleGroups", p.possibleGroups.stream().map(Item::toMap).toList());
            }
            members.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("familyId", familyId);
        out.put("today", AppClock.today().toString());
        out.put("allowed", allowed);
        out.put("members", members);
        return out;
    }

    // ── model ────────────────────────────────────────────────────────────────

    /** One line of detail: a volunteer role / event, a follow-up, a group. */
    record Item(String kind, String title, String detail, String date, String status, boolean tentative) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", kind);
            m.put("title", title);
            if (detail != null && !detail.isBlank()) m.put("detail", detail);
            if (date != null) m.put("date", date);
            if (status != null && !status.isBlank()) m.put("status", status);
            if (tentative) m.put("tentative", true);
            return m;
        }
    }

    static final class Person {
        final int id; final int familyId; final String first, last, display, email; final boolean inactive;
        final List<Item> volunteers = new ArrayList<>(), followUps = new ArrayList<>(), groups = new ArrayList<>();
        final List<Item> possibleFollowUps = new ArrayList<>(), possibleGroups = new ArrayList<>();
        Person(int id, int familyId, String first, String last, String other, String email, boolean inactive) {
            this.id = id; this.familyId = familyId; this.first = nz(first); this.last = nz(last);
            String d = MemberNameUtil.display(first, last, other);
            this.display = blank(d) ? (this.first + " " + this.last).trim() : d;
            this.email = nz(email).trim(); this.inactive = inactive;
        }
        String nameKey() { return key(first + " " + last); }
    }

    static final class Result {
        final Map<Integer, Person> byId = new LinkedHashMap<>();
        Result(List<Person> people) { for (Person p : people) byId.put(p.id, p); }
        List<Person> membersOf(Integer fid) {
            List<Person> l = new ArrayList<>();
            for (Person p : byId.values()) if (p.familyId == fid) l.add(p);
            return l;
        }
    }

    // ── computation ──────────────────────────────────────────────────────────

    private Result compute(String clientId, List<Integer> fids, Access a) {
        Result r = new Result(loadMembers(clientId, fids));
        if (r.byId.isEmpty()) return r;
        List<Integer> ids = new ArrayList<>(r.byId.keySet());
        LocalDate today = AppClock.today();
        if (a.volunteers()) {
            volunteerProfiles(clientId, ids, r);
            volunteerAssignments(clientId, ids, today, r);
            eventVolunteers(clientId, ids, today, r);
        }
        if (a.kidsMinistry()) kidsMinistry(clientId, ids, r);
        if (a.prayer())       prayerTeam(clientId, ids, r);
        if (a.followUps())    followUps(clientId, r, a);
        if (a.groups())       groups(clientId, r);
        for (Person p : r.byId.values()) {
            p.volunteers.sort(Comparator.comparing((Item i) -> i.date == null ? "9999" : i.date).thenComparing(i -> nz(i.title)));
            p.followUps.sort(Comparator.comparing((Item i) -> i.date == null ? "9999" : i.date).thenComparing(i -> nz(i.title)));
            p.groups.sort(Comparator.comparing(i -> nz(i.title).toLowerCase(Locale.ROOT)));
        }
        return r;
    }

    /** Live (not deleted) members of the given families of this church. */
    private List<Person> loadMembers(String clientId, List<Integer> fids) {
        return jdbc.query(
                "SELECT m.id, m.family_id, m.first_name, m.last_name, m.other_name, m.email, m.inactive "
              + "FROM family_member m JOIN family f ON f.id = m.family_id "
              + "WHERE f.app_client_id = :c AND m.family_id IN (:f) AND m.delete_flag = false "
              + "ORDER BY m.family_id, m.id",
                new MapSqlParameterSource().addValue("c", clientId).addValue("f", fids),
                (rs, i) -> new Person(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getBoolean(7)));
    }

    private void volunteerProfiles(String c, List<Integer> ids, Result r) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("c", c).addValue("ids", ids);
        jdbc.query("SELECT vp.family_member_id, "
                 + "  (SELECT string_agg(vr.role_name, ', ' ORDER BY vr.role_name) FROM volunteer_profile_role pr "
                 + "     JOIN volunteer_role vr ON vr.id = pr.role_id WHERE pr.volunteer_profile_id = vp.id) AS roles "
                 + "FROM volunteer_profile vp WHERE vp.app_client_id = :c AND vp.family_member_id IN (:ids) "
                 + "AND vp.delete_flag = false AND LOWER(COALESCE(vp.status, 'Active')) = 'active'", p, rs -> {
            Person m = r.byId.get(rs.getInt(1));
            if (m != null) m.volunteers.add(new Item("Volunteers", "Registered volunteer", rs.getString(2), null, "Active", false));
        });
    }

    private void volunteerAssignments(String c, List<Integer> ids, LocalDate today, Result r) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("c", c).addValue("ids", ids).addValue("today", Date.valueOf(today));
        jdbc.query("SELECT va.family_member_id, COALESCE(e.event_name, va.event_label) AS what, vr.role_name, "
                 + "  COALESCE(va.event_date, " + EVENT_END + ") AS d, va.shift_time, va.assignment_status "
                 + "FROM volunteer_assignment va "
                 + "LEFT JOIN church_event e ON e.id = va.event_id AND e.app_client_id = :c "
                 + "LEFT JOIN volunteer_role vr ON vr.id = va.role_id AND vr.app_client_id = :c "
                 + "WHERE va.app_client_id = :c AND va.family_member_id IN (:ids) AND va.delete_flag = false "
                 + "AND (va.event_id IS NULL OR (e.id IS NOT NULL AND e.delete_flag = false)) "
                 + "AND (COALESCE(va.event_date, " + EVENT_END + ") IS NULL OR COALESCE(va.event_date, " + EVENT_END + ") >= :today)",
                p, rs -> {
            Person m = r.byId.get(rs.getInt(1));
            String st = lc(rs.getString(6));
            if (m == null || !(COUNTED.contains(st) || TENTATIVE.contains(st))) return;
            String detail = join(" · ", rs.getString(3), rs.getString(5));
            m.volunteers.add(new Item("Volunteers", nzOr(rs.getString(2), "Volunteer assignment"), detail,
                    iso(rs.getDate(4)), statusLabel(st), TENTATIVE.contains(st)));
        });
    }

    private void eventVolunteers(String c, List<Integer> ids, LocalDate today, Result r) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("c", c).addValue("ids", ids).addValue("today", Date.valueOf(today));
        jdbc.query("SELECT ev.family_member_id, e.event_name, "
                 + "  (SELECT string_agg(rr.role_name, ', ' ORDER BY rr.role_name) FROM event_volunteer_role rr "
                 + "     WHERE rr.event_volunteer_id = ev.id AND rr.delete_flag = false) AS roles, "
                 + "  " + EVENT_END + " AS d, ev.status "
                 + "FROM event_volunteer ev JOIN church_event e ON e.id = ev.event_id AND e.app_client_id = :c "
                 + "WHERE ev.app_client_id = :c AND ev.family_member_id IN (:ids) AND ev.delete_flag = false "
                 + "AND e.delete_flag = false AND (" + EVENT_END + " IS NULL OR " + EVENT_END + " >= :today)",
                p, rs -> {
            Person m = r.byId.get(rs.getInt(1));
            String st = lc(rs.getString(5));
            if (m == null || !(COUNTED.contains(st) || TENTATIVE.contains(st))) return;
            m.volunteers.add(new Item("Event Volunteers", nzOr(rs.getString(2), "Event"), rs.getString(3),
                    iso(rs.getDate(4)), statusLabel(st), TENTATIVE.contains(st)));
        });
    }

    private void kidsMinistry(String c, List<Integer> ids, Result r) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("c", c).addValue("ids", ids);
        jdbc.query("SELECT v.family_member_id, k.class_name, "
                 + "  COALESCE((SELECT string_agg(a.role_name, ', ' ORDER BY a.role_name) FROM km_volunteer_role_assignment a "
                 + "     WHERE a.volunteer_id = v.id AND a.delete_flag = false), v.role) AS roles, v.status "
                 + "FROM km_volunteer v LEFT JOIN km_classroom k ON k.id = v.classroom_id AND k.client_id = :c "
                 + "WHERE v.client_id = :c AND v.family_member_id IN (:ids) AND v.delete_flag = false AND v.inactive = false",
                p, rs -> {
            Person m = r.byId.get(rs.getInt(1));
            String st = lc(rs.getString(4));
            if (m == null || !(COUNTED.contains(st) || "active".equals(st) || TENTATIVE.contains(st))) return;
            String detail = join(" · ", rs.getString(3), rs.getString(2));
            m.volunteers.add(new Item("Kids Ministry", "Kids Ministry volunteer", detail, null,
                    statusLabel(st), TENTATIVE.contains(st)));
        });
    }

    private void prayerTeam(String c, List<Integer> ids, Result r) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("c", c).addValue("ids", ids);
        jdbc.query("SELECT family_member_id, role FROM prayer_volunteer "
                 + "WHERE client_id = :c AND family_member_id IN (:ids) AND delete_flag = false AND active = true", p, rs -> {
            Person m = r.byId.get(rs.getInt(1));
            if (m != null) m.volunteers.add(new Item("Prayer Team", "Prayer Team volunteer", rs.getString(2), null, "Active", false));
        });
    }

    private void followUps(String c, Result r, Access a) {
        // Name keys the members of these families can be identified by (first + last, and the
        // display form with nickname that the assignee picker saves).
        Map<String, Set<Integer>> localByKey = new HashMap<>();
        for (Person m : r.byId.values()) {
            localByKey.computeIfAbsent(m.nameKey(), k -> new HashSet<>()).add(m.id);
            localByKey.computeIfAbsent(key(m.display), k -> new HashSet<>()).add(m.id);
        }
        localByKey.remove("");
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("c", c)
                .addValue("ids", new ArrayList<>(r.byId.keySet()))
                .addValue("keys", localByKey.isEmpty() ? List.of("\u0000") : new ArrayList<>(localByKey.keySet()))
                .addValue("me", a.username() == null ? "" : a.username());
        String mine = a.followUpsAll() ? "" : "AND f.assigned_to = :me ";   // Users see only their own (page rule)
        List<Object[]> rows = jdbc.query(
                "SELECT f.id, f.title, f.status, f.due_date, f.assigned_member_id, f.assigned_to FROM follow_up f "
              + "WHERE f.client_id = :c AND COALESCE(f.delete_flag, false) = false "
              + "AND UPPER(COALESCE(f.status, 'PENDING')) IN ('PENDING', 'MISSED') " + mine
              + "AND (f.assigned_member_id IN (:ids) "
              + "     OR (f.assigned_member_id IS NULL AND LOWER(TRIM(f.assigned_to)) IN (:keys)))",
                p, (rs, i) -> new Object[] { rs.getLong(1), rs.getString(2), rs.getString(3), rs.getDate(4),
                        (Integer) rs.getObject(5), rs.getString(6) });
        if (rows.isEmpty()) return;
        Map<String, Integer> churchCount = churchNameCounts(c, rows.stream()
                .filter(x -> x[4] == null).map(x -> key((String) x[5])).filter(k -> !k.isEmpty()).toList());
        for (Object[] x : rows) {
            Item it = new Item("Follow-ups", nzOr((String) x[1], "Follow-up"), null, iso((Date) x[3]),
                    statusLabel(lc((String) x[2])), false);
            Integer linked = (Integer) x[4];
            if (linked != null) {
                Person m = r.byId.get(linked);
                if (m != null) m.followUps.add(it);
                continue;
            }
            String k = key((String) x[5]);
            Set<Integer> local = localByKey.getOrDefault(k, Set.of());
            boolean certain = local.size() == 1 && churchCount.getOrDefault(k, 0) == 1;
            for (Integer mid : local) {
                Person m = r.byId.get(mid);
                if (certain) m.followUps.add(it); else m.possibleFollowUps.add(it);
            }
        }
    }

    /** How many live members of the church answer to each name key (first + last, or display form). */
    private Map<String, Integer> churchNameCounts(String c, List<String> keys) {
        Map<String, Integer> out = new HashMap<>();
        if (keys.isEmpty()) return out;
        Map<String, Set<Integer>> hits = new HashMap<>();
        jdbc.query("SELECT m.id, m.first_name, m.last_name, m.other_name FROM family_member m "
                 + "JOIN family f ON f.id = m.family_id "
                 + "WHERE f.app_client_id = :c AND m.delete_flag = false AND f.delete_flag = false "
                 + "AND LOWER(TRIM(COALESCE(m.first_name,'') || ' ' || COALESCE(m.last_name,''))) IN (:keys) "
                 + "UNION SELECT m.id, m.first_name, m.last_name, m.other_name FROM family_member m "
                 + "JOIN family f ON f.id = m.family_id "
                 + "WHERE f.app_client_id = :c AND m.delete_flag = false AND f.delete_flag = false "
                 + "AND m.other_name IS NOT NULL AND TRIM(m.other_name) <> '' "
                 + "AND LOWER(TRIM(COALESCE(m.first_name,'') || ' ' || COALESCE(m.last_name,''))) IN (:bases)",
                new MapSqlParameterSource().addValue("c", c).addValue("keys", new ArrayList<>(new HashSet<>(keys)))
                        .addValue("bases", keys.stream().map(FamilyInvolvementService::baseOfDisplay).distinct().toList()),
                rs -> {
                    String first = rs.getString(2), last = rs.getString(3), other = rs.getString(4);
                    int id = rs.getInt(1);
                    hits.computeIfAbsent(key(nz(first) + " " + nz(last)), k -> new HashSet<>()).add(id);
                    String d = MemberNameUtil.display(first, last, other);
                    if (!blank(d)) hits.computeIfAbsent(key(d), k -> new HashSet<>()).add(id);
                });
        hits.forEach((k, v) -> out.put(k, v.size()));
        return out;
    }

    private void groups(String c, Result r) {
        Map<String, Set<Integer>> localByEmail = new HashMap<>(), localByName = new HashMap<>();
        for (Person m : r.byId.values()) {
            if (!m.email.isEmpty()) localByEmail.computeIfAbsent(m.email.toLowerCase(Locale.ROOT), k -> new HashSet<>()).add(m.id);
            if (!m.nameKey().isEmpty()) localByName.computeIfAbsent(m.nameKey(), k -> new HashSet<>()).add(m.id);
        }
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("c", c)
                .addValue("ids", new ArrayList<>(r.byId.keySet()))
                .addValue("emails", localByEmail.isEmpty() ? List.of("\u0000") : new ArrayList<>(localByEmail.keySet()))
                .addValue("names", localByName.isEmpty() ? List.of("\u0000") : new ArrayList<>(localByName.keySet()));
        List<Object[]> rows = jdbc.query(
                "SELECT g.id, g.group_name, gm.family_member_id, gm.email, gm.first_name, gm.last_name "
              + "FROM group_member gm JOIN app_group g ON g.id = gm.group_id "
              + "WHERE gm.app_client_id = :c AND g.app_client_id = :c AND gm.delete_flag = false AND g.delete_flag = false "
              + "AND (gm.family_member_id IN (:ids) OR (gm.family_member_id IS NULL AND ("
              + "     LOWER(TRIM(COALESCE(gm.email,''))) IN (:emails) "
              + "     OR LOWER(TRIM(COALESCE(gm.first_name,'') || ' ' || COALESCE(gm.last_name,''))) IN (:names))))",
                p, (rs, i) -> new Object[] { rs.getInt(1), rs.getString(2), (Integer) rs.getObject(3),
                        rs.getString(4), rs.getString(5), rs.getString(6) });
        if (rows.isEmpty()) return;
        Map<String, Integer> emailCount = churchEmailCounts(c, rows.stream()
                .filter(x -> x[2] == null).map(x -> nz((String) x[3]).trim().toLowerCase(Locale.ROOT))
                .filter(e -> !e.isEmpty()).distinct().toList());
        Map<Integer, Set<Integer>> seenGroups = new HashMap<>(), seenPossible = new HashMap<>();
        for (Object[] x : rows) {
            int gid = (Integer) x[0];
            Item it = new Item("Groups", nzOr((String) x[1], "Group"), null, null, null, false);
            Integer linked = (Integer) x[2];
            if (linked != null) {
                Person m = r.byId.get(linked);
                if (m != null && seenGroups.computeIfAbsent(m.id, k -> new HashSet<>()).add(gid)) m.groups.add(it);
                continue;
            }
            String email = nz((String) x[3]).trim().toLowerCase(Locale.ROOT);
            Set<Integer> byEmail = email.isEmpty() ? Set.of() : localByEmail.getOrDefault(email, Set.of());
            if (byEmail.size() == 1 && emailCount.getOrDefault(email, 0) == 1) {
                Person m = r.byId.get(byEmail.iterator().next());
                if (seenGroups.computeIfAbsent(m.id, k -> new HashSet<>()).add(gid)) m.groups.add(it);
                continue;
            }
            // Not certain: shared or unknown email. Show it only to members whose name matches.
            for (Integer mid : localByName.getOrDefault(key(nz((String) x[4]) + " " + nz((String) x[5])), Set.of())) {
                Person m = r.byId.get(mid);
                if (seenPossible.computeIfAbsent(m.id, k -> new HashSet<>()).add(gid)) m.possibleGroups.add(it);
            }
        }
        // A group already credited to a member is not repeated as "possible" for that member.
        for (Person m : r.byId.values()) {
            Set<String> have = new HashSet<>();
            for (Item g : m.groups) have.add(g.title);
            m.possibleGroups.removeIf(g -> have.contains(g.title));
        }
    }

    /** How many live members of the church use each email. */
    private Map<String, Integer> churchEmailCounts(String c, List<String> emails) {
        Map<String, Integer> out = new HashMap<>();
        if (emails.isEmpty()) return out;
        jdbc.query("SELECT LOWER(TRIM(m.email)), COUNT(DISTINCT m.id) FROM family_member m JOIN family f ON f.id = m.family_id "
                 + "WHERE f.app_client_id = :c AND m.delete_flag = false AND f.delete_flag = false "
                 + "AND LOWER(TRIM(m.email)) IN (:e) GROUP BY LOWER(TRIM(m.email))",
                new MapSqlParameterSource().addValue("c", c).addValue("e", emails),
                rs -> { out.put(rs.getString(1), rs.getInt(2)); });
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static String key(String s) { return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }

    /** "john mathew (johnny)" → "john mathew"; anything else unchanged. */
    static String baseOfDisplay(String k) {
        int i = k.indexOf(" (");
        return i > 0 && k.endsWith(")") ? k.substring(0, i) : k;
    }

    private static List<Integer> cleanIds(Collection<Integer> ids) {
        if (ids == null) return List.of();
        LinkedHashSet<Integer> s = new LinkedHashSet<>();
        for (Integer i : ids) if (i != null && i > 0) s.add(i);
        return new ArrayList<>(s).subList(0, Math.min(s.size(), MAX_FAMILIES));
    }

    private static String statusLabel(String lc) {
        if (lc == null || lc.isEmpty()) return null;
        return Character.toUpperCase(lc.charAt(0)) + lc.substring(1);
    }
    private static String iso(Date d) { return d == null ? null : d.toLocalDate().toString(); }
    private static String lc(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.ROOT); }
    private static String nz(String s) { return s == null ? "" : s; }
    private static String nzOr(String s, String fallback) { return blank(s) ? fallback : s.trim(); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String join(String sep, String... parts) {
        StringJoiner j = new StringJoiner(sep);
        for (String p : parts) if (!blank(p)) j.add(p.trim());
        return j.length() == 0 ? null : j.toString();
    }
}
