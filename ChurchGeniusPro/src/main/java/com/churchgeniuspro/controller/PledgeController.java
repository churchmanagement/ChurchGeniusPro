package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.FamilyMember;
import com.churchgeniuspro.hibernate.PledgeCampaign;
import com.churchgeniuspro.hibernate.PledgeMember;
import com.churchgeniuspro.repository.FamilyMemberRepository;
import com.churchgeniuspro.repository.IncomeRepository;
import com.churchgeniuspro.repository.PledgeCampaignRepository;
import com.churchgeniuspro.repository.PledgeMemberRepository;
import com.churchgeniuspro.util.RoleGuard;
import com.churchgeniuspro.util.SessionUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * REST API + page route for the Pledge Campaign module.
 *
 * <h3>Page</h3>
 * <ul>
 *   <li>{@code GET /pledges} → {@code pledges.html} (Accountant / Admin only)</li>
 * </ul>
 *
 * <h3>API</h3>
 * <ul>
 *   <li>{@code GET    /api/pledges/campaigns}                       — list campaigns w/ totals</li>
 *   <li>{@code POST   /api/pledges/campaigns}                       — create campaign</li>
 *   <li>{@code PUT    /api/pledges/campaigns/{id}}                  — update campaign</li>
 *   <li>{@code DELETE /api/pledges/campaigns/{id}}                  — soft-delete</li>
 *   <li>{@code GET    /api/pledges/campaigns/{id}/members}          — list pledges for one campaign</li>
 *   <li>{@code POST   /api/pledges/campaigns/{id}/members}          — add pledge</li>
 *   <li>{@code PUT    /api/pledges/members/{id}}                    — update pledge</li>
 *   <li>{@code DELETE /api/pledges/members/{id}}                    — soft-delete pledge</li>
 * </ul>
 *
 * Auto-allocation: {@link #applyIncomeToPledge} is called by IncomeService
 * each time an income row is saved; it credits the matching member's pledge.
 */
@Controller
public class PledgeController {

    private final PledgeCampaignRepository campaignRepo;
    private final PledgeMemberRepository   pledgeRepo;
    private final FamilyMemberRepository   familyMemberRepo;
    private final IncomeRepository         incomeRepo;

    public PledgeController(PledgeCampaignRepository campaignRepo,
                            PledgeMemberRepository   pledgeRepo,
                            FamilyMemberRepository   familyMemberRepo,
                            IncomeRepository         incomeRepo) {
        this.campaignRepo     = campaignRepo;
        this.pledgeRepo       = pledgeRepo;
        this.familyMemberRepo = familyMemberRepo;
        this.incomeRepo       = incomeRepo;
    }

    /**
     * Amount collected against a single pledge, computed from actual income rows
     * rather than the denormalized {@code amount_collected} counter. Summing the
     * member's giving to the campaign's current fund — bounded to the campaign's
     * own window — keeps the figure correct when the campaign's fund is edited or
     * when contributions predate the pledge, while stopping two campaigns that
     * share a fund from each claiming the same gifts (financial audit M4): a
     * completed 2024 campaign and a new 2026 campaign on the same fund now sum
     * only the income dated inside each one's own window, instead of the
     * member's entire lifetime giving to the fund.
     *
     * <p>Falls back to the stored counter for guest pledges (no linked member) or
     * campaigns without a backing fund, where an income-based sum isn't possible.
     */
    public BigDecimal collectedForPledge(PledgeMember p, PledgeCampaign c) {
        if (p == null) return BigDecimal.ZERO;
        BigDecimal stored = p.getAmountCollected() != null ? p.getAmountCollected() : BigDecimal.ZERO;
        if (p.getFamilyMemberId() == null || c == null || c.getSubSourceId() == null) {
            return stored;
        }
        BigDecimal sum = incomeRepo.sumMemberSubSourceTotal(
                p.getFamilyMemberId(), c.getSubSourceId(), c.getClientId(),
                campaignStart(c), c.getEndDate());
        return sum != null ? sum : BigDecimal.ZERO;
    }

    /**
     * A campaign's window starts when it was created — there is no separate,
     * admin-editable start date (financial audit M4). This means income given
     * before staff created the campaign row (but after the drive was announced
     * to the congregation) will not count toward it; churches should create a
     * new campaign as soon as a drive begins, even before the first pledge card
     * is entered. Falls back to {@link LocalDate#MIN} for the rare legacy row
     * with no createdDate at all, so it behaves like the old, unbounded query
     * rather than excluding everything.
     */
    private static LocalDate campaignStart(PledgeCampaign c) {
        return c.getCreatedDate() != null ? c.getCreatedDate().toLocalDate() : LocalDate.MIN;
    }

    // ── Page route ────────────────────────────────────────────────────────

    @GetMapping("/pledges")
    public String pledgesPage(HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePagePermission(request, "accounting.pledges");
        if (deny != null) return deny;
        return "forward:/pledges.html";
    }

    // ── Campaign list ─────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/pledges/campaigns")
    public ResponseEntity<?> listCampaigns(HttpServletRequest req) {
        String deny = RoleGuard.requireAccountantOrAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        List<Map<String, Object>> out = new ArrayList<>();
        for (PledgeCampaign c : campaignRepo.findByClientId(cid)) {
            out.add(toCampaignMap(c, totalsForCampaign(cid, c)));
        }
        return ResponseEntity.ok(out);
    }

    @ResponseBody
    @PostMapping("/api/pledges/campaigns")
    @Transactional
    public ResponseEntity<?> createCampaign(@RequestBody Map<String, Object> body,
                                            HttpServletRequest req) {
        String deny = RoleGuard.requireAccountantOrAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        if (RoleGuard.requirePermission(req, "accounting.pledges.edit") != null)
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        PledgeCampaign c = new PledgeCampaign();
        c.setClientId(cid);
        applyCampaignBody(c, body);
        if (c.getName() == null || c.getName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Campaign name is required"));
        }
        String fundProblem = fundRuleProblem(c, cid);
        if (fundProblem != null) return ResponseEntity.status(409).body(Map.of("error", fundProblem, "code", "FUND_IN_USE"));
        return ResponseEntity.ok(toCampaignMap(campaignRepo.save(c),
                totalsForCampaign(cid, c)));
    }

    @ResponseBody
    @PutMapping("/api/pledges/campaigns/{id}")
    @Transactional
    public ResponseEntity<?> updateCampaign(@PathVariable Integer id,
                                            @RequestBody Map<String, Object> body,
                                            HttpServletRequest req) {
        String deny = RoleGuard.requireAccountantOrAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        if (RoleGuard.requirePermission(req, "accounting.pledges.edit") != null)
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        PledgeCampaign c = campaignRepo.findByIdAndClientId(id, cid).orElse(null);
        if (c == null) return ResponseEntity.status(404).body(Map.of("error", "Campaign not found"));
        // Checked on a copy BEFORE the campaign is touched, so a refused change leaves it exactly as it was.
        // Only a change to the fund, the option or the status can break the one-campaign-per-fund rule; an edit
        // of anything else (name, target, description …) is never held up by it.
        PledgeCampaign probe = new PledgeCampaign();
        probe.setId(c.getId()); probe.setClientId(cid); probe.setName(c.getName());
        probe.setSubSourceId(c.getSubSourceId()); probe.setFundExclusive(c.isFundExclusive()); probe.setStatus(c.getStatus());
        applyCampaignBody(probe, body);
        boolean ruleRelevantChange = !java.util.Objects.equals(probe.getSubSourceId(), c.getSubSourceId())
                || probe.isFundExclusive() != c.isFundExclusive() || !java.util.Objects.equals(probe.getStatus(), c.getStatus());
        if (ruleRelevantChange) {
            String fundProblem = fundRuleProblem(probe, cid);
            if (fundProblem != null) return ResponseEntity.status(409).body(Map.of("error", fundProblem, "code", "FUND_IN_USE"));
        }
        applyCampaignBody(c, body);
        return ResponseEntity.ok(toCampaignMap(campaignRepo.save(c),
                totalsForCampaign(cid, c)));
    }

    /**
     * 2026-11-16 — "Use this Fund exclusively for this Campaign" (user decision: one active campaign per fund).
     * An exclusive campaign claims every gift to its fund, so while it is Active no other Active campaign may use
     * that fund — otherwise two campaigns would count the same gifts. Returns the sentence to show, or null.
     * A campaign that is not Active (Closed / Archived) is never checked, and neither are two ordinary campaigns
     * sharing a fund (allowed before, still allowed).
     */
    String fundRuleProblem(PledgeCampaign c, String cid) {
        if (c.isFundExclusive() && c.getSubSourceId() == null) {
            return "Choose a Subcategory (Fund) to use the fund exclusively for this campaign.";
        }
        if (c.getSubSourceId() == null || !"Active".equals(c.getStatus() == null || c.getStatus().isBlank() ? "Active" : c.getStatus())) return null;
        for (PledgeCampaign other : campaignRepo.findLiveActiveOnFund(cid, c.getSubSourceId())) {
            if (other.getId() != null && other.getId().equals(c.getId())) continue;
            if (c.isFundExclusive()) {
                return "The campaign “" + other.getName() + "” is active on this fund. A fund can be used exclusively by one active campaign only — "
                        + "close that campaign, choose another fund, or untick the option.";
            }
            if (other.isFundExclusive()) {
                return "This fund is used exclusively by the active campaign “" + other.getName() + "”. Choose another fund, or close that campaign first.";
            }
        }
        return null;
    }

    @ResponseBody
    @DeleteMapping("/api/pledges/campaigns/{id}")
    @Transactional
    public ResponseEntity<?> deleteCampaign(@PathVariable Integer id, HttpServletRequest req) {
        String deny = RoleGuard.requireAccountantOrAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        if (RoleGuard.requirePermission(req, "accounting.pledges.delete") != null)
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        PledgeCampaign c = campaignRepo.findByIdAndClientId(id, cid).orElse(null);
        if (c == null) return ResponseEntity.status(404).body(Map.of("error", "Campaign not found"));
        c.setDeleteFlag(true);
        campaignRepo.save(c);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Member pledges ────────────────────────────────────────────────────

    @ResponseBody
    @GetMapping("/api/pledges/campaigns/{id}/members")
    public ResponseEntity<?> listPledges(@PathVariable Integer id, HttpServletRequest req) {
        String deny = RoleGuard.requireAccountantOrAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        if (campaignRepo.findByIdAndClientId(id, cid).isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Campaign not found"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PledgeMember p : pledgeRepo.findByCampaign(cid, id)) {
            out.add(toPledgeMap(p));
        }
        return ResponseEntity.ok(out);
    }

    @ResponseBody
    @PostMapping("/api/pledges/campaigns/{id}/members")
    @Transactional
    public ResponseEntity<?> addPledge(@PathVariable Integer id,
                                       @RequestBody Map<String, Object> body,
                                       HttpServletRequest req) {
        String deny = RoleGuard.requireAccountantOrAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        if (RoleGuard.requirePermission(req, "accounting.pledges.edit") != null)
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        if (campaignRepo.findByIdAndClientId(id, cid).isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error", "Campaign not found"));
        }
        PledgeMember p = new PledgeMember();
        p.setClientId(cid);
        p.setCampaignId(id);
        String bad = applyPledgeBody(p, body, cid);
        if (bad != null) return ResponseEntity.status(404).body(Map.of("error", bad));
        if (p.getFamilyMemberId() == null && (p.getGuestName() == null || p.getGuestName().isBlank())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Pick a member or enter a name"));
        }
        return ResponseEntity.ok(toPledgeMap(pledgeRepo.save(p)));
    }

    @ResponseBody
    @PutMapping("/api/pledges/members/{id}")
    @Transactional
    public ResponseEntity<?> updatePledge(@PathVariable Integer id,
                                          @RequestBody Map<String, Object> body,
                                          HttpServletRequest req) {
        String deny = RoleGuard.requireAccountantOrAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        if (RoleGuard.requirePermission(req, "accounting.pledges.edit") != null)
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        PledgeMember p = pledgeRepo.findByIdAndClientId(id, cid).orElse(null);
        if (p == null) return ResponseEntity.status(404).body(Map.of("error", "Pledge not found"));
        String bad = applyPledgeBody(p, body, cid);
        if (bad != null) return ResponseEntity.status(404).body(Map.of("error", bad));
        return ResponseEntity.ok(toPledgeMap(pledgeRepo.save(p)));
    }

    @ResponseBody
    @DeleteMapping("/api/pledges/members/{id}")
    @Transactional
    public ResponseEntity<?> deletePledge(@PathVariable Integer id, HttpServletRequest req) {
        String deny = RoleGuard.requireAccountantOrAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        if (RoleGuard.requirePermission(req, "accounting.pledges.delete") != null)
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        PledgeMember p = pledgeRepo.findByIdAndClientId(id, cid).orElse(null);
        if (p == null) return ResponseEntity.status(404).body(Map.of("error", "Pledge not found"));
        p.setDeleteFlag(true);
        pledgeRepo.save(p);
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Auto-allocate hook (called by IncomeService) ──────────────────────

    /**
     * Credits a member's pledge balance when their income matches the
     * subSource/fund of an active campaign. No-op when no match exists,
     * which keeps the existing Income flow side-effect-free for org
     * accounts that don't use pledges.
     *
     * <p>When more than one active campaign shares the fund, the income is
     * credited to at most one of them — the one whose window contains the
     * income's own date — rather than every campaign on the fund (financial
     * audit M4; this stored counter is currently read only as a fallback for
     * guest pledges and fundless campaigns, see {@link #collectedForPledge},
     * but is kept correct on principle since it is persisted, named data).
     *
     * @param clientId      tenant id
     * @param subSourceId   fund the income was tagged with
     * @param familyMemberId  contributor
     * @param amount        contribution amount (positive)
     * @param incomeDate    date of the income transaction
     *
     * <p>Runs in its OWN transaction (error-handling audit 2026-10-28, M2): the caller is
     * {@code IncomeService} inside its income-save transaction, and a failure here used to
     * mark that transaction rollback-only — the income "saved" and then the commit threw
     * ({@code UnexpectedRollbackException} → 500) — or, before any repository call, was
     * swallowed with the pledge balance silently drifting. Now a pledge failure rolls back
     * only the pledge credit and is logged by the caller with the income id.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void applyIncomeToPledge(String clientId, Integer subSourceId,
                                    Integer familyMemberId, BigDecimal amount,
                                    LocalDate incomeDate) {
        if (clientId == null || subSourceId == null || familyMemberId == null
                || amount == null || amount.signum() <= 0) return;
        PledgeCampaign c = campaignForIncomeDate(clientId, subSourceId, incomeDate);
        if (c == null) return;
        PledgeMember p = pledgeRepo
                .findByCampaignAndMember(clientId, c.getId(), familyMemberId)
                .orElse(null);
        if (p == null) return;
        BigDecimal cur = p.getAmountCollected() != null ? p.getAmountCollected() : BigDecimal.ZERO;
        p.setAmountCollected(cur.add(amount));
        pledgeRepo.save(p);
    }

    /**
     * Reverses {@link #applyIncomeToPledge} when an income row is deleted
     * or its amount is reduced on edit. Caller passes the *delta* (positive
     * to credit back, negative to charge more) and the income's own date, so
     * the reversal targets the same single campaign the original credit did
     * — including after the income row's date has since been changed, when
     * the caller passes the date as it was *before* that edit.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void adjustPledgeByDelta(String clientId, Integer subSourceId,
                                    Integer familyMemberId, BigDecimal delta,
                                    LocalDate incomeDate) {
        if (clientId == null || subSourceId == null || familyMemberId == null
                || delta == null || delta.signum() == 0) return;
        PledgeCampaign c = campaignForIncomeDate(clientId, subSourceId, incomeDate);
        if (c == null) return;
        PledgeMember p = pledgeRepo
                .findByCampaignAndMember(clientId, c.getId(), familyMemberId)
                .orElse(null);
        if (p == null) return;
        BigDecimal cur = p.getAmountCollected() != null ? p.getAmountCollected() : BigDecimal.ZERO;
        BigDecimal next = cur.add(delta);
        if (next.signum() < 0) next = BigDecimal.ZERO;
        p.setAmountCollected(next);
        pledgeRepo.save(p);
    }

    /**
     * The single active campaign on this fund whose window contains the
     * income's date — never more than one, so a gift is never double-credited
     * across campaigns that share a fund (financial audit M4). When more than
     * one candidate's window contains the date (overlapping campaigns on the
     * same fund), the most recently created wins, matching {@link
     * PledgeCampaignRepository#findByClientId}'s existing "newest first"
     * convention. Returns {@code null} when no campaign's window covers the
     * date — this stored counter is fallback/display data (see {@link
     * #collectedForPledge}), so it's fine to leave it uncredited rather than
     * guess which campaign a gift outside every window belongs to.
     */
    private PledgeCampaign campaignForIncomeDate(String clientId, Integer subSourceId, LocalDate incomeDate) {
        LocalDate effectiveDate = incomeDate != null ? incomeDate : LocalDate.now();
        PledgeCampaign best = null;
        for (PledgeCampaign c : campaignRepo.findActiveByClientIdAndSubSource(clientId, subSourceId)) {
            if (effectiveDate.isBefore(campaignStart(c))) continue;
            if (c.getEndDate() != null && effectiveDate.isAfter(c.getEndDate())) continue;
            if (best == null || isNewer(c, best)) best = c;
        }
        return best;
    }

    private static boolean isNewer(PledgeCampaign a, PledgeCampaign b) {
        LocalDateTime ac = a.getCreatedDate(), bc = b.getCreatedDate();
        if (ac == null) return false;
        if (bc == null) return true;
        return ac.isAfter(bc);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void applyCampaignBody(PledgeCampaign c, Map<String, Object> body) {
        if (body.containsKey("name"))         c.setName(str(body.get("name")));
        if (body.containsKey("subSourceId"))  c.setSubSourceId(toInt(body.get("subSourceId")));
        if (body.containsKey("otherGifts"))   c.setOtherGifts(str(body.get("otherGifts")));
        if (body.containsKey("description"))  c.setDescription(str(body.get("description")));
        if (body.containsKey("targetAmount")) c.setTargetAmount(toBd(body.get("targetAmount")));
        if (body.containsKey("fundExclusive")) {
            Object v = body.get("fundExclusive");
            c.setFundExclusive(Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v)));
        }
        if (body.containsKey("endDate"))      c.setEndDate(toDate(body.get("endDate")));
        if (body.containsKey("status")) {
            String s = str(body.get("status"));
            if (s != null && !s.isBlank()) c.setStatus(s);
        }
    }

    /**
     * Copies the editable fields from the request body. The member reference is
     * validated against the session tenant BEFORE it is stored; returns an error
     * message (caller responds 404) when it names a member of another church.
     */
    private String applyPledgeBody(PledgeMember p, Map<String, Object> body, String cid) {
        if (body.containsKey("familyMemberId")) {
            Integer fmId = toInt(body.get("familyMemberId"));
            if (fmId != null && familyMemberRepo.findByIdAndTenant(fmId, cid).isEmpty()) {
                return "Member not found";
            }
            p.setFamilyMemberId(fmId);
        }
        if (body.containsKey("familyId"))       p.setFamilyId(toInt(body.get("familyId")));
        if (body.containsKey("guestName"))      p.setGuestName(str(body.get("guestName")));
        if (body.containsKey("pledgeAmount"))   p.setPledgeAmount(toBd(body.get("pledgeAmount")));
        if (body.containsKey("monthlyAmount"))  p.setMonthlyAmount(toBd(body.get("monthlyAmount")));
        if (body.containsKey("gifts"))          p.setGifts(str(body.get("gifts")));
        if (body.containsKey("notes"))          p.setNotes(str(body.get("notes")));
        return null;
    }

    /** Aggregated totals across all pledges for a campaign. */
    private Map<String, Object> totalsForCampaign(String cid, PledgeCampaign c) {
        BigDecimal pledged   = BigDecimal.ZERO;
        BigDecimal collected = BigDecimal.ZERO;
        List<PledgeMember> rows = pledgeRepo.findByCampaign(cid, c.getId());
        for (PledgeMember p : rows) {
            if (p.getPledgeAmount() != null) pledged = pledged.add(p.getPledgeAmount());
            collected = collected.add(collectedForPledge(p, c));
        }
        BigDecimal pending   = pledged.subtract(collected);
        if (pending.signum() < 0) pending = BigDecimal.ZERO;
        BigDecimal remaining = c.getTargetAmount() != null
                ? c.getTargetAmount().subtract(collected) : null;
        if (remaining != null && remaining.signum() < 0) remaining = BigDecimal.ZERO;
        BigDecimal pct = null;
        if (c.getTargetAmount() != null && c.getTargetAmount().signum() > 0) {
            pct = collected.multiply(BigDecimal.valueOf(100))
                    .divide(c.getTargetAmount(), 1, RoundingMode.HALF_UP);
        }
        if (c.isFundExclusive() && c.getSubSourceId() != null) {
            return exclusiveTotals(cid, c, rows, pledged);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fundExclusive",   false);
        m.put("pledged",         pledged);
        m.put("collected",       collected);
        m.put("pending",         pending);
        m.put("remainingTarget", remaining);
        m.put("percent",         pct);
        m.put("pledgers",        rows.size());
        return m;
    }

    /** First day whose gifts count for a campaign (a safe floor for the rare legacy row with no created date). */
    private static LocalDate windowStart(PledgeCampaign c) {
        LocalDate s = campaignStart(c);
        return s.isBefore(LocalDate.of(1900, 1, 1)) ? LocalDate.of(1900, 1, 1) : s;
    }

    /** The distinct members who hold a pledge in the campaign (a member with two pledges counts once). */
    private static java.util.Set<Integer> pledgerIds(List<PledgeMember> rows) {
        java.util.Set<Integer> ids = new java.util.LinkedHashSet<>();
        for (PledgeMember p : rows) if (p.getFamilyMemberId() != null) ids.add(p.getFamilyMemberId());
        return ids;
    }

    /**
     * Totals of a campaign that uses its fund exclusively (2026-11-16). Every figure comes from the income rows of
     * the fund inside the campaign's window, each row counted exactly once:
     * <pre>
     *   Total Collected         = all gifts to the fund in the window (anyone)
     *   Collected from Pledgers = the part given by members who hold a pledge in this campaign
     *   Outside Contributions   = Total Collected − Collected from Pledgers   (other members, guests)
     *   Pending Pledge          = Total Pledged − Collected from Pledgers     (never below 0)
     *   Percentage of Target    = Total Collected ÷ Target × 100
     * </pre>
     * Nothing is stored: editing, deleting or moving an income row changes the figures on the next read.
     */
    private Map<String, Object> exclusiveTotals(String cid, PledgeCampaign c, List<PledgeMember> rows, BigDecimal pledged) {
        LocalDate from = windowStart(c);
        BigDecimal total = incomeRepo.campaignFundTotal(c.getSubSourceId(), cid, from, c.getEndDate());
        BigDecimal fromPledgers = incomeRepo.campaignFundTotalForMembers(c.getSubSourceId(), cid, pledgerIds(rows), from, c.getEndDate());
        BigDecimal outside = total.subtract(fromPledgers);
        BigDecimal pending = pledged.subtract(fromPledgers);
        if (pending.signum() < 0) pending = BigDecimal.ZERO;
        BigDecimal remaining = c.getTargetAmount() != null ? c.getTargetAmount().subtract(total) : null;
        if (remaining != null && remaining.signum() < 0) remaining = BigDecimal.ZERO;
        BigDecimal pct = null;
        if (c.getTargetAmount() != null && c.getTargetAmount().signum() > 0) {
            pct = total.multiply(BigDecimal.valueOf(100)).divide(c.getTargetAmount(), 1, RoundingMode.HALF_UP);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fundExclusive",         true);
        m.put("pledged",               pledged);
        m.put("collected",             total);          // what the progress bar and older readers show
        m.put("collectedFromPledgers", fromPledgers);
        m.put("outsideContributions",  outside);
        m.put("totalCollected",        total);
        m.put("pending",               pending);
        m.put("remainingTarget",       remaining);
        m.put("percent",               pct);
        m.put("pledgers",              rows.size());
        m.put("windowStart",           from.toString());
        m.put("windowEnd",             c.getEndDate() == null ? null : c.getEndDate().toString());
        return m;
    }

    /**
     * {@code GET /api/pledges/campaigns/{id}/outside} — the gifts counted as Outside Contributions (newest 200):
     * date, contributor, amount. Empty for a campaign that does not use its fund exclusively.
     */
    @ResponseBody
    @GetMapping("/api/pledges/campaigns/{id}/outside")
    public ResponseEntity<?> outsideContributions(@PathVariable Integer id, HttpServletRequest req) {
        String deny = RoleGuard.requireAccountantOrAdmin(req);
        if (deny != null) return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        String cid = SessionUtil.getAppClientId(req);
        PledgeCampaign c = campaignRepo.findByIdAndClientId(id, cid).orElse(null);
        if (c == null || c.isDeleteFlag()) return ResponseEntity.status(404).body(Map.of("error", "Campaign not found"));
        List<Map<String, Object>> out = new ArrayList<>();
        if (c.isFundExclusive() && c.getSubSourceId() != null) {
            java.util.Set<Integer> ids = pledgerIds(pledgeRepo.findByCampaign(cid, c.getId()));
            if (ids.isEmpty()) ids = java.util.Set.of(-1);
            LocalDate end = c.getEndDate() == null ? LocalDate.of(9999, 12, 31) : c.getEndDate();
            for (Object[] r : incomeRepo.campaignOutsideRows(c.getSubSourceId(), cid, ids,
                    java.sql.Date.valueOf(windowStart(c)), java.sql.Date.valueOf(end), 200)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("incomeId", r[0]);
                m.put("date", r[1] == null ? null : r[1].toString());
                m.put("amount", r[2]);
                String name = ((r[3] == null ? "" : r[3]) + " " + (r[4] == null ? "" : r[4])).trim();
                m.put("contributor", !name.isEmpty() ? name : (r[5] == null || String.valueOf(r[5]).isBlank() ? "Guest" : String.valueOf(r[5])));
                out.add(m);
            }
        }
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> toCampaignMap(PledgeCampaign c, Map<String, Object> totals) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           c.getId());
        m.put("name",         c.getName());
        m.put("subSourceId",  c.getSubSourceId());
        m.put("otherGifts",   c.getOtherGifts());
        m.put("description",  c.getDescription());
        m.put("targetAmount", c.getTargetAmount());
        m.put("fundExclusive", c.isFundExclusive());
        m.put("endDate",      c.getEndDate() != null ? c.getEndDate().toString() : null);
        m.put("status",       c.getStatus());
        m.put("createdDate",  c.getCreatedDate() != null ? c.getCreatedDate().toString() : null);
        m.put("totals",       totals);
        return m;
    }

    private Map<String, Object> toPledgeMap(PledgeMember p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",              p.getId());
        m.put("campaignId",      p.getCampaignId());
        m.put("familyMemberId",  p.getFamilyMemberId());
        m.put("familyId",        p.getFamilyId());
        m.put("guestName",       p.getGuestName());
        m.put("pledgeAmount",    p.getPledgeAmount());
        m.put("monthlyAmount",   p.getMonthlyAmount());
        m.put("gifts",           p.getGifts());
        m.put("notes",           p.getNotes());
        // Collected is computed from actual income (member + campaign fund) so it
        // reflects fund edits and contributions that predate the pledge.
        PledgeCampaign campaign = p.getCampaignId() != null
                ? campaignRepo.findByIdAndClientId(p.getCampaignId(), p.getClientId()).orElse(null)
                : null;
        BigDecimal collected = collectedForPledge(p, campaign);
        m.put("amountCollected", collected);
        BigDecimal pending = BigDecimal.ZERO;
        if (p.getPledgeAmount() != null) {
            pending = p.getPledgeAmount().subtract(collected);
            if (pending.signum() < 0) pending = BigDecimal.ZERO;
        }
        m.put("pending", pending);
        // Resolve display name when linked to a FamilyMember.
        if (p.getFamilyMemberId() != null) {
            FamilyMember fm = familyMemberRepo.findByIdAndTenant(p.getFamilyMemberId(), p.getClientId()).orElse(null);
            if (fm != null) {
                String name = ((fm.getFirstName() != null ? fm.getFirstName() : "") + " "
                             + (fm.getLastName()  != null ? fm.getLastName()  : "")).trim();
                m.put("memberName", name);
                m.put("memberPhone", fm.getPhone());
                m.put("memberEmail", fm.getEmail());
            }
        }
        return m;
    }

    private static String str(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }
    private static Integer toInt(Object v) {
        if (v == null) return null;
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }
    private static BigDecimal toBd(Object v) {
        if (v == null) return null;
        try { return new BigDecimal(v.toString()); } catch (Exception e) { return null; }
    }
    private static LocalDate toDate(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try { return LocalDate.parse(s); } catch (Exception e) { return null; }
    }
}
