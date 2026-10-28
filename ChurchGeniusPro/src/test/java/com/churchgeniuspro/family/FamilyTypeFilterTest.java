package com.churchgeniuspro.family;

import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import com.churchgeniuspro.service.FamilyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * /viewfamily → Type filter: a family's type is its primary member's Member Type
 * ({@code Member} when blank); Guest / Visitor primaries make a non-member family.
 * The filter narrows the existing Deleted / Inactive status filter and never
 * reclassifies an inactive or deleted Member family as a non-member one.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("/api/families — family type (non-member families)")
class FamilyTypeFilterTest {

    private static final String OURS = "CHR-ours";

    @Mock FamilyRepository       familyRepo;
    @Mock FamilyMemberRepository memberRepo;

    FamilyService service;

    @BeforeEach
    void setUp() {
        service = new FamilyService(familyRepo, memberRepo);
        when(familyRepo.findFamilyListProjection(OURS)).thenReturn(List.of(
                row(1, false, false, 10, "Ada",   "Lovelace", "Head of Household", "Member",  false, false),
                row(1, false, false, 11, "Guesty","Lovelace", "Adult",             "Guest",   false, false),   // a guest in a member family
                row(2, false, false, 20, "Alan",  "Turing",   "Head",              "Guest",   false, false),
                row(3, false, false, 30, "Grace", "Hopper",   "Head of Household", "Visitor", false, false),
                row(4, true,  false, 40, "Chas",  "Babbage",  "Head of Household", "Member",  false, false),   // inactive Member family
                row(5, false, true,  50, "Emmy",  "Noether",  "Head of Household", "Member",  false, false),   // deleted Member family
                row(6, false, true,  60, "Del",   "Visitorson","Head of Household","Visitor", false, false),   // deleted Visitor family
                row(7, false, false, 70, "Blank", "Type",     "Head of Household", null,      false, false),   // no type = Member
                row(8, false, false, 80, "Head",  "Gone",     "Head of Household", "Member",  true,  false),   // deleted head → next member is primary
                row(8, false, false, 81, "Vis",   "Gone",     "Adult",             "Visitor", false, false)
        ));
        when(familyRepo.findPrimaryMemberPhotosByAppUser(OURS)).thenReturn(List.of());
    }

    @Test
    @DisplayName("No type filter: the list is exactly what it was, plus familyType / nonMember on every row")
    void unchangedByDefault() {
        List<Map<String, Object>> items = items(service.getFamilies("", false, false, OURS, -1, 0));
        assertThat(items).extracting(r -> r.get("familyName"))
                .containsExactly("Lovelace Family", "Turing Family", "Type Family", "Hopper Family", "Gone Family"); // sorted by primary's first name
        assertThat(items).extracting(r -> r.get("familyType"))
                .containsExactly("Member", "Guest", "Member", "Visitor", "Visitor");
        assertThat(byName(items, "Lovelace Family")).containsEntry("nonMember", false);   // the guest member does not make it a guest family
        assertThat(byName(items, "Turing Family")).containsEntry("nonMember", true);
        assertThat(byName(items, "Type Family")).containsEntry("familyType", "Member");
    }

    @Test
    @DisplayName("member / nonmember / Guest / Visitor filters on active families")
    void typeFilters() {
        assertThat(names(service.getFamilies("", false, false, "member", OURS, -1, 0)))
                .containsExactly("Lovelace Family", "Type Family");
        assertThat(names(service.getFamilies("", false, false, "nonmember", OURS, -1, 0)))
                .containsExactly("Turing Family", "Hopper Family", "Gone Family");
        assertThat(names(service.getFamilies("", false, false, "Guest", OURS, -1, 0)))
                .containsExactly("Turing Family");
        assertThat(names(service.getFamilies("", false, false, "visitor", OURS, -1, 0)))
                .containsExactly("Hopper Family", "Gone Family");
        assertThat(names(service.getFamilies("", false, false, "Alien", OURS, -1, 0))).isEmpty();
        assertThat(service.getFamilies("", false, false, "nonmember", OURS, -1, 0)).containsEntry("total", 3);
    }

    @Test
    @DisplayName("Status filters come first: an inactive or deleted Member family is never a non-member family")
    void statusAndType() {
        assertThat(names(service.getFamilies("", false, true, "nonmember", OURS, -1, 0))).isEmpty();          // Babbage is inactive but a Member
        assertThat(names(service.getFamilies("", false, true, "member",    OURS, -1, 0))).containsExactly("Babbage Family");
        assertThat(names(service.getFamilies("", true,  false, "nonmember", OURS, -1, 0))).containsExactly("Visitorson Family");
        assertThat(names(service.getFamilies("", true,  false, "member",    OURS, -1, 0))).containsExactly("Noether Family");
        assertThat(names(service.getFamilies("", true,  false, "",          OURS, -1, 0))).containsExactly("Visitorson Family", "Noether Family");
    }

    @Test
    @DisplayName("Name search and paging still apply with a type filter")
    void searchAndPaging() {
        assertThat(names(service.getFamilies("tur", false, false, "nonmember", OURS, -1, 0))).containsExactly("Turing Family");
        Map<String, Object> page = service.getFamilies("", false, false, "nonmember", OURS, 0, 2);
        assertThat(names(page)).hasSize(2);
        assertThat(page).containsEntry("total", 3);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> res) {
        return (List<Map<String, Object>>) res.get("items");
    }

    private static List<String> names(Map<String, Object> res) {
        return items(res).stream().map(r -> (String) r.get("familyName")).collect(Collectors.toList());
    }

    private static Map<String, Object> byName(List<Map<String, Object>> items, String name) {
        return items.stream().filter(r -> name.equals(r.get("familyName"))).findFirst().orElseThrow();
    }

    /** One row of the /viewfamily projection (family + member scalar columns, no photo). */
    private static Map<String, Object> row(int familyId, boolean inactive, boolean deleted,
                                           int memberId, String first, String last, String role, String memberType,
                                           boolean memberDeleted, boolean memberInactive) {
        Map<String, Object> r = new HashMap<>();
        r.put("familyId", familyId);
        r.put("inactive", inactive);
        r.put("deleteFlag", deleted);
        r.put("appClientId", OURS);
        r.put("memberId", memberId);
        r.put("firstName", first);
        r.put("lastName", last);
        r.put("role", role);
        r.put("memberType", memberType);
        r.put("memberDeleteFlag", memberDeleted);
        r.put("memberInactive", memberInactive);
        return r;
    }
}
