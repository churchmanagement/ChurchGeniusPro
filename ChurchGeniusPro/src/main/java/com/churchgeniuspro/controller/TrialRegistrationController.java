package com.churchgeniuspro.controller;

import com.churchgeniuspro.model.TrialRegistrationBO;
import com.churchgeniuspro.service.TrialRegistrationLinkService;
import com.churchgeniuspro.service.TrialRegistrationService;
import com.churchgeniuspro.util.PublicFormGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The public self-service trial signup: one page, one POST, a fully provisioned
 * trial church.
 *
 * <p>Unauthenticated by necessity — the person filling it in has no account yet,
 * which is the point. That makes it the only endpoint in the application where an
 * anonymous request creates a tenant AND sends mail, so it runs the same anti-bot
 * stack the other public forms use, through {@link PublicFormGuard}: honeypot,
 * time-trap token, per-IP rate limit, and reCAPTCHA when a secret is configured.
 *
 * <p>The captcha layer is deliberately dormant until it is needed. It switches on
 * for every public form the moment {@code public.forms.recaptcha.secret} is set —
 * no redeploy, no code change — so it can be turned on the day trial signup is
 * actually abused rather than taxing every legitimate registration until then.
 * The other three layers run always and cost a human nothing.
 */
@RestController
public class TrialRegistrationController {

    private static final Logger log = LoggerFactory.getLogger(TrialRegistrationController.class);

    /** Rate-limit bucket name, kept separate from the other public forms. */
    private static final String FORM = "trial-registration";

    private final TrialRegistrationService trialService;
    private final PublicFormGuard formGuard;
    private final TrialRegistrationLinkService links;

    public TrialRegistrationController(TrialRegistrationService trialService,
                                       PublicFormGuard formGuard,
                                       TrialRegistrationLinkService links) {
        this.trialService = trialService;
        this.formGuard    = formGuard;
        this.links        = links;
    }

    /** Forwards the browser to the static trial-registration page. */
    @GetMapping("/trialRegistration")
    public String page() {
        return "forward:/trialRegistration.html";
    }

