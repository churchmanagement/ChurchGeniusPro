package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.BillingInvoice;
import com.churchgeniuspro.hibernate.ClientCharge;
import com.churchgeniuspro.service.BillingService;
import com.churchgeniuspro.service.PlatformSettingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

/**
 * Service Admin → Billing: upcoming billing dates, additional charges, invoices
 * (create → review → send, re-send, mark paid, void) and the reminder switches.
 * Every endpoint requires the Service Admin session (ServiceAdminAuthFilter guards
 * {@code /api/serviceadmin}; the role is checked again here).
 */
@RestController
@RequestMapping("/api/serviceadmin/billing")
public class ServiceAdminBillingController {

    private static final Logger log = LoggerFactory.getLogger(ServiceAdminBillingController.class);

    private final BillingService billing;
    private final PlatformSettingService settings;

    public ServiceAdminBillingController(BillingService billing, PlatformSettingService settings) {
        this.billing = billing;
        this.settings = settings;
    }

    /** Phase 6: receipts after a manual mark paid, and cancelling a voided invoice's open PaymentIntent. */
    private com.churchgeniuspro.service.BillingPaymentService payments;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPayments(com.churchgeniuspro.service.BillingPaymentService payments) { this.payments = payments; }

    // ── Lists ──────────────────────────────────────────────────────────────

    @GetMapping("/upcoming")
    public ResponseEntity<Map<String, Object>> upcoming(HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return ok(Map.of("clients", billing.upcoming(), "days", BillingService.UPCOMING_DAYS));
    }

    @GetMapping("/invoices")
    public ResponseEntity<Map<String, Object>> invoices(HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return ok(Map.of("invoices", billing.listInvoices()));
    }

    @GetMapping("/invoices/{id}")
    public ResponseEntity<Map<String, Object>> invoice(@PathVariable Long id, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return run(() -> ok(Map.of("invoice", billing.detail(id))));
    }

    @GetMapping("/charges")
    public ResponseEntity<Map<String, Object>> charges(HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return ok(Map.of("charges", billing.listCharges(), "paymentMethods", BillingService.PAYMENT_METHODS));
    }

    // ── Charges ────────────────────────────────────────────────────────────

    @PostMapping("/charges")
    public ResponseEntity<Map<String, Object>> createCharge(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return run(() -> {
            ClientCharge c = billing.createCharge(chargeForm(body), actor(req));
            return ok(Map.of("message", "Charge added. It will be included on the client's next invoice.", "id", c.getId()));
        });
    }

    @PutMapping("/charges/{id}")
    public ResponseEntity<Map<String, Object>> updateCharge(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                            HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return run(() -> {
            billing.updateCharge(id, chargeForm(body), actor(req));
            return ok(Map.of("message", "Charge updated."));
        });
    }

    @PostMapping("/charges/{id}/void")
    public ResponseEntity<Map<String, Object>> voidCharge(@PathVariable Long id, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return run(() -> {
            billing.voidCharge(id, actor(req));
            return ok(Map.of("message", "Charge voided."));
        });
    }

    @PostMapping("/charges/{id}/mark-paid")
    public ResponseEntity<Map<String, Object>> chargePaid(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body,
                                                          HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return run(() -> {
            billing.markChargePaid(id, date(body, "paidDate"), str(body, "paymentMethod"), actor(req));
            return ok(Map.of("message", "Charge marked paid."));
        });
    }

    // ── Invoices ───────────────────────────────────────────────────────────

