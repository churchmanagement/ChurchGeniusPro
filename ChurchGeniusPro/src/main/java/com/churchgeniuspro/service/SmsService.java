package com.churchgeniuspro.service;

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
                log.error("SmsService: Twilio init failed — {}", e.getMessage());
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
        return doSend(toNumber, body, fromNumber, clientId);
    }

    /**
     * Sends a confirmation request on behalf of a known tenant, so the opt-in SMS
     * is subject to the same block as everything else that church sends.
     */
    public boolean sendConfirmationRequest(String toNumber, String churchName, String clientId) {
        return doSend(toNumber, confirmationBody(churchName), fromNumber, clientId).sent();
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
        return doSend(toNumber, body, from, null);
    }

    public SendOutcome sendWithOutcome(String toNumber, String body) {
        return doSend(toNumber, body, fromNumber, null);
    }

    /**
     * The single point at which this service talks to Twilio.
     *
     * <p>The tenant block is enforced HERE rather than in the public methods, so a
     * caller cannot route around it by picking a different overload. {@code clientId}
     * may be null for genuinely tenant-less sends (Service Admin broadcasts, pre-login
     * codes); every caller that knows its tenant is expected to pass it.
     */
    private SendOutcome doSend(String toNumber, String body, String sendFrom, String clientId) {
        if (messagingPolicy != null) {
            String blocked = messagingPolicy.smsBlockReason(clientId);
            if (blocked != null) {
                log.info("SmsService: blocked SMS to {} for client {} — {}", toNumber, clientId, blocked);
                return SendOutcome.fail(blocked);
            }
        } else if (demoAccess != null && clientId != null && !demoAccess.sendingAllowed(clientId, true)) {
            log.info("SmsService: blocked SMS to {} — demo/test client {} has SMS disabled", toNumber, clientId);
            return SendOutcome.fail("SMS sending is disabled for this demo/test account.");
        }
        boolean metered = subscriptionService != null && clientId != null && !clientId.isBlank();
        if (metered && !subscriptionService.canSendSms(clientId)) {
            log.warn("SmsService: skipping send to {} — monthly SMS allowance spent (clientId={})",
                     toNumber, clientId);
            return SendOutcome.fail(ALLOWANCE_SPENT_MSG);
        }

        if (!configured) {
            log.warn("SmsService: skipping send to {} — Twilio not configured.", toNumber);
            return SendOutcome.fail("SMS provider not configured");
        }

        String e164 = PhoneNumbers.toE164(toNumber);
        if (e164 == null) {
            // Refused deliberately: a number we cannot resolve is not one we should guess
            // at. Logged at WARN because this is a data problem someone has to fix at the
            // source, not a transient send failure.
            log.warn("SmsService: skipping send — '{}' is not a usable phone number.", toNumber);
            return SendOutcome.fail("Not a usable phone number: " + toNumber);
        }

        try {
            Message msg = Message.creator(
                    new PhoneNumber(e164),
                    new PhoneNumber(sendFrom),
                    body
            ).create();
            log.info("SmsService: sent SID={} to={}", msg.getSid(), e164);
            if (metered) subscriptionService.recordSmsSent(clientId);
            return SendOutcome.ok();
        } catch (com.twilio.exception.ApiException e) {
            // Keep the code: it is the difference between "fix this number",
            // "this person opted out", and "your whole campaign is unregistered".
            Integer code = e.getCode();
            log.error("SmsService: failed to send to {} — Twilio {}: {}", e164, code, e.getMessage());
            return SendOutcome.fail(code, describe(code, e.getMessage()));
        } catch (Exception e) {
            log.error("SmsService: failed to send to {} — {}", e164, e.getMessage());
            return SendOutcome.fail(e.getMessage());
        }
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
