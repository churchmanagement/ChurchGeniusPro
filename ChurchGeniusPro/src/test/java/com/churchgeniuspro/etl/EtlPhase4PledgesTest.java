package com.churchgeniuspro.etl;

import com.churchgeniuspro.hibernate.*;
import com.churchgeniuspro.service.EtlLoadService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Migration Phase 4 — pledge campaigns and pledges: references, match tiers, money rules, load, rollback. */
@DisplayName("ETL Phase 4 — pledge campaigns and pledges")
class EtlPhase4PledgesTest extends EtlPhase4Support {

    private PledgeCampaign liveCampaign(int id, String name, Integer fundId) {
        PledgeCampaign c = new PledgeCampaign(); c.setId(id); c.setClientId(TENANT); c.setName(name); c.setSubSourceId(fundId); return liveRow(c);
    }

    @Test
    @DisplayName("campaigns: an existing fund is linked, a missing one is announced as created; name + fund → STRONG, name only → POSSIBLE (REVIEW); start/end checked")
    void campaignValidation() {
        SubSource tithe = new SubSource(); tithe.setId(7); tithe.setSourceName("Building Fund");
        when(live.funds(TENANT)).thenReturn(List.of(tithe));
        when(live.campaigns(TENANT)).thenReturn(List.of(liveCampaign(1, "New Roof", 7), liveCampaign(2, "Mission Trip", null)));
        StagingRecord strong = staged("pledge_campaign", "C1", v("campaignName", "new roof", "fund", "building fund", "startDate", "2026-01-01"));
        StagingRecord possible = staged("pledge_campaign", "C2", v("campaignName", "Mission Trip", "fund", "Missions"));
        StagingRecord fresh = staged("pledge_campaign", "C3", v("campaignName", "Organ Restoration", "startDate", "2026-05-01", "endDate", "2026-04-01"));

        var sum = svc.validate(RUN, TENANT, "actor", "pledge_campaign");

        assertThat(strong.getMatchTier()).isEqualTo("STRONG"); assertThat(strong.getDedupeMatchId()).isEqualTo(1L); assertThat(strong.getDedupeAction()).isEqualTo("SKIP");
        assertThat(links(strong)).containsEntry("fund", "l7");
        assertThat(possible.getMatchTier()).isEqualTo("POSSIBLE"); assertThat(possible.getRowStatus()).isEqualTo("REVIEW");
        assertThat(links(possible)).containsEntry("fund", "new:Missions");
        assertThat(msgText(possible)).contains("Fund 'Missions' does not exist yet and will be created");
        assertThat(fresh.getRowStatus()).isEqualTo("ERROR"); assertThat(msgText(fresh)).contains("End date is before the start date");
        assertThat(sum.strong()).isEqualTo(1); assertThat(sum.possible()).isEqualTo(1); assertThat(sum.review()).isEqualTo(1); assertThat(sum.error()).isEqualTo(1);
        verify(runService).recordAudit(eq(RUN), eq(TENANT), eq("actor"), eq("VALIDATE"), any());
    }

