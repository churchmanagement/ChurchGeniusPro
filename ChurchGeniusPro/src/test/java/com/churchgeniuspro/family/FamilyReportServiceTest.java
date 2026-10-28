package com.churchgeniuspro.family;

import com.churchgeniuspro.hibernate.Family;
import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.service.FamilyReportService;
import com.churchgeniuspro.service.FamilyReportService.Criteria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Families page → Report ({@code /familySearchReport}): the filters, the family grouping
 * (primary + spouse + members, never a duplicate family), month matching regardless of
 * year, tenant isolation and the private-field masking for member-portal viewers.
 *
 * <p>Fixture church "CHR-ours": the Lovelace family (Head Ada b. March 1980, anniv June 12
 * 1999 · Spouse Charles (legacy role "Husband", no anniversary) · Child Byron b. March 2015,
 * no year on record for his sister Annabella b. March) in Olathe, KS 66062; the Turing family
 * (Head Alan, Guest, Wichita KS 67202, anniversary only on the Spouse Joan: June 1); the
 * Hopper family (Head Grace, Visitor, Kansas City MO, address private, phone private);
 * the Babbage family is INACTIVE; the Noether family is DELETED; and Dijkstra's family is
 * active but Edsger himself is a deleted member (b. March).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Family & Member Report — filters, grouping, isolation, privacy")
class FamilyReportServiceTest {

    private static final String OURS   = "CHR-ours";
    private static final String THEIRS = "CHR-theirs";

    @Mock FamilyMemberRepository memberRepo;

    FamilyReportService service;
    List<FamilyMember> ours;

    @BeforeEach
    void setUp() {
        service = new FamilyReportService(memberRepo);
        ours = new ArrayList<>();

        Family lovelace = family(1, OURS, false, false);
        ours.add(member(10, lovelace, "Head of Household", "Ada", "Lovelace", "Female", "Member",
                3, 15, 1980, 6, 12, 1999, "Olathe", "16", "66062", "913-555-0100", "ada@example.test"));
        ours.add(member(11, lovelace, "Husband", "Charles", "Lovelace", "Male", "Member",
                11, 2, 1978, null, null, null, "Olathe", "16", "66062", null, null));
        ours.add(member(12, lovelace, "Child", "Byron", "Lovelace", "Male", "Member",
                3, 3, 2015, null, null, null, "Olathe", "16", "66062", null, null));
        ours.add(member(13, lovelace, "Daughter", "Annabella", "Lovelace", "Female", "Member",
                3, 20, null, null, null, null, "Olathe", "16", "66062", null, null));

        Family turing = family(2, OURS, false, false);
        ours.add(member(20, turing, "Head", "Alan", "Turing", "Male", "Guest",
                6, 23, 1912, null, null, null, "Wichita", "KS", "67202-1234", null, null));
        ours.add(member(21, turing, "Spouse", "Joan", "Turing", "Female", "Guest",
                null, null, null, 6, 1, 2001, "Wichita", "KS", "67202-1234", null, null));

        Family hopper = family(3, OURS, false, false);
        FamilyMember grace = member(30, hopper, "Head of Household", "Grace", "Hopper", "Female", "Visitor",
                12, 9, 1906, null, null, null, "Kansas City", "Missouri", "64101", "816-555-0100", "grace@example.test");
        grace.setAddressPrivate(true);
        grace.setPhonePrivate(true);
        ours.add(grace);

        Family babbage = family(4, OURS, true, false);     // inactive family
        ours.add(member(40, babbage, "Head of Household", "Charles", "Babbage", "Male", "Member",
                3, 26, 1791, null, null, null, "Olathe", "16", "66062", null, null));

        Family noether = family(5, OURS, false, true);     // deleted family
        ours.add(member(50, noether, "Head of Household", "Emmy", "Noether", "Female", "Member",
                3, 23, 1882, null, null, null, "Olathe", "16", "66062", null, null));

        Family dijkstra = family(6, OURS, false, false);
        FamilyMember edsger = member(60, dijkstra, "Head of Household", "Edsger", "Dijkstra", "Male", "Member",
                3, 11, 1930, null, null, null, "Olathe", "16", "66061", null, null);
        edsger.setDeleteFlag(true);                        // deleted member of an active family
        ours.add(edsger);
        ours.add(member(61, dijkstra, "Spouse", "Ria", "Dijkstra", "Female", "Member",
                null, null, null, null, null, null, "Olathe", "16", "66061", null, null));

        when(memberRepo.findAllWithFamilyByAppUserAll(OURS)).thenReturn(ours);
        when(memberRepo.findAllWithFamilyByAppUserAll(THEIRS)).thenReturn(List.of(
                member(900, family(90, THEIRS, false, false), "Head", "Other", "Church", "Male", "Member",
                        3, 1, 1990, 6, 1, 1990, "Olathe", "16", "66062", null, null)));
    }

