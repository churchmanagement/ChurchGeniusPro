package com.churchgeniuspro.pledge;

import com.churchgeniuspro.controller.PledgeController;
import com.churchgeniuspro.hibernate.PledgeCampaign;
import com.churchgeniuspro.hibernate.PledgeMember;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.PledgeCampaignRepository;
import com.churchgeniuspro.repository.PledgeMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Financial audit M4: a pledge's "collected" figure was the member's entire
 * lifetime giving to the campaign's fund, with no date bound at all — so two
 * campaigns sharing a fund (a completed drive and its successor, or two
 * concurrent funds) both reported the same gifts as their own, and a single
 * incoming gift credited every active campaign on the fund's stored counter.
 * {@link PledgeController#collectedForPledge} now bounds the sum to the
 * specific campaign's own window ({@code createdDate}..{@code endDate}), and
 * the write-side hooks ({@link PledgeController#applyIncomeToPledge},
 * {@link PledgeController#adjustPledgeByDelta}) credit at most one campaign
 * per gift — the one whose window contains the income's date.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Pledge campaigns — collected total is bounded per campaign")
class PledgeCollectedBoundaryTest {

    private static final String  CLIENT = "CHURCH-A";
    private static final Integer FUND   = 900;
    private static final Integer MEMBER = 42;

    @Mock PledgeCampaignRepository campaignRepo;
    @Mock PledgeMemberRepository   pledgeRepo;
    @Mock FamilyMemberRepository   familyMemberRepo;
    @Mock IncomeRepository         incomeRepo;

    private PledgeController controller;

    @BeforeEach
    void setUp() {
        controller = new PledgeController(campaignRepo, pledgeRepo, familyMemberRepo, incomeRepo);
    }

    private static PledgeCampaign campaign(int id, LocalDateTime created, LocalDate end) {
        PledgeCampaign c = new PledgeCampaign();
        c.setId(id);
        c.setClientId(CLIENT);
        c.setName("Campaign " + id);
        c.setSubSourceId(FUND);
        c.setStatus("Active");
        c.setCreatedDate(created);
        c.setEndDate(end);
        return c;
    }

    private static PledgeMember pledge(int id, int campaignId) {
        PledgeMember p = new PledgeMember();
        p.setId(id);
        p.setClientId(CLIENT);
        p.setCampaignId(campaignId);
        p.setFamilyMemberId(MEMBER);
        p.setAmountCollected(BigDecimal.ZERO);
        return p;
    }

    // ── read side: collectedForPledge bounds the sum per campaign ──────────

    @Nested
    @DisplayName("collectedForPledge")
    class CollectedForPledge {

        @Test
        @DisplayName("sums income bounded to the campaign's own createdDate..endDate window")
        void boundsSumToCampaignWindow() {
            PledgeCampaign c = campaign(1, LocalDateTime.of(2026, 1, 5, 0, 0), LocalDate.of(2026, 6, 30));
            PledgeMember   p = pledge(10, 1);
            when(incomeRepo.sumMemberSubSourceTotal(MEMBER, FUND, CLIENT,
                    LocalDate.of(2026, 1, 5), LocalDate.of(2026, 6, 30)))
                    .thenReturn(new BigDecimal("300.00"));

            BigDecimal collected = controller.collectedForPledge(p, c);

            assertThat(collected).isEqualByComparingTo("300.00");
        }

        @Test
        @DisplayName("audit scenario: a completed 2024 campaign and a new 2026 campaign on the same fund do not both claim the same gifts")
        void twoCampaignsOnSameFundAreIndependentlyBounded() {
            PledgeCampaign campaign2024 = campaign(1,
                    LocalDateTime.of(2024, 1, 1, 0, 0), LocalDate.of(2024, 12, 31));
            PledgeCampaign campaign2026 = campaign(2,
                    LocalDateTime.of(2026, 1, 1, 0, 0), null);
            PledgeMember pledge2024 = pledge(10, 1);
            PledgeMember pledge2026 = pledge(11, 2);

            // Member gave $500 total in 2024 (against the 2024 campaign) and
            // $300 so far in 2026 (against the 2026 campaign).
            when(incomeRepo.sumMemberSubSourceTotal(MEMBER, FUND, CLIENT,
                    LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)))
                    .thenReturn(new BigDecimal("500.00"));
            when(incomeRepo.sumMemberSubSourceTotal(MEMBER, FUND, CLIENT,
                    LocalDate.of(2026, 1, 1), null))
                    .thenReturn(new BigDecimal("300.00"));

            BigDecimal collected2024 = controller.collectedForPledge(pledge2024, campaign2024);
            BigDecimal collected2026 = controller.collectedForPledge(pledge2026, campaign2026);

            assertThat(collected2024).as("2024 campaign keeps only its own 2024 gifts")
                    .isEqualByComparingTo("500.00");
            assertThat(collected2026).as("2026 campaign starts from zero, not the member's lifetime total")
                    .isEqualByComparingTo("300.00");
        }

        @Test
        @DisplayName("a campaign with no createdDate at all falls back to unbounded-from-the-start (legacy row)")
        void nullCreatedDateFallsBackToUnbounded() {
            PledgeCampaign c = campaign(1, null, null);
            PledgeMember   p = pledge(10, 1);
            when(incomeRepo.sumMemberSubSourceTotal(MEMBER, FUND, CLIENT, LocalDate.MIN, null))
                    .thenReturn(new BigDecimal("75.00"));

            assertThat(controller.collectedForPledge(p, c)).isEqualByComparingTo("75.00");
        }

        @Test
        @DisplayName("still falls back to the stored counter for a guest pledge, without touching the income table")
        void guestPledgeUsesStoredCounter() {
            PledgeCampaign c = campaign(1, LocalDateTime.now(), null);
            PledgeMember   p = new PledgeMember();
            p.setFamilyMemberId(null);
            p.setGuestName("Cash Visitor");
            p.setAmountCollected(new BigDecimal("40.00"));

            assertThat(controller.collectedForPledge(p, c)).isEqualByComparingTo("40.00");
            verifyNoInteractions(incomeRepo);
        }
    }

    // ── write side: a gift credits at most one campaign ─────────────────────

    @Nested
    @DisplayName("applyIncomeToPledge")
    class ApplyIncomeToPledge {

        @Test
        @DisplayName("credits only the campaign whose window contains the income date, not every active campaign on the fund")
        void creditsOnlyTheMatchingCampaign() {
            PledgeCampaign closed2024 = campaign(1,
                    LocalDateTime.of(2024, 1, 1, 0, 0), LocalDate.of(2024, 12, 31));
            PledgeCampaign open2026 = campaign(2,
                    LocalDateTime.of(2026, 1, 1, 0, 0), null);
            when(campaignRepo.findActiveByClientIdAndSubSource(CLIENT, FUND))
                    .thenReturn(List.of(closed2024, open2026));
            when(pledgeRepo.findByCampaignAndMember(CLIENT, 2, MEMBER))
                    .thenReturn(Optional.of(pledge(11, 2)));

            controller.applyIncomeToPledge(CLIENT, FUND, MEMBER, new BigDecimal("100.00"),
                    LocalDate.of(2026, 6, 1));

            ArgumentCaptor<PledgeMember> saved = ArgumentCaptor.forClass(PledgeMember.class);
            verify(pledgeRepo, times(1)).save(saved.capture());
            assertThat(saved.getValue().getCampaignId()).isEqualTo(2);
            assertThat(saved.getValue().getAmountCollected()).isEqualByComparingTo("100.00");
            verify(pledgeRepo, never()).findByCampaignAndMember(CLIENT, 1, MEMBER);
        }

        @Test
        @DisplayName("when windows overlap, the most recently created campaign is credited")
        void overlappingWindowsPickMostRecentlyCreated() {
            PledgeCampaign older = campaign(1, LocalDateTime.of(2026, 1, 1, 0, 0), null);
            PledgeCampaign newer = campaign(2, LocalDateTime.of(2026, 3, 1, 0, 0), null);
            when(campaignRepo.findActiveByClientIdAndSubSource(CLIENT, FUND))
                    .thenReturn(List.of(older, newer));
            when(pledgeRepo.findByCampaignAndMember(CLIENT, 2, MEMBER))
                    .thenReturn(Optional.of(pledge(11, 2)));

            controller.applyIncomeToPledge(CLIENT, FUND, MEMBER, new BigDecimal("50.00"),
                    LocalDate.of(2026, 6, 1));

            verify(pledgeRepo).findByCampaignAndMember(CLIENT, 2, MEMBER);
            verify(pledgeRepo, never()).findByCampaignAndMember(CLIENT, 1, MEMBER);
        }

        @Test
        @DisplayName("a gift dated outside every active campaign's window credits nothing")
        void giftOutsideEveryWindowIsNotCredited() {
            PledgeCampaign closed2024 = campaign(1,
                    LocalDateTime.of(2024, 1, 1, 0, 0), LocalDate.of(2024, 12, 31));
            when(campaignRepo.findActiveByClientIdAndSubSource(CLIENT, FUND))
                    .thenReturn(List.of(closed2024));

            controller.applyIncomeToPledge(CLIENT, FUND, MEMBER, new BigDecimal("20.00"),
                    LocalDate.of(2025, 6, 1));

            verify(pledgeRepo, never()).findByCampaignAndMember(any(), any(), any());
            verify(pledgeRepo, never()).save(any());
        }

        @Test
        @DisplayName("a null income date is treated as today, not as 'match every campaign regardless of date'")
        void nullIncomeDateTreatedAsToday() {
            PledgeCampaign closed2024 = campaign(1,
                    LocalDateTime.of(2024, 1, 1, 0, 0), LocalDate.of(2024, 12, 31));
            when(campaignRepo.findActiveByClientIdAndSubSource(CLIENT, FUND))
                    .thenReturn(List.of(closed2024));

            controller.applyIncomeToPledge(CLIENT, FUND, MEMBER, new BigDecimal("20.00"), null);

            verify(pledgeRepo, never()).save(any());
        }
    }

    // ── write side: reversal targets the same campaign the credit did ──────

    @Nested
    @DisplayName("adjustPledgeByDelta")
    class AdjustPledgeByDelta {

        @Test
        @DisplayName("reverses against the campaign matching the original income date, even if two campaigns share the fund")
        void reversesOnlyTheOriginalCampaign() {
            PledgeCampaign closed2024 = campaign(1,
                    LocalDateTime.of(2024, 1, 1, 0, 0), LocalDate.of(2024, 12, 31));
            PledgeCampaign open2026 = campaign(2,
                    LocalDateTime.of(2026, 1, 1, 0, 0), null);
            when(campaignRepo.findActiveByClientIdAndSubSource(CLIENT, FUND))
                    .thenReturn(List.of(closed2024, open2026));

            PledgeMember pledgeIn2024 = pledge(10, 1);
            pledgeIn2024.setAmountCollected(new BigDecimal("500.00"));
            when(pledgeRepo.findByCampaignAndMember(CLIENT, 1, MEMBER)).thenReturn(Optional.of(pledgeIn2024));

            controller.adjustPledgeByDelta(CLIENT, FUND, MEMBER, new BigDecimal("-100.00"),
                    LocalDate.of(2024, 6, 1));

            ArgumentCaptor<PledgeMember> saved = ArgumentCaptor.forClass(PledgeMember.class);
            verify(pledgeRepo, times(1)).save(saved.capture());
            assertThat(saved.getValue().getCampaignId()).isEqualTo(1);
            assertThat(saved.getValue().getAmountCollected()).isEqualByComparingTo("400.00");
            verify(pledgeRepo, never()).findByCampaignAndMember(CLIENT, 2, MEMBER);
        }

        @Test
        @DisplayName("never drives the stored counter negative")
        void neverGoesNegative() {
            PledgeCampaign c = campaign(1, LocalDateTime.of(2026, 1, 1, 0, 0), null);
            when(campaignRepo.findActiveByClientIdAndSubSource(CLIENT, FUND)).thenReturn(List.of(c));
            PledgeMember p = pledge(10, 1);
            p.setAmountCollected(new BigDecimal("30.00"));
            when(pledgeRepo.findByCampaignAndMember(CLIENT, 1, MEMBER)).thenReturn(Optional.of(p));

            controller.adjustPledgeByDelta(CLIENT, FUND, MEMBER, new BigDecimal("-100.00"),
                    LocalDate.of(2026, 6, 1));

            assertThat(p.getAmountCollected()).isEqualByComparingTo("0.00");
        }
    }
}
