package com.churchgeniuspro.family;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.churchgeniuspro.service.FamilyServiceStatusAccess;

/**
 * View Family → Member Search: every row carries an effective status
 * (member OR family deleted → Deleted; member OR family inactive → Inactive),
 * shown in a Status column, and deleted rows use the read-only style.
 */
class MemberSearchStatusTest {

    @Test
    void effectiveStatusRule() {
        assertThat(FamilyServiceStatusAccess.status(false, false, false, false)).isEqualTo("Active");
        assertThat(FamilyServiceStatusAccess.status(true,  false, false, false)).isEqualTo("Deleted");
        assertThat(FamilyServiceStatusAccess.status(false, true,  false, false)).isEqualTo("Deleted");
        assertThat(FamilyServiceStatusAccess.status(false, false, true,  false)).isEqualTo("Inactive");
        assertThat(FamilyServiceStatusAccess.status(false, false, false, true )).isEqualTo("Inactive");
        assertThat(FamilyServiceStatusAccess.status(false, true,  true,  false)).isEqualTo("Deleted");

        assertThat(FamilyServiceStatusAccess.reason(true,  true,  false, false)).isNull();
        assertThat(FamilyServiceStatusAccess.reason(false, true,  false, false)).isEqualTo("Family deleted");
        assertThat(FamilyServiceStatusAccess.reason(false, false, false, true )).isEqualTo("Family inactive");
        assertThat(FamilyServiceStatusAccess.reason(false, false, true,  true )).isNull();
        assertThat(FamilyServiceStatusAccess.reason(false, false, false, false)).isNull();
    }

    @Test
    void pageShowsStatusColumnAndDisabledDeletedRows() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/viewFamily.html"), StandardCharsets.UTF_8);
        assertThat(html).contains("<th>Email</th><th>Status</th>");
        assertThat(html).contains("tr.ms-row-deleted");
        assertThat(html).contains("aria-disabled=\"true\"");
        assertThat(html).contains("function memberStatusOf(m)");
        assertThat(html).contains("id=\"mfShowDeleted\" onchange=\"onMemberFilterChange()\"");
    }
}
