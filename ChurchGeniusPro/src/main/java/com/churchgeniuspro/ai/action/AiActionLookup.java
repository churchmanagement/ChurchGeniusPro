package com.churchgeniuspro.ai.action;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * AI Phase 4 — tenant-scoped name lookups the tools share. Every query carries the church id on every table it
 * reads; names are compared case-insensitively on the whole string first, then as a prefix / contains fallback,
 * and the caller treats more than one hit as "ask which one". Only live rows (not deleted, not inactive) match.
 */
@Component
public class AiActionLookup {

    public record Hit(long id, String label, String extra) {}

    private final NamedParameterJdbcTemplate jdbc;

    public AiActionLookup(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    static String norm(String s) { return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }

    /** Live members whose "first last" (or nickname + last) equals the name; a last-name-only query matches the whole family. */
    public List<Hit> members(String cid, String name) {
        String n = norm(name);
        if (n.isBlank()) return List.of();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("cid", cid).addValue("n", n).addValue("like", "%" + n + "%");
        String base = "SELECT fm.id, TRIM(COALESCE(fm.first_name,'') || ' ' || COALESCE(fm.last_name,'')) AS label, f.id AS family_id "
                + "FROM family_member fm JOIN family f ON fm.family_id = f.id AND f.app_client_id = :cid "
                + "WHERE fm.delete_flag = false AND f.delete_flag = false AND fm.inactive = false AND f.inactive = false ";
        List<Hit> exact = jdbc.query(base + "AND (LOWER(TRIM(COALESCE(fm.first_name,'') || ' ' || COALESCE(fm.last_name,''))) = :n "
                + "OR LOWER(TRIM(COALESCE(fm.other_name,'') || ' ' || COALESCE(fm.last_name,''))) = :n) ORDER BY fm.last_name, fm.first_name LIMIT 6", p, this::hit);
        if (!exact.isEmpty()) return exact;
        return jdbc.query(base + "AND LOWER(TRIM(COALESCE(fm.first_name,'') || ' ' || COALESCE(fm.last_name,''))) LIKE :like ORDER BY fm.last_name, fm.first_name LIMIT 6", p, this::hit);
    }

    /** One live member of this church by id, or null. */
    public Hit memberById(String cid, long id) {
        List<Hit> r = jdbc.query("SELECT fm.id, TRIM(COALESCE(fm.first_name,'') || ' ' || COALESCE(fm.last_name,'')) AS label, f.id AS family_id "
                + "FROM family_member fm JOIN family f ON fm.family_id = f.id AND f.app_client_id = :cid WHERE fm.id = :id AND fm.delete_flag = false AND f.delete_flag = false",
                new MapSqlParameterSource().addValue("cid", cid).addValue("id", id), this::hit);
        return r.isEmpty() ? null : r.get(0);
    }

    /** One live row by id inside this church — the re-resolve at confirm time uses these instead of names. */
    public Hit groupById(String cid, long id) {
        return one("SELECT g.id, g.group_name, '' FROM app_group g WHERE g.app_client_id = :cid AND g.id = :id AND g.delete_flag = false", cid, id);
    }
    public Hit familyById(String cid, long id) {
        return one("SELECT f.id, TRIM(COALESCE(fm.first_name,'') || ' ' || COALESCE(fm.last_name,'')) || ' family', COALESCE(fm.last_name,'') FROM family f "
                + "LEFT JOIN family_member fm ON fm.family_id = f.id AND fm.delete_flag = false AND (LOWER(TRIM(COALESCE(fm.role,''))) LIKE 'head%' OR LOWER(TRIM(COALESCE(fm.role,''))) IN ('primary','primary/head')) "
                + "WHERE f.app_client_id = :cid AND f.id = :id AND f.delete_flag = false ORDER BY fm.id LIMIT 1", cid, id);
    }
    public Hit volunteerRoleById(String cid, long id) {
        return one("SELECT r.id, r.role_name, COALESCE(r.ministry,'') FROM volunteer_role r WHERE r.app_client_id = :cid AND r.id = :id AND r.delete_flag = false", cid, id);
    }
    public Hit meetingTypeById(String cid, long id) {
        return one("SELECT t.id, t.type_name, '' FROM meeting_type t WHERE t.app_client_id = :cid AND t.id = :id AND t.delete_flag = false", cid, id);
    }
    private Hit one(String sql, String cid, long id) {
        List<Hit> r = jdbc.query(sql, new MapSqlParameterSource().addValue("cid", cid).addValue("id", id), this::hit);
        return r.isEmpty() ? null : r.get(0);
    }

    /** Live families by family name ("the Smiths" → Smith), then the primary member's last name. */
    public List<Hit> families(String cid, String name) {
        String n = norm(name).replaceAll("^the ", "").replaceAll("s$", "");
        if (n.isBlank()) return List.of();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("cid", cid).addValue("n", n).addValue("like", n + "%");
        String sql = "SELECT f.id, TRIM(COALESCE(fm.first_name,'') || ' ' || COALESCE(fm.last_name,'')) AS label, COALESCE(fm.last_name,'') AS family_id "
                + "FROM family f JOIN family_member fm ON fm.family_id = f.id AND fm.delete_flag = false "
                + "AND (LOWER(TRIM(COALESCE(fm.role,''))) LIKE 'head%' OR LOWER(TRIM(COALESCE(fm.role,''))) IN ('primary','primary/head')) "
                + "WHERE f.app_client_id = :cid AND f.delete_flag = false AND f.inactive = false AND (LOWER(TRIM(COALESCE(fm.last_name,''))) = :n OR LOWER(TRIM(COALESCE(fm.last_name,''))) LIKE :like) "
                + "ORDER BY fm.last_name, fm.first_name LIMIT 6";
        return jdbc.query(sql, p, (rs, i) -> new Hit(rs.getLong(1), rs.getString(2) + " family", rs.getString(3)));
    }

    /** Live groups by name (exact, then contains). */
    public List<Hit> groups(String cid, String name) {
        String n = norm(name).replaceAll("^the ", "");
        if (n.isBlank()) return List.of();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("cid", cid).addValue("n", n).addValue("like", "%" + n + "%");
        String base = "SELECT g.id, g.group_name AS label, '' AS family_id FROM app_group g WHERE g.app_client_id = :cid AND g.delete_flag = false ";
        List<Hit> exact = jdbc.query(base + "AND LOWER(TRIM(g.group_name)) = :n LIMIT 6", p, this::hit);
        if (!exact.isEmpty()) return exact;
        // "Welcome team" vs "Welcome Team Ministry": contains, but also without a trailing group/team word
        List<Hit> like = jdbc.query(base + "AND LOWER(TRIM(g.group_name)) LIKE :like ORDER BY g.group_name LIMIT 6", p, this::hit);
        if (!like.isEmpty()) return like;
        String stem = n.replaceAll("\\s+(group|team|ministry|class)$", "");
        if (stem.equals(n)) return like;
        return jdbc.query(base + "AND LOWER(TRIM(g.group_name)) LIKE :like2 ORDER BY g.group_name LIMIT 6", p.addValue("like2", "%" + stem + "%"), this::hit);
    }

    /** Is this live member already in the group (by member link or e-mail)? */
    public boolean inGroup(String cid, long groupId, long memberId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM group_member gm JOIN app_group g ON g.id = gm.group_id WHERE g.app_client_id = :cid AND gm.group_id = :g AND gm.delete_flag = false "
                + "AND (gm.family_member_id = :m OR (gm.email IS NOT NULL AND TRIM(gm.email) <> '' AND LOWER(TRIM(gm.email)) = (SELECT LOWER(TRIM(fm.email)) FROM family_member fm JOIN family f ON f.id = fm.family_id "
                + "WHERE fm.id = :m AND f.app_client_id = :cid AND fm.email IS NOT NULL AND TRIM(fm.email) <> '')))",
                new MapSqlParameterSource().addValue("cid", cid).addValue("g", groupId).addValue("m", memberId), Long.class);
        return n != null && n > 0;
    }

