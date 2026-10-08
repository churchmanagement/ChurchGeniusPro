package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.ServiceClient;
import com.churchgeniuspro.hibernate.SubscriptionRequest;
import com.churchgeniuspro.service.SubscriptionRequestService;
import com.churchgeniuspro.util.PublicFormGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The "Request for Subscription" page's API ({@code /subscriptionReq}).
 *
 * <p>Reachable two ways, both of which identify the church BEFORE anything is shown:
 * the request-link token from a trial reminder email ({@code t}), or the session of a
 * signed-in church owner / admin. Anonymous by path (AuthFilter) and allowed for an
 * ended subscription (AccountStatusFilter), because a church whose trial has ended is
 * exactly who needs it. POSTs run the shared anti-bot stack ({@link PublicFormGuard}).
 */
@RestController
public class SubscriptionRequestController {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionRequestController.class);
    private static final String FORM = "subscription-request";
    static final String LINK_INVALID =
            "This subscription request link is no longer valid. Please contact support@churchgeniuspro.com.";

    private final SubscriptionRequestService requests;
    private final PublicFormGuard formGuard;

    public SubscriptionRequestController(SubscriptionRequestService requests, PublicFormGuard formGuard) {
        this.requests = requests;
        this.formGuard = formGuard;
    }

    static final String SESSION_NOT_ALLOWED =
            "Only the church's owner or an administrator can request a subscription. "
          + "Please ask them, or contact support@churchgeniuspro.com.";

    private static String refusal(String token) {
        return (token != null && !token.isBlank()) ? LINK_INVALID : SESSION_NOT_ALLOWED;
    }

    private Optional<ServiceClient> church(String token, HttpServletRequest req) {
        if (token != null && !token.isBlank()) return requests.clientForToken(token);
        return requests.clientForSession(req);
    }

    @GetMapping("/api/subscription-request/form-info")
    public ResponseEntity<Map<String, Object>> formInfo(@RequestParam(required = false) String t, HttpServletRequest req) {
        Optional<ServiceClient> sc = church(t, req);
        if (sc.isEmpty()) return ResponseEntity.status(403).body(error(refusal(t)));
        Map<String, Object> m = new LinkedHashMap<>(requests.formInfo(sc.get()));
        m.put("status", "success");
        m.put("formToken", formGuard.issueToken(null));
        if (formGuard.captchaSiteKey() != null) m.put("captchaSiteKey", formGuard.captchaSiteKey());
        return ResponseEntity.ok(m);
    }

    @PostMapping("/api/subscription-request")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String ip = PublicFormGuard.clientIp(req);
        if (formGuard.isHoneypotTripped(body)) {
            log.warn("Subscription request honeypot tripped — ignored. ip={} email='{}'", ip, str(body.get("registeredEmail")));
            return ResponseEntity.ok(Map.of("status", "success"));
        }
        String rate = formGuard.checkRate(ip, FORM);
        if (rate != null) return ResponseEntity.status(429).body(error(rate));
        Optional<ServiceClient> sc = church(str(body.get("t")), req);
        if (sc.isEmpty()) return ResponseEntity.status(403).body(error(refusal(str(body.get("t")))));
        String tokenError = formGuard.checkToken(str(body.get("formToken")), null);
        if (tokenError != null) return ResponseEntity.badRequest().body(error(tokenError));
        String captchaError = formGuard.checkCaptcha(str(body.get("captchaToken")), ip);
        if (captchaError != null) return ResponseEntity.badRequest().body(error(captchaError));

        SubscriptionRequestService.Form f = new SubscriptionRequestService.Form(
                str(body.get("firstName")), str(body.get("lastName")), str(body.get("registeredEmail")),
                str(body.get("phone")), str(body.get("planCode")), str(body.get("billingFrequency")), str(body.get("note")));
        String fieldError = SubscriptionRequestService.validate(f);
        if (fieldError == null) fieldError = formGuard.checkEmail(f.registeredEmail());
        if (fieldError == null) fieldError = formGuard.checkPhone(f.phone());
        if (fieldError == null) fieldError = formGuard.checkText(f.note(), 1000, 0);
        if (fieldError != null) return ResponseEntity.badRequest().body(error(fieldError));

        try {
            SubscriptionRequest r = requests.submit(sc.get(), f, ip);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "success");
            m.put("message", Boolean.TRUE.equals(r.getRegisterAsNewClient())
                    ? "Thank you — your request has been sent. Because this trial contains sample data, "
                      + "we will set up a new account for the " + r.getPlanName() + " plan and contact you shortly."
                    : "Thank you — your request for the " + r.getPlanName() + " plan has been sent. "
                      + "We will contact you shortly with the next steps.");
            return ResponseEntity.ok(m);
        } catch (SubscriptionRequestService.DuplicateRequestException e) {
            return ResponseEntity.status(409).body(error(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            log.error("Subscription request submit failed", e);
            return ResponseEntity.status(500).body(error("We could not send your request. Please try again."));
        }
    }

    private static Map<String, Object> error(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("message", msg);
        return m;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
