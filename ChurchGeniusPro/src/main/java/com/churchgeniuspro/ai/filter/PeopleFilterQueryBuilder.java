package com.churchgeniuspro.ai.filter;

import com.churchgeniuspro.ai.filter.PeopleFilter.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Phase 1 (2026-11-04): turns a {@link PeopleFilter} into ONE native PostgreSQL statement.
 *
 * <p>Rules every fragment obeys (pinned by {@code PeopleFilterSqlTest}):
 * <ul>
 *   <li>the person rows come from {@code family_member} joined to its {@code family}, both of the
 *       church ({@code f.app_client_id = :cid});</li>
 *   <li>each predicate is a fixed {@code EXISTS} / {@code NOT EXISTS} (or a column test) whose every
 *       table carries its own tenant column compared to {@code :cid} — attendance, income, groups,
 *       volunteer assignments, pledges, events, registrations;</li>
 *   <li>values arrive as named parameters only ({@code :p0}, {@code :p1} …) — no text from the question
 *       is ever concatenated into SQL;</li>
 *   <li>{@code LIMIT :limit} and {@code COUNT(*) OVER()} as the last column, like {@code AiCrossReferenceRepository}.</li>
 * </ul>
 * Columns: id, first_name, last_name, role, member_type, gender, age (years, null without a full birth
 * date), email, phone, last_attended (date or null), gave_total (numeric, only when a {@link Gave}
 * predicate is present, else null), total.
 */
public final class PeopleFilterQueryBuilder {

    private PeopleFilterQueryBuilder() {}

    /** SQL text plus its named parameters. */
    public record Built(String sql, Map<String, Object> params, boolean hasAttendance, boolean hasGiving) {}

    static final String TENANT_FM = "fm.family_id = f.id AND f.app_client_id = :cid";

