package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.SubscriptionPlan;
import com.churchgeniuspro.repository.SubscriptionPlanRepository;
import com.churchgeniuspro.service.SubscriptionService;
import com.churchgeniuspro.util.SubscriptionFeatureCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service Admin management of subscription plans (create / edit plans, their
 * limits, and feature flags). Guarded by the {@code serviceAdminId} session
 * attribute (same pattern as TestDataController — /api/serviceadmin/* is
 * whitelisted in AuthFilter, so each endpoint checks itself).
 *
 * <h3>Routes</h3>
 * <ul>
 *   <li>{@code GET  /api/serviceadmin/subscription-plans} → all plans + the feature catalog</li>
 *   <li>{@code POST /api/serviceadmin/subscription-plans} → create a plan</li>
 *   <li>{@code PUT  /api/serviceadmin/subscription-plans/{id}} → update a plan</li>
 * </ul>
 */
@RestController
public class SubscriptionPlanAdminController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SubscriptionPlanRepository planRepo;
    private final SubscriptionService        subscriptionService;

    public SubscriptionPlanAdminController(SubscriptionPlanRepository planRepo,
                                           SubscriptionService subscriptionService) {
        this.planRepo            = planRepo;
        this.subscriptionService = subscriptionService;
    }

    // ── List ──────────────────────────────────────────────────────────────

    @GetMapping("/api/serviceadmin/subscription-plans")
    public ResponseEntity<?> list(HttpServletRequest request) {
        ResponseEntity<?> deny = requireServiceAdmin(request);
        if (deny != null) return deny;

        List<Map<String, Object>> plans = new ArrayList<>();
        for (SubscriptionPlan p : planRepo.findAllByOrderBySortOrderAscIdAsc()) {
            plans.add(toMap(p));
        }
        List<Map<String, Object>> catalog = new ArrayList<>();
        for (SubscriptionFeatureCatalog.Feature f : SubscriptionFeatureCatalog.FEATURES) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key",   f.key());
            m.put("label", f.label());
            m.put("group", f.group());
            catalog.add(m);
        }
        return ResponseEntity.ok(Map.of("plans", plans, "featureCatalog", catalog));
    }

    // ── Create ────────────────────────────────────────────────────────────

    @PostMapping("/api/serviceadmin/subscription-plans")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        ResponseEntity<?> deny = requireServiceAdmin(request);
        if (deny != null) return deny;

        String code = str(body.get("planCode"));
        String name = str(body.get("planName"));
        if (code == null || name == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error", "message", "Plan code and plan name are required."));
        }
        code = code.trim().toUpperCase().replaceAll("[^A-Z0-9_]", "_");
        if (planRepo.existsByPlanCodeIgnoreCase(code)) {
            return ResponseEntity.status(409).body(Map.of(
                    "status", "error", "message", "A plan with code '" + code + "' already exists."));
        }

        SubscriptionPlan p = new SubscriptionPlan();
        p.setPlanCode(code);
        apply(p, body);
        p.setPlanName(name);
        planRepo.save(p);
        subscriptionService.clearCache();
        return ResponseEntity.ok(Map.of("status", "success", "plan", toMap(p)));
    }

    // ── Update ────────────────────────────────────────────────────────────

    @PutMapping("/api/serviceadmin/subscription-plans/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                    HttpServletRequest request) {
        ResponseEntity<?> deny = requireServiceAdmin(request);
        if (deny != null) return deny;

        SubscriptionPlan p = planRepo.findById(id).orElse(null);
        if (p == null) {
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", "Plan not found."));
        }
        apply(p, body);
        if (str(body.get("planName")) != null) p.setPlanName(str(body.get("planName")));
        planRepo.save(p);
        subscriptionService.clearCache();
        return ResponseEntity.ok(Map.of("status", "success", "plan", toMap(p)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Copies editable fields from the request body onto the plan. */
    private void apply(SubscriptionPlan p, Map<String, Object> body) {
        if (body.containsKey("description"))             p.setDescription(str(body.get("description")));
        if (body.containsKey("maxPeople"))               p.setMaxPeople(intOrNull(body.get("maxPeople")));
        if (body.containsKey("maxEmailsPerMonth"))       p.setMaxEmailsPerMonth(intOrNull(body.get("maxEmailsPerMonth")));
        if (body.containsKey("maxSmsPerMonth"))          p.setMaxSmsPerMonth(intOrNull(body.get("maxSmsPerMonth")));
        // NOTE: extraSmsCount intentionally NOT handled here — Extra SMS credits
        // are configured per registered client (ServiceClient.extraSmsCount).
        if (body.containsKey("maxOnlineGivingPerMonth")) p.setMaxOnlineGivingPerMonth(intOrNull(body.get("maxOnlineGivingPerMonth")));
        if (body.containsKey("maxMemberPortals"))        p.setMaxMemberPortals(intOrNull(body.get("maxMemberPortals")));
        if (body.containsKey("maxKidsPortals"))          p.setMaxKidsPortals(intOrNull(body.get("maxKidsPortals")));
        if (body.containsKey("sortOrder"))               p.setSortOrder(intOrNull(body.get("sortOrder")));
        if (body.containsKey("active"))                  p.setActive(Boolean.TRUE.equals(body.get("active"))
                                                                  || "true".equalsIgnoreCase(str(body.get("active"))));
        Object features = body.get("features");
        if (features instanceof Map<?, ?> m) {
            try { p.setFeaturesJson(MAPPER.writeValueAsString(m)); } catch (Exception ignored) { }
        }
    }

    private Map<String, Object> toMap(SubscriptionPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                      p.getId());
        m.put("planCode",                p.getPlanCode());
        m.put("planName",                p.getPlanName());
        m.put("description",             p.getDescription());
        m.put("maxPeople",               p.getMaxPeople());
        m.put("maxEmailsPerMonth",       p.getMaxEmailsPerMonth());
        m.put("maxSmsPerMonth",          p.getMaxSmsPerMonth());
        m.put("maxOnlineGivingPerMonth", p.getMaxOnlineGivingPerMonth());
        m.put("maxMemberPortals",        p.getMaxMemberPortals());
        m.put("maxKidsPortals",          p.getMaxKidsPortals());
        m.put("active",                  p.isActive());
        m.put("sortOrder",               p.getSortOrder());
        try {
            m.put("features", p.getFeaturesJson() != null && !p.getFeaturesJson().isBlank()
                    ? MAPPER.readValue(p.getFeaturesJson(), Map.class) : Map.of());
        } catch (Exception e) {
            m.put("features", Map.of());
        }
        return m;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    /** Blank/"unlimited"/null → null (unlimited); otherwise the integer. */
    private static Integer intOrNull(Object o) {
        String s = str(o);
        if (s == null || s.equalsIgnoreCase("unlimited")) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    /** Same service-admin session guard pattern as TestDataController. */
    private ResponseEntity<?> requireServiceAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("serviceAdminId") == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "status", "error", "message", "Service admin login required."));
        }
        return null;
    }
}
