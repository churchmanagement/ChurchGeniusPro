package com.churchgeniuspro.family;

import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.FamilyRepository;
import com.churchgeniuspro.service.FamilyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * /viewfamily address-label cards (2026-11-02): the family list carries the primary
 * member's phone and Disable Alerts setting (user decision: the card's Alert On / Off
 * follows the person the card is named after), and the page renders cards with the
 * same actions as the old table.
 */
@DisplayName("/viewfamily cards — primary phone + alert status")
class FamilyCardFieldsTest {

    private static final String CHURCH = "CHR-cards";

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    private static Map<String, Object> row(int fid, int mid, String first, String last, String role,
                                           String phone, boolean alertsOff, boolean memberDeleted) {
        Map<String, Object> r = new HashMap<>();
        r.put("familyId", fid); r.put("inactive", false); r.put("deleteFlag", false); r.put("appClientId", CHURCH);
        r.put("memberId", mid); r.put("firstName", first); r.put("lastName", last); r.put("role", role);
        r.put("memberType", "Member"); r.put("memberDeleteFlag", memberDeleted); r.put("memberInactive", false);
        r.put("phone", phone); r.put("disableAlerts", alertsOff);
        return r;
    }

    @Test
    @DisplayName("Phone and Alert come from the primary member, not from other members")
    void primaryMembersPhoneAndAlert() {
        FamilyRepository repo = mock(FamilyRepository.class);
        when(repo.findFamilyListProjection(CHURCH)).thenReturn(List.of(
                row(1, 10, "Kid",   "Varghese", "Son",               "5550000001", true,  false),
                row(1, 11, "Amly",  "Varghese", "Head of Household", "1242321023", false, false),
                row(2, 20, "Old",   "Stanly",   "Head",              "5550000002", false, true),    // deleted head
                row(2, 21, "Sam",   "Stanly",   "Spouse",            null,         true,  false),   // → primary
                row(3, 30, "Solo",  "Nophone",  "Head of Household", null,         false, false)));
        when(repo.findPrimaryMemberPhotosByAppUser(CHURCH)).thenReturn(List.of());
        FamilyService svc = new FamilyService(repo, mock(FamilyMemberRepository.class));

        Map<Object, Map<String, Object>> byId = new HashMap<>();
        items(svc.getFamilies("", false, false, CHURCH, -1, 0)).forEach(m -> byId.put(m.get("id"), m));

        assertThat(byId.get(1)).containsEntry("primaryPhone", "1242321023").containsEntry("primaryAlertsDisabled", false)
                               .containsEntry("memberCount", 2L);
        assertThat(byId.get(2)).containsEntry("primaryFirstName", "Sam").containsEntry("primaryAlertsDisabled", true);
        assertThat((String) byId.get(2).get("primaryPhone")).isEmpty();
        assertThat((String) byId.get(3).get("primaryPhone")).isEmpty();
        assertThat(byId.get(3)).containsEntry("primaryAlertsDisabled", false);
    }

    @Test
    @DisplayName("The list query reads phone + disable_alerts and still never reads photo_data")
    void projectionColumns() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/churchgeniuspro/repository/FamilyRepository.java"));
        int i = src.indexOf("List<Map<String, Object>> findFamilyListProjection");
        String query = src.substring(src.lastIndexOf("@Query", i), i);
        assertThat(query).contains("m.phone AS phone").contains("m.disable_alerts AS disableAlerts")
                         .doesNotContain("photo_data").contains(":appClientId");
    }

    @Test
    @DisplayName("Page: cards keep Edit (permission-checked), View Members, name → /family view, bulk select, sort, Load More")
    void pageContract() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/viewFamily.html"));
        assertThat(html).contains("id=\"familyGrid\"").contains("function buildFamilyCard(f)")
                .contains("postNavigate('/family', {id:${f.id}, mode:'view'})")
                .contains("postNavigate('/family', {id:${f.id}, mode:'edit'})")
                .contains("window.CGP_PERMS['admin.family.edit'] === false")
                .contains("onclick=\"showMembers(${f.id})\"")
                .contains("onRowCheck(${f.id}, this.checked)")
                .contains("id=\"selectAll\"").contains("togglePrimarySort()").contains("loadMoreFamilies()")
                .contains("'Alert Off'").contains("'Alert On'")
                .contains("fam-status-${status}")
                .contains("DEFAULT_FAMILY_SVG")
                .doesNotContain("id=\"familyTableBody\"");
        // the photo only reaches the img src when it is an image data URL or a same-site path
        assertThat(html).contains("function safePhotoSrc(src)");
    }
}
