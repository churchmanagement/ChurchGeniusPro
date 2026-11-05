package com.churchgeniuspro.ai;

import com.churchgeniuspro.ai.filter.PeopleFilter;
import com.churchgeniuspro.ai.filter.PeopleFilter.*;
import com.churchgeniuspro.ai.filter.PeopleFilterQueryBuilder;
import com.churchgeniuspro.ai.filter.PeopleFilterService;
import com.churchgeniuspro.service.AiAccessPolicy;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.AiAccessPolicy.CrossAccess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI Phase 1 — the generated SQL is safe by construction: every table carries its tenant column
 * compared to {@code :cid}, every value is a named parameter, there is a LIMIT, and the question's
 * text never reaches the statement. Also the permission rule (whole-question refusal) without a database.
 */
@DisplayName("AI Phase 1 — PeopleFilter SQL and gating ratchets")
class PeopleFilterSqlTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    static PeopleFilter everything() {
        return PeopleFilter.of(new AgeBand(18, 30), new BirthMonth(3), new Role("Head"), new Gender("Female"), new Status("inactive"),
                new MemberType("Guest"), new HasContact(true, "email"),
                new Attended(false, 2, TODAY.minusDays(59), TODAY, "x"), new Gave(true, TODAY.withDayOfYear(1), TODAY, "y", "Building Fund"),
                new InGroup(true, "Youth Group"), new Serving(false, TODAY.minusMonths(6)), new InCampaign(false, "Roof"), new RegisteredForEvent(false, "Picnic"));
    }

    /** Tables that must each appear with their own tenant column. */
    static final Map<String, String> TENANT_COLUMN = Map.ofEntries(
            Map.entry("attendance_record", "client_id"), Map.entry("income", "app_client_id"), Map.entry("sub_source", "app_client_id"),
            Map.entry("app_group", "app_client_id"), Map.entry("volunteer_assignment", "app_client_id"), Map.entry("pledge_member", "client_id"),
            Map.entry("pledge_campaign", "client_id"), Map.entry("event_registration", "client_id"), Map.entry("church_event", "app_client_id"),
            Map.entry("family", "app_client_id"));

    @Test @DisplayName("every table the statement reads is compared to :cid on its own tenant column; LIMIT and COUNT(*) OVER() present")
    void tenantOnEveryTable() {
        PeopleFilterQueryBuilder.Built b = PeopleFilterQueryBuilder.build(everything(), TODAY, 21);
        String sql = b.sql();
        for (Map.Entry<String, String> e : TENANT_COLUMN.entrySet()) {
            Matcher m = Pattern.compile("\\b" + e.getKey() + "\\s+(\\w+)\\b").matcher(sql);
            int uses = 0;
            while (m.find()) {
                String alias = m.group(1);
                if (alias.equalsIgnoreCase("ON") || alias.equalsIgnoreCase("WHERE") || alias.equalsIgnoreCase("JOIN")) continue;
                uses++;
                assertThat(sql).as(e.getKey() + " alias " + alias).contains(alias + "." + e.getValue() + " = :cid");
            }
            assertThat(uses).as(e.getKey() + " is read").isPositive();
        }
        // family_member joins through family (f.app_client_id) — the correlated look-ups through x/xf too
        assertThat(sql).contains("fm.family_id = f.id AND f.app_client_id = :cid").contains("xf.app_client_id = :cid");
        assertThat(sql).endsWith("LIMIT :limit").contains("COUNT(*) OVER()");
        assertThat(b.params()).containsKeys("cid", "today", "limit").containsEntry("limit", 21);
        // no literal from the question in the SQL
        assertThat(sql).doesNotContain("Building Fund").doesNotContain("Youth Group").doesNotContain("Roof").doesNotContain("Picnic").doesNotContain("'f'");
        assertThat(b.params()).containsValue("Building Fund").containsValue("Youth Group").containsValue("Roof").containsValue("Picnic").containsValue("f");
        // every :param in the SQL has a value
        Matcher pm = Pattern.compile("(?<!:):([a-zA-Z][a-zA-Z0-9]*)").matcher(sql.replace("::int", ""));
        while (pm.find()) assertThat(b.params()).as(":" + pm.group(1)).containsKey(pm.group(1));
    }

    @Test @DisplayName("status variants, negation and the default active rule")
    void statusAndNegation() {
        String active = PeopleFilterQueryBuilder.build(PeopleFilter.of(new Gender("Male")), TODAY, 21).sql();
        assertThat(active).contains("fm.delete_flag = false AND f.delete_flag = false AND fm.inactive = false AND f.inactive = false");
        String deleted = PeopleFilterQueryBuilder.build(PeopleFilter.of(new Status("deleted")), TODAY, 21).sql();
        assertThat(deleted).contains("(fm.delete_flag = true OR f.delete_flag = true)").doesNotContain("fm.inactive = false");
        String neg = PeopleFilterQueryBuilder.build(PeopleFilter.of(new InGroup(true, null), new Serving(true, TODAY)), TODAY, 21).sql();
        assertThat(neg.split("NOT EXISTS")).hasSize(3);
        String age = PeopleFilterQueryBuilder.build(PeopleFilter.of(new AgeBand(65, null)), TODAY, 21).sql();
        assertThat(age).contains("CASE WHEN fm.birthday_year BETWEEN 1900 AND 2100").contains(">= :p0min").doesNotContain(":p0max");
    }

    @Test @DisplayName("gating without a database: missing modules named, whole question refused; member / church / kids sessions get nothing")
    void gating() {
        PeopleFilterService svc = new PeopleFilterService(new AiAccessPolicy());
        AiAccess staffNoFinance = new AiAccess("User", "u", false, false, false, true, false, false, false, true, true, false, true, true, false, true, true, false, true, true, Set.of());
        PeopleFilter f = PeopleFilter.of(new Gender("Female"), new Gave(false, TODAY.withDayOfYear(1), TODAY, "", null), new InGroup(true, null), new InCampaign(false, null));
        assertThat(PeopleFilterService.missing(f, staffNoFinance, new CrossAccess(true, false))).containsExactly("Income", "Groups", "Pledges");
        Map<String, Object> m = svc.run(f, staffNoFinance, new CrossAccess(true, false), "CHR-1", TODAY);
        assertThat(m.get("denied")).isEqualTo(true);
        assertThat(String.valueOf(m.get("answer"))).contains("Income").contains("Groups").contains("Pledges").doesNotContain("rows");
        AiAccess member = new AiAccess("Member", "m", false, true, false, true, false, false, false, false, false, false, false, false, false, false, false, false, false, false, Set.of());
        assertThat(svc.run(PeopleFilter.of(new Gender("Female")), member, new CrossAccess(true, true), "CHR-1", TODAY).get("denied")).isEqualTo(true);
        assertThat(svc.route("women over 60", "women over 60", member, new CrossAccess(true, true), "CHR-1", TODAY)).isNull();
        assertThat(svc.route("women over 60", "women over 60", AiAccess.none("Member"), new CrossAccess(true, true), "CHR-1", TODAY)).isNull();
        // a fully permitted session without a database reaches the query step (reported, not thrown)
        AiAccess all = new AiAccess("Admin", "a", false, false, false, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, Set.of());
        assertThat(svc.run(PeopleFilter.of(new Gender("Female")), all, new CrossAccess(true, true), "CHR-1", TODAY).get("intent")).isEqualTo("filter.error");
        // too wide
        assertThat(svc.run(PeopleFilter.of(new Attended(false, 1, TODAY.minusYears(2), TODAY, "")), all, new CrossAccess(true, true), "CHR-1", TODAY).get("intent")).isEqualTo("narrow");
        // "65+" survives the normaliser through preNorm
        Map<String, Object> plus = svc.route("members 65+", "members 65", all, new CrossAccess(true, true), "CHR-1", TODAY);
        assertThat(plus).isNotNull();
        assertThat(plus.get("intent")).as("parsed and reached the query step").isEqualTo("filter.error");
    }

    @Test @DisplayName("AiDataSearchService: the cross-reference route still answers first, the filter route takes composed questions, the older single intents are untouched")
    void routeOrder() {
        com.churchgeniuspro.repository.FamilyMemberRepository members = org.mockito.Mockito.mock(com.churchgeniuspro.repository.FamilyMemberRepository.class);
        com.churchgeniuspro.repository.IncomeRepository income = org.mockito.Mockito.mock(com.churchgeniuspro.repository.IncomeRepository.class);
        com.churchgeniuspro.repository.VolunteerProfileRepository vol = org.mockito.Mockito.mock(com.churchgeniuspro.repository.VolunteerProfileRepository.class);
        com.churchgeniuspro.service.AiDataSearchService ds = new com.churchgeniuspro.service.AiDataSearchService(members, income, vol);
        PeopleFilterService pf = new PeopleFilterService(new AiAccessPolicy());
        ds.setPeopleFilter(pf);
        com.churchgeniuspro.repository.AiCrossReferenceRepository xrepo = org.mockito.Mockito.mock(com.churchgeniuspro.repository.AiCrossReferenceRepository.class);
        ds.setCrossReference(new com.churchgeniuspro.service.AiCrossReferenceService(xrepo));
        org.springframework.mock.web.MockHttpSession s = new org.springframework.mock.web.MockHttpSession();
        s.setAttribute("username", "pat"); s.setAttribute("role", "Admin"); s.setAttribute("appClientId", "CHR-1");
        org.springframework.mock.web.MockHttpServletRequest req = new org.springframework.mock.web.MockHttpServletRequest(); req.setSession(s);

        Map<String, Object> xr = ds.answer(req, "CHR-1", "regular attenders not in a group");
        assertThat(xr.get("intent")).as("cross-reference answers first").isEqualTo("crossref.notInGroup");
        Map<String, Object> f = ds.answer(req, "CHR-1", "women over 60 who are not in a group");
        assertThat(f.get("intent")).as("composed question → filter route (no DB here)").isEqualTo("filter.error");
        Map<String, Object> single = ds.answer(req, "CHR-1", "list volunteers");
        assertThat(single.get("intent")).as("single-module question keeps its route").isEqualTo("volunteers");
        Map<String, Object> howto = ds.answer(req, "CHR-1", "how do i add a visitor");
        assertThat(howto.get("intent")).isNotEqualTo("filter.error");
    }
}