    public static Built build(PeopleFilter filter, LocalDate today, int limit) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("cid", null);                 // caller sets
        p.put("today", today);
        p.put("limit", limit);
        List<String> where = new ArrayList<>();
        String status = "active";
        boolean attendance = false, giving = false;
        LocalDate giveFrom = null, giveTo = null; String giveFund = null;
        int i = 0;
        for (Predicate pr : filter.predicates()) {
            String k = "p" + (i++);
            switch (pr) {
                case Status s -> status = s.status();
                case AgeBand a -> {
                    // AGE is a CASE: without a complete, plausible birth date it is NULL and never matches
                    if (a.min() != null) { where.add(AGE + " >= :" + k + "min"); p.put(k + "min", a.min()); }
                    if (a.max() != null) { where.add(AGE + " <= :" + k + "max"); p.put(k + "max", a.max()); }
                }
                case BirthMonth b -> { where.add("fm.birthday_month = :" + k); p.put(k, b.month()); }
                case Role r -> where.add(roleSql(r.role()));
                case Gender g -> { where.add("LOWER(LEFT(TRIM(COALESCE(fm.gender, '')), 1)) = :" + k); p.put(k, g.gender().substring(0, 1).toLowerCase()); }
                case MemberType t -> where.add(typeSql(t.type()));
                case HasContact h -> where.add((h.negated() ? "NOT " : "") + "(" + ("email".equals(h.what())
                        ? "fm.email IS NOT NULL AND TRIM(fm.email) <> ''" : "fm.phone IS NOT NULL AND TRIM(fm.phone) <> ''") + ")");
                case Attended at -> {
                    attendance = true;
                    p.put(k + "from", at.from()); p.put(k + "to", at.to());
                    String rows = "SELECT 1 FROM attendance_record ar WHERE ar.client_id = :cid AND ar.family_member_id = fm.id AND ar.delete_flag = false "
                            + "AND (ar.status IS NULL OR ar.status IN ('PRESENT', 'LATE')) AND ar.attendance_date BETWEEN :" + k + "from AND :" + k + "to";
                    if (at.negated()) where.add("NOT EXISTS (" + rows + ")");
                    else if (at.minDays() <= 1) where.add("EXISTS (" + rows + ")");
                    else { where.add("(SELECT COUNT(DISTINCT ar.attendance_date) FROM attendance_record ar WHERE ar.client_id = :cid AND ar.family_member_id = fm.id "
                            + "AND ar.delete_flag = false AND (ar.status IS NULL OR ar.status IN ('PRESENT', 'LATE')) AND ar.attendance_date BETWEEN :" + k + "from AND :" + k + "to) >= :" + k + "min");
                           p.put(k + "min", at.minDays()); }
                }
                case Gave g -> {
                    giving = true; giveFrom = g.from(); giveTo = g.to(); giveFund = g.fund();
                    p.put(k + "from", g.from()); p.put(k + "to", g.to());
                    String rows = "SELECT 1 FROM income i " + (g.fund() != null ? "JOIN sub_source ss ON ss.id = i.sub_source_id AND ss.app_client_id = :cid " : "")
                            + "WHERE i.app_client_id = :cid AND i.member_id = fm.id AND i.delete_flag = false AND i.income_date BETWEEN :" + k + "from AND :" + k + "to"
                            + (g.fund() != null ? " AND LOWER(TRIM(ss.source_name)) = LOWER(TRIM(:" + k + "fund))" : "");
                    if (g.fund() != null) p.put(k + "fund", g.fund());
                    where.add((g.negated() ? "NOT " : "") + "EXISTS (" + rows + ")");
                }
                case InGroup ig -> {
                    String rows = "SELECT 1 FROM group_member gm JOIN app_group g ON g.id = gm.group_id WHERE g.app_client_id = :cid AND g.delete_flag = false AND gm.delete_flag = false "
                            + (ig.group() != null ? "AND LOWER(TRIM(g.group_name)) = LOWER(TRIM(:" + k + "name)) " : "")
                            + "AND (gm.family_member_id = fm.id OR (fm.email IS NOT NULL AND TRIM(fm.email) <> '' AND LOWER(TRIM(gm.email)) = LOWER(TRIM(fm.email)) "
                            + "AND (SELECT COUNT(*) FROM family_member x JOIN family xf ON xf.id = x.family_id WHERE xf.app_client_id = :cid AND x.delete_flag = false "
                            + "AND xf.delete_flag = false AND LOWER(TRIM(x.email)) = LOWER(TRIM(fm.email))) = 1))";
                    if (ig.group() != null) p.put(k + "name", ig.group());
                    where.add((ig.negated() ? "NOT " : "") + "EXISTS (" + rows + ")");
                }
                case Serving sv -> {
                    p.put(k + "since", sv.since());
                    where.add((sv.negated() ? "NOT " : "") + "EXISTS (SELECT 1 FROM volunteer_assignment va WHERE va.app_client_id = :cid AND va.family_member_id = fm.id "
                            + "AND va.delete_flag = false AND LOWER(COALESCE(va.assignment_status, 'pending')) <> 'declined' AND (va.event_date IS NULL OR va.event_date >= :" + k + "since))");
                }
                case InCampaign ic -> {
                    String rows = "SELECT 1 FROM pledge_member pm JOIN pledge_campaign pc ON pc.id = pm.campaign_id AND pc.client_id = :cid AND pc.delete_flag = false "
                            + "WHERE pm.client_id = :cid AND pm.family_member_id = fm.id AND pm.delete_flag = false"
                            + (ic.campaign() != null ? " AND LOWER(TRIM(pc.name)) = LOWER(TRIM(:" + k + "name))" : "");
                    if (ic.campaign() != null) p.put(k + "name", ic.campaign());
                    where.add((ic.negated() ? "NOT " : "") + "EXISTS (" + rows + ")");
                }
                case RegisteredForEvent re -> {
                    p.put(k + "name", re.event());
                    // registrations carry no member id: match an email that belongs to this member alone (the group rule)
                    where.add((re.negated() ? "NOT " : "") + "EXISTS (SELECT 1 FROM event_registration er JOIN church_event ce ON ce.id = er.event_id AND ce.app_client_id = :cid AND ce.delete_flag = false "
                            + "WHERE er.client_id = :cid AND LOWER(TRIM(ce.event_name)) = LOWER(TRIM(:" + k + "name)) AND fm.email IS NOT NULL AND TRIM(fm.email) <> '' "
                            + "AND LOWER(TRIM(er.email)) = LOWER(TRIM(fm.email)) "
                            + "AND (SELECT COUNT(*) FROM family_member x JOIN family xf ON xf.id = x.family_id WHERE xf.app_client_id = :cid AND x.delete_flag = false "
                            + "AND xf.delete_flag = false AND LOWER(TRIM(x.email)) = LOWER(TRIM(fm.email))) = 1)");
                }
                case Engagement en -> {
                    attendance = true;
                    p.put(k + "sig", en.signal());
                    // the member's LATEST nightly snapshot row of this church carries the signal
                    where.add("EXISTS (SELECT 1 FROM member_engagement_snapshot es WHERE es.app_client_id = :cid AND es.member_id = fm.id AND es.signal = :" + k + "sig "
                            + "AND es.snapshot_date = (SELECT MAX(es2.snapshot_date) FROM member_engagement_snapshot es2 WHERE es2.app_client_id = :cid AND es2.member_id = fm.id))");
                }
            }
        }
        String statusSql = switch (status) {
            case "inactive" -> "fm.delete_flag = false AND f.delete_flag = false AND (fm.inactive = true OR f.inactive = true)";
            case "deleted"  -> "(fm.delete_flag = true OR f.delete_flag = true)";
            case "any"      -> "1 = 1";
            default         -> "fm.delete_flag = false AND f.delete_flag = false AND fm.inactive = false AND f.inactive = false";
        };
        String gaveTotal = "NULL";
        if (giving) {
            p.put("gfrom", giveFrom); p.put("gto", giveTo);
            gaveTotal = "(SELECT COALESCE(SUM(i2.amount), 0) FROM income i2 " + (giveFund != null ? "JOIN sub_source ss2 ON ss2.id = i2.sub_source_id AND ss2.app_client_id = :cid " : "")
                    + "WHERE i2.app_client_id = :cid AND i2.member_id = fm.id AND i2.delete_flag = false AND i2.income_date BETWEEN :gfrom AND :gto"
                    + (giveFund != null ? " AND LOWER(TRIM(ss2.source_name)) = LOWER(TRIM(:gfund))" : "") + ")";
            if (giveFund != null) p.put("gfund", giveFund);
        }
        String lastAttended = "(SELECT MAX(ar2.attendance_date) FROM attendance_record ar2 WHERE ar2.client_id = :cid AND ar2.family_member_id = fm.id AND ar2.delete_flag = false "
                + "AND (ar2.status IS NULL OR ar2.status IN ('PRESENT', 'LATE')))";
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT fm.id, fm.first_name, fm.last_name, fm.role, fm.member_type, fm.gender, ")
           .append(AGE).append(", ")
           .append("fm.email, fm.phone, ").append(attendance ? lastAttended : "NULL").append(", ").append(gaveTotal).append(", COUNT(*) OVER() ")
           .append("FROM family_member fm JOIN family f ON ").append(TENANT_FM).append(" WHERE ").append(statusSql);
        for (String w : where) sql.append(" AND ").append(w);
        sql.append(" ORDER BY fm.last_name, fm.first_name, fm.id LIMIT :limit");
        return new Built(sql.toString(), p, attendance, giving);
    }

    /** Whole years between the (clamped) birth date and :today — the same arithmetic as KidsPortalEligibility (29 Feb → 28 Feb). */
    static final String AGE = "(CASE WHEN fm.birthday_year BETWEEN 1900 AND 2100 AND fm.birthday_month BETWEEN 1 AND 12 AND fm.birthday_day BETWEEN 1 AND 31 "
            + "AND make_date(fm.birthday_year, fm.birthday_month, LEAST(fm.birthday_day, 28)) <= CAST(:today AS date) "
            + "THEN EXTRACT(YEAR FROM age(CAST(:today AS date), make_date(fm.birthday_year, fm.birthday_month, LEAST(fm.birthday_day, 28))))::int END)";

    /** Mirrors {@code FamilyRoles.canonical}: Head = head… or primary; Spouse = spouse, wife, husband, partner; Child = child, son, daughter, kid…, minor; Adult = the rest. */
    static String roleSql(String role) {
        String r = "LOWER(TRIM(COALESCE(fm.role, '')))";
        String head = "(" + r + " LIKE 'head%' OR " + r + " IN ('primary', 'primary/head'))";
        String spouse = r + " IN ('spouse', 'wife', 'husband', 'partner')";
        String child = "(" + r + " IN ('child', 'son', 'daughter', 'minor') OR " + r + " LIKE 'kid%')";
        return switch (role) {
            case "Head" -> head;
            case "Spouse" -> spouse;
            case "Child" -> child;
            default -> "NOT " + head + " AND NOT " + spouse + " AND NOT " + child;
        };
    }

    static String typeSql(String type) {
        String t = "LOWER(TRIM(COALESCE(fm.member_type, '')))";
        return switch (type) {
            case "Guest" -> t + " = 'guest'";
            case "Visitor" -> t + " = 'visitor'";
            case "NonMember" -> t + " IN ('guest', 'visitor')";
            default -> t + " NOT IN ('guest', 'visitor')";
        };
    }
}
