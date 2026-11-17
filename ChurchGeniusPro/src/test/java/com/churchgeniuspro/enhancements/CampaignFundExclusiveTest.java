package com.churchgeniuspro.enhancements;

import com.churchgeniuspro.controller.PledgeController;
import com.churchgeniuspro.hibernate.PledgeCampaign;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.PledgeCampaignRepository;
import com.churchgeniuspro.repository.PledgeMemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 2026-11-16 (items 1 + 8) — the one-campaign-per-fund rule and the page wiring. The figures themselves are
 * proven on PostgreSQL in {@code integration/CampaignFundExclusiveIT}.
 */
@DisplayName("Campaign-specific fund — rule and wiring")
class CampaignFundExclusiveTest {

    static PledgeCampaign c(Integer id, String name, Integer fund, boolean exclusive, String status) {
        PledgeCampaign c = new PledgeCampaign();
        c.setId(id); c.setName(name); c.setSubSourceId(fund); c.setFundExclusive(exclusive); c.setStatus(status); c.setClientId("CHR-1");
        return c;
    }

    @Test @DisplayName("an exclusive campaign needs the fund to itself among ACTIVE campaigns; ordinary campaigns may still share a fund")
    void rule() throws Exception {
        PledgeCampaignRepository repo = mock(PledgeCampaignRepository.class);
        PledgeController ctl = new PledgeController(repo, mock(PledgeMemberRepository.class), mock(FamilyMemberRepository.class), mock(IncomeRepository.class));
        Method m = PledgeController.class.getDeclaredMethod("fundRuleProblem", PledgeCampaign.class, String.class);
        m.setAccessible(true);

        when(repo.findLiveActiveOnFund("CHR-1", 7)).thenReturn(List.of(c(1, "Existing", 7, false, "Active")));
        assertThat((String) m.invoke(ctl, c(null, "New", 7, false, "Active"), "CHR-1")).as("two ordinary campaigns on one fund — allowed as before").isNull();
        assertThat((String) m.invoke(ctl, c(null, "New", 7, true, "Active"), "CHR-1")).contains("“Existing” is active on this fund");
        assertThat((String) m.invoke(ctl, c(1, "Existing", 7, true, "Active"), "CHR-1")).as("itself is not a conflict").isNull();
        assertThat((String) m.invoke(ctl, c(null, "Closed one", 7, true, "Closed"), "CHR-1")).as("not active → not checked").isNull();
        assertThat((String) m.invoke(ctl, c(null, "No fund", null, true, "Active"), "CHR-1")).contains("Choose a Subcategory (Fund)");
        assertThat((String) m.invoke(ctl, c(null, "No fund", null, false, "Active"), "CHR-1")).isNull();

        when(repo.findLiveActiveOnFund("CHR-1", 8)).thenReturn(List.of(c(2, "Owner", 8, true, "Active")));
        assertThat((String) m.invoke(ctl, c(null, "New", 8, false, "Active"), "CHR-1")).contains("used exclusively by the active campaign “Owner”");
    }

    @Test @DisplayName("default off, migration additive and mirrored, queries tenant-bound, page explains the option")
    void wiring() throws Exception {
        assertThat(new PledgeCampaign().isFundExclusive()).isFalse();
        String v26 = Files.readString(Path.of("src/main/resources/db/migration/V26__pledge_campaign_fund_exclusive.sql"));
        assertThat(v26).contains("to_regclass").contains("ADD COLUMN IF NOT EXISTS fund_exclusive BOOLEAN NOT NULL DEFAULT false");
        assertThat(v26.toUpperCase()).doesNotContain("DROP ").doesNotContain("DELETE FROM").doesNotContain("UPDATE ");
        assertThat(Files.readString(Path.of("scripts/sql/migrate_production.sql"))).contains("fund_exclusive BOOLEAN NOT NULL DEFAULT false");

        String repo = Files.readString(Path.of("src/main/java/com/churchgeniuspro/repository/IncomeRepository.java"));
        String block = repo.substring(repo.indexOf("// ── Campaign-specific fund"), repo.indexOf("// ── Tenant-scoped lookups (security audit, week 1)"));
        assertThat(block).doesNotContain(":appClientId IS NULL");          // the church is always required
        assertThat(block.split("@Query").length - 1).isEqualTo(5);
        for (String q : block.split("@Query")) {
            if (!q.contains("SELECT")) continue;
            assertThat(q).containsAnyOf("i.appClientId = :appClientId", "i.app_client_id = :appClientId")
                         .containsAnyOf("i.deleteFlag = false", "i.delete_flag = false");
        }
        String ctl = Files.readString(Path.of("src/main/java/com/churchgeniuspro/controller/PledgeController.java"));
        assertThat(ctl).contains("if (c.isFundExclusive() && c.getSubSourceId() != null) {\n            return exclusiveTotals(cid, c, rows, pledged);");

        String page = Files.readString(Path.of("src/main/resources/static/pledges.html"));
        assertThat(page).contains("Use this Fund exclusively for this Campaign").contains("id=\"cf_fundExclusive\"").contains("id=\"cf_fundNote\"")
                .contains("Outside Contributions").contains("Collected from Pledgers").contains("Total Collected").contains("Pending Pledge")
                .contains("fundExclusive: $('cf_fundExclusive').checked")
                .contains("Each gift is counted once").contains("/outside'");
        String note = page.substring(page.indexOf("function updateFundNote"), page.indexOf("function openOutsideModal"));
        assertThat(note).doesNotContain("innerHTML");
    }
}
