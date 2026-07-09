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
     * member's giving to the campaign's current fund keeps the figure correct when
     * the campaign's fund is edited or when contributions predate the pledge.
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
                p.getFamilyMemberId(), c.getSubSourceId(), c.getClientId());
        return sum != null ? sum : BigDecimal.ZERO;
    }

    // ── Page route ────────────────────────────────────────────────────────

    @GetMapping("/pledges")
    public String pledgesPage(HttpServletRequest request) {
        String deny = RoleGuard.requireAccountantOrAdmin(request);
        if (deny != null) return deny;
        deny = RoleGuard.requirePermission(request, "accounting.pledges");
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
        applyCampaignBody(c, body);
        return ResponseEntity.ok(toCampaignMap(campaignRepo.save(c),
                totalsForCampaign(cid, c)));
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
        applyPledgeBody(p, body);
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
        applyPledgeBody(p, body);
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
     * @param clientId      tenant id
     * @param subSourceId   fund the income was tagged with
     * @param familyMemberId  contributor
     * @param amount        contribution amount (positive)
     */
    @Transactional
    public void applyIncomeToPledge(String clientId, Integer subSourceId,
                                    Integer familyMemberId, BigDecimal amount) {
        if (clientId == null || subSourceId == null || familyMemberId == null
                || amount == null || amount.signum() <= 0) return;
        List<PledgeCampaign> matches = campaignRepo
                .findActiveByClientIdAndSubSource(clientId, subSourceId);
        for (PledgeCampaign c : matches) {
            PledgeMember p = pledgeRepo
                    .findByCampaignAndMember(clientId, c.getId(), familyMemberId)
                    .orElse(null);
            if (p == null) continue;
            BigDecimal cur = p.getAmountCollected() != null ? p.getAmountCollected() : BigDecimal.ZERO;
            p.setAmountCollected(cur.add(amount));
            pledgeRepo.save(p);
        }
    }

    /**
     * Reverses {@link #applyIncomeToPledge} when an income row is deleted
     * or its amount is reduced on edit. Caller passes the *delta* (positive
     * to credit back, negative to charge more).
     */
    @Transactional
    public void adjustPledgeByDelta(String clientId, Integer subSourceId,
                                    Integer familyMemberId, BigDecimal delta) {
        if (clientId == null || subSourceId == null || familyMemberId == null
                || delta == null || delta.signum() == 0) return;
        for (PledgeCampaign c : campaignRepo.findActiveByClientIdAndSubSource(clientId, subSourceId)) {
            PledgeMember p = pledgeRepo
                    .findByCampaignAndMember(clientId, c.getId(), familyMemberId)
                    .orElse(null);
            if (p == null) continue;
            BigDecimal cur = p.getAmountCollected() != null ? p.getAmountCollected() : BigDecimal.ZERO;
            BigDecimal next = cur.add(delta);
            if (next.signum() < 0) next = BigDecimal.ZERO;
            p.setAmountCollected(next);
            pledgeRepo.save(p);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void applyCampaignBody(PledgeCampaign c, Map<String, Object> body) {
        if (body.containsKey("name"))         c.setName(str(body.get("name")));
        if (body.containsKey("subSourceId"))  c.setSubSourceId(toInt(body.get("subSourceId")));
        if (body.containsKey("otherGifts"))   c.setOtherGifts(str(body.get("otherGifts")));
        if (body.containsKey("description"))  c.setDescription(str(body.get("description")));
        if (body.containsKey("targetAmount")) c.setTargetAmount(toBd(body.get("targetAmount")));
        if (body.containsKey("endDate"))      c.setEndDate(toDate(body.get("endDate")));
        if (body.containsKey("status")) {
            String s = str(body.get("status"));
            if (s != null && !s.isBlank()) c.setStatus(s);
        }
    }

    private void applyPledgeBody(PledgeMember p, Map<String, Object> body) {
        if (body.containsKey("familyMemberId")) p.setFamilyMemberId(toInt(body.get("familyMemberId")));
        if (body.containsKey("familyId"))       p.setFamilyId(toInt(body.get("familyId")));
        if (body.containsKey("guestName"))      p.setGuestName(str(body.get("guestName")));
        if (body.containsKey("pledgeAmount"))   p.setPledgeAmount(toBd(body.get("pledgeAmount")));
        if (body.containsKey("monthlyAmount"))  p.setMonthlyAmount(toBd(body.get("monthlyAmount")));
        if (body.containsKey("gifts"))          p.setGifts(str(body.get("gifts")));
        if (body.containsKey("notes"))          p.setNotes(str(body.get("notes")));
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
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pledged",         pledged);
        m.put("collected",       collected);
        m.put("pending",         pending);
        m.put("remainingTarget", remaining);
        m.put("percent",         pct);
        m.put("pledgers",        rows.size());
        return m;
    }

    private Map<String, Object> toCampaignMap(PledgeCampaign c, Map<String, Object> totals) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           c.getId());
        m.put("name",         c.getName());
        m.put("subSourceId",  c.getSubSourceId());
        m.put("otherGifts",   c.getOtherGifts());
        m.put("description",  c.getDescription());
        m.put("targetAmount", c.getTargetAmount());
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
            FamilyMember fm = familyMemberRepo.findById(p.getFamilyMemberId()).orElse(null);
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
