package com.churchgeniuspro.service;

import com.churchgeniuspro.hibernate.PledgeCampaign;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.PledgeMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Campaign totals for the AI Search, computed with two aggregate statements per campaign.
 *
 * <p>Same rule as the Pledges page ({@code PledgeController.totalsForCampaign}): "collected"
 * for a pledge held by a linked member is the member's income to the campaign's fund
 * between the campaign's creation date and its end date; a guest pledge, or every pledge
 * of a campaign without a fund, uses the stored {@code amountCollected} counter. The page
 * sums one query per pledge; here the member part is one SUM over the pledgers' set, which
 * gives the same figure (a member appears once per campaign) without N round trips.
 * The three figures stay separate: pledged, collected, pending (= pledged − collected, floored at 0).
 */
@Service
public class PledgeTotalsService {

    public record Totals(BigDecimal pledged, BigDecimal collected, BigDecimal pending, long pledgers) {}

    private final PledgeMemberRepository pledgeRepo;
    private final IncomeRepository incomeRepo;

    public PledgeTotalsService(PledgeMemberRepository pledgeRepo, IncomeRepository incomeRepo) {
        this.pledgeRepo = pledgeRepo;
        this.incomeRepo = incomeRepo;
    }

    @Transactional(readOnly = true)
    public Totals totals(String clientId, PledgeCampaign c) {
        List<Object[]> rows = pledgeRepo.aiCampaignPledgeTotals(clientId, c.getId());
        Object[] r = rows == null || rows.isEmpty() ? new Object[] {BigDecimal.ZERO, 0L, BigDecimal.ZERO, BigDecimal.ZERO} : rows.get(0);
        BigDecimal pledged   = num(r[0]);
        long       pledgers  = r[1] == null ? 0L : ((Number) r[1]).longValue();
        BigDecimal guestPart = num(r[2]);
        BigDecimal storedAll = num(r[3]);
        BigDecimal collected;
        if (c.getSubSourceId() == null) {
            collected = storedAll;                                   // no fund → stored counters only
        } else {
            // No creation date (rare legacy row) → unbounded start, as the page does; 1900 is a safe
            // database date where LocalDate.MIN is not.
            LocalDate start = c.getCreatedDate() != null ? c.getCreatedDate().toLocalDate() : LocalDate.of(1900, 1, 1);
            BigDecimal memberPart = c.getEndDate() != null
                    ? incomeRepo.aiCampaignMemberCollected(clientId, c.getId(), c.getSubSourceId(), start, c.getEndDate())
                    : incomeRepo.aiCampaignMemberCollectedOpenEnded(clientId, c.getId(), c.getSubSourceId(), start);
            collected = (memberPart == null ? BigDecimal.ZERO : memberPart).add(guestPart);
        }
        BigDecimal pending = pledged.subtract(collected);
        if (pending.signum() < 0) pending = BigDecimal.ZERO;
        return new Totals(pledged, collected, pending, pledgers);
    }

    private static BigDecimal num(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        return new BigDecimal(o.toString());
    }
}
