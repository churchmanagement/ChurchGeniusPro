package com.churchgeniuspro.controller;

import com.churchgeniuspro.logging.SensitiveDataMasker;
import com.churchgeniuspro.service.BillingPaymentService;
import com.churchgeniuspro.service.PlatformStripeService;
import com.churchgeniuspro.util.PublicFormGuard;
import com.churchgeniuspro.util.RoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Online (Stripe) payment of platform invoices (Phase 6): card, and whatever other methods the
 * Stripe Dashboard enables (ACH Direct Debit, bank transfer, …).
 *
 * <ul>
 *   <li>{@code /api/invoice/pay/*} — reached from the secure invoice page; the invoice is
 *       identified ONLY by its link token, exactly as for viewing it (same 404 for every
 *       refusal). The amount is never taken from the browser. Rate-limited per IP.</li>
 *   <li>{@code POST /api/billing/stripe-webhook} — Stripe's signed webhook for the
 *       platform account. Anonymous and CSRF-exempt by necessity; nothing is applied
 *       unless the HMAC signature verifies within the time tolerance. Returns no data.</li>
 * </ul>
 * No response carries a Stripe secret. The PaymentIntent client secret is returned only to
 * the holder of the invoice link, for that invoice's own PaymentIntent, because Stripe.js
 * needs it to collect the card; it is never logged.
 */
@RestController
public class InvoicePaymentController {

    private static final Logger log = LoggerFactory.getLogger(InvoicePaymentController.class);
    static final String NOT_AVAILABLE = InvoiceController.NOT_AVAILABLE;

    private final BillingPaymentService payments;
    private final PublicFormGuard guard;

    public InvoicePaymentController(BillingPaymentService payments, PublicFormGuard guard) {
        this.payments = payments;
        this.guard = guard;
    }

    @GetMapping("/api/invoice/pay/config")
    public ResponseEntity<Map<String, Object>> config(@RequestParam(required = false) String t, HttpServletRequest req) {
        try {
            Map<String, Object> m = new LinkedHashMap<>(payments.payConfig(t, sessionClient(req)));
            m.put("status", "success");
            return secure(ResponseEntity.ok()).body(m);
        } catch (BillingPaymentService.NotFound e) {
            return secure(ResponseEntity.status(404)).body(error(NOT_AVAILABLE));
        }
    }

    @PostMapping("/api/invoice/pay/start")
    public ResponseEntity<Map<String, Object>> start(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        String rate = guard.checkRate(PublicFormGuard.clientIp(req), "invoice-pay");
        if (rate != null) return secure(ResponseEntity.status(429)).body(error(rate));
        try {
            BillingPaymentService.Started s = payments.startPayment(str(body, "t"), sessionClient(req));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "success");
            m.put("clientSecret", s.clientSecret());
            m.put("publishableKey", s.publishableKey());
            m.put("amountCents", s.amountCents());
            return secure(ResponseEntity.ok()).body(m);
        } catch (BillingPaymentService.NotFound e) {
            return secure(ResponseEntity.status(404)).body(error(NOT_AVAILABLE));
        } catch (IllegalStateException e) {
            return secure(ResponseEntity.status(409)).body(error(e.getMessage()));
        } catch (PlatformStripeService.StripeException e) {
            log.error("Invoice online payment could not start — {}", e.getMessage());   // already masked
            return secure(ResponseEntity.status(502)).body(error("Online payment is temporarily unavailable. Please try again later."));
        }
    }

    @PostMapping("/api/invoice/pay/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        String rate = guard.checkRate(PublicFormGuard.clientIp(req), "invoice-pay-confirm");
        if (rate != null) return secure(ResponseEntity.status(429)).body(error(rate));
        try {
            Map<String, Object> m = new LinkedHashMap<>(payments.confirm(str(body, "t"), sessionClient(req)));
            m.put("status", "success");
            return secure(ResponseEntity.ok()).body(m);
        } catch (BillingPaymentService.NotFound e) {
            return secure(ResponseEntity.status(404)).body(error(NOT_AVAILABLE));
        } catch (PlatformStripeService.StripeException e) {
            log.error("Invoice online payment confirm failed — {}", e.getMessage());
            return secure(ResponseEntity.status(502)).body(error("We could not check the payment just now. If you were charged, "
                    + "the invoice will update shortly."));
        }
    }

    /** Stripe → platform webhook. 400 = signature refused (nothing applied); 500 = Stripe should retry. */
    @PostMapping("/api/billing/stripe-webhook")
    public ResponseEntity<String> webhook(@RequestBody(required = false) String payload,
                                          @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        try {
            String outcome = payments.handleWebhook(payload, signature);
            return ResponseEntity.ok("ok:" + outcome);
        } catch (PlatformStripeService.WebhookSignatureException e) {
            log.warn("Platform Stripe webhook refused: {}", e.getMessage());
            return ResponseEntity.status(400).body("invalid");
        } catch (Exception e) {
            log.error("Platform Stripe webhook failed — {}", SensitiveDataMasker.mask(String.valueOf(e.getMessage())));
            return ResponseEntity.status(500).body("error");
        }
    }

    private static String sessionClient(HttpServletRequest req) {
        if (req.getSession(false) == null) return null;
        String cid = RoleGuard.clientId(req);
        return cid == null || cid.isBlank() ? null : cid;
    }

    private static ResponseEntity.BodyBuilder secure(ResponseEntity.BodyBuilder b) {
        return b.cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").header("X-Robots-Tag", "noindex, nofollow");
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) return null;
        String s = String.valueOf(body.get(key)).trim();
        return s.isEmpty() ? null : s;
    }

    private static Map<String, Object> error(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("message", msg);
        return m;
    }
}
