package com.churchgeniuspro.service;

import com.churchgeniuspro.util.LogSafe;
import com.churchgeniuspro.util.PhoneMask;
import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import com.churchgeniuspro.util.PhoneNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around the Twilio SDK for sending outbound SMS messages.
 *
 * <p>Usage rules:
 * <ul>
 *   <li>Always check {@link com.churchgeniuspro.repository.SmsOptInRepository#isOptedIn}
 *       before calling {@link #send}. Opt-in records are keyed by the E.164 number, so
 *       look them up with {@link com.churchgeniuspro.util.PhoneNumbers#toE164} applied —
 *       an exact-string lookup on a raw number silently matches nothing, which reads as
 *       "not opted in" and quietly suppresses every message.</li>
 *   <li>Prefer {@code WhatsAppSenderService.sendSingleSms} over calling {@link #send}
 *       directly: it applies the subscription plan's monthly SMS allowance. Calling this
 *       method straight bypasses that quota.</li>
 *   <li>Every outbound message must include church identity and opt-out instructions
 *       (TCPA / Twilio AUP compliance).</li>
 * </ul>
 *
 * <p>Example compliant message body:
 * <pre>{@code
 *   churchName + ": Service starts at 10 AM. Reply STOP to opt out."
 * }</pre>
 */
@Service
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);

    private final String fromNumber;
    private final boolean configured;

    public SmsService(
            @Value("${twilio.account-sid:}") String accountSid,
            @Value("${twilio.auth-token:}")  String authToken,
            @Value("${twilio.phone-number:}") String fromNumber) {

        this.fromNumber = fromNumber;

        boolean ok = !accountSid.isBlank() && !authToken.isBlank()
                  && !fromNumber.isBlank()
                  && !accountSid.startsWith("YOUR_");
        this.configured = ok;

        if (ok) {
            try {
                Twilio.init(accountSid, authToken);
                log.info("SmsService: Twilio initialized (from={})", fromNumber);
            } catch (Exception e) {
                log.error("SmsService: Twilio init failed — {}", LogSafe.exception(e));
            }
        } else {
            log.warn("SmsService: Twilio credentials not configured — SMS disabled. " +
                     "Set twilio.account-sid, twilio.auth-token, twilio.phone-number in application.properties.");
        }
    }

    /**
     * Sends an SMS message.
     *
     * <p>The recipient is normalised to E.164 here rather than being assumed to arrive
     * that way. Twilio rejects anything else with error 21211, and because this method
     * catches and logs its own failures, an unnormalised number produced a message that
     * was never delivered and never complained — the quietest possible way to lose a
     * notification. Normalising at this boundary means every caller is covered, including
     * ones written later that forget.
     *
     * @param toNumber  recipient number; E.164 preferred, national formats accepted
     * @param body      Message text. Must include church name + "Reply STOP to opt out."
     * @return {@code true} if the message was accepted by Twilio, {@code false} otherwise.
     */
    public boolean send(String toNumber, String body) {
        return sendWithOutcome(toNumber, body).sent();
    }

    /**
     * The outcome of one send attempt, including <em>why</em> it failed.
     *
     * <p>{@link #send} collapses this to a boolean, which is what made
     * under-delivery impossible to diagnose: a number blocked by the carrier, a
     * recipient who had replied STOP, and an unregistered A2P campaign all
     * looked identical to the caller — {@code false} — and the distinguishing
     * detail existed only in a log line nobody correlated with a specific
     * reminder run.
     *
     * @param sent      whether the provider accepted the message
     * @param errorCode the provider's numeric error code, or {@code null}.
     *                  The ones worth recognising:
     *                  <b>21211</b> invalid "To" number,
     *                  <b>21610</b> the recipient replied STOP and is blocked,
     *                  <b>21612</b> not reachable from this sending number,
     *                  <b>30034</b> the sending number is not registered for
     *                  A2P 10DLC — US carriers drop this traffic wholesale,
     *                  which looks exactly like "only a couple of people got it".
     * @param reason    short human-readable explanation, {@code null} on success
     */
    public record SendOutcome(boolean sent, Integer errorCode, String reason) {
        public static SendOutcome ok()                 { return new SendOutcome(true, null, null); }
        public static SendOutcome fail(String reason)  { return new SendOutcome(false, null, reason); }
        static SendOutcome fail(Integer code, String reason) { return new SendOutcome(false, code, reason); }
    }

    /**
     * Sends an SMS and reports the outcome, keeping the provider's error code.
     *
     * <p>Prefer this over {@link #send} anywhere the result is recorded or shown
     * to an administrator. {@link #send} remains for callers that genuinely only
     * need "did it go".
     */
    /**
     * Demo-tenant send guard. Field injection keeps every existing construction
     * site of this service unchanged; null-checked at use.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DemoAccessService demoAccess;

    /**
     * The one authority on whether a tenant may send at all (Trial subscription,
     * demo tenant). Field-injected for the same reason as {@code demoAccess}:
     * every existing construction site of this service stays untouched.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MessagingPolicy messagingPolicy;

    /** Test seam — supply the policy without a Spring context. */
    public void setMessagingPolicy(MessagingPolicy p) { this.messagingPolicy = p; }

    /**
     * The subscription plan's monthly SMS allowance, applied here for the same
     * reason the tenant block is: this is the one method that hands a message to
     * Twilio, so a caller cannot route around the plan by picking a different
     * overload. It used to live in {@code WhatsAppSenderService}, which only some
     * senders go through — pickup alerts, volunteer and kids-ministry broadcasts,
     * event RSVP confirmations and the opt-in reply all called
     * {@link #sendForClient} directly and were never counted.
     *
     * <p>Optional and null-checked so the existing construction sites (and their
     * unit tests) are unchanged; absent, sends are unmetered exactly as before.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SubscriptionService subscriptionService;

    /** Test seam — supply the plan without a Spring context. */
    public void setSubscriptionService(SubscriptionService s) { this.subscriptionService = s; }

    /**
     * Consent and message-identity rules (toll-free verification / TCPA, 2026-10-19).
     * Field-injected and null-checked like the guards above, so existing construction
     * sites and their unit tests are unchanged.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.repository.SmsOptInRepository optInRepo;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.repository.EmailSettingsRepository emailSettingsRepo;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.churchgeniuspro.repository.ChurchRegistrationRepository churchRepo;

    /**
     * When true, a church's texts go only to numbers that confirmed SMS opt-in for that
     * church (signed up and replied YES). The opt-in confirmation request itself is the one
     * exception — it is how a number confirms. OFF by default (property
     * {@code sms.require-opt-in}, env {@code SMS_REQUIRE_OPT_IN}) so deploying this changes
     * nothing until production moves to the verified toll-free number.
     */
    @Value("${sms.require-opt-in:false}")
    private boolean requireOptIn = false;

    /**
     * When true, every text gets the sender's name prefixed and "Reply STOP to opt out."
     * appended when missing ({@link #withIdentityAndOptOut}). OFF by default (property
     * {@code sms.add-sender-identity}, env {@code SMS_ADD_SENDER_IDENTITY}): message text is
     * then exactly what the caller built, as before.
     */
    @Value("${sms.add-sender-identity:false}")
    private boolean addSenderIdentity = false;

    /** Test seams. */
    public void setOptInRepo(com.churchgeniuspro.repository.SmsOptInRepository r) { this.optInRepo = r; }
    public void setEmailSettingsRepo(com.churchgeniuspro.repository.EmailSettingsRepository r) { this.emailSettingsRepo = r; }
    public void setChurchRepo(com.churchgeniuspro.repository.ChurchRegistrationRepository r) { this.churchRepo = r; }
    public void setRequireOptIn(boolean b) { this.requireOptIn = b; }
    public void setAddSenderIdentity(boolean b) { this.addSenderIdentity = b; }

    /** The reason a send is refused when the recipient has not opted in. */
    public static final String NOT_OPTED_IN_MSG =
            "Recipient has not opted in to texts from this church (they must sign up and reply YES)";

    /** The reason a send is refused once the plan's monthly allowance is spent. */
    public static final String ALLOWANCE_SPENT_MSG =
            "Monthly SMS allowance for this subscription plan is used up";

    /**
     * Sends on behalf of a tenant, refusing when that tenant is a demo/test
     * client with SMS delivery switched off.
     *
     * <p>Prefer this over {@link #send} anywhere a tenant is known: it is the
     * only thing standing between generated demo data and a real phone.
     */
    public SendOutcome sendForClient(String clientId, String toNumber, String body) {
        return doSend(toNumber, body, fromNumber, clientId, false);
    }

    /**
     * Sends a confirmation request on behalf of a known tenant, so the opt-in SMS
     * is subject to the same block as everything else that church sends.
     */
    public boolean sendConfirmationRequest(String toNumber, String churchName, String clientId) {
        return sendConfirmationRequestWithOutcome(toNumber, churchName, clientId).sent();
    }

    /** {@link #sendConfirmationRequest} keeping the provider's code (opt-in page tells the truth — audit 2026-10-28, M7). */
    public SendOutcome sendConfirmationRequestWithOutcome(String toNumber, String churchName, String clientId) {
        return doSend(toNumber, confirmationBody(churchName), fromNumber, clientId, true);
    }

    /** The Twilio number this service sends from — shown as the default in admin tools. */
    public String getFromNumber() { return fromNumber; }

    /**
     * Sends from a caller-supplied number instead of the configured one.
     *
     * <p>The override must still be a number the existing Twilio account owns;
     * Twilio rejects anything else, and that rejection is surfaced verbatim
     * rather than being retried from the default number — silently sending from
     * a different number than the admin chose would be worse than failing.
     */
    public SendOutcome sendWithOutcome(String toNumber, String body, String fromOverride) {
        if (fromOverride == null || fromOverride.isBlank()) {
            return sendWithOutcome(toNumber, body);
        }
        String from = PhoneNumbers.toE164(fromOverride);
        if (from == null) {
            return SendOutcome.fail("Not a usable 'from' number: " + fromOverride);
        }
        return doSend(toNumber, body, from, null, false);
    }

    public SendOutcome sendWithOutcome(String toNumber, String body) {
        return doSend(toNumber, body, fromNumber, null, false);
    }

    /**
     * The single point at which this service talks to Twilio.
     *
     * <p>The tenant block is enforced HERE rather than in the public methods, so a
     * caller cannot route around it by picking a different overload. {@code clientId}
     * may be null for genuinely tenant-less sends (Service Admin broadcasts, pre-login
     * codes); every caller that knows its tenant is expected to pass it.
     */
    private SendOutcome doSend(String toNumber, String body, String sendFrom, String clientId, boolean consentRequest) {
        if (messagingPolicy != null) {
            String blocked = messagingPolicy.smsBlockReason(clientId);
            if (blocked != null) {
                log.info("SmsService: blocked SMS to {} for client {} — {}", PhoneMask.mask(toNumber), clientId, blocked);
                return SendOutcome.fail(blocked);
            }
        } else if (demoAccess != null && clientId != null && !demoAccess.sendingAllowed(clientId, true)) {
            log.info("SmsService: blocked SMS to {} — demo/test client {} has SMS disabled", PhoneMask.mask(toNumber), clientId);
            return SendOutcome.fail("SMS sending is disabled for this demo/test account.");
        }
        boolean metered = subscriptionService != null && clientId != null && !clientId.isBlank();
        if (metered && !subscriptionService.canSendSms(clientId)) {
            log.warn("SmsService: skipping send to {} — monthly SMS allowance spent (clientId={})",
                     PhoneMask.mask(toNumber), clientId);
            return SendOutcome.fail(ALLOWANCE_SPENT_MSG);
        }

        if (!configured) {
            log.warn("SmsService: skipping send to {} — Twilio not configured.", PhoneMask.mask(toNumber));
            return SendOutcome.fail("SMS provider not configured");
        }

        String e164 = PhoneNumbers.toE164(toNumber);
        if (e164 == null) {
            // Refused deliberately: a number we cannot resolve is not one we should guess
            // at. Logged at WARN because this is a data problem someone has to fix at the
            // source, not a transient send failure.
            log.warn("SmsService: skipping send — '{}' is not a usable phone number.", PhoneMask.mask(toNumber));
            return SendOutcome.fail("Not a usable phone number: " + toNumber);
        }

        boolean tenant = clientId != null && !clientId.isBlank();
        if (tenant && !consentRequest && requireOptIn && optInRepo != null && !optInRepo.isOptedIn(e164, clientId)) {
            log.info("SmsService: skipping send to {} for client {} — no confirmed SMS opt-in", PhoneMask.mask(e164), clientId);
            return SendOutcome.fail(NOT_OPTED_IN_MSG);
        }
        if (addSenderIdentity) {
            body = withIdentityAndOptOut(body, tenant ? senderName(clientId) : "ChurchGeniusPro");
        }

        try {
            Message msg = Message.creator(
                    new PhoneNumber(e164),
                    new PhoneNumber(sendFrom),
                    body
            ).create();
            log.info("SmsService: sent SID={} to={}", msg.getSid(), PhoneMask.mask(e164));
            if (metered) subscriptionService.recordSmsSent(clientId);
            return SendOutcome.ok();
        } catch (com.twilio.exception.ApiException e) {
            // Keep the code: it is the difference between "fix this number",
            // "this person opted out", and "your whole campaign is unregistered".
            Integer code = e.getCode();
            log.error("SmsService: failed to send to {} — Twilio {}: {}", PhoneMask.mask(e164), code, LogSafe.exception(e));
            return SendOutcome.fail(code, describe(code, e.getMessage()));
        } catch (Exception e) {
            log.error("SmsService: failed to send to {} — {}", PhoneMask.mask(e164), LogSafe.exception(e));
            return SendOutcome.fail(e.getMessage());
        }
    }

    /**
     * Every text must say who it is from and how to stop (carrier rules, TCPA). Prefixes
     * "{name}: " unless the body already starts with the name, and appends
     * "Reply STOP to opt out." unless the body already mentions STOP. Messages that
     * already comply (reminders, RSVP confirmations, the opt-in request) are unchanged.
     */
    static String withIdentityAndOptOut(String body, String name) {
        String b = body == null ? "" : body.trim();
        if (name != null && !name.isBlank()) {
            String n = name.trim();
            if (!b.regionMatches(true, 0, n, 0, n.length())) b = n + ": " + b;
        }
        if (!b.toUpperCase(java.util.Locale.ROOT).contains("STOP")) {
            b = b + (b.endsWith(".") || b.endsWith("!") || b.endsWith("?") ? " " : ". ") + "Reply STOP to opt out.";
        }
        return b;
    }

    /** The name a church's texts carry: the email display name, else the registered church name. */
    private String senderName(String clientId) {
        try {
            if (emailSettingsRepo != null) {
                String d = emailSettingsRepo.findByClientId(clientId)
                        .map(com.churchgeniuspro.hibernate.EmailSettings::getDisplayName)
                        .filter(n -> n != null && !n.isBlank()).orElse(null);
                if (d != null) return d.trim();
            }
            if (churchRepo != null) {
                return churchRepo.findByClientIdAndDeleteFlagFalse(clientId)
                        .map(com.churchgeniuspro.hibernate.ChurchRegistration::getChurchName)
                        .filter(n -> n != null && !n.isBlank()).map(String::trim).orElse(null);
            }
        } catch (Exception e) {
            log.warn("SmsService: could not resolve sender name for client {}: {}", clientId, e.getClass().getSimpleName());
        }
        return null;
    }

    /**
     * Turns a Twilio error code into something an administrator can act on.
     * Unknown codes keep the provider's own wording rather than being flattened
     * into a generic failure.
     */
    private static String describe(Integer code, String providerMessage) {
        if (code == null) return providerMessage;
        return switch (code) {
            case 21211 -> "Twilio 21211: the number is not a valid destination";
            case 21408 -> "Twilio 21408: this account cannot send to that region";
            case 21610 -> "Twilio 21610: recipient replied STOP and is blocked by the carrier";
            case 21612 -> "Twilio 21612: not reachable from the sending number";
            case 21614 -> "Twilio 21614: the number cannot receive SMS (landline or VoIP)";
            case 30003 -> "Twilio 30003: handset unreachable or powered off";
            case 30004 -> "Twilio 30004: message blocked by the carrier";
            case 30006 -> "Twilio 30006: landline or unreachable carrier";
            case 30007 -> "Twilio 30007: carrier filtered this message as spam";
            case 30034 -> "Twilio 30034: sending number is not registered for A2P 10DLC — "
                        + "US carriers block this traffic until the campaign is registered";
            default    -> "Twilio " + code + ": " + providerMessage;
        };
    }

    /**
     * Sends the double-opt-in confirmation request.
     * Called immediately after the user submits the opt-in form.
     */
    public boolean sendConfirmationRequest(String toNumber, String churchName) {
        return sendConfirmationRequest(toNumber, churchName, null);
    }

    /** The opt-in confirmation wording, shared by both overloads. */
    private static String confirmationBody(String churchName) {
        return churchName + ": You have requested to receive SMS messages from us. "
             + "Reply YES to confirm. Reply STOP to cancel. Msg & data rates may apply.";
    }

    /** Returns true if Twilio credentials are configured and the service is active. */
    public boolean isConfigured() { return configured; }
}