    // ── Members report ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Birthday month lists members born in that month whatever the year — or with no year at all")
    void birthdayMonthRegardlessOfYear() {
        List<String> names = names(run(members().birthdayMonth(3)));
        // 1980, 2015 and no year all match; the deleted member, the inactive family and the deleted family do not.
        assertThat(names).containsExactly("Ada Lovelace", "Annabella Lovelace", "Byron Lovelace");
        assertThat(names(run(members().birthdayMonth(11)))).containsExactly("Charles Lovelace");
        assertThat(run(members().birthdayMonth(2)).get("rows")).asList().isEmpty();
    }

    @Test
    @DisplayName("Gender, family role (legacy spellings map to the canonical four) and member type filters")
    void genderRoleAndType() {
        assertThat(names(run(members().gender("Female")))).containsExactly("Ria Dijkstra", "Grace Hopper", "Ada Lovelace", "Annabella Lovelace", "Joan Turing");
        // "Husband" is stored on Charles; the Spouse filter still finds him (and the canonical Spouses).
        assertThat(names(run(members().role("Spouse")))).containsExactly("Ria Dijkstra", "Charles Lovelace", "Joan Turing");
        // "Daughter" counts as Child.
        assertThat(names(run(members().role("Child")))).containsExactly("Annabella Lovelace", "Byron Lovelace");
        assertThat(names(run(members().memberType("nonmember")))).containsExactly("Grace Hopper", "Alan Turing", "Joan Turing");
        assertThat(names(run(members().memberType("Visitor")))).containsExactly("Grace Hopper");
        assertThat(names(run(members().gender("Male").role("Child")))).containsExactly("Byron Lovelace");
    }

    @Test
    @DisplayName("City / state / ZIP — any one, or combined; state accepts code, abbreviation or name; ZIP matches ZIP+4")
    void locationFilters() {
        assertThat(names(run(members().city("wichita")))).containsExactly("Alan Turing", "Joan Turing");
        // State 16 (the Family form's code), "KS" and "Kansas" are the same state.
        assertThat(names(run(members().state("16")))).hasSize(7);
        assertThat(names(run(members().state("KS")))).hasSize(7);
        assertThat(names(run(members().state("Kansas")))).hasSize(7);
        assertThat(names(run(members().state("MO")))).containsExactly("Grace Hopper");
        assertThat(names(run(members().zip("67202")))).containsExactly("Alan Turing", "Joan Turing");
        assertThat(names(run(members().zip("66061")))).containsExactly("Ria Dijkstra");
        assertThat(names(run(members().city("Olathe").state("KS").zip("66062")))).hasSize(4);
        assertThat(names(run(members().city("Olathe").state("MO")))).isEmpty();
        // Display: the numeric code is shown as the abbreviation.
        Map<String, Object> ada = row(run(members().name("Ada")), 0);
        assertThat(ada).containsEntry("state", "KS").containsEntry("city", "Olathe").containsEntry("zip", "66062");
    }

    @Test
    @DisplayName("Name search matches first, last, nickname or full name; results sorted by last then first name")
    void nameSearch() {
        assertThat(names(run(members().name("lovelace")))).containsExactly("Ada Lovelace", "Annabella Lovelace", "Byron Lovelace", "Charles Lovelace");
        assertThat(names(run(members().name("ada love")))).containsExactly("Ada Lovelace");
        assertThat(names(run(members().name("zzz")))).isEmpty();
    }

    @Test
    @DisplayName("Deleted / inactive: hidden by default, included on request, never confused with non-member type")
    void statusHandling() {
        Map<String, Object> all = run(members());
        assertThat(names(all)).doesNotContain("Charles Babbage", "Emmy Noether", "Edsger Dijkstra");

        Map<String, Object> withInactive = run(members().showInactive(true));
        assertThat(names(withInactive)).contains("Charles Babbage").doesNotContain("Emmy Noether", "Edsger Dijkstra");
        assertThat(row(withInactive, "Charles Babbage")).containsEntry("status", "Inactive").containsEntry("statusReason", "Family inactive");

        Map<String, Object> withDeleted = run(members().showDeleted(true));
        assertThat(names(withDeleted)).contains("Emmy Noether", "Edsger Dijkstra").doesNotContain("Charles Babbage");
        assertThat(row(withDeleted, "Emmy Noether")).containsEntry("status", "Deleted").containsEntry("statusReason", "Family deleted");
        assertThat(row(withDeleted, "Edsger Dijkstra")).containsEntry("status", "Deleted").containsEntry("statusReason", null);
        // A deleted / inactive Member is still a Member, never a non-member.
        assertThat(names(run(members().showDeleted(true).showInactive(true).memberType("nonmember"))))
                .containsExactly("Grace Hopper", "Alan Turing", "Joan Turing");
    }

