package com.churchgeniuspro.ai.intel;

import com.churchgeniuspro.ai.intel.EngagementSignalRules.Facts;
import com.churchgeniuspro.ai.intel.EngagementSignalRules.Outcome;
import com.churchgeniuspro.ai.intel.EngagementSignalRules.Thresholds;
import com.churchgeniuspro.hibernate.MemberEngagementSnapshot;
import com.churchgeniuspro.repository.MemberEngagementSnapshotRepository;
import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.util.AppClock;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

/**
 * AI Phase 2 — nightly member engagement snapshots. One native query per church gathers, for every live
 * member, the counts the rules need (attendance days in 30/90/365, first/last attendance, gift presence
 * in 90/365/prior 365, last gift, confirmed groups, serving); {@link EngagementSignalRules} turns them into a
 * signal with reasons; one {@code member_engagement_snapshot} row per member per day is stored. Idempotent
 * per church and day (a second run the same day is skipped unless {@code force}). Every table in the query
 * carries its own tenant column. Nothing here calls a model.
 */
@Slf4j
@Service
public class MemberIntelligenceService {

    private static final java.time.ZoneId SCHED_ZONE = java.time.ZoneId.of("America/Chicago");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;
    private final MemberEngagementSnapshotRepository snapshots;
    private final SubscriptionService subscriptions;
    private final boolean enabled;
    private final Thresholds thresholds;