    /** A directory person offered to the user: id, "First Last", e-mail (may be blank). */
    public record Person(long id, String firstName, String lastName, String email) {
        public String name() { return (firstName + " " + lastName).trim(); }
    }

    /**
     * 2026-11-11 — live people of THIS church whose first name, last name, nickname, full name or e-mail contains
     * the text (the Groups page's own "pick a member" search, by the same fields). Up to {@code limit} rows.
     */
    public List<Person> searchPeople(String cid, String text, int limit) {
        String n = norm(text);
        if (n.isBlank() || cid == null) return List.of();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("cid", cid).addValue("like", "%" + n.replace("%", "").replace("_", "") + "%").addValue("lim", Math.max(1, Math.min(limit, 25)));
        return jdbc.query("SELECT fm.id, COALESCE(fm.first_name,''), COALESCE(fm.last_name,''), COALESCE(fm.email,'') "
                + "FROM family_member fm JOIN family f ON fm.family_id = f.id AND f.app_client_id = :cid "
                + "WHERE fm.delete_flag = false AND f.delete_flag = false AND fm.inactive = false AND f.inactive = false "
                + "AND (LOWER(COALESCE(fm.first_name,'')) LIKE :like OR LOWER(COALESCE(fm.last_name,'')) LIKE :like OR LOWER(COALESCE(fm.other_name,'')) LIKE :like "
                + "OR LOWER(TRIM(COALESCE(fm.first_name,'') || ' ' || COALESCE(fm.last_name,''))) LIKE :like OR LOWER(COALESCE(fm.email,'')) LIKE :like) "
                + "ORDER BY fm.last_name, fm.first_name, fm.id LIMIT :lim", p,
                (rs, i) -> new Person(rs.getLong(1), rs.getString(2).trim(), rs.getString(3).trim(), rs.getString(4).trim()));
    }

