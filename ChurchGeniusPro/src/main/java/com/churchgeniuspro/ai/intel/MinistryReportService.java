package com.churchgeniuspro.ai.intel;

import com.churchgeniuspro.repository.MemberEngagementSnapshotRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;

/**
 * AI Phase 2 — the monthly Ministry Report: one month of a church's attendance, visitors, follow-ups, giving
 * (finance viewers only), groups, volunteers and engagement signals, each compared with the previous month and
 * the same month a year earlier, plus a short template narrative. Every number comes from a tenant-scoped SQL
 * statement that can be re-run by hand; no model is involved and no person is named.
 */
@Service
public class MinistryReportService {

    /** What the viewer may see: finance = Income page rule; attendance/groups = the cross-reference rules; volunteers = Volunteers page; followUps = Follow-ups page. */
    public record Access(boolean attendance, boolean finance, boolean groups, boolean volunteers, boolean followUps) {
        public static Access all() { return new Access(true, true, true, true, true); }
        public boolean any() { return attendance || finance || groups || volunteers || followUps; }
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final MemberEngagementSnapshotRepository snapshots;

    public MinistryReportService(NamedParameterJdbcTemplate jdbc, MemberEngagementSnapshotRepository snapshots) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
    }

    /** Facts for one month; the giving block is present only when finance is allowed. */
    public Map<String, Object> monthly(String cid, int year, int month, Access access, LocalDate today) {
        YearMonth ym = YearMonth.of(year, month);
        Access a = access == null ? new Access(false, false, false, false, false) : access;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", year); out.put("month", month);
        out.put("label", ym.getMonth().getDisplayName(TextStyle.FULL, Locale.US) + " " + year);
        out.put("partial", today != null && !ym.atEndOfMonth().isBefore(today));   // the month is not over yet
        out.put("access", Map.of("attendance", a.attendance(), "finance", a.finance(), "groups", a.groups(), "volunteers", a.volunteers(), "followUps", a.followUps()));

        Map<String, Object> cur = period(cid, ym, a), prev = period(cid, ym.minusMonths(1), a), lastYear = period(cid, ym.minusYears(1), a);
        out.put("current", cur);
        out.put("previous", prev);
        out.put("lastYear", lastYear);
        out.put("previousLabel", ym.minusMonths(1).getMonth().getDisplayName(TextStyle.SHORT, Locale.US) + " " + ym.minusMonths(1).getYear());
        out.put("lastYearLabel", ym.minusYears(1).getMonth().getDisplayName(TextStyle.SHORT, Locale.US) + " " + ym.minusYears(1).getYear());

        if (a.attendance()) {
            Map<String, Object> eng = engagementAt(cid, ym.atEndOfMonth());
            eng.put("previous", engagementAt(cid, ym.minusMonths(1).atEndOfMonth()).get("counts"));
            out.put("engagement", eng);
        }
        out.put("narrative", narrative(out, a));
        out.put("definitions", definitions(a));
        return out;
    }

    /* ───────────────────────────── one period ───────────────────────────── */

    @SuppressWarnings("unchecked")
    Map<String, Object> period(String cid, YearMonth ym, Access a) {
        LocalDate from = ym.atDay(1), to = ym.atEndOfMonth();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("cid", cid).addValue("from", from).addValue("to", to);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from.toString()); m.put("to", to.toString());