    // ── Families report ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Anniversary month: primary's anniversary, else the spouse's; both names shown; blanks handled")
    void anniversaryMonthFamilies() {
        Map<String, Object> june = run(families().anniversaryMonth(6));
        List<Map<String, Object>> rows = rows(june);
        assertThat(rows).extracting(r -> r.get("familyName")).containsExactly("Lovelace Family", "Turing Family");

        Map<String, Object> lovelace = rows.get(0);
        assertThat(lovelace).containsEntry("primaryName", "Ada Lovelace")
                            .containsEntry("spouseName", "Charles Lovelace")     // legacy "Husband" is the spouse
                            .containsEntry("anniversary", "June 12, 1999")
                            .containsEntry("anniversaryFrom", "primary");
        Map<String, Object> turing = rows.get(1);
        assertThat(turing).containsEntry("primaryName", "Alan Turing")
                          .containsEntry("spouseName", "Joan Turing")
                          .containsEntry("anniversary", "June 1, 2001")
                          .containsEntry("anniversaryFrom", "spouse");           // Alan has none → Joan's

        assertThat(rows(run(families().anniversaryMonth(7)))).isEmpty();
        // A family with no anniversary and no spouse shows nulls, not an error.
        Map<String, Object> hopper = rows(run(families().name("Hopper"))).get(0);
        assertThat(hopper).containsEntry("spouseName", null).containsEntry("anniversary", null);
    }

    @Test
    @DisplayName("Family search: one row per family even when several members match; primary flagged, all members listed")
    void familySearchNoDuplicates() {
        Map<String, Object> res = run(families().name("Lovelace"));   // four members match
        List<Map<String, Object>> rows = rows(res);
        assertThat(rows).hasSize(1);
        Map<String, Object> fam = rows.get(0);
        assertThat(fam).containsEntry("familyName", "Lovelace Family")
                       .containsEntry("primaryName", "Ada Lovelace")
                       .containsEntry("primaryRole", "Head of Household")
                       .containsEntry("memberCount", 4)
                       .containsEntry("city", "Olathe").containsEntry("state", "KS").containsEntry("zip", "66062")
                       .containsEntry("primaryPhone", "913-555-0100");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) fam.get("members");
        assertThat(members).extracting(m -> m.get("name"))
                .containsExactly("Ada Lovelace", "Charles Lovelace", "Annabella Lovelace", "Byron Lovelace"); // primary, spouse, then the rest
        assertThat(members).filteredOn(m -> Boolean.TRUE.equals(m.get("primary"))).extracting(m -> m.get("name"))
                .containsExactly("Ada Lovelace");
        assertThat(members.get(1)).containsEntry("role", "Spouse");   // canonical, not "Husband"