    /**
     * What the page needs before it can submit: the time-trap token, and the
     * captcha site key when a captcha is configured.
     *
     * <p>The token is issued here rather than baked into the page so its age is
     * measured from when the form was actually loaded.
     */
    @ResponseBody
    @GetMapping("/api/trial-registration/form-info")
    public ResponseEntity<Map<String, Object>> formInfo(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "success");
        m.put("formToken", formGuard.issueToken(null));
        if (formGuard.captchaSiteKey() != null) m.put("captchaSiteKey", formGuard.captchaSiteKey());
        // The trial length THIS link grants (captured on the link when it was issued),
        // so the page can say how long the trial runs. Read-only: validate, never claim.
        if (id != null && !id.isBlank()) {
            TrialRegistrationLinkService.Validation v = links.validate(id);
            if (v.valid()) m.put("trialDays", TrialRegistrationLinkService.trialDaysFor(v.link()));
        }
        return ResponseEntity.ok(m);
    }

    @ResponseBody
    @PostMapping("/api/trial-registration")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> body,
                                                        HttpServletRequest req) {
        String ip = PublicFormGuard.clientIp(req);

        // 1. Honeypot. Answer as though it worked: a bot that is told "rejected"
        //    learns to stop filling the field, and nothing was created either way.
        if (formGuard.isHoneypotTripped(body)) {
            // WARN with the identifying fields, not INFO with only an IP. A false
            // positive here silently discards a real signup while telling the
            // person it worked, so the log has to be enough to find that person
            // and finish their registration by hand. Learned from one: a browser
            // was autofilling the honeypot, and nothing in the logs said who.
            log.warn("Trial registration honeypot tripped — no tenant created. "
                   + "ip={} email='{}' church='{}'",
                     ip, str(body.get("email")), str(body.get("churchName")));
            return ResponseEntity.ok(fakeSuccess());
        }

        // 2. Per-IP rate limit — the same sliding window the other public forms use.
        String rate = formGuard.checkRate(ip, FORM);
        if (rate != null) {
            log.warn("Trial registration rate-limited for ip={}", ip);
            return ResponseEntity.status(429).body(error(rate));
        }

        // 3. The invitation token. Re-checked here and not merely at the page, so
        //    posting straight to this endpoint is no easier than opening the form:
        //    the filter guards the door, this guards the action. Re-validated
        //    rather than trusted, because a form sits open while it is filled in
        //    and the link can expire or be revoked in the meantime.
        String inviteToken = str(body.get("inviteToken"));
        TrialRegistrationLinkService.Validation invite = links.validate(inviteToken);
        if (!invite.valid()) {
            log.warn("Trial registration refused — invitation token {} (ip={})", invite.outcome(), ip);
            return ResponseEntity.status(403).body(error(invite.message()));
        }

        // 4. Time-trap: the page must have been open for a few seconds, and not
        //    for hours. Instant posts and replayed stale tokens both fail.
        String tokenError = formGuard.checkToken(str(body.get("formToken")), null);
        if (tokenError != null) return ResponseEntity.badRequest().body(error(tokenError));

        // 5. Captcha, when one is configured. A no-op otherwise.
        String captchaError = formGuard.checkCaptcha(str(body.get("captchaToken")), ip);
        if (captchaError != null) return ResponseEntity.badRequest().body(error(captchaError));

        TrialRegistrationBO bo = toBo(body);

        // 6. Shared field validation, so this form rejects the same shapes the
        //    other public forms do rather than inventing its own rules.
        String fieldError = firstNonNull(
                formGuard.checkEmail(bo.getEmail()),
                formGuard.checkPhone(bo.getPhone()),
                formGuard.checkText(bo.getNote(), 1000, 0),
                formGuard.checkText(bo.getChurchName(), 200, 0));
        if (fieldError != null) return ResponseEntity.badRequest().body(error(fieldError));

        // 7. Take the invitation BEFORE provisioning, atomically. Every check above
        //    is read-only; this is the one exclusive step, and it is the reason a
        //    token creates one tenant however many POSTs carry it at once. A
        //    registration that fails past this point hands the link back (release),
        //    so the prospect is not left with a spent link and nothing to show for it.
        TrialRegistrationLinkService.Claim claim = links.claim(inviteToken);
        if (!claim.valid()) {
            log.warn("Trial registration refused at claim — invitation token {} (ip={})",
                     claim.validation().outcome(), ip);
            return ResponseEntity.status(403).body(error(claim.validation().message()));
        }

        // The trial length is the one the Service Admin chose when issuing this link.
        bo.setTrialDays(TrialRegistrationLinkService.trialDaysFor(claim.link()));

        try {
            Map<String, Object> result = trialService.register(bo);

            // The tenant exists; record it against the claim we already hold.
            links.complete(claim, String.valueOf(result.get("clientId")));

            Map<String, Object> out = new LinkedHashMap<>(result);
            out.put("status", "success");
            out.put("message", Boolean.TRUE.equals(result.get("invited"))
                    ? "Your trial church has been created. Check your email for the link to finish setting up your account."
                    : "Your trial church has been created, but we could not send the setup email. "
                      + "Please contact support and we will resend it.");
            return ResponseEntity.ok(out);

        } catch (IllegalArgumentException e) {
            // Form problems — the message is written for the person filling it in.
            releaseQuietly(claim);
            return ResponseEntity.badRequest().body(error(e.getMessage()));

        } catch (IllegalStateException e) {
            // Configuration problems (e.g. no active TRIAL plan). Already logged
            // with detail by the service; the visitor gets the safe version.
            releaseQuietly(claim);
            return ResponseEntity.status(503).body(error(e.getMessage()));

        } catch (Exception e) {
            log.error("Trial registration failed", e);
            releaseQuietly(claim);
            return ResponseEntity.status(500).body(error(
                    "We could not complete your registration. Please try again or contact support."));
        }
    }

    /** Gives a claimed link back after a failed registration; never lets that itself fail the response. */
    private void releaseQuietly(TrialRegistrationLinkService.Claim claim) {
        try { links.release(claim); }
        catch (Exception e) { log.error("Could not release trial registration link after a failed registration", e); }
    }

    /* ── helpers ────────────────────────────────────────────────────────── */

    private static TrialRegistrationBO toBo(Map<String, Object> body) {
        TrialRegistrationBO bo = new TrialRegistrationBO();
        bo.setChurchName(str(body.get("churchName")));
        bo.setFirstName(str(body.get("firstName")));
        bo.setLastName(str(body.get("lastName")));
        bo.setPhone(str(body.get("phone")));
        bo.setEmail(str(body.get("email")));
        bo.setAddressLine1(str(body.get("addressLine1")));
        bo.setAddressLine2(str(body.get("addressLine2")));
        bo.setCity(str(body.get("city")));
        bo.setState(str(body.get("state")));
        bo.setCountry(str(body.get("country")));
        bo.setPinCode(str(body.get("pinCode")));
        bo.setNote(str(body.get("note")));
        bo.setAccountType(str(body.get("accountType")));
        return bo;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) if (v != null) return v;
        return null;
    }

    /**
     * The response a tripped honeypot gets: shaped like a success, backed by
     * nothing. No clientId is invented — a bot has no use for one, and a real
     * user never sees this path.
     */
    private static Map<String, Object> fakeSuccess() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "success");
        m.put("invited", true);
        m.put("message", "Your trial church has been created. Check your email for the link "
                       + "to finish setting up your account.");
        return m;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "error");
        m.put("message", message);
        return m;
    }
}