    /** One live person of this church by id (name parts + e-mail), or null. */
    public Person personById(String cid, long id) {
        List<Person> r = jdbc.query("SELECT fm.id, COALESCE(fm.first_name,''), COALESCE(fm.last_name,''), COALESCE(fm.email,'') "
                + "FROM family_member fm JOIN family f ON fm.family_id = f.id AND f.app_client_id = :cid "
                + "WHERE fm.id = :id AND fm.delete_flag = false AND f.delete_flag = false AND fm.inactive = false AND f.inactive = false",
                new MapSqlParameterSource().addValue("cid", cid).addValue("id", id),
                (rs, i) -> new Person(rs.getLong(1), rs.getString(2).trim(), rs.getString(3).trim(), rs.getString(4).trim()));
        return r.isEmpty() ? null : r.get(0);
    }

    /** Is someone with this name (and the same e-mail, when one is given) already in the group? Used for people who are not in the directory. */
    public boolean nameInGroup(String cid, long groupId, String firstName, String lastName, String email) {
        String em = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM group_member gm JOIN app_group g ON g.id = gm.group_id WHERE g.app_client_id = :cid AND gm.group_id = :g AND gm.delete_flag = false "
                + "AND LOWER(TRIM(gm.first_name)) = :fn AND LOWER(TRIM(gm.last_name)) = :ln AND (:em = '' OR LOWER(TRIM(COALESCE(gm.email,''))) = :em)",
                new MapSqlParameterSource().addValue("cid", cid).addValue("g", groupId).addValue("fn", norm(firstName)).addValue("ln", norm(lastName)).addValue("em", em), Long.class);
        return n != null && n > 0;
    }

    /** Members currently in the group. */
    public int groupSize(String cid, long groupId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM group_member gm JOIN app_group g ON g.id = gm.group_id WHERE g.app_client_id = :cid AND gm.group_id = :g AND gm.delete_flag = false",
                new MapSqlParameterSource().addValue("cid", cid).addValue("g", groupId), Long.class);
        return n == null ? 0 : n.intValue();
    }

    /** Member contact fields the group row stores (e-mail only). */
    public String memberEmail(String cid, long memberId) {
        List<String> r = jdbc.query("SELECT fm.email FROM family_member fm JOIN family f ON f.id = fm.family_id WHERE fm.id = :m AND f.app_client_id = :cid",
                new MapSqlParameterSource().addValue("cid", cid).addValue("m", memberId), (rs, i) -> rs.getString(1));
        return r.isEmpty() ? null : r.get(0);
    }

    /** Volunteer roles by name ("usher", "greeter"); exact, then contains. */
    public List<Hit> volunteerRoles(String cid, String name) {
        String n = norm(name).replaceAll("s$", "");
        if (n.isBlank()) return List.of();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("cid", cid).addValue("n", n).addValue("like", "%" + n + "%");
        String base = "SELECT r.id, r.role_name AS label, COALESCE(r.ministry,'') AS family_id FROM volunteer_role r WHERE r.app_client_id = :cid AND r.delete_flag = false ";
        List<Hit> exact = jdbc.query(base + "AND (LOWER(TRIM(r.role_name)) = :n OR LOWER(TRIM(r.role_name)) = :n || 's') LIMIT 6", p, this::hit);
        if (!exact.isEmpty()) return exact;
        return jdbc.query(base + "AND LOWER(TRIM(r.role_name)) LIKE :like ORDER BY r.role_name LIMIT 6", p, this::hit);
    }

    /** Open follow-ups (PENDING / MISSED) whose title or linked label contains the words. */
    public List<Hit> openFollowUps(String cid, String words, String onlyAssignedTo) {
        String n = norm(words);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("cid", cid).addValue("like", "%" + n + "%").addValue("who", onlyAssignedTo);
        String sql = "SELECT f.id, f.title AS label, COALESCE(f.linked_label,'') AS family_id FROM follow_up f WHERE f.client_id = :cid AND f.delete_flag = false "
                + "AND f.status IN ('PENDING','MISSED') " + (onlyAssignedTo != null ? "AND f.assigned_to = :who " : "")
                + (n.isBlank() ? "" : "AND (LOWER(f.title) LIKE :like OR LOWER(COALESCE(f.linked_label,'')) LIKE :like OR LOWER(COALESCE(f.description,'')) LIKE :like) ")
                + "ORDER BY f.created_at DESC LIMIT 6";
        return jdbc.query(sql, p, this::hit);
    }

    /** Meeting types by name (exact, then contains). */
    public List<Hit> meetingTypes(String cid, String name) {
        String n = norm(name).replaceAll("\\s+meeting$", "");
        if (n.isBlank()) return List.of();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("cid", cid).addValue("n", n).addValue("like", "%" + n + "%");
        String base = "SELECT t.id, t.type_name AS label, '' AS family_id FROM meeting_type t WHERE t.app_client_id = :cid AND t.delete_flag = false ";
        List<Hit> exact = jdbc.query(base + "AND LOWER(TRIM(t.type_name)) = :n LIMIT 6", p, this::hit);
        if (!exact.isEmpty()) return exact;
        return jdbc.query(base + "AND LOWER(TRIM(t.type_name)) LIKE :like ORDER BY t.type_name LIMIT 6", p, this::hit);
    }

    private Hit hit(java.sql.ResultSet rs, int i) throws java.sql.SQLException { return new Hit(rs.getLong(1), rs.getString(2), rs.getString(3)); }
}