        // A member-level criterion (birthday month) picks the family once, too.
        assertThat(rows(run(families().birthdayMonth(3)))).extracting(r -> r.get("familyName")).containsExactly("Lovelace Family");
    }

    @Test
    @DisplayName("Family type and location apply to the primary member; inactive / deleted families follow Show Inactive / Deleted")
    void familyTypeAndStatus() {
        assertThat(rows(run(families()))).extracting(r -> r.get("familyName"))
                .containsExactly("Dijkstra Family", "Hopper Family", "Lovelace Family", "Turing Family");
        assertThat(rows(run(families().memberType("nonmember")))).extracting(r -> r.get("familyName"))
                .containsExactly("Hopper Family", "Turing Family");
        assertThat(rows(run(families().memberType("Guest")))).extracting(r -> r.get("familyName")).containsExactly("Turing Family");
        assertThat(rows(run(families().memberType("member")))).extracting(r -> r.get("familyName")).containsExactly("Dijkstra Family", "Lovelace Family");
        assertThat(rows(run(families().showInactive(true).memberType("nonmember")))).extracting(r -> r.get("familyName"))
                .containsExactly("Hopper Family", "Turing Family");                 // Babbage (inactive Member) is not a non-member
        assertThat(rows(run(families().showInactive(true)))).extracting(r -> r.get("familyName")).contains("Babbage Family");
        assertThat(rows(run(families().showDeleted(true)))).extracting(r -> r.get("familyName")).contains("Noether Family");
        assertThat(rows(run(families().city("Olathe").zip("66061")))).extracting(r -> r.get("familyName")).containsExactly("Dijkstra Family");
        // Dijkstra: the Head is deleted → Ria (the only listed member) becomes the primary unless deleted rows are shown.
        assertThat(rows(run(families().name("Dijkstra"))).get(0)).containsEntry("primaryName", "Ria Dijkstra");
        assertThat(rows(run(families().name("Dijkstra").showDeleted(true))).get(0)).containsEntry("primaryName", "Edsger Dijkstra");
    }

    // ── Isolation & privacy ──────────────────────────────────────────────────

    @Test
    @DisplayName("Only the caller's church is queried; a blank client id never runs an unscoped query")
    void tenantIsolation() {
        Map<String, Object> res = run(members().birthdayMonth(3));
        verify(memberRepo).findAllWithFamilyByAppUserAll(OURS);
        verify(memberRepo, never()).findAllWithFamilyByAppUserAll(THEIRS);
        verify(memberRepo, never()).findAllWithFamilyByAppUserAll((String) null);
        assertThat(names(res)).doesNotContain("Other Church");

        assertThat(rows(service.run(members().build(), null, false))).isEmpty();
        assertThat(rows(service.run(members().build(), "  ", false))).isEmpty();
        verify(memberRepo, org.mockito.Mockito.times(1)).findAllWithFamilyByAppUserAll(anyString());   // only the OURS call above
    }

    @Test
    @DisplayName("Defence in depth: a row whose family belongs to another church is dropped even if the query returned it")
    void foreignRowDropped() {
        List<FamilyMember> poisoned = new ArrayList<>(ours);
        poisoned.add(member(999, family(99, THEIRS, false, false), "Head", "Leaked", "Row", "Male", "Member",
                3, 1, 1990, null, null, null, "Olathe", "16", "66062", null, null));
        when(memberRepo.findAllWithFamilyByAppUserAll(OURS)).thenReturn(poisoned);
        assertThat(names(run(members().birthdayMonth(3)))).doesNotContain("Leaked Row");
        assertThat(rows(run(families()))).extracting(r -> r.get("familyName")).doesNotContain("Row Family");
    }

    @Test
    @DisplayName("Member-portal viewers: private phone / e-mail / address are removed and never matched by a location filter")
    void privacyMasking() {
        // Staff see everything.
        Map<String, Object> staff = row(run(members().name("Grace")), 0);
        assertThat(staff).containsEntry("phone", "816-555-0100").containsEntry("city", "Kansas City").containsEntry("phoneHidden", false);
        assertThat(names(run(members().city("Kansas City")))).containsExactly("Grace Hopper");

        // Masked viewers do not.
        Map<String, Object> masked = row(service.run(members().name("Grace").build(), OURS, true), 0);
        assertThat(masked).containsEntry("phone", null).containsEntry("phoneHidden", true)
                          .containsEntry("email", "grace@example.test").containsEntry("emailHidden", false)
                          .containsEntry("city", null).containsEntry("state", null).containsEntry("zip", null)
                          .containsEntry("address1", null).containsEntry("addressHidden", true);
        assertThat(names(service.run(members().city("Kansas City").build(), OURS, true))).isEmpty();
        assertThat(names(service.run(members().state("MO").build(), OURS, true))).isEmpty();
        // Families mode masks the primary's fields the same way.
        Map<String, Object> fam = rows(service.run(families().name("Hopper").build(), OURS, true)).get(0);
        assertThat(fam).containsEntry("primaryPhone", null).containsEntry("phoneHidden", true)
                       .containsEntry("city", null).containsEntry("addressHidden", true);
        assertThat(rows(service.run(families().city("Kansas City").build(), OURS, true))).isEmpty();
    }

    // ── Headings ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("The heading reflects the report type and filters; the summary lists every criterion")
    void headings() {
        assertThat(run(members().birthdayMonth(1))).containsEntry("title", "Members with Birthdays in January");
        assertThat(run(families().anniversaryMonth(6))).containsEntry("title", "Families with Anniversaries in June");
        assertThat(run(members().city("Olathe").state("16"))).containsEntry("title", "Members by City and State");
        assertThat(run(members().city("Olathe").state("16").zip("66062"))).containsEntry("title", "Members by City, State and ZIP/Postal Code");
        assertThat(run(families())).containsEntry("title", "Family Directory Report");
        assertThat(run(members())).containsEntry("title", "Member Directory Report")
                                  .containsEntry("filterSummary", "All records");
        assertThat(run(families().memberType("nonmember"))).containsEntry("title", "Non-Member Families");
        Map<String, Object> combo = run(members().birthdayMonth(3).state("KS").gender("Female").showInactive(true));
        assertThat((String) combo.get("filterSummary"))
                .isEqualTo("Birthday month: March · State: KS · Gender: Female · Including inactive");
        assertThat(combo).containsKey("generatedAt").containsEntry("total", 2).containsEntry("reportType", "members");
    }

    @Test
    @DisplayName("Dates format readably with or without day and year")
    void dateFormatting() {
        Map<String, Object> ada = row(run(members().name("Ada")), 0);
        assertThat(ada).containsEntry("birthday", "March 15, 1980").containsEntry("anniversary", "June 12, 1999");
        assertThat(row(run(members().name("Annabella")), 0)).containsEntry("birthday", "March 20");
        assertThat(row(run(members().name("Ria")), 0)).containsEntry("birthday", null);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> run(CriteriaBuilder b) { return service.run(b.build(), OURS, false); }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> res) {
        return (List<Map<String, Object>>) res.get("rows");
    }

    private static List<String> names(Map<String, Object> res) {
        return rows(res).stream().map(r -> (String) r.get("name")).collect(Collectors.toList());
    }

    private static Map<String, Object> row(Map<String, Object> res, int i) { return rows(res).get(i); }

    private static Map<String, Object> row(Map<String, Object> res, String name) {
        return rows(res).stream().filter(r -> name.equals(r.get("name"))).findFirst().orElseThrow();
    }

    private static CriteriaBuilder members()  { return new CriteriaBuilder("members"); }
    private static CriteriaBuilder families() { return new CriteriaBuilder("families"); }

    /** Small builder so each test reads as the filters it sets. */
    static final class CriteriaBuilder {
        String type; Integer bm, am; String city = "", state = "", zip = "", gender = "", role = "", memberType = "", name = "";
        boolean del, inact;
        CriteriaBuilder(String type) { this.type = type; }
        CriteriaBuilder birthdayMonth(int m)    { bm = m; return this; }
        CriteriaBuilder anniversaryMonth(int m) { am = m; return this; }
        CriteriaBuilder city(String v)   { city = v; return this; }
        CriteriaBuilder state(String v)  { state = v; return this; }
        CriteriaBuilder zip(String v)    { zip = v; return this; }
        CriteriaBuilder gender(String v) { gender = v; return this; }
        CriteriaBuilder role(String v)   { role = v; return this; }
        CriteriaBuilder memberType(String v) { memberType = v; return this; }
        CriteriaBuilder name(String v)   { name = v; return this; }
        CriteriaBuilder showDeleted(boolean v)  { del = v; return this; }
        CriteriaBuilder showInactive(boolean v) { inact = v; return this; }
        Criteria build() { return new Criteria(type, bm, am, city, state, zip, gender, role, memberType, name, del, inact); }
    }

    private static Family family(int id, String clientId, boolean inactive, boolean deleted) {
        Family f = new Family();
        f.setId(id);
        f.setAppClientId(clientId);
        f.setInactive(inactive);
        f.setDeleteFlag(deleted);
        return f;
    }

    private static FamilyMember member(int id, Family f, String role, String first, String last, String gender, String type,
                                       Integer bm, Integer bd, Integer by, Integer am, Integer ad, Integer ay,
                                       String city, String state, String zip, String phone, String email) {
        FamilyMember m = new FamilyMember();
        m.setId(id); m.setFamily(f); m.setRole(role); m.setFirstName(first); m.setLastName(last);
        m.setGender(gender); m.setMemberType(type);
        m.setBirthdayMonth(bm); m.setBirthdayDay(bd); m.setBirthdayYear(by);
        m.setAnniversaryMonth(am); m.setAnniversaryDay(ad); m.setAnniversaryYear(ay);
        m.setAddress1("1 Main St"); m.setCity(city); m.setState(state); m.setPinCode(zip); m.setCountry("USA");
        m.setPhone(phone); m.setEmail(email);
        m.setAppClientId(f.getAppClientId());
        f.getMembers().add(m);
        return m;
    }
}