    @Test
    @DisplayName("pledges: campaign by id → staged row; by name → live campaign; unknown / ambiguous → ERROR; person → member, unresolved → guest WARN, nothing → ERROR")
    void pledgeReferences() {
        when(live.campaigns(TENANT)).thenReturn(List.of(liveCampaign(1, "New Roof", null), liveCampaign(2, "Twin", null), liveCampaign(3, "Twin", null)));
        StagingRecord campaign = staged("pledge_campaign", "C-10", v("campaignName", "Mission Trip"));
        member(40, "Grace", "Okafor", null, null);
        StagingRecord byId = staged("pledge", "P1", v("campaignLink", "C-10", "personLink", "40", "pledgeAmount", "100"));
        StagingRecord byName = staged("pledge", "P2", v("campaignName", "new roof", "personLink", "999", "guestName", "Visiting Donor", "pledgeAmount", "50"));
        StagingRecord missing = staged("pledge", "P3", v("campaignName", "Nope", "guestName", "A", "pledgeAmount", "5"));
        StagingRecord ambiguous = staged("pledge", "P4", v("campaignName", "Twin", "guestName", "A", "pledgeAmount", "5"));
        StagingRecord noPerson = staged("pledge", "P5", v("campaignLink", "C-10", "pledgeAmount", "5"));
        StagingRecord zero = staged("pledge", "P6", v("campaignLink", "C-10", "guestName", "Z", "pledgeAmount", "0"));

        svc.validate(RUN, TENANT, "actor", "pledge");

        assertThat(links(byId)).containsEntry("parent", "s" + campaign.getId()).containsEntry("person", "m40");
        assertThat(byId.getRowStatus()).isEqualTo("VALID");
        assertThat(links(byName)).containsEntry("parent", "l1").doesNotContainKey("person");
        assertThat(byName.getRowStatus()).isEqualTo("WARN"); assertThat(msgText(byName)).contains("Could not resolve person '999'").contains("will load as guest 'Visiting Donor'");
        assertThat(missing.getRowStatus()).isEqualTo("ERROR"); assertThat(msgText(missing)).contains("The campaign 'Nope' was not found");
        assertThat(ambiguous.getRowStatus()).isEqualTo("ERROR"); assertThat(msgText(ambiguous)).contains("is ambiguous: 2 existing records");
        assertThat(noPerson.getRowStatus()).isEqualTo("ERROR"); assertThat(msgText(noPerson)).contains("needs a person (Person Id) or a name");
        assertThat(zero.getRowStatus()).isEqualTo("ERROR"); assertThat(msgText(zero)).contains("greater than zero");
    }

    @Test
    @DisplayName("pledge tiers: earlier-run link → EXACT; campaign + member → STRONG; campaign + amount for a guest → POSSIBLE; repeat in file → IN_FILE")
    void pledgeTiers() {
        liveCampaign(1, "New Roof", null);
        when(live.campaigns(TENANT)).thenReturn(List.of((PledgeCampaign) liveRows.get("pledge_campaign:1")));
        PledgeMember p = new PledgeMember(); p.setId(300); p.setCampaignId(1); p.setFamilyMemberId(40); p.setPledgeAmount(new BigDecimal("1200.00"));
        PledgeMember g = new PledgeMember(); g.setId(301); g.setCampaignId(1); g.setGuestName("Guest"); g.setPledgeAmount(new BigDecimal("250"));
        PledgeMember p41 = new PledgeMember(); p41.setId(302); p41.setCampaignId(1); p41.setFamilyMemberId(41); p41.setPledgeAmount(new BigDecimal("10"));
        when(live.pledges(TENANT)).thenReturn(List.of(p, g, p41));
        when(links.index(TENANT, "GENERIC", "pledge")).thenReturn(Map.of("PL-OLD", 300L, "PL-GONE", 999L));
        when(live.activeIds("pledge", TENANT)).thenReturn(Set.of(300L, 301L));
        member(40, "Grace", "Okafor", null, null);
        member(41, "Daniel", "Okafor", null, null);
        StagingRecord exact = staged("pledge", "PL-OLD", v("campaignName", "New Roof", "personLink", "40", "pledgeAmount", "1200"));
        StagingRecord gone = staged("pledge", "PL-GONE", v("campaignName", "New Roof", "guestName", "X", "pledgeAmount", "7"));
        StagingRecord strong = staged("pledge", "PL-2", v("campaignName", "New Roof", "personLink", "41", "pledgeAmount", "900"));
        StagingRecord possible = staged("pledge", "PL-3", v("campaignName", "New Roof", "guestName", "Someone", "pledgeAmount", "250.00"));
        StagingRecord inFile = staged("pledge", "PL-4", v("campaignName", "New Roof", "personLink", "41", "pledgeAmount", "900"));

        svc.validate(RUN, TENANT, "actor", "pledge");

        assertThat(exact.getMatchTier()).isEqualTo("EXACT"); assertThat(exact.getDedupeMatchId()).isEqualTo(300L); assertThat(exact.getDedupeAction()).isEqualTo("SKIP");
        assertThat(gone.getMatchTier()).isEqualTo("NONE");                              // a link to a removed pledge is ignored
        assertThat(strong.getMatchTier()).isEqualTo("STRONG"); assertThat(strong.getMatchDetail()).isEqualTo("campaign + member"); assertThat(strong.getDedupeMatchId()).isEqualTo(302L);
        assertThat(possible.getMatchTier()).isEqualTo("POSSIBLE"); assertThat(possible.getRowStatus()).isEqualTo("REVIEW"); assertThat(possible.getDedupeMatchId()).isEqualTo(301L);
        assertThat(msgText(possible)).contains("decide: skip, update or import as new").doesNotContain("merge");
        assertThat(inFile.getMatchTier()).isEqualTo("IN_FILE"); assertThat(inFile.getMatchDetail()).contains("row " + strong.getId());
    }