    @Autowired
    public MemberIntelligenceService(NamedParameterJdbcTemplate jdbc,
                                     MemberEngagementSnapshotRepository snapshots,
                                     SubscriptionService subscriptions,
                                     @Value("${ai.intel.enabled:true}") boolean enabled,
                                     @Value("${ai.intel.new-days:90}") int newDays,
                                     @Value("${ai.intel.regular-min-days:3}") int regularMinDays,
                                     @Value("${ai.intel.at-risk-gap-days:42}") int atRiskGapDays,
                                     @Value("${ai.intel.watch-gap-days:30}") int watchGapDays) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
        this.subscriptions = subscriptions;
        this.enabled = enabled;
        this.thresholds = new Thresholds(newDays, regularMinDays, atRiskGapDays, watchGapDays);
    }

    /** Test constructor: defaults, no subscription service (tenants must be passed explicitly). */
    public MemberIntelligenceService(NamedParameterJdbcTemplate jdbc, MemberEngagementSnapshotRepository snapshots) {
        this(jdbc, snapshots, null, true, 90, 3, 42, 30);
    }

    public Thresholds thresholds() { return thresholds; }

    // ───────────────────────── nightly job ─────────────────────────

    @Scheduled(cron = "${ai.intel.snapshot-cron:0 30 2 * * *}", zone = "America/Chicago")
    public void nightly() {
        if (!enabled) return;
        Set<String> tenants;
        try {
            tenants = subscriptions == null ? Set.of() : subscriptions.activeAccountClientIds();
        } catch (Exception e) {
            log.warn("[ai-intel] could not list active churches: {}", e.getClass().getSimpleName());
            return;
        }
        LocalDate today = LocalDate.now(SCHED_ZONE);
        int churches = 0, rows = 0;
        for (String cid : tenants) {
            try {
                int n = snapshot(cid, today, false);
                if (n >= 0) { churches++; rows += n; }
            } catch (Exception e) {
                log.warn("[ai-intel] snapshot failed for one church: {}", e.getClass().getSimpleName());
            }
        }
        log.info("[ai-intel] nightly snapshot: {} churches, {} member rows for {}", churches, rows, today);
    }

    /**
     * Snapshot one church for one day. Returns the number of rows written, or -1 when the day already
     * existed and {@code force} was false. With {@code force} the day's rows are replaced.
     */
    @Transactional
    public int snapshot(String cid, LocalDate day, boolean force) {
        if (cid == null || cid.isBlank()) return 0;
        if (snapshots.existsByAppClientIdAndSnapshotDate(cid, day)) {
            if (!force) return -1;
            snapshots.deleteDay(cid, day);
        }
        List<MemberEngagementSnapshot> out = new ArrayList<>();
        boolean tracksAttendance = churchTracksAttendance(cid, day);
        for (Row r : facts(cid, day)) {
            Outcome o = EngagementSignalRules.evaluate(r.facts, day, thresholds, tracksAttendance);
            MemberEngagementSnapshot s = new MemberEngagementSnapshot();
            s.setAppClientId(cid); s.setMemberId(r.memberId); s.setSnapshotDate(day);
            s.setAttendance30(r.facts.attendance30()); s.setAttendance90(r.facts.attendance90()); s.setAttendance365(r.facts.attendance365());
            s.setFirstAttended(r.facts.firstAttended()); s.setLastAttended(r.facts.lastAttended());
            s.setGifts90(r.facts.gifts90()); s.setGifts365(r.facts.gifts365()); s.setGiftsPrior365(r.facts.giftsPrior365()); s.setLastGift(r.facts.lastGift());
            s.setGroupsCount(r.facts.groups()); s.setServing(r.facts.serving());
            s.setSignal(o.signal());
            s.setReasonsJson(toJson(o.reasons()));
            out.add(s);
        }
        snapshots.saveAll(out);
        return out.size();
    }

    record Row(int memberId, Facts facts) {}

    static final String ATT = "FROM attendance_record ar WHERE ar.client_id = :cid AND ar.family_member_id = fm.id AND ar.delete_flag = false "
            + "AND (ar.status IS NULL OR ar.status IN ('PRESENT', 'LATE')) AND ar.attendance_date <= :today";
    static final String GIFT = "FROM income i WHERE i.app_client_id = :cid AND i.member_id = fm.id AND i.delete_flag = false AND i.income_date <= :today";

    /** The one query: every live member of the church with the facts the rules need. */
    public static final String FACTS_SQL = "SELECT fm.id AS member_id, "
            + "(SELECT COUNT(DISTINCT ar.attendance_date) " + ATT + " AND ar.attendance_date > :d30) AS a30, "
            + "(SELECT COUNT(DISTINCT ar.attendance_date) " + ATT + " AND ar.attendance_date > :d90) AS a90, "
            + "(SELECT COUNT(DISTINCT ar.attendance_date) " + ATT + " AND ar.attendance_date > :d365) AS a365, "
            + "(SELECT MIN(ar.attendance_date) " + ATT + ") AS first_att, "
            + "(SELECT MAX(ar.attendance_date) " + ATT + ") AS last_att, "
            + "(SELECT COUNT(*) " + GIFT + " AND i.income_date > :d90) AS g90, "
            + "(SELECT COUNT(*) " + GIFT + " AND i.income_date > :d365) AS g365, "
            + "(SELECT COUNT(*) " + GIFT + " AND i.income_date > :d730 AND i.income_date <= :d365) AS gprior, "
            + "(SELECT MAX(i.income_date) " + GIFT + ") AS last_gift, "
            + "(SELECT COUNT(DISTINCT g.id) FROM group_member gm JOIN app_group g ON g.id = gm.group_id WHERE g.app_client_id = :cid AND g.delete_flag = false AND gm.delete_flag = false "
            + "AND (gm.family_member_id = fm.id OR (fm.email IS NOT NULL AND TRIM(fm.email) <> '' AND LOWER(TRIM(gm.email)) = LOWER(TRIM(fm.email)) "
            + "AND (SELECT COUNT(*) FROM family_member x JOIN family xf ON xf.id = x.family_id WHERE xf.app_client_id = :cid AND x.delete_flag = false "
            + "AND xf.delete_flag = false AND LOWER(TRIM(x.email)) = LOWER(TRIM(fm.email))) = 1))) AS groups_n, "
            + "EXISTS (SELECT 1 FROM volunteer_assignment va WHERE va.app_client_id = :cid AND va.family_member_id = fm.id AND va.delete_flag = false "
            + "AND LOWER(COALESCE(va.assignment_status, 'pending')) <> 'declined' AND (va.event_date IS NULL OR va.event_date >= :serveSince)) AS serving "
            + "FROM family_member fm JOIN family f ON fm.family_id = f.id AND f.app_client_id = :cid "
            + "WHERE fm.delete_flag = false AND f.delete_flag = false AND fm.inactive = false AND f.inactive = false "
            + "ORDER BY fm.id";

    /** Does the church take attendance? Any attendance record in the 90 days up to {@code day} (any status). */
    public static final String TRACKS_ATTENDANCE_SQL = "SELECT EXISTS (SELECT 1 FROM attendance_record ar WHERE ar.client_id = :cid "
            + "AND ar.delete_flag = false AND ar.attendance_date > :d90 AND ar.attendance_date <= :today)";

    boolean churchTracksAttendance(String cid, LocalDate day) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("cid", cid).addValue("today", day).addValue("d90", day.minusDays(90));
        Boolean b = jdbc.queryForObject(TRACKS_ATTENDANCE_SQL, p, Boolean.class);
        return Boolean.TRUE.equals(b);
    }

    List<Row> facts(String cid, LocalDate day) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("cid", cid).addValue("today", day)
                .addValue("d30", day.minusDays(30)).addValue("d90", day.minusDays(90))
                .addValue("d365", day.minusDays(365)).addValue("d730", day.minusDays(730))
                .addValue("serveSince", day.minusMonths(6));
        return jdbc.query(FACTS_SQL, p, (rs, n) -> new Row(rs.getInt("member_id"), new Facts(
                rs.getInt("a30"), rs.getInt("a90"), rs.getInt("a365"), date(rs, "first_att"), date(rs, "last_att"),
                rs.getInt("g90"), rs.getInt("g365"), rs.getInt("gprior"), date(rs, "last_gift"),
                rs.getInt("groups_n"), rs.getBoolean("serving"))));
    }

    private static LocalDate date(ResultSet rs, String col) throws SQLException {
        Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    // ───────────────────────── reads ─────────────────────────

    /** The latest snapshot for one member of the church (null when none yet). */
    public MemberEngagementSnapshot latestFor(String cid, Integer memberId) {
        if (cid == null || memberId == null) return null;
        return snapshots.findFirstByAppClientIdAndMemberIdOrderBySnapshotDateDesc(cid, memberId).orElse(null);
    }

    /** signal → count on the latest day on or before {@code onOrBefore}; empty when no snapshot exists. */
    public Map<String, Object> summary(String cid, LocalDate onOrBefore) {
        LocalDate day = snapshots.latestDateOnOrBefore(cid, onOrBefore);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshotDate", day == null ? null : day.toString());
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String s : EngagementSignalRules.SIGNALS) counts.put(s, 0L);
        if (day != null) for (Object[] r : snapshots.countBySignal(cid, day)) counts.put(String.valueOf(r[0]), ((Number) r[1]).longValue());
        out.put("counts", counts);
        return out;
    }

    /**
     * Family 360: the latest signal for every live member of one family of the church. Null when the family is not
     * this church's. {@code finance} adds the giving-presence facts and keeps the gift reasons; otherwise both are
     * left out (presence of giving is still finance information).
     */
    public Map<String, Object> family(String cid, Integer familyId, boolean finance) {
        if (cid == null || cid.isBlank() || familyId == null) return null;
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("cid", cid).addValue("f", familyId);
        Integer found = jdbc.query("SELECT id FROM family WHERE id = :f AND app_client_id = :cid", p, rs -> rs.next() ? rs.getInt(1) : null);
        if (found == null) return null;
        List<Map<String, Object>> members = jdbc.query(
                "SELECT fm.id, fm.first_name, fm.last_name FROM family_member fm JOIN family f ON fm.family_id = f.id AND f.app_client_id = :cid "
                        + "WHERE f.id = :f AND fm.delete_flag = false ORDER BY fm.id", p,
                (rs, n) -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("memberId", rs.getInt(1));
                             m.put("name", ((rs.getString(2) == null ? "" : rs.getString(2)) + " " + (rs.getString(3) == null ? "" : rs.getString(3))).trim()); return m; });
        LocalDate day = null;
        for (Map<String, Object> m : members) {
            MemberEngagementSnapshot s = latestFor(cid, (Integer) m.get("memberId"));
            if (s == null) { m.put("signal", null); continue; }
            if (day == null || s.getSnapshotDate().isAfter(day)) day = s.getSnapshotDate();
            Map<String, Object> sm = toMap(s);
            if (!finance) {
                @SuppressWarnings("unchecked") List<String> reasons = (List<String>) sm.get("reasons");
                sm.put("reasons", reasons.stream().filter(r -> !isGivingReason(r)).toList());
            } else sm.put("giving", givingFacts(s));
            m.putAll(sm);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("familyId", familyId);
        out.put("snapshotDate", day == null ? null : day.toString());
        out.put("finance", finance);
        out.put("members", members);
        return out;
    }

    /** Shape of a snapshot for the API (no names — the caller adds them when allowed). */
    public static Map<String, Object> toMap(MemberEngagementSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("signal", s.getSignal());
        m.put("snapshotDate", s.getSnapshotDate().toString());
        m.put("attendance30", s.getAttendance30()); m.put("attendance90", s.getAttendance90()); m.put("attendance365", s.getAttendance365());
        m.put("firstAttended", s.getFirstAttended() == null ? null : s.getFirstAttended().toString());
        m.put("lastAttended", s.getLastAttended() == null ? null : s.getLastAttended().toString());
        m.put("groups", s.getGroupsCount()); m.put("serving", s.isServing());
        m.put("reasons", fromJson(s.getReasonsJson()));
        return m;
    }

    /** Giving facts are presence only, but still finance: callers include them only for finance roles. */
    public static Map<String, Object> givingFacts(MemberEngagementSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gifts90", s.getGifts90()); m.put("gifts365", s.getGifts365()); m.put("giftsPrior365", s.getGiftsPrior365());
        m.put("lastGift", s.getLastGift() == null ? null : s.getLastGift().toString());
        return m;
    }

    /** True when a reason sentence talks about gifts (so it can be hidden from non-finance viewers). */
    public static boolean isGivingReason(String reason) {
        String r = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        return r.contains("gift");
    }

    static String toJson(List<String> reasons) {
        try { return JSON.writeValueAsString(reasons); } catch (Exception e) { return "[]"; }
    }

    public static List<String> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return JSON.readValue(json, new TypeReference<List<String>>() {}); } catch (Exception e) { return List.of(); }
    }

    /** Church "today" for callers that need the same clock as the job. */
    public static LocalDate today() { return AppClock.today(); }
}
