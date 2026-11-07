package com.churchgeniuspro.ai;

import com.churchgeniuspro.ai.filter.PeopleFilter;
import com.churchgeniuspro.ai.filter.PeopleFilterParser;
import com.churchgeniuspro.ai.filter.PeopleFilterQueryBuilder;
import com.churchgeniuspro.ai.intel.EngagementSignalRules;
import com.churchgeniuspro.ai.intel.EngagementSignalRules.Facts;
import com.churchgeniuspro.ai.intel.EngagementSignalRules.Outcome;
import com.churchgeniuspro.ai.intel.EngagementSignalRules.Thresholds;
import com.churchgeniuspro.ai.intel.MemberIntelligenceService;
import com.churchgeniuspro.ai.intel.MinistryReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AI Phase 2 — the engagement rules are deterministic, explain themselves, and never mention a name or an amount. */
@DisplayName("AI Phase 2 — engagement signal rules, Engagement predicate, no-PII shapes")
class EngagementSignalRulesTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 4);
    static final Thresholds T = Thresholds.DEFAULT;

    static Facts f(int a30, int a90, int a365, LocalDate first, LocalDate last, int g90, int g365, int gPrior, LocalDate lastGift, int groups, boolean serving) {
        return new Facts(a30, a90, a365, first, last, g90, g365, gPrior, lastGift, groups, serving);
    }

    @Test @DisplayName("each signal from its defining facts, with reasons made of numbers and dates only")
    void signals() {
        // NEW wins whatever else: first attended 20 days ago
        Outcome o = EngagementSignalRules.evaluate(f(2, 2, 2, TODAY.minusDays(20), TODAY.minusDays(3), 0, 0, 0, null, 0, false), TODAY, T);
        assertThat(o.signal()).isEqualTo("NEW");
        assertThat(o.reasons()).anyMatch(r -> r.contains("20 days ago"));
        // NEW even on day 89, not on day 90
        assertThat(EngagementSignalRules.evaluate(f(1, 1, 1, TODAY.minusDays(89), TODAY.minusDays(1), 0, 0, 0, null, 0, false), TODAY, T).signal()).isEqualTo("NEW");
        assertThat(EngagementSignalRules.evaluate(f(1, 1, 1, TODAY.minusDays(90), TODAY.minusDays(1), 0, 0, 0, null, 0, false), TODAY, T).signal()).isEqualTo("ENGAGED");

        // QUIET: nothing in 365 days (older history exists)
        o = EngagementSignalRules.evaluate(f(0, 0, 0, TODAY.minusYears(3), TODAY.minusDays(400), 0, 0, 2, TODAY.minusDays(500), 0, false), TODAY, T);
        assertThat(o.signal()).isEqualTo("QUIET");
        assertThat(o.reasons()).anyMatch(r -> r.startsWith("No attendance, gift, group or serving"));
        // a member with no records at all is QUIET too
        assertThat(EngagementSignalRules.evaluate(f(0, 0, 0, null, null, 0, 0, 0, null, 0, false), TODAY, T).signal()).isEqualTo("QUIET");

        // AT_RISK: regular (4 days between 90 and 30 days ago), none in 30, last 45 days ago
        o = EngagementSignalRules.evaluate(f(0, 4, 20, TODAY.minusYears(2), TODAY.minusDays(45), 1, 5, 6, TODAY.minusDays(10), 1, false), TODAY, T);
        assertThat(o.signal()).isEqualTo("AT_RISK");
        assertThat(o.reasons()).contains("Attended 4 days between 90 and 30 days ago, none in the last 30.", "Last attended " + TODAY.minusDays(45) + " (45 days ago).");
        // the same person seen 35 days ago is only WATCH (gap below 42)
        assertThat(EngagementSignalRules.evaluate(f(0, 4, 20, TODAY.minusYears(2), TODAY.minusDays(35), 1, 5, 6, null, 1, false), TODAY, T).signal()).isEqualTo("WATCH");
        // AT_RISK: gave last year, nothing this year, no attendance in 90 days (but attended once this year)
        o = EngagementSignalRules.evaluate(f(0, 0, 1, TODAY.minusYears(3), TODAY.minusDays(200), 0, 0, 12, TODAY.minusDays(400), 0, false), TODAY, T);
        assertThat(o.signal()).isEqualTo("AT_RISK");
        assertThat(o.reasons()).anyMatch(r -> r.contains("12 gifts recorded in the previous 12 months, none in the last 12."));

        // WATCH: gifts in the year, none in 90 days, no attendance in 30
        o = EngagementSignalRules.evaluate(f(0, 1, 6, TODAY.minusYears(2), TODAY.minusDays(28), 0, 4, 4, TODAY.minusDays(120), 0, false), TODAY, T);
        assertThat(o.signal()).isEqualTo("WATCH");
        // WATCH: in a group / serving but never attended this year
        o = EngagementSignalRules.evaluate(f(0, 0, 0, TODAY.minusYears(2), TODAY.minusDays(380), 0, 0, 0, null, 2, true), TODAY, T);
        assertThat(o.signal()).isEqualTo("WATCH");
        assertThat(o.reasons()).contains("No attendance recorded in the last 365 days.", "In 2 groups.", "Serving as a volunteer.");

        // ENGAGED
        o = EngagementSignalRules.evaluate(f(3, 9, 30, TODAY.minusYears(2), TODAY.minusDays(2), 3, 12, 12, TODAY.minusDays(5), 1, true), TODAY, T);
        assertThat(o.signal()).isEqualTo("ENGAGED");
        assertThat(o.reasons()).contains("3 attendance days in the last 30, 9 in the last 90.", "3 gifts in the last 90 days.", "In 1 group.", "Serving as a volunteer.");

        // every reason: no currency, no letters that look like a name (only the fixed vocabulary + numbers + ISO dates)
        Pattern allowed = Pattern.compile("^[A-Za-z0-9 ,.()\\-]+$");
        for (Facts facts : List.of(f(3, 9, 30, TODAY.minusYears(2), TODAY.minusDays(2), 3, 12, 12, TODAY, 1, true), f(0, 4, 20, TODAY.minusYears(2), TODAY.minusDays(45), 1, 5, 6, null, 1, false)))
            for (String r : EngagementSignalRules.evaluate(facts, TODAY, T).reasons()) {
                assertThat(r).doesNotContain("$").matches(allowed);
            }
        // same facts → same outcome (pure)
        Facts x = f(0, 4, 20, TODAY.minusYears(2), TODAY.minusDays(45), 1, 5, 6, null, 1, false);
        assertThat(EngagementSignalRules.evaluate(x, TODAY, T)).isEqualTo(EngagementSignalRules.evaluate(x, TODAY, T));
    }

    @Test @DisplayName("2026-11-07: no attendance recorded (church or person) → giving, groups and serving only, with a note")
    void withoutAttendance() {
        Pattern allowed = Pattern.compile("^[A-Za-z0-9 ,.()\\-]+$");
        // church takes no attendance: monthly giver who volunteers → ENGAGED (was WATCH)
        Facts giverServing = f(0, 0, 0, null, null, 3, 12, 12, TODAY.minusDays(12), 0, true);
        Outcome o = EngagementSignalRules.evaluate(giverServing, TODAY, T, false);
        assertThat(o.signal()).isEqualTo("ENGAGED");
        assertThat(o.reasons()).contains("3 gifts in the last 90 days.", "Serving as a volunteer.", EngagementSignalRules.CHURCH_NO_ATTENDANCE_NOTE);
        o.reasons().forEach(r -> assertThat(r).doesNotContain("$").matches(allowed));
        // same facts in a church that takes attendance, person never checked in → ENGAGED with the person note
        o = EngagementSignalRules.evaluate(giverServing, TODAY, T, true);
        assertThat(o.signal()).isEqualTo("ENGAGED");
        assertThat(o.reasons()).contains(EngagementSignalRules.PERSON_NO_ATTENDANCE_NOTE).doesNotContain(EngagementSignalRules.CHURCH_NO_ATTENDANCE_NOTE);
        // the 3-argument form = church takes attendance
        assertThat(EngagementSignalRules.evaluate(giverServing, TODAY, T)).isEqualTo(o);
        // group only → ENGAGED; gift only in the last 90 days → ENGAGED
        assertThat(EngagementSignalRules.evaluate(f(0, 0, 0, null, null, 0, 0, 0, null, 1, false), TODAY, T, false).signal()).isEqualTo("ENGAGED");
        assertThat(EngagementSignalRules.evaluate(f(0, 0, 0, null, null, 1, 1, 0, TODAY.minusDays(80), 0, false), TODAY, T, false).signal()).isEqualTo("ENGAGED");
        // giving paused: gifts in the year, none in 90 days → WATCH
        o = EngagementSignalRules.evaluate(f(0, 0, 0, null, null, 0, 4, 12, TODAY.minusDays(120), 0, false), TODAY, T, false);
        assertThat(o.signal()).isEqualTo("WATCH");
        assertThat(o.reasons()).contains("4 gifts in the last 12 months, none in the last 90 days.", "Last gift " + TODAY.minusDays(120) + ".");
        // giving stopped year over year → AT_RISK
        o = EngagementSignalRules.evaluate(f(0, 0, 0, null, null, 0, 0, 6, TODAY.minusDays(400), 0, false), TODAY, T, false);
        assertThat(o.signal()).isEqualTo("AT_RISK");
        assertThat(o.reasons()).contains("6 gifts recorded in the previous 12 months, none in the last 12.");
        // nothing at all → QUIET; NEW never applies without attendance
        o = EngagementSignalRules.evaluate(f(0, 0, 0, null, null, 0, 0, 0, null, 0, false), TODAY, T, true);
        assertThat(o.signal()).isEqualTo("QUIET");
        assertThat(o.reasons()).contains("No gift, group or serving in the last 365 days.", EngagementSignalRules.PERSON_NO_ATTENDANCE_NOTE);
        // church no longer takes attendance: older check-ins are ignored (no WATCH for "no attendance in a year")
        assertThat(EngagementSignalRules.evaluate(f(0, 0, 3, TODAY.minusYears(2), TODAY.minusDays(200), 2, 8, 8, TODAY.minusDays(20), 0, false), TODAY, T, false).signal()).isEqualTo("ENGAGED");
        // people who HAVE been checked in, in a church that takes attendance, keep the attendance rules
        assertThat(EngagementSignalRules.evaluate(f(0, 0, 0, TODAY.minusYears(2), TODAY.minusDays(380), 0, 0, 0, null, 2, true), TODAY, T, true).signal()).isEqualTo("WATCH");
        assertThat(EngagementSignalRules.evaluate(f(0, 4, 20, TODAY.minusYears(2), TODAY.minusDays(45), 1, 5, 6, null, 1, false), TODAY, T, true).signal()).isEqualTo("AT_RISK");
        // the church check is tenant-scoped and only looks at the last 90 days
        assertThat(MemberIntelligenceService.TRACKS_ATTENDANCE_SQL).contains("ar.client_id = :cid").contains("ar.delete_flag = false")
                .contains("ar.attendance_date > :d90").contains("ar.attendance_date <= :today");
    }

    @Test @DisplayName("thresholds are honoured: a stricter regular-min or a longer at-risk gap changes the verdict")
    void thresholds() {
        Facts x = f(0, 3, 20, TODAY.minusYears(2), TODAY.minusDays(45), 0, 0, 0, null, 0, false);
        assertThat(EngagementSignalRules.evaluate(x, TODAY, T).signal()).isEqualTo("AT_RISK");
        assertThat(EngagementSignalRules.evaluate(x, TODAY, new Thresholds(90, 4, 42, 30)).signal()).isEqualTo("WATCH");
        assertThat(EngagementSignalRules.evaluate(x, TODAY, new Thresholds(90, 3, 60, 30)).signal()).isEqualTo("WATCH");
        assertThat(EngagementSignalRules.evaluate(x, TODAY, new Thresholds(90, 3, 60, 50)).signal()).isEqualTo("ENGAGED");   // 45 < 50: nothing "stopped" yet
    }

    @Test @DisplayName("Engagement predicate: parser words, JSON round-trip + refusal, SQL reads the member's latest snapshot with the tenant column")
    void predicate() {
        PeopleFilterParser parser = new PeopleFilterParser(PeopleFilterParser.Context.empty(), TODAY);
        // the route hands the parser the canonical question (AiQueryLexicon), as AiDataSearchService does
        java.util.function.Function<String, PeopleFilterParser.Result> parse = q -> parser.parse(com.churchgeniuspro.service.AiQueryLexicon.canonical(q));
        var r = parse.apply("show members at risk who are not in a group");
        assertThat(r.claim()).isTrue();
        assertThat(r.filter().predicates()).containsExactly(new PeopleFilter.Engagement("AT_RISK"), new PeopleFilter.InGroup(true, null));
        // "new attenders" is the NEW signal, not an attendance count
        r = parse.apply("list new attenders");
        assertThat(r.filter().predicates()).containsExactly(new PeopleFilter.Engagement("NEW"));
        assertThat(r.claim()).isTrue();
        assertThat(parse.apply("which members have gone quiet").filter().predicates()).containsExactly(new PeopleFilter.Engagement("QUIET"));
        assertThat(parse.apply("women on the watch list").filter().predicates()).containsExactlyInAnyOrder(new PeopleFilter.Gender("Female"), new PeopleFilter.Engagement("WATCH"));
        assertThat(parse.apply("most engaged members who gave this year").filter().predicates()).hasSize(2).contains(new PeopleFilter.Engagement("ENGAGED"));
        // how-to never claims
        assertThat(parse.apply("how do i see members at risk").claim()).isFalse();

        // JSON round trip; a made-up signal is refused (allow-list)
        PeopleFilter f = PeopleFilter.fromJson(List.of(Map.of("kind", "Engagement", "signal", "AT_RISK")));
        assertThat(f.predicates()).containsExactly(new PeopleFilter.Engagement("AT_RISK"));
        assertThat(f.toJson()).containsExactly(Map.of("kind", "Engagement", "signal", "AT_RISK"));
        assertThat(f.predicates().get(0).label()).isEqualTo("At risk");
        assertThat(f.modules()).containsExactly("Members", "Attendance");
        assertThatThrownBy(() -> PeopleFilter.fromJson(List.of(Map.of("kind", "Engagement", "signal", "ALL' OR 1=1")))).isInstanceOf(IllegalArgumentException.class);

        // SQL: tenant column on both snapshot references, signal as a named parameter, latest-day subquery
        PeopleFilterQueryBuilder.Built b = PeopleFilterQueryBuilder.build(f, TODAY, 20);
        assertThat(b.sql()).contains("member_engagement_snapshot es WHERE es.app_client_id = :cid AND es.member_id = fm.id AND es.signal = :p0sig")
                           .contains("MAX(es2.snapshot_date) FROM member_engagement_snapshot es2 WHERE es2.app_client_id = :cid");
        assertThat(b.params()).containsEntry("p0sig", "AT_RISK");
        assertThat(b.sql()).doesNotContain("AT_RISK");
        assertThat(b.hasAttendance()).isTrue();
    }

    @Test @DisplayName("snapshot facts SQL and report SQL carry a tenant column on every table they read; API shapes hold no names or amounts")
    void sqlTenancyAndShapes() {
        String sql = MemberIntelligenceService.FACTS_SQL;
        for (String t : List.of("attendance_record ar", "income i", "group_member gm JOIN app_group g", "volunteer_assignment va", "family_member fm JOIN family f"))
            assertThat(sql).contains(t);
        assertThat(sql).contains("ar.client_id = :cid").contains("i.app_client_id = :cid").contains("g.app_client_id = :cid").contains("va.app_client_id = :cid").contains("f.app_client_id = :cid");
        assertThat(sql).doesNotContain("SUM(").doesNotContain("amount");             // presence only, never amounts
        assertThat(sql).doesNotContain("first_name").doesNotContain("email,");       // no PII columns selected (email appears only inside the group-match rule)
        Matcher m = Pattern.compile("FROM\\s+(\\w+)").matcher(sql);
        while (m.find()) assertThat(List.of("attendance_record", "income", "group_member", "family_member", "volunteer_assignment")).contains(m.group(1));

        // the Family 360 map leaves giving facts out unless finance is allowed
        var s = new com.churchgeniuspro.hibernate.MemberEngagementSnapshot();
        s.setSnapshotDate(TODAY); s.setSignal("WATCH"); s.setGifts90(0); s.setGifts365(4);
        s.setReasonsJson("[\"4 gifts in the last 12 months, none in the last 90 days.\",\"No attendance in the last 30 days.\"]");
        Map<String, Object> map = MemberIntelligenceService.toMap(s);
        assertThat(map).containsEntry("signal", "WATCH").doesNotContainKeys("gifts90", "gifts365", "giving");
        assertThat(MemberIntelligenceService.isGivingReason("4 gifts in the last 12 months, none in the last 90 days.")).isTrue();
        assertThat(MemberIntelligenceService.isGivingReason("No attendance in the last 30 days.")).isFalse();
        assertThat(MemberIntelligenceService.givingFacts(s)).containsEntry("gifts365", 4);

        // report comparisons
        assertThat(MinistryReportService.compare(110, 100, "Sep 2026")).isEqualTo(", up 10% on Sep 2026");
        assertThat(MinistryReportService.compare(90, 100, "Sep 2026")).isEqualTo(", down 10% on Sep 2026");
        assertThat(MinistryReportService.compare(5, 0, "Sep 2026")).isEmpty();
        assertThat(MinistryReportService.compareMoney("1,250.00", "1,000.00", "Oct 2025")).isEqualTo(", up 25% on Oct 2025");
        assertThat(MinistryReportService.definitions(new MinistryReportService.Access(true, false, false, false, false))).noneMatch(d -> d.startsWith("Giving"));
        assertThat(MinistryReportService.definitions(MinistryReportService.Access.all())).anyMatch(d -> d.startsWith("Giving"));
    }
}