    @Test
    @DisplayName("pledges are money: never MERGE; UPDATE only from an EXACT/STRONG match (through the loader's decision guard)")
    void moneyRules() {
        EtlLoadService loader = new EtlLoadService(mock(com.churchgeniuspro.repository.ImportBatchRepository.class), familyRepo,
            mock(com.churchgeniuspro.repository.StagingIncomeRepository.class), mock(com.churchgeniuspro.repository.StagingExpenseRepository.class),
            mock(com.churchgeniuspro.repository.FamilyRepository.class), memberRepo, mock(com.churchgeniuspro.repository.IncomeRepository.class),
            mock(com.churchgeniuspro.repository.ExpenseRepository.class), mock(com.churchgeniuspro.service.EtlReferenceResolver.class), runService,
            mock(org.springframework.transaction.PlatformTransactionManager.class));
        loader.setRecordService(svc);
        StagingRecord r = staged("pledge", "P", v()); r.setRowStatus("REVIEW"); r.setMatchTier("POSSIBLE"); r.setDedupeMatchId(301L);
        assertThatThrownBy(() -> loader.updateRow(TENANT, "pledge", r.getId(), "MERGE", null)).hasMessageContaining("cannot be merged");
        assertThatThrownBy(() -> loader.updateRow(TENANT, "pledge", r.getId(), "UPDATE", null)).hasMessageContaining("exact or strong match");
        loader.updateRow(TENANT, "pledge", r.getId(), "INSERT", null);
        assertThat(r.getDedupeAction()).isEqualTo("INSERT"); assertThat(r.getRowStatus()).isEqualTo("WARN");
        StagingRecord ev = staged("event", "E", v()); ev.setRowStatus("REVIEW"); ev.setMatchTier("POSSIBLE"); ev.setDedupeMatchId(5L);
        loader.updateRow(TENANT, "event", ev.getId(), "MERGE", null);                     // non-financial types may merge
        assertThat(ev.getDedupeAction()).isEqualTo("MERGE");
        assertThatThrownBy(() -> loader.updateRow("CHR-other", "event", ev.getId(), "SKIP", null)).hasMessageContaining("not found for tenant");
    }

    @Test
    @DisplayName("load: campaign keeps its start date as created_date and gets the new fund; pledges attach to the member + household or keep the guest name; links learned")
    void load() {
        StagingRecord c = staged("pledge_campaign", "C-10", v("campaignName", "New Roof", "fund", "Building P4", "targetAmount", "40000.00",
            "startDate", "2026-01-01", "endDate", "2026-12-31"));
        svc.validate(RUN, TENANT, "actor", "pledge_campaign");
        int[] cc = load("pledge_campaign", 70);
        assertThat(cc).containsExactly(1, 0, 0, 0);
        PledgeCampaign live1 = savedOf(PledgeCampaign.class, 0);
        assertThat(live1.getCreatedDate()).isEqualTo(LocalDate.of(2026, 1, 1).atStartOfDay());
        assertThat(live1.getSubSourceId()).isEqualTo(62);                                  // created through the resolver session
        assertThat(live1.getTargetAmount()).isEqualByComparingTo("40000");
        verify(links).record(TENANT, "GENERIC", "pledge_campaign", "C-10", live1.getId().longValue(), RUN, 70L);
        verify(links).recordReferenceRows(eq(TENANT), eq("GENERIC"), eq(RUN), eq(70L), any());

        member(40, "Grace", "Okafor", null, null);
        person("701", "Grace", "Okafor", 40L);
        StagingRecord pm = staged("pledge", "PL-1", v("campaignLink", "C-10", "personLink", "701", "pledgeAmount", "1200.00", "monthlyAmount", "100"));
        StagingRecord pg = staged("pledge", "PL-2", v("campaignLink", "C-10", "guestName", "Visiting Donor", "pledgeAmount", "250", "amountCollected", "50"));
        svc.validate(RUN, TENANT, "actor", "pledge");
        assertThat(links(pm)).containsEntry("person", "m40");                              // staged person already loaded → live id
        int[] pc = load("pledge", 71);
        assertThat(pc).containsExactly(2, 0, 0, 0);
        PledgeMember m = savedOf(PledgeMember.class, 0), g = savedOf(PledgeMember.class, 1);
        assertThat(m.getCampaignId()).isEqualTo(live1.getId()); assertThat(m.getFamilyMemberId()).isEqualTo(40); assertThat(m.getFamilyId()).isEqualTo(400);
        assertThat(m.getAmountCollected()).isEqualByComparingTo("0");                     // member pledges compute collected from income
        assertThat(g.getFamilyMemberId()).isNull(); assertThat(g.getGuestName()).isEqualTo("Visiting Donor"); assertThat(g.getAmountCollected()).isEqualByComparingTo("50");
        assertThat(pm.getRowStatus()).isEqualTo("LOADED"); assertThat(pm.getTargetId()).isEqualTo(m.getId().longValue());
    }

