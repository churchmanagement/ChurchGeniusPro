package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.ClientPromotionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service Admin → a client's discount (promotions phase 2): give a promotional deal or
 * a manual discount, change its terms, cancel it, and read its history. Service Admin
 * session required on every endpoint. Nothing here deletes anything.
 *
 * <ul>
 *   <li>{@code GET  /api/serviceadmin/clients/{id}/promotion} → current discount, past ones, history, assignable deals</li>
 *   <li>{@code POST /api/serviceadmin/clients/{id}/promotion} → assign</li>
 *   <li>{@code PUT  /api/serviceadmin/clients/{id}/promotion} → change terms (note required)</li>
 *   <li>{@code POST /api/serviceadmin/clients/{id}/promotion/cancel} → cancel (reason required)</li>
 *   <li>{@code GET  /api/serviceadmin/client-promotions/summary} → client id → live discount, for the list badge</li>
 * </ul>
 */
@RestController
public class ClientPromotionAdminController {

    private static final Logger log = LoggerFactory.getLogger(ClientPromotionAdminController.class);

    private final ClientPromotionService promotions;

    public ClientPromotionAdminController(ClientPromotionService promotions) {
        this.promotions = promotions;
    }

    @GetMapping("/api/serviceadmin/clients/{id}/promotion")
    public ResponseEntity<Map<String, Object>> view(@PathVariable Integer id, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return run(() -> ok(promotions.view(id)));
    }

    @PostMapping("/api/serviceadmin/clients/{id}/promotion")
    public ResponseEntity<Map<String, Object>> assign(@PathVariable Integer id,
                                                      @RequestBody(required = false) Map<String, Object> body,
                                                      HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return run(() -> {
            if (body == null) throw new IllegalArgumentException("Choose a promotional deal or a manual discount.");
            ClientPromotionService.AssignForm f = new ClientPromotionService.AssignForm(s(body.get("kind")),
                    s(body.get("promotionCode")), terms(body.get("terms")), s(body.get("notes")),
                    Boolean.TRUE.equals(body.get("confirmRepeat")));
            return ok(Map.of("discount", promotions.assign(id, f, actor(req)), "message", "Discount saved."));
        });
    }

    @PutMapping("/api/serviceadmin/clients/{id}/promotion")
    public ResponseEntity<Map<String, Object>> modify(@PathVariable Integer id,
                                                      @RequestBody(required = false) Map<String, Object> body,
                                                      HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return run(() -> {
            if (body == null) throw new IllegalArgumentException("Enter the new terms.");
            return ok(Map.of("discount", promotions.modify(id, terms(body.get("terms")), s(body.get("notes")), actor(req)),
                             "message", "Discount terms changed."));
        });
    }

    @PostMapping("/api/serviceadmin/clients/{id}/promotion/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable Integer id,
                                                      @RequestBody(required = false) Map<String, Object> body,
                                                      HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return run(() -> ok(Map.of("discount", promotions.cancel(id, body == null ? null : s(body.get("reason")), actor(req)),
                                   "message", "Discount cancelled.")));
    }

    @GetMapping("/api/serviceadmin/client-promotions/summary")
    public ResponseEntity<Map<String, Object>> summary(HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return run(() -> ok(Map.of("discounts", promotions.summary())));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static ClientPromotionService.Terms terms(Object o) {
        if (!(o instanceof Map<?, ?> m)) return null;
        Integer amount = null;
        String n = s(m.get("durationAmount"));
        if (n != null) {
            try { amount = Integer.valueOf(n.contains(".") ? n.substring(0, n.indexOf('.')) : n); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Enter the duration as a number."); }
        }
        return new ClientPromotionService.Terms(s(m.get("discountType")), s(m.get("discountValue")), amount,
                s(m.get("durationUnit")), s(m.get("endDate")));
    }

    private interface Action { ResponseEntity<Map<String, Object>> call(); }

    private static ResponseEntity<Map<String, Object>> run(Action a) {
        try {
            return a.call();
        } catch (ClientPromotionService.RepeatConfirmationRequired e) {
            Map<String, Object> m = error(e.getMessage());
            m.put("status", "confirm");
            m.put("code", "REPEAT_DEAL_CONFIRMATION_REQUIRED");
            return ResponseEntity.status(409).body(m);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException e) {
            return ResponseEntity.status(409).body(error("This client's discount was changed by someone else. Reopen it and try again."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            log.error("Client promotion action failed", e);
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
}
