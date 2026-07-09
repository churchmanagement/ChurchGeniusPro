package com.churchgeniuspro.service;

import com.twilio.Twilio;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
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
 *       before calling {@link #send}.</li>
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
     * @param toNumber  E.164 recipient number, e.g. {@code +12345678901}
     * @param body      Message text. Must include church name + "Reply STOP to opt out."
     * @return {@code true} if the message was accepted by Twilio, {@code false} otherwise.
     */
    public boolean send(String toNumber, String body) {
        if (!configured) {
            log.warn("SmsService: skipping send to {} — Twilio not configured.", toNumber);
            return false;
        }
        try {
            Message msg = Message.creator(
                    new PhoneNumber(toNumber),
                    new PhoneNumber(fromNumber),
                    body
            ).create();
            log.info("SmsService: sent SID={} to={}", msg.getSid(), toNumber);
            return true;
        } catch (Exception e) {
            log.error("SmsService: failed to send to {} — {}", toNumber, e.getMessage());
            return false;
        }
    }

    /**
     * Sends the double-opt-in confirmation request.
     * Called immediately after the user submits the opt-in form.
     */
    public boolean sendConfirmationRequest(String toNumber, String churchName) {
        String body = churchName + ": You have requested to receive SMS messages from us. "
                    + "Reply YES to confirm. Reply STOP to cancel. Msg & data rates may apply.";
        return send(toNumber, body);
    }

    /** Returns true if Twilio credentials are configured and the service is active. */
    public boolean isConfigured() { return configured; }
}