    @Test
    @DisplayName("waitingFor: pledges whose campaign is staged in this run but not loaded are held back with a clear message")
    void waiting() {
        StagingRecord c = staged("pledge_campaign", "C-10", v("campaignName", "New Roof"));
        svc.validate(RUN, TENANT, "actor", "pledge_campaign");
        staged("pledge", "PL-1", v("campaignLink", "C-10", "guestName", "G", "pledgeAmount", "5"));
        svc.validate(RUN, TENANT, "actor", "pledge");
        svc.tagLoadable(RUN, "pledge"); svc.assignBatch(RUN, "pledge", 71L);
        assertThat(svc.waitingFor(71L, "pledge", TENANT)).contains("1 row(s) refer to pledge campaigns staged in this run that are not loaded yet");
        c.setRowStatus("LOADED"); c.setTargetId(1L);
        assertThat(svc.waitingFor(71L, "pledge", TENANT)).isNull();
    }

    @Test
    @DisplayName("rollback: an inserted campaign the church has since pledged to is refused (nothing changed); otherwise inserts are soft-deleted and updates restored")
    void rollback() {
        PledgeCampaign inserted = liveCampaign(11, "New Roof", null);
        PledgeCampaign updated = liveCampaign(12, "Old Name", null); updated.setTargetAmount(new BigDecimal("10.00"));
        StagingRecord ins = staged("pledge_campaign", "C1", v()); ins.setBatchId(70L); ins.setRowStatus("LOADED"); ins.setTargetId(11L);
        StagingRecord upd = staged("pledge_campaign", "C2", v()); upd.setBatchId(70L); upd.setRowStatus("LOADED"); upd.setTargetId(12L);
        upd.setBeforeImage("{\"name\":\"Original\",\"subSourceId\":null,\"targetAmount\":\"5000.00\",\"endDate\":\"2026-12-31\",\"description\":null,\"status\":\"Active\",\"otherGifts\":null}");
        when(live.dependents("pledge_campaign", 11L, TENANT)).thenReturn(2L);

        assertThatThrownBy(() -> svc.rollback(batch(70, "pledge_campaign", "LOADED"), TENANT))
            .isInstanceOf(EtlLoadService.LoadException.class).hasMessageContaining("pledge campaigns created by this import now have records added outside it: #11 (2)");
        assertThat(inserted.isDeleteFlag()).isFalse(); assertThat(updated.getName()).isEqualTo("Old Name");      // refused before any change

        when(live.dependents("pledge_campaign", 11L, TENANT)).thenReturn(0L);
        assertThat(svc.rollback(batch(70, "pledge_campaign", "LOADED"), TENANT)).isEqualTo(2);
        assertThat(inserted.isDeleteFlag()).isTrue();
        assertThat(updated.getName()).isEqualTo("Original"); assertThat(updated.getTargetAmount()).isEqualByComparingTo("5000");
        assertThat(updated.getEndDate()).isEqualTo(LocalDate.of(2026, 12, 31)); assertThat(updated.isDeleteFlag()).isFalse();
        assertThat(ins.getRowStatus()).isEqualTo("ROLLED_BACK"); assertThat(upd.getRowStatus()).isEqualTo("ROLLED_BACK");
    }
}
