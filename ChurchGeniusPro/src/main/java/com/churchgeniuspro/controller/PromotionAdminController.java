package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.PromotionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
 * Service Admin → Promotional Deals (promotions phase 1): the deal catalogue.
 * Every endpoint requires a Service Admin session, like Subscription Plans and Billing.
 * There is deliberately no delete endpoint — a deal is deactivated, never removed.
 *
 * <ul>
 *   <li>{@code GET  /api/serviceadmin/promotions} → all deals + the plans a deal may cover</li>
 *   <li>{@code POST /api/serviceadmin/promotions} → create</li>
 *   <li>{@code PUT  /api/serviceadmin/promotions/{id}} → edit (the code never changes)</li>
 *   <li>{@code POST /api/serviceadmin/promotions/{id}/active} → deactivate / reactivate</li>
 *   <li>{@code GET  /api/serviceadmin/promotions/{id}/history} → who changed what, and when</li>
 * </ul>
 */
@RestController
public class PromotionAdminController {

    private static final Logger log = LoggerFactory.getLogger(PromotionAdminController.class);

    private final PromotionService promotions;

    public PromotionAdminController(PromotionService promotions) {
        this.promotions = promotions;
    }

    @GetMapping("/api/serviceadmin/promotions")
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return run(() -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deals", promotions.list());
            m.put("plans", promotions.eligiblePlans());
            return ok(m);
        });
    }

    @PostMapping("/api/serviceadmin/promotions")
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body,
                                                      HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return run(() -> ok(Map.of("deal", promotions.create(form(body), actor(req)),
                                   "message", "Deal created.")));
    }

    @PutMapping("/api/serviceadmin/promotions/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id,
                                                      @RequestBody(required = false) Map<String, Object> body,
                                                      HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return run(() -> ok(Map.of("deal", promotions.update(id, form(body), actor(req)),
                                   "message", "Deal saved.")));
    }

    @PostMapping("/api/serviceadmin/promotions/{id}/active")
    public ResponseEntity<Map<String, Object>> setActive(@PathVariable Long id,
                                                         @RequestBody(required = false) Map<String, Object> body,
                                                         HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return run(() -> {
            Object a = body == null ? null : body.get("active");
            if (!(a instanceof Boolean active)) throw new IllegalArgumentException("Say whether the deal should be active.");
            return ok(Map.of("deal", promotions.setActive(id, active, lng(body.get("version")), actor(req)),
                             "message", active ? "Deal reactivated." : "Deal deactivated."));
        });
    }

    @GetMapping("/api/serviceadmin/promotions/{id}/history")
    public ResponseEntity<Map<String, Object>> history(@PathVariable Long id, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return run(() -> ok(Map.of("history", promotions.historyOf(id))));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static PromotionService.Form form(Map<String, Object> body) {
        if (body == null) throw new IllegalArgumentException("Enter the deal details.");
        List<String> plans = new ArrayList<>();
        if (body.get("planCodes") instanceof List<?> l) {
            for (Object o : l) if (o != null) plans.add(String.valueOf(o));
        }
        Long amount = lng(body.get("durationAmount"));
        return new PromotionService.Form(s(body.get("code")), s(body.get("name")), s(body.get("billingFrequency")),
                s(body.get("discountType")), s(body.get("discountValue")),
                amount == null ? null : amount.intValue(), s(body.get("durationUnit")), plans,
                s(body.get("assignFrom")), s(body.get("assignUntil")), s(body.get("notes")), lng(body.get("version")));
    }

    private interface Action { ResponseEntity<Map<String, Object>> call(); }

    private static ResponseEntity<Map<String, Object>> run(Action a) {
        try {
            return a.call();
        } catch (PromotionService.Conflict e) {
            return ResponseEntity.status(409).body(error(e.getMessage()));
        } catch (ObjectOptimisticLockingFailureException e) {
            return ResponseEntity.status(409).body(error("This deal was changed by someone else. Reopen it and try again."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            log.error("Promotion action failed", e);
            return ResponseEntity.status(500).body(error("Something went wrong. Please try again."));
        }
    }

    private static ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) {
        Map<String, Object> m = new LinkedHashMap<>(body);
        m.put("status", "success");
        return ResponseEntity.ok(m);
    }

    private static Map<String, Object> error(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("message", msg);
        return m;
    }

    private static String actor(HttpServletRequest req) { return ServiceAdminPlatformSettingsController.actor(req); }

    private static boolean isServiceAdmin(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null && "ServiceAdmin".equals(s.getAttribute("role"));
    }

    private static ResponseEntity<Map<String, Object>> denied() {
        return ResponseEntity.status(401).body(error("Not signed in as Service Admin"));
    }

    private static String s(Object o) {
        if (o == null) return null;
        String t = String.valueOf(o).trim();
        return t.isEmpty() ? null : t;
    }

    private static Long lng(Object o) {
        String t = s(o);
        if (t == null) return null;
        try { return Long.valueOf(t.contains(".") ? t.substring(0, t.indexOf('.')) : t); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid number: " + t); }
    }
}
