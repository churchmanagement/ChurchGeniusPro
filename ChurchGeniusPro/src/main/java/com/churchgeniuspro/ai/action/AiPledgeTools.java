package com.churchgeniuspro.ai.action;

import com.churchgeniuspro.ai.action.AiActionLookup.Hit;
import com.churchgeniuspro.hibernate.PledgeCampaign;
import com.churchgeniuspro.repository.PledgeCampaignRepository;
import com.churchgeniuspro.service.AiAccessPolicy.AiAccess;
import com.churchgeniuspro.service.AiAccessPolicy.CrossAccess;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 2026-11-11 — a pledge campaign from the Genius Assistant conversation. {@link AiTool#guidedOnly() Guided-only};
 * the user's explicit Save runs {@code AiActionService.confirm} and stores the same row the Pledges page's
 * "New Campaign" form does ({@code POST /api/pledges/campaigns}: name required; fund, target amount, end date,
 * other gifts and description optional; status Active).
 *
 * <p>Permission = that endpoint's rule: SuperAdmin / Admin / Accountant ({@code requireAccountantOrAdmin}), the
 * page permission {@code accounting.pledges} and {@code accounting.pledges.edit}. Unlike the page's form the fund
 * is re-checked here to be one of THIS church's income funds, at proposal time and again at Save.
 */
public final class AiPledgeTools {

    private AiPledgeTools() {}

    public static final int MAX_NAME = 200, MAX_OTHER = 500, MAX_DESCRIPTION = 2000;
    public static final BigDecimal MAX_TARGET = new BigDecimal("999999999.99");

    public static boolean mayEditPledges(HttpServletRequest req) {
        return RoleGuard.requireAccountantOrAdmin(req) == null
            && RoleGuard.requirePagePermission(req, "accounting.pledges") == null
            && RoleGuard.requirePermission(req, "accounting.pledges.edit") == null;
    }

    static String text(Object raw, int max) {
        if (raw == null) return null;
        String t = String.valueOf(raw).replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").replaceAll("[ \\t]+", " ").trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    @Component
    public static class CreateCampaign implements AiTool {
        private final PledgeCampaignRepository repo; private final AiFinanceLookup funds;
        public CreateCampaign(PledgeCampaignRepository repo, AiFinanceLookup funds) { this.repo = repo; this.funds = funds; }
        public String name() { return "create_pledge_campaign"; }
        public String label() { return "Create a pledge campaign"; }
        public String description() { return "Create a new pledge campaign on the Pledges page."; }
        public Map<String, String> parameters() { return Map.of("name", "the campaign name", "fundId", "income fund (optional)", "targetAmount", "target (optional)", "endDate", "end date (optional)"); }
        @Override public boolean guidedOnly() { return true; }
        public boolean allowed(HttpServletRequest req, AiAccess a, CrossAccess x, String cid) { return mayEditPledges(req); }

        public Resolution resolve(Map<String, Object> raw, String cid, String username) {
            if (AiSettingsTools.blank(cid)) return Resolution.refuse("No church is selected.");
            String name = AiSettingsTools.cleanName(raw.get("name") == null ? null : text(raw.get("name"), MAX_NAME));
            if (name == null) return Resolution.refuse("A campaign name is needed.");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", name);
            Long fid = AiSettingsTools.l(raw, "fundId");
            if (fid != null) {
                Hit f = funds.incomeFundById(cid, fid);
                if (f == null) return Resolution.refuse("That fund is no longer one of this church's funds.");
                r.put("fundId", f.id()); r.put("fundLabel", f.label());
            }
            if (raw.get("targetAmount") != null && !String.valueOf(raw.get("targetAmount")).isBlank()) {
                BigDecimal t;
                try { t = new BigDecimal(String.valueOf(raw.get("targetAmount")).replace(",", "").replace("$", "").trim()); }
                catch (Exception e) { return Resolution.refuse("The target amount is not a number."); }
                if (t.signum() <= 0 || t.scale() > 2 || t.compareTo(MAX_TARGET) > 0) return Resolution.refuse("The target amount must be more than zero, with at most two decimals.");
                r.put("targetAmount", t.setScale(2).toPlainString());
            }
            if (raw.get("endDate") != null && !String.valueOf(raw.get("endDate")).isBlank()) {
                try { r.put("endDate", LocalDate.parse(String.valueOf(raw.get("endDate")).trim()).toString()); }
                catch (Exception e) { return Resolution.refuse("The end date is not a valid date."); }
            }
            String other = text(raw.get("otherGifts"), MAX_OTHER); if (other != null) r.put("otherGifts", other);
            String desc = text(raw.get("description"), MAX_DESCRIPTION); if (desc != null) r.put("description", desc);
            return Resolution.ok(r);
        }
        public String summary(Map<String, Object> r) { return "Create the pledge campaign “" + r.get("name") + "”."; }
        public Map<String, Object> execute(Map<String, Object> r, String cid, String username) {
            PledgeCampaign c = new PledgeCampaign();
            c.setClientId(cid); c.setName(String.valueOf(r.get("name"))); c.setStatus("Active");
            Long fid = AiSettingsTools.l(r, "fundId"); if (fid != null) c.setSubSourceId(fid.intValue());
            if (r.get("targetAmount") != null) c.setTargetAmount(new BigDecimal(String.valueOf(r.get("targetAmount"))));
            if (r.get("endDate") != null) c.setEndDate(LocalDate.parse(String.valueOf(r.get("endDate"))));
            if (r.get("otherGifts") != null) c.setOtherGifts(String.valueOf(r.get("otherGifts")));
            if (r.get("description") != null) c.setDescription(String.valueOf(r.get("description")));
            c = repo.save(c);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("campaignId", c.getId());
            out.put("answer", "The pledge campaign “" + c.getName() + "” was created. Open Pledges to add members' pledges to it.");
            out.put("link", new LinkedHashMap<>(Map.of("label", "Open Pledges", "url", "/pledges")));
            return out;
        }
    }
}
