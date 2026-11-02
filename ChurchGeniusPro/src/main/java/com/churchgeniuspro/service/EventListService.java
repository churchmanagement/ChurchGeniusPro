package com.churchgeniuspro.service;

import com.churchgeniuspro.util.AppClock;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Events page tabs, decided on the server (2026-11-02).
 *
 * <p>An event's END date is its last day: the latest {@code church_event_day} date for a
 * "Multiple Days" event, otherwise {@code event_date}. It is UPCOMING when it has no date
 * or its end date is today or later, and PAST when its end date is before today, with
 * "today" in the church time zone ({@link AppClock#today()}, America/Chicago) — the page
 * used the browser's UTC date, so after ~6–7 PM Central today's events showed as Past.
 * Because the tab is worked out from the saved date on every request, an edited event
 * simply appears in the right tab the next time the lists load. Deleted events (by a
 * person or by the retention job) never appear.
 *
 * <p>Past is paged 10 at a time: by end date (newest or oldest first) with a keyset cursor
 * {@code "<yyyy-MM-dd>|<id>"}, so an event edited or deleted while the user pages cannot
 * shift the next page; by name with an offset cursor {@code "o<n>"} (the page drops any
 * id it already shows). Every query is filtered by the session's church.
 */
@Service
public class EventListService {

    /** Last day of an event (Multiple Days → latest day row, else event_date). */
    static final String END_DATE =
            "(CASE WHEN e.event_type = 'Multiple Days' "
          + "      THEN COALESCE((SELECT MAX(d.event_date) FROM church_event_day d WHERE d.event_id = e.id), e.event_date) "
          + "      ELSE e.event_date END)";

    public static final int MAX_LIMIT = 50;

    private final NamedParameterJdbcTemplate jdbc;
    private final ChurchEventService events;

    public EventListService(NamedParameterJdbcTemplate jdbc, ChurchEventService events) {
        this.jdbc = jdbc;
        this.events = events;
    }

    /** One page of Past events plus the cursor for the next one. */
    public record PastPage(List<Map<String, Object>> rows, boolean hasMore, String nextCursor) {}

    /** Thrown for a malformed cursor or sort (the controller answers 400). */
    public static class BadRequest extends RuntimeException {
        public BadRequest(String m) { super(m); }
    }

    /** Upcoming & Today: every live event with no date or ending today or later (same row shape as before). */
    public List<Map<String, Object>> upcoming(String clientId) {
        if (blank(clientId)) return List.of();
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("client", clientId).addValue("today", AppClock.today());
        List<Integer> ids = jdbc.queryForList(
                "SELECT e.id FROM church_event e WHERE e.app_client_id = :client AND e.delete_flag = false "
              + "AND (" + END_DATE + " IS NULL OR " + END_DATE + " >= :today) "
              + "ORDER BY e.created_date DESC, e.id DESC", p, Integer.class);
        return events.rowsFor(ids, clientId);
    }

    /**
     * Past events, {@code limit} at a time. sort: {@code newest} (default — most recent
     * end date first), {@code oldest}, or {@code name}. q matches name, event code or city
     * (case-insensitive); type is the exact event type.
     */
    public PastPage past(String clientId, String q, String type, String sort, String cursor, int limit) {
        if (blank(clientId)) return new PastPage(List.of(), false, null);
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        String s = (sort == null || sort.isBlank()) ? "newest" : sort.trim().toLowerCase();
        if (!s.equals("newest") && !s.equals("oldest") && !s.equals("name")) throw new BadRequest("Invalid sort");

        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("client", clientId).addValue("today", AppClock.today()).addValue("lim", size + 1);
        StringBuilder sql = new StringBuilder(
                "SELECT e.id, " + END_DATE + " AS end_date FROM church_event e "
              + "WHERE e.app_client_id = :client AND e.delete_flag = false "
              + "AND " + END_DATE + " < :today ");
        if (!blank(q)) {
            sql.append("AND (LOWER(e.event_name) LIKE :q ESCAPE '!' OR LOWER(COALESCE(e.event_code,'')) LIKE :q ESCAPE '!' "
                     + "OR LOWER(COALESCE(e.city,'')) LIKE :q ESCAPE '!') ");
            p.addValue("q", likePattern(q));
        }
        if (!blank(type)) { sql.append("AND e.event_type = :type "); p.addValue("type", type.trim()); }

        int offset = 0;
        if (s.equals("name")) {
            if (!blank(cursor)) {
                if (!cursor.startsWith("o")) throw new BadRequest("Invalid cursor");
                try { offset = Integer.parseInt(cursor.substring(1)); } catch (NumberFormatException e) { throw new BadRequest("Invalid cursor"); }
                if (offset < 0) throw new BadRequest("Invalid cursor");
            }
            sql.append("ORDER BY LOWER(e.event_name) ASC, e.id ASC LIMIT :lim OFFSET :off");
            p.addValue("off", offset);
        } else {
            boolean desc = s.equals("newest");
            if (!blank(cursor)) {
                String[] parts = cursor.split("\\|", 2);
                if (parts.length != 2) throw new BadRequest("Invalid cursor");
                LocalDate cd; int cid;
                try { cd = LocalDate.parse(parts[0]); cid = Integer.parseInt(parts[1]); }
                catch (RuntimeException e) { throw new BadRequest("Invalid cursor"); }
                sql.append(desc
                        ? "AND (" + END_DATE + " < :cd OR (" + END_DATE + " = :cd AND e.id < :cid)) "
                        : "AND (" + END_DATE + " > :cd OR (" + END_DATE + " = :cd AND e.id > :cid)) ");
                p.addValue("cd", cd).addValue("cid", cid);
            }
            sql.append(desc ? "ORDER BY end_date DESC, e.id DESC LIMIT :lim" : "ORDER BY end_date ASC, e.id ASC LIMIT :lim");
        }

        List<Object[]> found = jdbc.query(sql.toString(), p, (rs, i) -> new Object[] { rs.getInt(1), rs.getDate(2) });
        boolean hasMore = found.size() > size;
        List<Object[]> page = hasMore ? found.subList(0, size) : found;
        List<Integer> ids = new ArrayList<>();
        for (Object[] r : page) ids.add((Integer) r[0]);

        String next = null;
        if (hasMore && !page.isEmpty()) {
            Object[] last = page.get(page.size() - 1);
            next = s.equals("name") ? "o" + (offset + page.size())
                                    : ((java.sql.Date) last[1]).toLocalDate() + "|" + last[0];
        }
        return new PastPage(events.rowsFor(ids, clientId), hasMore, next);
    }

    /** Stat cards: total live events, upcoming (same rule as the tab) and multi-day. */
    public Map<String, Object> stats(String clientId) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (blank(clientId)) { out.put("total", 0); out.put("upcoming", 0); out.put("past", 0); out.put("multi", 0); return out; }
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("client", clientId).addValue("today", AppClock.today());
        Map<String, Object> r = jdbc.queryForMap(
                "SELECT COUNT(*) AS total, "
              + "COUNT(*) FILTER (WHERE " + END_DATE + " IS NULL OR " + END_DATE + " >= :today) AS upcoming, "
              + "COUNT(*) FILTER (WHERE " + END_DATE + " < :today) AS past, "
              + "COUNT(*) FILTER (WHERE e.event_type = 'Multiple Days') AS multi "
              + "FROM church_event e WHERE e.app_client_id = :client AND e.delete_flag = false", p);
        for (String k : new String[] { "total", "upcoming", "past", "multi" }) out.put(k, ((Number) r.get(k)).longValue());
        return out;
    }

    /** Case-insensitive "contains" pattern with LIKE wildcards escaped by '!'. */
    static String likePattern(String q) {
        String t = q.trim().toLowerCase().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + t + "%";
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