    /** {clientId, kind: RENEWAL | MANUAL} → the draft (or the existing renewal invoice). */
    @PostMapping("/invoices")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return run(() -> {
            String kind = Optional.ofNullable(str(body, "kind")).orElse(BillingInvoice.KIND_RENEWAL).toUpperCase();
            BillingService.Draft d = BillingInvoice.KIND_MANUAL.equals(kind)
                    ? billing.createManualDraft(str(body, "clientId"), actor(req))
                    : billing.createRenewalDraft(str(body, "clientId"), actor(req));
            return ok(draftResult(d));
        });
    }

    @PostMapping("/invoices/from-request/{requestId}")
    public ResponseEntity<Map<String, Object>> fromRequest(@PathVariable Long requestId, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return run(() -> ok(draftResult(billing.createRequestDraft(requestId, actor(req)))));
    }

    @PutMapping("/invoices/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                                      HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return run(() -> {
            List<BillingService.LineForm> lines = new ArrayList<>();
            Object raw = body.get("lines");
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> m)) continue;
                    lines.add(new BillingService.LineForm(s(m.get("kind")), s(m.get("description")), s(m.get("amount")),
                            lng(m.get("chargeId"))));
                }
            }
            BillingInvoice inv = billing.updateDraft(id, new BillingService.DraftForm(lng(body.get("version")),
                    str(body, "billToName"), str(body, "billToEmail"), date(body, "dueDate"), str(body, "note"),
                    str(body, "discount"), lines), actor(req));
            return ok(Map.of("message", "Draft saved.", "version", inv.getVersion()));
        });
    }

    @PostMapping("/invoices/{id}/send")
    public ResponseEntity<Map<String, Object>> send(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body,
                                                    HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return run(() -> {
            BillingService.Sent s = billing.send(id, body == null ? null : lng(body.get("version")), actor(req));
            return ok(Map.of("message", "Invoice " + s.invoice().getInvoiceNumber() + " sent to " + s.invoice().getBillToEmail() + ".",
                    "link", s.link()));
        });
    }

    @PostMapping("/invoices/{id}/resend")
    public ResponseEntity<Map<String, Object>> resend(@PathVariable Long id, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return run(() -> {
            BillingService.Sent s = billing.resend(id, actor(req));
            return ok(Map.of("message", "Invoice " + s.invoice().getInvoiceNumber() + " re-sent to " + s.invoice().getBillToEmail()
                    + ". Earlier links no longer work.", "link", s.link()));
        });
    }

    @PostMapping("/invoices/{id}/mark-paid")
    public ResponseEntity<Map<String, Object>> markPaid(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body,
                                                        HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return run(() -> {
            billing.markPaid(id, date(body, "paidDate"), str(body, "paymentMethod"), str(body, "reference"), actor(req));
            String msg = "Invoice marked paid. To extend the subscription, update the client's end date.";
            if (body != null && Boolean.TRUE.equals(body.get("sendReceipt")) && payments != null) {
                try {
                    payments.sendReceipt(id);
                    msg += " A receipt was emailed.";
                } catch (Exception e) {
                    msg += " The payment is recorded, but the receipt email could not be sent ("
                         + com.churchgeniuspro.logging.SensitiveDataMasker.mask(String.valueOf(e.getMessage())) + ").";
                }
            }
            return ok(Map.of("message", msg));
        });
    }

    @PostMapping("/invoices/{id}/void")
    public ResponseEntity<Map<String, Object>> voidInvoice(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body,
                                                           HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return run(() -> {
            billing.voidInvoice(id, str(body, "reason"), actor(req));
            if (payments != null) payments.afterVoid(id);   // cancel an open card payment (best effort)
            return ok(Map.of("message", "Invoice voided. Its link no longer works and its charges are unbilled again."));
        });
    }

    // ── Reminder settings ──────────────────────────────────────────────────

    @GetMapping("/settings")
    public ResponseEntity<Map<String, Object>> getSettings(HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        return ok(settingsMap());
    }

    @PutMapping("/settings")
    public ResponseEntity<Map<String, Object>> putSettings(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!isServiceAdmin(req)) return denied();
        String who = actor(req);
        if (body.containsKey("supportEnabled")) {
            settings.set(PlatformSettingService.BILLING_REMINDER_SUPPORT_ENABLED, String.valueOf(Boolean.TRUE.equals(body.get("supportEnabled"))), who);
        }
        if (body.containsKey("clientEnabled")) {
            settings.set(PlatformSettingService.BILLING_REMINDER_CLIENT_ENABLED, String.valueOf(Boolean.TRUE.equals(body.get("clientEnabled"))), who);
        }
        log.info("Billing reminder settings changed by {}: {}", who, body);
        Map<String, Object> m = settingsMap();
        m.put("message", "Reminder settings saved.");
        return ok(m);
    }

    private Map<String, Object> settingsMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("supportEnabled", settings.isEnabled(PlatformSettingService.BILLING_REMINDER_SUPPORT_ENABLED));
        m.put("clientEnabled", settings.isEnabled(PlatformSettingService.BILLING_REMINDER_CLIENT_ENABLED));
        m.put("supportEmail", settings.supportEmail());
        m.put("days", List.of(10, 3, 0));
        return m;
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static Map<String, Object> draftResult(BillingService.Draft d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.invoice().getId());
        m.put("created", d.created());
        m.put("message", d.created() ? "Draft " + d.invoice().getInvoiceNumber() + " created. Review it before sending."
                : "Invoice " + d.invoice().getInvoiceNumber() + " already exists (" + d.invoice().getStatus().toLowerCase() + ").");
        return m;
    }

    private static BillingService.ChargeForm chargeForm(Map<String, Object> body) {
        return new BillingService.ChargeForm(str(body, "clientId"), str(body, "description"), str(body, "amount"),
                str(body, "billingPeriod"), str(body, "note"));
    }

    private interface Action { ResponseEntity<Map<String, Object>> call(); }

    private static ResponseEntity<Map<String, Object>> run(Action a) {
        try {
            return a.call();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(error(e.getMessage()));
        } catch (Exception e) {
            log.error("Billing action failed", e);
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

    private static String str(Map<String, Object> body, String key) { return body == null ? null : s(body.get(key)); }

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

    private static LocalDate date(Map<String, Object> body, String key) {
        String t = str(body, key);
        if (t == null) return null;
        try { return LocalDate.parse(t); }
        catch (Exception e) { throw new IllegalArgumentException("Dates must be YYYY-MM-DD."); }
    }
}
