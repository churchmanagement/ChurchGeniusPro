package com.churchgeniuspro.controller;

import com.churchgeniuspro.hibernate.TrialRequest;
import com.churchgeniuspro.service.TrialRequestService;
import com.churchgeniuspro.util.PublicFormGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The public Trial Request page (token-gated by {@code TrialRequestLinkFilter}).
 *
 * <p>Anonymous by design — the requester has no account — so it runs the same
 * anti-bot stack as the Trial Registration form through {@link PublicFormGuard}:
 * honeypot, per-IP rate limit, time-trap token and (when configured) reCAPTCHA. Every
 * POST also re-checks the link token, so posting straight to the API is no easier
 * than opening the page, and revoking the link closes the API too.
 */
@Controller
public class TrialRequestController {

    private static final Logger log = LoggerFactory.getLogger(TrialRequestController.class);

    /** Rate-limit buckets, separate from the other public forms. */
    private static final String FORM_SUBMIT = "trial-request";
    private static final String FORM_VERIFY = "trial-request-verify";

    private final TrialRequestService requests;
    private final PublicFormGuard formGuard;

    public TrialRequestController(TrialRequestService requests, PublicFormGuard formGuard) {
        this.requests = requests;
        this.formGuard = formGuard;
    }

    @GetMapping("/trialRequest")
    public String page() {
        return "forward:/trialRequest.html";
    }

    /** Time-trap token, captcha key, and the configured trial length for the page's wording. */
    @ResponseBody
    @GetMapping("/api/trial-request/form-info")
    public ResponseEntity<Map<String, Object>> formInfo(@RequestParam(required = false) String k) {
        if (!requests.isLinkToken(k)) return ResponseEntity.status(403).body(error(LINK_INVALID));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "success");
        m.put("formToken", formGuard.issueToken(null));
        if (formGuard.captchaSiteKey() != null) m.put("captchaSiteKey", formGuard.captchaSiteKey());
        m.put("trialDays", requests.trialDays());
        return ResponseEntity.ok(m);
    }

    @ResponseBody
    @PostMapping("/api/trial-request")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String ip = PublicFormGuard.clientIp(req);

        // Honeypot: answer like a success so a bot learns nothing; nothing is stored or sent.
        if (formGuard.isHoneypotTripped(body)) {
            log.warn("Trial request honeypot tripped — ignored. ip={} email='{}' church='{}'",
                     ip, str(body.get("email")), str(body.get("churchName")));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "success");
            m.put("reference", "TRQ-" + Long.toString(System.nanoTime() & 0xFFFFFFFFL, 36).toUpperCase());
            m.put("email", str(body.get("email")));
            return ResponseEntity.ok(m);
        }
        String rate = formGuard.checkRate(ip, FORM_SUBMIT);
        if (rate != null) return ResponseEntity.status(429).body(error(rate));
        if (!requests.isLinkToken(str(body.get("k")))) return ResponseEntity.status(403).body(error(LINK_INVALID));
        String tokenError = formGuard.checkToken(str(body.get("formToken")), null);
        if (tokenError != null) return ResponseEntity.badRequest().body(error(tokenError));
        String captchaError = formGuard.checkCaptcha(str(body.get("captchaToken")), ip);
        if (captchaError != null) return ResponseEntity.badRequest().body(error(captchaError));

        TrialRequestService.Form f = new TrialRequestService.Form(
                str(body.get("firstName")), str(body.get("lastName")), str(body.get("churchName")),
                str(body.get("email")), str(body.get("phone")), str(body.get("designation")), str(body.get("note")));
        String fieldError = firstNonNull(TrialRequestService.validate(f),
                formGuard.checkEmail(f.email()), formGuard.checkPhone(f.phone()),
                formGuard.checkText(f.note(), 1000, 0), formGuard.checkText(f.churchName(), 200, 0),
                formGuard.checkText(f.designation(), 100, 0));
        if (fieldError != null) return ResponseEntity.badRequest().body(error(fieldError));

        try {
            TrialRequest r = requests.submit(f, ip);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "success");
            m.put("reference", r.getReference());
            m.put("email", r.getEmail());
            m.put("message", "We sent a 6-digit verification code to " + r.getEmail() + ".");
            return ResponseEntity.ok(m);
        } catch (TrialRequestService.DuplicateRequestException e) {
            // One open request per email (server-side rule). While the earlier request
            // still awaits its code, hand back its reference so the page resumes code
            // entry instead of starting another request.
            Map<String, Object> m = error(e.getMessage());
            m.put("code", e.reference() != null ? "AWAITING_VERIFICATION" : "REQUEST_PENDING");
            if (e.reference() != null) {
                m.put("reference", e.reference());
                m.put("email", f.email().trim().toLowerCase());
            }
            return ResponseEntity.status(409).body(m);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(error(e.getMessage()));
        } catch (Exception e) {
            log.error("Trial request submit failed", e);
            return ResponseEntity.status(500).body(error("We could not submit your request. Please try again."));
        }
    }

    @ResponseBody
    @PostMapping("/api/trial-request/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String ip = PublicFormGuard.clientIp(req);
        String rate = formGuard.checkRate(ip, FORM_VERIFY);
        if (rate != null) return ResponseEntity.status(429).body(error(rate));
        if (!requests.isLinkToken(str(body.get("k")))) return ResponseEntity.status(403).body(error(LINK_INVALID));
        try {
            requests.verify(str(body.get("reference")), str(body.get("code")));
            return ResponseEntity.ok(Map.of("status", "success", "message",
                    "Thank you — your email is verified and your trial request has been submitted. "
                  + "We will review it and email you as soon as it is approved."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (Exception e) {
            log.error("Trial request verify failed", e);
            return ResponseEntity.status(500).body(error("We could not verify your code. Please try again."));
        }
    }

    @ResponseBody
    @PostMapping("/api/trial-request/resend")
    public ResponseEntity<Map<String, Object>> resend(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String ip = PublicFormGuard.clientIp(req);
        String rate = formGuard.checkRate(ip, FORM_VERIFY);
        if (rate != null) return ResponseEntity.status(429).body(error(rate));
        if (!requests.isLinkToken(str(body.get("k")))) return ResponseEntity.status(403).body(error(LINK_INVALID));
        try {
            requests.resend(str(body.get("reference")));
            return ResponseEntity.ok(Map.of("status", "success", "message", "A new code is on its way."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(error(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(502).body(error(e.getMessage()));
        }
    }

    private static final String LINK_INVALID =
            "This Trial Request link is no longer active. Please contact us for a new link.";

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

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) if (v != null) return v;
        return null;
    }
}
