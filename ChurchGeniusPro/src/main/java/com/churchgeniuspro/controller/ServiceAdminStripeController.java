package com.churchgeniuspro.controller;

import com.churchgeniuspro.service.BillingPaymentService;
import com.churchgeniuspro.service.PlatformStripeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service Admin → Billing → Card payments (Stripe). Status with masked hints, a read-only
 * Test connection, and an invoice's payment audit trail. The keys are Azure App Service
 * settings; nothing here can set, store or reveal them.
 */
@RestController
@RequestMapping("/api/serviceadmin/billing")
public class ServiceAdminStripeController {

    private final PlatformStripeService stripe;
    private final BillingPaymentService payments;

    public ServiceAdminStripeController(PlatformStripeService stripe, BillingPaymentService payments) {
        this.stripe = stripe;
        this.payments = payments;
    }

    @GetMapping("/stripe")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        Map<String, Object> m = new LinkedHashMap<>(stripe.status());
        m.put("webhookPath", "/api/billing/stripe-webhook");
        m.put("webhookEvent", "payment_intent.succeeded");
        m.put("status", "success");
        return ResponseEntity.ok(m);
    }

    @PostMapping("/stripe/test")
    public ResponseEntity<Map<String, Object>> test(HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        Map<String, Object> m = new LinkedHashMap<>(stripe.testConnection());
        m.put("status", "success");
        return ResponseEntity.ok(m);
    }

    @GetMapping("/invoices/{id}/payments")
    public ResponseEntity<Map<String, Object>> events(@PathVariable Long id, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return ResponseEntity.ok(Map.of("status", "success", "events", payments.paymentEvents(id)));
    }

    private static boolean isServiceAdmin(HttpServletRequest req) {
        HttpSession s = req.getSession(false);
        return s != null && "ServiceAdmin".equals(s.getAttribute("role"));
    }

    private static ResponseEntity<Map<String, Object>> denied() {
        return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Not signed in as Service Admin"));
    }
}