        if (a.attendance()) {
            Map<String, Object> att = jdbc.queryForMap(
                    "SELECT COUNT(*) AS checkins, COUNT(DISTINCT attendance_date) AS days, "
                            + "COUNT(DISTINCT CASE WHEN family_member_id IS NOT NULL THEN family_member_id END) AS members, "
                            + "COUNT(DISTINCT CASE WHEN visitor_id IS NOT NULL THEN visitor_id END) AS visitors "
                            + "FROM attendance_record WHERE client_id = :cid AND delete_flag = false AND (status IS NULL OR status IN ('PRESENT', 'LATE')) "
                            + "AND attendance_date BETWEEN :from AND :to", p);
            long checkins = num(att.get("checkins")), days = num(att.get("days"));
            m.put("attendance", Map.of("checkins", checkins, "days", days, "members", num(att.get("members")), "visitors", num(att.get("visitors")),
                    "avgPerDay", days == 0 ? 0 : Math.round((double) checkins / days)));

            Map<String, Object> vis = jdbc.queryForMap(
                    "SELECT (SELECT COUNT(*) FROM attendance_visitor v WHERE v.client_id = :cid AND v.delete_flag = false AND v.first_visit_date BETWEEN :from AND :to) AS first_time, "
                            + "(SELECT COUNT(DISTINCT ar.visitor_id) FROM attendance_record ar JOIN attendance_visitor v ON v.id = ar.visitor_id AND v.client_id = :cid "
                            + "WHERE ar.client_id = :cid AND ar.delete_flag = false AND ar.visitor_id IS NOT NULL AND ar.attendance_date BETWEEN :from AND :to AND v.first_visit_date < :from) AS returning", p);
            m.put("visitors", Map.of("firstTime", num(vis.get("first_time")), "returning", num(vis.get("returning"))));
        }
        if (a.followUps()) {
            Map<String, Object> fu = jdbc.queryForMap(
                    "SELECT COUNT(*) FILTER (WHERE CAST(created_at AS date) BETWEEN :from AND :to) AS created, "
                            + "COUNT(*) FILTER (WHERE status = 'COMPLETED' AND CAST(COALESCE(updated_at, created_at) AS date) BETWEEN :from AND :to) AS completed, "
                            + "COUNT(*) FILTER (WHERE status IN ('PENDING', 'MISSED') AND CAST(created_at AS date) <= :to) AS open_at_end "
                            + "FROM follow_up WHERE client_id = :cid AND delete_flag = false", p);
            m.put("followUps", Map.of("created", num(fu.get("created")), "completed", num(fu.get("completed")), "openAtEnd", num(fu.get("open_at_end"))));
        }
        if (a.finance()) {
            Map<String, Object> g = jdbc.queryForMap(
                    "SELECT COALESCE(SUM(amount), 0) AS total, COUNT(*) AS gifts, COUNT(DISTINCT member_id) AS givers "
                            + "FROM income WHERE app_client_id = :cid AND delete_flag = false AND income_date BETWEEN :from AND :to", p);
            List<Map<String, Object>> funds = jdbc.query(
                    "SELECT COALESCE(ss.source_name, 'Unassigned') AS fund, SUM(i.amount) AS total, COUNT(*) AS gifts FROM income i "
                            + "LEFT JOIN sub_source ss ON ss.id = i.sub_source_id AND ss.app_client_id = :cid "
                            + "WHERE i.app_client_id = :cid AND i.delete_flag = false AND i.income_date BETWEEN :from AND :to "
                            + "GROUP BY COALESCE(ss.source_name, 'Unassigned') ORDER BY SUM(i.amount) DESC, 1 LIMIT 8", p,
                    (rs, n) -> { Map<String, Object> f = new LinkedHashMap<>(); f.put("fund", rs.getString("fund")); f.put("total", money(rs.getBigDecimal("total"))); f.put("gifts", rs.getLong("gifts")); return f; });
            Map<String, Object> giving = new LinkedHashMap<>();
            giving.put("total", money((BigDecimal) toBig(g.get("total")))); giving.put("gifts", num(g.get("gifts"))); giving.put("givers", num(g.get("givers")));
            giving.put("funds", funds);
            m.put("giving", giving);
        }
        if (a.groups()) {
            Map<String, Object> gr = jdbc.queryForMap(
                    "SELECT (SELECT COUNT(*) FROM app_group g WHERE g.app_client_id = :cid AND g.delete_flag = false) AS groups_n, "
                            + "(SELECT COUNT(DISTINCT COALESCE(CAST(gm.family_member_id AS varchar), LOWER(TRIM(gm.email)))) FROM group_member gm JOIN app_group g ON g.id = gm.group_id "
                            + "WHERE g.app_client_id = :cid AND g.delete_flag = false AND gm.delete_flag = false) AS people", p);
            m.put("groups", Map.of("groups", num(gr.get("groups_n")), "people", num(gr.get("people"))));
        }
        if (a.volunteers()) {
            Map<String, Object> v = jdbc.queryForMap(
                    "SELECT COUNT(*) AS assignments, COUNT(DISTINCT family_member_id) AS volunteers, "
                            + "COUNT(*) FILTER (WHERE LOWER(COALESCE(assignment_status, 'pending')) = 'declined') AS declined "
                            + "FROM volunteer_assignment WHERE app_client_id = :cid AND delete_flag = false AND event_date BETWEEN :from AND :to", p);
            m.put("volunteers", Map.of("assignments", num(v.get("assignments")), "volunteers", num(v.get("volunteers")), "declined", num(v.get("declined"))));
        }
        return m;
    }

    Map<String, Object> engagementAt(String cid, LocalDate onOrBefore) {
        LocalDate day = snapshots.latestDateOnOrBefore(cid, onOrBefore);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshotDate", day == null ? null : day.toString());
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String s : EngagementSignalRules.SIGNALS) counts.put(s, 0L);
        if (day != null) for (Object[] r : snapshots.countBySignal(cid, day)) counts.put(String.valueOf(r[0]), ((Number) r[1]).longValue());
        out.put("counts", counts);
        return out;
    }

    /* ───────────────────────────── narrative ───────────────────────────── */

    @SuppressWarnings("unchecked")
    List<String> narrative(Map<String, Object> r, Access a) {
        List<String> n = new ArrayList<>();
        Map<String, Object> cur = (Map<String, Object>) r.get("current"), prev = (Map<String, Object>) r.get("previous"), ly = (Map<String, Object>) r.get("lastYear");
        String label = (String) r.get("label");
        if (Boolean.TRUE.equals(r.get("partial"))) n.add(label + " is not over yet — these figures cover the days recorded so far.");
        if (a.attendance()) {
            Map<String, Object> at = (Map<String, Object>) cur.get("attendance"), pa = (Map<String, Object>) prev.get("attendance"), la = (Map<String, Object>) ly.get("attendance");
            long checkins = num(at.get("checkins")), days = num(at.get("days"));
            if (days == 0) n.add("No attendance was recorded in " + label + ".");
            else n.add(checkins + " check-ins on " + days + " day" + (days == 1 ? "" : "s") + " (about " + at.get("avgPerDay") + " per day), "
                    + at.get("members") + " different members and " + at.get("visitors") + " visitors" + compare(checkins, num(pa.get("checkins")), (String) r.get("previousLabel"))
                    + compare(checkins, num(la.get("checkins")), (String) r.get("lastYearLabel")) + ".");
            Map<String, Object> vi = (Map<String, Object>) cur.get("visitors");
            long ft = num(vi.get("firstTime")), ret = num(vi.get("returning"));
            if (ft + ret > 0) n.add(ft + " first-time visitor" + (ft == 1 ? "" : "s") + " and " + ret + " returning visitor" + (ret == 1 ? "" : "s") + ".");
        }
        if (a.followUps()) {
            Map<String, Object> f = (Map<String, Object>) cur.get("followUps");
            n.add(num(f.get("created")) + " follow-up task" + (num(f.get("created")) == 1 ? "" : "s") + " created, " + num(f.get("completed")) + " completed; "
                    + num(f.get("openAtEnd")) + " still open at month end.");
        }
        if (a.finance()) {
            Map<String, Object> g = (Map<String, Object>) cur.get("giving"), pg = (Map<String, Object>) prev.get("giving"), lg = (Map<String, Object>) ly.get("giving");
            n.add("Giving recorded: $" + g.get("total") + " across " + g.get("gifts") + " gift" + (num(g.get("gifts")) == 1 ? "" : "s") + " from " + g.get("givers") + " giver"
                    + (num(g.get("givers")) == 1 ? "" : "s") + compareMoney((String) g.get("total"), (String) pg.get("total"), (String) r.get("previousLabel"))
                    + compareMoney((String) g.get("total"), (String) lg.get("total"), (String) r.get("lastYearLabel")) + ".");
        }
        if (a.groups()) {
            Map<String, Object> g = (Map<String, Object>) cur.get("groups");
            n.add(g.get("groups") + " live group" + (num(g.get("groups")) == 1 ? "" : "s") + " with " + g.get("people") + " people listed.");
        }
        if (a.volunteers()) {
            Map<String, Object> v = (Map<String, Object>) cur.get("volunteers");
            n.add(v.get("volunteers") + " volunteer" + (num(v.get("volunteers")) == 1 ? "" : "s") + " served in " + v.get("assignments") + " assignment"
                    + (num(v.get("assignments")) == 1 ? "" : "s") + (num(v.get("declined")) > 0 ? " (" + v.get("declined") + " declined)" : "") + ".");
        }
        if (a.attendance() && r.get("engagement") != null) {
            Map<String, Object> e = (Map<String, Object>) r.get("engagement");
            Map<String, Long> c = (Map<String, Long>) e.get("counts");
            if (e.get("snapshotDate") == null) n.add("No engagement snapshot exists yet — signals appear after the first nightly run.");
            else n.add("Engagement (snapshot " + e.get("snapshotDate") + "): " + c.get("ENGAGED") + " engaged, " + c.get("WATCH") + " to watch, " + c.get("AT_RISK") + " at risk, "
                    + c.get("NEW") + " new, " + c.get("QUIET") + " quiet.");
        }
        return n;
    }

    public static String compare(long now, long then, String label) {
        if (then == 0) return "";
        long pct = Math.round((now - then) * 100.0 / then);
        return pct == 0 ? ", level with " + label : (pct > 0 ? ", up " : ", down ") + Math.abs(pct) + "% on " + label;
    }

    public static String compareMoney(String now, String then, String label) {
        try {
            BigDecimal a = new BigDecimal(now.replace(",", "")), b = new BigDecimal(then.replace(",", ""));
            if (b.signum() == 0) return "";
            long pct = a.subtract(b).multiply(BigDecimal.valueOf(100)).divide(b, 0, RoundingMode.HALF_UP).longValue();
            return pct == 0 ? ", level with " + label : (pct > 0 ? ", up " : ", down ") + Math.abs(pct) + "% on " + label;
        } catch (Exception e) { return ""; }
    }

    public static List<String> definitions(Access a) {
        List<String> d = new ArrayList<>();
        if (a.attendance()) {
            d.add("Attendance: Present or Late check-ins dated in the month; days = distinct attendance dates; members and visitors are distinct people.");
            d.add("First-time visitors: visitor records whose first visit falls in the month; returning: visitors who checked in during the month and first visited earlier.");
            d.add("Engagement: the nightly snapshot closest to the month end. ENGAGED = recent attendance; WATCH = attendance or giving recently stopped, or no attendance in a year; "
                    + "AT_RISK = a regular attender absent for six weeks, or gave last year but not this one with no recent attendance; NEW = first attendance within 90 days; QUIET = nothing in 365 days.");
            d.add("Engagement without attendance: Where attendance is not recorded (the church took none in the last 90 days, or the person has never been checked in), the signal uses giving, groups and serving only: ENGAGED = a gift in the last 90 days, serving or in a group; WATCH = gave in the last 12 months but not in 90 days; AT_RISK = gave in the previous 12 months but not in the last 12; QUIET = none of these.");
        }
        if (a.followUps()) d.add("Follow-ups: tasks created / completed in the month (by their recorded dates); open = Pending or Missed at month end.");
        if (a.finance()) d.add("Giving: income rows dated in the month (gross amounts; fees are expenses), grouped by fund.");
        if (a.groups()) d.add("Groups: live groups and distinct people listed in them (by member record, else e-mail) as of today — not a month-end figure.");
        if (a.volunteers()) d.add("Volunteers: assignments whose event date falls in the month; distinct volunteers by member record.");
        d.add("Comparisons are percentage changes against the previous month and the same month a year earlier; none is shown when that month had nothing.");
        return d;
    }

    /* ───────────────────────────── helpers ───────────────────────────── */

    static long num(Object o) { return o == null ? 0 : ((Number) o).longValue(); }
    static BigDecimal toBig(Object o) { return o == null ? BigDecimal.ZERO : o instanceof BigDecimal b ? b : new BigDecimal(o.toString()); }
    static String money(BigDecimal b) { return String.format(Locale.US, "%,.2f", b == null ? BigDecimal.ZERO : b); }
}
